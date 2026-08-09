// rutracker relay: the node performs HTTP requests that the SERVER dictates.
//
// Why the node at all, when rutracker logic lives on the server: since 2026-08 the tracker sits
// behind a Cloudflare managed challenge. Only a real browser clears it, and the resulting
// cf_clearance cookie is bound to the exit IP *and* the User-Agent — a Cloud Function calling
// rutracker from a GCP address gets 403 even while holding a valid cookie (verified 2026-08-09).
// So the requests themselves must leave from the node's home IP.
//
// The node stays deliberately dumb: it knows nothing about login forms, categories, parsing or
// .torrent files. It takes {url, method, body, cookie} from Firestore, replays it with the
// clearance cookie + matching UA, and returns the raw response. All rutracker logic remains in
// dashboard/functions/rutracker.js.
//
// The clearance itself is harvested by the TV app's ClearanceActivity (WebView + CookieManager),
// which posts it back to /api/rt-clearance on the local server.
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const { execFile } = require("child_process");
const {
  collection, doc, onSnapshot, updateDoc, setDoc, serverTimestamp
} = require("firebase/firestore");
const tvapp = require("./tvapp");

const TV_PKG = "com.mediacenter.tv";
const CACHE_FILE = path.join(__dirname, "..", "cache", "rt-clearance.json");
const HARVEST_TIMEOUT_MS = 150_000;
const REQUEST_TTL_MS = 5 * 60 * 1000;   // older requests are leftovers, not work
const MAX_BODY_BYTES = 20 * 1024 * 1024;
const PART_CHARS = 700_000;             // base64 per document, safely under Firestore's 1 MB cap

let clearance = null;      // { cookie, ua, at }
let harvestWaiter = null;  // { resolve, reject, timer } while a harvest is in flight
let relayCtx = null;       // set once the watcher starts, so we can publish relay state

function loadClearance() {
  if (clearance) return clearance;
  try { clearance = JSON.parse(fs.readFileSync(CACHE_FILE, "utf8")); } catch (_) { clearance = null; }
  return clearance;
}

function saveClearance(c) {
  clearance = c;
  try {
    fs.mkdirSync(path.dirname(CACHE_FILE), { recursive: true });
    fs.writeFileSync(CACHE_FILE, JSON.stringify(c));
  } catch (e) { console.error("rt: не сохранил cf_clearance:", e.message); }
  publishRelayState();
}

// Состояние релея — серверу (кого просить) и дашборду (бейдж). Годной нода считается, если она
// может ДОБЫТЬ куку (установлено TV-приложение) или у неё уже есть живая: без приложения нода
// доработает на текущей куке, но обновить её не сможет.
function publishRelayState() {
  if (!relayCtx) return;
  const ctx = relayCtx;
  tvapp.status((st) => {
    const c = clearance;
    const canHarvest = !!(st && st.installed);
    setDoc(doc(ctx.db, "devices", ctx.config.device.id), {
      rtRelay: {
        ok: canHarvest || !!c,
        canHarvest,
        clearanceAt: c ? c.at : null,
        ua: c ? c.ua : null
      }
    }, { merge: true }).catch(() => {});
  });
}

// Called by the local server when ClearanceActivity reports back.
function submitClearance({ cookie, ua, error }) {
  if (!harvestWaiter) {
    // Unsolicited (e.g. activity launched by hand) — still worth keeping.
    if (cookie && ua) saveClearance({ cookie, ua, at: Date.now() });
    return;
  }
  const w = harvestWaiter;
  harvestWaiter = null;
  clearTimeout(w.timer);
  if (error || !cookie || !ua) return w.reject(new Error(error || "нода не добыла cf_clearance"));
  saveClearance({ cookie, ua, at: Date.now() });
  w.resolve(clearance);
}

// Ask the TV app to open rutracker in a WebView and hand us the cookie it earns.
function harvest(ctx, { fresh = false } = {}) {
  if (harvestWaiter) return harvestWaiter.promise;
  const port = ctx.config.localPort || 8088;
  const args = ["start", "-n", `${TV_PKG}/.ClearanceActivity`, "--ei", "port", String(port)];
  if (fresh) args.push("--ez", "fresh", "true");
  console.log("rt: прошу TV-приложение добыть cf_clearance…");

  const promise = new Promise((resolve, reject) => {
    harvestWaiter = { resolve, reject, promise: null };
    harvestWaiter.timer = setTimeout(() => {
      harvestWaiter = null;
      reject(new Error("нода не добыла cf_clearance за " + HARVEST_TIMEOUT_MS / 1000 + "с"));
    }, HARVEST_TIMEOUT_MS);
    execFile("am", args, { timeout: 15_000 }, (err) => {
      if (!err) return;
      const w = harvestWaiter;
      harvestWaiter = null;
      if (w) { clearTimeout(w.timer); w.reject(new Error("не запустилось TV-приложение (" + TV_PKG + "): " + err.message)); }
    });
  });
  if (harvestWaiter) harvestWaiter.promise = promise;
  return promise;
}

