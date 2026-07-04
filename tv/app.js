// TV-режим: категории → сетка 4×2 с описанием слева → страница фильма с Play.
// Навигация пультом (Flirc = клавиши): стрелки, OK/Enter, Back (Esc/Backspace).
const IMG = "https://image.tmdb.org/t/p";
const poster = (p) => (p ? `${IMG}/w342${p}` : null);
const backdrop = (p) => (p ? `${IMG}/w1280${p}` : null);
const shot = (p) => `${IMG}/w500${p}`;
const prof = (p) => (p ? `${IMG}/w185${p}` : null); // фото актёра
const fmtDur = (sec) => {
  if (!sec) return "";
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = Math.floor(sec % 60);
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
};
const fmtDate = (d) => {
  const m = String(d || "").match(/^(\d{4})-(\d{2})-(\d{2})/);
  return m ? `${m[3]}.${m[2]}.${m[1]}` : "";
};
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

/* ---------- Иконки (стиль SF Symbols / Lucide: stroke, скруглённые) ---------- */
const icon = (paths, cls = "") =>
  `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;
const ICONS = {
  movie: (cls) => icon(`<path d="M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3Z"/><path d="m6.2 5.3 3.1 3.9"/><path d="m12.4 3.4 3.1 4"/><path d="M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>`, cls),
  cartoon: (cls) => icon(`<path d="M9 10h.01"/><path d="M15 10h.01"/><path d="M12 2a8 8 0 0 0-8 8v12l3-3 2.5 2.5L12 19l2.5 2.5L17 19l3 3V10a8 8 0 0 0-8-8z"/>`, cls),
  series: (cls) => icon(`<rect width="20" height="15" x="2" y="7" rx="2"/><polyline points="17 2 12 7 7 2"/>`, cls),
  back: (cls) => icon(`<path d="m15 18-6-6 6-6"/>`, cls),
  folderBack: (cls) => icon(`<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/><path d="m12.5 15.5-3-3 3-3"/><path d="M9.5 12.5H16"/>`, cls),
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
async function load() {
  // Просим агента закрепить системную альбомную ориентацию (если у Termux есть права).
  try { fetch("/api/ensure-landscape").catch(() => {}); } catch (_) {}
  try { deviceName = (await (await fetch("/api/device")).json()).name || ""; } catch (_) {}
  try { const st = await (await fetch("/api/player-status")).json(); playerMissing = st && st.installed === false; } catch (_) {}
  await reloadLibrary();
  history.replaceState({ screen: "categories" }, ""); // корневая запись истории
  render();
  tryLandscape(false); // PWA/standalone может залочить сразу; браузер — при первом взаимодействии
  // live-обновление: любое изменение (скан, «Исправить» из дашборда, переименование) прилетает сюда.
  // SSE держим ТОЛЬКО на видимой вкладке: фоновые дубли (агент открывает страницу при каждом
  // рестарте) иначе съедают весь пул соединений Chrome (6 на хост) — новые вкладки виснут.
  let es = null, ssePending = null;
  // Дебаунс: при массовом дообогащении события сыплются пачками — перерисовываем раз в 1.5с,
  // и только если данные реально изменились (иначе окантовка фокуса «прыгает» на ровном месте).
  const onChange = () => {
    if (ssePending) return;
    ssePending = setTimeout(async () => {
      ssePending = null;
      if (await reloadLibrary()) rerenderKeepingFocus();
    }, 1500);
  };
  const connectEvents = () => {
    if (es) return;
    try {
      es = new EventSource("/api/events");
      es.onmessage = onChange;
    } catch (_) { es = null; }
  };
  document.addEventListener("visibilitychange", async () => {
    if (document.hidden) { if (es) { es.close(); es = null; } }
    else { connectEvents(); if (await reloadLibrary()) rerenderKeepingFocus(); } // догнать пропущенное
  });
  if (!document.hidden) connectEvents();
}

let itemsJson = "";
// true — если данные изменились с прошлой загрузки.
async function reloadLibrary() {
  try {
    const txt = await (await fetch("/api/library")).text();
    loaded = true;
    if (txt === itemsJson) return false;
    itemsJson = txt;
    items = JSON.parse(txt);
    return true;
  } catch (_) { items = items || []; return false; }
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
        poster: c.poster || e.poster, backdrop: e.backdrop,
        overview: c.overview || e.overview || "", parts: []
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
  else if (state.screen === "person") renderPerson();
  else if (state.screen === "detail") renderDetail();
}

// При live-обновлении перерисовываем текущий экран, сохраняя фокус по id
// и позицию скролла (иначе SSE-шторм при массовом дообогащении дёргает страницу).
function rerenderKeepingFocus() {
  const focusedId = document.activeElement?.dataset?.id;
  const scrolls = [...app.querySelectorAll("div")].filter((n) => n.scrollTop > 0)
    .map((n) => ({ cls: n.className, top: n.scrollTop }));
  if ((state.screen === "detail" || state.screen === "collection") && state.current) {
    const cur = findEntry(state.type, state.current.id);
    if (cur) state.current = cur;      // обновить состав серий/частей
    else state.screen = "grid";        // элемент исчез — назад в сетку
  }
  render();
  if (focusedId) {
    const el = app.querySelector(`[data-id="${CSS.escape(focusedId)}"]`);
    if (el) el.focus({ preventScroll: true });
  }
  for (const s of scrolls) {
    const n = [...app.querySelectorAll("div")].find((d) => d.className === s.cls && d.scrollHeight > d.clientHeight);
    if (n) n.scrollTop = s.top;
  }
}

