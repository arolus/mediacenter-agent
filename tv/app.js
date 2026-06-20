// TV-режим: браузинг локальной коллекции + запуск плеера. Навигация пультом (Flirc = клавиши).
const TMDB_IMG = "https://image.tmdb.org/t/p/w342";
const SECTIONS = [
  { type: "movie", title: "Фильмы" },
  { type: "cartoon", title: "Мультфильмы" },
  { type: "series", title: "Сериалы" }
];
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

async function init() {
  try {
    const dev = await (await fetch("/api/device")).json();
    document.getElementById("tv-device").textContent = dev.name || "";
  } catch (_) {}

  let items = [];
  try { items = await (await fetch("/api/library")).json(); }
  catch (_) { document.getElementById("tv-loading").textContent = "Не удалось загрузить коллекцию"; return; }

  const main = document.getElementById("tv-main");
  main.innerHTML = "";
  for (const sec of SECTIONS) {
    const list = items.filter((i) => i.type === sec.type)
      .sort((a, b) => (a.title || "").localeCompare(b.title || ""));
    if (!list.length) continue;
    const h = document.createElement("h2");
    h.className = "tv-section-title";
    h.textContent = `${sec.title} · ${list.length}`;
    const row = document.createElement("div");
    row.className = "tv-row";
    row.innerHTML = list.map(cardHtml).join("");
    main.append(h, row);
  }

  const cards = [...document.querySelectorAll(".tv-card")];
  if (!cards.length) { main.innerHTML = '<p class="tv-empty">Коллекция пуста. Добавь контент из дашборда.</p>'; return; }
  cards.forEach((c) => c.addEventListener("click", () => play(c.dataset.id)));
  cards[0].focus();
}

function cardHtml(i) {
  const poster = i.poster
    ? `<div class="tv-poster" style="background-image:url('${TMDB_IMG}${i.poster}')"></div>`
    : `<div class="tv-poster">${esc(i.title)}</div>`;
  const meta = [i.year, i.season ? `S${i.season}${i.episode ? "E" + i.episode : ""}` : null].filter(Boolean).join(" · ");
  return `<div class="tv-card" tabindex="0" data-id="${esc(i.id)}">
    ${poster}
    <div class="tv-card-body">
      <div class="tv-card-title">${esc(i.title)}</div>
      <div class="tv-card-meta">${esc(meta)}</div>
    </div>
  </div>`;
}

// ---- Навигация пультом ----
let fullscreenTried = false;
document.addEventListener("keydown", (e) => {
  const cur = document.activeElement;
  if (!cur || !cur.classList || !cur.classList.contains("tv-card")) {
    const first = document.querySelector(".tv-card");
    if (first) { first.focus(); e.preventDefault(); }
    return;
  }
  const dirs = { ArrowLeft: "left", ArrowRight: "right", ArrowUp: "up", ArrowDown: "down" };
  if (dirs[e.key]) {
    e.preventDefault();
    if (!fullscreenTried) { fullscreenTried = true; document.documentElement.requestFullscreen?.().catch(() => {}); }
    const next = nearest(cur, dirs[e.key]);
    if (next) { next.focus(); next.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" }); }
  } else if (e.key === "Enter" || e.key === " " || e.key === "MediaPlayPause") {
    e.preventDefault();
    play(cur.dataset.id);
  }
});

// Ближайшая карточка в заданном направлении (пространственная навигация).
function nearest(cur, dir) {
  const cards = [...document.querySelectorAll(".tv-card")];
  const cr = cur.getBoundingClientRect();
  const cx = cr.left + cr.width / 2, cy = cr.top + cr.height / 2;
  let best = null, bestScore = Infinity;
  for (const el of cards) {
    if (el === cur) continue;
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const dx = x - cx, dy = y - cy;
    let primary, secondary;
    if (dir === "left") { if (dx >= -2) continue; primary = -dx; secondary = Math.abs(dy); }
    else if (dir === "right") { if (dx <= 2) continue; primary = dx; secondary = Math.abs(dy); }
    else if (dir === "up") { if (dy >= -2) continue; primary = -dy; secondary = Math.abs(dx); }
    else { if (dy <= 2) continue; primary = dy; secondary = Math.abs(dx); }
    const score = primary + secondary * 2;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  return best;
}

async function play(id) {
  if (!id) return;
  showOverlay("▶ Запускаю плеер…");
  try {
    const r = await (await fetch("/api/play?id=" + encodeURIComponent(id))).json();
    showOverlay(r.ok ? "▶ Играет в плеере" : "⚠️ " + (r.error || "ошибка"));
  } catch (_) { showOverlay("⚠️ Не удалось запустить"); }
  setTimeout(hideOverlay, 2500);
}

function showOverlay(text) {
  document.getElementById("tv-overlay-text").textContent = text;
  document.getElementById("tv-overlay").classList.remove("hidden");
}
function hideOverlay() { document.getElementById("tv-overlay").classList.add("hidden"); }

init();
