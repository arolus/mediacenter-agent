// Поиск по rutracker — порт из kodi/rutracker.js, адаптированный под агент:
// конфиг-driven, кэш в agent/cache, и категории сгруппированы в movies/series/cartoons.
const fs = require("fs");
const path = require("path");
const execFileSync = require("child_process").execFileSync;
const HTMLParser = require("node-html-parser");

const CACHE_DIR = path.join(__dirname, "..", "cache");
const COOKIES = path.join(CACHE_DIR, "cookies.jar");
const CATS = path.join(CACHE_DIR, "categories.json");

function ensureCache() { if (!fs.existsSync(CACHE_DIR)) fs.mkdirSync(CACHE_DIR, { recursive: true }); }
// Успешный логин = в cookie-jar появилась bb_session (тело ответа при успехе пустое: 302).
function loggedIn() {
  try { return /bb_session/.test(fs.readFileSync(COOKIES, "utf8")); } catch (_) { return false; }
}
function host(cfg) { return cfg.hostname || "https://rutracker.org"; }
function ua(cfg) { return cfg.useragent || "Mozilla/5.0"; }

// curl с аргументами массивом (без shell) — как в kodi. Возвращает Buffer.
function curl(args) { return execFileSync("curl", args, { maxBuffer: 64 * 1024 * 1024 }); }
function decodeCp1251(buf) { return new TextDecoder("windows-1251").decode(buf); }

function downloadTorrent(cfg, torrentId) {
  if (!/^\d+$/.test(String(torrentId))) throw new Error("Invalid torrent id: " + torrentId);
  return curl(["-s", `${host(cfg)}/forum/dl.php?t=${torrentId}`,
    "-H", `User-Agent: ${ua(cfg)}`, "--cookie", COOKIES]);
}

