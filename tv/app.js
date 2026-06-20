// TV-режим: категории → сетка 4×2 с описанием слева → страница фильма с Play.
// Навигация пультом (Flirc = клавиши): стрелки, OK/Enter, Back (Esc/Backspace).
const IMG = "https://image.tmdb.org/t/p";
const poster = (p) => (p ? `${IMG}/w342${p}` : null);
const backdrop = (p) => (p ? `${IMG}/w1280${p}` : null);
const shot = (p) => `${IMG}/w500${p}`;
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

const CATS = [
  { type: "movie", label: "Фильмы", emoji: "🎬" },
  { type: "cartoon", label: "Мультфильмы", emoji: "🧸" },
  { type: "series", label: "Сериалы", emoji: "📺" }
];

const app = document.getElementById("app");
let deviceName = "";
let items = [];
let state = { screen: "categories", type: "movie" };

async function load() {
  try { deviceName = (await (await fetch("/api/device")).json()).name || ""; } catch (_) {}
  await reloadLibrary();
  history.replaceState({ screen: "categories" }, ""); // корневая запись истории
  render();
  // live-обновление: любое изменение (скан, «Исправить» из дашборда, переименование) прилетает сюда
  try {
    const es = new EventSource("/api/events");
    es.onmessage = async () => { await reloadLibrary(); rerenderKeepingFocus(); };
  } catch (_) {}
}

async function reloadLibrary() {
  try { items = await (await fetch("/api/library")).json(); } catch (_) { items = items || []; }
}

const byType = (t) => items.filter((i) => i.type === t).sort((a, b) => (a.title || "").localeCompare(b.title || ""));

function render() {
  if (state.screen === "categories") renderCategories();
  else if (state.screen === "grid") renderGrid();
  else if (state.screen === "detail") renderDetail();
}

// При live-обновлении перерисовываем текущий экран, сохраняя фокус по id.
function rerenderKeepingFocus() {
  const focusedId = document.activeElement?.dataset?.id;
  if (state.screen === "detail" && state.current && !items.find((i) => i.id === state.current.id)) {
    state.screen = "grid"; // фильм исчез — назад в сетку
  }
  render();
  if (focusedId) {
    const el = app.querySelector(`[data-id="${CSS.escape(focusedId)}"]`);
    if (el) el.focus();
  }
}

/* ---------- Категории ---------- */
function renderCategories() {
  app.innerHTML = `
    <div class="cat-screen">
      <div class="cat-head"><span class="cat-brand">🎬 MediaCenter</span><span class="cat-device">${esc(deviceName)}</span></div>
      <div class="cat-tiles">
        ${CATS.map((c) => `
          <div class="cat-tile" tabindex="0" data-type="${c.type}">
            <div class="emoji">${c.emoji}</div>
            <div class="label">${c.label}</div>
            <div class="count">${byType(c.type).length} шт.</div>
          </div>`).join("")}
      </div>
    </div>`;
  app.querySelectorAll(".cat-tile").forEach((t) => t.addEventListener("click", () => enterGrid(t.dataset.type)));
  const idx = CATS.findIndex((c) => c.type === state.type);
  app.querySelectorAll(".cat-tile")[idx >= 0 ? idx : 0].focus();
}

/* ---------- Сетка ---------- */
function computeCardWidth() {
  const side = Math.min(460, Math.max(280, window.innerWidth * 0.32));
  const gap = 20, gridPad = 80, headerH = 70, titleH = 50;
  const byW = (window.innerWidth - side - gridPad - 3 * gap) / 4;
  const byH = ((window.innerHeight - headerH) / 2 - gap - titleH) / 1.5; // 2 ряда, постер 2:3
  const w = Math.max(120, Math.floor(Math.min(byW, byH)));
  document.documentElement.style.setProperty("--card-w", w + "px");
}

