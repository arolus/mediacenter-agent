// Хранилища ноды: какие носители есть, куда складывать медиатеку и сколько места занимать.
//
// Android 11+ не даёт писать в корень флешки — пишем только в свой каталог на ней
// (getExternalFilesDirs), для этого не нужно ни одного разрешения, и он переживает перезагрузки.
// Встроенная память бывает крошечной (на телевизоре — пара гигабайт), поэтому для неё есть
// лимит в процентах: агент не займёт больше, чем разрешено.
//
// Выбор хранится в файле рядом с конфигом: это настройка устройства, а не общая для всех нод.
package com.mediacenter.tv.agent

import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Volume(
    val id: String,            // стабильный ключ: "internal" или UUID тома ("6A7A-DCD4")
    val label: String,
    val removable: Boolean,
    val dir: File,             // куда реально писать (наш каталог внутри тома)
    val totalBytes: Long,
    val freeBytes: Long
)

object Storage {
    private const val FILE = "storage.json"
    private const val DEFAULT_INTERNAL_PERCENT = 60

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun settings(ctx: Context): JSONObject = try {
        JSONObject(file(ctx).readText())
    } catch (_: Exception) {
        JSONObject().put("selected", JSONArray()).put("internalPercent", DEFAULT_INTERNAL_PERCENT)
    }

    fun saveSettings(ctx: Context, o: JSONObject) {
        try { file(ctx).writeText(o.toString()) } catch (e: Exception) { Log.e("storage: ${e.message}") }
    }

    // Все доступные носители: съёмные из getExternalFilesDirs плюс встроенная память.
    //
    // Внутреннее хранилище — ВСЕГДА собственный каталог приложения (filesDir), а не запись из
    // getExternalFilesDirs. Общий том /storage/emulated может быть то смонтирован, то нет (на
    // телевизоре он поднялся только после переразметки флешки), и путь под ним уезжал бы вместе
    // с ним — уже скачанное переставало находиться при сканировании. filesDir лежит на том же
    // разделе, доступен всегда и не требует разрешений.
    fun volumes(ctx: Context): List<Volume> {
        val out = mutableListOf<Volume>()
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { base ->
            val path = base.absolutePath
            val removable = !path.contains("/emulated/") && path.startsWith("/storage/")
            if (!removable) return@forEach
            if (Environment.getExternalStorageState(base) != Environment.MEDIA_MOUNTED) return@forEach
            val id = uuidOf(path)
            val stat = try { StatFs(base.path) } catch (_: Exception) { null }
            out.add(Volume(
                id = id,
                label = "USB-накопитель${if (id.isNotEmpty()) " $id" else ""}",
                removable = true,
                dir = File(base, "media"),
                totalBytes = stat?.totalBytes ?: 0,
                freeBytes = stat?.availableBytes ?: 0
            ))
        }
        val f = File(ctx.filesDir, "media")
        val stat = try { StatFs(ctx.filesDir.path) } catch (_: Exception) { null }
        out.add(Volume("internal", "Встроенная память", false, f,
            stat?.totalBytes ?: 0, stat?.availableBytes ?: 0))
        return out
    }

    // "/storage/6A7A-DCD4/Android/data/..." → "6A7A-DCD4"
    private fun uuidOf(path: String): String =
        path.removePrefix("/storage/").substringBefore('/')

    // Выбранные пользователем носители; пока не выбрал — все найденные (флешка предпочтительнее).
    fun activeVolumes(ctx: Context): List<Volume> {
        val all = volumes(ctx)
        val sel = settings(ctx).optJSONArray("selected") ?: JSONArray()
        if (sel.length() == 0) {
            val removable = all.filter { it.removable }
            return if (removable.isNotEmpty()) removable else all
        }
        val ids = (0 until sel.length()).map { sel.optString(it) }.toSet()
        val chosen = all.filter { it.id in ids }
        return chosen.ifEmpty { all }
    }

    fun internalPercent(ctx: Context): Int =
        settings(ctx).optInt("internalPercent", DEFAULT_INTERNAL_PERCENT).coerceIn(5, 100)

    // Сколько ещё можно занять на носителе: для встроенной памяти — с учётом лимита в процентах.
    fun writableBytes(ctx: Context, v: Volume): Long {
        if (v.removable) return v.freeBytes
        val cap = v.totalBytes * internalPercent(ctx) / 100
        val used = dirSize(v.dir)
        return maxOf(0, minOf(v.freeBytes, cap - used))
    }

    fun dirSize(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) { 0 }

    // --- буфер во встроенной памяти -------------------------------------------------------------
    //
    // Торрент пишет куски вразнобой, и на USB телевизора это катастрофа: подряд он принимает
    // ~2,4 МБ/с, вразнобой — ~0,9. Встроенная память быстрая (20 МБ/с), поэтому крупные файлы
    // сперва принимаем в неё, а потом перекладываем на носитель одним последовательным потоком.
    // Оправдано только для съёмных носителей и только если файл целиком влезает в буфер.
    fun stagingDir(ctx: Context): File = File(ctx.filesDir, "staging").apply { mkdirs() }

    private fun isRemovableDir(ctx: Context, dir: File): Boolean =
        volumes(ctx).any { it.removable && dir.absolutePath.startsWith(it.dir.absolutePath) }

    // Режим: "auto" (по измеренной скорости носителя), "on" (всегда через буфер), "off" (напрямую).
    fun bufferMode(ctx: Context): String =
        settings(ctx).optString("bufferMode", "auto").takeIf { it in setOf("auto", "on", "off") } ?: "auto"

