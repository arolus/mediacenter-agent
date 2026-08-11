// rutracker relay: executes HTTP requests the SERVER dictates (rtRequests/{id}), replaying
// them from the home IP with the cf_clearance cookie. All tracker logic stays on the server.
// Harvesting the cookie is easier here than in the Node agent: ClearanceActivity lives in
// THIS app, and CookieManager is per-process — no localhost POST hop needed, but we keep the
// /api/rt-clearance endpoint so the flow (and manual runs) stay compatible.
package com.mediacenter.tv.agent

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class RtRelay(
    private val ctx: Context,
    private val db: FirebaseFirestore,
    private val config: Config,
    private val scope: CoroutineScope
) {
    private val cacheFile = File(ctx.filesDir, "rt-clearance.json")
    @Volatile private var clearance: JSONObject? = null
    @Volatile private var harvestWaiter: CompletableDeferred<JSONObject>? = null
    private val running = HashSet<String>()
    private var sub: ListenerRegistration? = null

    companion object {
        private const val REQUEST_TTL_MS = 5 * 60 * 1000L
        private const val PART_CHARS = 700_000
        private const val HARVEST_TIMEOUT_MS = 150_000L
        @Volatile var instance: RtRelay? = null

        // Кука приходит из ClearanceActivity — она в ЭТОМ же процессе, так что ходить за ней
        // по HTTP незачем (и ненадёжно: сервер мог ещё не подняться). Если релей уже работает,
        // отдаём ему; иначе просто кладём на диск — он подхватит при старте.
        @JvmStatic
        fun deliverClearance(ctx: Context, cookie: String?, ua: String?, error: String?) {
            val r = instance
            if (r != null) { r.submitClearance(cookie, ua, error); return }
            if (error != null || cookie == null || ua == null) {
                Log.e("rt: WebView не добыл куку: ${error ?: "пусто"}")
                return
            }
            val c = JSONObject().put("cookie", cookie).put("ua", ua).put("at", System.currentTimeMillis())
            try {
                File(ctx.filesDir, "rt-clearance.json").writeText(c.toString())
                Log.i("rt: cf_clearance сохранена до старта релея")
            } catch (e: Exception) { Log.e("rt: не сохранил куку: ${e.message}") }
        }
    }

    fun start() {
        instance = this
        loadClearance()
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
                        writeResult(id, perform(t))
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

    private fun loadClearance(): JSONObject? {
        clearance?.let { return it }
        clearance = try { JSONObject(cacheFile.readText()) } catch (_: Exception) { null }
        return clearance
    }

    // Called from the HTTP server when ClearanceActivity posts the cookie.
    fun submitClearance(cookie: String?, ua: String?, error: String?) {
        val w = harvestWaiter
        if (error == null && cookie != null && ua != null) {
            val c = JSONObject().put("cookie", cookie).put("ua", ua).put("at", System.currentTimeMillis())
            clearance = c
            try { cacheFile.writeText(c.toString()) } catch (_: Exception) {}
            publishState()
            Log.i("rt: cf_clearance сохранена (ua=${ua.take(40)})")
            w?.complete(c)
        } else {
            Log.e("rt: WebView не добыл куку: ${error ?: "пусто"}")
            w?.completeExceptionally(Exception(error ?: "нода не добыла cf_clearance"))
        }
        harvestWaiter = null
    }

    private fun publishState() {
        val c = clearance
        db.collection("devices").document(config.deviceId).set(
            mapOf("rtRelay" to mapOf(
                "ok" to true,               // the harvester is always present: it is this very app
                "canHarvest" to true,
                "clearanceAt" to (c?.optLong("at") ?: null),
                "ua" to c?.optString("ua")
            )), SetOptions.merge())
    }

    private suspend fun harvest(fresh: Boolean): JSONObject {
        harvestWaiter?.let { return it.await() }
        val waiter = CompletableDeferred<JSONObject>()
        harvestWaiter = waiter
        val i = Intent(ctx, com.mediacenter.tv.ClearanceActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra("port", config.localPort)
        if (fresh) i.putExtra("fresh", true)
        try { ctx.startActivity(i) } catch (e: Exception) {
            harvestWaiter = null
            throw Exception("не открылась ClearanceActivity: ${e.message}")
        }
        return withTimeout(HARVEST_TIMEOUT_MS) { waiter.await() }
    }

    private fun cookieHeader(serverCookie: String?): String {
        val cf = (loadClearance()?.optString("cookie") ?: "")
            .split(";").map { it.trim() }.firstOrNull { it.startsWith("cf_clearance=") }
        return listOfNotNull(serverCookie?.ifEmpty { null }, cf).joinToString("; ")
    }

    private fun isChallenge(r: FetchResult): Boolean {
        if (r.status != 403) return false
        if (r.headers["cf-mitigated"]?.firstOrNull() == "challenge") return true
        val head = String(r.body.copyOfRange(0, minOf(400, r.body.size)), Charsets.ISO_8859_1)
        return head.contains("Just a moment", ignoreCase = true)
    }

    private suspend fun perform(task: Map<String, Any?>): Map<String, Any?> {
        if (loadClearance() == null) harvest(fresh = false)
        suspend fun run(): FetchResult {
            val c = loadClearance() ?: throw Exception("нет cf_clearance")
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
            harvest(fresh = true)
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
