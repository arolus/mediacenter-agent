// Library sync: scans Movies/Series/Cartoons and mirrors them into devices/{id}/library.
// A straight port of the Node agent's lib/library.js — same doc ids (SHA1 of path), same
// fields, same TMDb/torrent field preservation on rescan, so the server enrichment and the
// dashboard see no difference between a Node node and this one.
package com.mediacenter.tv.agent

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.security.MessageDigest

object Library {
    private val TMDB_FIELDS = listOf(
        "tmdbId", "catalogId", "poster", "backdrop", "backdrops", "overview", "cast", "rating",
        "animation", "collection", "castX", "genres", "director", "writers", "country", "studio",
        "premiered", "votes", "tagline", "runtime", "trailer", "budget", "revenue",
        "imdbId", "imdbRating", "imdbVotes", "enrichV"
    )
    private val TORRENT_FIELDS = listOf("magnet", "infoHash", "rutrackerTid", "rutrackerUrl")
    private const val MIN_SERIES_FILES = 8
    private val VIDEO = Regex("\\.(mkv|mp4|avi|mov|wmv|m4v|mpg|mpeg)$", RegexOption.IGNORE_CASE)

    fun libIdFor(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }.substring(0, 20)

    data class Found(val file: File, val relSegments: List<String>)

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

    suspend fun sync(ctx: Context, db: FirebaseFirestore, config: Config): Int {
        val libCol = db.collection("devices").document(config.deviceId).collection("library")
        val existing = HashMap<String, Map<String, Any?>>()
        libCol.get().await().documents.forEach { existing[it.id] = it.data ?: emptyMap() }
        val seen = HashSet<String>()
        var changed = 0

        for ((type, dir) in config.mediaDirs(ctx)) {
            val files = try { scan(dir) } catch (_: Exception) { emptyList() }

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
                    if (patch.isNotEmpty()) libCol.document(id).set(patch, SetOptions.merge()).await()
                    continue
                }

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
                if (prev != null) {
                    keep.putAll(pick(prev, TORRENT_FIELDS))
                    if (prev["tmdbId"] != null) {
                        keep.putAll(pick(prev, TMDB_FIELDS))
                        keep["title"] = prev["title"]; keep["year"] = prev["year"]
                    }
                    if (prev["tmdbTried"] == true && prev["title"] == title) keep["tmdbTried"] = true
                    if (prev["watched"] == true) { keep["watched"] = true; keep["watchedAt"] = prev["watchedAt"] }
                    if (prev["started"] == true) keep["started"] = true
                }

                val docData = HashMap<String, Any?>(keep)
                docData["type"] = type
                docData["seriesDir"] = seriesDir
                if (!keep.containsKey("title")) docData["title"] = title
                if (!keep.containsKey("year")) docData["year"] = year
                docData["season"] = season
                docData["episode"] = episode
                docData["filePath"] = f.file.path
                docData["fileName"] = f.file.name
                docData["sizeBytes"] = f.file.length()
                docData["addedAt"] = (prev?.get("addedAt") as? Number)?.toLong() ?: f.file.lastModified()
                docData["originalName"] = prev?.get("originalName") ?: f.file.name
                docData["updatedAt"] = FieldValue.serverTimestamp()
                libCol.document(id).set(docData).await()
                changed++
                Log.i("library: + $type | ${seriesDir?.plus("/") ?: ""}${f.file.name}")
            }
        }

        for (id in existing.keys) {
            if (id !in seen) { libCol.document(id).delete().await(); changed++ }
        }
        return changed
    }

    // After a torrent download / P2P transfer lands a file on disk.
    suspend fun addFile(ctx: Context, db: FirebaseFirestore, config: Config,
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
        data["addedAt"] = System.currentTimeMillis()
        data["updatedAt"] = FieldValue.serverTimestamp()
        libCol.document(libIdFor(filePath)).set(data).await()
    }
}
