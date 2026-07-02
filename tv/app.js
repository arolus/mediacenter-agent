// TV-режим: категории → сетка 4×2 с описанием слева → страница фильма с Play.
// Навигация пультом (Flirc = клавиши): стрелки, OK/Enter, Back (Esc/Backspace).
const IMG = "https://image.tmdb.org/t/p";
const poster = (p) => (p ? `${IMG}/w342${p}` : null);
const backdrop = (p) => (p ? `${IMG}/w1280${p}` : null);
const shot = (p) => `${IMG}/w500${p}`;
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

/* ---------- Иконки (стиль SF Symbols / Lucide: stroke, скруглённые) ---------- */
const icon = (paths, cls = "") =>
  `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;
const ICONS = {
  movie: (cls) => icon(`<path d="M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3Z"/><path d="m6.2 5.3 3.1 3.9"/><path d="m12.4 3.4 3.1 4"/><path d="M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>`, cls),
  cartoon: (cls) => icon(`<path d="M9 10h.01"/><path d="M15 10h.01"/><path d="M12 2a8 8 0 0 0-8 8v12l3-3 2.5 2.5L12 19l2.5 2.5L17 19l3 3V10a8 8 0 0 0-8-8z"/>`, cls),
  series: (cls) => icon(`<rect width="20" height="15" x="2" y="7" rx="2"/><polyline points="17 2 12 7 7 2"/>`, cls),
  back: (cls) => icon(`<path d="m15 18-6-6 6-6"/>`, cls),
  download: (cls) => icon(`<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="3" y2="15"/>`, cls),
  play: (cls) => `<svg class="${cls}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5.5v13a1 1 0 0 0 1.54.84l10.2-6.5a1 1 0 0 0 0-1.68L9.54 4.66A1 1 0 0 0 8 5.5Z"/></svg>`,
  check: (cls) => icon(`<path d="m5 13 4 4 10-10"/>`, cls),
  star: (cls) => `<svg class="${cls}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2.5l2.9 6 6.6.9-4.8 4.6 1.2 6.5L12 17.4 6.1 20.5l1.2-6.5L2.5 9.4l6.6-.9z"/></svg>`
};
const logo = (cls) =>
  `<span class="grid ${cls} place-items-center rounded-2xl bg-gradient-to-br from-violet-500 to-indigo-600 text-white shadow-lg shadow-violet-500/30">${ICONS.play("h-[55%] w-[55%] translate-x-[4%]")}</span>`;

const CATS = [
  { type: "movie", label: "Фильмы", icon: ICONS.movie },
  { type: "cartoon", label: "Мультфильмы", icon: ICONS.cartoon },
  { type: "series", label: "Сериалы", icon: ICONS.series }
];

const app = document.getElementById("app");
let deviceName = "";
let items = [];
let loaded = false; // первая загрузка библиотеки завершена
let state = { screen: "categories", type: "movie" };

let playerMissing = false;

// Крутилка загрузки (первая подгрузка библиотеки может занять пару секунд)
const spinner = (label) => `
  <div class="flex h-full w-full flex-col items-center justify-center space-y-4 py-16">
    <svg class="h-10 w-10 animate-spin text-violet-400" viewBox="0 0 24 24" fill="none">
      <circle class="opacity-20" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
      <path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
    </svg>
    <div class="text-lg text-zinc-400">${label}</div>
  </div>`;
// Зелёная галка «просмотрено» на карточке
const watchedBadge = `<div class="absolute top-2 left-2 grid h-6 w-6 place-items-center rounded-full bg-emerald-500/90 text-white shadow">${ICONS.check("h-4 w-4")}</div>`;

async function load() {
  try { deviceName = (await (await fetch("/api/device")).json()).name || ""; } catch (_) {}
  try { const st = await (await fetch("/api/player-status")).json(); playerMissing = st && st.installed === false; } catch (_) {}
  await reloadLibrary();
  history.replaceState({ screen: "categories" }, ""); // корневая запись истории
  render();
  tryLandscape(false); // PWA/standalone может залочить сразу; браузер — при первом взаимодействии
  // live-обновление: любое изменение (скан, «Исправить» из дашборда, переименование) прилетает сюда
  try {
    const es = new EventSource("/api/events");
    es.onmessage = async () => { await reloadLibrary(); rerenderKeepingFocus(); };
  } catch (_) {}
}

