// Достаёт TMDb-ключи из .env-файла, НЕ требуя точного имени переменной.
// Берёт любую переменную, в имени которой есть "TMDB", и классифицирует значение:
//   - 32-символьный hex            -> v3 API Key (?api_key=)
//   - строка, начинающаяся с eyJ   -> v4 Read Access Token (Authorization: Bearer)
// Значения наружу не логируются.
const fs = require("fs");
const path = require("path");

function parseEnv(text) {
  const out = {};
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim();
    let val = line.slice(eq + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    out[key] = val;
  }
  return out;
}

function classify(vars) {
  const creds = { v3: null, v4: null };
  for (const [name, val] of Object.entries(vars)) {
    if (!/tmdb/i.test(name) || !val) continue;
    if (/^[a-f0-9]{32}$/i.test(val)) creds.v3 = val;
    else if (/^eyJ/.test(val)) creds.v4 = val;
  }
  return creds;
}

// Ищет .env / .env.local в нескольких местах относительно агента.
function loadTmdbCreds(baseDir) {
  const candidates = [
    path.join(baseDir, ".env.local"),
    path.join(baseDir, ".env"),
    path.join(baseDir, "..", "dashboard", ".env.local"),
    path.join(baseDir, "..", "dashboard", ".env"),
    path.join(baseDir, "..", ".env.local"),
    path.join(baseDir, "..", ".env")
  ];
  for (const file of candidates) {
    try {
      if (fs.existsSync(file)) {
        const creds = classify(parseEnv(fs.readFileSync(file, "utf8")));
        if (creds.v3 || creds.v4) return { ...creds, source: file };
      }
    } catch (_) { /* пропускаем нечитаемые */ }
  }
  return { v3: null, v4: null, source: null };
}

module.exports = { loadTmdbCreds };
