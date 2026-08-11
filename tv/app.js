// TV-режим: категории → сетка 4×2 с описанием слева → страница фильма с Play.
// Навигация пультом (Flirc = клавиши): стрелки, OK/Enter, Back (Esc/Backspace).
// Все картинки — через агентский прокси-кэш (/img): работает офлайн после первого показа.
const IMG = "/img";
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
  user: (cls) => icon(`<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>`, cls),
  eye: (cls) => icon(`<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>`, cls),
  eyeOff: (cls) => icon(`<path d="m3 3 18 18"/><path d="M6.6 6.7C3.4 8.6 2 12 2 12s3.5 7 10 7c1.9 0 3.6-.5 5-1.3"/><path d="M10.7 5.1c.4 0 .9-.1 1.3-.1 6.5 0 10 7 10 7s-.6 1.3-1.9 2.8"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>`, cls),
  gear: (cls) => icon(`<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.14.5.53.9 1.02 1.02H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/>`, cls),
  drive: (cls) => icon(`<rect width="20" height="8" x="2" y="4" rx="2"/><rect width="20" height="8" x="2" y="12" rx="2"/><path d="M6 8h.01"/><path d="M6 16h.01"/>`, cls),
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
let epFilter = "all"; // фильтр списка серий (кнопка-глаз): all → unwatched → watched → all
// Активные загрузки этого устройства (для прогресса на «призраках»). Ключ: norm(title)|year.
let downloads = new Map();
const dlKey = (title, year) => String(title || "").toLowerCase().replace(/[^a-zа-я0-9]+/gi, " ").trim() + "|" + (year || "");
async function loadDownloads() {
  try {
    const list = await (await fetch("/api/downloads")).json();
    const m = new Map();
    for (const d of list) if (d.status !== "done" && d.status !== "error") m.set(dlKey(d.title, d.year), d);
    const changed = m.size !== downloads.size || [...m.keys()].some((k) => !downloads.has(k) || downloads.get(k).progress !== m.get(k).progress);
    downloads = m;
    return changed;
  } catch (_) { return false; }
}
// Пока есть активные загрузки — опрашиваем прогресс и перерисовываем плашки.
setInterval(async () => {
  if (!downloads.size) return;
  if (await loadDownloads() && (state.screen === "collection" || state.screen === "grid")) rerenderKeepingFocus();
}, 3000);
// Живём внутри своего WebView-приложения (com.mediacenter.tv)? Оно само даёт fullscreen,
// ландшафт и негаснущий экран — браузерные пляски с orientation-lock не нужны.
const IN_APP = /MediaCenterTV/.test(navigator.userAgent);
let appOffer = null; // "install" | "update" | null — кнопка приложения на экране категорий
// Родительский режим: код задан в дашборде. Сам код сюда НЕ приходит — введённый
// отправляем агенту, он сверяет (иначе любой в локальной сети прочитал бы его в исходнике).
let kioskPinSet = false;
// Режим приставки задаётся в дашборде (config/tv.mode): «kids» — детский, с замками
// (выйти из медиатеки можно только по коду), «normal» — обычный телевизор без ограничений.
// Системная часть (наше приложение как домашний экран, перехват кнопок пульта) от режима
// не зависит — она живёт на уровне Android; режим решает, спрашивать ли код.
let kidsMode = false;
async function refreshKiosk() {
  try {
    const k = await (await fetch("/api/kiosk", { cache: "no-store" })).json();
    const before = kidsMode;
    kioskPinSet = !!k.pinSet;
    kidsMode = k.mode === "kids" && kioskPinSet;   // без кода запирать нельзя: ключа не будет
    return before !== kidsMode;
  } catch (_) { return false; }
}

/* ---------- Статус-точки (правый верхний угол): агент / сервер / live ----------
   агент  — отвечает ли HTTP локального сервера (сам факт ответа /api/health);
   сервер — связь агента с Firebase (heartbeat, из ответа /api/health);
   live   — подключён ли SSE (мгновенные обновления библиотеки). */
function setDot(id, state) {
  const el = document.getElementById(id);
  if (el) el.className = "st-dot st-" + state;
}
async function pollHealth() {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 4000);
    const h = await (await fetch("/api/health", { signal: ctrl.signal })).json();
    clearTimeout(t);
    setDot("st-agent", "ok");
    // heartbeat ходит раз в 30с; молчание дольше 90с — связи фактически нет,
    // даже если последняя попытка формально не упала (SDK может «висеть» в ретраях)
    const stale = h.lastOkAgo == null || h.lastOkAgo > 90;
    setDot("st-cloud", h.firebase && !stale ? "ok" : "bad");
  } catch (_) {
    setDot("st-agent", "bad");
    setDot("st-cloud", "off"); // агент недоступен — про сервер ничего не знаем
  }
}
setInterval(pollHealth, 10000);
pollHealth();

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
  await refreshDeviceName();
  await refreshKiosk();
  try { const st = await (await fetch("/api/player-status")).json(); playerMissing = st && st.installed === false; } catch (_) {}
  // Наше TV-приложение: предлагаем поставить (в браузере) или обновить (везде)
  try {
    const st = await (await fetch("/api/app-status")).json();
    if (st && st.apkAvailable) {
      if (!st.installed && !IN_APP) appOffer = "install";
      else if (st.updateAvailable) appOffer = "update";
    }
  } catch (_) {}
  await reloadLibrary();
  await loadDownloads(); // активные загрузки — для прогресса на «призраках»
  history.replaceState({ screen: "categories" }, ""); // корневая запись истории
  render();
  // Пришли из ленты «Продолжить просмотр» на домашнем экране (?play=<id>) — открываем фильм
  // сразу: карточка там показывает прогресс, и ждать от зрителя ещё пары нажатий незачем.
  const playId = new URLSearchParams(location.search).get("play");
  if (playId && items.some((x) => x.id === playId)) {
    history.replaceState({ screen: "categories" }, "", location.pathname);
    openDetail(items.find((x) => x.id === playId));
    play(playId);
  }
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
      // Имя устройства правят в дашборде — плашка в шапке должна меняться без перезапуска.
      const renamed = await refreshDeviceName();
      const modeChanged = await refreshKiosk();   // режим правят в дашборде
      if ((await reloadLibrary()) || renamed || modeChanged) rerenderKeepingFocus();
    }, 1500);
  };
  const connectEvents = () => {
    if (es) return;
    try {
      es = new EventSource("/api/events");
      es.onmessage = onChange;
      es.onopen = () => { setDot("st-live", "ok"); reloadIfAgentUpdated(); };
      es.onerror = () => setDot("st-live", "bad"); // EventSource сам переподключится (retry)
    } catch (_) { es = null; setDot("st-live", "bad"); }
  };
  document.addEventListener("visibilitychange", async () => {
    if (document.hidden) { if (es) { es.close(); es = null; setDot("st-live", "off"); } }
    else { connectEvents(); if (await reloadLibrary()) rerenderKeepingFocus(); } // догнать пропущенное
  });
  if (!document.hidden) connectEvents();
  waitForLibrary();
}

// Страница вполне может открыться РАНЬШЕ, чем агент поднялся и досканировал медиатеку
// (при старте устройства или после обновления приложения): тогда первый запрос вернул пусто,
// а SSE ещё не подключился — и на экране навсегда оставались бы «0 шт.». Поэтому, пока
// медиатека пуста, тихо переспрашиваем агента; как только данные появились — рисуем и
// прекращаем. Опрос сам сходит на нет: 60 попыток по 3 секунды, дальше остаётся живой SSE.
function waitForLibrary() {
  if (items.length) return;
  let tries = 0;
  const timer = setInterval(async () => {
    if (items.length || ++tries > 60) return clearInterval(timer);
    if (document.hidden) return;
    if (await reloadLibrary()) { clearInterval(timer); rerenderKeepingFocus(); }
  }, 3000);
}

// Агент обновился — перезагружаем страницу. Иначе телевизор неделями крутит код, загруженный
// при первом открытии: агент подтягивает новый app.js, а WebView об этом не знает, и правки
// «не появляются». Sha берём при старте и сверяем на каждом (пере)подключении SSE — рестарт
// агента рвёт поток, так что момент ровно тот.
let agentVersion = null;
async function reloadIfAgentUpdated() {
  try {
    const v = (await (await fetch("/api/device", { cache: "no-store" })).json()).version || null;
    if (!v) return;
    if (agentVersion === null) { agentVersion = v; return; }
    if (v !== agentVersion) location.reload();
  } catch (_) { /* агент перезапускается — проверим на следующем подключении */ }
}

// Имя ноды агент отдаёт из Firestore (дашборд — источник правды), поэтому перечитываем его
// по событиям, а не только при загрузке. true — если имя сменилось.
async function refreshDeviceName() {
  try {
    const name = (await (await fetch("/api/device")).json()).name || "";
    if (name === deviceName) return false;
    deviceName = name;
    return true;
  } catch (_) { return false; }
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
        overview: c.overview || e.overview || "", parts: [],
        tmdbParts: c.parts || [] // ВСЕ части франшизы по TMDb (для «недостающих» на странице коллекции)
      };
      map.set(c.id, col);
      out.push(col);
    }
    if (!col.tmdbParts.length && c.parts && c.parts.length) col.tmdbParts = c.parts;
    col.parts.push(e);
  }
  // Число недостающих частей франшизы (есть в TMDb, нет в медиатеке).
  const ghostCount = (col) => {
    const owned = new Set(col.parts.map((p) => p.tmdbId));
    return (col.tmdbParts || []).filter((g) => g.tmdbId && !owned.has(g.tmdbId)).length;
  };
  return out
    // Схлопываем в обычный фильм ТОЛЬКО если скачаны все части (нет «призраков»): иначе
    // даже одна скачанная часть остаётся коллекцией — видны продолжения, их можно докачать.
    .map((e) => (e.isCollection && e.parts.length === 1 && ghostCount(e) === 0 ? e.parts[0] : e))
    .map((e) => { if (e.isCollection) e.parts.sort((a, b) => (a.year || 0) - (b.year || 0)); return e; })
    .sort((a, b) => (a.title || "").localeCompare(b.title || ""));
}

// Дата добавления сущности: у сериала — самая свежая серия, у коллекции — самая свежая часть.
const newestOf = (e) => e.isCollection
  ? Math.max(0, ...e.parts.map(newestOf))
  : Math.max(0, ...(e.episodes && e.episodes.length ? e.episodes : [e]).map((x) => x.addedAt || 0));
// Фильмы и мультфильмы — новые сверху (по дате добавления файла); сериалы — по алфавиту.
const entriesForType = (t) => {
  const arr = groupCollections(groupTitles(byType(t)));
  if (t === "movie" || t === "cartoon") {
    arr.sort((a, b) => newestOf(b) - newestOf(a) || (a.title || "").localeCompare(b.title || ""));
  }
  return arr;
};
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
  else if (state.screen === "detail" || state.screen === "ghost") renderDetail();
}