/* ---------- Ориентация и полный экран: TV-режим живёт в ландшафтном fullscreen ---------- */
let fullscreenAchieved = false;
async function tryLandscape(interactive) {
  try { await screen.orientation.lock("landscape"); } catch (_) {}
  if (!interactive || document.fullscreenElement) return;
  try {
    await document.documentElement.requestFullscreen();
    fullscreenAchieved = true;
    await screen.orientation.lock("landscape");
  } catch (_) {}
}
// Браузер разрешает fullscreen только по жесту: пробуем на КАЖДОМ взаимодействии,
// пока не получится (PWA-ярлык открывается fullscreen сразу через манифест).
// После первого успешного входа больше не форсируем — уважая намеренный выход по Esc.
function armOrientation() {
  if (fullscreenAchieved || document.fullscreenElement) return;
  tryLandscape(true);
}
document.addEventListener("pointerdown", armOrientation, true);
document.addEventListener("touchend", armOrientation, true);

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
  // Палец не на скроллируемом (заголовок/описание) — крутим самый большой
  // скроллируемый контейнер экрана (список серий, сетка…), как ожидает пользователь.
  let best = null, bestArea = 0;
  for (const n of app.querySelectorAll("div")) {
    if (n.scrollHeight <= n.clientHeight + 1) continue;
    const s = getComputedStyle(n);
    if (!/(auto|scroll)/.test(s.overflowY)) continue;
    const area = n.clientWidth * n.clientHeight;
    if (area > bestArea) { bestArea = area; best = n; }
  }
  return best;
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
  // Постеры как у Kodi: всегда 4 в ряд, первая плитка в сетке — «Назад».
  const W = uiW();
  const cols = 4;
  const side = Math.min(460, Math.max(250, W * 0.3));
  const gap = 16, gridPad = 44; // узкие поля: px-3 слева/справа + запас под фокус-кольца
  const w = Math.max(96, Math.min(340, Math.floor((W - side - gridPad - (cols - 1) * gap) / cols)));
  document.documentElement.style.setProperty("--card-w", w + "px");
  document.documentElement.style.setProperty("--cols", cols);
}

// Карточка сетки — постер 2:3 (Kodi-стиль): без подписи, название — в левой панели.
function cardHtml(i) {
  const badge = i.isCollection
    ? `${i.parts.filter(isWatched).length}/${i.parts.length}`
    : (i.episodes && i.episodes.length > 1 ? `${i.episodes.length} серий` : (i.isCollectionPart && i.year ? String(i.year) : ""));
  const p = poster(i.poster) || backdrop(i.backdrop);
  return `
    <div class="tv-card relative w-[var(--card-w)] cursor-pointer overflow-hidden rounded-xl bg-zinc-900 ring-1 ring-white/10 outline-none transition duration-150 focus:z-10 focus:scale-[1.06] focus:shadow-[0_16px_50px_-8px_rgba(139,92,246,.45)] focus:ring-[3px] focus:ring-violet-500" tabindex="0" data-id="${esc(i.id)}">
      ${p ? `<div class="h-0 w-full bg-zinc-800 bg-cover bg-center pb-[150%]" style="background-image:url('${p}')"></div>` : `<div class="relative h-0 w-full bg-gradient-to-br from-zinc-800 to-zinc-900 pb-[150%]"><div class="absolute top-0 right-0 bottom-0 left-0 flex items-center justify-center p-2 text-center text-[13px] leading-snug text-zinc-300">${esc(i.title)}</div></div>`}
      ${badge ? `<div class="absolute top-1.5 right-1.5 rounded-md bg-black/75 px-1.5 py-0.5 text-[11px] font-semibold text-zinc-100">${badge}</div>` : ""}
      ${isWatched(i) ? `<div class="absolute bottom-1.5 right-1.5 grid h-6 w-6 place-items-center rounded-md bg-black/70 text-emerald-400">${ICONS.check("h-4 w-4")}</div>` : ""}
    </div>`;
}

// Плитка «Назад» — первая в сетке, размером с постер (Kodi-стиль).
const backTile = () => `
  <div class="grid-back tv-card relative w-[var(--card-w)] cursor-pointer overflow-hidden rounded-xl bg-zinc-900/80 ring-1 ring-white/10 outline-none transition duration-150 focus:z-10 focus:scale-[1.06] focus:ring-[3px] focus:ring-violet-500" tabindex="0">
    <div class="relative h-0 w-full pb-[150%]">
      <div class="absolute top-0 right-0 bottom-0 left-0 grid place-items-center text-zinc-300">${ICONS.folderBack("h-[42%] w-[42%]")}</div>
    </div>
  </div>`;

