// MediaCenter slave-агент. Запуск: node index.js [путь-к-конфигу]
//
// Что делает:
//   1) входит в Firebase под аккаунтом владельца;
//   2) регистрирует устройство в devices/{id} и шлёт heartbeat;
//   3) сканирует медиапапку, распознаёт фильмы/сериалы (TMDb), пишет в library;
//   4) слушает команды (rescan / delete);
//   5) обслуживает P2P-переносы (seed / download) через WebTorrent.
const fs = require("fs");
const path = require("path");
const { doc, setDoc, serverTimestamp } = require("firebase/firestore");
const { initFirebase } = require("./lib/firebase");
const { loadTmdbCreds } = require("./lib/env");
const { syncLibrary } = require("./lib/library");
const { watchCommands } = require("./lib/commands");
const { watchTransfers } = require("./lib/transfer");
const { watchSearches } = require("./lib/searches");
const { watchDownloads } = require("./lib/downloads");
const { watchUpdates, currentSha, currentBranch } = require("./lib/updater");

const VERSION = (currentSha() || "").slice(0, 7) || "dev";
const HEARTBEAT_MS = 30_000;

function loadConfig() {
  const file = path.resolve(process.argv[2] || "agent-config.json");
  if (!fs.existsSync(file)) {
    console.error(`Нет конфига ${file}. Скопируй agent-config.example.json в agent-config.json и заполни.`);
    process.exit(1);
  }
  const config = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!config.mediaDir || String(config.mediaDir).includes("REPLACE_ME")) {
    console.error('Не заполнено поле "mediaDir" в конфиге.');
    process.exit(1);
  }
  // Если ключ TMDb не задан в конфиге — пробуем подтянуть из .env / .env.local.
  const hasConfigKey = config.tmdbApiKey && !String(config.tmdbApiKey).includes("REPLACE_ME");
  if (!hasConfigKey) {
    const creds = loadTmdbCreds(__dirname);
    if (creds.v4) { config.tmdbBearer = creds.v4; console.log("✓ TMDb: v4-токен из", creds.source); }
    else if (creds.v3) { config.tmdbApiKey = creds.v3; console.log("✓ TMDb: v3-ключ из", creds.source); }
  }
  if ((!config.tmdbApiKey || String(config.tmdbApiKey).includes("REPLACE_ME")) && !config.tmdbBearer) {
    console.warn('⚠️ TMDb ключ не найден — распознавание выключено, файлы будут как "не распознан".');
  }
  if (!fs.existsSync(config.mediaDir)) {
    console.error(`Медиапапка не найдена: ${config.mediaDir}`);
    process.exit(1);
  }
  return config;
}

async function main() {
  const config = loadConfig();
  const ctx = { config };
  Object.assign(ctx, await initFirebase(config));

  const deviceRef = doc(ctx.db, "devices", config.device.id);
  await setDoc(deviceRef, {
    name: config.device.name || config.device.id,
    online: true,
    version: VERSION,
    branch: currentBranch(),
    lastSeen: serverTimestamp()
  }, { merge: true });
  console.log(`✓ Устройство зарегистрировано: ${config.device.name} (${config.device.id})`);

  // Heartbeat
  const heartbeat = setInterval(() => {
    setDoc(deviceRef, { online: true, lastSeen: serverTimestamp() }, { merge: true })
      .catch((e) => console.error("heartbeat:", e.message));
  }, HEARTBEAT_MS);

  // Первичный скан
  await syncLibrary(ctx).catch((e) => console.error("initial scan:", e.message));

  // Подписки
  const stopCommands = watchCommands(ctx);
  const stopTransfers = watchTransfers(ctx);
  const stopSearches = watchSearches(ctx);
  const stopDownloads = watchDownloads(ctx);
  const stopUpdates = watchUpdates(ctx);
  console.log("✓ Агент готов. Слушаю команды, переносы, поиск, загрузки и обновления…");

  // Аккуратное завершение
  async function shutdown() {
    console.log("\nЗавершаюсь…");
    clearInterval(heartbeat);
    stopCommands();
    stopTransfers();
    stopSearches();
    stopDownloads();
    stopUpdates();
    await setDoc(deviceRef, { online: false, lastSeen: serverTimestamp() }, { merge: true }).catch(() => {});
    process.exit(0);
  }
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

main().catch((e) => {
  console.error("Фатальная ошибка:", e);
  process.exit(1);
});