async function reloadLibrary() {
  try { items = await (await fetch("/api/library")).json(); loaded = true; } catch (_) { items = items || []; }
}

const byType = (t) => items.filter((i) => i.type === t).sort((a, b) => (a.title || "").localeCompare(b.title || ""));

// Серии одного сериала (общий tmdbId или одинаковое название) — ОДНА карточка со списком серий.
function groupTitles(list) {
  const map = new Map();
  for (const i of list) {
    const key = i.tmdbId ? "t:" + i.tmdbId : "n:" + String(i.title || i.fileName || i.id).toLowerCase().trim();
    let e = map.get(key);
    if (!e) { e = { ...i, episodes: [] }; map.set(key, e); }
    if (i.tmdbId && !e.tmdbId) Object.assign(e, i, { episodes: e.episodes });
    e.episodes.push(i);
  }
  const arr = [...map.values()];
  for (const e of arr) {
    e.episodes.sort((a, b) => (a.season || 0) - (b.season || 0) || (a.episode || 0) - (b.episode || 0) ||
      String(a.fileName || "").localeCompare(String(b.fileName || "")));
  }
  return arr.sort((a, b) => (a.title || "").localeCompare(b.title || ""));
}

// Части одной франшизы TMDb (Шрэк 1-2-3…) → «коллекция»: карточка → страница частей → деталь части.
function groupCollections(entries) {
  const map = new Map();
  const out = [];
  for (const e of entries) {
    const c = e.collection;
    if (!c || !c.id) { out.push(e); continue; }
    let col = map.get(c.id);
    if (!col) {
      col = {
        isCollection: true, id: "col_" + c.id, type: e.type,
        title: String(c.name || e.title).replace(/\s*[:(—-]*\s*коллекция\)?\s*$/i, "").trim(),
        poster: c.poster || e.poster, backdrop: e.backdrop, parts: []
      };
      map.set(c.id, col);
      out.push(col);
    }
    col.parts.push(e);
  }
  return out
    .map((e) => (e.isCollection && e.parts.length === 1 ? e.parts[0] : e)) // одна часть — обычный фильм
    .map((e) => { if (e.isCollection) e.parts.sort((a, b) => (a.year || 0) - (b.year || 0)); return e; })
    .sort((a, b) => (a.title || "").localeCompare(b.title || ""));
}

const entriesForType = (t) => groupCollections(groupTitles(byType(t)));
// Поиск сущности по id: среди верхнеуровневых карточек И внутри коллекций.
function findEntry(t, id) {
  for (const e of entriesForType(t)) {
    if (e.id === id) return e;
    if (e.isCollection) { const p = e.parts.find((x) => x.id === id); if (p) return p; }
  }
  return null;
}
const isWatched = (e) => e.isCollection
  ? e.parts.every(isWatched)
  : (e.episodes && e.episodes.length ? e.episodes.every((x) => x.watched) : e.watched === true);

function render() {
  if (state.screen === "categories") renderCategories();
  else if (state.screen === "grid") renderGrid();
  else if (state.screen === "collection") renderCollection();
  else if (state.screen === "detail") renderDetail();
}

// При live-обновлении перерисовываем текущий экран, сохраняя фокус по id.
function rerenderKeepingFocus() {
  const focusedId = document.activeElement?.dataset?.id;
  if ((state.screen === "detail" || state.screen === "collection") && state.current) {
    const cur = findEntry(state.type, state.current.id);
    if (cur) state.current = cur;      // обновить состав серий/частей
    else state.screen = "grid";        // элемент исчез — назад в сетку
  }
  render();
  if (focusedId) {
    const el = app.querySelector(`[data-id="${CSS.escape(focusedId)}"]`);
    if (el) el.focus();
  }
}