// Единая страница-сетка: и список типа, и страница коллекции (Kodi: они одинаковые).
// Заголовок (категория/коллекция) живёт в ЛЕВОЙ колонке, над описанием сфокусированного.
function renderGridPage({ heading, count, list, empty, onOpen, fallbackInfo }) {
  computeCardWidth();
  app.innerHTML = `
    <div class="flex h-full">
      <div class="flex w-[30%] min-w-[250px] max-w-[460px] flex-col overflow-hidden border-r border-white/5 bg-zinc-900/60 px-6 py-6 backdrop-blur-xl">
        <div class="flex flex-none items-center space-x-2.5 border-b border-white/10 pb-3">
          ${heading}
          <span class="rounded-full bg-zinc-800/80 px-2.5 py-0.5 text-[14px] text-zinc-400">${count}</span>
        </div>
        <div class="mt-4 flex min-h-0 flex-1 flex-col" id="grid-info"></div>
      </div>
      <div class="flex flex-1 flex-col px-3 pt-2">
        <!-- pt/pl: чтобы фокус-кольцо и scale карточек не обрезались краем скролл-зоны -->
        <div class="flex-1 overflow-y-auto pb-4 pl-2.5 pt-2.5">
          <div class="grid grid-cols-[repeat(var(--cols),var(--card-w))] justify-start gap-4">
            ${backTile()}${list.map(cardHtml).join("") || empty}
          </div>
        </div>
      </div>
    </div>`;
  app.querySelectorAll(".tv-card").forEach((card) => {
    if (!card.dataset.id) return; // плитка «Назад»
    const item = list.find((i) => i.id === card.dataset.id);
    card.addEventListener("focus", () => updateInfo(item));
    card.addEventListener("click", () => onOpen(item));
  });
  const bt = app.querySelector(".grid-back");
  bt.addEventListener("click", back);
  bt.addEventListener("focus", () => updateInfo(fallbackInfo || null));
  const first = app.querySelector(".tv-card[data-id]") || bt;
  if (first) first.focus();
}

function renderGrid() {
  const list = entriesForType(state.type);
  const cat = CATS.find((c) => c.type === state.type);
  renderGridPage({
    heading: `<span class="text-violet-400">${cat.icon("h-5 w-5")}</span>
      <h2 class="m-0 text-[clamp(15px,calc(var(--uivh)*2.8),20px)] font-bold tracking-tight">${cat.label}</h2>`,
    count: list.length,
    list,
    empty: loaded ? '<p class="tv-empty col-span-3 p-14 text-2xl text-zinc-400">Пусто</p>' : spinner("Загружаю медиатеку…"),
    onOpen: (item) => item.isCollection ? enterCollection(item) : enterDetail(item)
  });
}

/* ---------- Коллекция (франшиза): та же сетка, что и список фильмов ---------- */
function renderCollection() {
  const col = state.current;
  if (!col || !col.isCollection) { state.screen = "grid"; return render(); }
  const parts = col.parts.map((p) => ({ ...p, isCollectionPart: true }));
  renderGridPage({
    heading: `<h2 class="m-0 min-w-0 flex-1 truncate text-[clamp(15px,calc(var(--uivh)*2.8),20px)] font-bold tracking-tight">${esc(col.title)} <span class="font-normal text-zinc-500">(Коллекция)</span></h2>`,
    count: parts.length,
    list: parts,
    empty: "",
    onOpen: (part) => enterDetail(part),
    fallbackInfo: col // на плитке «Назад» — описание самой коллекции
  });
}

// Левая панель сетки — как у Kodi: только название и описание сфокусированного элемента.
function updateInfo(i) {
  const el = document.getElementById("grid-info");
  if (!el) return;
  if (!i) { el.innerHTML = ""; return; }
  el.innerHTML = `
    <div class="flex-none text-[clamp(18px,calc(var(--uivh)*3.8),28px)] font-bold leading-tight tracking-tight">${esc(i.title)}${i.isCollection ? ' <span class="font-normal text-zinc-500">(Коллекция)</span>' : ""}</div>
    <div class="mt-4 flex-1 overflow-y-auto pr-1 text-[clamp(14px,calc(var(--uivh)*2.7),19px)] leading-relaxed text-zinc-300">${esc(i.overview || "Нет описания")}</div>`;
}

/* ---------- Деталь фильма ---------- */
const epLabel = (ep) => ep.episode != null
  ? `Серия ${ep.episode}${ep.season ? ` · сезон ${ep.season}` : ""}`
  : (ep.fileName || "серия");

// Строка таблицы метаданных (Kodi: Режиссёр / Сценарий / Жанр / Студия / Премьера…)
const metaRow = (label, value) => value
  ? `<div class="flex text-[clamp(9px,calc(var(--uivh)*1.8),12px)] leading-snug">
       <span class="w-[72px] flex-none text-zinc-500">${label}</span>
       <span class="min-w-0 flex-1 text-zinc-200">${value}</span>
     </div>`
  : "";
