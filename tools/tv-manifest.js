#!/usr/bin/env node
// Манифест веб-части для самообновления нод (см. WebUpdater.kt).
//
// Список файлов agent/tv/ с их sha1 и общая версия = sha1 от всего списка: изменился любой
// файл — изменилась версия, и нода скачает ровно то, что поменялось. Запускается из ship.sh
// перед коммитом, так что в репозитории манифест всегда соответствует файлам.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const root = path.join(__dirname, "..", "tv");
const OUT = path.join(root, "manifest.json");
// Кэши, мусор ноды и APK старого WebView-приложения в манифест не попадают: обновляем
// только саму оболочку, а не мегабайты, которые нода и так не читает.
const SKIP = new Set(["manifest.json", "cache", "app"]);

const sha1 = (buf) => crypto.createHash("sha1").update(buf).digest("hex");

function walk(dir, prefix = "") {
  const out = {};
  for (const name of fs.readdirSync(dir).sort()) {
    if (SKIP.has(name) || name.startsWith(".")) continue;
    const full = path.join(dir, name);
    const rel = prefix ? `${prefix}/${name}` : name;
    const st = fs.statSync(full);
    if (st.isDirectory()) Object.assign(out, walk(full, rel));
    else out[rel] = sha1(fs.readFileSync(full));
  }
  return out;
}

const files = walk(root);
const version = sha1(Buffer.from(JSON.stringify(files))).slice(0, 12);
const prev = (() => {
  try { return JSON.parse(fs.readFileSync(OUT, "utf8")).version; } catch { return ""; }
})();

if (prev === version) {
  console.log(`tv-manifest: без изменений (${version})`);
} else {
  fs.writeFileSync(OUT, JSON.stringify({ version, files }, null, 2) + "\n");
  console.log(`tv-manifest: ${Object.keys(files).length} файлов, версия ${prev || "—"} → ${version}`);
}