/* ---------- Ориентация: TV-режим живёт в ландшафте ---------- */
let oriTried = false;
async function tryLandscape(interactive) {
  try { await screen.orientation.lock("landscape"); return; } catch (_) {}
  if (!interactive) return;
  try {
    await document.documentElement.requestFullscreen();
    await screen.orientation.lock("landscape");
  } catch (_) {}
}
// Первое взаимодействие (клик/клавиша) — единственный момент, когда браузер разрешит fullscreen+lock.
function armOrientation() {
  if (oriTried) return;
  oriTried = true;
  tryLandscape(true);
}
document.addEventListener("pointerdown", armOrientation, { once: true, capture: true });

/* ---------- Категории ---------- */
function renderCategories() {
  app.innerHTML = `
    <div class="flex h-full flex-col overflow-y-auto">
      <div class="flex items-center space-x-5 px-12 pt-[clamp(12px,calc(var(--uivh)*3),32px)]">
        ${logo("h-[clamp(36px,calc(var(--uivh)*6),48px)] w-[clamp(36px,calc(var(--uivh)*6),48px)]")}
        <span class="text-[clamp(22px,calc(var(--uivh)*4),30px)] font-extrabold tracking-tight">MediaCenter</span>
        ${deviceName ? `<span class="rounded-full border border-zinc-800 bg-zinc-900/80 px-4 py-1.5 text-lg text-zinc-400">${esc(deviceName)}</span>` : ""}
      </div>
      ${playerMissing ? `
        <button id="cat-vlc" tabindex="0" class="mx-12 mt-4 flex cursor-pointer items-center self-start rounded-2xl border border-red-500/30 bg-red-500/10 px-6 py-3.5 text-lg font-semibold text-red-300 outline-none transition focus:scale-[1.02] focus:border-red-400 focus:ring-4 focus:ring-red-500/30">
          ${ICONS.download("mr-3 h-6 w-6")} Установить плеер VLC — нужен для просмотра
        </button>` : ""}
      <div class="flex flex-1 items-center justify-center space-x-10 px-12">
        ${CATS.map((c) => `
          <div class="cat-tile group flex h-[clamp(250px,calc(var(--uivh)*60),420px)] w-[clamp(220px,calc(var(--uivw)*24),340px)] cursor-pointer flex-col items-center justify-center space-y-[clamp(12px,calc(var(--uivh)*3),28px)] rounded-[28px] border border-zinc-800 bg-zinc-900/70 outline-none backdrop-blur transition duration-150 focus:scale-105 focus:border-violet-500/60 focus:shadow-[0_0_70px_-12px_rgba(139,92,246,.55)] focus:ring-4 focus:ring-violet-500/25" tabindex="0" data-type="${c.type}">
            <div class="grid h-[clamp(64px,calc(var(--uivh)*16),112px)] w-[clamp(64px,calc(var(--uivh)*16),112px)] place-items-center rounded-3xl bg-gradient-to-br from-violet-500/15 to-indigo-500/5 text-violet-300 ring-1 ring-violet-500/20 transition duration-150 group-focus:from-violet-500 group-focus:to-indigo-600 group-focus:text-white group-focus:shadow-lg group-focus:shadow-violet-500/40 group-focus:ring-0">
              ${c.icon("h-1/2 w-1/2")}
            </div>
            <div class="text-[clamp(20px,calc(var(--uivh)*3.6),30px)] font-bold tracking-tight">${c.label}</div>
            <div class="rounded-full bg-zinc-800/80 px-4 py-1 text-[clamp(14px,calc(var(--uivh)*2.2),18px)] text-zinc-400">${loaded ? entriesForType(c.type).length + " шт." : "…"}</div>
          </div>`).join("")}
      </div>
    </div>`;
  app.querySelectorAll(".cat-tile").forEach((t) => t.addEventListener("click", () => enterGrid(t.dataset.type)));
  const vlc = document.getElementById("cat-vlc");
  if (vlc) vlc.addEventListener("click", installVLC);
  // фокус: если VLC не установлен — сразу на кнопку установки, иначе на текущую категорию
  if (vlc) vlc.focus();
  else { const idx = CATS.findIndex((c) => c.type === state.type); app.querySelectorAll(".cat-tile")[idx >= 0 ? idx : 0].focus(); }
}

