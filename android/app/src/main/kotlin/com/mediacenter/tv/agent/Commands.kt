// Command queue devices/{id}/commands: rescan / delete / normalize / update.
// "update" is a no-op here — code ships with the APK, not via git pull.
package com.mediacenter.tv.agent

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

object Commands {
    fun watch(ctx: Context, db: FirebaseFirestore, config: Config, scope: CoroutineScope,
              onLibraryChanged: () -> Unit): ListenerRegistration {
        val col = db.collection("devices").document(config.deviceId).collection("commands")
        return col.whereEqualTo("status", "pending").addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                if (ch.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) return@forEach
                val id = ch.document.id
                val cmd = ch.document.data
                scope.launch {
                    val ref = col.document(id)
                    try {
                        ref.update("status", "acked").await()
                        when (cmd["type"]) {
                            "rescan" -> { Library.sync(ctx, db, config); onLibraryChanged() }
                            "delete" -> {
                                val fp = cmd["filePath"] as? String ?: ""
                                // Файл на вынутом носителе: удалить нечего, но запись из
                                // каталога убрать надо — иначе «удалить везде» оставляет
                                // фильм висеть до подключения носителя (и падает «путь вне
                                // медиапапок»: несмонтированный том не в списке томов).
                                if (!Storage.isInMediaDirs(ctx, fp)) {
                                    if (File(fp).exists()) throw Exception("путь вне медиапапок: $fp")
                                } else File(fp).deleteRecursively()
                                val libId = cmd["libId"] as? String ?: Library.libIdFor(fp)
                                db.collection("devices").document(config.deviceId)
                                    .collection("library").document(libId).delete().await()
                                onLibraryChanged()
                            }
                            "rename" -> {
                                // Переименование ОДНОГО файла (дашборд → «Исправить файл…»).
                                // Док переносится на новый путь с сохранением всех полей —
                                // иначе новый SHA1-id стирал бы отметки и распознавание.
                                val fp = cmd["filePath"] as? String ?: ""
                                val newName = (cmd["newName"] as? String ?: "").trim()
                                if (newName.isEmpty() || newName.contains('/'))
                                    throw Exception("некорректное имя: «$newName»")
                                if (!Storage.isInMediaDirs(ctx, fp)) throw Exception("путь вне медиапапок: $fp")
                                val src = File(fp)
                                if (!src.isFile) throw Exception("файла нет: $fp")
                                val dst = File(src.parentFile, newName)
                                if (dst.exists()) throw Exception("уже есть файл с именем «$newName»")
                                if (!src.renameTo(dst)) throw Exception("не удалось переименовать")
                                val libCol = db.collection("devices").document(config.deviceId).collection("library")
                                val oldId = cmd["libId"] as? String ?: Library.libIdFor(fp)
                                val old = libCol.document(oldId).get().await()
                                if (old.exists()) {
                                    val data = HashMap(old.data ?: emptyMap())
                                    data["filePath"] = dst.path
                                    data["fileName"] = dst.name
                                    // originalName не трогаем: истинное имя раздачи остаётся историей
                                    libCol.document(Library.libIdFor(dst.path)).set(data).await()
                                    libCol.document(oldId).delete().await()
                                }
                                ref.update("result", "«${src.name}» → «${dst.name}»").await()
                                onLibraryChanged()
                            }
                            "normalize" -> {
                                val n = Normalize.run(ctx, db, config)
                                ref.update("result", "переименовано $n").await()
                                onLibraryChanged()
                            }
                            "update" -> {
                                ref.update("result", "агент внутри приложения — обновляется вместе с APK").await()
                            }
                            else -> throw Exception("неизвестная команда: ${cmd["type"]}")
                        }
                        ref.update(mapOf("status" to "done", "finishedAt" to FieldValue.serverTimestamp())).await()
                    } catch (e: Exception) {
                        Log.e("command ${cmd["type"]}: ${e.message}")
                        try { ref.update(mapOf("status" to "error", "error" to (e.message ?: "?"))).await() } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}

// "Normalize": recognized movies → "Title (Year).ext", series → one folder per show,
// cartoons (server sets animation=true) move to Cartoons. Then prune empty dirs + rescan.
object Normalize {
    private const val MIN_EPISODES = 8

    private fun sanitize(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim()

    suspend fun run(ctx: Context, db: FirebaseFirestore, config: Config): Int {
        // «Упорядочить» раскладывает по носителю, где файл уже лежит: перекладывать
        // фильмы между флешками без спроса — не наше дело.
        val dirs = Storage.scanDirs(ctx).mapValues { it.value.first() }
        val inMedia = { p: String -> dirs.values.any { p == it.path || p.startsWith(it.path + File.separator) } }
        val snap = db.collection("devices").document(config.deviceId).collection("library").get().await()
        val all = snap.documents.mapNotNull { it.data }

        val groupKey = { it: Map<String, Any?> ->
            val t = it["tmdbId"]
            if (t != null) "t:$t" else "n:" + (it["title"] as? String ?: "").lowercase().trim()
        }
        val groupCount = HashMap<String, Int>()
        for (it in all) {
            if (it["title"] == null) continue
            val cat = it["catalogId"] as? String ?: ""
            if (cat.startsWith("tv_") || it["episode"] != null || it["seriesDir"] != null)
                groupCount.merge(groupKey(it), 1, Int::plus)
        }
        val seriesLike = { it: Map<String, Any?> ->
            val cat = it["catalogId"] as? String ?: ""
            val n = groupCount[groupKey(it)] ?: 0
            when {
                cat.startsWith("tv_") -> n >= 2
                cat.startsWith("movie_") -> false
                it["episode"] == null && it["seriesDir"] == null -> false
                else -> n >= MIN_EPISODES
            }
        }

        var renamed = 0
        for (it in all) {
            val fp = it["filePath"] as? String ?: continue
            val src = File(fp)
            if (!src.exists() || !inMedia(fp)) continue
            val isSeries = seriesLike(it)
            val type = when {
                isSeries -> "series"
                it["animation"] == true -> "cartoon"
                it["type"] == "series" -> "movie"
                else -> it["type"] as? String ?: "movie"
            }
            val ext = "." + src.extension
            val title = it["title"] as? String
            val year = (it["year"] as? Number)?.toInt()
            val targetDir: File
            val targetName: String
            if (isSeries) {
                if (title == null) continue
                targetDir = File(dirs[type] ?: dirs["movie"]!!, sanitize(title + (year?.let { y -> " ($y)" } ?: "")))
                targetName = if (it["tmdbId"] != null && it["episode"] != null) {
                    val s = ((it["season"] as? Number)?.toInt() ?: 1).toString().padStart(2, '0')
                    val e = ((it["episode"] as? Number)?.toInt() ?: 0).toString().padStart(2, '0')
                    sanitize("$title S${s}E${e}") + ext
                } else src.name
            } else {
                targetDir = dirs[type] ?: dirs["movie"]!!
                targetName = when {
                    it["tmdbId"] != null && title != null -> sanitize(title + (year?.let { y -> " ($y)" } ?: "")) + ext
                    (it["type"] as? String ?: "movie") != type -> src.name
                    else -> continue
                }
            }
            val target = File(targetDir, targetName)
            if (target.path == src.path || target.exists() || !inMedia(target.path)) continue
            try {
                targetDir.mkdirs()
                if (!src.renameTo(target)) continue
                renamed++
                db.collection("devices").document(config.deviceId).collection("library")
                    .document(Library.libIdFor(target.path))
                    .set(mapOf("originalName" to (it["originalName"] ?: it["fileName"] ?: src.name)), SetOptions.merge())
                    .await()
            } catch (e: Exception) { Log.e("normalize: ${e.message}") }
        }
        // prune empty subdirs
        for (root in dirs.values) {
            root.walkBottomUp().forEach { d ->
                if (d.isDirectory && d != root && (d.listFiles()?.isEmpty() != false)) d.delete()
            }
        }
        Library.sync(ctx, db, config)
        return renamed
    }
}
