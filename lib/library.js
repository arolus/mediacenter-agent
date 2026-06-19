// Синхронизация локальной медиатеки с Firestore: скан -> распознавание -> запись
// в devices/{deviceId}/library. libId детерминированный (хэш пути) — повторный скан
// обновляет записи, а не плодит дубли. Распознаём только новые файлы (экономим TMDb).
const crypto = require("crypto");
const fs = require("fs");
const {
  collection, doc, getDocs, setDoc, deleteDoc, serverTimestamp
} = require("firebase/firestore");
const { scanMedia } = require("./scan");
const { recognize } = require("./recognizer");

const libIdFor = (filePath) => crypto.createHash("sha1").update(filePath).digest("hex").slice(0, 20);

async function syncLibrary(ctx) {
  const { db, config } = ctx;
  const deviceId = config.device.id;
  const libCol = collection(db, "devices", deviceId, "library");

  const files = scanMedia(config.mediaDir, config.videoExtensions);
  console.log(`library: найдено ${files.length} файлов в ${config.mediaDir}`);

  // Текущее состояние в Firestore
  const snap = await getDocs(libCol);
  const existing = new Map();
  snap.forEach((d) => existing.set(d.id, d.data()));

  const seen = new Set();

  for (const f of files) {
    const id = libIdFor(f.filePath);
    seen.add(id);
    const prev = existing.get(id);

    // Уже есть и имя файла не поменялось — не трогаем (не дёргаем TMDb лишний раз).
    if (prev && prev.fileName === f.fileName) {
      // обновим только размер, если изменился
      if (prev.sizeBytes !== f.sizeBytes) {
        await setDoc(doc(libCol, id), { sizeBytes: f.sizeBytes }, { merge: true });
      }
      continue;
    }

    const rec = await recognize(f.fileName, config);
    await setDoc(doc(libCol, id), {
      ...rec,
      filePath: f.filePath,
      fileName: f.fileName,
      sizeBytes: f.sizeBytes,
      updatedAt: serverTimestamp()
    });
    console.log(`library: ${rec.recognized.padEnd(9)} | ${f.fileName} -> ${rec.title}`);
  }

  // Удаляем из Firestore то, чего больше нет на диске
  for (const id of existing.keys()) {
    if (!seen.has(id)) {
      await deleteDoc(doc(libCol, id));
      console.log("library: удалён отсутствующий файл", id);
    }
  }
}

// Добавить одну запись (используется после успешного P2P-приёма файла).
async function addLibraryFile(ctx, filePath, meta = {}) {
  const { db, config } = ctx;
  const libCol = collection(db, "devices", config.device.id, "library");
  const id = libIdFor(filePath);
  let size = 0;
  try { size = fs.statSync(filePath).size; } catch (_) {}
  const rec = await recognize(meta.fileName || filePath.split("/").pop(), config);
  await setDoc(doc(libCol, id), {
    ...rec,
    // если перенос принёс уже распознанные метаданные из каталога — предпочесть их
    ...(meta.catalogId ? { recognized: "matched", catalogId: meta.catalogId, title: meta.title, year: meta.year || null, poster: meta.poster || null, candidates: [] } : {}),
    filePath,
    fileName: meta.fileName || filePath.split("/").pop(),
    sizeBytes: size,
    updatedAt: serverTimestamp()
  });
}

module.exports = { syncLibrary, addLibraryFile, libIdFor };