async function installVLC() {
  showOverlay("Скачиваю VLC…");
  try {
    const r = await (await fetch("/api/install-player")).json();
    if (r.launched) showOverlay("Подтверди установку VLC на телефоне ✓");
    else if (r.downloaded) showOverlay("VLC скачан в Загрузки — открой и установи");
    else showOverlay("⚠️ " + (r.error || "не удалось"));
  } catch (_) { showOverlay("⚠️ Не удалось скачать VLC"); }
  setTimeout(hideOverlay, 4000);
}

/* ---------- Сетка ---------- */
// Размер UI-вьюпорта: в портрете интерфейс повёрнут на 90° (#rot), поэтому оси меняются местами.
const isPortrait = () => matchMedia("(orientation: portrait)").matches;
const uiW = () => (isPortrait() ? window.innerHeight : window.innerWidth);
const uiH = () => (isPortrait() ? window.innerWidth : window.innerHeight);

/* ---------- Ручной тач-скролл ----------
   Старый Chrome на нодах (83) не скроллит жестами вложенные overflow-контейнеры вообще,
   а внутри transform:rotate — тем более. Транслируем свайп вручную в обоих режимах:
   в портрете (UI повёрнут) палец по физическому X = UI-ось Y, в ландшафте — обычный Y. */
function nearestScrollable(el) {
  for (let n = el; n && n !== document.documentElement; n = n.parentElement) {
    const s = getComputedStyle(n);
    if (/(auto|scroll)/.test(s.overflowY) && n.scrollHeight > n.clientHeight + 1) return n;
  }
  return null;
}
let tX = 0, tY = 0, tEl = null, tActive = false;
document.addEventListener("touchstart", (e) => {
  const t = e.touches[0];
  tX = t.clientX; tY = t.clientY; tActive = false;
  tEl = nearestScrollable(e.target);
}, { passive: true });
document.addEventListener("touchmove", (e) => {
  if (!tEl) return;
  const t = e.touches[0];
  const dUI = isPortrait() ? (tX - t.clientX) : (tY - t.clientY);
  if (!tActive && Math.abs(dUI) < 8) return; // порог: не мешаем тапам
  tActive = true;
  tEl.scrollTop += dUI;
  tX = t.clientX; tY = t.clientY;
  e.preventDefault();
}, { passive: false });

function computeCardWidth() {
  // На небольших экранах (телефон) — 3 карточки в ряд, на TV — 4.
  const W = uiW(), H = uiH();
  const cols = W < 1000 ? 3 : 4;
  const side = Math.min(460, Math.max(280, W * 0.32));
  const gap = 20, gridPad = 80, headerH = 70, titleH = 50;
  const byW = (W - side - gridPad - (cols - 1) * gap) / cols;
  const byH = ((H - headerH) / 2 - gap - titleH) / 1.5; // 2 ряда, постер 2:3
  const w = Math.max(96, Math.floor(Math.min(byW, byH)));
  document.documentElement.style.setProperty("--card-w", w + "px");
  document.documentElement.style.setProperty("--cols", cols);
}

// Карточка сетки (фильм / сериал / коллекция) — общая для сетки и страницы коллекции.
function cardHtml(i) {
  const badge = i.isCollection
    ? `${i.parts.length} части`
    : (i.episodes && i.episodes.length > 1 ? `${i.episodes.length} серий` : (i.isCollectionPart && i.year ? String(i.year) : ""));
  return `
    <div class="tv-card relative w-[var(--card-w)] cursor-pointer overflow-hidden rounded-2xl bg-zinc-900 ring-1 ring-white/5 outline-none transition duration-150 focus:z-10 focus:scale-[1.07] focus:shadow-[0_16px_50px_-8px_rgba(139,92,246,.45)] focus:ring-[3px] focus:ring-violet-500" tabindex="0" data-id="${esc(i.id)}">
      ${poster(i.poster) ? `<div class="h-0 w-full bg-zinc-800 bg-cover bg-center pb-[150%]" style="background-image:url('${poster(i.poster)}')"></div>` : `<div class="relative h-0 w-full bg-gradient-to-br from-zinc-800 to-zinc-900 pb-[150%]"><div class="absolute top-0 right-0 bottom-0 left-0 flex items-center justify-center p-2 text-center text-sm text-zinc-400">${esc(i.title)}</div></div>`}
      ${badge ? `<div class="absolute top-2 right-2 rounded-full bg-black/75 px-2.5 py-1 text-xs font-semibold text-zinc-100">${badge}</div>` : ""}
      ${isWatched(i) ? watchedBadge : ""}
      <div class="truncate px-3 pb-3 pt-2 text-[15px] font-semibold leading-tight">${esc(i.title)}</div>
    </div>`;
}

