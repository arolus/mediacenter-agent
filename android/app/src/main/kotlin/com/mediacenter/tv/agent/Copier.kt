// Перенос уже скачанного между носителями САМОЙ ноды: с флешки на флешку, из общей памяти
// на флешку и наоборот. Нужен, когда носитель кончается или его меняют: качать те же фильмы
// заново через торрент — часы, а копирование внутри устройства идёт на скорости диска.
//
// Копируем, а не перемещаем: исходник остаётся на месте до тех пор, пока человек сам его не
// удалит. Так неудачное копирование (выдернули флешку) ничего не теряет.
package com.mediacenter.tv.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Copier {
    // Одна пара «откуда → куда» для конкретного файла.
    data class Item(val src: File, val dst: File, val size: Long)

    @Volatile private var job: Job? = null
    @Volatile private var current: String = ""
    @Volatile private var doneFiles = 0
    @Volatile private var totalFiles = 0
    @Volatile private var doneBytes = 0L
    @Volatile private var totalBytes = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var error: String? = null
    @Volatile private var finishedAt = 0L

    fun running(): Boolean = job?.isActive == true

    fun status(): JSONObject {
        val secs = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
        return JSONObject()
            .put("running", running())
            .put("current", current)
            .put("doneFiles", doneFiles).put("totalFiles", totalFiles)
            .put("doneBytes", doneBytes).put("totalBytes", totalBytes)
            .put("speed", if (running() && doneBytes > 0) doneBytes / secs else 0)
            .put("error", error)
            .put("finishedAgo", if (finishedAt > 0) (System.currentTimeMillis() - finishedAt) / 1000 else -1)
    }

    fun stop() { job?.cancel(); job = null; current = "" }

    // onDone зовём в любом случае (в т.ч. при ошибке) — агент пересканирует медиатеку, чтобы
    // скопированное сразу появилось в списке на приёмнике.
    fun start(scope: CoroutineScope, items: List<Item>, onDone: () -> Unit): Boolean {
        if (running()) return false
        doneFiles = 0; totalFiles = items.size
        doneBytes = 0; totalBytes = items.sumOf { it.size }
        error = null; finishedAt = 0; startedAt = System.currentTimeMillis()
        job = scope.launch(Dispatchers.IO) {
            try {
                for (it in items) {
                    if (!isActive) break
                    current = it.src.name
                    // Уже на месте и того же размера — пропускаем: повторный запуск дожимает
                    // прерванное копирование, а не начинает всё сначала.
                    if (it.dst.exists() && it.dst.length() == it.size) {
                        doneFiles++; doneBytes += it.size; continue
                    }
                    it.dst.parentFile?.mkdirs()
                    val tmp = File(it.dst.parentFile, it.dst.name + ".part")
                    try {
                        it.src.inputStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(4 * 1024 * 1024)
                                while (isActive) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    doneBytes += n
                                }
                                out.flush()
                            }
                        }
                        if (!isActive) { tmp.delete(); break }
                        if (tmp.length() != it.size) throw java.io.IOException(
                            "скопировалось ${tmp.length()} из ${it.size} байт")
                        if (!tmp.renameTo(it.dst)) throw java.io.IOException("не удалось переименовать файл")
                        doneFiles++
                    } catch (e: Exception) {
                        tmp.delete()
                        error = "${it.src.name}: ${e.message}"
                        Log.e("copy: ${error}")
                        break
                    }
                }
            } finally {
                current = ""; finishedAt = System.currentTimeMillis()
                try { onDone() } catch (_: Exception) {}
            }
        }
        return true
    }

    // Что лежит на носителе: элементы медиатеки этого тома, сгруппированные так же, как их
    // видит человек (сериал — одной строкой со всеми сериями).
    fun plan(ctx: Context, library: JSONArray, fromDir: File): JSONArray {
        val groups = LinkedHashMap<String, JSONObject>()
        for (i in 0 until library.length()) {
            val o = library.optJSONObject(i) ?: continue
            val fp = o.optString("filePath")
            if (fp.isEmpty() || !fp.startsWith(fromDir.absolutePath + File.separator)) continue
            val type = o.optString("type", "movie")
            val key = if (type == "series")
                "s:" + (o.optString("seriesDir").ifEmpty { o.optString("title") })
            else "f:$fp"
            val g = groups.getOrPut(key) {
                JSONObject().put("key", key).put("type", type)
                    .put("title", o.optString("title").ifEmpty { File(fp).name })
                    .put("year", o.opt("year")).put("poster", o.opt("poster"))
                    .put("size", 0L).put("files", JSONArray())
            }
            g.put("size", g.optLong("size") + o.optLong("sizeBytes"))
            g.getJSONArray("files").put(fp)
        }
        val out = JSONArray()
        groups.values.sortedWith(compareBy({ it.optString("type") }, { it.optString("title") }))
            .forEach { out.put(it) }
        return out
    }
}
