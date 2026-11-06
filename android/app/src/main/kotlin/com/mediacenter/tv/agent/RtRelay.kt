// rutracker relay: executes HTTP requests the SERVER dictates (rtRequests/{id}), replaying
// them from the home IP with the cf_clearance cookie. All tracker logic stays on the server.
// Harvesting the cookie is easier here than in the Node agent: ClearanceActivity lives in
// THIS app, and CookieManager is per-process — no localhost POST hop needed, but we keep the
// /api/rt-clearance endpoint so the flow (and manual runs) stay compatible.
// The same Cloudflare-aware transport serves standalone search sources (fetch()): clearance is
// kept per site origin; the cloud relay uses the default one.
package com.mediacenter.tv.agent

import android.content.Context
import android.content.Intent
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import android.content.res.Configuration
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class RtRelay(
    private val ctx: Context,
    private val db: Db,
    private val config: Config,
    private val scope: CoroutineScope
) {
    private val clearances = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()   // origin -> {cookie, ua, at}
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val harvestLock = kotlinx.coroutines.sync.Mutex()   // one WebView harvest at a time
    @Volatile private var harvestOrigin: String = DEFAULT_ORIGIN
    @Volatile private var harvestWaiter: CompletableDeferred<JSONObject>? = null
    @Volatile private var harvestStarted: CompletableDeferred<Unit>? = null
    private val running = HashSet<String>()
    private var sub: ListenerRegistration? = null

    companion object {
        private const val REQUEST_TTL_MS = 5 * 60 * 1000L
        private const val PART_CHARS = 700_000
        private const val HARVEST_TIMEOUT_MS = 150_000L
        // Non-interactive challenge clears in ~10s, tapped Turnstile in ~30s; the server stops
        // waiting at 200s and the activity fallback still needs its share.
        private const val HEADLESS_TIMEOUT_MS = 80_000L
        // Android 12+ silently drops an activity start from a background service (BAL): the
        // WebView never opens and the harvest would just time out. If ClearanceActivity has not
        // reported onCreate within this window, fail fast so the server can pick another node.
        private const val ACTIVITY_START_MS = 8_000L
        @Volatile var instance: RtRelay? = null
        const val DEFAULT_ORIGIN = "https://rutracker.org"
        const val DEFAULT_PROBE = "$DEFAULT_ORIGIN/forum/tracker.php"

        fun originOf(url: String): String = java.net.URL(url).let { "${it.protocol}://${it.host}" }

        // The default origin keeps its historical file name; other sites get their own.
        private fun cacheFile(ctx: Context, origin: String): File =
            if (origin == DEFAULT_ORIGIN) File(ctx.filesDir, "rt-clearance.json")
            else File(ctx.filesDir, "cf-" + origin.substringAfter("://").replace(Regex("[^A-Za-z0-9.-]"), "_") + ".json")

        // ClearanceActivity.onCreate: proof that the start was not blocked.
        @JvmStatic
        fun harvestActivityStarted() { instance?.harvestStarted?.complete(Unit) }

        // Кука приходит из ClearanceActivity — она в ЭТОМ же процессе, так что ходить за ней
        // по HTTP незачем (и ненадёжно: сервер мог ещё не подняться). Если релей уже работает,
        // отдаём ему; иначе просто кладём на диск — он подхватит при старте.
        @JvmStatic
        fun deliverClearance(ctx: Context, cookie: String?, ua: String?, error: String?) =
            deliverClearance(ctx, cookie, ua, error, DEFAULT_ORIGIN)

        @JvmStatic
        fun deliverClearance(ctx: Context, cookie: String?, ua: String?, error: String?, origin: String) {
            val r = instance
            if (r != null) { r.submitClearance(cookie, ua, error, origin); return }
            if (error != null || cookie == null || ua == null) {
                Log.e("rt: WebView не добыл куку: ${error ?: "пусто"}")
                return
            }
            val c = JSONObject().put("cookie", cookie).put("ua", ua).put("at", System.currentTimeMillis())
            try {
                cacheFile(ctx, origin).writeText(c.toString())
                Log.i("rt: cf_clearance сохранена до старта релея")
            } catch (e: Exception) { Log.e("rt: не сохранил куку: ${e.message}") }
        }
    }

    fun start() {
        instance = this
        loadClearance(DEFAULT_ORIGIN)
        publishState()
        sub = db.collection("rtRequests").addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                if (ch.type == DocumentChange.Type.REMOVED) return@forEach
                val id = ch.document.id
                val t = ch.document.data
                if (t["status"] != "pending" || t["device"] != config.deviceId || id in running) return@forEach
                val createdAt = (t["createdAtMs"] as? Number)?.toLong() ?: 0
                if (createdAt > 0 && System.currentTimeMillis() - createdAt > REQUEST_TTL_MS) return@forEach
                running.add(id)
                scope.launch {
                    try {
                        writeResult(id, perform(id, t))
                    } catch (e: Exception) {
                        Log.e("rt: ${e.message}")
                        try {
                            db.collection("rtRequests").document(id).update(
                                mapOf("status" to "error", "error" to (e.message ?: "?"),
                                    "updatedAt" to FieldValue.serverTimestamp())).await()
                        } catch (_: Exception) {}
                    } finally { running.remove(id) }
                }
            }
        }
    }

    fun stop() { sub?.remove(); instance = null }

    private fun loadClearance(origin: String): JSONObject? {
        clearances[origin]?.let { return it }
        val c = try { JSONObject(cacheFile(ctx, origin).readText()) } catch (_: Exception) { null }
        if (c != null) clearances[origin] = c
        return c
    }

    // Called from the HTTP server when ClearanceActivity posts the cookie.
    fun submitClearance(cookie: String?, ua: String?, error: String?, origin: String = harvestOrigin) {
        val w = harvestWaiter
        if (error == null && cookie != null && ua != null) {
            val c = JSONObject().put("cookie", cookie).put("ua", ua).put("at", System.currentTimeMillis())
            clearances[origin] = c
            try { cacheFile(ctx, origin).writeText(c.toString()) } catch (_: Exception) {}
            if (origin == DEFAULT_ORIGIN) publishState()
            Log.i("rt: cf_clearance сохранена (ua=${ua.take(40)})")
            w?.complete(c)
        } else {
            Log.e("rt: WebView не добыл куку: ${error ?: "пусто"}")
            w?.completeExceptionally(Exception(error ?: "нода не добыла cf_clearance"))
        }
        harvestWaiter = null
    }

    // "Display over other apps" is what lets a background service open ClearanceActivity on
    // Android 12+; without it the harvest only works while our own UI is on screen (TV nodes).
    private fun canDrawOverlays(): Boolean =
        try { Settings.canDrawOverlays(ctx) } catch (_: Exception) { false }

    private fun isTv(): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    private fun publishState() {
        val c = clearances[DEFAULT_ORIGIN]
        db.collection("devices").document(config.deviceId).set(
            mapOf("rtRelay" to mapOf(
                "ok" to true,               // the harvester is always present: it is this very app
                "canHarvest" to true,
                // Server ranks nodes by this: phones clear the challenge headlessly in the
                // background, TVs tap Turnstile on their own activity while the UI is on screen.
                "autoHarvest" to true,
                "overlay" to canDrawOverlays(),
                "tv" to isTv(),
                "clearanceAt" to (c?.optLong("at") ?: null),
                "ua" to c?.optString("ua")
            )), SetOptions.merge())
    }

    // Concurrent requests for the same site share one harvest; different sites queue up (there
    // is one ClearanceActivity and one headless slot).
    private suspend fun harvest(fresh: Boolean, origin: String, probe: String): JSONObject {
        inflight[origin]?.let { return it.await() }
        val d = CompletableDeferred<JSONObject>()
        inflight[origin] = d
        try {
            val c = harvestLock.withLock {
                harvestOrigin = origin
                harvestOnce(fresh, origin, probe)
            }
            d.complete(c)
            return c
        } catch (e: Exception) {
            d.completeExceptionally(e)
            throw e
        } finally { inflight.remove(origin) }
    }

    private suspend fun harvestOnce(fresh: Boolean, origin: String, probe: String): JSONObject {
        // Phones: headless WebView in this very process — works with any app on screen and with
        // the screen off. TVs: the visible activity (its Turnstile needs a real, laid-out view).
        if (!isTv()) {
            val waiter = CompletableDeferred<JSONObject>()
            harvestWaiter = waiter
            HeadlessClearance(ctx, probe).harvest(fresh, HEADLESS_TIMEOUT_MS) { cookie, ua, error ->
                submitClearance(cookie, ua, error, origin)
            }
            try {
                return withTimeout(HEADLESS_TIMEOUT_MS + 5_000) { waiter.await() }
            } catch (e: Exception) {
                Log.e("rt: headless harvest failed (${e.message}), trying the activity")
                harvestWaiter = null
            }
        }
        val waiter = CompletableDeferred<JSONObject>()
        val started = CompletableDeferred<Unit>()
        harvestWaiter = waiter
        harvestStarted = started
        val i = Intent(ctx, com.mediacenter.tv.ClearanceActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra("port", config.localPort)
        i.putExtra("probe", probe)
        if (fresh) i.putExtra("fresh", true)
        try { ctx.startActivity(i) } catch (e: Exception) {
            harvestWaiter = null; harvestStarted = null
            throw Exception("не открылась ClearanceActivity: ${e.message}")
        }
        if (withTimeoutOrNull(ACTIVITY_START_MS) { started.await() } == null) {
            harvestWaiter = null; harvestStarted = null
            throw Exception("нода не может открыть проверку Cloudflare: приложение не на экране" +
                (if (canDrawOverlays()) "" else " (нужно право «поверх других приложений»)"))
        }
        try {
            return withTimeout(HARVEST_TIMEOUT_MS) { waiter.await() }
        } finally { harvestStarted = null }
    }

    private fun cookieHeader(serverCookie: String?, origin: String = DEFAULT_ORIGIN): String {
        val cf = (loadClearance(origin)?.optString("cookie") ?: "")
            .split(";").map { it.trim() }.firstOrNull { it.startsWith("cf_clearance=") }
        return listOfNotNull(serverCookie?.ifEmpty { null }, cf).joinToString("; ")
    }

    private fun isChallenge(r: FetchResult): Boolean {
        if (r.status != 403) return false
        if (r.headers["cf-mitigated"]?.firstOrNull() == "challenge") return true
        val head = String(r.body.copyOfRange(0, minOf(400, r.body.size)), Charsets.ISO_8859_1)
        return head.contains("Just a moment", ignoreCase = true)
    }

    // "challenge" is an interim status: the server keeps waiting past its usual deadline while
    // the WebView (and, on a TV, the Turnstile tap) does its work instead of giving up at 90s.
    private suspend fun markChallenge(id: String) {
        try {
            db.collection("rtRequests").document(id).update(
                mapOf("status" to "challenge", "updatedAt" to FieldValue.serverTimestamp())).await()
        } catch (_: Exception) {}
    }

    // Standalone search sources: one HTTP request to any site, from this device, with Cloudflare
    // handled transparently. Unlike the cloud relay, nothing is harvested up front — most sites
    // never challenge; on a challenge we harvest for that site's origin and retry once.
    suspend fun fetch(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
                      body: ByteArray? = null, cookie: String? = null, probe: String? = null,
                      followRedirects: Boolean = false): FetchResult {
        val origin = originOf(url)
        fun run(): FetchResult {
            val h = HashMap<String, String>()
            h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            h["Accept-Language"] = "ru-RU,ru;q=0.9,en;q=0.8"
            h.putAll(headers)
            val c = loadClearance(origin)
            c?.optString("ua")?.ifEmpty { null }?.let { h["User-Agent"] = it }
            val ck = cookieHeader(cookie, origin)
            if (ck.isNotEmpty()) h["Cookie"] = ck
            return httpFetch(url, method, h, body, followRedirects = followRedirects)
        }
        fun attempt(): FetchResult {
            val r = try { run() } catch (e: java.io.IOException) { throw unreachable(origin, e) }
            if (isIspStub(r, origin)) throw blocked(origin)
            return r
        }
        var r = attempt()
        if (isChallenge(r)) {
            Log.i("cf: challenge on $origin — harvesting clearance")
            harvest(fresh = loadClearance(origin) != null, origin = origin, probe = probe ?: "$origin/")
            r = attempt()
            if (isChallenge(r)) throw Exception("Cloudflare не пропустил даже со свежей cf_clearance")
        }
        return r
    }

    // The request never reached the site. Either there is no internet at all, or this network
    // blocks the site (an ISP block shows up as a timeout, reset or failed TLS handshake) — a
    // neutral reachability probe tells the two apart, so the user gets the right advice.
    private fun unreachable(origin: String, e: Exception): Exception {
        val online = try {
            httpFetch("https://www.google.com/generate_204", timeoutMs = 8000).status in 200..399
        } catch (_: Exception) { false }
        Log.e("cf: $origin unreachable (${e.javaClass.simpleName}: ${e.message}), internet ${if (online) "ok" else "down"}")
        return if (online) blocked(origin)
               else Exception("Нет связи с интернетом — проверьте подключение телевизора")
    }

    private fun blocked(origin: String) = Exception("Сайт ${origin.substringAfter("://")} недоступен из вашей сети — " +
        "похоже, его блокирует провайдер. Включите VPN на роутере или на телевизоре и попробуйте снова.")

    // Some ISPs answer for a blocked site themselves: a redirect to their warning page or a short
    // "access restricted" stub instead of the site.
    private fun isIspStub(r: FetchResult, origin: String): Boolean {
        val loc = r.headers["location"]?.firstOrNull()
        if (r.status in 300..399 && loc != null) {
            val host = try { java.net.URL(java.net.URL(origin), loc).host } catch (_: Exception) { "" }
            val own = java.net.URL(origin).host
            if (host.isNotEmpty() && !host.endsWith(own) && Regex("warning|block|zapret|rkn|stop|restrict", RegexOption.IGNORE_CASE).containsMatchIn(host + loc))
                return true
        }
        if (r.status in 200..299 && r.body.size in 1..20_000) {
            val text = (String(r.body, Charsets.UTF_8) + String(r.body, charset("windows-1251"))).lowercase()
            if (text.contains("роскомнадзор") || text.contains("единый реестр") || text.contains("доступ к ресурсу ограничен"))
                return true
        }
        return false
    }

    private suspend fun perform(id: String, task: Map<String, Any?>): Map<String, Any?> {
        if (loadClearance(DEFAULT_ORIGIN) == null) { markChallenge(id); harvest(false, DEFAULT_ORIGIN, DEFAULT_PROBE) }
        suspend fun run(): FetchResult {
            val c = loadClearance(DEFAULT_ORIGIN) ?: throw Exception("нет cf_clearance")
            val headers = HashMap<String, String>()
            headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            headers["Accept-Language"] = "ru-RU,ru;q=0.9,en;q=0.8"
            (task["headers"] as? Map<*, *>)?.forEach { (k, v) -> headers[k.toString()] = v.toString() }
            headers["User-Agent"] = c.optString("ua")
            headers["Cookie"] = cookieHeader(task["cookie"] as? String)
            val body = (task["bodyB64"] as? String)?.let { Base64.decode(it, Base64.DEFAULT) }
            return httpFetch(task["url"] as String, task["method"] as? String ?: "GET",
                headers, body, followRedirects = false)
        }
        var r = run()
        if (isChallenge(r)) {
            Log.i("rt: Cloudflare challenge — refreshing cf_clearance")
            markChallenge(id)
            harvest(true, DEFAULT_ORIGIN, DEFAULT_PROBE)
            r = run()
            if (isChallenge(r)) throw Exception("Cloudflare не пропустил даже со свежей cf_clearance")
        }
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(r.body) }
        return mapOf(
            "httpStatus" to r.status,
            "location" to r.headers["location"]?.firstOrNull(),
            "setCookie" to (r.headers["set-cookie"] ?: emptyList<String>()),
            "bodyB64" to Base64.encodeToString(gz.toByteArray(), Base64.NO_WRAP)
        )
    }

    private suspend fun writeResult(id: String, result: Map<String, Any?>) {
        val ref = db.collection("rtRequests").document(id)
        val bodyB64 = result["bodyB64"] as String
        val meta = result.filterKeys { it != "bodyB64" }
        if (bodyB64.length <= PART_CHARS) {
            ref.update(meta + mapOf("bodyB64" to bodyB64, "parts" to 0, "status" to "done",
                "updatedAt" to FieldValue.serverTimestamp())).await()
            return
        }
        val chunks = bodyB64.chunked(PART_CHARS)
        chunks.forEachIndexed { i, c ->
            ref.collection("parts").document(i.toString()).set(mapOf("b" to c)).await()
        }
        ref.update(meta + mapOf("bodyB64" to null, "parts" to chunks.size, "status" to "done",
            "updatedAt" to FieldValue.serverTimestamp())).await()
    }
}