const techChip = (t) => `<span class="mb-1 mr-1 rounded bg-black/60 px-1.5 py-0.5 text-[8px] font-bold tracking-wide text-zinc-300 ring-1 ring-white/15">${t}</span>`;
const CODEC_NAMES = { h264: "H.264", hevc: "HEVC", h265: "HEVC", mpeg4: "MPEG-4", vp9: "VP9", av1: "AV1", ac3: "DOLBY", eac3: "DOLBY+", dts: "DTS", aac: "AAC", mp3: "MP3", opus: "OPUS" };
const codecName = (c) => CODEC_NAMES[String(c || "").toLowerCase()] || String(c || "").toUpperCase();

// Деньги: 25 000 000 → «25 млн $»
const fmtMoney = (v) => {
  if (!v) return "";
  if (v >= 1e9) return (v / 1e9).toFixed(2).replace(/[.,]?0+$/, "") + " млрд $";
  if (v >= 1e6) return (v / 1e6).toFixed(1).replace(/[.,]0$/, "") + " млн $";
  return Number(v).toLocaleString("ru-RU") + " $";
};
// Кликабельные персоны (режиссёр/сценарист) в таблице метаданных → страница персоны
const personLinks = (names) => String(names || "").split(",").map((x) => x.trim()).filter(Boolean)
  .map((n) => `<button class="plink cursor-pointer border-b border-dotted border-zinc-500 text-left outline-none transition focus:border-violet-400 focus:text-violet-300" data-name="${esc(n)}">${esc(n)}</button>`).join(", ");