    // Порог автоматики: медленнее этого — есть смысл в буфере (встроенная память даёт ~20 МБ/с).
    private const val SLOW_MB_S = 5.0
    private const val SPEED_TTL_MS = 30L * 24 * 3600 * 1000

    // Замер скорости записи носителя: 24 МБ с принудительным сбросом на диск. Результат
    // кэшируется в настройках по id тома — мерить при каждой закачке незачем, а флешку
    // меняют редко (сменилась — сменился и id, замер будет заново).
    fun writeSpeedMbS(ctx: Context, v: Volume): Double {
        val st = settings(ctx)
        val cache = st.optJSONObject("speeds") ?: JSONObject()
        val entry = cache.optJSONObject(v.id)
        val now = System.currentTimeMillis()
        if (entry != null && now - entry.optLong("at") < SPEED_TTL_MS) return entry.optDouble("mbs", 0.0)
        val probe = File(v.dir, ".speedtest").apply { parentFile?.mkdirs() }
        val mbs = try {
            val buf = ByteArray(1024 * 1024)
            val t0 = System.nanoTime()
            java.io.FileOutputStream(probe).use { out ->
                repeat(24) { out.write(buf) }
                out.flush(); out.fd.sync()
            }
            val secs = (System.nanoTime() - t0) / 1e9
            if (secs > 0) 24.0 / secs else 0.0
        } catch (e: Exception) { Log.e("speed probe: ${e.message}"); 0.0 } finally { probe.delete() }
        cache.put(v.id, JSONObject().put("mbs", mbs).put("at", now))
        st.put("speeds", cache)
        saveSettings(ctx, st)
        Log.i("скорость записи ${v.id}: ${"%.1f".format(mbs)} МБ/с")
        return mbs
    }

    // Файл поместится в буфер? Оставляем запас 300 МБ, чтобы не забить раздел под ноль.
    fun stagingFor(ctx: Context, targetDir: File, sizeBytes: Long): File? {
        val mode = bufferMode(ctx)
        if (mode == "off") return null
        val vol = volumes(ctx).firstOrNull { targetDir.absolutePath.startsWith(it.dir.absolutePath) } ?: return null
        if (!vol.removable) return null
        if (sizeBytes <= 0) return null
        val free = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Exception) { 0 }
        if (free - sizeBytes < 300L * 1024 * 1024) return null
        if (mode == "auto" && writeSpeedMbS(ctx, vol) >= SLOW_MB_S) return null
        return stagingDir(ctx)
    }

    // Перекладывание буфер → носитель: один последовательный проход большими кусками.
    // onProgress зовётся долей [0..1], чтобы дашборд не показывал «замерло на 100%».
    fun moveToTarget(src: File, destDir: File, onProgress: (Double) -> Unit): File {
        destDir.mkdirs()
        val dest = File(destDir, src.name)
        val total = src.length().coerceAtLeast(1)
        var done = 0L
        var lastReport = 0L
        src.inputStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(4 * 1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 32L * 1024 * 1024) { lastReport = done; onProgress(done.toDouble() / total) }
                }
                out.flush()
            }
        }
        if (dest.length() != src.length()) {
            dest.delete()
            throw java.io.IOException("копирование на носитель оборвалось: ${dest.length()} из ${src.length()}")
        }
        src.delete()
        src.parentFile?.takeIf { it.name != "staging" && it.list()?.isEmpty() == true }?.delete()
        return dest
    }

    // Куда класть новый файл: носитель с наибольшим доступным местом (лимит уже учтён).
    fun targetDir(ctx: Context, type: String): File {
        val v = activeVolumes(ctx).maxByOrNull { writableBytes(ctx, it) } ?: volumes(ctx).first()
        val sub = when (type) { "series" -> "Series"; "cartoon" -> "Cartoons"; else -> "Movies" }
        return File(v.dir, sub).apply { mkdirs() }
    }

    // Все папки, которые нужно сканировать: по три на каждый выбранный носитель.
    fun scanDirs(ctx: Context): Map<String, List<File>> {
        val vols = activeVolumes(ctx)
        return mapOf(
            "movie" to vols.map { File(it.dir, "Movies") },
            "series" to vols.map { File(it.dir, "Series") },
            "cartoon" to vols.map { File(it.dir, "Cartoons") }
        )
    }

    fun ensureDirs(ctx: Context) {
        scanDirs(ctx).values.flatten().forEach { it.mkdirs() }
    }

    // Для экрана настроек: список носителей с занятым/свободным и признаком выбранности.
    fun view(ctx: Context): JSONObject {
        val sel = activeVolumes(ctx).map { it.id }.toSet()
        val arr = JSONArray()
        val speeds = settings(ctx).optJSONObject("speeds") ?: JSONObject()
        volumes(ctx).forEach { v ->
            arr.put(JSONObject()
                .put("id", v.id)
                .put("label", v.label)
                .put("removable", v.removable)
                .put("writeMbS", speeds.optJSONObject(v.id)?.optDouble("mbs") ?: 0.0)
                .put("selected", v.id in sel)
                .put("totalBytes", v.totalBytes)
                .put("freeBytes", v.freeBytes)
                .put("usedByUsBytes", dirSize(v.dir))
                .put("writableBytes", writableBytes(ctx, v))
                .put("path", v.dir.absolutePath))
        }
        return JSONObject()
            .put("volumes", arr)
            .put("internalPercent", internalPercent(ctx))
            .put("bufferMode", bufferMode(ctx))
    }
}
