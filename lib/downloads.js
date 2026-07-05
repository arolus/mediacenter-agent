// Скачивание торрента с rutracker на выбранное устройство.
// Документ downloads/{id}:
//   requested  -> СЕРВЕР (Cloud Function fetchTorrentFile) тянет .torrent -> torrentFile(base64), fetched
//   fetched    -> агент-цель (target) добавляет .torrent в WebTorrent, качает в mediaDir -> done
// Ноде доступ к rutracker не нужен: .torrent уже содержит трекеры rutracker.
const WebTorrent = require("webtorrent");
const path = require("path");
const {
  collection, doc, onSnapshot, updateDoc, setDoc, deleteDoc, serverTimestamp
} = require("firebase/firestore");
const { addLibraryFile, libIdFor } = require("./library");
const { parseName } = require("./recognizer");
const { dirForType } = require("./media");

function watchDownloads(ctx) {
  const { db, config } = ctx;
  const myId = config.device.id;
  // Отдельный клиент со случайным портом (transfer.js использует config.torrentPort).
  // Публичный контент, но раздающим быть не хотим: отдача зарезана до минимума
  // (в ноль нельзя — тем же исходящим каналом уходят протокольные запросы кусков,
  // и download встал бы тоже), а по готовности торрент уничтожается мгновенно.
  const client = new WebTorrent({ uploadLimit: 64 * 1024 });
  client.on("error", (e) => console.error("downloads webtorrent:", e.message));

  const downloading = new Map();
  const lastWrite = new Map();
  const ref = (id) => doc(db, "downloads", id);

  const unsub = onSnapshot(collection(db, "downloads"), (snap) => {
    snap.docChanges().forEach((ch) => {
      const id = ch.doc.id;
      const t = ch.doc.data();
      if (ch.type === "removed") { downloading.get(id)?.destroy(); downloading.delete(id); return; }

      // .torrent добывает сервер; нода — только «цель»: качает по готовому .torrent.
      // "downloading" тоже подхватываем — возобновление после перезапуска агента
      // (ребут/обновление): torrentFile ещё в доке, WebTorrent докачает недостающие куски.
      if (t.target === myId && (t.status === "fetched" || t.status === "downloading") &&
          t.torrentFile && !downloading.has(id)) {
        startDownload(id, t);
      }
    });
  });

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
      const fileName = path.basename(movie.path);
      const { magnetURI, infoHash } = torrent;
      // Раздачу глушим СРАЗУ, до записей в Firestore: они занимают секунды, а публичный
      // трекер уже объявил нас сидом — посторонние не должны качать фильм с телефона.
      torrent.destroy();
      downloading.delete(id);
      console.log("download: готово", destPath, "— раздача остановлена");
      try {
        // Распознавание делает СЕРВЕР — здесь только пишем файл + торрент-данные.
        const host = (config.rutracker && config.rutracker.hostname) || "https://rutracker.org";
        const rutrackerUrl = t.tid ? `${host}/forum/viewtopic.php?t=${t.tid}` : null;
        await addLibraryFile(ctx, destPath, {
          type,
          fileName,
          title: t.title || parseName(fileName).title,
          year: t.year || null,
          // торрент-данные скачанного файла: magnet/infoHash/tid + ссылка на форум
          magnet: magnetURI, infoHash, rutrackerTid: t.tid || null, rutrackerUrl
        });
        // Сам .torrent-файл — в отдельную подколлекцию (чтобы можно было переотдать/обновить).
        if (t.torrentFile) {
          await setDoc(doc(db, "devices", config.device.id, "torrents", libIdFor(destPath)), {
            torrentFile: t.torrentFile, magnet: magnetURI, infoHash,
            tid: t.tid || null, rutrackerUrl, savedAt: serverTimestamp()
          }).catch((e) => console.error("save .torrent:", e.message));
        }
      } catch (e) { console.error("download library:", e.message); }
      // Задача-загрузка выполнена: файл в библиотеке, .torrent сохранён в devices/{id}/torrents,
      // раздача остановлена (destroy выше). Удаляем downloads-док, чтобы он не висел в «Активных»
      // дашборда с застывшим speed (выглядит как «идёт раздача», хотя её нет).
      await deleteDoc(ref(id)).catch((e) => console.error("cleanup download doc:", e.message));
    });
    torrent.on("error", (e) => {
      updateDoc(ref(id), { status: "error", error: e.message, updatedAt: serverTimestamp() }).catch(() => {});
      downloading.delete(id);
    });
  }

  return () => { unsub(); client.destroy(); };
}

module.exports = { watchDownloads };
