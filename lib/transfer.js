// P2P-перенос файлов между устройствами через WebTorrent (чистый BitTorrent: TCP/uTP/DHT,
// без wrtc — оба агента это Node). Хаб (Firestore) передаёт только magnet-ссылку.
//
// Роли по документу transfers/{id}:
//   source == я && status=="requested"  -> раздаю файл, пишу magnet + status="seeding"
//   target == я && status=="seeding"    -> качаю по magnet, шлю прогресс, по done -> status="done"
const fs = require("fs");
const path = require("path");
const os = require("os");
const WebTorrent = require("webtorrent");
const {
  collection, doc, onSnapshot, updateDoc, serverTimestamp
} = require("firebase/firestore");
const { addLibraryFile } = require("./library");
const { dirForType } = require("./media");

// Первый внешний IPv4 (для прямого коннекта в пределах одной сети).
function lanIp() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const i of ifaces || []) {
      if (i.family === "IPv4" && !i.internal) return i.address;
    }
  }
  return "127.0.0.1";
}

function watchTransfers(ctx) {
  const { db, config } = ctx;
  const myId = config.device.id;
  // torrentPort фиксируем из конфига, чтобы сидер мог опубликовать свой адрес,
  // а приёмник подключился напрямую (надёжно в одной сети, мимо трекеров/DHT).
  const torrentPort = config.torrentPort || undefined;
  // ПРИВАТНОСТЬ: раздача только внутри своей сети. Отключаем DHT и публичные трекеры —
  // никакого анонса наружу; приёмник находит источник по прямому LAN-адресу (addPeer).
  const client = new WebTorrent({ dht: false, tracker: false, lsd: true, ...(torrentPort ? { torrentPort } : {}) });
  client.on("error", (e) => console.error("webtorrent error:", e.message));

  const seeding = new Map();      // transferId -> torrent
  const downloading = new Map();  // transferId -> torrent
  const lastWrite = new Map();    // transferId -> ms (троттлинг прогресса)

  const tRef = (id) => doc(db, "transfers", id);

  const unsub = onSnapshot(collection(db, "transfers"), (snap) => {
    snap.docChanges().forEach((ch) => {
      const id = ch.doc.id;
      const t = ch.doc.data();

      if (ch.type === "removed") {
        seeding.get(id)?.destroy(); seeding.delete(id);
        downloading.get(id)?.destroy(); downloading.delete(id);
        return;
      }

      // Источник закончил/упал — прекращаем раздачу
      if ((t.status === "done" || t.status === "error") && seeding.has(id)) {
        seeding.get(id).destroy();
        seeding.delete(id);
      }

      // Роль источника
      if (t.source === myId && t.status === "requested" && !seeding.has(id)) {
        startSeed(id, t);
      }
      // Роль приёмника
      if (t.target === myId && t.status === "seeding" && t.magnet && !downloading.has(id)) {
        startDownload(id, t);
      }
    });
  });

  function startSeed(id, t) {
    if (!t.filePath || !fs.existsSync(t.filePath)) {
      updateDoc(tRef(id), { status: "error", error: "файл не найден на источнике", updatedAt: serverTimestamp() });
      return;
    }
    console.log("transfer: раздаю", t.title, "->", t.target);
    seeding.set(id, null); // занять слот, чтобы не стартануть дважды
    client.seed(t.filePath, { announce: [] }, (torrent) => {
      seeding.set(id, torrent);
      // Адрес для прямого подключения приёмника (если знаем свой порт).
      const seederAddr = torrentPort ? `${lanIp()}:${torrentPort}` : null;
      updateDoc(tRef(id), {
        magnet: torrent.magnetURI,
        seederAddr,
        status: "seeding",
        updatedAt: serverTimestamp()
      }).catch((e) => console.error("transfer seed write:", e.message));
    });
  }

  function startDownload(id, t) {
    console.log("transfer: качаю", t.title, "от", t.source);
    downloading.set(id, null);
    updateDoc(tRef(id), { status: "downloading", updatedAt: serverTimestamp() }).catch(() => {});

    const dir = dirForType(config, t.type || "movie"); // кладём в Movies/Series/Cartoons по типу
    const torrent = client.add(t.magnet, { path: dir, announce: [] }); // без публичных трекеров — только прямой пир
    downloading.set(id, torrent);

    // ВАЖНО: addPeer сразу на torrent (infohash известен из magnet), НЕ внутри
    // колбэка готовности метаданных — иначе без пиров метадата не придёт и колбэк
    // никогда не вызовется. Прямой коннект к сидеру надёжен в пределах одной сети.
    if (t.seederAddr) {
      try { torrent.addPeer(t.seederAddr); console.log("transfer: addPeer", t.seederAddr); }
      catch (e) { console.error("addPeer:", e.message); }
    }

    {
      torrent.on("download", () => {
        const now = Date.now();
        if (now - (lastWrite.get(id) || 0) < 2000) return; // троттлинг ~2с
        lastWrite.set(id, now);
        updateDoc(tRef(id), {
          progress: torrent.progress,
          speed: torrent.downloadSpeed,
          updatedAt: serverTimestamp()
        }).catch(() => {});
      });

      torrent.on("done", async () => {
        const file = torrent.files[0];
        const destPath = path.join(dir, file.path);
        console.log("transfer: принято", destPath);
        try {
          // переносим тип и TMDb-мету от источника (TMDb здесь не дёргаем)
          await addLibraryFile(ctx, destPath, {
            fileName: path.basename(file.path),
            type: t.type || "movie",
            title: t.title, year: t.year,
            tmdbId: t.tmdbId, catalogId: t.catalogId, poster: t.poster,
            backdrop: t.backdrop, overview: t.overview, cast: t.cast, rating: t.rating
          });
        } catch (e) {
          console.error("transfer: ошибка добавления в библиотеку:", e.message);
        }
        await updateDoc(tRef(id), {
          progress: 1, speed: 0, status: "done", updatedAt: serverTimestamp()
        }).catch(() => {});
        // освобождаем ресурсы приёмника (файл уже на диске)
        torrent.destroy();
        downloading.delete(id);
      });

      torrent.on("error", (e) => {
        updateDoc(tRef(id), { status: "error", error: e.message, updatedAt: serverTimestamp() }).catch(() => {});
        downloading.delete(id);
      });
    }
  }

  return () => { unsub(); client.destroy(); };
}

module.exports = { watchTransfers };