function renderGrid() {
  computeCardWidth();
  const list = byType(state.type);
  const cat = CATS.find((c) => c.type === state.type);
  app.innerHTML = `
    <div class="grid-screen">
      <div class="grid-info" id="grid-info"></div>
      <div class="grid-wrap">
        <div class="grid-top">
          <button class="grid-back" tabindex="0">← Назад</button>
          <h2 class="grid-cat-title">${cat.label} · ${list.length}</h2>
        </div>
        <div class="tv-grid">
          ${list.map((i) => `
            <div class="tv-card" tabindex="0" data-id="${esc(i.id)}">
              ${poster(i.poster) ? `<div class="tv-poster" style="background-image:url('${poster(i.poster)}')"></div>` : `<div class="tv-poster">${esc(i.title)}</div>`}
              <div class="tv-card-title">${esc(i.title)}</div>
            </div>`).join("") || '<p class="tv-empty">Пусто</p>'}
        </div>
      </div>
    </div>`;
  app.querySelectorAll(".tv-card").forEach((card) => {
    const item = list.find((i) => i.id === card.dataset.id);
    card.addEventListener("focus", () => updateInfo(item));
    card.addEventListener("click", () => enterDetail(item));
  });
  app.querySelector(".grid-back").addEventListener("click", back);
  const first = app.querySelector(".tv-card") || app.querySelector(".grid-back");
  if (first) first.focus();
}

function updateInfo(i) {
  const el = document.getElementById("grid-info");
  if (!el || !i) return;
  const bg = backdrop(i.backdrop) || poster(i.poster);
  const meta = [i.year, i.rating ? `★ ${Number(i.rating).toFixed(1)}` : null].filter(Boolean).join(" · ");
  el.innerHTML = `
    <div class="gi-back" style="${bg ? `background-image:url('${bg}')` : ""}"></div>
    <div class="gi-title">${esc(i.title)}</div>
    <div class="gi-meta">${esc(meta)}</div>
    <div class="gi-overview">${esc(i.overview || "Нет описания")}</div>
    ${i.cast && i.cast.length ? `<div class="gi-cast">${esc(i.cast.slice(0, 5).join(", "))}</div>` : ""}`;
}

/* ---------- Деталь фильма ---------- */
function renderDetail() {
  const i = state.current;
  if (!i) { state.screen = "grid"; return render(); }
  const bg = backdrop(i.backdrop) || poster(i.poster);
  const meta = [i.year, i.rating ? `★ ${Number(i.rating).toFixed(1)}` : null, i.season ? `S${i.season}${i.episode ? "E" + i.episode : ""}` : null].filter(Boolean).join(" · ");
  app.innerHTML = `
    <div class="detail">
      <div class="detail-backdrop" style="${bg ? `background-image:url('${bg}')` : ""}"></div>
      <div class="detail-grad"></div>
      <div class="detail-body">
        <div class="detail-title">${esc(i.title)}</div>
        <div class="detail-meta">${esc(meta)}</div>
        <div class="detail-overview">${esc(i.overview || "Нет описания")}</div>
        ${i.cast && i.cast.length ? `<div class="detail-cast">В ролях: ${esc(i.cast.join(", "))}</div>` : ""}
        <div class="detail-shots" id="detail-shots"></div>
        <button class="detail-play" id="detail-play" data-id="${esc(i.id)}">▶ Смотреть</button>
      </div>
    </div>`;
  const playBtn = document.getElementById("detail-play");
  playBtn.addEventListener("click", () => play(i.id));
  playBtn.focus(); // фокус сразу на Play
  loadShots(i.id);
}

async function loadShots(id) {
  try {
    const { backdrops } = await (await fetch("/api/images?id=" + encodeURIComponent(id))).json();
    const el = document.getElementById("detail-shots");
    if (el && backdrops && backdrops.length) {
      el.innerHTML = backdrops.slice(0, 5).map((p) => `<img src="${shot(p)}" alt="" />`).join("");
    }
  } catch (_) {}
}

