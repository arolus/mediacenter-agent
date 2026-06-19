// Тестовый помощник: создаёт документ transfers/{id} для переноса с источника на цель.
// Использование: node tools/create-transfer.js <config.json> <sourceId> <targetId>
// Имитирует кнопку «Отправить на устройство…» в дашборде.
const fs = require("fs");
const path = require("path");
const { initFirebase } = require("../lib/firebase");
const { collection, addDoc, getDocs, serverTimestamp } = require("firebase/firestore");

async function main() {
  const [configPath, sourceId, targetId] = process.argv.slice(2);
  if (!configPath || !sourceId || !targetId) {
    console.error("Usage: node tools/create-transfer.js <config.json> <sourceId> <targetId>");
    process.exit(1);
  }
  const config = JSON.parse(fs.readFileSync(path.resolve(configPath), "utf8"));
  const ctx = await initFirebase(config);

  // Берём первый файл из библиотеки источника
  const libSnap = await getDocs(collection(ctx.db, "devices", sourceId, "library"));
  if (libSnap.empty) { console.error("У источника пустая библиотека"); process.exit(1); }
  const item = { id: libSnap.docs[0].id, ...libSnap.docs[0].data() };
  console.log("Переношу:", item.title || item.fileName, "->", targetId);

  const ref = await addDoc(collection(ctx.db, "transfers"), {
    catalogId: item.catalogId || null,
    title: item.title || item.fileName,
    type: item.type || "movie",
    year: item.year || null,
    poster: item.poster || null,
    source: sourceId,
    sourceLibId: item.id,
    filePath: item.filePath,
    target: targetId,
    magnet: null,
    status: "requested",
    progress: 0,
    speed: 0,
    error: null,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });
  console.log("transfer создан:", ref.id);
  process.exit(0);
}

main().catch((e) => { console.error(e); process.exit(1); });
