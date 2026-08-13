// Самообновление веб-части (agent/tv): интерфейс приезжает на ноды без переустановки APK.
//
// ЗАЧЕМ. Почти все правки — это app.js и вёрстка, а не Kotlin. Вшитые в APK, они требовали
// adb, ноутбука и знания порта отладки; теперь нода забирает их сама.
//
// КАК. В репозитории лежит tv/manifest.json — версия и sha1 каждого файла. Нода сверяет его со
// своим, качает только изменившееся во ВРЕМЕННУЮ папку, проверяет хэши и лишь потом подменяет
// рабочую одним переименованием. Битая или оборванная загрузка до экрана не доедет, а если
// что-то пойдёт не так — HttpServer всегда откатится на копию, вшитую в APK.
package com.mediacenter.tv.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object WebUpdater {
    private const val BASE = "https://raw.githubusercontent.com/arolus/mediacenter-agent/main/tv/"
    private const val EVERY_MS = 10 * 60 * 1000L

    // Рабочая папка веб-части; пусто — значит, отдаём вшитую в APK.
    fun dir(ctx: Context): File = File(ctx.filesDir, "tv")

    fun localVersion(ctx: Context): String = try {
        JSONObject(File(dir(ctx), "manifest.json").readText()).optString("version")
    } catch (_: Exception) { "" }

    fun start(ctx: Context, scope: CoroutineScope, onUpdated: () -> Unit) {
        scope.launch {
            while (true) {
                try { if (check(ctx)) onUpdated() } catch (e: Exception) { Log.e("webupdate: ${e.message}") }
                delay(EVERY_MS)
            }
        }
    }

    // true — интерфейс обновился и страницу нужно перезагрузить.
    fun check(ctx: Context): Boolean {
        val remote = JSONObject(fetch(BASE + "manifest.json").toString(Charsets.UTF_8))
        val version = remote.optString("version")
        if (version.isEmpty() || version == localVersion(ctx)) return false
        val files = remote.optJSONObject("files") ?: return false

        val work = dir(ctx)
        val tmp = File(ctx.filesDir, "tv.new").apply { deleteRecursively(); mkdirs() }
        Log.i("webupdate: ${localVersion(ctx).ifEmpty { "вшитая" }} → $version")

        for (name in files.keys()) {
            val want = files.optString(name)
            // Файл не изменился — берём из рабочей папки, не гоняя сеть впустую.
            val have = File(work, name)
            val out = File(tmp, name).apply { parentFile?.mkdirs() }
            if (have.isFile && sha1(have.readBytes()) == want) { have.copyTo(out, overwrite = true); continue }
            val body = fetch(BASE + name)
            if (sha1(body) != want) {
                Log.e("webupdate: $name не совпал по хэшу — обновление отменено")
                tmp.deleteRecursively()
                return false
            }
            out.writeBytes(body)
        }
        File(tmp, "manifest.json").writeText(remote.toString())

        // Подмена одним движением: до этого момента ни один запрос страницы не видел половину
        // нового и половину старого.
        val old = File(ctx.filesDir, "tv.old").apply { deleteRecursively() }
        if (work.exists() && !work.renameTo(old)) { tmp.deleteRecursively(); return false }
        if (!tmp.renameTo(work)) { old.renameTo(work); return false }   // не вышло — вернули как было
        old.deleteRecursively()
        Log.i("webupdate: интерфейс обновлён до $version")
        return true
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} на $url")
            return c.inputStream.readBytes()
        } finally { c.disconnect() }
    }

    private fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
}
