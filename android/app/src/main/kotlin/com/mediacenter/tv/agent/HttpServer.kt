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
            deviceMode = if (own == "kids" || own == "normal") own else null
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
        for (i in 0 until library.length()) {
            val it = library.optJSONObject(i) ?: continue
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
                .put("addedAt", it.optLong("addedAt", 0))
                .put("collection", it.opt("collection"))
                .put("castX", it.optJSONArray("castX") ?: JSONArray())
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
    private fun thumb(id: String?): Response {
        val item = findItem(id) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        val fp = findRawPath(item.optString("id"))
        if (!inMediaDirs(fp)) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        val out = File(thumbDir, item.optString("id") + ".jpg")
        if (!out.exists()) {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(fp)
                val durUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
                val bmp = mmr.getFrameAtTime(durUs / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                mmr.release()
                bmp ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        }
        val r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(out), out.length())
        r.addHeader("Cache-Control", "max-age=86400")
        return r
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

    // ---- static (assets/tv) ------------------------------------------------------------------
    private fun static(uri: String): Response {
        val rel = if (uri == "/") "index.html" else uri.trimStart('/')
        return try {
            val stream = ctx.assets.open("tv/$rel")
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
