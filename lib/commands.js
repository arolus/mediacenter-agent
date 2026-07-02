// Слушаем очередь команд devices/{id}/commands и выполняем (rescan, delete).
// Перенос файлов (seed/download) живёт отдельно в transfer.js.
const fs = require("fs");
const path = require("path");
const {
  collection, doc, getDocs, onSnapshot, query, where, updateDoc, deleteDoc, serverTimestamp
} = require("firebase/firestore");
const { syncLibrary, libIdFor } = require("./library");
const { mediaDirs, allDirs } = require("./media");

function watchCommands(ctx) {
  const { db, config } = ctx;
  const deviceId = config.device.id;
  const cmdCol = collection(db, "devices", deviceId, "commands");
  const pending = query(cmdCol, where("status", "==", "pending"));

  return onSnapshot(pending, (snap) => {
    snap.docChanges().forEach((ch) => {
      if (ch.type !== "added") return;
      handleCommand(ctx, ch.doc.id, ch.doc.data());
    });
  });
}

async function handleCommand(ctx, cmdId, cmd) {
  const { db, config } = ctx;
  const cmdRef = doc(db, "devices", config.device.id, "commands", cmdId);
  console.log("command:", cmd.type, cmdId);
  try {
    await updateDoc(cmdRef, { status: "acked" });
    if (cmd.type === "rescan") {
      await syncLibrary(ctx);
    } else if (cmd.type === "delete") {
      await deleteLocal(ctx, cmd);
    } else if (cmd.type === "normalize") {
      const n = await normalizeCollection(ctx);
      await updateDoc(cmdRef, { result: `переименовано ${n}` });
    } else if (cmd.type === "update") {
      // помечаем done и выходим — run.sh подтянет новый код и перезапустит
      await updateDoc(cmdRef, { status: "done", finishedAt: serverTimestamp() });
      require("./updater").triggerRestart("команда update");
      return;
    } else {
      throw new Error("неизвестная команда: " + cmd.type);
    }
    await updateDoc(cmdRef, { status: "done", finishedAt: serverTimestamp() });
  } catch (e) {
    console.error("command error:", e.message);
    await updateDoc(cmdRef, { status: "error", error: e.message }).catch(() => {});
  }
}

// «Упорядочить»: разложить коллекцию по местам.
// 1. Мультфильмы (сервер проставил animation=true по жанру TMDb) переезжают в Cartoons.
// 2. Сериал = ОДНА папка: «Series/Название (Год)/…». Распознанные серии переименовываются в
//    «Название SxxEyy.ext»; нераспознанные (но сгруппированные по выведенному названию/номеру
//    серии) просто собираются в папку сериала, имя файла не трогаем.
// 3. Распознанные фильмы → «Название (Год).ext» в корне своей папки.
// Затем подчистка пустых подпапок и рескан (сервер до-распознает переехавшее).
function sanitizeName(s) {
  return String(s).replace(/[\\/:*?"<>|]+/g, " ").replace(/\s+/g, " ").trim();
}
// Куда файл должен попасть: СЕРИЙНОСТЬ ВАЖНЕЕ АНИМАЦИИ — сериалы (в т.ч. мультсериалы вроде
// Тома и Джерри) живут в Series; в Cartoons только полнометражные мультфильмы.
function desiredType(it) {
  if (isSeriesLike(it)) return "series";
  if (it.animation === true) return "cartoon";
  return it.type || "movie";
}
const isSeriesLike = (it) => {
  const cat = String(it.catalogId || "");
  if (cat.startsWith("tv_")) return true;
  if (cat.startsWith("movie_")) return false; // распознанный ФИЛЬМ — не серия, даже в подпапке
  return it.season != null || it.episode != null || !!it.seriesDir;
};

// Удаляет опустевшие подпапки внутри root (сам root не трогает). Возвращает число оставшихся записей.
function pruneEmpty(root) {
  let entries;
  try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch (_) { return 1; }
  let left = 0;
  for (const e of entries) {
    const p = path.join(root, e.name);
    if (e.isDirectory()) {
      if (pruneEmpty(p) === 0) { try { fs.rmdirSync(p); continue; } catch (_) {} }
      left++;
    } else left++;
  }
  return left;
}

async function normalizeCollection(ctx) {
  const { db, config } = ctx;
  const dirs = mediaDirs(config);
  const inMedia = (p) => allDirs(config).some((root) => {
    const r = path.resolve(root); const rp = path.resolve(p);
    return rp === r || rp.startsWith(r + path.sep);
  });
  const snap = await getDocs(collection(db, "devices", config.device.id, "library"));
  let renamed = 0;
  for (const d of snap.docs) {
    const it = d.data();
    if (!it.filePath || !fs.existsSync(it.filePath) || !inMedia(it.filePath)) continue;
    const t = desiredType(it);
    const ext = path.extname(it.filePath);
    let targetDir, targetName;
    if (isSeriesLike(it)) {
      if (!it.title) continue;
      // папка сериала: «Название (Год)»; серия с распознанным номером — «Название SxxEyy.ext»
      targetDir = path.join(dirs[t] || dirs.movie, sanitizeName(it.title + (it.year ? ` (${it.year})` : "")));
      targetName = (it.tmdbId && it.episode != null)
        ? sanitizeName(`${it.title} S${String(it.season || 1).padStart(2, "0")}E${String(it.episode).padStart(2, "0")}`) + ext
        : path.basename(it.filePath);
    } else {
      if (!it.tmdbId) continue; // одиночные фильмы переименовываем только распознанные
      targetDir = dirs[t] || dirs.movie;
      targetName = sanitizeName(it.title + (it.year ? ` (${it.year})` : "")) + ext;
    }
    const target = path.join(targetDir, targetName);
    if (path.resolve(target) === path.resolve(it.filePath)) continue; // уже на месте
    if (fs.existsSync(target) || !inMedia(target)) continue;          // не перезатираем
    try {
      fs.mkdirSync(targetDir, { recursive: true });
      fs.renameSync(it.filePath, target);
      renamed++;
      console.log(`normalize: ${path.basename(it.filePath)} → ${path.join(path.basename(targetDir), targetName)}`);
    } catch (e) { console.error("normalize rename:", e.message); }
  }
  for (const root of Object.values(dirs)) pruneEmpty(root); // убрать опустевшие обёртки
  await syncLibrary(ctx); // библиотека переедет на новые пути (сервер до-распознает при необходимости)
  console.log(`normalize: перемещено/переименовано ${renamed}`);
  return renamed;
}

async function deleteLocal(ctx, cmd) {
  const { db, config } = ctx;
  // Безопасность: удаляем только внутри медиапапок (Movies/Series/Cartoons).
  const resolved = path.resolve(cmd.filePath || "");
  const allowed = allDirs(config).some((root) => {
    const r = path.resolve(root);
    return resolved === r || resolved.startsWith(r + path.sep);
  });
  if (!allowed) throw new Error("путь вне медиапапок: " + cmd.filePath);
  if (fs.existsSync(resolved)) {
    fs.rmSync(resolved, { recursive: true, force: true });
    console.log("command: удалён файл", resolved);
  }
  const libId = cmd.libId || libIdFor(resolved);
  await deleteDoc(doc(db, "devices", config.device.id, "library", libId)).catch(() => {});
}

module.exports = { watchCommands };
