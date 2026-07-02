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
// Сериал — это не менее 8 серий; TMDb-вердикт (tv_/movie_) важнее любых эвристик.
const MIN_EPISODES = 8;
const groupKey = (it) => (it.tmdbId ? "t:" + it.tmdbId : "n:" + String(it.title || "").toLowerCase().trim());

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
  const all = snap.docs.map((d) => d.data());
  // Сколько потенциальных серий у каждого названия: сериалом группа станет только при ≥MIN_EPISODES.
  const groupCount = new Map();
  for (const it of all) {
    if (!it.title) continue;
    if (String(it.catalogId || "").startsWith("tv_") || it.episode != null || it.seriesDir) {
      groupCount.set(groupKey(it), (groupCount.get(groupKey(it)) || 0) + 1);
    }
  }
  const seriesLike = (it) => {
    const cat = String(it.catalogId || "");
    if (cat.startsWith("tv_")) return true;      // TMDb говорит: сериал
    if (cat.startsWith("movie_")) return false;  // TMDb говорит: фильм (даже если лежит в подпапке)
    if (it.episode == null && !it.seriesDir) return false;
    return (groupCount.get(groupKey(it)) || 0) >= MIN_EPISODES;
  };
  const desired = (it) => {
    if (seriesLike(it)) return "series";
    if (it.animation === true) return "cartoon";
    if (it.type === "series") return "movie";    // не-сериалу нечего делать в Series
    return it.type || "movie";
  };
  let renamed = 0;
  for (const it of all) {
    if (!it.filePath || !fs.existsSync(it.filePath) || !inMedia(it.filePath)) continue;
    const t = desired(it);
    const ext = path.extname(it.filePath);
    let targetDir, targetName;
    if (seriesLike(it)) {
      if (!it.title) continue;
      // папка сериала: «Название (Год)»; серия с распознанным номером — «Название SxxEyy.ext»
      targetDir = path.join(dirs[t] || dirs.movie, sanitizeName(it.title + (it.year ? ` (${it.year})` : "")));
      targetName = (it.tmdbId && it.episode != null)
        ? sanitizeName(`${it.title} S${String(it.season || 1).padStart(2, "0")}E${String(it.episode).padStart(2, "0")}`) + ext
        : path.basename(it.filePath);
    } else {
      targetDir = dirs[t] || dirs.movie;
      // Распознанный — аккуратное имя; нераспознанный переносим как есть, только если он
      // лежит не в своей папке (напр. фильм застрял в Series) — иначе не трогаем.
      if (it.tmdbId && it.title) targetName = sanitizeName(it.title + (it.year ? ` (${it.year})` : "")) + ext;
      else if ((it.type || "movie") !== t) targetName = path.basename(it.filePath);
      else continue;
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
