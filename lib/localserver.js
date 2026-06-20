// Локальный «TV-режим» на ноде: отдаёт веб-страницу для браузинга ЛОКАЛЬНОЙ коллекции
// и запускает плеер (VLC) на самом устройстве (телефон→HDMI→TV), управление пультом (Flirc).
// Управление коллекцией (добавить/удалить/отправить) — НЕ здесь, а в центральном дашборде.
const http = require("http");
const fs = require("fs");
const path = require("path");
const { execFile } = require("child_process");
const { collection, onSnapshot } = require("firebase/firestore");
const { allDirs } = require("./media");

const TV_DIR = path.join(__dirname, "..", "tv");
const MIME = { ".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8", ".css": "text/css; charset=utf-8" };
const VIDEO_MIME = { ".mkv": "video/x-matroska", ".mp4": "video/mp4", ".avi": "video/x-msvideo", ".mov": "video/quicktime", ".m4v": "video/x-m4v" };

function startLocalServer(ctx) {
  const { db, config } = ctx;
  const port = config.localPort || 8088;
  const host = config.localHost || "127.0.0.1";

  // Держим актуальную медиатеку устройства в памяти (для offline-резистентности и скорости).
  let library = [];
  const unsub = onSnapshot(collection(db, "devices", config.device.id, "library"), (snap) => {
    library = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
  });
  const find = (id) => library.find((x) => x.id === id);

  const server = http.createServer((req, res) => {
    let u;
    try { u = new URL(req.url, `http://${host}:${port}`); } catch (_) { res.statusCode = 400; return res.end(); }
    if (u.pathname === "/api/library") return sendJson(res, libraryView(library, config));
    if (u.pathname === "/api/device") return sendJson(res, { name: config.device.name, id: config.device.id });
    if (u.pathname === "/api/play") return play(config, find(u.searchParams.get("id")), port, res);
    if (u.pathname === "/stream") return stream(find(u.searchParams.get("id")), config, req, res);
    return serveStatic(u.pathname, res);
  });
  server.on("error", (e) => console.error("TV-сервер:", e.message));
  server.listen(port, host, () => console.log(`✓ TV-режим: http://${host}:${port}/`));

  return () => { unsub(); server.close(); };
}

// Публичный вид библиотеки для TV-страницы (без локальных путей).
function libraryView(library) {
  return library.map((it) => ({
    id: it.id, type: it.type || "movie", title: it.title || it.fileName,
    year: it.year || null, poster: it.poster || null, overview: it.overview || "",
    season: it.season ?? null, episode: it.episode ?? null
  }));
}

function inMediaDirs(filePath, config) {
  const resolved = path.resolve(filePath || "");
  return allDirs(config).some((root) => {
    const r = path.resolve(root);
    return resolved === r || resolved.startsWith(r + path.sep);
  });
}

// Запуск плеера на устройстве через Android-intent (am). Открываем localhost-стрим,
// чтобы обойти ограничение Android на file:// между приложениями.
function play(config, item, port, res) {
  if (!item) return sendJson(res, { error: "не найдено" }, 404);
  if (!inMediaDirs(item.filePath, config)) return sendJson(res, { error: "путь вне медиапапок" }, 400);
  const url = `http://127.0.0.1:${port}/stream?id=${encodeURIComponent(item.id)}`;
  const pkg = (config.player && config.player.package) || "org.videolan.vlc";
  const args = ["start", "-a", "android.intent.action.VIEW", "-d", url, "-t", "video/*", "-p", pkg];
  execFile("am", args, (err) => {
    if (err) {
      // нет VLC или am — пробуем без привязки к пакету (системный выбор плеера)
      execFile("am", ["start", "-a", "android.intent.action.VIEW", "-d", url, "-t", "video/*"], (err2) => {
        if (err2) { console.error("play:", err.message); return sendJson(res, { error: "не удалось запустить плеер: " + err.message }, 500); }
        console.log("play (системный плеер):", item.title);
        sendJson(res, { ok: true, player: "system", title: item.title });
      });
      return;
    }
    console.log("play (VLC):", item.title);
    sendJson(res, { ok: true, player: pkg, title: item.title });
  });
}

// Отдача файла с поддержкой Range (для перемотки в плеере).
function stream(item, config, req, res) {
  if (!item || !inMediaDirs(item.filePath, config)) { res.statusCode = 404; return res.end(); }
  let stat;
  try { stat = fs.statSync(item.filePath); } catch (_) { res.statusCode = 404; return res.end(); }
  const mime = VIDEO_MIME[path.extname(item.filePath).toLowerCase()] || "application/octet-stream";
  const range = req.headers.range;
  res.setHeader("Accept-Ranges", "bytes");
  res.setHeader("Content-Type", mime);
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range) || [];
    const start = m[1] ? parseInt(m[1], 10) : 0;
    const end = m[2] ? parseInt(m[2], 10) : stat.size - 1;
    if (start >= stat.size || end >= stat.size) { res.statusCode = 416; res.setHeader("Content-Range", `bytes */${stat.size}`); return res.end(); }
    res.statusCode = 206;
    res.setHeader("Content-Range", `bytes ${start}-${end}/${stat.size}`);
    res.setHeader("Content-Length", end - start + 1);
    fs.createReadStream(item.filePath, { start, end }).on("error", () => res.end()).pipe(res);
  } else {
    res.statusCode = 200;
    res.setHeader("Content-Length", stat.size);
    fs.createReadStream(item.filePath).on("error", () => res.end()).pipe(res);
  }
}

function serveStatic(pathname, res) {
  const rel = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const file = path.join(TV_DIR, rel);
  if (!file.startsWith(TV_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.statusCode = 404; return res.end("not found");
  }
  res.setHeader("Content-Type", MIME[path.extname(file)] || "application/octet-stream");
  fs.createReadStream(file).on("error", () => res.end()).pipe(res);
}

function sendJson(res, obj, code = 200) {
  res.statusCode = code;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(obj));
}

module.exports = { startLocalServer };
