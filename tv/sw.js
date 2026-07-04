// Минимальный service worker: нужен, чтобы TV-страница считалась устанавливаемой PWA
// (ярлык на рабочем столе + запуск в отдельном окне). Кэшируем оболочку для оффлайна;
// сеть — в приоритете, чтобы данные (/api/*, /stream) всегда были свежими.
const CACHE = "mc-tv-v37";
const SHELL = ["/", "/index.html", "/tailwind.js", "/app.js", "/icon.svg", "/icons/icon-192.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).catch(() => {}));
  self.skipWaiting();
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  // Никогда не кэшируем API и стримы — только оболочку.
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/stream") || url.pathname.startsWith("/img/") || url.pathname.startsWith("/thumb")) return;
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request).then((r) => r || caches.match("/"))));
});
