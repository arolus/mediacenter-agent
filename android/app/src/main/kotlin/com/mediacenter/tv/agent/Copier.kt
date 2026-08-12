// Перенос уже скачанного между носителями САМОЙ ноды: с флешки на флешку, из общей памяти
// на флешку и наоборот. Нужен, когда носитель кончается или его меняют: качать те же фильмы
// заново через торрент — часы, а копирование внутри устройства идёт на скорости диска.
//
// Копируем, а не перемещаем: исходник остаётся на месте до тех пор, пока человек сам его не
// удалит. Так неудачное копирование (выдернули флешку) ничего не теряет.
//
// Задач может идти НЕСКОЛЬКО сразу — по одной на приёмник: наполнять две флешки одной и той же
// медиатекой одновременно это нормальное желание, и ждать сутки ради второй копии незачем.
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
import java.util.concurrent.ConcurrentHashMap

object Copier {
    // Одна пара «откуда → куда» для конкретного файла. Приёмник бывает двух видов: обычная
    // папка (dst) или USB через системный доступ к документам (dstRel + tree, см. SafStore).
    // На телефоне возможен только второй.
    data class Item(val src: File, val dst: File?, val dstRel: String?,
                    val tree: android.net.Uri?, val size: Long)

    // Состояние одной задачи; ключ — id приёмника, так что на каждый носитель своя очередь.
    class Task(val targetId: String, val targetLabel: String,
               val totalFiles: Int, val totalBytes: Long) {
        @Volatile var job: Job? = null
        @Volatile var current: String = ""
        @Volatile var doneFiles = 0
        @Volatile var doneBytes = 0L
        @Volatile var failed = 0
        @Volatile var error: String? = null
        @Volatile var startedAt = System.currentTimeMillis()
        @Volatile var finishedAt = 0L

        fun running() = job?.isActive == true

        fun view(): JSONObject {
            val secs = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
            return JSONObject()
                .put("target", targetId).put("targetLabel", targetLabel)
                .put("running", running()).put("current", current)
                .put("doneFiles", doneFiles).put("totalFiles", totalFiles)
                .put("doneBytes", doneBytes).put("totalBytes", totalBytes)
                .put("failed", failed)
                .put("speed", if (running() && doneBytes > 0) doneBytes / secs else 0)
                .put("error", error)
                .put("finishedAgo", if (finishedAt > 0) (System.currentTimeMillis() - finishedAt) / 1000 else -1)
        }
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    fun running(targetId: String): Boolean = tasks[targetId]?.running() == true
    fun anyRunning(): Boolean = tasks.values.any { it.running() }

    fun status(): JSONObject {
        val arr = JSONArray()
        // Свежие сверху; завершённые держим полчаса, чтобы человек увидел итог.
        tasks.values.sortedByDescending { it.startedAt }.forEach {
            if (it.running() || it.finishedAt == 0L ||
                System.currentTimeMillis() - it.finishedAt < 30 * 60 * 1000) arr.put(it.view())
        }
        return JSONObject().put("jobs", arr).put("running", anyRunning())
    }

    fun stop(targetId: String?) {
        if (targetId.isNullOrEmpty()) tasks.values.forEach { it.job?.cancel() }
        else tasks[targetId]?.job?.cancel()
    }

    // onDone зовём в любом случае (в т.ч. при ошибке) — агент пересканирует медиатеку, чтобы
    // скопированное сразу появилось в списке на приёмнике.
    fun start(ctx: Context, scope: CoroutineScope, targetId: String, targetLabel: String,
              items: List<Item>, onDone: () -> Unit): Boolean {
        if (running(targetId)) return false
        val t = Task(targetId, targetLabel, items.size, items.sumOf { it.size })
        tasks[targetId] = t
        t.job = scope.launch(Dispatchers.IO) {
            try {
                for (it in items) {
                    if (!isActive) break
                    t.current = it.src.name
                    // Уже на месте и того же размера — пропускаем: повторный запуск дожимает
                    // прерванное копирование, а не начинает всё сначала.
                    val already = if (it.dst != null) (if (it.dst.exists()) it.dst.length() else 0)
                                  else SafStore.sizeOf(ctx, it.tree!!, it.dstRel!!)
                    if (already == it.size) { t.doneFiles++; t.doneBytes += it.size; continue }
                    try {
                        if (it.dst != null) copyToFile(t, it) else copyToSaf(ctx, t, it)
                        t.doneFiles++
                    } catch (e: Exception) {
                        // Один сбойный файл не должен хоронить всю ночную заливку: отмечаем и
                        // идём дальше, а человек увидит, на чём споткнулись.
                        t.failed++
                        t.error = "${it.src.name}: ${e.message}"
                        Log.e("copy[${t.targetId}]: ${t.error}")
                    }
                }
            } finally {
                t.current = ""; t.finishedAt = System.currentTimeMillis()
                Log.i("copy[${t.targetId}]: готово ${t.doneFiles}/${t.totalFiles}, сбоев ${t.failed}")
                try { onDone() } catch (_: Exception) {}
            }
        }
        return true
    }

    // Обычная папка: пишем во временный файл и переименовываем — оборванная копия не
    // притворится готовым фильмом.
    private suspend fun copyToFile(t: Task, it: Item) = kotlinx.coroutines.coroutineScope {
        it.dst!!.parentFile?.mkdirs()
        val tmp = File(it.dst.parentFile, it.dst.name + ".part")
        try {
            it.src.inputStream().use { input -> tmp.outputStream().use { out -> pump(t, input, out); sync(out) } }
            if (!isActive) { tmp.delete(); return@coroutineScope }
            if (tmp.length() != it.size)
                throw java.io.IOException("скопировалось ${tmp.length()} из ${it.size} байт")
            if (!tmp.renameTo(it.dst)) throw java.io.IOException("не удалось переименовать файл")
        } catch (e: Exception) { tmp.delete(); throw e }
    }

    // USB через системный доступ к документам: переименования там нет, поэтому пишем сразу под
    // нужным именем, а размер сверяем после.
    private suspend fun copyToSaf(ctx: Context, t: Task, it: Item) = kotlinx.coroutines.coroutineScope {
        val out = SafStore.openForWrite(ctx, it.tree!!, it.dstRel!!)
            ?: throw java.io.IOException("накопитель не принял файл (нет доступа?)")
        it.src.inputStream().use { input -> out.use { o -> pump(t, input, o); sync(o) } }
        val got = SafStore.sizeOf(ctx, it.tree, it.dstRel)
        if (isActive && got != it.size)
            throw java.io.IOException("скопировалось $got из ${it.size} байт")
    }

    // Сброс на сам носитель, а не в кэш системы.
    //
    // Дорогой опыт 2026-08-12: терабайтную флешку выдернули из телефона без «извлечения» — файлы
    // были записаны и проверены по размеру, но exFAT держал записи каталога в памяти, и папки
    // Movies и Series приехали в телевизор ПУСТЫМИ (место занято, имён нет). Уцелело только то,
    // что копировалось на несколько часов раньше и успело осесть само.
    // close() такой гарантии не даёт, fsync — даёт: файл на диске сразу после копирования.
    private fun sync(out: java.io.OutputStream) {
        try {
            when (out) {
                is java.io.FileOutputStream -> out.fd.sync()
                // SAF отдаёт обёртку над файловым дескриптором — достаём его отражением,
                // другого пути к fsync через ParcelFileDescriptor.AutoCloseOutputStream нет.
                else -> {
                    val m = out.javaClass.methods.firstOrNull { it.name == "getFD" && it.parameterTypes.isEmpty() }
                    (m?.invoke(out) as? java.io.FileDescriptor)?.sync()
                }
            }
        } catch (e: Exception) { Log.e("copy: fsync не удался: ${e.message}") }
    }

    private suspend fun pump(t: Task, input: java.io.InputStream, out: java.io.OutputStream) =
        kotlinx.coroutines.coroutineScope {
            val buf = ByteArray(4 * 1024 * 1024)
            while (isActive) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                t.doneBytes += n
            }
            out.flush()
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