// Only cf_clearance comes from the browser; forum cookies (bb_session…) are the server's business.
function cookieHeader(serverCookie) {
  const cf = String((loadClearance() || {}).cookie || "")
    .split(";").map((c) => c.trim()).filter((c) => c.startsWith("cf_clearance="))[0];
  return [serverCookie, cf].filter(Boolean).join("; ");
}

const isChallenge = (res, body) =>
  res.status === 403 && (res.headers.get("cf-mitigated") === "challenge" || /Just a moment/i.test(body.slice(0, 400)));

// One request as dictated by the server; retries once with a fresh clearance if Cloudflare
// challenges us (the cookie expires, and the home IP changes now and then).
async function perform(ctx, task) {
  if (!loadClearance()) await harvest(ctx, { fresh: false });

  const run = async () => {
    const c = loadClearance();
    if (!c) throw new Error("нет cf_clearance");
    const headers = {
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.8",
      ...(task.headers || {}),
      // UA and cookie stay ours no matter what the server sent: the clearance is bound to both
      "User-Agent": c.ua,
      Cookie: cookieHeader(task.cookie)
    };
    const res = await fetch(task.url, {
      method: task.method || "GET",
      body: task.bodyB64 ? Buffer.from(task.bodyB64, "base64") : null,
      headers,
      redirect: "manual"
    });
    const buf = Buffer.from(await res.arrayBuffer());
    return { res, buf };
  };

  let { res, buf } = await run();
  if (isChallenge(res, buf.toString("latin1"))) {
    console.log("rt: Cloudflare показал проверку — обновляю cf_clearance");
    await harvest(ctx, { fresh: true });
    ({ res, buf } = await run());
    if (isChallenge(res, buf.toString("latin1"))) throw new Error("Cloudflare не пропустил даже со свежей cf_clearance");
  }
  if (buf.length > MAX_BODY_BYTES) throw new Error("ответ rutracker слишком большой: " + buf.length + " байт");
  return {
    httpStatus: res.status,
    location: res.headers.get("location") || null,
    setCookie: res.headers.getSetCookie ? res.headers.getSetCookie() : [],
    bodyB64: zlib.gzipSync(buf).toString("base64")   // HTML shrinks ~10x, .torrent barely — hence chunking
  };
}

// Body goes inline when it fits, otherwise into a parts/ subcollection the server reassembles.
async function writeResult(db, id, result) {
  const ref = doc(db, "rtRequests", id);
  const { bodyB64, ...meta } = result;
  if (bodyB64.length <= PART_CHARS) {
    return updateDoc(ref, { ...meta, bodyB64, parts: 0, status: "done", updatedAt: serverTimestamp() });
  }
  const chunks = [];
  for (let i = 0; i < bodyB64.length; i += PART_CHARS) chunks.push(bodyB64.slice(i, i + PART_CHARS));
  for (let i = 0; i < chunks.length; i++) {
    await setDoc(doc(db, "rtRequests", id, "parts", String(i)), { b: chunks[i] });
  }
  return updateDoc(ref, { ...meta, bodyB64: null, parts: chunks.length, status: "done", updatedAt: serverTimestamp() });
}

function watchRtRequests(ctx) {
  const { db, config } = ctx;
  const myId = config.device.id;
  const running = new Set();

  const unsub = onSnapshot(collection(db, "rtRequests"), (snap) => {
    snap.docChanges().forEach((ch) => {
      if (ch.type === "removed") return;
      const id = ch.doc.id;
      const t = ch.doc.data();
      if (t.status !== "pending" || t.device !== myId || running.has(id)) return;
      // A stale request means the server already gave up waiting; doing it would only spend traffic.
      const createdAt = t.createdAtMs || 0;
      if (createdAt && Date.now() - createdAt > REQUEST_TTL_MS) return;
      running.add(id);
      perform(ctx, t)
        .then((result) => writeResult(db, id, result))
        .catch((e) => {
          console.error("rt:", e.message);
          return updateDoc(doc(db, "rtRequests", id), {
            status: "error", error: e.message, updatedAt: serverTimestamp()
          }).catch(() => {});
        })
        .finally(() => running.delete(id));
    });
  }, (e) => console.error("rt: подписка:", e.message));

  // Advertise the relay so the server knows which node can reach rutracker.
  relayCtx = ctx;
  loadClearance();
  publishRelayState();

  return () => { relayCtx = null; unsub(); };
}

module.exports = { watchRtRequests, submitClearance, loadClearance, harvest };
