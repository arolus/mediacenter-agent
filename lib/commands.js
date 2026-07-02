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

// «Причесать»: переименовать РАСПОЗНАННЫЕ файлы в аккуратный вид «Название (Год).ext»
// (для сериалов + SxxExx) и убрать опустевшие папки-обёртки. Название берём из того, что
// проставил СЕРВЕР (tmdbId + title). Нераспознанные не трогаем. Затем рескан.
function sanitizeName(s) {
  return String(s).replace(/[\\/:*?"<>|]+/g, " ").replace(/\s+/g, " ").trim();
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
    if (!it.tmdbId || !it.title || !it.filePath) continue;      // только распознанные
    if (!fs.existsSync(it.filePath) || !inMedia(it.filePath)) continue;
    const ext = path.extname(it.filePath);
    let base = it.title + (it.year ? ` (${it.year})` : "");
    if (it.type === "series" && it.season) {
      base += ` S${String(it.season).padStart(2, "0")}` + (it.episode ? `E${String(it.episode).padStart(2, "0")}` : "");
    }
    const dir = dirs[it.type] || dirs.movie;
    const target = path.join(dir, sanitizeName(base) + ext);
    if (path.resolve(target) === path.resolve(it.filePath)) continue; // уже норм
    if (fs.existsSync(target) || !inMedia(target)) continue;          // не перезатираем
    try {
      fs.renameSync(it.filePath, target);
      renamed++;
      console.log(`normalize: ${path.basename(it.filePath)} → ${path.basename(target)}`);
      // убрать опустевшую папку-обёртку (если файл лежал в подпапке)
      const parent = path.dirname(it.filePath);
      if (path.resolve(parent) !== path.resolve(dir)) {
        try { if (!fs.readdirSync(parent).length) fs.rmdirSync(parent); } catch (_) {}
      }
    } catch (e) { console.error("normalize rename:", e.message); }
  }
  await syncLibrary(ctx); // библиотека переедет на новые пути (сервер до-распознает при необходимости)
  console.log(`normalize: переименовано ${renamed}`);
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
