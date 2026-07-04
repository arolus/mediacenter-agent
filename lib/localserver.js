// Локальный «TV-режим» на ноде: отдаёт веб-страницу для браузинга ЛОКАЛЬНОЙ коллекции
// и запускает плеер (VLC) на самом устройстве (телефон→HDMI→TV), управление пультом (Flirc).
// Управление коллекцией (добавить/удалить/отправить) — НЕ здесь, а в центральном дашборде.
const http = require("http");
const fs = require("fs");
const path = require("path");
const { execFile } = require("child_process");
const { collection, doc, onSnapshot, updateDoc, serverTimestamp } = require("firebase/firestore");
const crypto = require("crypto");
const { allDirs } = require("./media");
const player = require("./player");
const tvapp = require("./tvapp");
const transcoder = require("./transcoder");

const TV_DIR = path.join(__dirname, "..", "tv");
const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".png": "image/png", ".svg": "image/svg+xml; charset=utf-8", ".ico": "image/x-icon",
  ".webmanifest": "application/manifest+json; charset=utf-8", ".json": "application/json; charset=utf-8"
};
const VIDEO_MIME = { ".mkv": "video/x-matroska", ".mp4": "video/mp4", ".avi": "video/x-msvideo", ".mov": "video/quicktime", ".m4v": "video/x-m4v" };

function startLocalServer(ctx) {
  const { db, config } = ctx;
  const port = config.localPort || 8088;
  // Слушаем всю локальную сеть (не только localhost): дашборд с компа стримит
  // видео прямо с устройства (`/stream`). Наружу порт не выходит — только LAN.
  const host = config.localHost || "0.0.0.0";

  // Держим актуальную медиатеку устройства в памяти (для offline-резистентности и скорости).
  // onSnapshot => любое изменение (скан, «Исправить» из дашборда, переименование) сразу здесь.
  // Снапшот дублируется на диск: после рестарта без сети TV работает от кэша.
  const LIB_CACHE = path.join(__dirname, "..", "cache", "library.json");
  let library = [];
  try { library = JSON.parse(fs.readFileSync(LIB_CACHE, "utf8")); } catch (_) {}
  let libSaveT = null;
  const sseClients = new Set();
  const unsub = onSnapshot(collection(db, "devices", config.device.id, "library"), (snap) => {
    library = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    clearTimeout(libSaveT);
    libSaveT = setTimeout(() => {
      try { fs.mkdirSync(path.dirname(LIB_CACHE), { recursive: true }); fs.writeFileSync(LIB_CACHE, JSON.stringify(library)); } catch (_) {}
    }, 3000);
    // живое обновление TV-страницы
    for (const c of sseClients) { try { c.write("data: change\n\n"); } catch (_) {} }
  });
  const find = (id) => library.find((x) => x.id === id);

  const server = http.createServer((req, res) => {
    let u;
    try { u = new URL(req.url, `http://${host}:${port}`); } catch (_) { res.statusCode = 400; return res.end(); }
    if (u.pathname === "/api/library") return sendJson(res, libraryView(library, config));
    if (u.pathname === "/api/device") return sendJson(res, { name: config.device.name, id: config.device.id });
    // Здоровье агента для статус-точек TV: сам ответ = «агент жив», внутри — связь с Firebase.
    if (u.pathname === "/api/health") {
      const h = ctx.health || {};
      return sendJson(res, {
        ok: true,
        firebase: !!h.firebase,
        lastOkAgo: h.lastOk ? Math.round((Date.now() - h.lastOk) / 1000) : null,
        error: h.error || null
      });
    }
    if (u.pathname === "/api/events") return sse(req, res, sseClients);
    if (u.pathname === "/api/player-status") return playerStatus(config, res);
    if (u.pathname === "/api/install-player") return installPlayer(config, res);
    if (u.pathname === "/api/app-status") return tvapp.status((st) => sendJson(res, st));
    if (u.pathname === "/api/install-app") return installApp(res);
    if (u.pathname === "/api/play") return play(ctx, find(u.searchParams.get("id")), port, res);
    if (u.pathname === "/api/trailer") return playTrailer(find(u.searchParams.get("id")), res);
    if (u.pathname === "/api/person") return personInfo(ctx, u, res);
    if (u.pathname === "/api/ensure-landscape") return ensureLandscape((ok) => sendJson(res, { ok }));
    if (u.pathname === "/api/watched") return setWatched(ctx, find(u.searchParams.get("id")), u, res);
    if (u.pathname === "/thumb") return thumb(find(u.searchParams.get("id")), config, res);
    if (u.pathname.startsWith("/img/")) return imgProxy(u, res);
    if (u.pathname === "/api/mediainfo") return mediainfo(find(u.searchParams.get("id")), config, res);
    // /stream?id=… ИЛИ /stream/<id>/<имя.ext> — путь с расширением нужен VLC,
    // иначе он считает поток live и не показывает полосу перемотки.
    if (u.pathname === "/stream" || u.pathname.startsWith("/stream/")) {
      const id = u.pathname === "/stream" ? u.searchParams.get("id") : u.pathname.split("/")[2];
      return stream(find(id), config, req, res);
    }
    if (u.pathname === "/transcode") return transcode(find(u.searchParams.get("id")), config, u, res, req);
    return serveStatic(u.pathname, res);
  });
  server.on("error", (e) => console.error("TV-сервер:", e.message));
  ensureLandscape(); // и при старте агента, и по запросу страницы (/api/ensure-landscape)
  server.listen(port, host, () => {
    console.log(`✓ TV-режим: http://${host}:${port}/`);
    // Авто-открытие TV на устройстве (Android): наше приложение (com.mediacenter.tv), если
    // установлено, иначе браузер. Отключается config.openTv:false.
    // Уже открытая вкладка переподключает SSE через ~3с (retry) — ждём: если клиент появился,
    // страница уже открыта и новую вкладку НЕ плодим (дубли съедали пул соединений Chrome).
    if (config.openTv !== false) {
      setTimeout(() => {
        if (sseClients.size > 0) return console.log("→ TV-страница уже открыта (SSE подключён)");
        const inBrowser = () => execFile("am", [
          "start", "-a", "android.intent.action.VIEW", "-d", `http://127.0.0.1:${port}/`
        ], { timeout: 8000 }, (err) => { if (!err) console.log("→ открыл TV-страницу в браузере"); });
        tvapp.status((st) => {
          if (!st.installed) return inBrowser();
          tvapp.open(port, (err) => {
            if (!err) return console.log("→ открыл приложение MediaCenter TV");
            inBrowser();
          });
        });
      }, 8000);
    }
  });

  return () => { unsub(); server.close(); };
}