function renderGrid() {
  computeCardWidth();
  const list = entriesForType(state.type);
  const cat = CATS.find((c) => c.type === state.type);
  app.innerHTML = `
    <div class="flex h-full">
      <div class="flex w-[32%] min-w-[280px] max-w-[460px] flex-col space-y-3.5 overflow-hidden border-r border-white/5 bg-zinc-900/60 px-9 py-10 backdrop-blur-xl" id="grid-info"></div>
      <div class="flex-1 overflow-y-auto px-10 py-7">
        <div class="mb-5 flex items-center space-x-4">
          <button class="grid-back flex cursor-pointer items-center rounded-xl border border-white/10 bg-white/5 py-2.5 pl-3 pr-5 text-lg font-semibold outline-none backdrop-blur transition duration-150 focus:scale-105 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/30" tabindex="0">${ICONS.back("mr-1.5 h-5 w-5")} Назад</button>
          <span class="text-violet-400">${cat.icon("h-7 w-7")}</span>
          <h2 class="m-0 text-2xl font-bold tracking-tight">${cat.label}</h2>
          <span class="rounded-full bg-zinc-800/80 px-3 py-0.5 text-base text-zinc-400">${list.length}</span>
        </div>
        <div class="grid grid-cols-[repeat(var(--cols),var(--card-w))] justify-start gap-5">
          ${list.map(cardHtml).join("") || (loaded ? '<p class="tv-empty p-14 text-2xl text-zinc-400">Пусто</p>' : spinner("Загружаю медиатеку…"))}
        </div>
      </div>
    </div>`;
  app.querySelectorAll(".tv-card").forEach((card) => {
    const item = list.find((i) => i.id === card.dataset.id);
    card.addEventListener("focus", () => updateInfo(item));
    card.addEventListener("click", () => item.isCollection ? enterCollection(item) : enterDetail(item));
  });
  app.querySelector(".grid-back").addEventListener("click", back);
  const first = app.querySelector(".tv-card") || app.querySelector(".grid-back");
  if (first) first.focus();
}

/* ---------- Коллекция (франшиза): страница частей ---------- */
function renderCollection() {
  computeCardWidth();
  const col = state.current;
  if (!col || !col.isCollection) { state.screen = "grid"; return render(); }
  app.innerHTML = `
    <div class="flex h-full flex-col overflow-y-auto px-10 py-7">
      <div class="mb-5 flex items-center space-x-4">
        <button class="grid-back flex cursor-pointer items-center rounded-xl border border-white/10 bg-white/5 py-2.5 pl-3 pr-5 text-lg font-semibold outline-none backdrop-blur transition duration-150 focus:scale-105 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/30" tabindex="0">${ICONS.back("mr-1.5 h-5 w-5")} Назад</button>
        <h2 class="m-0 truncate text-2xl font-bold tracking-tight">${esc(col.title)}</h2>
        <span class="rounded-full bg-zinc-800/80 px-3 py-0.5 text-base text-zinc-400">${col.parts.length} части</span>
      </div>
      <div class="grid grid-cols-[repeat(var(--cols),var(--card-w))] justify-start gap-5">
        ${col.parts.map((p) => cardHtml({ ...p, isCollectionPart: true })).join("")}
      </div>
    </div>`;
  app.querySelectorAll(".tv-card").forEach((card) => {
    const part = col.parts.find((p) => p.id === card.dataset.id);
    card.addEventListener("click", () => enterDetail(part));
  });
  app.querySelector(".grid-back").addEventListener("click", back);
  (app.querySelector(".tv-card") || app.querySelector(".grid-back")).focus();
}

