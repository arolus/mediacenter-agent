// Library sync: scans Movies/Series/Cartoons and mirrors them into devices/{id}/library.
// A straight port of the Node agent's lib/library.js — same doc ids (SHA1 of path), same
// fields, same TMDb/torrent field preservation on rescan, so the server enrichment and the
// dashboard see no difference between a Node node and this one.
package com.mediacenter.tv.agent

import android.content.Context
import java.io.File
import java.security.MessageDigest

object Library {
    private val TMDB_FIELDS = listOf(
        "tmdbId", "catalogId", "poster", "backdrop", "backdrops", "overview", "cast", "rating",
        "animation", "collection", "castX", "directorsX", "genres", "director", "writers", "country", "studio",
        "premiered", "votes", "tagline", "runtime", "trailer", "budget", "revenue", "keywords",
        "imdbId", "imdbRating", "imdbVotes", "ageRating", "enrichV"
    )
    private val TORRENT_FIELDS = listOf("magnet", "infoHash", "rutrackerTid", "rutrackerUrl")
    private const val MIN_SERIES_FILES = 8
    private val VIDEO = Regex("\\.(mkv|mp4|avi|mov|wmv|m4v|mpg|mpeg)$", RegexOption.IGNORE_CASE)

    fun libIdFor(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }.substring(0, 20)

    data class Found(val file: File, val relSegments: List<String>)

    // Content fingerprint, so a file keeps its record (recognition, watched mark, position) when
    // it is renamed or moved: size + SHA-1 of the first and last 64 KB (the OpenSubtitles idea —
    // 128 KB read instead of gigabytes). Doc ids stay SHA-1 of the path for compatibility.
    private const val FP_CHUNK = 64 * 1024

