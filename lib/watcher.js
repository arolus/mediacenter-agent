// Авто-подхват изменений в папках Movies/Series/Cartoons.
// Две стратегии вместе (надёжно на всех платформах, включая Android/Termux):
//   1) fs.watch на каждую папку — мгновенная реакция на «кинули файл» (на Android ловит верхний
//      уровень; рекурсия там не поддерживается, поэтому есть и пункт 2);
//   2) периодический опрос — гарантированный baseline (находит и вложенные, и пропущенные события).
// На каждое срабатывание запускаем syncLibrary (он сам диффит и обновляет Firestore).
const fs = require("fs");
const { syncLibrary } = require("./library");
const { mediaDirs, ensureDirs } = require("./media");

const DEBOUNCE_MS = 2000;

function watchLibrary(ctx) {
  const { config } = ctx;
  const intervalMs = config.scanIntervalMs || 60_000;
  ensureDirs(config);
  const dirs = Object.values(mediaDirs(config));

  let running = false, dirty = false, debounce = null;

  async function run() {
    if (running) { dirty = true; return; }   // уже идёт — пометим, что нужен повтор
    running = true;
    try {
      do {
        dirty = false;
        await syncLibrary(ctx);
      } while (dirty);
    } catch (e) {
      console.error("watcher: ошибка скана:", e.message);
    } finally {
      running = false;
    }
  }

  function schedule() {
    clearTimeout(debounce);
    debounce = setTimeout(run, DEBOUNCE_MS);
  }

  // 1) fs.watch (best-effort)
  const watchers = [];
  for (const d of dirs) {
    try {
      const w = fs.watch(d, () => schedule());
      w.on("error", () => {});
      watchers.push(w);
    } catch (_) { /* папки может не быть — её создаст ensureDirs, но watch мог не встать */ }
  }

  // 2) периодический опрос
  const poll = setInterval(run, intervalMs);
  console.log(`✓ слежу за медиапапками (fs.watch + опрос раз в ${Math.round(intervalMs / 1000)}с)`);

  return () => {
    clearTimeout(debounce);
    clearInterval(poll);
    watchers.forEach((w) => { try { w.close(); } catch (_) {} });
  };
}

module.exports = { watchLibrary };