const metaChips = (i) => [
  i.year ? `<span class="rounded-full bg-zinc-800/90 px-3 py-1 text-base font-medium text-zinc-300">${i.year}</span>` : "",
  i.rating ? `<span class="flex items-center rounded-full bg-amber-400/10 px-3 py-1 text-base font-semibold text-amber-400 ring-1 ring-amber-400/20">${ICONS.star("mr-1.5 h-4 w-4")}${Number(i.rating).toFixed(1)}</span>` : "",
  i.season ? `<span class="rounded-full bg-violet-500/10 px-3 py-1 text-base font-medium text-violet-300 ring-1 ring-violet-500/20">S${i.season}${i.episode ? "E" + i.episode : ""}</span>` : "",
  isWatched(i) ? `<span class="flex items-center rounded-full bg-emerald-500/10 px-3 py-1 text-base font-semibold text-emerald-400 ring-1 ring-emerald-500/20">${ICONS.check("mr-1.5 h-4 w-4")}Просмотрено</span>` : ""
].filter(Boolean).join("");

function updateInfo(i) {
  const el = document.getElementById("grid-info");
  if (!el || !i) return;
  const bg = backdrop(i.backdrop) || poster(i.poster);
  el.innerHTML = `
    <div class="h-[clamp(140px,calc(var(--uivh)*38),320px)] w-full flex-none rounded-2xl bg-zinc-800 bg-cover bg-center shadow-2xl shadow-black/50 ring-1 ring-white/10" style="${bg ? `background-image:url('${bg}')` : ""}"></div>
    <div class="mt-1 text-3xl font-bold leading-tight tracking-tight">${esc(i.title)}</div>
    <div class="flex flex-wrap space-x-2">${metaChips(i)}${i.episodes && i.episodes.length > 1 ? `<span class="rounded-full bg-zinc-800/90 px-3 py-1 text-base font-medium text-zinc-300">${i.episodes.length} серий</span>` : ""}</div>
    <div class="line-clamp-6 text-[17px] leading-relaxed text-zinc-300">${esc(i.overview || "Нет описания")}</div>
    ${i.cast && i.cast.length ? `<div class="text-[15px] text-zinc-500">${esc(i.cast.slice(0, 5).join(", "))}</div>` : ""}`;
}

/* ---------- Деталь фильма ---------- */
const epLabel = (ep) => ep.episode != null
  ? `Серия ${ep.episode}${ep.season ? ` · сезон ${ep.season}` : ""}`
  : (ep.fileName || "серия");

