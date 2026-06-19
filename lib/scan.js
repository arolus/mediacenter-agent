// Рекурсивный скан медиапапки — по образцу scanDir из kodi/movies.js.
const fs = require("fs");
const path = require("path");

function buildExtRegex(exts) {
  const list = (exts && exts.length ? exts : ["mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg"]);
  return new RegExp("\\.(" + list.join("|") + ")$", "i");
}

// Возвращает [{ filePath, fileName, sizeBytes }]
function scanMedia(dir, exts) {
  const re = buildExtRegex(exts);
  const out = [];
  walk(dir, re, out);
  return out;
}

function walk(dir, re, out) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (e) {
    console.error("scan: не читается папка", dir, e.message);
    return;
  }
  for (const ent of entries) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) {
      walk(full, re, out);
    } else if (re.test(ent.name)) {
      let size = 0;
      try { size = fs.statSync(full).size; } catch (_) {}
      out.push({ filePath: full, fileName: ent.name, sizeBytes: size });
    }
  }
}

module.exports = { scanMedia };
