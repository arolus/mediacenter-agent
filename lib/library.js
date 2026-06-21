// Синхронизация локальной медиатеки с Firestore. Сканируем три папки (Movies/Series/Cartoons);
// ТИП берётся из папки. TMDb при скане НЕ дёргаем (только parseName). Уже привязанная TMDb-мета
// (постер/описание/актёры от скачивания с торрента) при рескане сохраняется.
const crypto = require("crypto");
const fs = require("fs");
const {
  collection, doc, getDocs, setDoc, deleteDoc, serverTimestamp
} = require("firebase/firestore");
const { scanMedia } = require("./scan");
const { parseName, enrich, hasTmdb } = require("./recognizer");
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
      // Пропускаем неизменившийся файл, если он уже обогащён (есть tmdbId), уже пытались (tmdbTried)
      // или TMDb не настроен. Иначе — провалимся ниже и попробуем подтянуть метаданные.
      const enriched = prev && (prev.tmdbId || prev.tmdbTried);
      if (prev && prev.fileName === f.fileName && prev.type === type && (enriched || !hasTmdb(config))) {
        if (prev.sizeBytes !== f.sizeBytes) await setDoc(doc(libCol, id), { sizeBytes: f.sizeBytes }, { merge: true });
        continue;
      }
      const p = parseName(f.fileName);
      // extra = метаданные TMDb. Если файл уже был привязан (скачан с торрента) — сохраняем их;
      // иначе пробуем подтянуть из TMDb по названию (постер/описание/актёры для локальных файлов).
      let extra = {};
      if (prev && prev.tmdbId) {
        extra = { ...pickTmdb(prev), title: prev.title, year: prev.year };
      } else if (hasTmdb(config)) {
        try {
          const m = await enrich({ title: p.title, year: p.year, isSeries: type === "series" || p.isSeries }, config);
          if (m) extra = { tmdbId: m.tmdbId, catalogId: m.catalogId, poster: m.poster, backdrop: m.backdrop, overview: m.overview, cast: m.cast, rating: m.rating, title: m.title, year: m.year };
          else extra = { tmdbTried: true };   // не нашли — пометим, чтобы не дёргать TMDb каждый цикл
        } catch (_) { extra = { tmdbTried: true }; }
      }
      await setDoc(doc(libCol, id), {
        type,
        title: p.title, year: p.year, season: p.season, episode: p.episode,
        filePath: f.filePath, fileName: f.fileName, sizeBytes: f.sizeBytes,
        ...(prev ? pickTorrent(prev) : {}), // сохраняем торрент-данные при переобработке
        ...extra,
        updatedAt: serverTimestamp()
      });
      added++;
      console.log(`library: + ${type.padEnd(7)} | ${f.fileName}${extra.tmdbId ? " → " + extra.title : ""}`);
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