// Публичный вид библиотеки для TV-страницы (без локальных путей).
// Метаданные (постер/кадры/актёры) проставляет СЕРВЕР — агент их просто отдаёт.
function libraryView(library) {
  return library.map((it) => ({
    id: it.id, type: it.type || "movie", title: it.title || it.fileName,
    year: it.year || null, poster: it.poster || null, backdrop: it.backdrop || null,
    backdrops: it.backdrops || [],
    overview: it.overview || "", rating: it.rating || 0, cast: it.cast || [],
    tmdbId: it.tmdbId || null, season: it.season ?? null, episode: it.episode ?? null,
    fileName: it.fileName || null,
    watched: it.watched === true,
    addedAt: it.addedAt || 0,
    collection: it.collection || null,
    // расширенные метаданные (Kodi-подобная страница фильма)
    castX: it.castX || [], genres: it.genres || [],
    director: it.director || "",
    country: it.country || "", studio: it.studio || "",
    premiered: it.premiered || null, votes: it.votes || 0,
    tagline: it.tagline || "", runtime: it.runtime || 0,
    trailer: it.trailer || null,
    budget: it.budget || 0, revenue: it.revenue || 0,
    imdbRating: it.imdbRating || 0, imdbVotes: it.imdbVotes || 0
  }));
}