function renderDetail() {
  const i = state.current;
  if (!i) { state.screen = "grid"; return render(); }
  const bg = backdrop(i.backdrop) || poster(i.poster);
  const eps = i.episodes && i.episodes.length ? i.episodes : [i];
  const multi = eps.length > 1;
  app.innerHTML = `
    <div class="relative h-full overflow-hidden">
      <div class="absolute top-0 right-0 bottom-0 left-0 bg-black bg-cover bg-center brightness-[.38] saturate-[1.1]" style="${bg ? `background-image:url('${bg}')` : ""}"></div>
      <div class="absolute top-0 right-0 bottom-0 left-0 bg-gradient-to-r from-zinc-950/95 via-zinc-950/65 to-zinc-950/30"></div>
      <div class="absolute right-0 bottom-0 left-0 h-40 bg-gradient-to-t from-zinc-950/90 to-transparent"></div>
      <div class="relative flex h-full max-w-[1100px] flex-col space-y-[clamp(8px,calc(var(--uivh)*1.8),16px)] overflow-y-auto px-16 py-[clamp(16px,calc(var(--uivh)*4),48px)]">
        <button class="dfoc flex cursor-pointer items-center self-start rounded-xl border border-white/10 bg-white/5 py-2 pl-3 pr-6 text-[clamp(15px,calc(var(--uivh)*2.4),18px)] font-semibold outline-none backdrop-blur transition duration-150 focus:scale-105 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/30" id="detail-back">${ICONS.back("mr-1.5 h-5 w-5")} Назад</button>
        <div class="mt-1 text-[clamp(28px,calc(var(--uivh)*7),48px)] font-extrabold tracking-tight drop-shadow-lg">${esc(i.title)}</div>
        <div class="flex flex-wrap space-x-2">${metaChips(i)}${multi ? `<span class="rounded-full bg-zinc-800/90 px-3 py-1 text-base font-medium text-zinc-300">${eps.length} серий</span>` : ""}</div>
        <div class="line-clamp-5 max-w-[780px] text-[clamp(15px,calc(var(--uivh)*2.7),20px)] leading-relaxed text-zinc-200 drop-shadow">${esc(i.overview || "Нет описания")}</div>
        ${i.cast && i.cast.length ? `<div class="max-w-[780px] text-[clamp(13px,calc(var(--uivh)*2.2),16px)] text-zinc-400">В ролях: ${esc(i.cast.join(", "))}</div>` : ""}
        ${multi ? "" : `<div class="mt-1 flex space-x-4 overflow-hidden">${(i.backdrops || []).slice(0, 5).map((p) => `<img class="h-[clamp(80px,calc(var(--uivh)*20),150px)] flex-none rounded-xl shadow-xl shadow-black/40 ring-1 ring-white/10" src="${shot(p)}" alt="" />`).join("")}</div>`}
        ${multi ? `
        <div class="max-w-[640px] flex-1 space-y-1.5 overflow-y-auto pr-2">
          ${eps.map((ep) => `
            <button class="dfoc ep flex w-full cursor-pointer items-center rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-left text-[15px] outline-none backdrop-blur transition focus:border-violet-400 focus:bg-violet-500/15 focus:ring-2 focus:ring-violet-500/40 ${ep.watched ? "opacity-60" : ""}" data-id="${esc(ep.id)}">
              <span class="mr-3 ${ep.watched ? "text-emerald-400" : "text-violet-300"}">${ep.watched ? ICONS.check("h-4 w-4") : ICONS.play("h-4 w-4")}</span>
              <span class="truncate">${esc(epLabel(ep))}</span>
              ${ep.watched ? `<span class="ml-auto pl-3 text-xs text-emerald-400">просмотрено</span>` : ""}
            </button>`).join("")}
        </div>` : `
        <button class="dfoc !mt-auto flex cursor-pointer items-center self-start rounded-2xl bg-gradient-to-r from-violet-600 to-indigo-600 px-[clamp(28px,calc(var(--uivw)*5),48px)] py-[clamp(10px,calc(var(--uivh)*2.2),16px)] text-[clamp(18px,calc(var(--uivh)*3.2),24px)] font-bold text-white shadow-xl shadow-violet-600/40 outline-none transition focus:scale-[1.04] focus:ring-4 focus:ring-violet-400/50" id="detail-play" data-id="${esc(i.id)}">${ICONS.play("mr-3 h-[1.2em] w-[1.2em]")} Смотреть</button>`}
      </div>
    </div>`;
  document.getElementById("detail-back").addEventListener("click", back);
  app.querySelectorAll(".ep").forEach((b) => b.addEventListener("click", () => play(b.dataset.id)));
  const playBtn = document.getElementById("detail-play");
  if (playBtn) playBtn.addEventListener("click", () => play(i.id));
  (app.querySelector(".ep") || playBtn || document.getElementById("detail-back")).focus();
}

/* ---------- Переходы (через History API: браузерная «Назад» тоже работает) ---------- */
function applyState(s) {
  state = { screen: s.screen || "categories", type: s.type || state.type, current: null };
  if (state.screen === "detail" || state.screen === "collection") {
    state.current = findEntry(state.type, s.id) || null;
  }
  render();
  // Возврат «Назад»: встаём на ту же карточку, с которой уходили (и скроллим к ней).
  if (s.focusId) {
    const el = app.querySelector(`[data-id="${CSS.escape(s.focusId)}"]`);
    if (el) { el.focus(); el.scrollIntoView({ block: "center" }); }
  }
}
function navigate(s) {
  // Перед уходом запоминаем текущую карточку в ТЕКУЩЕЙ записи истории —
  // браузерная/пультовая «Назад» вернёт ровно на неё, а не в начало списка.
  const focusId = document.activeElement?.dataset?.id || null;
  history.replaceState({ ...(history.state || {}), focusId }, "");
  history.pushState(s, "");
  applyState(s);
}
function enterGrid(type) { navigate({ screen: "grid", type }); }
function enterCollection(col) { navigate({ screen: "collection", type: state.type, id: col.id }); }
function enterDetail(item) { navigate({ screen: "detail", type: state.type, id: item.id }); }
// Назад: кнопка «Назад», Esc/Backspace пульта И браузерная «Назад» — всё через историю.
function back() { if (state.screen !== "categories") history.back(); }
window.addEventListener("popstate", (e) => applyState(e.state || { screen: "categories" }));

