// Синхронизация локальной медиатеки с Firestore. Сканируем три папки (Movies/Series/Cartoons);
// ТИП берётся из папки. TMDb при скане НЕ дёргаем (только parseName). Уже привязанная TMDb-мета
// (постер/описание/актёры от скачивания с торрента) при рескане сохраняется.
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const {
  collection, doc, getDocs, setDoc, deleteDoc, serverTimestamp
} = require("firebase/firestore");
const { scanMedia } = require("./scan");
const { parseName } = require("./recognizer");
const { mediaDirs } = require("./media");

const libIdFor = (filePath) => crypto.createHash("sha1").update(filePath).digest("hex").slice(0, 20);

// Сериал — это не менее 8 серий. Меньше (дилогии, трилогии, случайные номера) — фильмы.
const MIN_SERIES_FILES = 8;
// «Название 151» → { base: "Название", num: 151 } (год за номер не считаем), иначе null.
function stripTail(t) {
  const m = String(t).match(/^(.*?)[\s._-]+(\d{1,4})$/);
  if (!m || (Number(m[2]) >= 1900 && Number(m[2]) <= 2099)) return null;
  return { base: m[1].replace(/[\s._-]+$/, ""), num: Number(m[2]) };
}

const TMDB_FIELDS = ["tmdbId", "catalogId", "poster", "backdrop", "backdrops", "overview", "cast", "rating", "animation", "collection"];
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
    // Считаем файлы в подпапках первого уровня: папка = сериал, только если в ней ≥MIN_SERIES_FILES.
    // Пара файлов в папке-обёртке («Шрэк (2004)/…») — не сериал.
    const dirCounts = new Map();
    // И то же для плоских файлов с хвостовым номером: «серия» — только если у базового
    // названия ≥MIN_SERIES_FILES файлов («Том и Джерри..1…151»), иначе это «Шрэк 2».
    const baseCounts = new Map();
    for (const f of files) {
      const seg = path.relative(dir, f.filePath).split(path.sep);
      if (seg.length > 1) { dirCounts.set(seg[0], (dirCounts.get(seg[0]) || 0) + 1); continue; }
      if (type === "series" || type === "cartoon") {
        const st = stripTail(parseName(f.fileName).title);
        if (st) baseCounts.set(st.base.toLowerCase(), (baseCounts.get(st.base.toLowerCase()) || 0) + 1);
      }
    }
    for (const f of files) {
      const id = libIdFor(f.filePath);
      seen.add(id);
      const prev = existing.get(id);
      // Подпапка первого уровня в Series/Cartoons = «папка сериала»: все файлы в ней — серии одного шоу.
      const segs = path.relative(dir, f.filePath).split(path.sep);
      const seriesDir = segs.length > 1 && (dirCounts.get(segs[0]) || 0) >= MIN_SERIES_FILES ? segs[0] : null;
      // Тип берётся из папки. Распознавание (TMDb) делает СЕРВЕР (Cloud Function), не агент —
      // поэтому просто пишем распарсенное имя. Неизменившийся файл пропускаем.
      if (prev && prev.fileName === f.fileName && prev.type === type && (prev.seriesDir ?? null) === seriesDir) {
        if (prev.sizeBytes !== f.sizeBytes) await setDoc(doc(libCol, id), { sizeBytes: f.sizeBytes }, { merge: true });
        // бэкофилл: у старых доков фиксируем оригинальное имя (контекст для «Исправить»)
        if (!prev.originalName) await setDoc(doc(libCol, id), { originalName: f.fileName }, { merge: true });
        continue;
      }
      const p = parseName(f.fileName);
      let { title, year, season, episode } = p;
      if (type === "series" || type === "cartoon") {
        // Серия без SxxExx: хвостовой номер в имени («…классики..151.avi» → серия 151, название
        // без хвоста). Только внутри папки сериала ИЛИ когда у базового названия ≥MIN_SERIES_FILES
        // плоских файлов. «Шрэк 2» (одиночный сиквел) серией не считается.
        if (episode == null) {
          const st = stripTail(p.title);
          if (st && (seriesDir || (baseCounts.get(st.base.toLowerCase()) || 0) >= MIN_SERIES_FILES)) {
            episode = st.num;
            if (!seriesDir) title = st.base;
          }
        }
        // Папка сериала важнее имени файла: название шоу = имя папки.
        if (seriesDir) {
          const pd = parseName(seriesDir);
          title = pd.title;
          year = pd.year ?? year;
        }
      }
      // При переобработке сохраняем уже проставленную сервером TMDb-мету и торрент-данные.
      // Если выведенное название изменилось, а tmdbId так и нет — сбрасываем tmdbTried:
      // сервер попробует распознать заново уже по новому названию.
      const keep = prev
        ? { ...pickTorrent(prev),
            ...(prev.tmdbId ? { ...pickTmdb(prev), title: prev.title, year: prev.year } : {}),
            ...(prev.tmdbTried && prev.title === title ? { tmdbTried: true } : {}),
            ...(prev.watched ? { watched: true, watchedAt: prev.watchedAt ?? null } : {}) }
        : {};
      await setDoc(doc(libCol, id), {
        type, seriesDir,
        title, year: year ?? null, season: season ?? null, episode: episode ?? null,
        filePath: f.filePath, fileName: f.fileName, sizeBytes: f.sizeBytes,
        // оригинальное имя файла живёт вечно: заготовку с ним оставляет normalize при
        // переименовании (см. commands.js), для новых файлов — текущее имя
        originalName: (prev && prev.originalName) || f.fileName,
        ...keep,
        updatedAt: serverTimestamp()
      });
      added++;
      console.log(`library: + ${type.padEnd(7)} | ${seriesDir ? seriesDir + "/" : ""}${f.fileName}`);
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
    originalName: fileName,
    ...pickTmdb(meta),
    ...pickTorrent(meta),
    updatedAt: serverTimestamp()
  });
}

module.exports = { syncLibrary, addLibraryFile, libIdFor };
