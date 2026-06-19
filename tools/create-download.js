// Тест: создаёт downloads/{id} (как кнопка «Скачать») и следит за статусом.
// node tools/create-download.js <config.json> <tid> <targetDeviceId> "<title>"
const fs = require("fs");
const path = require("path");
const { initFirebase } = require("../lib/firebase");
const { collection, addDoc, doc, onSnapshot, serverTimestamp } = require("firebase/firestore");

async function main() {
  const [configPath, tid, target, title = "тест"] = process.argv.slice(2);
  const config = JSON.parse(fs.readFileSync(path.resolve(configPath), "utf8"));
  const ctx = await initFirebase(config);
  const ref = await addDoc(collection(ctx.db, "downloads"), {
    tid: String(tid), title, year: null, poster: null, target,
    status: "requested", torrentFile: null, progress: 0, speed: 0, error: null,
    createdAt: serverTimestamp(), updatedAt: serverTimestamp()
  });
  console.log("download создан:", ref.id, "target:", target);
  onSnapshot(doc(ctx.db, "downloads", ref.id), (snap) => {
    const d = snap.data(); if (!d) return;
    const tf = d.torrentFile ? `${d.torrentFile.length}b64` : "—";
    console.log(`  status=${d.status} torrentFile=${tf} progress=${Math.round((d.progress||0)*100)}%${d.error ? " err="+d.error : ""}`);
    if (d.status === "fetched") { console.log("✓ .torrent добыт; цель офлайн — качать некому. Готово."); process.exit(0); }
    if (d.status === "error") process.exit(1);
  });
}
main().catch((e) => { console.error(e); process.exit(1); });