/* ---------- Навигация пультом ---------- */
document.addEventListener("keydown", (e) => {
  armOrientation(); // первая клавиша — момент для fullscreen + landscape-lock
  if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) { e.preventDefault(); back(); return; }
  const cur = document.activeElement;

  if (state.screen === "categories") {
    const tiles = [...app.querySelectorAll(".cat-tile")];
    const vlc = document.getElementById("cat-vlc");
    const idx = tiles.indexOf(cur);
    if (cur === vlc) {
      if (e.key === "ArrowDown") { e.preventDefault(); tiles[0]?.focus(); }
      else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); installVLC(); }
      return;
    }
    if (e.key === "ArrowRight") { e.preventDefault(); tiles[Math.min(tiles.length - 1, idx + 1)]?.focus(); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); tiles[Math.max(0, idx - 1)]?.focus(); }
    else if (e.key === "ArrowUp" && vlc) { e.preventDefault(); vlc.focus(); }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); cur?.dataset?.type && enterGrid(cur.dataset.type); }
    return;
  }

  if (state.screen === "grid" || state.screen === "collection") {
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) {
      e.preventDefault();
      const next = nearest(cur, { ArrowLeft: "left", ArrowRight: "right", ArrowUp: "up", ArrowDown: "down" }[e.key]);
      if (next) { next.focus(); next.scrollIntoView({ block: "nearest", behavior: "smooth" }); }
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault(); cur?.click();
    }
    return;
  }

  if (state.screen === "detail") {
    // фокус ходит по цепочке: Назад → серии (список) → Смотреть
    const foc = [...app.querySelectorAll(".dfoc")];
    const idx = foc.indexOf(cur);
    if (["ArrowDown", "ArrowRight"].includes(e.key)) {
      e.preventDefault();
      const n = foc[idx < 0 ? 0 : Math.min(foc.length - 1, idx + 1)];
      if (n) { n.focus(); n.scrollIntoView({ block: "nearest", behavior: "smooth" }); }
    } else if (["ArrowUp", "ArrowLeft"].includes(e.key)) {
      e.preventDefault();
      const n = foc[idx < 0 ? 0 : Math.max(0, idx - 1)];
      if (n) { n.focus(); n.scrollIntoView({ block: "nearest", behavior: "smooth" }); }
    } else if (e.key === "Enter" || e.key === " " || e.key === "MediaPlayPause") { e.preventDefault(); cur?.click(); }
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
  showOverlay("Запускаю плеер…", true);
  try {
    const r = await (await fetch("/api/play?id=" + encodeURIComponent(id))).json();
    showOverlay(r.ok ? "Играет в плеере" : "⚠️ " + (r.error || "ошибка"), r.ok);
  } catch (_) { showOverlay("⚠️ Не удалось запустить"); }
  setTimeout(hideOverlay, 2500);
}
function showOverlay(t, withPlay) {
  document.getElementById("tv-overlay-text").innerHTML =
    (withPlay ? `<span class="text-violet-400">${ICONS.play("h-8 w-8")}</span>` : "") + `<span>${esc(t)}</span>`;
  document.getElementById("tv-overlay").classList.remove("hidden");
}
function hideOverlay() { document.getElementById("tv-overlay").classList.add("hidden"); }

window.addEventListener("resize", () => {
  if (state.screen === "grid" || state.screen === "collection") computeCardWidth();
});
load();
