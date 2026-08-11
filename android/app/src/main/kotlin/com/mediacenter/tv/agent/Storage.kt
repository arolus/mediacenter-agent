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

    // Все доступные носители. Индекс 0 в getExternalFilesDirs — встроенная память, дальше — съёмные.
    fun volumes(ctx: Context): List<Volume> {
        val out = mutableListOf<Volume>()
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        dirs.forEachIndexed { i, base ->
            val path = base.absolutePath
            val removable = !path.contains("/emulated/") && path.startsWith("/storage/")
            if (removable && Environment.getExternalStorageState(base) != Environment.MEDIA_MOUNTED) return@forEachIndexed
            val id = if (removable) uuidOf(path) else "internal"
            val dir = File(base, "media")
            val stat = try { StatFs(base.path) } catch (_: Exception) { null }
            out.add(Volume(
                id = id,
                label = if (removable) "USB-накопитель${if (id.isNotEmpty()) " $id" else ""}" else "Встроенная память",
                removable = removable,
                dir = dir,
                totalBytes = stat?.totalBytes ?: 0,
                freeBytes = stat?.availableBytes ?: 0
            ))
        }
        // Внутренняя память: на телевизоре общий раздел не смонтирован вовсе, и в списке выше
        // его нет — но собственный каталог приложения доступен всегда, и это честное «встроенное
        // хранилище», которое можно выбрать (с лимитом в процентах).
        if (out.none { !it.removable }) {
            val f = File(ctx.filesDir, "media")
            val stat = try { StatFs(ctx.filesDir.path) } catch (_: Exception) { null }
            out.add(Volume("internal", "Встроенная память", false, f,
                stat?.totalBytes ?: 0, stat?.availableBytes ?: 0))
        }
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
        volumes(ctx).forEach { v ->
            arr.put(JSONObject()
                .put("id", v.id)
                .put("label", v.label)
                .put("removable", v.removable)
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
    }
}