// SSE: держим соединение, шлём «change» при изменении библиотеки.
function sse(req, res, clients) {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    "Connection": "keep-alive"
  });
  res.write("retry: 3000\n\n");
  // Chrome даёт максимум 6 соединений на хост: осиротевшие вкладки с вечным SSE
  // съедают весь пул, и новые страницы/EventSource виснут. Старых — выкидываем.
  while (clients.size >= 4) {
    const oldest = clients.values().next().value;
    clients.delete(oldest);
    try { oldest.end(); } catch (_) {}
  }
  clients.add(res);
  const ping = setInterval(() => { try { res.write(": ping\n\n"); } catch (_) {} }, 25000);
  req.on("close", () => { clearInterval(ping); clients.delete(res); });
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
function play(ctx, item, port, res) {
  const { db, config } = ctx;
  if (!item) return sendJson(res, { error: "не найдено" }, 404);
  if (!inMediaDirs(item.filePath, config)) return sendJson(res, { error: "путь вне медиапапок" }, 400);
  // Имя файла в пути: VLC по расширению понимает, что это файл (не live) → полоса перемотки
  const fname = encodeURIComponent(path.basename(item.filePath) || "video.mkv");
  const url = `http://127.0.0.1:${port}/stream/${encodeURIComponent(item.id)}/${fname}`;
  const pkg = (config.player && config.player.package) || "org.videolan.vlc";
  // Отметка о просмотре: запуск = посмотрели (живёт на library-элементе, переживает рескан).
  const markWatched = () => updateDoc(
    doc(db, "devices", config.device.id, "library", item.id),
    { watched: true, watchedAt: serverTimestamp() }
  ).catch(() => {});
  const args = ["start", "-a", "android.intent.action.VIEW", "-d", url, "-t", "video/*", "-p", pkg];
  execFile("am", args, (err) => {
    if (err) {
      // нет VLC или am — пробуем без привязки к пакету (системный выбор плеера)
      execFile("am", ["start", "-a", "android.intent.action.VIEW", "-d", url, "-t", "video/*"], (err2) => {
        if (err2) { console.error("play:", err.message); return sendJson(res, { error: "не удалось запустить плеер: " + err.message }, 500); }
        console.log("play (системный плеер):", item.title);
        markWatched();
        sendJson(res, { ok: true, player: "system", title: item.title });
      });
      return;
    }
    console.log("play (VLC):", item.title);
    markWatched();
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

// Транскодинг на лету: AVI/MKV/HEVC → fMP4 для браузера (ffmpeg на ноде).
function transcode(item, config, u, res, req) {
  if (!item || !inMediaDirs(item.filePath, config)) { res.statusCode = 404; return res.end(); }
  // HEAD-проба браузера: заголовки без запуска ffmpeg
  if (req && req.method === "HEAD") {
    res.writeHead(200, { "Content-Type": "video/mp4", "Cache-Control": "no-store" });
    return res.end();
  }
  transcoder.ffmpegAvailable((ok) => {
    if (!ok) return sendJson(res, { error: "ffmpeg не установлен на устройстве (pkg install ffmpeg)" }, 501);
    const t = Math.max(0, Number(u.searchParams.get("t")) || 0);
    transcoder.streamTranscode(item, t, res);
  });
}

// Пометка «Просмотрено/Не просмотрено» из TV-интерфейса.
function setWatched(ctx, item, u, res) {
  const { db, config } = ctx;
  if (!item) return sendJson(res, { error: "не найдено" }, 404);
  const set = u.searchParams.get("set") !== "0";
  updateDoc(doc(db, "devices", config.device.id, "library", item.id),
    set ? { watched: true, watchedAt: serverTimestamp() } : { watched: false })
    .then(() => sendJson(res, { ok: true, watched: set }))
    .catch((e) => sendJson(res, { error: e.message }, 500));
}

// Превью-кадр из середины видео (для серий без TMDb-кадров). Кэш в cache/thumbs/.
const THUMB_DIR = path.join(__dirname, "..", "cache", "thumbs");
const thumbInflight = new Set();
function thumb(item, config, res) {
  if (!item || !inMediaDirs(item.filePath, config)) { res.statusCode = 404; return res.end(); }
  const file = path.join(THUMB_DIR, item.id + ".jpg");
  const send = () => {
    res.writeHead(200, { "Content-Type": "image/jpeg", "Cache-Control": "max-age=86400" });
    fs.createReadStream(file).on("error", () => res.end()).pipe(res);
  };
  if (fs.existsSync(file)) return send();
  if (thumbInflight.size >= 2 || thumbInflight.has(item.id)) { res.statusCode = 503; return res.end(); }
  thumbInflight.add(item.id);
  fs.mkdirSync(THUMB_DIR, { recursive: true });
  transcoder.makeThumb(item.filePath, file, (ok) => {
    thumbInflight.delete(item.id);
    if (ok && fs.existsSync(file)) return send();
    res.statusCode = 404; res.end();
  });
}

// Закрепление альбомной ориентации (переживает запуск/выход VLC). TV-страница просит это
// при каждом открытии. Требует прав, которых у Termux обычно нет (CLI `settings` живёт под
// shell/root uid) — тогда честно логируем, страница остаётся на CSS-ландшафте.
// В PATH Termux нет /system/bin — только абсолютный путь.
let landscapeLogged = false;
function ensureLandscape(cb) {
  const SETTINGS = "/system/bin/settings";
  execFile(SETTINGS, ["put", "system", "accelerometer_rotation", "0"], { timeout: 5000 }, (e1, _o1, se1) => {
    if (e1) {
      if (!landscapeLogged) { landscapeLogged = true; console.log("система: ориентация не закреплена (" + ((se1 || e1.message) + "").trim().slice(0, 120) + ")"); }
      return cb && cb(false);
    }
    execFile(SETTINGS, ["put", "system", "user_rotation", "1"], { timeout: 5000 }, (e2) => {
      if (!e2 && !landscapeLogged) { landscapeLogged = true; console.log("✓ системная ориентация закреплена: ландшафт"); }
      cb && cb(!e2);
    });
  });
}

// Трейлер: открываем YouTube-ссылку системным интентом — уходит в приложение YouTube,
// а без него Android открывает в браузере (Chrome есть на всех нодах).
function playTrailer(item, res) {
  if (!item || !item.trailer) return sendJson(res, { error: "трейлера нет" }, 404);
  const url = "https://www.youtube.com/watch?v=" + encodeURIComponent(item.trailer);
  execFile("am", ["start", "-a", "android.intent.action.VIEW", "-d", url], { timeout: 8000 }, (err) => {
    if (err) return sendJson(res, { error: "не удалось открыть: " + err.message.slice(0, 120) }, 500);
    console.log(`трейлер: ${item.title || item.fileName} → ${url}`);
    sendJson(res, { ok: true });
  });
}

// Полная фильмография персоны для страницы актёра/режиссёра. Агент в TMDb НЕ ходит —
// проксирует к Cloud Function `person` (ключ только на сервере); кэш в cache/persons/.
const PERSON_DIR = path.join(__dirname, "..", "cache", "persons");
function personFnUrl(config, name) {
  const pid = config.firebase && config.firebase.projectId;
  const q = "?name=" + encodeURIComponent(name);
  if (config.emulator) {
    const host = (config.emulator.firestore && config.emulator.firestore.host) || "127.0.0.1";
    return `http://${host}:5001/${pid}/us-central1/person${q}`;
  }
  return `https://us-central1-${pid}.cloudfunctions.net/person${q}`;
}
function personInfo(ctx, u, res) {
  const name = String(u.searchParams.get("name") || "").trim();
  if (!name) return sendJson(res, { error: "нет имени" }, 400);
  const file = path.join(PERSON_DIR, crypto.createHash("sha1").update(name).digest("hex").slice(0, 20) + ".json");
  if (u.searchParams.get("refresh")) { try { fs.unlinkSync(file); } catch (_) {} }
  // no-cache: браузер не должен запоминать ответ (кэш и так на диске агента),
  // иначе после обновления данных страница видит старьё.
  if (fs.existsSync(file)) {
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-cache" });
    return fs.createReadStream(file).on("error", () => res.end()).pipe(res);
  }
  (async () => {
    const r = await fetch(personFnUrl(ctx.config, name));
    const body = await r.text();
    if (!r.ok) { res.writeHead(r.status, { "Content-Type": "application/json; charset=utf-8" }); return res.end(body); }
    try { fs.mkdirSync(PERSON_DIR, { recursive: true }); fs.writeFileSync(file, body); } catch (_) {}
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
    res.end(body);
  })().catch((e) => sendJson(res, { error: e.message }, 502));
}

// Прокси-кэш картинок TMDb: TV-страница берёт постеры/кадры/фото с агента (/img/<size>/<file>),
// агент скачивает с image.tmdb.org один раз и хранит на диске — интерфейс работает офлайн.
const IMG_DIR = path.join(__dirname, "..", "cache", "img");
const IMG_MIME = { ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".svg": "image/svg+xml" };
function imgProxy(u, res) {
  const m = u.pathname.match(/^\/img\/(w\d{2,4}|original)\/([a-zA-Z0-9_-]+\.(?:jpg|jpeg|png|svg))$/);
  if (!m) { res.statusCode = 400; return res.end(); }
  const [, size, name] = m;
  const file = path.join(IMG_DIR, `${size}_${name}`);
  const type = IMG_MIME[path.extname(name).toLowerCase()] || "image/jpeg";
  const send = () => {
    res.writeHead(200, { "Content-Type": type, "Cache-Control": "max-age=31536000, immutable" });
    fs.createReadStream(file).on("error", () => res.end()).pipe(res);
  };
  if (fs.existsSync(file)) return send();
  (async () => {
    const r = await fetch(`https://image.tmdb.org/t/p/${size}/${name}`);
    if (!r.ok) { res.statusCode = r.status; return res.end(); }
    const buf = Buffer.from(await r.arrayBuffer());
    fs.mkdirSync(IMG_DIR, { recursive: true });
    fs.writeFileSync(file, buf);
    send();
  })().catch(() => { res.statusCode = 502; res.end(); });
}

// Техданные файла (кодек/разрешение/звук/длительность) — техчипы на странице фильма.
// ffprobe разовый, результат кэшируется в cache/mediainfo/<id>.json.
const MI_DIR = path.join(__dirname, "..", "cache", "mediainfo");
const miInflight = new Set();
function mediainfo(item, config, res) {
  if (!item || !inMediaDirs(item.filePath, config)) return sendJson(res, { error: "не найдено" }, 404);
  const file = path.join(MI_DIR, item.id + ".json");
  if (fs.existsSync(file)) {
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "max-age=86400" });
    return fs.createReadStream(file).on("error", () => res.end()).pipe(res);
  }
  if (miInflight.size >= 2 || miInflight.has(item.id)) { res.statusCode = 503; return res.end(); }
  miInflight.add(item.id);
  transcoder.probeMediaInfo(item.filePath, (info) => {
    miInflight.delete(item.id);
    if (!info) return sendJson(res, { error: "ffprobe не справился" }, 500);
    try { fs.mkdirSync(MI_DIR, { recursive: true }); fs.writeFileSync(file, JSON.stringify(info)); } catch (_) {}
    sendJson(res, info);
  });
}

// Статус плеера: установлен ли VLC (для кнопки в TV-интерфейсе).
function playerStatus(config, res) {
  const pkg = (config.player && config.player.package) || player.DEFAULT_PKG;
  player.isInstalled(pkg, (installed) => sendJson(res, { installed, package: pkg }));
}

// Скачивает и запускает установщик VLC.
async function installPlayer(config, res) {
  try {
    const info = await player.downloadVLC();
    player.launchInstaller(info.path, (err, via) => {
      if (err) return sendJson(res, { downloaded: true, path: info.path, launched: false, error: err.message }, 200);
      console.log("VLC APK скачан и запущен установщик (", via, "):", info.file);
      sendJson(res, { downloaded: true, launched: true, via, file: info.file });
    });
  } catch (e) {
    console.error("installPlayer:", e.message);
    sendJson(res, { error: e.message }, 500);
  }
}

// Установка/обновление нашего TV-приложения (APK из репо агента).
function installApp(res) {
  tvapp.install((err, via) => {
    if (err) { console.error("installApp:", err.message); return sendJson(res, { error: err.message }, 500); }
    console.log("TV-приложение: APK скопирован в Загрузки, установщик запущен (", via, ")");
    sendJson(res, { ok: true, launched: true, via });
  });
}

function serveStatic(pathname, res) {
  const rel = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const file = path.join(TV_DIR, rel);
  if (!file.startsWith(TV_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.statusCode = 404; return res.end("not found");
  }
  res.setHeader("Content-Type", MIME[path.extname(file)] || "application/octet-stream");
  res.setHeader("Cache-Control", "no-cache"); // после обновления агента UI всегда свежий
  fs.createReadStream(file).on("error", () => res.end()).pipe(res);
}

function sendJson(res, obj, code = 200) {
  res.statusCode = code;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(obj));
}

module.exports = { startLocalServer };
