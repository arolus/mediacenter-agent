// Слушаем очередь команд devices/{id}/commands и выполняем (rescan, delete).
// Перенос файлов (seed/download) живёт отдельно в transfer.js.
const fs = require("fs");
const {
  collection, doc, onSnapshot, query, where, updateDoc, deleteDoc, serverTimestamp
} = require("firebase/firestore");
const { syncLibrary, libIdFor } = require("./library");

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

async function deleteLocal(ctx, cmd) {
  const { db, config } = ctx;
  // Безопасность: удаляем только внутри mediaDir (как assertAllowed в kodi/movies.js).
  const path = require("path");
  const resolved = path.resolve(cmd.filePath || "");
  const root = path.resolve(config.mediaDir);
  if (!(resolved === root || resolved.startsWith(root + path.sep))) {
    throw new Error("путь вне медиапапки: " + cmd.filePath);
  }
  if (fs.existsSync(resolved)) {
    fs.rmSync(resolved, { recursive: true, force: true });
    console.log("command: удалён файл", resolved);
  }
  const libId = cmd.libId || libIdFor(resolved);
  await deleteDoc(doc(db, "devices", config.device.id, "library", libId)).catch(() => {});
}

module.exports = { watchCommands };
