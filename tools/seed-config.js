// Записывает Firestore-док config/agent — доступы, которые дашборд зашивает в QR новой ноды.
// Владелец делает это один раз. node tools/seed-config.js <config.json>
const fs = require("fs");
const path = require("path");
const { initFirebase } = require("../lib/firebase");
const { loadTmdbCreds } = require("../lib/env");
const { doc, setDoc } = require("firebase/firestore");

async function main() {
  const config = JSON.parse(fs.readFileSync(path.resolve(process.argv[2] || "config-a.json"), "utf8"));
  const ctx = await initFirebase(config);
  const creds = loadTmdbCreds(__dirname);

  const agent = {
    auth: config.auth,                         // аккаунт, под которым ноды входят в Firebase
    rutracker: config.rutracker || null,       // креды rutracker для поиска/скачивания
    tmdbApiKey: creds.v3 || "",                // v3-ключ (компактнее в QR, чем v4-токен)
    tmdbLang: config.tmdbLang || "ru-RU",
    mediaRoot: config.mediaRoot || "/storage/emulated/0", // корень медиа (подпапки Movies/Series/Cartoons)
    torrentPort: 51413,
    repo: "https://github.com/arolus/mediacenter-agent" // ОТКУДА install.sh клонирует агента
  };
  await setDoc(doc(ctx.db, "config", "agent"), agent);
  console.log("✓ config/agent записан. Поля:", Object.keys(agent).join(", "));
  console.log("  repo =", agent.repo, "(не забудь опубликовать папку agent/ в этот репозиторий)");
  process.exit(0);
}
main().catch((e) => { console.error(e); process.exit(1); });