    fun fingerprint(f: File): String? = try {
        val len = f.length()
        val md = MessageDigest.getInstance("SHA-1")
        java.io.RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(FP_CHUNK)
            fun chunk(at: Long) {
                raf.seek(at)
                val n = raf.read(buf, 0, minOf(FP_CHUNK.toLong(), len - at).toInt())
                if (n > 0) md.update(buf, 0, n)
            }
            chunk(0)
            if (len > FP_CHUNK) chunk(maxOf(FP_CHUNK.toLong(), len - FP_CHUNK))
        }
        "$len:" + md.digest().joinToString("") { "%02x".format(it) }.substring(0, 24)
    } catch (_: Exception) { null }

    // Not carried over on a move: the file is here now, and recognition may retry on a new name.
    private val MOVE_DROP = setOf("offline", "tmdbTried")

    private fun scan(dir: File): List<Found> {
        val out = mutableListOf<Found>()
        fun walk(d: File) {
            val entries = d.listFiles() ?: return
            for (e in entries) {
                if (e.isDirectory) walk(e)
                else if (VIDEO.containsMatchIn(e.name))
                    out.add(Found(e, e.relativeTo(dir).path.split(File.separatorChar)))
            }
        }
        walk(dir)
        return out
    }

    // "Title 151" → base+num (a year is not an episode number)
    private fun stripTail(t: String): Pair<String, Int>? {
        val m = Regex("^(.*?)[\\s._-]+(\\d{1,4})$").find(t) ?: return null
        val num = m.groupValues[2].toInt()
        if (num in 1900..2099) return null
        return m.groupValues[1].trimEnd(' ', '.', '_', '-') to num
    }

    private fun pick(src: Map<String, Any?>, fields: List<String>): Map<String, Any?> =
        fields.mapNotNull { k -> src[k]?.let { k to it } }.toMap()

    // Прогресс текущего сканирования — для окошка на TV-странице (см. /api/scan-status):
    // воткнули флешку → на экране видно, что нода перебирает файлы, а не зависла.
    object Progress {
        @Volatile var running = false
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var finishedAt = 0L
    }

    suspend fun sync(ctx: Context, db: Db, config: Config): Int {
        // Медиатека ноды в общей памяти, а доступ «Все файлы» ещё не выдан: папки прочитаются
        // как пустые, и синхронизация СТЁРЛА БЫ всю библиотеку из Firestore (сотни записей
        // вместе с распознаванием). Молча ждём разрешения — приложение его запрашивает.
        if (Storage.needsAllFilesAccess(ctx)) {
            Log.e("library: нет доступа «Все файлы» — скан пропущен, чтобы не стереть медиатеку")
            return 0
        }
        val libCol = db.collection("devices").document(config.deviceId).collection("library")
        val existing = HashMap<String, Map<String, Any?>>()
        libCol.get().await().documents.forEach { existing[it.id] = it.data ?: emptyMap() }
        val seen = HashSet<String>()
        var changed = 0
        // Offline every write would wait for a server that never answers. After the first
        // unconfirmed one stop waiting: the rest go to the local cache and sync later.
        var queued = false
        suspend fun write(op: Op<Unit>) { if (!queued && !op.settle()) queued = true }

        // Медиатека может лежать на нескольких носителях сразу (встроенная память + флешки):
        // сканируем все выбранные, тип по-прежнему определяется папкой.
        // Сначала собираем списки файлов целиком — прогресс должен знать общий объём работы.
        val batches = Storage.scanDirs(ctx).map { (type, dirs) ->
            type to dirs.map { dir -> dir to (try { scan(dir) } catch (_: Exception) { emptyList() }) }
        }
        Progress.total = batches.sumOf { (_, ds) -> ds.sumOf { it.second.size } }

        // Records whose file is gone from a CONNECTED volume (a removed stick is not a move) are
        // candidates for a rename/move: a new path with the same size and fingerprint takes them
        // over. Grouped by size — comparing sizes is free, fingerprints are read only on a match.
        val mounted0 = Storage.activeVolumes(ctx).map { it.dir.absolutePath + File.separator }
        val foundIds = HashSet<String>()
        val newBySize = HashMap<Long, Int>()
        for ((_, ds) in batches) for ((_, fs) in ds) for (f in fs) {
            val id = libIdFor(f.file.path)
            foundIds.add(id)
            if (id !in existing) newBySize.merge(f.file.length(), 1, Int::plus)
        }
        val orphans = HashMap<Long, MutableList<String>>()
        for ((id, prev) in existing) {
            if (id in foundIds) continue
            val path = prev["filePath"] as? String ?: continue
            if (mounted0.none { path.startsWith(it) } || File(path).exists()) continue
            val size = (prev["sizeBytes"] as? Number)?.toLong() ?: continue
            orphans.getOrPut(size) { mutableListOf() }.add(id)
        }
        val thumbs = File(ctx.filesDir, "cache/thumbs")

        // The orphan this new file is, if any: fingerprints must match; a record from before
        // fingerprints existed is accepted only when its size is unique on both sides.
        fun movedFrom(f: File, fp: String?): String? {
            val cands = orphans[f.length()] ?: return null
            val hit = cands.firstOrNull { fp != null && existing[it]?.get("fp") == fp }
                ?: cands.singleOrNull()?.takeIf { existing[it]?.get("fp") == null && newBySize[f.length()] == 1 }
                ?: return null
            cands.remove(hit)
            return hit
        }
        Progress.done = 0
        Progress.running = true
        try {
        for ((type, dirsFiles) in batches) {
          for ((dir, files) in dirsFiles) {

            val dirCounts = HashMap<String, Int>()
            val baseCounts = HashMap<String, Int>()
            for (f in files) {
                if (f.relSegments.size > 1) {
                    dirCounts.merge(f.relSegments[0], 1, Int::plus); continue
                }
                if (type == "series" || type == "cartoon") {
                    stripTail(Recognizer.parse(f.file.name).title)?.let {
                        baseCounts.merge(it.first.lowercase(), 1, Int::plus)
                    }
                }
            }

            for (f in files) {
                Progress.done++
                val id = libIdFor(f.file.path)
                seen.add(id)
                val prev = existing[id]
                val seriesDir = if (f.relSegments.size > 1 &&
                    (dirCounts[f.relSegments[0]] ?: 0) >= MIN_SERIES_FILES) f.relSegments[0] else null

                if (prev != null && prev["fileName"] == f.file.name && prev["type"] == type &&
                    (prev["seriesDir"] as? String) == seriesDir) {
                    val patch = HashMap<String, Any>()
                    if ((prev["sizeBytes"] as? Number)?.toLong() != f.file.length()) patch["sizeBytes"] = f.file.length()
                    if (prev["originalName"] == null) patch["originalName"] = f.file.name
                    if (prev["addedAt"] == null) patch["addedAt"] = f.file.lastModified()
                    // Fingerprint once per file (backfills records made before it existed) and again
                    // whenever the file is rewritten: a stale one would fail to recognise a later move.
                    val mtime = f.file.lastModified()
                    if (prev["fp"] == null || patch.containsKey("sizeBytes") || (prev["fpMtime"] as? Number)?.toLong() != mtime)
                        fingerprint(f.file)?.let { patch["fp"] = it; patch["fpMtime"] = mtime }
                    if (patch.isNotEmpty()) write(libCol.document(id).set(patch, SetOptions.merge()))
                    continue
                }

                val fp = fingerprint(f.file)
                val fromId = if (prev == null) movedFrom(f.file, fp) else null
                val moved = fromId?.let { existing[it] }
                val base = prev ?: moved

                val p = Recognizer.parse(f.file.name)
                var title = p.title
                var year = p.year
                var episode = p.episode
                val season = p.season
                if (type == "series" || type == "cartoon") {
                    if (episode == null) {
                        stripTail(p.title)?.let { (base, num) ->
                            if (seriesDir != null || (baseCounts[base.lowercase()] ?: 0) >= MIN_SERIES_FILES) {
                                episode = num
                                if (seriesDir == null) title = base
                            }
                        }
                    }
                    if (seriesDir != null) {
                        val pd = Recognizer.parse(seriesDir)
                        title = pd.title
                        year = pd.year ?: year
                    }
                }

                val keep = HashMap<String, Any?>()
                if (base != null) {
                    keep.putAll(pick(base, TORRENT_FIELDS))
                    if (base["tmdbId"] != null) {
                        keep.putAll(pick(base, TMDB_FIELDS))
                        keep["title"] = base["title"]; keep["year"] = base["year"]
                    }
                    if (base["tmdbTried"] == true && base["title"] == title) keep["tmdbTried"] = true
                    if (base["watched"] == true) { keep["watched"] = true; keep["watchedAt"] = base["watchedAt"] }
                    if (base["started"] == true) keep["started"] = true
                }

                val docData = HashMap<String, Any?>(keep)
                // A move keeps the rest of the record too (position, manual fields); everything
                // that follows from the path is rewritten below.
                moved?.forEach { (k, v) -> if (k !in MOVE_DROP && k !in keep && k != "title" && k != "year") docData[k] = v }
                docData["fp"] = fp
                docData["fpMtime"] = f.file.lastModified()
                docData["type"] = type
                docData["seriesDir"] = seriesDir
                if (!keep.containsKey("title")) docData["title"] = title
                if (!keep.containsKey("year")) docData["year"] = year
                docData["season"] = season
                docData["episode"] = episode
                docData["filePath"] = f.file.path
                docData["fileName"] = f.file.name
                docData["sizeBytes"] = f.file.length()
                docData["addedAt"] = (base?.get("addedAt") as? Number)?.toLong() ?: f.file.lastModified()
                docData["originalName"] = base?.get("originalName") ?: f.file.name
                docData["updatedAt"] = FieldValue.serverTimestamp()
                write(libCol.document(id).set(docData))
                changed++
                if (fromId != null) {
                    write(libCol.document(fromId).delete())
                    existing.remove(fromId)
                    File(thumbs, "$fromId.jpg").renameTo(File(thumbs, "$id.jpg"))
                    Log.i("library: moved ${moved?.get("filePath")} -> ${f.file.path}")
                } else Log.i("library: + $type | ${seriesDir?.plus("/") ?: ""}${f.file.name}")
            }
          }
        }
        } finally {
            Progress.running = false
            Progress.finishedAt = System.currentTimeMillis()
        }

        // Носителей может быть несколько, и вынутая флешка — это НЕ повод стирать её фильмы.
        // Раньше пропавший файл означал «удалили», и после отключения второй флешки из
        // Firestore улетала сотня записей вместе с распознаванием, отметками «просмотрено» и
        // позицией просмотра, а по возвращении носителя всё обогащалось заново. Теперь смотрим
        // на путь: лежит он на подключённом сейчас носителе или на отсутствующем.
        val mounted = Storage.activeVolumes(ctx).map { it.dir.absolutePath + File.separator }
        for ((id, prev) in existing) {
            if (id in seen) {
                if (prev["offline"] == true)
                    write(libCol.document(id).set(mapOf("offline" to false), SetOptions.merge()))
                continue
            }
            val path = prev["filePath"] as? String ?: ""
            val here = mounted.any { path.startsWith(it) }
            if (here) { write(libCol.document(id).delete()); changed++ }          // файла правда нет
            else if (prev["offline"] != true) {                                    // носитель вынут
                write(libCol.document(id).set(mapOf("offline" to true), SetOptions.merge()))
                changed++
            }
        }
        return changed
    }

    // After a torrent download / P2P transfer lands a file on disk.
    suspend fun addFile(ctx: Context, db: Db, config: Config,
                        filePath: String, meta: Map<String, Any?>) {
        val libCol = db.collection("devices").document(config.deviceId).collection("library")
        val f = File(filePath)
        val p = Recognizer.parse(f.name)
        val data = HashMap<String, Any?>()
        data.putAll(pick(meta, TMDB_FIELDS))
        data.putAll(pick(meta, TORRENT_FIELDS))
        data["type"] = meta["type"] ?: "movie"
        data["title"] = meta["title"] ?: p.title
        data["year"] = meta["year"] ?: p.year
        data["season"] = p.season
        data["episode"] = p.episode
        data["filePath"] = filePath
        data["fileName"] = f.name
        data["sizeBytes"] = f.length()
        data["originalName"] = f.name
        fingerprint(f)?.let { data["fp"] = it; data["fpMtime"] = f.lastModified() }
        data["addedAt"] = System.currentTimeMillis()
        data["updatedAt"] = FieldValue.serverTimestamp()
        libCol.document(libIdFor(filePath)).set(data).settle()
    }
}