// Страница фильма: целиком помещается на экране (скроллятся только описание и серии).
function renderDetail() {
  const i = state.current;
  if (!i) { state.screen = "grid"; return render(); }
  const bg = backdrop(i.backdrop) || poster(i.poster);
  const p = poster(i.poster);
  const eps = i.episodes && i.episodes.length ? i.episodes : [i];
  const multi = eps.length > 1;
  const castX = i.castX && i.castX.length ? i.castX : [];
  const BTN = "dfoc flex flex-none cursor-pointer items-center rounded-2xl border border-white/15 px-4 py-[clamp(7px,calc(var(--uivh)*1.8),12px)] text-[clamp(12px,calc(var(--uivh)*2.2),15px)] font-semibold outline-none backdrop-blur transition focus:ring-4";
  const metaTable = `
    ${metaRow("Режиссёр", personLinks(i.director))}
    ${metaRow("Рейтинг", i.rating ? `<span class="font-semibold text-amber-400">★ ${Number(i.rating).toFixed(1)}</span>${i.votes ? ` <span class="text-zinc-500">(${Number(i.votes).toLocaleString("ru-RU")})</span>` : ""}` : "")}
    ${metaRow("Жанр", esc((i.genres || []).join(", ")))}
    ${metaRow("Страна", esc(i.country))}
    ${metaRow("Студия", esc(i.studio))}
    ${metaRow("Премьера", fmtDate(i.premiered))}
    ${metaRow("Бюджет", fmtMoney(i.budget))}
    ${metaRow("Сборы", fmtMoney(i.revenue))}
    ${metaRow("Коллекция", i.collection && i.collection.name ? esc(i.collection.name) : "")}`;
  const episodesHtml = eps.map((ep) => `
    <div class="flex items-center">
      <button class="dfoc ep flex min-w-0 flex-1 cursor-pointer items-center rounded-xl border border-white/10 bg-white/5 px-2.5 py-1.5 text-left text-[14px] outline-none backdrop-blur transition focus:border-violet-400 focus:bg-violet-500/15 focus:ring-2 focus:ring-violet-500/40 ${ep.watched ? "opacity-60" : ""}" data-id="${esc(ep.id)}">
        <img src="/thumb?id=${esc(ep.id)}" loading="lazy" alt=""
          class="mr-2.5 h-10 w-[72px] flex-none rounded-lg bg-zinc-800 object-cover"
          onerror="this.style.display='none'" />
        <span class="mr-2.5 flex-none ${ep.watched ? "text-emerald-400" : "text-violet-300"}">${ep.watched ? ICONS.check("h-4 w-4") : ICONS.play("h-4 w-4")}</span>
        <span class="truncate">${esc(epLabel(ep))}</span>
      </button>
      <button class="dfoc epw ml-1.5 grid h-8 w-8 flex-none cursor-pointer place-items-center rounded-lg border border-white/10 outline-none transition focus:border-emerald-400 focus:ring-2 focus:ring-emerald-500/40 ${ep.watched ? "bg-emerald-500/20 text-emerald-400" : "bg-white/5 text-zinc-500"}"
        data-id="${esc(ep.id)}" data-set="${ep.watched ? 0 : 1}" aria-label="Просмотрено / не просмотрено">${ICONS.check("h-4 w-4")}</button>
    </div>`).join("");
  app.innerHTML = `
    <div class="relative h-full overflow-hidden">
      <div class="absolute top-0 right-0 bottom-0 left-0 bg-black bg-cover bg-center brightness-[.3] saturate-[1.1]" style="${bg ? `background-image:url('${bg}')` : ""}"></div>
      <div class="absolute top-0 right-0 bottom-0 left-0 bg-gradient-to-r from-zinc-950/90 via-zinc-950/70 to-zinc-950/40"></div>
      <div class="relative flex h-full min-h-0 space-x-6 px-7 py-[clamp(10px,calc(var(--uivh)*2.4),22px)]">
        ${p || !multi ? `<div class="flex w-[21%] max-w-[280px] flex-none flex-col">
          ${p ? `<div class="h-0 w-full flex-none rounded-xl bg-zinc-800 bg-cover bg-center pb-[150%] shadow-2xl shadow-black/60 ring-1 ring-white/15" style="background-image:url('${p}')"></div>` : ""}
          ${multi ? "" : `<div id="tech-strip" class="mt-2.5 flex flex-none flex-wrap">
            ${i.premiered ? techChip("📅 " + fmtDate(i.premiered)) : ""}
            ${i.runtime ? techChip("⏱ " + fmtDur(i.runtime * 60)) : ""}
          </div>`}
        </div>` : ""}
        <div class="flex min-w-0 flex-1 flex-col">
          ${castX.length ? `
          <div class="thin-scroll flex flex-none space-x-2.5 overflow-x-auto p-1 pb-1.5">
            ${castX.map((a) => `
              <button class="dfoc actor w-[clamp(56px,calc(var(--uivw)*6),96px)] flex-none cursor-pointer rounded-lg text-center outline-none transition focus:ring-2 focus:ring-violet-400" data-name="${esc(a.n)}" data-photo="${esc(a.p || "")}">
                <div class="h-0 w-full rounded-lg bg-zinc-800 bg-cover bg-center pb-[130%] ring-1 ring-white/10" style="${a.p ? `background-image:url('${prof(a.p)}')` : ""}"></div>
                <div class="mt-0.5 truncate text-[clamp(9px,calc(var(--uivh)*1.8),12px)] font-semibold leading-tight">${esc(a.n)}</div>
                <div class="truncate text-[clamp(8px,calc(var(--uivh)*1.6),11px)] leading-tight text-zinc-400">${esc(a.c)}</div>
              </button>`).join("")}
          </div>` : ""}
          <div class="mt-1 flex-none truncate text-[clamp(18px,calc(var(--uivh)*4),28px)] font-extrabold leading-tight tracking-tight drop-shadow-lg">${esc(i.title)}${i.year ? ` <span class="font-semibold text-zinc-400">(${i.year})</span>` : ""}${multi ? ` <span class="text-[0.6em] font-semibold text-zinc-400">· ${eps.length} серий</span>` : ""}</div>
          ${i.tagline ? `<div class="flex-none truncate text-[clamp(11px,calc(var(--uivh)*2.1),15px)] italic text-zinc-400">«${esc(i.tagline)}»</div>` : ""}
          <div class="mt-2 flex min-h-0 flex-1 space-x-6">
            <div class="min-w-0 flex-1 overflow-y-auto pr-1 text-[clamp(12px,calc(var(--uivh)*2.4),16px)] leading-snug text-zinc-200 drop-shadow">
              ${esc(i.overview || "Нет описания")}
              ${multi ? `<div class="mt-3 space-y-1">${metaTable}</div>` : ""}
            </div>
            ${multi
              ? `<div class="w-[46%] max-w-[560px] flex-none space-y-1.5 overflow-y-auto pr-1">${episodesHtml}</div>`
              : `<div class="w-[38%] max-w-[400px] flex-none space-y-1 overflow-y-auto">${metaTable}</div>`}
          </div>
          <div id="detail-buttons" class="mt-2.5 flex flex-none items-center space-x-2.5">
            <button class="${BTN} bg-white/5 text-zinc-300 focus:ring-violet-500/40" id="detail-back">${ICONS.back("mr-1.5 h-[1.1em] w-[1.1em]")} Назад</button>
            ${multi ? "" : `<button class="dfoc flex flex-none cursor-pointer items-center rounded-2xl bg-gradient-to-r from-violet-600 to-indigo-600 px-[clamp(18px,calc(var(--uivw)*3),32px)] py-[clamp(7px,calc(var(--uivh)*1.8),12px)] text-[clamp(14px,calc(var(--uivh)*2.6),18px)] font-bold text-white shadow-xl shadow-violet-600/40 outline-none transition focus:scale-[1.04] focus:ring-4 focus:ring-violet-400/50" id="detail-play" data-id="${esc(i.id)}">${ICONS.play("mr-2 h-[1.2em] w-[1.2em]")} Смотреть</button>`}
            ${i.trailer ? `<button class="${BTN} bg-white/5 text-zinc-300 focus:ring-violet-500/40" id="detail-trailer">${ICONS.movie("mr-2 h-[1.1em] w-[1.1em]")} Трейлер</button>` : ""}
            ${multi ? "" : `<button class="${BTN} epw ${i.watched ? "bg-emerald-500/15 text-emerald-300 focus:ring-emerald-500/40" : "bg-white/5 text-zinc-300 focus:ring-violet-500/40"}"
              data-id="${esc(i.id)}" data-set="${i.watched ? 0 : 1}">${ICONS.check("mr-2 h-[1.1em] w-[1.1em]")}${i.watched ? "Просмотрено" : "Отметить просмотренным"}</button>`}
          </div>
        </div>
      </div>
    </div>`;
  document.getElementById("detail-back").addEventListener("click", back);
  app.querySelectorAll(".ep").forEach((b) => b.addEventListener("click", () => play(b.dataset.id)));
  // Отметка «Просмотрено/Не просмотрено» — агент запишет, SSE перерисует экран
  app.querySelectorAll(".epw").forEach((b) => b.addEventListener("click", () => {
    fetch(`/api/watched?id=${encodeURIComponent(b.dataset.id)}&set=${b.dataset.set}`).catch(() => {});
  }));
  // Актёры и персоны из таблицы → страница персоны
  app.querySelectorAll(".actor").forEach((b) => b.addEventListener("click", () => enterPerson(b.dataset.name, b.dataset.photo || null)));
  app.querySelectorAll(".plink").forEach((b) => b.addEventListener("click", () => enterPerson(b.dataset.name, null)));
  const playBtn = document.getElementById("detail-play");
  if (playBtn) playBtn.addEventListener("click", () => play(i.id));
  const trailerBtn = document.getElementById("detail-trailer");
  if (trailerBtn) trailerBtn.addEventListener("click", async () => {
    showOverlay("Открываю трейлер…", true);
    try {
      const r = await (await fetch("/api/trailer?id=" + encodeURIComponent(i.id))).json();
      showOverlay(r.ok ? "Трейлер открыт" : "⚠️ " + (r.error || "ошибка"), r.ok);
    } catch (_) { showOverlay("⚠️ Не удалось открыть трейлер"); }
    setTimeout(hideOverlay, 2500);
  });
  if (!multi) loadTech(i);
  // preventScroll: фокус не должен дёргать раскладку
  (playBtn || app.querySelector(".ep") || document.getElementById("detail-back")).focus({ preventScroll: true });
}