/* ---------- Переходы (через History API: браузерная «Назад» тоже работает) ---------- */
function applyState(s) {
  state = { screen: s.screen || "categories", type: s.type || state.type, current: null };
  if (state.screen === "detail") state.current = items.find((i) => i.id === s.id) || null;
  render();
}
function navigate(s) { history.pushState(s, ""); applyState(s); }
function enterGrid(type) { navigate({ screen: "grid", type }); }
function enterDetail(item) { navigate({ screen: "detail", type: state.type, id: item.id }); }
// Назад: кнопка «Назад», Esc/Backspace пульта И браузерная «Назад» — всё через историю.
function back() { if (state.screen !== "categories") history.back(); }
window.addEventListener("popstate", (e) => applyState(e.state || { screen: "categories" }));

/* ---------- Навигация пультом ---------- */
let fsTried = false;
document.addEventListener("keydown", (e) => {
  if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) { e.preventDefault(); back(); return; }
  const cur = document.activeElement;

  if (state.screen === "categories") {
    const tiles = [...app.querySelectorAll(".cat-tile")];
    const idx = tiles.indexOf(cur);
    if (e.key === "ArrowRight") { e.preventDefault(); tiles[Math.min(tiles.length - 1, idx + 1)]?.focus(); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); tiles[Math.max(0, idx - 1)]?.focus(); }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); cur?.dataset?.type && enterGrid(cur.dataset.type); }
    return;
  }

  if (state.screen === "grid") {
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) {
      e.preventDefault();
      if (!fsTried) { fsTried = true; document.documentElement.requestFullscreen?.().catch(() => {}); }
      const next = nearest(cur, { ArrowLeft: "left", ArrowRight: "right", ArrowUp: "up", ArrowDown: "down" }[e.key]);
      if (next) { next.focus(); next.scrollIntoView({ block: "nearest", behavior: "smooth" }); }
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault(); cur?.click();
    }
    return;
  }

  if (state.screen === "detail") {
    if (e.key === "Enter" || e.key === " " || e.key === "MediaPlayPause") { e.preventDefault(); cur?.click(); }
  }
});

function nearest(cur, dir) {
  const SEL = ".tv-card, .grid-back";
  if (!cur || !cur.matches(SEL)) return app.querySelector(".tv-card") || app.querySelector(".grid-back");
  const cards = [...app.querySelectorAll(SEL)];
  const cr = cur.getBoundingClientRect(), cx = cr.left + cr.width / 2, cy = cr.top + cr.height / 2;
  let best = null, bestScore = Infinity;
  for (const el of cards) {
    if (el === cur) continue;
    const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
    const dx = x - cx, dy = y - cy;
    let p, s;
    if (dir === "left") { if (dx >= -2) continue; p = -dx; s = Math.abs(dy); }
    else if (dir === "right") { if (dx <= 2) continue; p = dx; s = Math.abs(dy); }
    else if (dir === "up") { if (dy >= -2) continue; p = -dy; s = Math.abs(dx); }
    else { if (dy <= 2) continue; p = dy; s = Math.abs(dx); }
    const score = p + s * 2;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  return best;
}

async function play(id) {
  showOverlay("▶ Запускаю плеер…");
  try {
    const r = await (await fetch("/api/play?id=" + encodeURIComponent(id))).json();
    showOverlay(r.ok ? "▶ Играет в плеере" : "⚠️ " + (r.error || "ошибка"));
  } catch (_) { showOverlay("⚠️ Не удалось запустить"); }
  setTimeout(hideOverlay, 2500);
}
function showOverlay(t) { document.getElementById("tv-overlay-text").textContent = t; document.getElementById("tv-overlay").classList.remove("hidden"); }
function hideOverlay() { document.getElementById("tv-overlay").classList.add("hidden"); }

window.addEventListener("resize", () => { if (state.screen === "grid") computeCardWidth(); });
load();
