// Синхронизация локальной медиатеки с Firestore. Сканируем три папки (Movies/Series/Cartoons);
// ТИП берётся из папки. TMDb при скане НЕ дёргаем (только parseName). Уже привязанная TMDb-мета
// (постер/описание/актёры от скачивания с торрента) при рескане сохраняется.
const crypto = require("crypto");
const fs = require("fs");
const {
  collection, doc, getDocs, setDoc, deleteDoc, serverTimestamp
} = require("firebase/firestore");
const { scanMedia } = require("./scan");
const { parseName } = require("./recognizer");
const { mediaDirs } = require("./media");

const libIdFor = (filePath) => crypto.createHash("sha1").update(filePath).digest("hex").slice(0, 20);

const TMDB_FIELDS = ["tmdbId", "catalogId", "poster", "backdrop", "overview", "cast", "rating"];
const TORRENT_FIELDS = ["magnet", "infoHash", "rutrackerTid", "rutrackerUrl"]; // торрент-данные скачанного
function pickFields(src, fields) {
  const out = {};
  for (const k of fields) if (src[k] !== undefined) out[k] = src[k];
  return out;
}
function pickTmdb(src) { return pickFields(src, TMDB_FIELDS); }
function pickTorrent(src) { return pickFields(src, TORRENT_FIELDS); }

async function syncLibrary(ctx) {
  const { db, config } = ctx;
  const libCol = collection(db, "devices", config.device.id, "library");
  const dirs = mediaDirs(config);

  const snap = await getDocs(libCol);
  const existing = new Map();
  snap.forEach((d) => existing.set(d.id, d.data()));
  const seen = new Set();

  let total = 0, added = 0, removed = 0;
  for (const [type, dir] of Object.entries(dirs)) {
    let files = [];
    try { files = scanMedia(dir, config.videoExtensions); } catch (_) {}
    total += files.length;
    for (const f of files) {
      const id = libIdFor(f.filePath);
      seen.add(id);
      const prev = existing.get(id);
      // Тип берётся из папки. Распознавание (TMDb) делает СЕРВЕР (Cloud Function), не агент —
      // поэтому просто пишем распарсенное имя. Неизменившийся файл пропускаем.
      if (prev && prev.fileName === f.fileName && prev.type === type) {
        if (prev.sizeBytes !== f.sizeBytes) await setDoc(doc(libCol, id), { sizeBytes: f.sizeBytes }, { merge: true });
        continue;
      }
      const p = parseName(f.fileName);
      // При переобработке сохраняем уже проставленную сервером TMDb-мету и торрент-данные.
      const keep = prev
        ? { ...pickTorrent(prev),
            ...(prev.tmdbId ? { ...pickTmdb(prev), title: prev.title, year: prev.year } : {}),
            ...(prev.tmdbTried ? { tmdbTried: true } : {}) }
        : {};
      await setDoc(doc(libCol, id), {
        type,
        title: p.title, year: p.year, season: p.season, episode: p.episode,
        filePath: f.filePath, fileName: f.fileName, sizeBytes: f.sizeBytes,
        ...keep,
        updatedAt: serverTimestamp()
      });
      added++;
      console.log(`library: + ${type.padEnd(7)} | ${f.fileName}`);
    }
  }

  for (const id of existing.keys()) {
    if (!seen.has(id)) { await deleteDoc(doc(libCol, id)); removed++; console.log("library: − удалён", id); }
  }

  if (added || removed) console.log(`library: изменения — добавлено ${added}, удалено ${removed} (всего ${total})`);
  return { total, added, removed };
}

// Добавить запись (после P2P-приёма или скачивания с торрента). meta может нести type + TMDb-мету.
async function addLibraryFile(ctx, filePath, meta = {}) {
  const { db, config } = ctx;
  const libCol = collection(db, "devices", config.device.id, "library");
  const id = libIdFor(filePath);
  let size = 0; try { size = fs.statSync(filePath).size; } catch (_) {}
  const fileName = meta.fileName || filePath.split("/").pop();
  const p = parseName(fileName);
  await setDoc(doc(libCol, id), {
    type: meta.type || "movie",
    title: meta.title || p.title,
    year: meta.year ?? p.year,
    season: p.season, episode: p.episode,
    filePath, fileName, sizeBytes: size,
    ...pickTmdb(meta),
    ...pickTorrent(meta),
    updatedAt: serverTimestamp()
  });
}

module.exports = { syncLibrary, addLibraryFile, libIdFor };