// При live-обновлении перерисовываем текущий экран, сохраняя фокус по id
// и позицию скролла (иначе SSE-шторм при массовом дообогащении дёргает страницу).
function rerenderKeepingFocus() {
  const ae = document.activeElement;
  const focusedId = ae?.dataset?.id;
  // Кнопка-галка «Просмотрено» (.epw) делит data-id с кнопкой «Смотреть»/строкой серии —
  // без уточнения селектора фокус после перерисовки перескакивал бы на «Смотреть».
  const focusedSel = ae?.classList?.contains("epw") ? ".epw" : "";
  const scrolls = [...app.querySelectorAll("div")].filter((n) => n.scrollTop > 0)
    .map((n) => ({ cls: n.className, top: n.scrollTop }));
  if ((state.screen === "detail" || state.screen === "collection") && state.current) {
    const cur = findEntry(state.type, state.current.id);
    if (cur) state.current = cur;      // обновить состав серий/частей
    else state.screen = "grid";        // элемент исчез — назад в сетку
  }
  render();
  if (focusedId) {
    const el = app.querySelector(`${focusedSel}[data-id="${CSS.escape(focusedId)}"]`)
      || app.querySelector(`[data-id="${CSS.escape(focusedId)}"]`);
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
  if (IN_APP) return; // приложение и так fullscreen+landscape на уровне активити
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
        <button id="cat-settings" tabindex="0" title="Настройки"
          class="ml-auto mr-2 grid h-12 w-12 cursor-pointer place-items-center rounded-full border border-zinc-800 bg-zinc-900/80 text-zinc-400 outline-none transition focus:scale-110 focus:border-violet-500/60 focus:text-zinc-100 focus:ring-4 focus:ring-violet-500/25">
          ${ICONS.gear("h-6 w-6")}</button>
      </div>
      ${playerMissing ? `
        <button id="cat-vlc" tabindex="0" class="mx-12 mt-4 flex cursor-pointer items-center self-start rounded-2xl border border-red-500/30 bg-red-500/10 px-6 py-3.5 text-lg font-semibold text-red-300 outline-none transition focus:scale-[1.02] focus:border-red-400 focus:ring-4 focus:ring-red-500/30">
          ${ICONS.download("mr-3 h-6 w-6")} Установить плеер VLC — нужен для просмотра
        </button>` : ""}
      ${appOffer ? `
        <button id="cat-app" tabindex="0" class="mx-12 mt-4 flex cursor-pointer items-center self-start rounded-2xl border border-violet-500/30 bg-violet-500/10 px-6 py-3.5 text-lg font-semibold text-violet-300 outline-none transition focus:scale-[1.02] focus:border-violet-400 focus:ring-4 focus:ring-violet-500/30">
          ${ICONS.download("mr-3 h-6 w-6")} ${appOffer === "update" ? "Обновить приложение MediaCenter TV" : "Установить приложение MediaCenter TV — полный экран без браузера"}
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
  const appBtn = document.getElementById("cat-app");
  if (appBtn) appBtn.addEventListener("click", installApp);
  document.getElementById("cat-settings")?.addEventListener("click", openSettings);
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

// Установка/обновление нашего WebView-приложения: агент кладёт APK из своего репо
// в Загрузки и открывает системный установщик (подтвердить на телефоне).
async function installApp() {
  showOverlay("Готовлю APK…");
  try {
    const r = await (await fetch("/api/install-app")).json();
    if (r.launched) showOverlay("Подтверди установку на телефоне ✓");
    else showOverlay("⚠️ " + (r.error || "не удалось"));
  } catch (_) { showOverlay("⚠️ Не удалось запустить установку"); }
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
  const gap = 28, gridPad = 56; // просторнее между постерами, сами постеры мельче
  const w = Math.max(90, Math.min(280, Math.floor((W - side - gridPad - (cols - 1) * gap) / cols)));
  document.documentElement.style.setProperty("--card-w", w + "px");
  document.documentElement.style.setProperty("--cols", cols);
}

// Карточка сетки — постер 2:3 (Kodi-стиль): без подписи, название — в левой панели.
function cardHtml(i) {
  // у коллекции — «скачано/всего частей франшизы» (включая недостающие «призраки»)
  const badge = i.isCollection
    ? `${i.parts.length}/${Math.max(i.parts.length, (i.tmdbParts || []).length || i.parts.length)}`
    : (i.episodes && i.episodes.length > 1 ? `${i.episodes.length} серий` : (i.isCollectionPart && i.year ? String(i.year) : ""));
  const p = poster(i.poster) || backdrop(i.backdrop);
  // идёт ли загрузка этого фильма (по названию+году) — и на «призраке», и на уже
  // появившейся карточке: WebTorrent создаёт файл сразу, карточка становится «реальной»,
  // но докачка ещё идёт, и прогресс нужно продолжать показывать. У коллекций — не сюда.
  const dl = i.isCollection ? null : downloads.get(dlKey(i.title, i.year));
  // рейтинг на карточке: у коллекции — лучшей части; иначе IMDb, иначе TMDb
  const best = i.isCollection
    ? i.parts.slice().sort((a, b) => (b.imdbRating || b.rating || 0) - (a.imdbRating || a.rating || 0))[0] || {}
    : i;
  const rt = i.isCollection
    ? Number(best.imdbRating || best.rating || 0)
    : Number(i.imdbRating || i.rating || 0);
  // Голоса — от того же источника, что и балл (как на плитках фильмографии)
  const votes = best.imdbRating ? Number(best.imdbVotes || 0) : Number(best.votes || 0);
  return `
    <div class="tv-card relative w-[var(--card-w)] cursor-pointer overflow-hidden rounded-xl bg-zinc-900 ring-1 ring-white/10 outline-none transition duration-150 focus:z-10 focus:scale-[1.06] focus:shadow-[0_16px_50px_-8px_rgba(139,92,246,.45)] focus:ring-[3px] focus:ring-violet-500${i.isGhost ? " opacity-40" : ""}" tabindex="0" data-id="${esc(i.id)}">
      ${p ? `<div class="h-0 w-full bg-zinc-800 bg-cover bg-center pb-[150%]" style="background-image:url('${p}')"></div>` : `<div class="relative h-0 w-full bg-gradient-to-br from-zinc-800 to-zinc-900 pb-[150%]"><div class="absolute top-0 right-0 bottom-0 left-0 flex items-center justify-center p-2 text-center text-[13px] leading-snug text-zinc-300">${esc(i.title)}</div></div>`}
      ${rt ? `<div class="absolute top-1.5 left-1.5 rounded-md bg-black/75 px-1.5 py-0.5 text-center text-[11px] font-semibold leading-tight text-yellow-300">★ ${rt.toFixed(1)}${
        votes ? `<div class="text-[9px] font-normal text-zinc-400">${fmtVotes(votes)}</div>` : ""}</div>` : ""}
      ${badge ? `<div class="absolute top-1.5 right-1.5 rounded-md bg-black/75 px-1.5 py-0.5 text-[11px] font-semibold text-zinc-100">${badge}</div>` : ""}
      ${isWatched(i) ? `<div class="absolute bottom-1.5 right-1.5 grid h-6 w-6 place-items-center rounded-md bg-black/70 text-emerald-400">${ICONS.check("h-4 w-4")}</div>` : ""}
      ${dl ? `<div class="absolute right-0 bottom-0 left-0 bg-black/80 px-2 py-1.5">
        <div class="mb-1 text-[10px] font-semibold text-violet-200">${dl.status === "downloading" ? "Скачивается " + Math.round((dl.progress || 0) * 100) + "%" : dl.status === "moving" ? "Переносим " + Math.round((dl.progress || 0) * 100) + "%" : dl.error ? "Ошибка" : "Ожидает…"}</div>
        <div class="h-1 overflow-hidden rounded bg-white/15"><div class="h-full bg-violet-500 transition-all" style="width:${Math.round((dl.progress || 0) * 100)}%"></div></div>
      </div>` : ""}
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
          <div class="grid grid-cols-[repeat(var(--cols),var(--card-w))] justify-start gap-7">
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
  const owned = col.parts.map((p) => ({ ...p, isCollectionPart: true }));
  // Недостающие части франшизы (по TMDb) — полупрозрачные «призраки»: видно, что докачать.
  const ownedIds = new Set(owned.map((p) => p.tmdbId));
  const ghosts = (col.tmdbParts || [])
    .filter((g) => g.tmdbId && !ownedIds.has(g.tmdbId))
    .map((g) => ({
      isGhost: true, isCollectionPart: true, id: "ghost_" + g.tmdbId, type: col.type,
      tmdbId: g.tmdbId,   // нужен, чтобы открыть карточку фильма (страница «Скачать»)
      title: g.title || "", year: g.year || null, poster: g.poster || null,
      overview: g.overview || "", rating: g.rating || 0
    }));
  const parts = [...owned, ...ghosts].sort((a, b) => (a.year || 9999) - (b.year || 9999));
  renderGridPage({
    heading: `<h2 class="m-0 min-w-0 flex-1 truncate text-[clamp(15px,calc(var(--uivh)*2.8),20px)] font-bold tracking-tight">${esc(col.title)} <span class="font-normal text-zinc-500">(Коллекция)</span></h2>`,
    count: parts.length,
    list: parts,
    empty: "",
    onOpen: (part) => {
      if (part.isGhost) enterGhost(part);
      else enterDetail(part);
    },
    fallbackInfo: col // на плитке «Назад» — описание самой коллекции
  });
}

// Длительность для мета-строки: 148 мин → «2 ч 28 мин»
// Число голосов коротко: 4447 → 4,4K; 1 250 000 → 1,3M. На ТВ его читают мельком,
// полные разряды только зашумляют строку.
const fmtVotes = (n) => {
  const v = Number(n) || 0;
  if (v >= 1000000) return (v / 1000000).toFixed(1).replace(".", ",") + "M";
  if (v >= 1000) return (v / 1000).toFixed(v >= 10000 ? 0 : 1).replace(".", ",") + "K";
  return String(v);
};

// Рейтинг с числом голосов мелкой строкой под ним: сам балл читается издалека, а «сколько
// людей его поставили» — уточнение, которому крупный шрифт не нужен.
const ratingCell = (i) => {
  const imdb = Number(i.imdbRating || 0);
  const value = imdb || Number(i.rating || 0);
  if (!value) return "";
  const votes = imdb ? Number(i.imdbVotes || 0) : Number(i.votes || 0);
  return `<div><span class="font-semibold text-yellow-300">★ ${value.toFixed(1)}</span>` +
    ` <span class="text-zinc-600">${imdb ? "IMDb" : "TMDb"}</span></div>` +
    (votes ? `<div class="text-[clamp(9px,calc(var(--uivh)*1.5),11px)] leading-tight text-zinc-500">${fmtVotes(votes)} голосов</div>` : "");
};

// Отметка «Просмотрено» идёт в Firestore и возвращается по SSE — на ТВ это ощутимая пауза.
// Показываем, что нажатие принято: кнопка гаснет и крутит спиннер до ответа агента.
async function markWatched(btn) {
  if (btn.dataset.busy === "1") return;
  btn.dataset.busy = "1";
  const label = btn.innerHTML;
  const compact = !btn.textContent.trim();   // у серий кнопка — иконка 8x8, подпись не влезет
  btn.classList.add("pointer-events-none", "opacity-60");
  btn.innerHTML = `<svg class="${compact ? "h-4 w-4" : "mr-2 h-5 w-5"} animate-spin" viewBox="0 0 24 24" fill="none">
      <circle class="opacity-20" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
      <path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
    </svg>${compact ? "" : "Сохраняю…"}`;
  try {
    await fetch(`/api/watched?id=${encodeURIComponent(btn.dataset.id)}&set=${btn.dataset.set}`);
    await reloadLibrary();
    // Именно rerenderKeepingFocus, а не render(): он подтягивает свежий state.current, иначе
    // страница рисовалась по старым данным и кнопка оставалась в прежнем состоянии.
    rerenderKeepingFocus();
  } catch (_) {
    btn.innerHTML = label;         // не вышло — возвращаем кнопку как была
    btn.classList.remove("pointer-events-none", "opacity-60");
    btn.dataset.busy = "";
  }
}

const fmtRuntime = (min) => {
  if (!min) return "";
  const h = Math.floor(min / 60), m = Math.round(min % 60);
  return h ? `${h} ч${m ? ` ${m} мин` : ""}` : `${m} мин`;
};

// Левая панель сетки — как у Kodi: название, мета-строка (рейтинг/год/длительность) и описание.
function updateInfo(i) {
  const el = document.getElementById("grid-info");
  if (!el) return;
  if (!i) { el.innerHTML = ""; return; }
  const rating = Number(i.imdbRating || i.rating || 0);
  const meta = [
    rating ? `<span class="font-semibold text-yellow-300">★ ${rating.toFixed(1)}</span>` : "",
    i.year ? String(i.year) : "",
    !i.isCollection ? fmtRuntime(i.runtime) : "",
    i.episodes && i.episodes.length > 1 ? `${i.episodes.length} серий` : ""
  ].filter(Boolean).join('<span class="mx-1.5 text-zinc-600">·</span>');
  el.innerHTML = `
    <div class="flex-none text-[clamp(18px,calc(var(--uivh)*3.8),28px)] font-bold leading-tight tracking-tight">${esc(i.title)}${i.isCollection ? ' <span class="font-normal text-zinc-500">(Коллекция)</span>' : ""}</div>
    ${meta ? `<div class="mt-1.5 flex-none text-[clamp(11px,calc(var(--uivh)*2),14px)] text-zinc-400">${meta}</div>` : ""}
    <div class="mt-3 flex-1 overflow-y-auto pr-1 text-[clamp(12px,calc(var(--uivh)*2.2),15px)] leading-snug text-zinc-300">${esc(i.overview || "Нет описания")}</div>`;
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

// Карточка персоны в горизонтальной ленте детальной (актёр или режиссёр).
function actorCard(a) {
  return `
    <button class="dfoc actor w-[clamp(56px,calc(var(--uivw)*6),96px)] flex-none cursor-pointer rounded-lg text-center outline-none transition focus:ring-2 focus:ring-violet-400" data-name="${esc(a.n)}" data-photo="${esc(a.p || "")}">
      ${a.p
        ? `<div class="h-0 w-full rounded-lg bg-zinc-800 bg-cover bg-center pb-[130%] ring-1 ring-white/10" style="background-image:url('${prof(a.p)}')"></div>`
        : `<div class="relative h-0 w-full rounded-lg bg-gradient-to-br from-zinc-800 to-zinc-900 pb-[130%] ring-1 ring-white/10"><div class="absolute top-0 right-0 bottom-0 left-0 grid place-items-center text-zinc-600">${ICONS.user("h-1/3 w-1/3")}</div></div>`}
      <div class="mt-0.5 truncate text-[clamp(9px,calc(var(--uivh)*1.8),12px)] font-semibold leading-tight">${esc(a.n)}</div>
      <div class="truncate text-[clamp(8px,calc(var(--uivh)*1.6),11px)] leading-tight text-zinc-400">${esc(a.c)}</div>
    </button>`;
}

// Страница фильма: целиком помещается на экране (скроллятся только описание и серии).
function renderDetail() {
  const i = state.current;
  if (!i) { state.screen = "grid"; return render(); }
  // Фильма у нас ещё нет: данные едут с сервера — показываем постер с плитки и крутилку.
  if (i.isGhost && (i.loading || i.failed)) return renderGhostWaiting(i);
  const bg = backdrop(i.backdrop) || poster(i.poster);
  const p = poster(i.poster);
  const eps = i.episodes && i.episodes.length ? i.episodes : [i];
  const multi = eps.length > 1;
  const castX = i.castX && i.castX.length ? i.castX : [];
  // Режиссёры — первыми в ленте персон (с вертикальным разделителем перед актёрами);
  // из таблицы метаданных строка «Режиссёр» убрана. Фото — из castX, если он там есть.
  const dirCards = String(i.director || "").split(",").map((s) => s.trim()).filter(Boolean)
    .map((n) => ({ n, c: "режиссёр", p: (castX.find((a) => a.n === n) || {}).p || null }));
  const BTN = "dfoc flex flex-none cursor-pointer items-center rounded-2xl border border-white/15 px-4 py-[clamp(7px,calc(var(--uivh)*1.8),12px)] text-[clamp(12px,calc(var(--uivh)*2.2),15px)] font-semibold outline-none backdrop-blur transition focus:ring-4";
  const metaTable = `
    ${metaRow("Рейтинг", ratingCell(i))}
    ${metaRow("Жанр", esc((i.genres || []).join(", ")))}
    ${metaRow("Страна", esc(i.country))}
    ${metaRow("Студия", esc(i.studio))}
    ${metaRow("Премьера", fmtDate(i.premiered))}
    ${metaRow("Бюджет", fmtMoney(i.budget))}
    ${metaRow("Сборы", fmtMoney(i.revenue))}
    ${metaRow("Коллекция", i.collection && i.collection.name ? esc(i.collection.name) : "")}`;
  // Фильтр серий: все / только непросмотренные / только просмотренные (кнопка-глаз)
  const shownEps = multi && epFilter !== "all"
    ? eps.filter((ep) => (epFilter === "watched" ? ep.watched : !ep.watched))
    : eps;
  const episodesHtml = shownEps.map((ep) => `
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
          ${castX.length || dirCards.length ? `
          <div class="thin-scroll flex flex-none space-x-2.5 overflow-x-auto p-1 pb-1.5">
            ${dirCards.map(actorCard).join("")}
            ${dirCards.length && castX.length ? '<div class="mx-0.5 w-px flex-none self-stretch bg-white/20"></div>' : ""}
            ${castX.filter((a) => !dirCards.some((d) => d.n === a.n)).map(actorCard).join("")}
          </div>` : ""}
          <div class="mt-1 flex-none truncate text-[clamp(18px,calc(var(--uivh)*4),28px)] font-extrabold leading-tight tracking-tight drop-shadow-lg">${esc(i.title)}${i.year ? ` <span class="font-semibold text-zinc-400">(${i.year})</span>` : ""}${multi ? ` <span class="text-[0.6em] font-semibold text-zinc-400">· ${eps.length} серий</span>` : ""}</div>
          ${i.tagline ? `<div class="flex-none truncate text-[clamp(11px,calc(var(--uivh)*2.1),15px)] italic text-zinc-400">«${esc(i.tagline)}»</div>` : ""}
          <div class="mt-2 flex min-h-0 flex-1 space-x-6">
            <div id="detail-desc" tabindex="0" class="thin-scroll min-w-0 flex-1 overflow-y-auto rounded-md pr-1 text-[clamp(12px,calc(var(--uivh)*2.4),16px)] leading-snug text-zinc-200 outline-none drop-shadow focus:ring-2 focus:ring-violet-500/40">
              ${esc(i.overview || "Нет описания")}
              ${multi ? `<div class="mt-3 space-y-1">${metaTable}</div>` : ""}
            </div>
            ${multi
              ? `<div class="flex min-h-0 w-[46%] max-w-[560px] flex-none flex-col">
                  <!-- Кнопки над сериями: зона прокрутки серий выше, дотягиваться ближе -->
                  <div id="detail-buttons" data-top="1" class="flex flex-none items-center space-x-2 pb-2">
                    <button class="${BTN} bg-white/5 text-zinc-300 focus:ring-violet-500/40" id="detail-back">${ICONS.back("mr-1.5 h-[1.1em] w-[1.1em]")} Назад</button>
                    ${i.trailer ? `<button class="${BTN} bg-white/5 text-zinc-300 focus:ring-violet-500/40" id="detail-trailer">${ICONS.movie("mr-2 h-[1.1em] w-[1.1em]")} Трейлер</button>` : ""}
                    <button class="${BTN} ${epFilter === "watched" ? "bg-emerald-500/15 text-emerald-300 focus:ring-emerald-500/40" : epFilter === "unwatched" ? "bg-violet-500/15 text-violet-300 focus:ring-violet-500/40" : "bg-white/5 text-zinc-300 focus:ring-violet-500/40"}" id="ep-eye" aria-label="Фильтр серий">
                      ${(epFilter === "unwatched" ? ICONS.eyeOff : ICONS.eye)("mr-2 h-[1.1em] w-[1.1em]")}${epFilter === "all" ? "Все" : epFilter === "watched" ? "Просмотренные" : "Непросмотренные"}
                    </button>
                  </div>
                  <div class="min-h-0 flex-1 space-y-1.5 overflow-y-auto pr-1">
                    ${episodesHtml || `<div class="p-3 text-[13px] text-zinc-500">${epFilter === "watched" ? "Нет просмотренных серий" : "Все серии просмотрены"}</div>`}
                  </div>
                </div>`
              : `<div class="w-[38%] max-w-[400px] flex-none space-y-1 overflow-y-auto">${metaTable}</div>`}
          </div>
          ${multi ? "" : `<div id="detail-buttons" class="mt-2.5 flex flex-none items-center space-x-2.5">
            <button class="${BTN} bg-white/5 text-zinc-300 focus:ring-violet-500/40" id="detail-back">${ICONS.back("mr-1.5 h-[1.1em] w-[1.1em]")} Назад</button>
            <button class="dfoc flex flex-none cursor-pointer items-center rounded-2xl bg-gradient-to-r from-violet-600 to-indigo-600 px-[clamp(18px,calc(var(--uivw)*3),32px)] py-[clamp(7px,calc(var(--uivh)*1.8),12px)] text-[clamp(14px,calc(var(--uivh)*2.6),18px)] font-bold text-white shadow-xl shadow-violet-600/40 outline-none transition focus:scale-[1.04] focus:ring-4 focus:ring-violet-400/50" id="detail-play" data-id="${esc(i.id)}">${(i.isGhost ? ICONS.download : ICONS.play)("mr-2 h-[1.2em] w-[1.2em]")} ${i.isGhost ? "Скачать" : "Смотреть"}</button>
            ${i.trailer ? `<button class="${BTN} bg-white/5 text-zinc-300 focus:ring-violet-500/40" id="detail-trailer">${ICONS.movie("mr-2 h-[1.1em] w-[1.1em]")} Трейлер</button>` : ""}
            ${i.isGhost ? "" : `<button class="${BTN} epw ${i.watched ? "bg-emerald-500/15 text-emerald-300 focus:ring-emerald-500/40" : "bg-white/5 text-zinc-300 focus:ring-violet-500/40"}"
              data-id="${esc(i.id)}" data-set="${i.watched ? 0 : 1}">${(i.watched ? ICONS.check : ICONS.eyeOff)("mr-2 h-[1.1em] w-[1.1em]")}${i.watched ? "Просмотрено" : "Не просмотрено"}</button>`}
          </div>`}
        </div>
      </div>
    </div>`;
  document.getElementById("detail-back").addEventListener("click", back);
  // Глаз-фильтр серий: три позиции по кругу; фокус остаётся на кнопке после перерисовки
  const eye = document.getElementById("ep-eye");
  if (eye) eye.addEventListener("click", () => {
    epFilter = epFilter === "all" ? "unwatched" : epFilter === "unwatched" ? "watched" : "all";
    render();
    document.getElementById("ep-eye")?.focus({ preventScroll: true });
  });
  app.querySelectorAll(".ep").forEach((b) => b.addEventListener("click", () => play(b.dataset.id)));
  // Отметка «Просмотрено/Не просмотрено» — агент запишет, SSE перерисует экран
  app.querySelectorAll(".epw").forEach((b) => b.addEventListener("click", () => markWatched(b)));
  // Актёры и персоны из таблицы → страница персоны
  app.querySelectorAll(".actor").forEach((b) => b.addEventListener("click", () => enterPerson(b.dataset.name, b.dataset.photo || null)));
  app.querySelectorAll(".plink").forEach((b) => b.addEventListener("click", () => enterPerson(b.dataset.name, null)));
  const playBtn = document.getElementById("detail-play");
  if (playBtn) playBtn.addEventListener("click", () => (i.isGhost ? askDownload(i) : play(i.id)));
  const trailerBtn = document.getElementById("detail-trailer");
  if (trailerBtn) trailerBtn.addEventListener("click", async () => {
    showOverlay("Открываю трейлер…", true);
    try {
      // Плашку убираем сразу: ролик открывается тут же, а иначе она висела под ним
      // и всплывала обратно, когда трейлер закрывали.
      if (IN_APP && i.trailer) { hideOverlay(); return openTrailerInline(i.trailer, i.title); }
      const r = await (await fetch("/api/trailer?id=" + encodeURIComponent(i.id))).json();
      showOverlay(r.ok ? "Трейлер открыт" : "⚠️ " + (r.error || "ошибка"), r.ok);
    } catch (_) { showOverlay("⚠️ Не удалось открыть трейлер"); }
    setTimeout(hideOverlay, 2500);
  });
  if (!multi && !i.isGhost) loadTech(i);   // ffprobe только у своих файлов
  // preventScroll: фокус не должен дёргать раскладку
  (playBtn || app.querySelector(".ep") || document.getElementById("detail-back")).focus({ preventScroll: true });
}

// Кэш карточек нескачанных фильмов на время сессии (у агента есть ещё и дисковый).
const ghostCache = new Map();

function ghostKey(s) { return (s.kind || "movie") + "_" + s.tmdbId; }

// Пока данные едут, показываем то немногое, что уже знаем с плитки, и крутилку.
function ghostItem(s) {
  const seed = {
    isGhost: true, id: "ghost_" + s.tmdbId, tmdbId: s.tmdbId, kind: s.kind || "movie",
    type: s.type || state.type, title: s.title || "", year: s.year || null, poster: s.poster || null
  };
  const got = ghostCache.get(ghostKey(s));
  if (!got) return { ...seed, loading: true };
  // Данные с сервера полнее того, что было на плитке, — они и главные; своё оставляем только
  // там, где сервер молчит, плюс служебные поля (тип нужен для «Скачать»: он решает папку).
  return { ...seed, ...got, isGhost: true, id: seed.id,
    type: got.animation && seed.type !== "series" ? "cartoon" : seed.type };
}

async function loadGhost(s) {
  const key = ghostKey(s);
  try {
    const r = await fetch(`/api/movie?tmdbId=${encodeURIComponent(s.tmdbId)}&kind=${encodeURIComponent(s.kind || "movie")}`);
    const d = await r.json();
    if (d && !d.error) ghostCache.set(key, d);
    else if (d && d.error) return ghostFailed(s, d.error);
  } catch (_) { return ghostFailed(s, "нет связи с сервером"); }
  // Экран мог смениться, пока ходили на сервер
  if (state.screen !== "ghost" || !state.current || state.current.tmdbId !== s.tmdbId) return;
  state.current = ghostItem(s);
  render();
}

function ghostFailed(s, msg) {
  if (state.screen !== "ghost" || !state.current || state.current.tmdbId !== s.tmdbId) return;
  state.current = { ...state.current, loading: false, failed: msg };
  render();
}

// Пока карточка едет с сервера (и если не доехала): постер с плитки, название и крутилка.
function renderGhostWaiting(i) {
  const p = i.poster ? `${IMG}/w342${i.poster}` : null;
  app.innerHTML = `
    <div class="relative h-full overflow-hidden">
      <div class="absolute top-0 right-0 bottom-0 left-0 bg-black bg-cover bg-center brightness-[.25]" style="${p ? `background-image:url('${p}')` : ""}"></div>
      <div class="relative flex h-full items-center justify-center px-10">
        <div class="flex items-center space-x-8">
          ${p ? `<div class="h-0 w-[180px] flex-none rounded-xl bg-zinc-800 bg-cover bg-center pb-[270px] shadow-2xl shadow-black/60 ring-1 ring-white/15" style="background-image:url('${p}')"></div>` : ""}
          <div class="min-w-0">
            <div class="truncate text-[clamp(20px,calc(var(--uivh)*4),30px)] font-extrabold">${esc(i.title)}${i.year ? ` <span class="font-semibold text-zinc-400">(${i.year})</span>` : ""}</div>
            <div class="mt-4 flex items-center text-zinc-300">
              ${i.failed
                ? `<span class="text-amber-300">⚠️ ${esc(i.failed)}</span>`
                : `<span class="mr-3 inline-block h-6 w-6 animate-spin rounded-full border-2 border-violet-400 border-t-transparent"></span> Загружаю описание…`}
            </div>
            <div class="mt-6 flex space-x-3">
              <button id="detail-back" class="dfoc flex cursor-pointer items-center rounded-2xl border border-white/15 bg-white/5 px-5 py-3 font-semibold text-zinc-200 outline-none transition focus:scale-[1.03] focus:ring-4 focus:ring-violet-500/40">${ICONS.back("mr-2 h-5 w-5")} Назад</button>
              <button id="ghost-dl" class="dfoc flex cursor-pointer items-center rounded-2xl bg-gradient-to-r from-violet-600 to-indigo-600 px-6 py-3 font-bold text-white shadow-xl shadow-violet-600/40 outline-none transition focus:scale-[1.04] focus:ring-4 focus:ring-violet-400/50">${ICONS.download("mr-2 h-5 w-5")} Скачать</button>
            </div>
          </div>
        </div>
      </div>
    </div>`;
  document.getElementById("detail-back").addEventListener("click", back);
  document.getElementById("ghost-dl").addEventListener("click", () => askDownload(i));
  document.getElementById("ghost-dl").focus({ preventScroll: true });
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
// Бейджи: год (низ-лево), рейтинг (верх-лево; IMDb у элементов медиатеки, иначе TMDb),
// под рейтингом — число проголосовавших (без подписи: на плитке для неё нет места).
function pcardHtml(c, inLib, rating, votes) {
  const r = Number(rating || 0);
  const v = Number(votes || 0);
  return `
    <div class="tv-card pcard relative w-[calc(var(--card-w)*0.7)] flex-none cursor-pointer overflow-hidden rounded-lg bg-zinc-900 ring-1 ring-white/10 outline-none transition duration-150 focus:z-10 focus:scale-[1.06] focus:ring-2 focus:ring-violet-500 ${inLib ? "" : "opacity-70"}"
      tabindex="0" data-key="${esc(c.kind + "_" + c.tmdbId)}" data-title="${esc(c.title)}" data-year="${c.year || ""}" data-roles="${esc((c.roles || []).slice(0, 2).join(", "))}">
      ${c.poster
        ? `<div class="h-0 w-full bg-zinc-800 bg-cover bg-center pb-[150%]" style="background-image:url('${IMG}/w185${c.poster}')"></div>`
        : `<div class="relative h-0 w-full bg-gradient-to-br from-zinc-800 to-zinc-900 pb-[150%]"><div class="absolute top-0 right-0 bottom-0 left-0 flex items-center justify-center p-1 text-center text-[10px] leading-snug text-zinc-300">${esc(c.title)}</div></div>`}
      ${r ? `<div class="absolute top-1 left-1 rounded bg-black/75 px-1 py-0.5 text-center text-[10px] font-semibold leading-tight text-yellow-300">★ ${r.toFixed(1)}${
        v ? `<div class="text-[9px] font-normal text-zinc-400">${fmtVotes(v)}</div>` : ""}</div>` : ""}
      ${c.year ? `<div class="absolute bottom-1 left-1 rounded bg-black/75 px-1 py-0.5 text-[10px] font-semibold text-zinc-200">${c.year}</div>` : ""}
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
        <!-- Панель контекстная: инфо актёра (фокус на «Назад»/нигде) ИЛИ инфо фильма (фокус на карточке) -->
        <div id="person-info-actor" class="flex min-h-0 flex-1 flex-col">
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
          <div id="person-bio" tabindex="0" class="thin-scroll mt-3 min-h-0 flex-1 overflow-y-auto rounded-md pr-1 text-[clamp(11px,calc(var(--uivh)*2.1),14px)] leading-snug text-zinc-400 outline-none focus:ring-2 focus:ring-violet-500/40"></div>
        </div>
        <div id="person-info-film" class="hidden min-h-0 flex-1 flex-col">
          <!-- Обложка сфокусированного фильма — над описанием, как на детальной странице -->
          <div class="mb-3 flex-none">
            <!-- Обёртка обязательна: padding-bottom в % считается от ширины РОДИТЕЛЯ, и без неё
                 постер тянулся на 150% ширины всей панели (~600px вместо 225). -->
            <div class="w-[42%] max-w-[150px]">
              <div id="pf-poster" class="h-0 w-full rounded-xl bg-zinc-800 bg-cover bg-center pb-[150%] shadow-2xl shadow-black/50 ring-1 ring-white/10"></div>
            </div>
          </div>
          <div id="pf-title" class="flex-none text-[clamp(18px,calc(var(--uivh)*3.8),28px)] font-bold leading-tight tracking-tight"></div>
          <div id="pf-meta" class="mt-1.5 flex-none text-[clamp(11px,calc(var(--uivh)*2),14px)] text-zinc-400"></div>
          <div id="pf-overview" class="mt-3 flex-1 overflow-y-auto pr-1 text-[clamp(12px,calc(var(--uivh)*2.2),15px)] leading-snug text-zinc-300"></div>
        </div>
      </div>
      <div class="flex min-w-0 flex-1 flex-col px-3 pt-2">
        <div class="flex flex-none items-center space-x-3 px-2.5 pt-1.5">
          <span class="text-[clamp(13px,calc(var(--uivh)*2.4),17px)] font-bold text-zinc-400">Фильмография</span>
          <span id="person-progress" class="flex items-center text-[12px] text-zinc-500">
            <svg class="mr-1.5 h-3.5 w-3.5 animate-spin text-violet-400" viewBox="0 0 24 24" fill="none"><circle class="opacity-20" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/><path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" stroke-width="4" stroke-linecap="round"/></svg>
            загружаю полную…
          </span>
        </div>
        <!-- Плитки строками и колонками: свежие первыми, вертикальный скролл -->
        <div id="person-films" class="thin-scroll min-h-0 flex-1 overflow-y-auto pb-3 pl-2.5 pt-2">${spinner("Загружаю фильмографию…")}</div>
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
    .catch(() => { // остаёмся на локальной фильмографии
      const progress = document.getElementById("person-progress");
      if (progress) progress.innerHTML = '<span class="text-zinc-600">полная фильмография недоступна — показано из медиатеки</span>';
    });
}

// Правая зона страницы персоны: фильмография ПЛИТКАМИ (строки × колонки), свежие первыми,
// вертикальный скролл. full=true — пришла полная фильмография TMDb (прячем индикатор).
function renderPersonFilms(credits, lib, full) {
  const el = document.getElementById("person-films");
  if (!el) return;
  const sorted = [...credits].sort((a, b) => (b.year || 0) - (a.year || 0) || String(a.title).localeCompare(String(b.title)));
  el.innerHTML = `
    <div class="flex flex-wrap">
      <div class="mb-3 mr-3">${backTile().replace('w-[var(--card-w)]', 'w-[calc(var(--card-w)*0.7)]')}</div>
      ${sorted.map((c) => {
        const key = c.kind + "_" + c.tmdbId;
        const libItem = lib.get(key);
        // рейтинг: IMDb для того, что в медиатеке; TMDb (из фильмографии) для остального.
        // Голоса берём от ТОГО ЖЕ источника, что и балл, иначе цифры не про одно и то же.
        const imdb = Number((libItem && libItem.imdbRating) || 0);
        const votes = imdb ? Number((libItem && libItem.imdbVotes) || 0) : Number(c.votes || 0);
        return `<div class="mb-3 mr-3">${pcardHtml(c, lib.has(key), imdb || c.rating || 0, votes)}</div>`;
      }).join("")}
    </div>`;
  const progress = document.getElementById("person-progress");
  if (progress && full) progress.remove(); // полная пришла — индикатор больше не нужен
  // Левая панель контекстная: фокус на карточке — инфо фильма (как в списке фильмов:
  // рейтинг/год/длительность/роль + описание), фокус на «Назад» — снова инфо актёра.
  const byKey = new Map(credits.map((c) => [c.kind + "_" + c.tmdbId, c]));
  const showActor = () => {
    document.getElementById("person-info-film")?.classList.add("hidden");
    document.getElementById("person-info-actor")?.classList.remove("hidden");
  };
  const showFilm = (key) => {
    const filmEl = document.getElementById("person-info-film");
    if (!filmEl) return;
    const c = byKey.get(key) || {};
    const li = lib.get(key); // элемент медиатеки — у него данные полнее (IMDb, длительность)
    const rating = Number((li && li.imdbRating) || c.rating || 0);
    const meta = [
      rating ? `<span class="font-semibold text-yellow-300">★ ${rating.toFixed(1)}</span>` : "",
      (li && li.year) || c.year ? String((li && li.year) || c.year) : "",
      li ? fmtRuntime(li.runtime) : "",
      (c.roles || []).length ? esc(c.roles.slice(0, 2).join(", ")) : ""
    ].filter(Boolean).join('<span class="mx-1.5 text-zinc-600">·</span>');
    const poster = (li && li.poster) || c.poster || null;
    const pe = document.getElementById("pf-poster");
    if (pe) {
      pe.style.backgroundImage = poster ? `url('${IMG}/w342${poster}')` : "";
      pe.classList.toggle("hidden", !poster);
    }
    document.getElementById("pf-title").textContent = (li && li.title) || c.title || "";
    document.getElementById("pf-meta").innerHTML = meta;
    document.getElementById("pf-overview").textContent = (li && li.overview) || c.overview || "Нет описания";
    document.getElementById("person-info-actor")?.classList.add("hidden");
    filmEl.classList.remove("hidden");
  };
  el.querySelectorAll(".pcard").forEach((card) => {
    card.addEventListener("focus", () => showFilm(card.dataset.key));
    card.addEventListener("click", () => {
      const it = lib.get(card.dataset.key);
      if (it) { it.isCollection ? enterCollection(it) : enterDetail(it); return; }
      // Нет в медиатеке — открываем ту же страницу с данными TMDb и кнопкой «Скачать»
      const [kind, tmdbId] = String(card.dataset.key).split("_");
      enterGhost({ tmdbId, kind, type: kind === "tv" ? "series" : (state.type || "movie"),
        title: card.dataset.title || "", year: Number(card.dataset.year) || null,
        poster: (byKey.get(card.dataset.key) || {}).poster || null });
    });
  });
  const bt = el.querySelector(".grid-back");
  bt.addEventListener("click", back);
  bt.addEventListener("focus", showActor);
  // Начальный фокус — на «Назад» (страница открывается с инфо актёра). При перерисовке
  // (доехала полная фильмография) фокус и панель сохраняются: та же карточка или биография.
  const prevKey = document.activeElement?.dataset?.key || null;
  const prevCard = prevKey ? el.querySelector(`.pcard[data-key="${CSS.escape(prevKey)}"]`) : null;
  if (prevCard) { prevCard.focus({ preventScroll: true }); showFilm(prevKey); }
  else if (document.activeElement?.id !== "person-bio") { bt.focus({ preventScroll: true }); showActor(); }
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
  if (state.screen === "ghost") state.current = ghostItem(s);
  render();
  if (state.screen === "ghost" && state.current && state.current.loading) loadGhost(s);
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
// Фильм, которого у нас ещё нет (часть коллекции или строка фильмографии): открываем такую же
// страницу, как у своих, только вместо «Смотреть» — «Скачать». Данные тянет агент с сервера.
function enterGhost(g) {
  const tmdbId = g.tmdbId || (g.catalogId || "").split("_")[1];
  if (!tmdbId) return;
  const kind = g.kind || (g.type === "series" ? "tv" : "movie");
  navigate({ screen: "ghost", type: g.type || state.type, tmdbId, kind,
    title: g.title || "", year: g.year || null, poster: g.poster || null });
}
// Назад: кнопка «Назад», Esc/Backspace пульта И браузерная «Назад» — всё через историю.
// В корне (категории) — родительский код: выйти отсюда = покинуть медиатеку.
function back() {
  if (state.screen !== "categories") { history.back(); return; }
  // Детский режим выпускает только по коду. В обычном не спрашиваем ничего: «Назад» на
  // первом экране — это и есть «выйти», лишний вопрос там только мешает.
  if (IN_APP && kidsMode) openPinPad(); else exitApp();
}

/* ---------- Скачивание фильма-«призрака» с rutracker ---------- */
// Универсальный модальный диалог Да/Нет (выбор в JS, без focus() — WebView его не переносит).
let modalSel = "no", modalYes = null;
function paintModalSel() {
  const y = document.getElementById("modal-yes"), n = document.getElementById("modal-no");
  if (!y || !n) return;
  y.classList.toggle("ring-4", modalSel === "yes"); y.classList.toggle("scale-105", modalSel === "yes");
  n.classList.toggle("ring-4", modalSel === "no"); n.classList.toggle("scale-105", modalSel === "no");
}
function askConfirm(text, onYes, labels) {
  closeModal();
  modalYes = onYes;
  // По умолчанию подсвечена безопасная кнопка «Нет»; диалог может попросить обратное
  // (у «Фильм уже начат» ожидаемое действие — «Продолжить»).
  modalSel = (labels && labels.def === "yes") ? "yes" : "no";
  const yesLabel = (labels && labels.yes) || "Да";
  const noLabel = (labels && labels.no) || "Нет";
  const wrap = document.createElement("div");
  wrap.id = "mc-modal";
  wrap.className = "fixed top-0 right-0 bottom-0 left-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm";
  wrap.innerHTML = `
    <div class="max-w-[70%] rounded-3xl border border-white/10 bg-zinc-900/95 px-10 py-8 text-center shadow-2xl shadow-black/60">
      <div class="mb-6 text-2xl font-bold">${esc(text)}</div>
      <div class="mx-auto flex w-[280px] flex-col space-y-3">
        <button id="modal-yes" class="w-full cursor-pointer rounded-2xl bg-gradient-to-r from-violet-600 to-indigo-600 px-9 py-3 text-lg font-bold text-white outline-none ring-violet-400/60 transition">${esc(yesLabel)}</button>
        <button id="modal-no" class="w-full cursor-pointer rounded-2xl border border-white/15 bg-white/5 px-9 py-3 text-lg font-semibold text-zinc-300 outline-none ring-violet-500/50 transition">${esc(noLabel)}</button>
      </div>
    </div>`;
  document.getElementById("rot").appendChild(wrap);
  document.getElementById("modal-yes").addEventListener("click", () => { const cb = modalYes; closeModal(); cb && cb(); });
  document.getElementById("modal-no").addEventListener("click", () => {
    const cb = labels && labels.onNo; closeModal(); cb && cb();
  });
  paintModalSel();
}
function closeModal() { document.getElementById("mc-modal")?.remove(); modalYes = null; }

// Диалог «Скачать?» → поиск на rutracker → окно выбора торрента.
function askDownload(ghost) {
  const kind = ghost.type === "cartoon" ? "мультфильм" : ghost.type === "series" ? "сериал" : "фильм";
  askConfirm(`Скачать этот ${kind}?`, async () => {
    showOverlay("Ищу на rutracker…", false);
    try {
      const q = `title=${encodeURIComponent(ghost.title)}&year=${ghost.year || ""}&type=${ghost.type}`;
      const r = await (await fetch("/api/search-torrents?" + q)).json();
      hideOverlay();
      if (r.error) { showOverlay("⚠️ " + r.error); return setTimeout(hideOverlay, 3500); }
      const list = r.results || [];
      if (!list.length) { showOverlay("Ничего не найдено на rutracker"); return setTimeout(hideOverlay, 3000); }
      showTorrentPicker(ghost, list);
    } catch (_) { showOverlay("⚠️ Ошибка поиска"); setTimeout(hideOverlay, 3000); }
  });
}

// Размер строки rutracker («2.15GB», «980MB», «1.4 GB») → байты.
function parseSize(s) {
  const m = String(s || "").replace(",", ".").match(/([\d.]+)\s*(GB|MB|TB|КБ|МБ|ГБ|ТБ)?/i);
  if (!m) return 0;
  const n = parseFloat(m[1]) || 0;
  const u = (m[2] || "GB").toUpperCase();
  const mul = u.startsWith("T") || u === "ТБ" ? 1e12 : u.startsWith("M") || u === "МБ" ? 1e6 : u.startsWith("K") || u === "КБ" ? 1e3 : 1e9;
  return n * mul;
}
const fmtSize = (b) => b >= 1e9 ? (b / 1e9).toFixed(2) + " ГБ" : (b / 1e6).toFixed(0) + " МБ";

let pickerSel = 0, pickerRows = [];
// Окно выбора торрента: приоритет размеру 1–3 ГБ, внутри — по сидам убыв.; остальные ниже.
function showTorrentPicker(ghost, results) {
  closeModal();
  const scored = results.map((t) => ({ ...t, _bytes: parseSize(t.size), _seeds: Number(t.seeds) || 0 }))
    .sort((a, b) => {
      const pa = a._bytes >= 1e9 && a._bytes <= 3e9 ? 0 : 1;
      const pb = b._bytes >= 1e9 && b._bytes <= 3e9 ? 0 : 1;
      return pa - pb || b._seeds - a._seeds;
    }).slice(0, 30);
  pickerRows = scored; pickerSel = 0;
  const wrap = document.createElement("div");
  wrap.id = "mc-picker";
  wrap.className = "fixed top-0 right-0 bottom-0 left-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm";
  wrap.innerHTML = `
    <div class="flex max-h-[86%] w-[80%] max-w-[900px] flex-col rounded-3xl border border-white/10 bg-zinc-900/95 p-6 shadow-2xl shadow-black/60">
      <div class="mb-3 flex-none text-xl font-bold">${esc(ghost.title)}${ghost.year ? ` (${ghost.year})` : ""} — выбери раздачу</div>
      <div id="picker-list" class="thin-scroll min-h-0 flex-1 space-y-1.5 overflow-y-auto pr-1">
        ${scored.map((t, i) => torrentRow(t, i)).join("")}
      </div>
      <div class="mt-3 flex-none text-[13px] text-zinc-500">1–3 ГБ и больше сидов — сверху · Enter — скачать · Esc — отмена</div>
    </div>`;
  document.getElementById("rot").appendChild(wrap);
  wrap.querySelectorAll(".torrent-row").forEach((row) => {
    row.addEventListener("click", () => { pickerSel = Number(row.dataset.i); doDownload(ghost); });
  });
  paintPicker();
}
function torrentRow(t, i) {
  const inRange = t._bytes >= 1e9 && t._bytes <= 3e9;
  return `
    <div class="torrent-row flex cursor-pointer items-center rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 outline-none transition" data-i="${i}">
      <div class="min-w-0 flex-1">
        <div class="truncate text-[15px] font-semibold">${esc(t.label || t.title || "раздача")}</div>
        <div class="truncate text-[12px] text-zinc-400">${esc(t.meta || t.sublabel || "")}</div>
      </div>
      <div class="ml-3 flex-none text-right">
        <div class="text-[14px] font-semibold ${inRange ? "text-emerald-300" : "text-zinc-300"}">${fmtSize(t._bytes)}</div>
        <div class="text-[12px] text-zinc-400">▲ ${t._seeds} сид${t._seeds % 10 === 1 && t._seeds % 100 !== 11 ? "" : "ов"}</div>
      </div>
    </div>`;
}
function paintPicker() {
  const rows = [...document.querySelectorAll("#mc-picker .torrent-row")];
  rows.forEach((r, i) => {
    r.classList.toggle("ring-2", i === pickerSel);
    r.classList.toggle("ring-violet-500", i === pickerSel);
    r.classList.toggle("bg-violet-500/15", i === pickerSel);
    if (i === pickerSel) r.scrollIntoView({ block: "nearest" });
  });
}
function closePicker() { document.getElementById("mc-picker")?.remove(); pickerRows = []; }
async function doDownload(ghost) {
  const t = pickerRows[pickerSel];
  if (!t) return;
  closePicker();
  showOverlay("Ставлю на загрузку…", false);
  try {
    const q = `tid=${encodeURIComponent(t.tid)}&title=${encodeURIComponent(ghost.title)}&year=${ghost.year || ""}&type=${ghost.type}`;
    const r = await (await fetch("/api/download?" + q)).json();
    showOverlay(r.ok ? "Загрузка начата — прогресс на плашке" : "⚠️ " + (r.error || "ошибка"), false);
  } catch (_) { showOverlay("⚠️ Не удалось поставить загрузку"); }
  setTimeout(hideOverlay, 3000);
  await loadDownloads();
  if (state.screen === "collection" || state.screen === "grid") rerenderKeepingFocus();
}

/* ---------- Выход из приложения (диалог на корневом экране) ----------
   ВАЖНО: выбор Да/Нет ведём в JS-переменной с ручной подсветкой, НЕ через focus():
   в WebView (touch mode) focus() на кнопки не переносится, activeElement оставался
   на плитке ПОД диалогом, и Enter кликал её. */
let exitSel = "no";
function paintExitSel() {
  const yes = document.getElementById("exit-yes"), no = document.getElementById("exit-no");
  if (!yes || !no) return;
  yes.classList.toggle("ring-4", exitSel === "yes");
  yes.classList.toggle("scale-105", exitSel === "yes");
  no.classList.toggle("ring-4", exitSel === "no");
  no.classList.toggle("scale-105", exitSel === "no");
}
function showExitConfirm() {
  if (document.getElementById("exit-confirm")) return;
  const wrap = document.createElement("div");
  wrap.id = "exit-confirm";
  wrap.className = "fixed top-0 right-0 bottom-0 left-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm";
  wrap.innerHTML = `
    <div class="rounded-3xl border border-white/10 bg-zinc-900/95 px-10 py-8 text-center shadow-2xl shadow-black/60">
      <div class="mb-6 text-2xl font-bold">Выйти из приложения?</div>
      <div class="flex justify-center space-x-4">
        <button id="exit-yes" class="cursor-pointer rounded-2xl bg-gradient-to-r from-violet-600 to-indigo-600 px-9 py-3 text-lg font-bold text-white outline-none ring-violet-400/60 transition">Да</button>
        <button id="exit-no" class="cursor-pointer rounded-2xl border border-white/15 bg-white/5 px-9 py-3 text-lg font-semibold text-zinc-300 outline-none ring-violet-500/50 transition">Нет</button>
      </div>
    </div>`;
  document.getElementById("rot").appendChild(wrap);
  document.getElementById("exit-yes").addEventListener("click", exitApp);
  document.getElementById("exit-no").addEventListener("click", hideExitConfirm);
  exitSel = "no"; // безопасный дефолт — «Нет»
  paintExitSel();
}
function hideExitConfirm() {
  const el = document.getElementById("exit-confirm");
  if (el) el.remove();
  const tile = app.querySelector(".cat-tile");
  if (tile) tile.focus();
}
function exitApp() {
  // В приложении — мост в активити (закрыть и убить процесс); в браузере — попытка close.
  if (window.MCApp && window.MCApp.exitApp) window.MCApp.exitApp();
  else window.close();
}
// Аппаратная «Назад» приложения зовёт сюда (см. MainActivity.onBackPressed) — единая
// точка Back-логики: сначала закрываем открытый оверлей (пикер/модалка/диалог), затем
// навигация по истории, в корне — вопрос о выходе. Всегда возвращаем true (обрабатываем сами).
window.mcHandleBack = () => {
  // Оверлеи — первыми, иначе Back уходил в историю ПОД ними: трейлер оставался играть
  // поверх уже сменившегося экрана, и выйти из него было нечем.
  if (document.getElementById("settings-box")) { closeSettings(); return true; }
  if (closeTrailerInline()) return true;
  if (document.getElementById("pin-pad")) { closePinPad(); return true; }
  if (document.getElementById("mc-picker")) { closePicker(); return true; }
  if (document.getElementById("mc-modal")) { closeModal(); return true; }
  if (document.getElementById("exit-confirm")) { hideExitConfirm(); return true; }
  if (state.screen !== "categories") { history.back(); return true; }
  // В детском режиме с первого экрана выпускает только код — иначе «Назад» была бы дырой
  // шире замка: приставка и так наш домашний экран. В обычном — выходим сразу, без вопросов.
  if (kidsMode) openPinPad(); else exitApp();
  return true;
};
// Брендовая кнопка пульта (Xiaomi TV+, YouTube, Netflix…) — всегда на первый экран медиатеки,
// даже если мы уже внутри: зритель жмёт её, чтобы «вернуться к началу», а не продолжить с места.
window.mcGoHome = () => {
  closeTrailerInline();
  if (document.getElementById("pin-pad")) closePinPad();
  if (document.getElementById("mc-picker")) closePicker();
  if (document.getElementById("mc-modal")) closeModal();
  history.pushState({ screen: "categories" }, "");
  applyState({ screen: "categories" });
  return true;
};

window.addEventListener("popstate", (e) => applyState(e.state || { screen: "categories" }));

/* ---------- Навигация пультом ---------- */
document.addEventListener("keydown", (e) => {
  armOrientation(); // первая клавиша — момент для fullscreen + landscape-lock
  // Трейлер поверх всего: любая «назад» закрывает его и возвращает в медиатеку
  // Экран настроек: ↑/↓ по носителям, OK — включить/выключить, ←/→ — лимит, Back — выход.
  const st = document.getElementById("settings-box");
  if (st) {
    e.preventDefault();
    if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) return void closeSettings();
    const items = [...st.querySelectorAll(".st-vol, #st-limit, #st-close")];
    const i = items.indexOf(document.activeElement);
    if (e.key === "ArrowDown") return void items[Math.min(items.length - 1, i + 1)]?.focus();
    if (e.key === "ArrowUp") return void items[Math.max(0, i - 1)]?.focus();
    if (document.activeElement?.id === "st-limit" && (e.key === "ArrowLeft" || e.key === "ArrowRight")) {
      const cur = storageState?.internalPercent ?? 60;
      const next = Math.max(5, Math.min(100, cur + (e.key === "ArrowRight" ? 5 : -5)));
      if (next !== cur) {
        // рисуем сразу, сохраняем следом — ползунок не должен ждать сеть
        document.getElementById("st-pct").textContent = next;
        document.getElementById("st-limit-bar").style.width = next + "%";
        storageState.internalPercent = next;
        clearTimeout(saveStorage.t);
        saveStorage.t = setTimeout(() => saveStorage({ internalPercent: next }), 500);
      }
      return;
    }
    if (e.key === "Enter" || e.key === " ") return void document.activeElement?.click();
    return;
  }

  if (document.getElementById("trailer-box")) {
    e.preventDefault();
    if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) return void closeTrailerInline();
    if (["Enter", " ", "MediaPlayPause", "MediaPlay", "MediaPause"].includes(e.key)) return trailerToggle();
    if (e.key === "ArrowRight" || e.key === "MediaFastForward") return trailerSeek(10);
    if (e.key === "ArrowLeft" || e.key === "MediaTrackPrevious") return trailerSeek(-10);
    if (e.key === "MediaStop") return void closeTrailerInline();
    return;
  }
  // Окно выбора торрента: ↑/↓ по списку, Enter — скачать, Esc/Back — отмена.
  const picker = document.getElementById("mc-picker");
  if (picker) {
    e.preventDefault();
    if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) return closePicker();
    if (e.key === "ArrowDown") { pickerSel = Math.min(pickerRows.length - 1, pickerSel + 1); paintPicker(); }
    else if (e.key === "ArrowUp") { pickerSel = Math.max(0, pickerSel - 1); paintPicker(); }
    else if (e.key === "Enter" || e.key === " ") picker.querySelector(`.torrent-row[data-i="${pickerSel}"]`)?.click();
    return;
  }
  // Панель родительского кода: стрелки — по цифрам, Enter — нажать, Back — отмена.
  // Перехватываем всё, чтобы из-под неё нельзя было управлять медиатекой.
  if (document.getElementById("pin-pad")) {
    e.preventDefault();
    if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) return closePinPad();
    if (e.key === "Enter" || e.key === " ") return document.activeElement?.click();
    // Сетка 3 в ряд — nearest() тут не помощник, он ходит только по карточкам медиатеки.
    const keys = [...document.querySelectorAll(".pin-key")];
    const i = keys.indexOf(document.activeElement);
    if (i < 0) return keys[0]?.focus();
    const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -3, ArrowDown: 3 }[e.key];
    if (step === undefined) return;
    // ←/→ не должны перепрыгивать на соседнюю строку
    if (Math.abs(step) === 1 && Math.floor((i + step) / 3) !== Math.floor(i / 3)) return;
    keys[i + step]?.focus();
    return;
  }
  // Модальный Да/Нет (скачать?): ←/→ между кнопками, Enter, Back = отмена.
  if (document.getElementById("mc-modal")) {
    e.preventDefault();
    if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) return closeModal();
    if (e.key === "ArrowLeft" || e.key === "ArrowUp") { modalSel = "yes"; paintModalSel(); }
    else if (e.key === "ArrowRight" || e.key === "ArrowDown") { modalSel = "no"; paintModalSel(); }
    else if (e.key === "Enter" || e.key === " ") document.getElementById(modalSel === "yes" ? "modal-yes" : "modal-no")?.click();
    return;
  }
  // Диалог «Выйти из приложения?» перехватывает всё: ←/→ между Да/Нет, Enter, Back = отмена.
  if (document.getElementById("exit-confirm")) {
    e.preventDefault();
    if (["Escape", "Backspace", "GoBack", "BrowserBack"].includes(e.key)) return hideExitConfirm();
    if (e.key === "ArrowLeft") { exitSel = "yes"; paintExitSel(); }
    else if (e.key === "ArrowRight") { exitSel = "no"; paintExitSel(); }
    else if (e.key === "Enter" || e.key === " ") { exitSel === "yes" ? exitApp() : hideExitConfirm(); }
    return;
  }
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
    if (cur === document.getElementById("cat-settings")) {
      if (e.key === "ArrowDown") { e.preventDefault(); tiles[0]?.focus(); }
      else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openSettings(); }
      return;
    }
    if (e.key === "ArrowRight") { e.preventDefault(); tiles[Math.min(tiles.length - 1, idx + 1)]?.focus(); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); tiles[Math.max(0, idx - 1)]?.focus(); }
    // вверх — к предложению установить VLC, если оно есть, иначе к шестерёнке настроек
    else if (e.key === "ArrowUp" && (vlc || document.getElementById("cat-settings"))) {
      e.preventDefault(); (vlc || document.getElementById("cat-settings")).focus();
    }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); cur?.dataset?.type && enterGrid(cur.dataset.type); }
    return;
  }

  if (state.screen === "grid" || state.screen === "collection" || state.screen === "person") {
    // Биография персоны — зона: ←/→ входят/выходят, ↑/↓ листают текст.
    const bio = state.screen === "person" ? document.getElementById("person-bio") : null;
    const bioScrolls = bio && bio.scrollHeight > bio.clientHeight + 2;
    if (cur === bio) {
      e.preventDefault();
      const STEP = Math.max(40, Math.floor(bio.clientHeight * 0.6));
      if (e.key === "ArrowDown") bio.scrollTop += STEP;
      else if (e.key === "ArrowUp") bio.scrollTop -= STEP;
      else if (e.key === "ArrowRight") { const c = app.querySelector(".pcard, .tv-card"); if (c) c.focus({ preventScroll: true }); }
      return;
    }
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) {
      e.preventDefault();
      const next = nearest(cur, { ArrowLeft: "left", ArrowRight: "right", ArrowUp: "up", ArrowDown: "down" }[e.key]);
      if (next) { next.focus({ preventScroll: true }); next.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" }); }
      else if (e.key === "ArrowLeft" && bioScrolls) bio.focus({ preventScroll: true }); // из сетки влево — в биографию
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
    const desc = document.getElementById("detail-desc");
    const descScrolls = desc && desc.scrollHeight > desc.clientHeight + 2; // зона, только если есть что листать
    const focusEl = (el) => { if (el) { el.focus({ preventScroll: true }); el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" }); } };
    const aIdx = actors.indexOf(cur);
    const rowIdx = epRows.findIndex((r) => r.includes(cur));
    const bIdx = buttons.indexOf(cur);
    const inDesc = cur === desc;
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
      const d = e.key === "ArrowRight" ? 1 : -1;
      if (aIdx >= 0) focusEl(actors[Math.max(0, Math.min(actors.length - 1, aIdx + d))]);
      else if (rowIdx >= 0) { const row = epRows[rowIdx]; focusEl(row[Math.max(0, Math.min(row.length - 1, row.indexOf(cur) + d))]); }
      else if (bIdx >= 0) focusEl(buttons[Math.max(0, Math.min(buttons.length - 1, bIdx + d))]);
      else if (!inDesc) focusEl(buttons[0] || epRows[0]?.[0] || actors[0]);
      return;
    }
    const STEP = Math.max(40, Math.floor((desc ? desc.clientHeight : 0) * 0.6));
    // У сериала кнопки стоят НАД списком серий (data-top) — порядок зон по вертикали другой:
    // актёры → описание → кнопки → серии (у фильма: актёры → описание → кнопки внизу).
    const btnTop = !!app.querySelector('#detail-buttons[data-top]');
    if (e.key === "ArrowDown") {
      if (aIdx >= 0) focusEl(descScrolls ? desc : (btnTop ? buttons[0] : (epRows[0]?.[0] || buttons[0])));
      else if (inDesc) {
        // листаем описание; дочитали — идём дальше вниз
        if (desc.scrollTop + desc.clientHeight < desc.scrollHeight - 2) desc.scrollTop += STEP;
        else focusEl(btnTop ? buttons[0] : (epRows[0]?.[0] || buttons[0]));
      }
      else if (bIdx >= 0) { if (btnTop) focusEl(epRows[0]?.[0]); } // кнопки внизу — дальше некуда
      else if (rowIdx >= 0) { if (rowIdx < epRows.length - 1) focusEl(epRows[rowIdx + 1][0]); else if (!btnTop) focusEl(buttons[0]); }
      return;
    }
    // ArrowUp
    if (bIdx >= 0) focusEl(btnTop ? (descScrolls ? desc : actors[0]) : (epRows.length ? epRows[epRows.length - 1][0] : (descScrolls ? desc : actors[0])));
    else if (inDesc) {
      if (desc.scrollTop > 0) desc.scrollTop -= STEP;
      else focusEl(actors[0]);
    }
    else if (rowIdx >= 0) focusEl(rowIdx > 0 ? epRows[rowIdx - 1][0] : (btnTop ? buttons[0] : (descScrolls ? desc : actors[0])));
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


// Трейлер играем ВНУТРИ приложения (youtube.com/embed): приложения YouTube на приставке нет,
// а открывать его ради ролика значило бы выпускать зрителя в бесконечную ленту. Здесь же
// доступен ровно один ролик, Back возвращает в медиатеку.
// Пультом управляем САМИ, а не отдаём клавиши плееру YouTube: его встроенные контролы ловят
// фокус внутрь iframe (это чужой origin — наши обработчики там уже не слышат клавиш), и зритель
// оказывается заперт в ролике. Поэтому iframe — только картинка (`pointer-events:none`, фокус
// возвращаем себе), а пауза/перемотка идут командами postMessage через YouTube IFrame API.
let trailerTimer = null;
let trailerTime = 0;     // текущая позиция, её присылает сам плеер (infoDelivery)
let trailerPlaying = true;

function trailerCmd(func, args) {
  const f = document.querySelector("#trailer-box iframe");
  if (!f || !f.contentWindow) return;
  try {
    f.contentWindow.postMessage(JSON.stringify({ event: "command", func, args: args || [] }), "*");
  } catch (_) {}
}

// Плеер шлёт состояние только тому, кто попросил: после загрузки отправляем «listening».
function onTrailerMessage(e) {
  if (!/youtube(-nocookie)?\.com$/.test(String(e.origin).replace(/^https?:\/\//, ""))) return;
  try {
    const d = typeof e.data === "string" ? JSON.parse(e.data) : e.data;
    const info = d && d.info;
    if (!info) return;
    if (typeof info.currentTime === "number") trailerTime = info.currentTime;
    if (typeof info.playerState === "number") {
      trailerPlaying = info.playerState === 1;
      // 0 = ended: ролик кончился — закрываем сами, чтобы не оставлять зрителя перед
      // экраном «похожих видео» YouTube, из которого он всё равно никуда не пойдёт.
      if (info.playerState === 0) closeTrailerInline();
    }
  } catch (_) {}
}

function openTrailerInline(key, title) {
  if (document.getElementById("trailer-box")) return;
  trailerTime = 0;
  trailerPlaying = true;
  const box = document.createElement("div");
  box.id = "trailer-box";
  box.tabIndex = 0;   // клавиши должны приходить в НАШ документ
  box.className = "fixed top-0 right-0 bottom-0 left-0 z-50 bg-black outline-none";
  // controls=0 — свои контролы плеера не нужны, до них всё равно не добраться пультом
  // Надписи гаснут через пару секунд: ролик смотрят, а не читают экран.
  box.innerHTML = `
    <iframe class="pointer-events-none h-full w-full border-0" allow="autoplay; encrypted-media"
      src="https://www.youtube.com/embed/${encodeURIComponent(key)}?autoplay=1&rel=0&modestbranding=1&controls=0&playsinline=1&enablejsapi=1"></iframe>
    <div id="trailer-osd" class="pointer-events-none absolute top-0 right-0 bottom-0 left-0 transition-opacity duration-700">
      <div class="absolute top-4 left-6 text-lg text-zinc-300">${esc(title || "Трейлер")}</div>
      <div id="trailer-hint" class="absolute right-6 bottom-5 left-6 text-center text-sm text-zinc-500">
        OK — пауза · ← / → — 10 секунд</div>
    </div>`;
  document.body.appendChild(box);
  box.focus();
  setTimeout(() => document.getElementById("trailer-osd")?.classList.add("opacity-0"), 2500);
  window.addEventListener("message", onTrailerMessage);
  const f = box.querySelector("iframe");
  f.addEventListener("load", () => {
    try { f.contentWindow.postMessage(JSON.stringify({ event: "listening" }), "*"); } catch (_) {}
  });
  // WebView норовит отдать фокус iframe при автозапуске — забираем обратно, пока ролик открыт.
  trailerTimer = setInterval(() => {
    if (document.activeElement !== box) box.focus();
  }, 500);
}

// Любое действие пультом снова показывает подпись — и она опять гаснет.
function trailerOsd(text) {
  const osd = document.getElementById("trailer-osd");
  const hint = document.getElementById("trailer-hint");
  if (!osd || !hint) return;
  hint.textContent = text;
  osd.classList.remove("opacity-0");
  clearTimeout(trailerOsd.t);
  trailerOsd.t = setTimeout(() => osd.classList.add("opacity-0"), 2500);
}

function trailerToggle() {
  trailerPlaying = !trailerPlaying;
  trailerCmd(trailerPlaying ? "playVideo" : "pauseVideo");
  trailerOsd(trailerPlaying ? "Воспроизведение" : "Пауза");
}

function trailerSeek(delta) {
  trailerTime = Math.max(0, trailerTime + delta);
  trailerCmd("seekTo", [trailerTime, true]);
  trailerOsd((delta > 0 ? "+" : "−") + Math.abs(delta) + " секунд");
}

function closeTrailerInline() {
  const b = document.getElementById("trailer-box");
  if (!b) return false;
  clearInterval(trailerTimer);
  trailerTimer = null;
  window.removeEventListener("message", onTrailerMessage);
  b.remove();
  // Возвращаем фокус на кнопку, с которой ушли: после автозакрытия пульт иначе «повисает».
  document.getElementById("detail-trailer")?.focus();
  return true;
}

let playBusy = false;

// Фильм начат — предлагаем выбор вместо молчаливого решения за зрителя: продолжить с той
// секунды, где остановились, или смотреть сначала.
function play(id) {
  const it = items.find((x) => x.id === id);
  // Секунду остановки VLC отдаёт при выходе, агент её запоминает — показываем прямо на кнопке.
  const pos = Number((it && it.position) || 0);
  if (it && (pos > 5000 || it.started) && !it.watched) {
    return askConfirm("Фильм уже начат", () => startPlay(id, false),
      { yes: pos > 5000 ? `Продолжить с ${fmtDur(Math.round(pos / 1000))}` : "Продолжить",
        no: "Начать сначала", def: "yes", onNo: () => startPlay(id, true) });
  }
  return startPlay(id, false);
}

async function startPlay(id, fromStart) {
  // Кнопка «Смотреть» срабатывает дважды: наш обработчик Enter зовёт click(), и браузер
  // генерирует click сам. Два интента подряд — второй обрывает первому соединение с потоком
  // ("http stream: connection failed"), и фильм не стартует.
  if (playBusy) return;
  playBusy = true;
  setTimeout(() => { playBusy = false; }, 4000);
  showOverlay("Запускаю плеер…", true);
  // В приложении плеер запускаем САМИ через мост: агент сидит в фоне Termux, и с Android 12+
  // система рубит его `am start` как background activity launch. Агент в этом режиме только
  // отдаёт адрес потока. В браузере моста нет — там всё как раньше, запускает агент.
  const viaApp = IN_APP && window.MCApp && typeof window.MCApp.playVideo === "function";
  try {
    const r = await (await fetch("/api/play?id=" + encodeURIComponent(id) + (viaApp ? "&via=app" : "")
      + (fromStart ? "&fromStart=1" : ""))).json();
    if (r.ok && viaApp) {
      const it = items.find((x) => x.id === id);
      const pos = fromStart ? 0 : Number((it && it.position) || 0);
      window.MCApp.playVideo(r.url, r.package || "", r.title || "", r.subtitles || "", id, !!fromStart, pos);
    }
    showOverlay(r.ok ? "Играет в плеере" : "⚠️ " + (r.error || "ошибка"), r.ok);
  } catch (_) { showOverlay("⚠️ Не удалось запустить"); }
  setTimeout(hideOverlay, 2500);
}
// --- Родительский код: экранная цифровая панель (на пульте Google TV цифр нет) ---
let pinEntered = "";
const PIN_MAX = 8;

function paintPin() {
  const dots = document.getElementById("pin-dots");
  if (dots) dots.textContent = pinEntered ? "•".repeat(pinEntered.length) : "";
}

function openPinPad() {
  if (document.getElementById("pin-pad")) return;
  pinEntered = "";
  const keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"];
  const wrap = document.createElement("div");
  wrap.id = "pin-pad";
  wrap.className = "fixed top-0 right-0 bottom-0 left-0 z-50 flex flex-col items-center justify-center bg-black/85 backdrop-blur";
  wrap.innerHTML = `
    <div class="mb-2 text-2xl font-semibold">Родительский код</div>
    <div class="mb-6 text-base text-zinc-400">введите код, чтобы выйти из режима ТВ</div>
    <div id="pin-dots" class="mb-6 h-9 text-4xl tracking-[0.4em] text-violet-300"></div>
    <div class="grid grid-cols-3 gap-3">
      ${keys.map((k) => `
        <button tabindex="0" data-key="${k}"
          class="pin-key h-16 w-24 cursor-pointer rounded-2xl border border-zinc-700 bg-zinc-900 text-2xl font-semibold outline-none transition focus:scale-105 focus:border-violet-500/60 focus:bg-zinc-800 focus:ring-4 focus:ring-violet-500/25">${k}</button>`).join("")}
    </div>
    <div id="pin-msg" class="mt-5 h-6 text-lg text-red-400"></div>`;
  document.body.appendChild(wrap);
  wrap.querySelectorAll(".pin-key").forEach((b) => b.addEventListener("click", () => pinKey(b.dataset.key)));
  wrap.querySelector(".pin-key").focus();
}

function closePinPad() {
  document.getElementById("pin-pad")?.remove();
  pinEntered = "";
  renderCategories();
}

async function pinKey(k) {
  const msg = document.getElementById("pin-msg");
  if (msg) msg.textContent = "";
  if (k === "⌫") { pinEntered = pinEntered.slice(0, -1); return paintPin(); }
  if (k !== "OK") {
    if (pinEntered.length < PIN_MAX) pinEntered += k;
    return paintPin();
  }
  if (!pinEntered) return;
  try {
    const r = await fetch("/api/kiosk-exit", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pin: pinEntered })
    });
    const j = await r.json();
    if (j.ok) {
      document.getElementById("pin-pad")?.remove();
      // Уводит с экрана САМО приложение: `am start` из фонового Termux Android 12+ рубит
      // как BAL — агент код проверил, но открыть ничего не смог бы (проверено на приставке).
      try { if (window.MCApp) MCApp.openSettings(); else exitApp(); } catch (_) {}
      return;
    }
    if (msg) msg.textContent = j.error || "неверный код";
  } catch (_) {
    if (msg) msg.textContent = "нода не ответила";
  }
  pinEntered = "";
  paintPin();
}

// ---------- Настройки: хранилища ----------
// Показываем, где лежит медиатека, сколько занято, и даём выбрать носители. Флешку агент
// находит сам; здесь только выбор «куда писать» и лимит для встроенной памяти (её на
// телевизорах пара гигабайт, и занимать её целиком нельзя — системе тоже нужно место).
const fmtBytes = (n) => {
  const v = Number(n) || 0;
  if (v >= 1e12) return (v / 1e12).toFixed(1).replace(".", ",") + " ТБ";
  if (v >= 1e9) return (v / 1e9).toFixed(1).replace(".", ",") + " ГБ";
  if (v >= 1e6) return Math.round(v / 1e6) + " МБ";
  return Math.round(v / 1e3) + " КБ";
};

let storageState = null;

async function openSettings() {
  if (document.getElementById("settings-box")) return;
  const box = document.createElement("div");
  box.id = "settings-box";
  box.className = "fixed top-0 right-0 bottom-0 left-0 z-50 overflow-y-auto bg-zinc-950/95 px-12 py-8 backdrop-blur";
  box.innerHTML = `<div class="text-2xl font-bold">Настройки</div>
    <div class="mt-6 text-zinc-400">Загружаю хранилища…</div>`;
  document.body.appendChild(box);
  try {
    storageState = await (await fetch("/api/storage", { cache: "no-store" })).json();
  } catch (_) {
    storageState = { volumes: [], internalPercent: 60 };
  }
  paintSettings();
}

function paintSettings() {
  const box = document.getElementById("settings-box");
  if (!box || !storageState) return;
  const vols = storageState.volumes || [];
  box.innerHTML = `
    <div class="flex items-center space-x-4">
      <div class="text-[clamp(20px,calc(var(--uivh)*3.4),28px)] font-bold">Настройки · хранилище</div>
      <div class="text-zinc-500">${esc(deviceName || "")}</div>
    </div>
    <div class="mt-2 text-zinc-400">Где хранить медиатеку. Отмеченные носители используются
      для скачивания и переносов; фильмы с них показываются вместе.</div>
    <div class="mt-6 space-y-4">
      ${vols.length ? vols.map((v) => {
        const usedPct = v.totalBytes ? Math.min(100, Math.round((v.totalBytes - v.freeBytes) / v.totalBytes * 100)) : 0;
        const ourPct = v.totalBytes ? Math.min(100, Math.round(v.usedByUsBytes / v.totalBytes * 100)) : 0;
        return `
        <div class="st-vol flex cursor-pointer items-center space-x-5 rounded-2xl border ${v.selected ? "border-violet-500/60 bg-violet-500/10" : "border-zinc-800 bg-zinc-900/70"} px-6 py-4 outline-none transition focus:scale-[1.01] focus:border-violet-400 focus:ring-4 focus:ring-violet-500/25"
             tabindex="0" data-id="${esc(v.id)}">
          <div class="grid h-12 w-12 flex-none place-items-center rounded-xl ${v.selected ? "bg-violet-600 text-white" : "bg-zinc-800 text-zinc-400"}">
            ${ICONS.drive("h-6 w-6")}
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex items-baseline space-x-3">
              <span class="text-xl font-semibold">${esc(v.label)}</span>
              ${v.removable ? '<span class="rounded-full bg-zinc-800 px-2.5 py-0.5 text-xs text-zinc-400">съёмный</span>' : ""}
            </div>
            <div class="mt-2 h-2 w-full overflow-hidden rounded-full bg-zinc-800">
              <div class="h-full bg-zinc-600" style="width:${usedPct}%"></div>
            </div>
            <div class="mt-2 text-sm text-zinc-400">
              свободно ${fmtBytes(v.freeBytes)} из ${fmtBytes(v.totalBytes)}
              · наша медиатека ${fmtBytes(v.usedByUsBytes)} (${ourPct}%)
              · можно занять ещё ${fmtBytes(v.writableBytes)}
              ${v.writeMbS ? `· запись ${v.writeMbS.toFixed(1)} МБ/с` : ""}
            </div>
          </div>
          <div class="ml-4 grid h-9 w-9 flex-none place-items-center rounded-full ${v.selected ? "bg-violet-600 text-white" : "border border-zinc-700 text-transparent"}">
            ${ICONS.check("h-5 w-5")}
          </div>
        </div>`;
      }).join("") : '<div class="text-zinc-500">Носителей не найдено</div>'}
    </div>
    ${vols.some((v) => !v.removable) ? `
      <div class="mt-8">
        <div class="text-lg font-semibold">Лимит встроенной памяти: <span id="st-pct">${storageState.internalPercent}</span>%</div>
        <div class="mt-1 text-sm text-zinc-400">Сколько места на встроенной памяти можно занять медиатекой. ← / → меняют на 5%.</div>
        <div id="st-limit" tabindex="0" class="mt-3 h-3 w-full max-w-xl overflow-hidden rounded-full bg-zinc-800 outline-none ring-violet-500/40 focus:ring-4">
          <div id="st-limit-bar" class="h-full bg-violet-500" style="width:${storageState.internalPercent}%"></div>
        </div>
      </div>` : ""}
    <div class="mt-8">
      <div class="text-lg font-semibold">Как скачивать на съёмный носитель</div>
      <div class="mt-1 text-sm text-zinc-400">Торрент пишет куски вразнобой, и медленная флешка от этого
        проседает втрое. Через буфер файл сначала целиком приезжает во встроенную память, а потом
        одним потоком перекладывается на носитель.</div>
      <div class="mt-3 flex space-x-3">
        ${[["auto", "Автоматически"], ["on", "Всегда через буфер"], ["off", "Всегда напрямую"]].map(([id, label]) => `
          <div class="st-mode cursor-pointer rounded-2xl border px-5 py-3 text-base font-semibold outline-none transition focus:scale-[1.03] focus:border-violet-400 focus:ring-4 focus:ring-violet-500/25
               ${(storageState.bufferMode || "auto") === id ? "border-violet-500/60 bg-violet-500/10 text-white" : "border-zinc-800 bg-zinc-900/70 text-zinc-300"}"
               tabindex="0" data-mode="${id}">${label}</div>`).join("")}
      </div>
    </div>
    <div class="mt-10 flex space-x-4">
      <button id="st-close" tabindex="0" class="flex cursor-pointer items-center rounded-2xl border border-white/15 bg-white/5 px-5 py-3 text-lg font-semibold text-zinc-200 outline-none transition focus:scale-[1.03] focus:border-violet-400 focus:ring-4 focus:ring-violet-500/40">${ICONS.back("mr-2 h-5 w-5")} Назад</button>
    </div>`;

  box.querySelectorAll(".st-vol").forEach((el) => el.addEventListener("click", () => toggleVolume(el.dataset.id)));
  box.querySelectorAll(".st-mode").forEach((el) => el.addEventListener("click", () => saveStorage({ bufferMode: el.dataset.mode })));
  document.getElementById("st-close")?.addEventListener("click", closeSettings);
  (box.querySelector(".st-vol") || document.getElementById("st-close"))?.focus();
}

async function saveStorage(patch) {
  try {
    storageState = await (await fetch("/api/storage", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(patch)
    })).json();
    paintSettings();
    reloadLibrary().then((changed) => { if (changed) rerenderKeepingFocus(); });
  } catch (_) {}
}

function toggleVolume(id) {
  if (!storageState) return;
  const vols = storageState.volumes || [];
  const selected = vols.filter((v) => v.selected).map((v) => v.id);
  const next = selected.includes(id) ? selected.filter((x) => x !== id) : selected.concat(id);
  if (!next.length) return;   // хотя бы один носитель должен остаться
  saveStorage({ selected: next });
}

function closeSettings() {
  document.getElementById("settings-box")?.remove();
  renderCategories();
  return true;
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
