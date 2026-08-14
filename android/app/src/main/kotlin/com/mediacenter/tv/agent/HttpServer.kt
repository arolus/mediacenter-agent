// The node's TV server: same endpoints and JSON shapes as the Node agent's lib/localserver.js,
// so agent/tv/app.js (shipped in APK assets) works unchanged. Built on NanoHTTPD.
//
// What differs from the Node version, on purpose:
//   - thumbnails and media info come from Android itself (MediaMetadataRetriever / MediaExtractor)
//     instead of ffmpeg — no external binaries in the APK;
//   - /transcode is gone: this device always plays through VLC, never in the WebView;
//   - the player is launched by the app (AgentService → MainActivity), never by a background
//     process — Android 12+ blocks background activity launches.
package com.mediacenter.tv.agent

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest

class HttpServer(
    private val ctx: Context,
    private val db: FirebaseFirestore,
    private val config: Config,
    private val scope: CoroutineScope,
    private val onPlay: (item: JSONObject, url: String, pkg: String, subtitles: String?, fromStart: Boolean) -> Unit,
    private val onOpenUrl: (url: String) -> Unit,
    private val onStorageChanged: () -> Unit
) : NanoHTTPD(config.localPort) {

    // ---- state mirrored from Firestore -------------------------------------------------------
    @Volatile var library: JSONArray = JSONArray()
    @Volatile private var deviceName: String = config.deviceName
    @Volatile private var kioskPin: String = ""
    @Volatile private var kioskMode: String = "normal"      // общий режим (config/tv)
    @Volatile private var deviceMode: String? = null        // режим этого устройства, если задан
    @Volatile private var downloadsView: JSONArray = JSONArray()
    @Volatile var lastFirebaseOk: Long = 0
    @Volatile var firebaseOk: Boolean = false

    private val subs = mutableListOf<ListenerRegistration>()
    private val sseClients = java.util.Collections.synchronizedList(mutableListOf<PipedOutputStream>())
    private val libCache = File(ctx.filesDir, "library.json")
    private val kioskCache = File(ctx.filesDir, "kiosk.json")
    private val imgDir = File(ctx.filesDir, "cache/img").apply { mkdirs() }
    private val personDir = File(ctx.filesDir, "cache/persons").apply { mkdirs() }
    private val movieDir = File(ctx.filesDir, "cache/movies").apply { mkdirs() }
    private val thumbDir = File(ctx.filesDir, "cache/thumbs").apply { mkdirs() }
    private val miDir = File(ctx.filesDir, "cache/mediainfo").apply { mkdirs() }

    fun startAll() {
        try { library = JSONArray(libCache.readText()) } catch (_: Exception) {}
        try {
            val c = JSONObject(kioskCache.readText())
            kioskPin = c.optString("pin"); kioskMode = c.optString("mode", "normal")
        } catch (_: Exception) {}

        val devRef = db.collection("devices").document(config.deviceId)
        subs.add(devRef.collection("library").addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val arr = JSONArray()
            snap.documents.forEach { d ->
                val o = JSONObject(d.data ?: emptyMap<String, Any>())
                o.put("id", d.id)
                arr.put(o)
            }
            library = arr
            try { libCache.writeText(arr.toString()) } catch (_: Exception) {}
            notifyClients()
        })
        subs.add(devRef.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            snap.getString("name")?.let { if (it != deviceName) { deviceName = it; notifyClients() } }
            // Режим у каждого устройства свой: приставка у ребёнка может быть детской, а
            // телевизор в гостиной — обычным. Пусто — берём общий из config/tv (совместимость).
            val own = snap.getString("mode")
            deviceMode = if (own == "kids" || own == "normal" || own == "admin") own else null
            notifyClients()
        })
        subs.add(db.collection("config").document("tv").addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            kioskPin = snap.getString("pin") ?: ""
            kioskMode = if (snap.getString("mode") == "kids") "kids" else "normal"
            try { kioskCache.writeText(JSONObject().put("pin", kioskPin).put("mode", kioskMode).toString()) } catch (_: Exception) {}
            notifyClients()
        })
        subs.add(db.collection("downloads").addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val arr = JSONArray()
            snap.documents.filter { it.getString("target") == config.deviceId }.forEach { d ->
                arr.put(JSONObject()
                    .put("tid", d.getString("tid"))
                    .put("title", d.getString("title") ?: "")
                    .put("year", d.get("year"))
                    .put("type", d.getString("type") ?: "movie")
                    .put("status", d.getString("status") ?: "")
                    .put("progress", d.getDouble("progress") ?: 0.0)
                    .put("speed", (d.get("speed") as? Number)?.toLong() ?: 0)
                    .put("error", d.getString("error")))
            }
            downloadsView = arr
            notifyClients()
        })
        start(SOCKET_READ_TIMEOUT, false)
        Log.i("TV server: http://0.0.0.0:${config.localPort}/")
    }

    fun stopAll() {
        subs.forEach { it.remove() }; subs.clear()
        synchronized(sseClients) { sseClients.forEach { runCatching { it.close() } }; sseClients.clear() }
        stop()
    }

    // Перезагрузить страницы на ноде — после обновления веб-части.
    fun reloadClients() {
        synchronized(sseClients) {
            val dead = mutableListOf<PipedOutputStream>()
            sseClients.forEach {
                try { it.write("data: reload\n\n".toByteArray()); it.flush() } catch (_: Exception) { dead.add(it) }
            }
            sseClients.removeAll(dead)
        }
    }

    private fun notifyClients() {
        synchronized(sseClients) {
            val dead = mutableListOf<PipedOutputStream>()
            sseClients.forEach {
                try { it.write("data: change\n\n".toByteArray()); it.flush() } catch (_: Exception) { dead.add(it) }
            }
            sseClients.removeAll(dead)
        }
    }

    private fun findItem(id: String?): JSONObject? {
        if (id.isNullOrEmpty()) return null
        for (i in 0 until library.length()) {
            val o = library.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o
        }
        return null
    }

    private fun inMediaDirs(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return Storage.scanDirs(ctx).values.flatten().any { path == it.path || path.startsWith(it.path + File.separator) }
    }

    private fun json(o: Any, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())

    private fun err(msg: String, status: Response.Status = Response.Status.INTERNAL_ERROR): Response =
        json(JSONObject().put("error", msg), status)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val q = session.parameters.mapValues { it.value.firstOrNull() ?: "" }
        return try {
            when {
                uri == "/api/library" -> json(libraryView())
                uri == "/api/device" -> json(JSONObject()
                    .put("name", deviceName).put("id", config.deviceId)
                    .put("version", appVersion()))
                uri == "/api/health" -> json(JSONObject()
                    .put("ok", true).put("firebase", firebaseOk)
                    .put("lastOkAgo", (System.currentTimeMillis() - lastFirebaseOk) / 1000)
                    .put("error", null as Any?))
                uri == "/api/events" -> sse()
                uri == "/api/kiosk" -> json(JSONObject()
                    .put("pinSet", kioskPin.isNotEmpty())
                    .put("mode", deviceMode ?: kioskMode))
                uri == "/api/kiosk-pin" -> if (isLocal(session)) json(JSONObject().put("pin", kioskPin))
                    else err("только с localhost", Response.Status.FORBIDDEN)
                uri == "/api/kiosk-exit" -> kioskExit(session)
                uri == "/api/rt-clearance" -> rtClearance(session)
                uri == "/api/play" -> play(q["id"], q["via"] == "app", q["from"] == "start")
                uri == "/api/watched" -> setWatched(q["id"], q["set"] != "0")
                uri == "/api/trailer" -> trailer(q["id"])
                uri == "/api/person" -> person(q["name"], q["refresh"] != null)
                uri == "/api/movie" -> movieInfo(q["tmdbId"], q["kind"])
                uri == "/api/position" ->
                    if (!isLocal(session)) err("только локально", Response.Status.FORBIDDEN)
                    else savePosition(q["id"], q["pos"]?.toLongOrNull(), q["dur"]?.toLongOrNull())
                uri == "/api/scan-status" -> json(JSONObject()
                    .put("running", Library.Progress.running)
                    .put("done", Library.Progress.done)
                    .put("total", Library.Progress.total)
                    .put("finishedAgo", if (Library.Progress.finishedAt > 0)
                        (System.currentTimeMillis() - Library.Progress.finishedAt) / 1000 else -1))
                uri == "/api/copy-plan" -> copyPlan(q["from"])
                uri == "/api/copy-status" -> json(Copier.status())
                uri == "/api/copy-stop" -> { Copier.stop(q["to"]); json(Copier.status()) }
                uri == "/api/usb-forget" -> { SafStore.forget(ctx, q["id"] ?: ""); json(JSONObject().put("ok", true)) }
                uri == "/api/copy" -> copyStart(session)
                // Проверить обновление интерфейса прямо сейчас, не дожидаясь десятиминутного
                // цикла — для кнопки в настройках и для проверки после выката.
                uri == "/api/web-update" -> {
                    val was = WebUpdater.localVersion(ctx)
                    val res = runCatching { WebUpdater.check(ctx) }
                    if (res.isSuccess) {
                        if (res.getOrDefault(false)) {
                            reloadClients()
                            ctx.sendBroadcast(android.content.Intent("com.mediacenter.tv.UI_UPDATED")
                                .setPackage(ctx.packageName))
                        }
                        json(JSONObject().put("updated", res.getOrDefault(false))
                            .put("was", was.ifEmpty { "вшитая" })
                            .put("now", WebUpdater.localVersion(ctx).ifEmpty { "вшитая" }))
                    } else err("обновление не удалось: ${res.exceptionOrNull()?.message}")
                }
                uri == "/api/verify" -> verifyVolume(q["from"] ?: "shared", q["to"] ?: "")
                uri == "/api/delete-item" -> deleteItem(q["id"], q["everywhere"] == "1")
                uri == "/api/home-screen" -> json(homeScreenView())
                // Хранилища: что нашли, что выбрано, сколько занято. POST — сохранить выбор.
                uri == "/api/storage" -> storage(session, q)
                uri == "/api/downloads" -> json(downloadsView)
                uri == "/api/search-torrents" -> searchTorrents(q)
                uri == "/api/download" -> startDownload(q)
                uri == "/api/player-status" -> json(JSONObject()
                    .put("installed", isPackageInstalled(config.playerPackage))
                    .put("package", config.playerPackage))
                uri == "/api/install-player" -> err("VLC ставится вручную из магазина или по adb", Response.Status.NOT_IMPLEMENTED)
                uri == "/api/app-status" -> json(JSONObject()
                    .put("installed", true).put("apkAvailable", false)
                    .put("versionName", appVersion()))
                uri == "/api/install-app" -> err("агент внутри приложения — отдельная установка не нужна", Response.Status.NOT_IMPLEMENTED)
                uri == "/api/ensure-landscape" -> json(JSONObject().put("ok", false))
                uri == "/api/mediainfo" -> mediaInfo(q["id"])
                uri == "/thumb" -> thumb(q["id"])
                uri.startsWith("/img/") -> imgProxy(uri)
                uri == "/stream" || uri.startsWith("/stream/") -> {
                    val id = if (uri == "/stream") q["id"] else uri.split("/").getOrNull(2)
                    stream(findItem(id), session)
                }
                uri == "/transcode" -> err("транскодинг не нужен: воспроизводит VLC", Response.Status.NOT_IMPLEMENTED)
                else -> static(uri)
            }
        } catch (e: Exception) {
            Log.e("http ${session.uri}: ${e.message}")
            err(e.message ?: "ошибка")
        }
    }

    private fun isLocal(session: IHTTPSession): Boolean {
        val ip = session.headers["http-client-ip"] ?: session.remoteIpAddress
        return ip == "127.0.0.1" || ip == "::1" || ip.startsWith("127.")
    }

    // Версия = имя + номер сборки. По ней TV-страница понимает, что агент обновился, и
    // перезагружает себя (иначе WebView крутил бы старый app.js): одного versionName мало —
    // он меняется редко, а APK пересобирается на каждую правку интерфейса.
    private fun appVersion(): String = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        "${p.versionName}.$code"
    } catch (_: Exception) { "app" }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    // ---- views ------------------------------------------------------------------------------
    private fun libraryView(): JSONArray {
        val out = JSONArray()
        val mountedDirs = Storage.activeVolumes(ctx).map { it.dir.absolutePath + File.separator }
        for (i in 0 until library.length()) {
            val it = library.optJSONObject(i) ?: continue
            // Фильмы с вынутой флешки остаются в базе (см. Library.sync), но показывать их на
            // телевизоре незачем — нажатие всё равно упрётся в отсутствующий файл. Метку ставит
            // сканирование, а оно доходит до дела через полминуты после старта; поэтому здесь же
            // смотрим на путь — иначе после включения телевизора без флешки успевает мелькнуть
            // каталог, которого нет.
            if (it.optBoolean("offline")) continue
            val path = it.optString("filePath")
            if (path.isNotEmpty() && mountedDirs.none { d -> path.startsWith(d) }) continue
            out.put(JSONObject()
                .put("id", it.opt("id"))
                .put("type", it.optString("type", "movie"))
                .put("title", it.optString("title").ifEmpty { it.optString("fileName") })
                .put("year", it.opt("year"))
                .put("poster", it.opt("poster"))
                .put("backdrop", it.opt("backdrop"))
                .put("backdrops", it.optJSONArray("backdrops") ?: JSONArray())
                .put("overview", it.optString("overview"))
                .put("rating", it.optDouble("rating", 0.0))
                .put("cast", it.optJSONArray("cast") ?: JSONArray())
                .put("tmdbId", it.opt("tmdbId"))
                .put("season", it.opt("season"))
                .put("episode", it.opt("episode"))
                .put("fileName", it.opt("fileName"))
                .put("watched", it.optBoolean("watched", false))
                .put("started", it.optBoolean("started", false))
                .put("position", it.optLong("position", 0))   // мс, отдал VLC при выходе
                .put("duration", it.optLong("duration", 0))
                .put("addedAt", it.optLong("addedAt", 0))
                .put("collection", it.opt("collection"))
                .put("castX", it.optJSONArray("castX") ?: JSONArray())
                .put("directorsX", it.optJSONArray("directorsX") ?: JSONArray())
                .put("tmdbTried", it.optBoolean("tmdbTried"))
                .put("keywords", it.optJSONArray("keywords") ?: JSONArray())
                .put("genres", it.optJSONArray("genres") ?: JSONArray())
                .put("director", it.optString("director"))
                .put("country", it.optString("country"))
                .put("studio", it.optString("studio"))
                .put("premiered", it.opt("premiered"))
                .put("votes", it.optInt("votes", 0))
                .put("tagline", it.optString("tagline"))
                .put("runtime", it.optInt("runtime", 0))
                .put("trailer", it.opt("trailer"))
                .put("budget", it.optLong("budget", 0))
                .put("revenue", it.optLong("revenue", 0))
                .put("imdbRating", it.optDouble("imdbRating", 0.0))
                .put("imdbVotes", it.optLong("imdbVotes", 0)))
        }
        return out
    }

    private fun homeScreenView(): JSONObject {
        val base = "http://${lanIp() ?: "127.0.0.1"}:${config.localPort}"
        val items = mutableListOf<JSONObject>()
        for (i in 0 until library.length()) {
            val it = library.optJSONObject(i) ?: continue
            val poster = it.optString("poster", "")
            items.add(JSONObject()
                .put("id", it.opt("id"))
                .put("title", it.optString("title").ifEmpty { it.optString("fileName") })
                .put("year", it.opt("year"))
                .put("type", it.optString("type", "movie"))
                .put("poster", if (poster.isEmpty()) null else "$base/img/w342${if (poster.startsWith("/")) "" else "/"}$poster")
                .put("description", it.optString("overview").take(300))
                .put("durationMs", it.optInt("runtime", 0) * 60000L)
                .put("started", it.optBoolean("started", false))
                .put("position", it.optLong("position", 0))   // мс, отдал VLC при выходе
                .put("duration", it.optLong("duration", 0))
                .put("watched", it.optBoolean("watched", false))
                .put("addedAt", it.optLong("addedAt", 0)))
        }
        val byDate = items.sortedByDescending { it.optLong("addedAt", 0) }
        val cont = JSONArray()
        byDate.filter { it.optBoolean("started") && !it.optBoolean("watched") }.take(10).forEach { cont.put(it) }
        val chan = JSONArray()
        byDate.filter { !it.optBoolean("watched") }.take(20).forEach { chan.put(it) }
        return JSONObject().put("continueWatching", cont).put("channel", chan)
    }

    // ---- SSE --------------------------------------------------------------------------------
    private fun sse(): Response {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 64 * 1024)
        synchronized(sseClients) {
            while (sseClients.size >= 4) { runCatching { sseClients.removeAt(0).close() } }
            sseClients.add(out)
        }
        runCatching { out.write("retry: 3000\n\n".toByteArray()); out.flush() }
        val res = newChunkedResponse(Response.Status.OK, "text/event-stream", input)
        res.addHeader("Cache-Control", "no-cache")
        res.addHeader("Connection", "keep-alive")
        return res
    }

    // ---- actions ----------------------------------------------------------------------------
    private fun play(id: String?, viaApp: Boolean, fromStart: Boolean): Response {
        val item = findItem(id) ?: return err("не найдено", Response.Status.NOT_FOUND)
        val fp = item.optString("filePath").ifEmpty {
            // libraryView hides filePath; look it up in the raw library doc
            findRawPath(item.optString("id"))
        }
        if (!inMediaDirs(fp)) return err("путь вне медиапапок", Response.Status.BAD_REQUEST)
        val f = File(fp)
        val url = "http://127.0.0.1:${config.localPort}/stream/${item.optString("id")}/${java.net.URLEncoder.encode(f.name, "UTF-8")}"
        val srt = File(fp.replace(Regex("\\.[^.]+$"), ".srt"))
        val title = item.optString("title").ifEmpty { f.name } +
            (item.opt("year")?.let { y -> if (y != JSONObject.NULL) " ($y)" else "" } ?: "")
        // Mark as started (position itself is remembered by VLC)
        db.collection("devices").document(config.deviceId).collection("library")
            .document(item.optString("id"))
            .set(mapOf("started" to true, "startedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        onPlay(item, url, config.playerPackage, if (srt.exists()) srt.path else null, fromStart)
        return json(JSONObject()
            .put("ok", true).put("launchBy", "app").put("url", url)
            .put("package", config.playerPackage).put("title", title)
            .put("subtitles", if (srt.exists()) srt.path else null))
    }

    private fun findRawPath(id: String): String {
        for (i in 0 until library.length()) {
            val o = library.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o.optString("filePath")
        }
        return ""
    }

    private fun setWatched(id: String?, set: Boolean): Response {
        val item = findItem(id) ?: return err("не найдено", Response.Status.NOT_FOUND)
        db.collection("devices").document(config.deviceId).collection("library")
            .document(item.optString("id"))
            .set(mapOf("watched" to set, "watchedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        return json(JSONObject().put("ok", true).put("watched", set))
    }

    private fun trailer(id: String?): Response {
        val item = findItem(id) ?: return err("не найдено", Response.Status.NOT_FOUND)
        val key = item.optString("trailer")
        if (key.isEmpty()) return err("трейлера нет", Response.Status.NOT_FOUND)
        onOpenUrl("https://www.youtube.com/watch?v=$key")
        return json(JSONObject().put("ok", true))
    }

    private fun kioskExit(session: IHTTPSession): Response {
        val body = readBody(session)
        val sent = try { JSONObject(body).optString("pin") } catch (_: Exception) { "" }
        if (kioskPin.isEmpty()) return err("код не задан в дашборде", Response.Status.BAD_REQUEST)
        if (sent != kioskPin) { Thread.sleep(1000); return err("неверный код", Response.Status.FORBIDDEN) }
        return json(JSONObject().put("ok", true))
    }

    private fun rtClearance(session: IHTTPSession): Response {
        if (!isLocal(session)) return err("только с localhost", Response.Status.FORBIDDEN)
        val body = readBody(session)
        Log.i("rt-clearance: получено ${body.length} байт от ClearanceActivity")
        return try {
            val o = JSONObject(body)
            RtRelay.instance?.submitClearance(
                o.optString("cookie").ifEmpty { null },
                o.optString("ua").ifEmpty { null },
                o.optString("error").ifEmpty { null })
            json(JSONObject().put("ok", !o.has("error")))
        } catch (e: Exception) { err(e.message ?: "bad json", Response.Status.BAD_REQUEST) }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try { session.parseBody(files); files["postData"] ?: "" } catch (_: Exception) { "" }
    }

    private fun searchTorrents(q: Map<String, String>): Response {
        val title = q["title"]?.trim().orEmpty()
        if (title.isEmpty()) return err("нет названия", Response.Status.BAD_REQUEST)
        val year = q["year"].orEmpty()
        val type = q["type"] ?: "movie"
        val cat = mapOf("movie" to "movies", "cartoon" to "cartoons", "series" to "series")[type] ?: "movies"
        val ref = db.collection("searches").document()
        val data = mapOf(
            "phrase" to if (year.isNotEmpty()) "$title $year" else title,
            "days" to -1, "order" to 10, "categories" to listOf(cat),
            "status" to "requested",
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp())
        return try {
            kotlinx.coroutines.runBlocking {
                ref.set(data).await()
                val started = System.currentTimeMillis()
                while (System.currentTimeMillis() - started < 30000) {
                    kotlinx.coroutines.delay(1200)
                    val d = ref.get().await()
                    when (d.getString("status")) {
                        "done" -> {
                            val results = d.get("results")
                            return@runBlocking json(JSONObject()
                                .put("results", JSONArray((results as? List<*>)?.map { JSONObject(it as Map<*, *>) } ?: emptyList<Any>()))
                                .put("count", d.getLong("count") ?: 0))
                        }
                        "error" -> return@runBlocking err(d.getString("error") ?: "ошибка поиска", Response.Status.SERVICE_UNAVAILABLE)
                    }
                }
                err("сервер не ответил", Response.Status.SERVICE_UNAVAILABLE)
            }
        } catch (e: Exception) { err(e.message ?: "ошибка") }
    }

    private fun startDownload(q: Map<String, String>): Response {
        val tid = q["tid"]?.trim().orEmpty()
        if (tid.isEmpty()) return err("нет tid", Response.Status.BAD_REQUEST)
        db.collection("downloads").add(mapOf(
            "tid" to tid, "title" to (q["title"] ?: ""), "year" to q["year"],
            "type" to (q["type"] ?: "movie"), "poster" to null,
            "target" to config.deviceId, "status" to "requested",
            "torrentFile" to null, "progress" to 0, "speed" to 0, "error" to null,
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        return json(JSONObject().put("ok", true))
    }

    // Экран настроек: список носителей и сохранение выбора (какие использовать, лимит для
    // встроенной памяти). После смены — пересканировать: медиатека могла переехать.
    private fun storage(session: IHTTPSession, q: Map<String, String>): Response {
        if (session.method == Method.POST) {
            val body = readBody(session)
            return try {
                val o = JSONObject(body)
                val cur = Storage.settings(ctx)
                o.optJSONArray("selected")?.let { cur.put("selected", it) }
                if (o.has("internalPercent")) cur.put("internalPercent", o.optInt("internalPercent"))
                if (o.has("bufferMode")) cur.put("bufferMode", o.optString("bufferMode"))
                Storage.saveSettings(ctx, cur)
                Storage.ensureDirs(ctx)
                onStorageChanged()
                json(Storage.view(ctx))
            } catch (e: Exception) { err(e.message ?: "bad json", Response.Status.BAD_REQUEST) }
        }
        return json(Storage.view(ctx))
    }

    // ---- media ------------------------------------------------------------------------------
    private fun stream(item: JSONObject?, session: IHTTPSession): Response {
        val fp = item?.let { findRawPath(it.optString("id")) } ?: ""
        if (!inMediaDirs(fp)) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        val f = File(fp)
        if (!f.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        val mime = when (f.extension.lowercase()) {
            "mkv" -> "video/x-matroska"; "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
            val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val end = m?.groupValues?.get(2)?.toLongOrNull() ?: (f.length() - 1)
            if (start >= f.length()) {
                val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                r.addHeader("Content-Range", "bytes */${f.length()}")
                return r
            }
            val len = end - start + 1
            val fis = FileInputStream(f)
            fis.skip(start)
            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, len)
            r.addHeader("Accept-Ranges", "bytes")
            r.addHeader("Content-Range", "bytes $start-$end/${f.length()}")
            return r
        }
        val r = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    // Frame from the middle of the video — Android's own retriever, no ffmpeg.
    // The retriever is firmware-dependent (the Vestel TV has no AVI demuxer at all, and a
    // mid-file frame can land on a black fade), so this escalates: several positions, any
    // keyframe as a last resort, and finally the same file on another online node — the
    // phones decode formats the TV can't. Failures are cached so every rerender of a
    // 160-episode series doesn't rescan files that will never produce a frame.
    private fun thumb(id: String?): Response {
        val item = findItem(id) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        val fp = findRawPath(item.optString("id"))
        if (!inMediaDirs(fp)) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        val out = File(thumbDir, item.optString("id") + ".jpg")
        if (!out.exists()) {
            val fail = File(thumbDir, item.optString("id") + ".fail")
            if (fail.exists() && System.currentTimeMillis() - fail.lastModified() < 6 * 3600_000L)
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            val bmp = grabFrame(fp)
            if (bmp != null) {
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            } else if (!remoteThumb(item.optString("fileName"), out)) {
                try { fail.writeText("") } catch (_: Exception) {}
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
            fail.delete()
        }
        val r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(out), out.length())
        r.addHeader("Cache-Control", "max-age=86400")
        return r
    }

    private fun grabFrame(fp: String): android.graphics.Bitmap? {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(fp)
            val durUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
            var best: android.graphics.Bitmap? = null
            for (pos in listOf(0.5, 0.3, 0.7)) {
                val b = try { mmr.getFrameAtTime((durUs * pos).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                        catch (_: Exception) { null } ?: continue
                if (!isDark(b)) { best = b; break }
                if (best == null) best = b       // dark, but better than nothing
            }
            if (best == null) best = try { mmr.getFrameAtTime(-1) } catch (_: Exception) { null }
            mmr.release()
            best?.let { b ->
                if (b.width > 640)
                    android.graphics.Bitmap.createScaledBitmap(b, 640, b.height * 640 / b.width, true)
                else b
            }
        } catch (_: Exception) { null }
    }

    private fun isDark(b: android.graphics.Bitmap): Boolean {
        var sum = 0L; var n = 0
        val stepX = maxOf(1, b.width / 16); val stepY = maxOf(1, b.height / 9)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                sum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                n++; x += stepX
            }
            y += stepY
        }
        return n == 0 || sum / n < 18
    }

    // Other online nodes: address is devices/{id}.lanIp/tvPort, and their library ids for the
    // same file differ (SHA1 of THEIR path) — so we map fileName→id via their /api/library.
    @Volatile private var peers: List<Pair<String, Map<String, String>>> = emptyList()
    @Volatile private var peersAt = 0L

    private fun refreshPeers() {
        if (System.currentTimeMillis() - peersAt < 5 * 60_000L) return
        peersAt = System.currentTimeMillis()
        peers = try {
            kotlinx.coroutines.runBlocking {
                db.collection("devices").get().await().documents
                    .filter { it.id != config.deviceId && it.getBoolean("online") == true }
                    .mapNotNull { d ->
                        val ip = d.getString("lanIp") ?: return@mapNotNull null
                        val port = (d.get("tvPort") as? Number)?.toInt() ?: return@mapNotNull null
                        try {
                            val res = httpFetch("http://$ip:$port/api/library", timeoutMs = 5000)
                            if (res.status != 200) return@mapNotNull null
                            val arr = JSONArray(String(res.body))
                            val map = HashMap<String, String>()
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val fn = o.optString("fileName")
                                if (fn.isNotEmpty()) map[fn] = o.optString("id")
                            }
                            "http://$ip:$port" to map
                        } catch (_: Exception) { null }
                    }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun remoteThumb(fileName: String, out: File): Boolean {
        if (fileName.isEmpty()) return false
        refreshPeers()
        for ((base, map) in peers) {
            val rid = map[fileName] ?: continue
            try {
                val res = httpFetch("$base/thumb?id=$rid", timeoutMs = 20000)
                if (res.status == 200 && res.body.size > 2 && res.body[0] == 0xFF.toByte()) {
                    out.writeBytes(res.body)
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    // Codec/resolution/audio/duration — MediaExtractor, cached like the Node agent did with ffprobe.
    private fun mediaInfo(id: String?): Response {
        val item = findItem(id) ?: return err("не найдено", Response.Status.NOT_FOUND)
        val fp = findRawPath(item.optString("id"))
        if (!inMediaDirs(fp)) return err("не найдено", Response.Status.NOT_FOUND)
        val cache = File(miDir, item.optString("id") + ".json")
        if (cache.exists()) return json(JSONObject(cache.readText()))
        val info = JSONObject()
        try {
            val ex = MediaExtractor()
            ex.setDataSource(fp)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !info.has("video")) {
                    info.put("video", mime.removePrefix("video/"))
                    info.put("width", fmt.getInteger(MediaFormat.KEY_WIDTH))
                    info.put("height", fmt.getInteger(MediaFormat.KEY_HEIGHT))
                    if (fmt.containsKey(MediaFormat.KEY_DURATION))
                        info.put("duration", fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000)
                } else if (mime.startsWith("audio/") && !info.has("audio")) {
                    info.put("audio", mime.removePrefix("audio/"))
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        info.put("channels", fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
            }
            ex.release()
            info.put("sizeBytes", File(fp).length())
            cache.writeText(info.toString())
        } catch (e: Exception) { return err("не удалось прочитать файл", Response.Status.INTERNAL_ERROR) }
        return json(info)
    }

    // TMDb image proxy-cache: the page never talks to image.tmdb.org directly (works offline).
    private fun imgProxy(uri: String): Response {
        val m = Regex("^/img/(w\\d{2,4}|original)/([a-zA-Z0-9_-]+\\.(?:jpg|jpeg|png|svg))$").find(uri)
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "")
        val (size, name) = m.destructured
        val f = File(imgDir, "${size}_$name")
        if (!f.exists()) {
            try {
                val r = httpFetch("https://image.tmdb.org/t/p/$size/$name")
                if (r.status >= 400) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                f.writeBytes(r.body)
            } catch (_: Exception) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "")
            }
        }
        val mime = if (name.endsWith(".png")) "image/png" else if (name.endsWith(".svg")) "image/svg+xml" else "image/jpeg"
        val r = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
        r.addHeader("Cache-Control", "max-age=31536000, immutable")
        return r
    }

    // Person page: proxied to Cloud Function `person` (TMDb key lives on the server only).
    private fun person(name: String?, refresh: Boolean): Response {
        if (name.isNullOrEmpty()) return err("нет имени", Response.Status.BAD_REQUEST)
        val key = MessageDigest.getInstance("SHA-1").digest("v3|$name".toByteArray())
            .joinToString("") { "%02x".format(it) }.substring(0, 20)
        val cache = File(personDir, "$key.json")
        if (refresh) cache.delete()
        if (cache.exists()) {
            val r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", cache.readText())
            r.addHeader("Cache-Control", "no-cache")
            return r
        }
        val pid = config.firebase.optString("projectId")
        val url = "https://us-central1-$pid.cloudfunctions.net/person?name=" +
            java.net.URLEncoder.encode(name, "UTF-8")
        return try {
            val r = httpFetch(url, timeoutMs = 40000)
            val text = String(r.body, Charsets.UTF_8)
            if (r.status < 400) cache.writeText(text)
            newFixedLengthResponse(
                Response.Status.lookup(r.status) ?: Response.Status.OK,
                "application/json; charset=utf-8", text)
        } catch (e: Exception) { err(e.message ?: "нет связи", Response.Status.SERVICE_UNAVAILABLE) }
    }

    // Карточка ещё не скачанного фильма (из фильмографии или коллекции): те же данные,
    // что у своих фильмов, но с сервера — TMDb-ключа на ноде нет. Кэш на диске вечный:
    // описание и актёры не меняются, а сеть на ноде может и отсутствовать.
    private fun movieInfo(tmdbId: String?, kind: String?): Response {
        if (tmdbId.isNullOrEmpty()) return err("нет tmdbId", Response.Status.BAD_REQUEST)
        val k = if (kind == "tv") "tv" else "movie"
        val cache = File(movieDir, "${k}_$tmdbId.json")
        if (cache.exists()) {
            val r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", cache.readText())
            r.addHeader("Cache-Control", "no-cache")
            return r
        }
        val pid = config.firebase.optString("projectId")
        val url = "https://us-central1-$pid.cloudfunctions.net/movie?kind=$k&tmdbId=" +
            java.net.URLEncoder.encode(tmdbId, "UTF-8")
        return try {
            val r = httpFetch(url, timeoutMs = 40000)
            val text = String(r.body, Charsets.UTF_8)
            if (r.status < 400) cache.writeText(text)
            newFixedLengthResponse(
                Response.Status.lookup(r.status) ?: Response.Status.OK,
                "application/json; charset=utf-8", text)
        } catch (e: Exception) { err(e.message ?: "нет связи", Response.Status.SERVICE_UNAVAILABLE) }
    }

    // Секунда остановки, которую VLC вернул при выходе. Раньше мы её не знали вовсе (плеер
    // запускался «без ответа»), и на карточке было глухое «фильм уже начат» — теперь на кнопке
    // видно, с какого места продолжим. Досмотренным считаем с 92% длительности: у релизов в
    // конце титры, и возвращаться к ним никто не станет.
    private fun savePosition(id: String?, pos: Long?, dur: Long?): Response {
        val item = findItem(id) ?: return err("не найдено", Response.Status.NOT_FOUND)
        if (pos == null || pos < 0) return err("нет позиции", Response.Status.BAD_REQUEST)
        val duration = dur ?: item.optLong("duration", 0)
        val watched = duration > 0 && pos > duration * 92 / 100
        val fields = mutableMapOf<String, Any>(
            "position" to pos, "started" to true,
            "positionAt" to FieldValue.serverTimestamp())
        if (duration > 0) fields["duration"] = duration
        if (watched) { fields["watched"] = true; fields["position"] = 0L }
        db.collection("devices").document(config.deviceId).collection("library")
            .document(item.optString("id")).set(fields, SetOptions.merge())
        Log.i("position: ${item.optString("title")} — ${pos / 1000}s${if (watched) " (досмотрено)" else ""}")
        return json(JSONObject().put("ok", true).put("watched", watched))
    }

    // ---- перенос между носителями ноды --------------------------------------------------------
    // Что можно скопировать с выбранного тома (сериал — одной строкой) плюс список самих томов.
    private fun copyPlan(from: String?): Response {
        val vols = Storage.volumes(ctx)
        val arr = JSONArray()
        vols.forEach { v ->
            arr.put(JSONObject().put("id", v.id).put("label", v.label)
                .put("removable", v.removable)
                .put("freeBytes", v.freeBytes).put("totalBytes", v.totalBytes))
        }
        // Флешка, подключённая через системный выбор папки (единственный способ на телефоне):
        // копировать НА неё можно, а вот источником она быть не может — у неё нет пути,
        // по которому агент сканирует медиатеку.
        SafStore.views(ctx).forEach { arr.put(it) }
        val src = vols.firstOrNull { it.id == from } ?: vols.firstOrNull()
        val items = if (src != null) Copier.plan(ctx, library, src.dir) else JSONArray()
        return json(JSONObject().put("volumes", arr).put("from", src?.id ?: "").put("items", items))
    }

    // Удаление фильма с ТВ-страницы (меню ⋯ на карточке): своё — сразу файлом, «везде» —
    // командами delete остальным устройствам (их агенты удалят сами; поиск по tmdbId).
    private fun deleteItem(id: String?, everywhere: Boolean): Response {
        val item = findItem(id) ?: return err("не найдено", Response.Status.NOT_FOUND)
        val fp = item.optString("filePath")
        if (fp.isNotEmpty()) {
            if (!inMediaDirs(fp)) return err("путь вне медиапапок")
            java.io.File(fp).delete()
        }
        db.collection("devices").document(config.deviceId)
            .collection("library").document(item.optString("id")).delete()
        if (everywhere) {
            val tmdbId = item.opt("tmdbId")
            if (tmdbId != null && tmdbId != org.json.JSONObject.NULL) {
                scope.launch {
                    try {
                        val devs = db.collection("devices").get().await()
                        for (dev in devs.documents) {
                            if (dev.id == config.deviceId) continue
                            val hits = dev.reference.collection("library")
                                .whereEqualTo("tmdbId", tmdbId).get().await()
                            for (h in hits.documents) {
                                dev.reference.collection("commands").add(mapOf(
                                    "type" to "delete", "libId" to h.id,
                                    "filePath" to (h.getString("filePath") ?: ""),
                                    "status" to "pending",
                                    "createdAt" to FieldValue.serverTimestamp()))
                            }
                        }
                    } catch (e: Exception) { Log.e("delete everywhere: ${e.message}") }
                }
            }
        }
        onStorageChanged()
        return json(JSONObject().put("ok", true))
    }

    // Проверка носителя: перечитывает ВСЁ, что уже лежит на приёмнике, и сверяет с исходником.
    // Нужна отдельно от копирования: подделка ёмкости проявляется не там, где писали, а раньше —
    // накопитель принимает новое, молча затирая старое. Поэтому проверять надо всё сразу.
    private fun verifyVolume(fromId: String, toId: String): Response {
        val vols = Storage.volumes(ctx)
        val src = vols.firstOrNull { it.id == fromId } ?: return err("нет носителя-источника")
        val tree = if (toId.startsWith("usb-")) SafStore.uriById(ctx, toId) else null
        val dstVol = if (tree == null) vols.firstOrNull { it.id == toId } else null
        if (tree == null && dstVol == null) return err("нет носителя-приёмника")
        val plan = Copier.plan(ctx, library, src.dir)
        val bad = JSONArray()
        var checked = 0
        var missing = 0
        for (i in 0 until plan.length()) {
            val files = plan.optJSONObject(i)?.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = File(files.optString(j))
                if (!f.isFile) continue
                val rel = f.absolutePath.removePrefix(src.dir.absolutePath + File.separator)
                val dst = if (dstVol != null) File(dstVol.dir, rel) else null
                val e = Copier.check(ctx, f, dst, rel, tree)
                if (e == "файла нет на носителе") { missing++; continue }
                checked++
                if (e != null) bad.put(JSONObject().put("file", rel).put("error", e))
            }
        }
        Log.i("verify[$toId]: проверено $checked, сбойных ${bad.length()}")
        return json(JSONObject()
            .put("checked", checked).put("missing", missing)
            .put("bad", bad).put("ok", bad.length() == 0))
    }

    // Запуск: {"from":"<vol>","to":"<vol>","keys":["f:/path", "s:Сериал", …]}
    private fun copyStart(session: IHTTPSession): Response {
        // Параллельные задачи разрешены, но по одной на приёмник.
        val o = try { JSONObject(readBody(session)) } catch (e: Exception) {
            return err("не разобрал запрос", Response.Status.BAD_REQUEST)
        }
        val e = startCopy(o)
        return if (e == null) json(Copier.status()) else err(e, Response.Status.BAD_REQUEST)
    }

    // Заливка сотен гигабайт идёт часами, а приложение за это время могут перезапустить
    // (система выгрузила, обновили APK, перезагрузили телефон). Поэтому план задачи лежит на
    // диске, и агент подхватывает его при старте — уже скопированные файлы пропускаются по
    // размеру, так что продолжение обходится в один быстрый проход.
    fun resumeCopyJobs() {
        val f = File(ctx.filesDir, "copy-jobs.json")
        val arr = try { JSONArray(f.readText()) } catch (_: Exception) { return }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val err = startCopy(o)
            if (err != null) Log.e("copy: не продолжить «${o.optString("to")}» — $err")
            else Log.i("copy: продолжаю ${o.optString("to")} после перезапуска")
        }
    }

    private fun saveCopyJob(o: JSONObject, done: Boolean) {
        val f = File(ctx.filesDir, "copy-jobs.json")
        val cur = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until cur.length()) {
            val x = cur.optJSONObject(i) ?: continue
            if (x.optString("to") != o.optString("to")) out.put(x)
        }
        if (!done) out.put(o)
        try { f.writeText(out.toString()) } catch (e: Exception) { Log.e("copy: план не сохранён: ${e.message}") }
    }

    // Возвращает текст ошибки или null, если задача принята.
    private fun startCopy(o: JSONObject): String? {
        val vols = Storage.volumes(ctx)
        val src = vols.firstOrNull { it.id == o.optString("from") } ?: return "нет носителя-источника"
        val toId = o.optString("to")
        val tree = if (toId.startsWith("usb-")) SafStore.uriById(ctx, toId) else null
        val toSaf = tree != null
        if (toId.startsWith("usb-") && tree == null) return "накопитель не подключён — выдайте доступ заново"
        val dst = if (toSaf) null else vols.firstOrNull { it.id == toId } ?: return "нет носителя-приёмника"
        if (!toSaf && src.id == dst!!.id) return "носители совпадают"
        val keys = o.optJSONArray("keys") ?: JSONArray()
        val want = (0 until keys.length()).map { keys.optString(it) }.toSet()
        val plan = Copier.plan(ctx, library, src.dir)
        val items = mutableListOf<Copier.Item>()
        var need = 0L
        for (i in 0 until plan.length()) {
            val g = plan.optJSONObject(i) ?: continue
            if (g.optString("key") !in want) continue
            val files = g.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val fp = files.optString(j)
                val rel = fp.removePrefix(src.dir.absolutePath + File.separator)
                val f = File(fp)
                if (!f.isFile) continue
                items.add(if (toSaf) Copier.Item(f, null, rel, tree, f.length())
                          else Copier.Item(f, File(dst!!.dir, rel), null, null, f.length()))
                need += f.length()
            }
        }
        if (items.isEmpty()) return "нечего копировать"
        // Запас 300 МБ: файловой системе нужно место и на служебные записи.
        val free = if (toSaf) SafStore.freeBytes(ctx, tree!!) else dst!!.freeBytes
        val where = if (toSaf) SafStore.labelById(ctx, toId) else dst!!.label
        if (free > 0 && need + 300L * 1024 * 1024 > free) {
            // Продолжение прерванной заливки: то, что уже лежит на приёмнике, места не займёт —
            // Copier такие файлы пропускает. Без этой поправки задачу нельзя было возобновить:
            // объём всей медиатеки сравнивался со свободным местом уже наполовину полной флешки.
            var rest = 0L
            for (it in items) {
                val done = if (it.dst != null) (if (it.dst.exists()) it.dst.length() else 0)
                           else SafStore.sizeOf(ctx, it.tree!!, it.dstRel!!)
                if (done != it.size) rest += it.size
            }
            if (rest + 300L * 1024 * 1024 > free)
                return "на «$where» не хватит места: нужно ${rest / 1048576} МБ, свободно ${free / 1048576} МБ"
            Log.i("copy: на «$where» уже лежит ${(need - rest) / 1048576} МБ — продолжаю")
        }
        if (Copier.running(toId)) return "на «$where» уже идёт копирование"
        saveCopyJob(o, done = false)
        Copier.start(ctx, scope, toId, where, items) {
            // Дошли до конца (или человек остановил) — план больше не нужен.
            if (!Copier.running(toId)) saveCopyJob(o, done = true)
            onStorageChanged()
        }
        return null
    }

    // ---- static (assets/tv) ------------------------------------------------------------------
    private fun static(uri: String): Response {
        val rel = if (uri == "/") "index.html" else uri.trimStart('/')
        return try {
            // Скачанная веб-часть важнее вшитой (см. WebUpdater); нет её или файла в ней —
            // молча отдаём то, что приехало вместе с APK.
            val downloaded = File(WebUpdater.dir(ctx), rel)
            val stream = if (downloaded.isFile) downloaded.inputStream() else ctx.assets.open("tv/$rel")
            val mime = when (rel.substringAfterLast('.', "")) {
                "html" -> "text/html; charset=utf-8"
                "js" -> "application/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "png" -> "image/png"; "svg" -> "image/svg+xml; charset=utf-8"
                "ico" -> "image/x-icon"; "json" -> "application/json; charset=utf-8"
                "webmanifest" -> "application/manifest+json; charset=utf-8"
                else -> "application/octet-stream"
            }
            val r = newChunkedResponse(Response.Status.OK, mime, stream)
            r.addHeader("Cache-Control", "no-cache")
            r
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }
}
