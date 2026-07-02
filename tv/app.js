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
let state = { screen: "categories", type: "movie" };

let playerMissing = false;

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
    <div class="flex h-screen flex-col">
      <div class="flex items-center space-x-5 px-12 pt-[clamp(12px,3vh,32px)]">
        ${logo("h-[clamp(36px,6vh,48px)] w-[clamp(36px,6vh,48px)]")}
        <span class="text-[clamp(22px,4vh,30px)] font-extrabold tracking-tight">MediaCenter</span>
        ${deviceName ? `<span class="rounded-full border border-zinc-800 bg-zinc-900/80 px-4 py-1.5 text-lg text-zinc-400">${esc(deviceName)}</span>` : ""}
      </div>
      ${playerMissing ? `
        <button id="cat-vlc" tabindex="0" class="mx-12 mt-4 flex cursor-pointer items-center self-start rounded-2xl border border-red-500/30 bg-red-500/10 px-6 py-3.5 text-lg font-semibold text-red-300 outline-none transition focus:scale-[1.02] focus:border-red-400 focus:ring-4 focus:ring-red-500/30">
          ${ICONS.download("mr-3 h-6 w-6")} Установить плеер VLC — нужен для просмотра
        </button>` : ""}
      <div class="flex flex-1 items-center justify-center space-x-10 px-12">
        ${CATS.map((c) => `
          <div class="cat-tile group flex h-[clamp(250px,60vh,420px)] w-[clamp(220px,24vw,340px)] cursor-pointer flex-col items-center justify-center space-y-[clamp(12px,3vh,28px)] rounded-[28px] border border-zinc-800 bg-zinc-900/70 outline-none backdrop-blur transition duration-150 focus:scale-105 focus:border-violet-500/60 focus:shadow-[0_0_70px_-12px_rgba(139,92,246,.55)] focus:ring-4 focus:ring-violet-500/25" tabindex="0" data-type="${c.type}">
            <div class="grid h-[clamp(64px,16vh,112px)] w-[clamp(64px,16vh,112px)] place-items-center rounded-3xl bg-gradient-to-br from-violet-500/15 to-indigo-500/5 text-violet-300 ring-1 ring-violet-500/20 transition duration-150 group-focus:from-violet-500 group-focus:to-indigo-600 group-focus:text-white group-focus:shadow-lg group-focus:shadow-violet-500/40 group-focus:ring-0">
              ${c.icon("h-1/2 w-1/2")}
            </div>
            <div class="text-[clamp(20px,3.6vh,30px)] font-bold tracking-tight">${c.label}</div>
            <div class="rounded-full bg-zinc-800/80 px-4 py-1 text-[clamp(14px,2.2vh,18px)] text-zinc-400">${byType(c.type).length} шт.</div>
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
    <div class="flex h-screen">
      <div class="flex w-[32%] min-w-[280px] max-w-[460px] flex-col space-y-3.5 overflow-hidden border-r border-white/5 bg-zinc-900/60 px-9 py-10 backdrop-blur-xl" id="grid-info"></div>
      <div class="flex-1 overflow-y-auto px-10 py-7">
        <div class="mb-5 flex items-center space-x-4">
          <button class="grid-back flex cursor-pointer items-center rounded-xl border border-white/10 bg-white/5 py-2.5 pl-3 pr-5 text-lg font-semibold outline-none backdrop-blur transition duration-150 focus:scale-105 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/30" tabindex="0">${ICONS.back("mr-1.5 h-5 w-5")} Назад</button>
          <span class="text-violet-400">${cat.icon("h-7 w-7")}</span>
          <h2 class="m-0 text-2xl font-bold tracking-tight">${cat.label}</h2>
          <span class="rounded-full bg-zinc-800/80 px-3 py-0.5 text-base text-zinc-400">${list.length}</span>
        </div>
        <div class="grid grid-cols-[repeat(4,var(--card-w))] justify-start gap-5">
          ${list.map((i) => `
            <div class="tv-card relative w-[var(--card-w)] cursor-pointer overflow-hidden rounded-2xl bg-zinc-900 ring-1 ring-white/5 outline-none transition duration-150 focus:z-10 focus:scale-[1.07] focus:shadow-[0_16px_50px_-8px_rgba(139,92,246,.45)] focus:ring-[3px] focus:ring-violet-500" tabindex="0" data-id="${esc(i.id)}">
              ${poster(i.poster) ? `<div class="h-0 w-full bg-zinc-800 bg-cover bg-center pb-[150%]" style="background-image:url('${poster(i.poster)}')"></div>` : `<div class="relative h-0 w-full bg-gradient-to-br from-zinc-800 to-zinc-900 pb-[150%]"><div class="absolute top-0 right-0 bottom-0 left-0 flex items-center justify-center p-2 text-center text-sm text-zinc-400">${esc(i.title)}</div></div>`}
              <div class="truncate px-3 pb-3 pt-2 text-[15px] font-semibold leading-tight">${esc(i.title)}</div>
            </div>`).join("") || '<p class="tv-empty p-14 text-2xl text-zinc-400">Пусто</p>'}
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

const metaChips = (i) => [
  i.year ? `<span class="rounded-full bg-zinc-800/90 px-3 py-1 text-base font-medium text-zinc-300">${i.year}</span>` : "",
  i.rating ? `<span class="flex items-center rounded-full bg-amber-400/10 px-3 py-1 text-base font-semibold text-amber-400 ring-1 ring-amber-400/20">${ICONS.star("mr-1.5 h-4 w-4")}${Number(i.rating).toFixed(1)}</span>` : "",
  i.season ? `<span class="rounded-full bg-violet-500/10 px-3 py-1 text-base font-medium text-violet-300 ring-1 ring-violet-500/20">S${i.season}${i.episode ? "E" + i.episode : ""}</span>` : ""
].filter(Boolean).join("");

function updateInfo(i) {
  const el = document.getElementById("grid-info");
  if (!el || !i) return;
  const bg = backdrop(i.backdrop) || poster(i.poster);
  el.innerHTML = `
    <div class="h-[clamp(140px,38vh,320px)] w-full flex-none rounded-2xl bg-zinc-800 bg-cover bg-center shadow-2xl shadow-black/50 ring-1 ring-white/10" style="${bg ? `background-image:url('${bg}')` : ""}"></div>
    <div class="mt-1 text-3xl font-bold leading-tight tracking-tight">${esc(i.title)}</div>
    <div class="flex flex-wrap space-x-2">${metaChips(i)}</div>
    <div class="line-clamp-6 text-[17px] leading-relaxed text-zinc-300">${esc(i.overview || "Нет описания")}</div>
    ${i.cast && i.cast.length ? `<div class="text-[15px] text-zinc-500">${esc(i.cast.slice(0, 5).join(", "))}</div>` : ""}`;
}

/* ---------- Деталь фильма ---------- */
function renderDetail() {
  const i = state.current;
  if (!i) { state.screen = "grid"; return render(); }
  const bg = backdrop(i.backdrop) || poster(i.poster);
  app.innerHTML = `
    <div class="relative h-screen overflow-hidden">
      <div class="absolute top-0 right-0 bottom-0 left-0 bg-black bg-cover bg-center brightness-[.38] saturate-[1.1]" style="${bg ? `background-image:url('${bg}')` : ""}"></div>
      <div class="absolute top-0 right-0 bottom-0 left-0 bg-gradient-to-r from-zinc-950/95 via-zinc-950/65 to-zinc-950/30"></div>
      <div class="absolute right-0 bottom-0 left-0 h-40 bg-gradient-to-t from-zinc-950/90 to-transparent"></div>
      <div class="relative flex h-full max-w-[1100px] flex-col space-y-[clamp(8px,1.8vh,16px)] px-16 py-[clamp(16px,4vh,48px)]">
        <button class="flex cursor-pointer items-center self-start rounded-xl border border-white/10 bg-white/5 py-2 pl-3 pr-6 text-[clamp(15px,2.4vh,18px)] font-semibold outline-none backdrop-blur transition duration-150 focus:scale-105 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/30" id="detail-back">${ICONS.back("mr-1.5 h-5 w-5")} Назад</button>
        <div class="mt-1 text-[clamp(28px,7vh,48px)] font-extrabold tracking-tight drop-shadow-lg">${esc(i.title)}</div>
        <div class="flex flex-wrap space-x-2">${metaChips(i)}</div>
        <div class="line-clamp-5 max-w-[780px] text-[clamp(15px,2.7vh,20px)] leading-relaxed text-zinc-200 drop-shadow">${esc(i.overview || "Нет описания")}</div>
        ${i.cast && i.cast.length ? `<div class="max-w-[780px] text-[clamp(13px,2.2vh,16px)] text-zinc-400">В ролях: ${esc(i.cast.join(", "))}</div>` : ""}
        <div class="mt-1 flex space-x-4 overflow-hidden">${(i.backdrops || []).slice(0, 5).map((p) => `<img class="h-[clamp(80px,20vh,150px)] flex-none rounded-xl shadow-xl shadow-black/40 ring-1 ring-white/10" src="${shot(p)}" alt="" />`).join("")}</div>
        <button class="!mt-auto flex cursor-pointer items-center self-start rounded-2xl bg-gradient-to-r from-violet-600 to-indigo-600 px-[clamp(28px,5vw,48px)] py-[clamp(10px,2.2vh,16px)] text-[clamp(18px,3.2vh,24px)] font-bold text-white shadow-xl shadow-violet-600/40 outline-none transition focus:scale-[1.04] focus:ring-4 focus:ring-violet-400/50" id="detail-play" data-id="${esc(i.id)}">${ICONS.play("mr-3 h-[1.2em] w-[1.2em]")} Смотреть</button>
      </div>
    </div>`;
  document.getElementById("detail-back").addEventListener("click", back);
  const playBtn = document.getElementById("detail-play");
  playBtn.addEventListener("click", () => play(i.id));
  playBtn.focus(); // фокус сразу на Play
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

  if (state.screen === "grid") {
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
    if (["ArrowUp", "ArrowLeft"].includes(e.key)) { e.preventDefault(); document.getElementById("detail-back")?.focus(); }
    else if (["ArrowDown", "ArrowRight"].includes(e.key)) { e.preventDefault(); document.getElementById("detail-play")?.focus(); }
    else if (e.key === "Enter" || e.key === " " || e.key === "MediaPlayPause") { e.preventDefault(); cur?.click(); }
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

window.addEventListener("resize", () => { if (state.screen === "grid") computeCardWidth(); });
load();
