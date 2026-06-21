// Скачивание торрента с rutracker на выбранное устройство.
// Документ downloads/{id}:
//   requested  -> агент с rutracker «захватывает», тянет .torrent (dl.php) -> torrentFile(base64), fetched
//   fetched    -> агент-цель (target) добавляет .torrent в WebTorrent, качает в mediaDir -> done
// Так цель может НЕ иметь доступа к rutracker: .torrent уже содержит трекеры rutracker.
const WebTorrent = require("webtorrent");
const path = require("path");
const {
  collection, doc, onSnapshot, runTransaction, updateDoc, setDoc, serverTimestamp
} = require("firebase/firestore");
const rutracker = require("./rutracker");
const { addLibraryFile, libIdFor } = require("./library");
const { parseName, enrich } = require("./recognizer");
const { dirForType } = require("./media");

const MAX_TORRENT_B64 = 700 * 1024; // лимит на размер .torrent в Firestore-доке

function watchDownloads(ctx) {
  const { db, config } = ctx;
  const myId = config.device.id;
  const canFetch = !!(config.rutracker && config.rutracker.user);
  // Отдельный клиент со случайным портом (transfer.js использует config.torrentPort).
  const client = new WebTorrent();
  client.on("error", (e) => console.error("downloads webtorrent:", e.message));

  const fetching = new Set();
  const downloading = new Map();
  const lastWrite = new Map();
  const ref = (id) => doc(db, "downloads", id);

  const unsub = onSnapshot(collection(db, "downloads"), (snap) => {
    snap.docChanges().forEach((ch) => {
      const id = ch.doc.id;
      const t = ch.doc.data();
      if (ch.type === "removed") { downloading.get(id)?.destroy(); downloading.delete(id); return; }

      // Роль «добытчик»: тянем .torrent с rutracker.
      if (canFetch && t.status === "requested" && !fetching.has(id)) {
        fetching.add(id);
        fetchTorrent(id, t).finally(() => fetching.delete(id));
      }
      // Роль «цель»: качаем по .torrent.
      if (t.target === myId && t.status === "fetched" && t.torrentFile && !downloading.has(id)) {
        startDownload(id, t);
      }
    });
  });

  async function fetchTorrent(id, t) {
    const claimed = await runTransaction(db, async (tx) => {
      const s = await tx.get(ref(id));
      if (!s.exists() || s.data().status !== "requested") return false;
      tx.update(ref(id), { status: "fetching", fetchedBy: myId, updatedAt: serverTimestamp() });
      return true;
    }).catch(() => false);
    if (!claimed) return;

    console.log("download: тяну .torrent с rutracker, tid", t.tid);
    try {
      let buf = rutracker.download(config.rutracker, t.tid);
      if (!buf || buf.length < 100) { await rutracker.login(config.rutracker); buf = rutracker.download(config.rutracker, t.tid); }
      const head = buf.toString("latin1", 0, 200);
      if (!head.startsWith("d") || !/announce|infohash|info/i.test(head)) {
        throw new Error("rutracker не отдал .torrent (нужен логин?)");
      }
      const b64 = buf.toString("base64");
      if (b64.length > MAX_TORRENT_B64) throw new Error("слишком большой .torrent для передачи через Firestore");
      await updateDoc(ref(id), { torrentFile: b64, status: "fetched", updatedAt: serverTimestamp() });
      console.log("download: .torrent получен (", buf.length, "байт)");
    } catch (e) {
      console.error("download fetch error:", e.message);
      await updateDoc(ref(id), { status: "error", error: e.message, updatedAt: serverTimestamp() }).catch(() => {});
    }
  }

  function startDownload(id, t) {
    downloading.set(id, null);
    const type = t.type || "movie";
    const dir = dirForType(config, type);          // Movies/Series/Cartoons по типу
    console.log("download: качаю", t.title, `(${type})`, "в", dir);
    updateDoc(ref(id), { status: "downloading", updatedAt: serverTimestamp() }).catch(() => {});
    const torrentBuf = Buffer.from(t.torrentFile, "base64");
    const torrent = client.add(torrentBuf, { path: dir });
    downloading.set(id, torrent);

    torrent.on("download", () => {
      const now = Date.now();
      if (now - (lastWrite.get(id) || 0) < 2000) return;
      lastWrite.set(id, now);
      updateDoc(ref(id), { progress: torrent.progress, speed: torrent.downloadSpeed, updatedAt: serverTimestamp() }).catch(() => {});
    });
    torrent.on("done", async () => {
      const movie = torrent.files.find((f) => /\.(mkv|mp4|avi|mov|wmv|m4v|mpg|mpeg)$/i.test(f.path)) || torrent.files[0];
      const destPath = path.join(dir, movie.path);
      console.log("download: готово", destPath);
      try {
        // TMDb-обогащение ТОЛЬКО здесь (добавление с торрента): постер, описание, актёры.
        const p = parseName(path.basename(movie.path));
        const isSeries = type === "series" || p.isSeries;
        const meta = await enrich({ title: t.title || p.title, year: t.year || p.year, isSeries }, config);
        const host = (config.rutracker && config.rutracker.hostname) || "https://rutracker.org";
        const rutrackerUrl = t.tid ? `${host}/forum/viewtopic.php?t=${t.tid}` : null;
        await addLibraryFile(ctx, destPath, {
          type,
          fileName: path.basename(movie.path),
          title: meta?.title || t.title || p.title,
          year: meta?.year ?? t.year ?? p.year,
          // торрент-данные скачанного файла: magnet/infoHash/tid + ссылка на форум
          magnet: torrent.magnetURI, infoHash: torrent.infoHash, rutrackerTid: t.tid || null, rutrackerUrl,
          ...(meta ? { tmdbId: meta.tmdbId, catalogId: meta.catalogId, poster: meta.poster, backdrop: meta.backdrop, overview: meta.overview, cast: meta.cast, rating: meta.rating } : {})
        });
        // Сам .torrent-файл — в отдельную подколлекцию (чтобы можно было переотдать/обновить).
        if (t.torrentFile) {
          await setDoc(doc(db, "devices", config.device.id, "torrents", libIdFor(destPath)), {
            torrentFile: t.torrentFile, magnet: torrent.magnetURI, infoHash: torrent.infoHash,
            tid: t.tid || null, rutrackerUrl, savedAt: serverTimestamp()
          }).catch((e) => console.error("save .torrent:", e.message));
        }
      } catch (e) { console.error("download library:", e.message); }
      await updateDoc(ref(id), { progress: 1, speed: 0, status: "done", updatedAt: serverTimestamp() }).catch(() => {});
      torrent.destroy();
      downloading.delete(id);
    });
    torrent.on("error", (e) => {
      updateDoc(ref(id), { status: "error", error: e.message, updatedAt: serverTimestamp() }).catch(() => {});
      downloading.delete(id);
    });
  }

  return () => { unsub(); client.destroy(); };
}

module.exports = { watchDownloads };
