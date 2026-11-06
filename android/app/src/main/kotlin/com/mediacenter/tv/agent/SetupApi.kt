// Standalone setup API behind the phone page (tv/setup.html). Every call except /api/setup-state
// needs the token from the TV's QR. Secrets are write-only: GET reports "set" and the last four
// characters, never the value.
package com.mediacenter.tv.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SetupApi(private val ctx: Context, private val db: Db, private val config: Config) {

    // What the TV page needs to show the QR. The URL carries the token, so it is only handed to
    // the TV's own WebView (localhost), never to other devices on the LAN.
    fun state(local: Boolean): JSONObject {
        val o = JSONObject()
            .put("mode", if (config.isLocal) "local" else "cloud")
            .put("configured", !config.isLocal || LocalSettings.configured(ctx))
        if (local && config.isLocal) o.put("setupUrl", setupUrl())
        return o
    }

    fun setupUrl(): String =
        "http://${lanIp() ?: "127.0.0.1"}:${config.localPort}/setup?t=${LocalSettings.setupToken(ctx)}"

    fun authorized(token: String?): Boolean =
        config.isLocal && !token.isNullOrEmpty() && token == LocalSettings.setupToken(ctx)

    private fun secret(v: String) = JSONObject().put("set", v.isNotEmpty())
        .put("hint", if (v.length > 4) "…" + v.takeLast(4) else "")

    suspend fun get(): JSONObject {
        val s = LocalSettings.load(ctx)
        val dev = db.collection("devices").document(config.deviceId).get().await()
        val tv = db.collection("config").document("tv").get().await()
        return JSONObject()
            .put("device", JSONObject().put("id", config.deviceId)
                .put("name", dev.getString("name") ?: config.deviceName))
            .put("tmdb", secret(s.optString("tmdbKey")))
            .put("omdb", secret(s.optString("omdbKey")))
            .put("seed", LocalSettings.seed(ctx))
            .put("sources", JSONArray(Sources.instance?.list().orEmpty().map { sourceView(it) }))
            .put("kids", JSONObject()
                .put("mode", dev.getString("mode") ?: "normal")
                .put("exitPin", dev.getBoolean("exitPin") == true)
                .put("pinSet", !tv.getString("pin").isNullOrEmpty()))
    }

    // Saves whatever the page sent; keys are verified before they are stored, so a typo shows up
    // on the phone right away instead of as "nothing gets recognized" days later.
    suspend fun save(body: JSONObject): JSONObject {
        val errors = JSONObject()
        body.optString("tmdbKey").trim().takeIf { it.isNotEmpty() }?.let { key ->
            val e = Tmdb.check(key)
            if (e == null) {
                LocalSettings.update(ctx) { it.put("tmdbKey", key) }
                LocalBackend.instance?.kick()
            } else errors.put("tmdb", e)
        }
        if (body.has("omdbKey")) {
            val key = body.optString("omdbKey").trim()
            val e = if (key.isEmpty()) null else Omdb.check(key)
            if (e == null) LocalSettings.update(ctx) { it.put("omdbKey", key) } else errors.put("omdb", e)
        }
        body.optJSONObject("seed")?.let { seed ->
            LocalSettings.update(ctx) {
                it.put("seed", JSONObject()
                    .put("ratio", seed.optDouble("ratio", 1.0).coerceIn(0.0, 10.0))
                    .put("days", seed.optInt("days", 7).coerceIn(0, 365))
                    .put("uploadKbps", seed.optInt("uploadKbps", 2048).coerceIn(0, 1_000_000)))
            }
        }
        val devPatch = HashMap<String, Any?>()
        body.optString("name").trim().takeIf { it.isNotEmpty() }?.let { devPatch["name"] = it.take(60) }
        body.optJSONObject("kids")?.let { k ->
            if (k.has("mode")) {
                val m = k.optString("mode")
                if (m == "kids" || m == "normal") devPatch["mode"] = m
            }
            if (k.has("exitPin")) devPatch["exitPin"] = k.optBoolean("exitPin")
            if (k.has("pin")) {
                val pin = k.optString("pin").trim()
                if (pin.isEmpty() || pin.matches(Regex("\\d{4,8}")))
                    db.collection("config").document("tv").set(mapOf("pin" to pin), SetOptions.merge()).await()
                else errors.put("pin", "Код — от 4 до 8 цифр")
            }
        }
        if (devPatch.isNotEmpty())
            db.collection("devices").document(config.deviceId).set(devPatch, SetOptions.merge()).await()
        return get().put("errors", errors)
    }

    // Plain settings come back as values, passwords only as "set" — the page never sees them.
    private fun sourceView(src: JSONObject): JSONObject {
        val values = src.optJSONObject("values") ?: JSONObject()
        val fields = src.optJSONArray("settings") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            val k = f.optString("key")
            val v = if (values.isNull(k)) "" else values.optString(k)
            val o = JSONObject().put("key", k).put("label", f.optString("label", k)).put("type", f.optString("type", "text"))
            if (f.optString("type") == "password") o.put("set", v.isNotEmpty()) else o.put("value", v)
            out.put(o)
        }
        return JSONObject().put("id", src.optString("id")).put("name", src.optString("name"))
            .put("version", src.optInt("version", 1)).put("url", src.opt("url") ?: JSONObject.NULL)
            .put("settings", out).put("loggedIn", src.optBoolean("loggedIn"))
    }

    // Login through the plugin: ok, or a captcha picture for the phone to show, or an error.
    private suspend fun sourceLogin(id: String, captcha: JSONObject?): JSONObject {
        val srcs = Sources.instance ?: return JSONObject().put("error", "источники не запущены")
        return try {
            srcs.call(id, "login", JSONObject().put("captcha", captcha ?: JSONObject.NULL))
            LocalSettings.update(ctx) { it.optJSONObject("sources")?.optJSONObject(id)?.put("loggedIn", true) }
            JSONObject().put("ok", true)
        } catch (e: SourceException) {
            if (!e.info.isNull("captcha")) JSONObject().put("captcha", e.info.getJSONObject("captcha"))
            else JSONObject().put("error", e.message)
        } catch (e: Exception) { JSONObject().put("error", e.message ?: "ошибка") }
    }

    suspend fun source(action: String, body: JSONObject): JSONObject {
        val srcs = Sources.instance ?: return JSONObject().put("error", "источники не запущены")
        val id = body.optString("id")
        return when (action) {
            "add" -> try {
                val m = if (body.optString("code").isNotEmpty()) srcs.install(body.getString("code"), null)
                        else srcs.installFromUrl(body.optString("url").trim())
                JSONObject().put("ok", true).put("id", m.optString("id"))
            } catch (e: Exception) { JSONObject().put("error", e.message ?: "не удалось добавить") }
            "save" -> {
                val values = body.optJSONObject("values") ?: JSONObject()
                // An empty password field means "keep the saved one".
                val keep = JSONObject()
                for (k in values.keys()) if (values.optString(k).isNotEmpty()) keep.put(k, values.optString(k))
                srcs.saveValues(id, keep)
                sourceLogin(id, null)
            }
            "login" -> sourceLogin(id, body.optJSONObject("captcha"))
            "update" -> try {
                val url = srcs.get(id)?.optString("url").orEmpty()
                if (url.isEmpty() || url == "null") throw Exception("источник добавлен файлом — загрузите новый файл")
                srcs.installFromUrl(url); JSONObject().put("ok", true)
            } catch (e: Exception) { JSONObject().put("error", e.message) }
            "remove" -> { srcs.remove(id); JSONObject().put("ok", true) }
            else -> JSONObject().put("error", "нет такого действия")
        }.put("state", get())
    }

    // Support report for whoever helps the user: device facts + the app's own recent log, with
    // every stored secret blanked out. Android lets an app read only its own process's log.
    fun report(): String {
        val secrets = mutableListOf<String>()
        val s = LocalSettings.load(ctx)
        listOf("tmdbKey", "omdbKey").forEach { k -> s.optString(k).takeIf { it.length >= 4 }?.let { secrets.add(it) } }
        Sources.instance?.list()?.forEach { src ->
            val v = src.optJSONObject("values") ?: return@forEach
            v.keys().forEach { k -> v.optString(k).takeIf { it.length >= 4 }?.let { secrets.add(it) } }
        }
        secrets.add(LocalSettings.setupToken(ctx))
        val log = try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500", "-v", "time", "MCAgent:I", "MCWeb:W", "*:S"))
            p.inputStream.bufferedReader().readText()
        } catch (e: Exception) { "logcat: ${e.message}" }
        val head = "MediaCenter ${appVersion()} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            "Android ${android.os.Build.VERSION.RELEASE} · ${config.deviceId}\n" +
            "TMDb: ${if (LocalSettings.tmdbKey(ctx).isNotEmpty()) "set" else "NOT SET"} · " +
            "sources: ${Sources.instance?.list()?.joinToString { it.optString("id") + "@" + it.optInt("version") } ?: "-"}\n\n"
        var text = head + log
        for (x in secrets) text = text.replace(x, "•••")
        // cookies and URL-embedded keys that never went through settings
        text = text.replace(Regex("(api_key=|apikey=|bb_session=|cf_clearance=)[^&;\\s]+"), "$1•••")
        return text
    }

    private fun appVersion(): String = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${p.versionName}.${AppUpdater.installedCode(ctx)}"
    } catch (_: Exception) { "?" }

    fun check(body: JSONObject): JSONObject {
        val key = body.optString("key")
        val e = when (body.optString("kind")) {
            "tmdb" -> Tmdb.check(key)
            "omdb" -> Omdb.check(key)
            else -> "неизвестная проверка"
        }
        return JSONObject().put("ok", e == null).put("error", e)
    }
}