/* ---------- Страница персоны: фото, чем известен, фильмография из медиатеки ---------- */
function personEntries(name) {
  const out = [];
  for (const c of CATS) {
    for (const e of entriesForType(c.type)) {
      const inE = (x) => (x.castX || []).some((a) => a.n === name) ||
        String(x.director || "").includes(name);
      if (e.isCollection ? e.parts.some(inE) : inE(e)) out.push(e);
    }
  }
  return out;
}

// Элемент библиотеки по catalogId ("movie_123"/"tv_456") — для пометок «в медиатеке»
// и переходов из полной фильмографии. Части коллекций тоже учитываем.
function libByCatalog() {
  const map = new Map();
  for (const c of CATS) {
    for (const e of entriesForType(c.type)) {
      if (e.isCollection) { for (const p of e.parts) if (p.catalogId || p.tmdbId) map.set(p.catalogId || ((p.type === "series" ? "tv_" : "movie_") + p.tmdbId), p); }
      else if (e.catalogId || e.tmdbId) map.set(e.catalogId || ((e.type === "series" ? "tv_" : "movie_") + e.tmdbId), e);
    }
  }
  return map;
}

// Мини-карточка фильмографии (постер TMDb): есть в медиатеке — яркая с галкой, нет — приглушена.
function pcardHtml(c, inLib) {
  return `
    <div class="tv-card pcard relative w-[calc(var(--card-w)*0.7)] flex-none cursor-pointer overflow-hidden rounded-lg bg-zinc-900 ring-1 ring-white/10 outline-none transition duration-150 focus:z-10 focus:scale-[1.06] focus:ring-2 focus:ring-violet-500 ${inLib ? "" : "opacity-40"}"
      tabindex="0" data-key="${esc(c.kind + "_" + c.tmdbId)}" data-title="${esc(c.title)}" data-year="${c.year || ""}" data-roles="${esc((c.roles || []).slice(0, 2).join(", "))}">
      ${c.poster
        ? `<div class="h-0 w-full bg-zinc-800 bg-cover bg-center pb-[150%]" style="background-image:url('${IMG}/w185${c.poster}')"></div>`
        : `<div class="relative h-0 w-full bg-gradient-to-br from-zinc-800 to-zinc-900 pb-[150%]"><div class="absolute top-0 right-0 bottom-0 left-0 flex items-center justify-center p-1 text-center text-[10px] leading-snug text-zinc-300">${esc(c.title)}</div></div>`}
      ${inLib ? `<div class="absolute top-1 right-1 grid h-5 w-5 place-items-center rounded bg-violet-600 text-white shadow">${ICONS.check("h-3.5 w-3.5")}</div>` : ""}
    </div>`;
}