async function login(cfg) {
  ensureCache();
  const user = encodeURIComponent(cfg.user ?? "");
  const pass = encodeURIComponent(cfg.pass ?? "");
  // login=%C2%F5%EE%E4 — CP1251 "Вход".
  let result = curl(["-s", "-X", "POST", `${host(cfg)}/forum/login.php`,
    "-d", `redirect=index.php&login_username=${user}&login_password=${pass}&login=%C2%F5%EE%E4`,
    "-H", "Content-Type: application/x-www-form-urlencoded",
    "-H", `User-Agent: ${ua(cfg)}`, "--cookie-jar", COOKIES]).toString();

  let captchaSrc = result.match(/(https:\/\/static\.t-ru\.org\/captcha\/[a-z0-9]+\.jpg\?\d+)/);
  if (captchaSrc && (captchaSrc = captchaSrc[1])) {
    if (!cfg.antigateKey) throw new Error("rutracker требует капчу, но antigateKey не задан");
    const sid = result.match(/name="cap_sid"\s+value="([^"]+)"/)[1];
    const name = result.match(/(cap_code_[^"]+)/)[1];
    const captcha = await antiGateImg(cfg, captchaSrc);
    result = curl(["-s", "-X", "POST", `${host(cfg)}/forum/login.php`,
      "-d", `cap_sid=${encodeURIComponent(sid)}&${encodeURIComponent(name)}=${encodeURIComponent(captcha)}&redirect=index.php&login_username=${user}&login_password=${pass}&login=%C2%F5%EE%E4`,
      "-H", "Content-Type: application/x-www-form-urlencoded",
      "-H", `User-Agent: ${ua(cfg)}`, "--cookie-jar", COOKIES]).toString();
  }
  if (fs.existsSync(CATS)) fs.unlinkSync(CATS);
  return loggedIn();
}

// Мусор, который НЕ нужен ни в одной из групп (саундтреки, трейлеры, девайс-специфичное,
// 3D, спорт, документалки о музыке, софт-«мультимедиа» и т.п.).
const EXCLUDE = /саундтрек|трейлер|дополнительные материал|psp|ipod|iphone|ipad|apple\s*tv|3d|стерео|спорт|футбол|музык|юмор|пародии|9\s*мая|познавательн|документальн|передач|авто\/мото|артбук|звуковые\s+дорожк|плеерный|qc\s+подраздел|мультимед|мультиязычн|программ|материал|театр|цирк|эстрад|\[\s*док\s*\]/i;

// Группирует категории трекера: cartoons -> series -> movies (порядок важен из-за подстрок).
// Сначала отсекаем мусор, потом классифицируем по ключевым словам названия форума.
// Десятилетия («Фильмы 1991-2000», «Фильмы 2001-2005» … «Фильмы 2026») попадают в movies
// по слову «Фильмы»; аниме и мультсериалы — в cartoons.
function classify(text) {
  if (EXCLUDE.test(text)) return null;
  if (/мультфильм|мультсериал|аниме/i.test(text)) return "cartoons";
  if (/сериал/i.test(text)) return "series";
  if (/фильм|кино|кинематограф/i.test(text)) return "movies";
  return null;
}

// Группа (мн.ч.) -> тип (ед.ч.) для раскладки по папкам на ноде.
const GROUP_TO_TYPE = { movies: "movie", series: "series", cartoons: "cartoon" };
function typeForCategory(category) {
  return GROUP_TO_TYPE[classify(category || "")] || "movie";
}

function getCategoryGroups(cfg) {
  if (fs.existsSync(CATS)) return JSON.parse(fs.readFileSync(CATS));
  const groups = { movies: [], series: [], cartoons: [] };
  const result = decodeCp1251(curl(["-s", `${host(cfg)}/forum/tracker.php`,
    "-H", `User-Agent: ${ua(cfg)}`, "--cookie", COOKIES]));
  if (result) {
    const root = HTMLParser.parse(result);
    root.querySelectorAll("option").forEach((opt) => {
      const id = opt.getAttribute("id") || "";
      const value = opt.getAttribute("value");
      if (!id.startsWith("fs-") || !value || !/^\d+$/.test(value)) return;
      const g = classify(opt.text);
      if (g) groups[g].push(value);
    });
    if (groups.movies.length || groups.series.length || groups.cartoons.length) {
      ensureCache();
      fs.writeFileSync(CATS, JSON.stringify(groups));
    }
  }
  return groups;
}

function searchRaw(cfg, { phrase, days, order, forums }) {
  // rutracker tm: 3/7/14/32, -1 = за всё время.
  const safeDays = /^-?\d+$/.test(String(days ?? "")) ? String(days) : "";
  const safeOrder = /^\d+$/.test(String(order)) ? String(order) : "10";
  const result = decodeCp1251(curl(["-s", `${host(cfg)}/forum/tracker.php`,
    "-d", `f=${forums.join(",")}&nm=${encodeURIComponent(phrase ?? "")}&tm=${safeDays}&o=${safeOrder}&s=2&pn=`,
    "-H", "Content-Type: application/x-www-form-urlencoded",
    "-H", `User-Agent: ${ua(cfg)}`, "--cookie", COOKIES]));

  const torrents = [];
  const root = HTMLParser.parse(result);
  root.querySelectorAll("tr.tCenter.hl-tr").forEach((tr) => {
    const link = tr.querySelector("a.med.tLink.tt-text.ts-text.hl-tags.bold");
    if (!link) return;
    const fullTitle = unescapeHTML(link.innerHTML);
    const yearMatch = fullTitle.match(/\[(\d{4}),/);
    const category = tr.querySelector("a.gen.f.ts-text")?.innerHTML || "";
    torrents.push({
      tid: tr.attributes["data-topic_id"],
      label: fullTitle.replace(/\s*(.+?)\s*[\/\(].+/, "$1").trim(),
      sublabel: { ...fullTitle.match(/.+?[\/]\s*(.+?)\s*\(.+/, "$1") }[1] || "",
      year: yearMatch?.[1] ?? "",
      meta: fullTitle.replace(/.+?\s*(\(.+)\s*/, "$1").trim(),
      category,
      type: typeForCategory(category),
      size: unescapeHTML(tr.querySelector("a.small.tr-dl.dl-stub")?.innerHTML || "").replaceAll(/[^0-9A-Z.,]+/g, "").trim(),
      seeds: tr.querySelector("b.seedmed") ? Number(tr.querySelector("b.seedmed").innerHTML) : 0,
      downloads: tr.querySelector("td.row4.small.number-format")?.innerHTML?.replace(/\D/g, "") || "0"
    });
  });
  // Сортируем по выбранному полю (o=4 — скачивания, иначе сиды), убывающе, независимо от трекера.
  const field = String(order) === "4" ? "downloads" : "seeds";
  torrents.sort((a, b) => (Number(b[field]) || 0) - (Number(a[field]) || 0));
  return torrents;
}

// Публичное API: ищет в выбранных группах, при пустом ответе логинится и повторяет.
async function search(cfg, { phrase, days, order, categories }) {
  const groups = getCategoryGroups(cfg);
  const selected = (categories && categories.length ? categories : ["movies", "series", "cartoons"]);
  const forums = [...new Set(selected.flatMap((g) => groups[g] || []))];
  if (!forums.length) {
    // категории ещё не закэшированы (нужен логин) — логинимся и пробуем снова
    await login(cfg);
    return search(cfg, { phrase, days, order, categories });
  }
  const opts = { phrase, days: days ?? (phrase ? "" : 7), order: order ?? 10, forums };
  let torrents = searchRaw(cfg, opts);
  if (!torrents.length) {
    await login(cfg);
    torrents = searchRaw(cfg, opts);
  }
  return torrents;
}

// --- antigate (порт из kodi) ---
function antiCaptchaPost(endpoint, payload) {
  return JSON.parse(curl(["-s", "-H", "Accept: application/json", "-H", "Content-Type: application/json",
    "-X", "POST", "-d", JSON.stringify(payload), `https://api.anti-captcha.com/${endpoint}`]).toString());
}
async function antiGateImg(cfg, src) {
  const image = curl(["-s", src]);
  const task = antiCaptchaPost("createTask", {
    clientKey: cfg.antigateKey, softId: 0,
    task: { type: "ImageToTextTask", body: Buffer.from(image).toString("base64"), phrase: false, case: false, numeric: 0, math: false, minLength: 0, maxLength: 0 }
  });
  for (let i = 0; i < 20; i++) {
    await new Promise((r) => setTimeout(r, 3000));
    const res = antiCaptchaPost("getTaskResult", { clientKey: cfg.antigateKey, taskId: task.taskId });
    if (res.status === "ready") return res.solution.text;
  }
  throw new Error("antigate timeout");
}

function unescapeHTML(str) {
  const e = { nbsp: " ", cent: "¢", pound: "£", yen: "¥", euro: "€", copy: "©", reg: "®", lt: "<", gt: ">", quot: '"', amp: "&", apos: "'", raquo: "»", laquo: "«" };
  return String(str).replace(/&([^;]+);/g, (m, c) => {
    if (c in e) return e[c];
    let mm;
    if ((mm = c.match(/^#x([\da-fA-F]+)$/))) return String.fromCharCode(parseInt(mm[1], 16));
    if ((mm = c.match(/^#(\d+)$/))) return String.fromCharCode(~~mm[1]);
    return m;
  });
}

module.exports = { search, getCategoryGroups, download: downloadTorrent, login };
