// Тестовый помощник: создаёт документ searches/{id} (как кнопка «Искать» в дашборде)
// и ждёт результат. Использование:
//   node tools/create-search.js <config.json> "<фраза>" <дни> <категории через запятую>
const fs = require("fs");
const path = require("path");
const { initFirebase } = require("../lib/firebase");
const { collection, addDoc, doc, onSnapshot, serverTimestamp } = require("firebase/firestore");

async function main() {
  const [configPath, phrase = "", days = "7", cats = "movies,series,cartoons"] = process.argv.slice(2);
  const config = JSON.parse(fs.readFileSync(path.resolve(configPath), "utf8"));
  const ctx = await initFirebase(config);
  const ref = await addDoc(collection(ctx.db, "searches"), {
    phrase, days: Number(days), order: 10,
    categories: cats.split(",").map((s) => s.trim()).filter(Boolean),
    status: "requested", createdAt: serverTimestamp(), updatedAt: serverTimestamp()
  });
  console.log("search создан:", ref.id, "— жду результат…");
  onSnapshot(doc(ctx.db, "searches", ref.id), (snap) => {
    const d = snap.data();
    if (!d) return;
    if (d.status === "running") console.log("  …выполняется на", d.claimedBy);
    if (d.status === "done") {
      console.log(`  ГОТОВО: найдено ${d.count}, в доке ${d.results.length}`);
      d.results.slice(0, 5).forEach((t) => console.log(`   • [${t.seeds}🌱 ${t.size}] ${t.label} (${t.year}) — ${t.category}`));
      process.exit(0);
    }
    if (d.status === "error") { console.error("  ОШИБКА:", d.error); process.exit(1); }
  });
}
main().catch((e) => { console.error(e); process.exit(1); });