function renderPerson() {
  computeCardWidth();
  const pr = state.person || {};
  const name = pr.name || "";
  const localList = personEntries(name);
  let photo = pr.photo || null;
  const jobs = new Set();
  for (const it of items) {
    const a = (it.castX || []).find((x) => x.n === name);
    if (a) { jobs.add("Актёр"); if (a.p && !photo) photo = a.p; }
    if (String(it.director || "").includes(name)) jobs.add("Режиссёр");
  }
  app.innerHTML = `
    <div class="flex h-full">
      <div class="flex w-[30%] min-w-[250px] max-w-[460px] flex-col overflow-hidden border-r border-white/5 bg-zinc-900/60 px-6 py-6 backdrop-blur-xl">
        <div class="flex flex-none space-x-4">
          <div class="w-[42%] max-w-[150px] flex-none">
            <div id="person-photo" class="h-0 w-full rounded-xl bg-zinc-800 bg-cover bg-center pb-[130%] shadow-2xl shadow-black/50 ring-1 ring-white/10" style="${photo ? `background-image:url('${shot(photo)}')` : ""}"></div>
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-[clamp(16px,calc(var(--uivh)*3.2),22px)] font-bold leading-tight tracking-tight">${esc(name)}</div>
            <div class="mt-1 text-[clamp(11px,calc(var(--uivh)*2),14px)] text-violet-300">${[...jobs].join(" · ")}</div>
            <div id="person-dates" class="mt-1 text-[clamp(10px,calc(var(--uivh)*1.9),13px)] text-zinc-500"></div>
          </div>
        </div>
        <div id="person-bio" class="thin-scroll mt-3 min-h-0 flex-1 overflow-y-auto pr-1 text-[clamp(11px,calc(var(--uivh)*2.1),14px)] leading-snug text-zinc-400"></div>
        <div id="person-focus" class="h-9 flex-none pt-2 text-[clamp(11px,calc(var(--uivh)*2.1),14px)] leading-tight text-zinc-300"></div>
      </div>
      <div class="flex flex-1 flex-col px-3 pt-2">
        <div id="person-films" class="thin-scroll flex-1 overflow-y-auto pb-4 pl-2.5 pt-2.5">${spinner("Загружаю фильмографию…")}</div>
      </div>
    </div>`;
  // Пока TMDb грузится/недоступен — локальная фильмография; потом заменим полной.
  const lib = libByCatalog();
  renderPersonFilms(localList.map((e) => ({
    tmdbId: e.tmdbId, kind: e.type === "series" ? "tv" : "movie",
    title: e.title, year: e.year, poster: e.poster, roles: []
  })), lib, false);
  fetch("/api/person?name=" + encodeURIComponent(name), { cache: "no-store" })
    .then((r) => r.ok ? r.json() : Promise.reject(new Error("HTTP " + r.status)))
    .then((p) => {
      if (!state.person || state.person.name !== name) return; // уже ушли со страницы
      if (p.photo && !photo) document.getElementById("person-photo").style.backgroundImage = `url('${shot(p.photo)}')`;
      const dates = [p.birthday ? fmtDate(p.birthday) : "", p.deathday ? "— " + fmtDate(p.deathday) : ""].join(" ").trim();
      document.getElementById("person-dates").textContent = [dates, p.place].filter(Boolean).join(" · ");
      document.getElementById("person-bio").textContent = p.biography || "";
      renderPersonFilms(p.credits || [], lib, true);
    })
    .catch(() => {}); // остаёмся на локальной фильмографии
}

// Правая зона страницы персоны: фильмография с разбивкой по годам (свежие сверху).
function renderPersonFilms(credits, lib, full) {
  const el = document.getElementById("person-films");
  if (!el) return;
  const groups = new Map();
  for (const c of credits) {
    const y = c.year || 0;
    if (!groups.has(y)) groups.set(y, []);
    groups.get(y).push(c);
  }
  const years = [...groups.keys()].sort((a, b) => b - a);
  el.innerHTML = `
    <div class="-mt-1 mb-1 flex flex-wrap items-end">
      <div class="mb-2 mr-3">${backTile().replace('w-[var(--card-w)]', 'w-[calc(var(--card-w)*0.7)]')}</div>
      ${!full ? '<div class="mb-3 text-[13px] text-zinc-500">Полная фильмография недоступна — показано из медиатеки</div>' : ""}
    </div>
    ${years.map((y) => `
      <div class="mb-1.5 mt-1 text-[clamp(13px,calc(var(--uivh)*2.4),17px)] font-bold text-zinc-400">${y || "Без даты"}</div>
      <div class="-mr-3 flex flex-wrap">${groups.get(y).map((c) => `<div class="mb-3 mr-3">${pcardHtml(c, lib.has(c.kind + "_" + c.tmdbId))}</div>`).join("")}</div>`).join("")}`;
  const focusInfo = document.getElementById("person-focus");
  el.querySelectorAll(".pcard").forEach((card) => {
    card.addEventListener("focus", () => {
      if (focusInfo) focusInfo.textContent = `${card.dataset.title}${card.dataset.year ? " (" + card.dataset.year + ")" : ""}${card.dataset.roles ? " · " + card.dataset.roles : ""}`;
    });
    card.addEventListener("click", () => {
      const it = lib.get(card.dataset.key);
      if (it) { it.isCollection ? enterCollection(it) : enterDetail(it); }
      else { showOverlay("Нет в медиатеке"); setTimeout(hideOverlay, 1400); }
    });
  });
  el.querySelector(".grid-back").addEventListener("click", back);
  (el.querySelector(".pcard") || el.querySelector(".grid-back")).focus({ preventScroll: true });
}

