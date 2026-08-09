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
const { doc, getDoc, setDoc, serverTimestamp } = require("firebase/firestore");
const { ensureDirs, mediaRoot } = require("./lib/media");
const { initFirebase } = require("./lib/firebase");
const { syncLibrary } = require("./lib/library");
const { watchCommands } = require("./lib/commands");
const { watchTransfers } = require("./lib/transfer");
const { watchDownloads } = require("./lib/downloads");
const { watchRtRequests } = require("./lib/rtrelay");
const { watchUpdates, currentSha, currentBranch } = require("./lib/updater");
const { startLocalServer } = require("./lib/localserver");
const { watchLibrary } = require("./lib/watcher");

const VERSION = (currentSha() || "").slice(0, 7) || "dev";
const HEARTBEAT_MS = 30_000;

// LAN-адрес устройства — по нему дашборд стримит видео с TV-сервера ноды.
const os = require("os");
function lanIp() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const i of ifaces || []) if (i.family === "IPv4" && !i.internal) return i.address;
  }
  return null;
}

// Свободное/общее место на диске медиапапки — для дашборда (сколько ещё влезет).
// fs.statfs есть с Node 18.15; на старее вернём null (дашборд просто не покажет).
function diskUsage(config) {
  return new Promise((resolve) => {
    if (!fs.statfs) return resolve(null);
    fs.statfs(mediaRoot(config), (err, st) => {
      if (err || !st) return resolve(null);
      resolve({ freeBytes: st.bavail * st.bsize, totalBytes: st.blocks * st.bsize });
    });
  });
}

function loadConfig() {
  const file = path.resolve(process.argv[2] || "agent-config.json");
  if (!fs.existsSync(file)) {
    console.error(`Нет конфига ${file}. Скопируй agent-config.example.json в agent-config.json и заполни.`);
    process.exit(1);
  }
  const config = JSON.parse(fs.readFileSync(file, "utf8"));
  if ((!config.mediaRoot || String(config.mediaRoot).includes("REPLACE_ME")) &&
      (!config.mediaDir || String(config.mediaDir).includes("REPLACE_ME"))) {
    console.error('Не заполнено поле "mediaRoot" в конфиге (напр. "/storage/emulated/0").');
    process.exit(1);
  }
  // TMDb-ключ агенту НЕ нужен — распознавание делает сервер (Cloud Function).
  // Создаём папки Movies/Series/Cartoons под mediaRoot, если их нет.
  const dirs = ensureDirs(config);
  console.log("медиапапки:", Object.entries(dirs).map(([t, d]) => `${t}→${d}`).join("  "));
  return config;
}

async function main() {
  const config = loadConfig();
  // health — для статус-точек TV-страницы (/api/health): жив ли канал в Firebase.
  // Обновляется heartbeat'ом: он и так ходит в Firestore каждые 30с.
  const ctx = { config, health: { firebase: false, lastOk: 0, error: null } };
  Object.assign(ctx, await initFirebase(config));

  const deviceRef = doc(ctx.db, "devices", config.device.id);
  // Имя из дашборда имеет приоритет: ставим имя из конфига только при первой регистрации.
  const existing = await getDoc(deviceRef).catch(() => null);
  const base = {
    online: true,
    version: VERSION,
    branch: currentBranch(),
    // адрес TV-сервера в локальной сети — дашборд стримит видео прямо с устройства
    lanIp: lanIp(),
    tvPort: config.localPort || 8088,
    disk: await diskUsage(config),
    lastSeen: serverTimestamp()
  };
  if (!existing || !existing.exists() || !existing.data().name) {
    base.name = config.device.name || config.device.id;
  }
  await setDoc(deviceRef, base, { merge: true });
  ctx.health.firebase = true; ctx.health.lastOk = Date.now();
  console.log(`✓ Устройство зарегистрировано: ${config.device.name} (${config.device.id})`);

  // Heartbeat (lanIp может меняться при смене Wi-Fi, disk — при скачивании/удалении)
  const heartbeat = setInterval(async () => {
    setDoc(deviceRef, { online: true, lanIp: lanIp(), disk: await diskUsage(config), lastSeen: serverTimestamp() }, { merge: true })
      .then(() => { ctx.health.firebase = true; ctx.health.lastOk = Date.now(); ctx.health.error = null; })
      .catch((e) => {
        ctx.health.firebase = false; ctx.health.error = e.message;
        console.error("heartbeat:", e.message);
      });
  }, HEARTBEAT_MS);

  // Первичный скан + слежение за папками (авто-подхват новых/удалённых файлов)
  await syncLibrary(ctx).catch((e) => console.error("initial scan:", e.message));
  const stopWatch = watchLibrary(ctx);

  // Подписки
  const stopCommands = watchCommands(ctx);
  const stopTransfers = watchTransfers(ctx);
  const stopDownloads = watchDownloads(ctx);
  // Ретранслятор запросов к rutracker: Cloudflare пускает только с домашнего IP, поэтому
  // сами HTTP-запросы уходят отсюда, а вся логика трекера остаётся на сервере (lib/rtrelay.js).
  const stopRtRelay = watchRtRequests(ctx);
  const stopUpdates = watchUpdates(ctx);
  const stopLocal = startLocalServer(ctx);
  console.log("✓ Агент готов. Слушаю команды, переносы, загрузки и обновления…");

  // Аккуратное завершение
  async function shutdown() {
    console.log("\nЗавершаюсь…");
    clearInterval(heartbeat);
    stopWatch();
    stopCommands();
    stopTransfers();
    stopDownloads();
    stopRtRelay();
    stopUpdates();
    stopLocal();
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
