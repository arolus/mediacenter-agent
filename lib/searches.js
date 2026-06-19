// Слушает коллекцию searches: дашборд кладёт запрос, агент выполняет поиск по rutracker
// и пишет результаты обратно. Задачу атомарно «захватывает» один агент (claim), чтобы
// при нескольких онлайн-агентах поиск не выполнялся дважды.
const {
  collection, doc, onSnapshot, query, where,
  runTransaction, updateDoc, serverTimestamp
} = require("firebase/firestore");
const rutracker = require("./rutracker");

const MAX_RESULTS = 60;

function watchSearches(ctx) {
  const { db, config } = ctx;
  // Поиск обслуживают только агенты с настроенным rutracker — иначе не подписываемся
  // (чтобы такой агент не «перехватил» задачу и не свалил её в ошибку).
  if (!config.rutracker || !config.rutracker.user) {
    console.log("ℹ rutracker не настроен — поиск этим агентом не обслуживается");
    return () => {};
  }
  const myId = config.device.id;
  const col = collection(db, "searches");
  const pending = query(col, where("status", "==", "requested"));

  return onSnapshot(pending, (snap) => {
    snap.docChanges().forEach((ch) => {
      if (ch.type !== "added") return;
      handle(ctx, ch.doc.id).catch((e) => console.error("search handle:", e.message));
    });
  });
}

async function claim(db, id, myId) {
  const ref = doc(db, "searches", id);
  try {
    return await runTransaction(db, async (tx) => {
      const s = await tx.get(ref);
      if (!s.exists() || s.data().status !== "requested") return null;
      tx.update(ref, { status: "running", claimedBy: myId, updatedAt: serverTimestamp() });
      return s.data();
    });
  } catch (_) { return null; }
}

async function handle(ctx, id) {
  const { db, config } = ctx;
  const data = await claim(db, id, config.device.id);
  if (!data) return; // не наша / уже взята
  console.log("search: выполняю", JSON.stringify({ phrase: data.phrase, days: data.days, categories: data.categories }));
  const ref = doc(db, "searches", id);
  try {
    if (!config.rutracker || !config.rutracker.user) {
      throw new Error("rutracker не настроен в конфиге агента");
    }
    const torrents = await rutracker.search(config.rutracker, {
      phrase: data.phrase || "",
      days: data.days,
      order: data.order || 10,
      categories: data.categories || []
    });
    await updateDoc(ref, {
      status: "done",
      results: torrents.slice(0, MAX_RESULTS),
      count: torrents.length,
      finishedAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    console.log(`search: готово, найдено ${torrents.length}`);
  } catch (e) {
    console.error("search error:", e.message);
    await updateDoc(ref, { status: "error", error: e.message, updatedAt: serverTimestamp() }).catch(() => {});
  }
}

module.exports = { watchSearches };