// Техчипы (Kodi-стиль: кодек, разрешение, аспект, звук) — ffprobe на ноде, лениво.
async function loadTech(i) {
  const el = document.getElementById("tech-strip");
  if (!el) return;
  try {
    let r = await fetch("/api/mediainfo?id=" + encodeURIComponent(i.id));
    if (r.status === 503) { await new Promise((ok) => setTimeout(ok, 4000)); r = await fetch("/api/mediainfo?id=" + encodeURIComponent(i.id)); }
    if (!r.ok) return;
    const m = await r.json();
    if (document.getElementById("tech-strip") !== el) return; // экран сменился
    const chips = [
      i.premiered ? techChip("📅 " + fmtDate(i.premiered)) : "",
      m.duration ? techChip("⏱ " + fmtDur(m.duration)) : (i.runtime ? techChip("⏱ " + fmtDur(i.runtime * 60)) : ""),
      m.vcodec ? techChip(codecName(m.vcodec)) : "",
      m.height ? techChip(m.height >= 2000 ? "4K" : m.height >= 1000 ? "1080 HD" : m.height >= 700 ? "720 HD" : "SD") : "",
      m.width && m.height ? techChip((m.width / m.height).toFixed(2) + ":1") : "",
      m.acodec ? techChip(codecName(m.acodec)) : "",
      m.channels ? techChip(m.channels >= 8 ? "7.1" : m.channels >= 6 ? "5.1" : m.channels + ".0") : ""
    ];
    el.innerHTML = chips.filter(Boolean).join("");
  } catch (_) { /* нет ffprobe — остаются TMDb-чипы */ }
}

/* ---------- Переходы (через History API: браузерная «Назад» тоже работает) ---------- */
function applyState(s) {
  state = { screen: s.screen || "categories", type: s.type || state.type, current: null, person: null };
  if (state.screen === "detail" || state.screen === "collection") {
    state.current = findEntry(state.type, s.id) || null;
  }
  if (state.screen === "person") state.person = { name: s.name || "", photo: s.photo || null };
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
function enterCollection(col) { navigate({ screen: "collection", type: col.type || state.type, id: col.id }); }
function enterDetail(item) { navigate({ screen: "detail", type: item.type || state.type, id: item.id }); }
function enterPerson(name, photo) { if (name) navigate({ screen: "person", name, photo: photo || null }); }
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

  if (state.screen === "grid" || state.screen === "collection" || state.screen === "person") {
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
    // Зоны: актёры (горизонталь) → серии (вертикаль; ←/→ = серия/галка) → кнопки (горизонталь).
    // Вверх/вниз — переход между зонами (в зону — на её первый элемент), влево/вправо — внутри.
    if (e.key === "Enter" || e.key === " " || e.key === "MediaPlayPause") { e.preventDefault(); cur?.click(); return; }
    if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) return;
    e.preventDefault();
    const actors = [...app.querySelectorAll(".actor")];
    const epRows = [...app.querySelectorAll(".ep")].map((ep) => {
      const w = ep.parentElement.querySelector(".epw");
      return w ? [ep, w] : [ep];
    });
    const buttons = [...app.querySelectorAll("#detail-buttons .dfoc")];
    const focusEl = (el) => { if (el) { el.focus({ preventScroll: true }); el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" }); } };
    const aIdx = actors.indexOf(cur);
    const rowIdx = epRows.findIndex((r) => r.includes(cur));
    const bIdx = buttons.indexOf(cur);
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
      const d = e.key === "ArrowRight" ? 1 : -1;
      if (aIdx >= 0) focusEl(actors[Math.max(0, Math.min(actors.length - 1, aIdx + d))]);
      else if (rowIdx >= 0) { const row = epRows[rowIdx]; focusEl(row[Math.max(0, Math.min(row.length - 1, row.indexOf(cur) + d))]); }
      else if (bIdx >= 0) focusEl(buttons[Math.max(0, Math.min(buttons.length - 1, bIdx + d))]);
      else focusEl(buttons[0] || epRows[0]?.[0] || actors[0]);
      return;
    }
    if (e.key === "ArrowDown") {
      if (aIdx >= 0) focusEl(epRows[0]?.[0] || buttons[0]);
      else if (rowIdx >= 0) focusEl(rowIdx < epRows.length - 1 ? epRows[rowIdx + 1][0] : buttons[0]);
      // из кнопок вниз — некуда
      return;
    }
    // ArrowUp
    if (bIdx >= 0) focusEl(epRows.length ? epRows[epRows.length - 1][0] : actors[0]);
    else if (rowIdx >= 0) focusEl(rowIdx > 0 ? epRows[rowIdx - 1][0] : actors[0]);
    // из актёров вверх — некуда
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
  if (["grid", "collection", "person"].includes(state.screen)) computeCardWidth();
});
load();
