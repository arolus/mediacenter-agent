// Standalone mode: the work Cloud Functions do for cloud nodes, done in-process against LocalDb.
// A port of dashboard/functions/index.js (enrichLibrary, movie, person) — same fields, same
// ENRICH_V semantics — using the user's own TMDb/OMDb keys. Differences on purpose:
//   - IMDb ratings come from OMDb per title (no weekly dataset here); filmography and collection
//     parts keep TMDb ratings — an actor page would burn a day of the free OMDb quota;
//   - no shared caches (cache_fixes, overrides): there is no dashboard to write them.
package com.mediacenter.tv.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Android's optString() turns a JSON null into the text "null" — TMDb has plenty of those.
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
private fun JSONObject.s(k: String): String? = str(k).ifEmpty { null }

class LocalBackend(
    private val ctx: Context,
    private val db: Db,
    private val config: Config,
    private val scope: CoroutineScope
) {
    companion object {
        const val ENRICH_V = 15
        private val SELF_RE = Regex("\\bself\\b|himself|herself|самого себя|самим собой|archive footage|архив", RegexOption.IGNORE_CASE)
        @Volatile var instance: LocalBackend? = null
    }

    private val lib get() = db.collection("devices").document(config.deviceId).collection("library")
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queued = java.util.Collections.synchronizedSet(HashSet<String>())
    private var sub: ListenerRegistration? = null
    private var searchSub: ListenerRegistration? = null
    private var downloadSub: ListenerRegistration? = null
    private val claimed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val memo = HashMap<String, Pair<Long, JSONObject>>()

    private fun tmdbKey() = LocalSettings.tmdbKey(ctx)
    private fun tmdb(path: String) = Tmdb.get(tmdbKey(), path)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun start() {
        instance = this
        sub = lib.addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                if (ch.type != DocumentChange.Type.REMOVED && needsEnrich(ch.document.data)) enqueue(ch.document.id)
            }
        }
        scope.launch { worker() }
        // Torrent search and .torrent fetching — the cloud's searchTorrents/fetchTorrentFile,
        // served by the user's installed sources (Sources.kt).
        searchSub = db.collection("searches").addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                val d = ch.document.data
                if (ch.type != DocumentChange.Type.REMOVED && d["status"] == "requested" && claimed.add(ch.document.id))
                    scope.launch { runSearch(ch.document.id, d) }
            }
        }
        downloadSub = db.collection("downloads").addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                val d = ch.document.data
                if (ch.type != DocumentChange.Type.REMOVED && d["status"] == "requested" && claimed.add(ch.document.id))
                    scope.launch { fetchTorrent(ch.document.id, d) }
            }
        }
    }

    fun stop() {
        sub?.remove(); sub = null
        searchSub?.remove(); searchSub = null
        downloadSub?.remove(); downloadSub = null
        instance = null
    }

    // ---- search sources -----------------------------------------------------------------------
    private fun sourceError(name: String, e: Exception): String = when {
        e is SourceException && !e.info.isNull("captcha") ->
            "«$name» просит войти заново — откройте «Настройка с телефона» на телевизоре"
        else -> "«$name»: ${e.message}"
    }

    private suspend fun runSearch(id: String, d: Map<String, Any?>) {
        val ref = db.collection("searches").document(id)
        try {
            ref.update(mapOf("status" to "running", "claimedBy" to "local")).await()
            val sources = Sources.instance?.list().orEmpty()
            if (sources.isEmpty()) throw Exception("Источник поиска не подключён — добавьте его в «Настройка с телефона»")
            val args = JSONObject()
                .put("phrase", d["phrase"] as? String ?: "")
                .put("days", d["days"] ?: JSONObject.NULL)
                .put("order", d["order"] ?: 10)
                .put("categories", JSONArray((d["categories"] as? List<*>) ?: emptyList<Any>()))
            val results = mutableListOf<Map<String, Any?>>()
            val errors = mutableListOf<String>()
            for (src in sources) {
                val sid = src.optString("id")
                val name = src.optString("name", sid)
                try {
                    val arr = Sources.instance!!.call(sid, "search", args) as? JSONArray ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("tid").isEmpty()) continue
                        // tid carries the source so the download goes back to the same plugin.
                        o.put("tid", "$sid:${o.optString("tid")}").put("source", name)
                        results.add(LocalDb.fromJson(o))
                    }
                } catch (e: Exception) {
                    Log.e("search[$sid]: ${e.message}")
                    errors.add(sourceError(name, e))
                }
            }
            if (results.isEmpty() && errors.isNotEmpty()) throw Exception(errors.joinToString("; "))
            ref.update(mapOf("status" to "done", "results" to results.take(60), "count" to results.size,
                "finishedAt" to FieldValue.serverTimestamp())).await()
            Log.i("search: «${d["phrase"]}» — найдено ${results.size}")
        } catch (e: Exception) {
            ref.update(mapOf("status" to "error", "error" to (e.message ?: "ошибка поиска")))
        } finally {
            claimed.remove(id)
            pruneSearches()
        }
    }

    // Finished searches are only read once by the waiting request — keep the store small.
    private suspend fun pruneSearches() {
        val cutoff = System.currentTimeMillis() - 3600_000L
        db.collection("searches").get().await().documents.forEach {
            val at = (it.data["createdAt"] as? Number)?.toLong() ?: 0
            if (at < cutoff && it.data["status"] != "running") it.reference.delete()
        }
    }

    private suspend fun fetchTorrent(id: String, d: Map<String, Any?>) {
        val ref = db.collection("downloads").document(id)
        try {
            ref.update(mapOf("status" to "fetching", "fetchedBy" to "local")).await()
            val tid = d["tid"] as? String ?: throw Exception("нет tid")
            val sid = tid.substringBefore(':', "")
            if (sid.isEmpty()) throw Exception("неизвестный источник раздачи")
            val name = Sources.instance?.get(sid)?.optString("name") ?: sid
            val b64 = try {
                Sources.instance?.call(sid, "download", JSONObject().put("tid", tid.substringAfter(':'))) as? String
            } catch (e: Exception) { throw Exception(sourceError(name, e)) }
            if (b64.isNullOrEmpty()) throw Exception("«$name» не отдал .torrent")
            ref.update(mapOf("torrentFile" to b64.replace(Regex("\\s"), ""), "status" to "fetched")).await()
            Log.i("download: .torrent от «$name» получен")
        } catch (e: Exception) {
            Log.e("download fetch: ${e.message}")
            ref.update(mapOf("status" to "error", "error" to (e.message ?: "ошибка")))
        } finally { claimed.remove(id) }
    }

    // The key was just entered on the phone: everything skipped so far gets its turn.
    fun kick() {
        scope.launch {
            lib.get().await().documents.forEach { if (needsEnrich(it.data)) enqueue(it.id) }
        }
    }

    private fun enqueue(id: String) { if (queued.add(id)) queue.trySend(id) }

    private fun needsEnrich(d: Map<String, Any?>): Boolean {
        val v = (d["enrichV"] as? Number)?.toInt() ?: 0
        if ((d["tmdbId"] != null || d["tmdbTried"] == true) && v >= ENRICH_V) return false
        return !(d["title"] as? String).isNullOrEmpty()
    }

    private suspend fun worker() {
        for (id in queue) {
            queued.remove(id)
            if (tmdbKey().isEmpty()) continue           // kick() re-queues once a key is set
            try { enrichOne(id) } catch (e: ApiKeyException) {
                Log.e("enrich: ${e.message}")
            } catch (e: Exception) {
                Log.e("enrich $id: ${e.message}")
                lib.document(id).update(mapOf("tmdbTried" to true, "enrichV" to ENRICH_V))
            }
            delay(150)   // stay far below TMDb's rate limit on a library scan
        }
    }

    private suspend fun enrichOne(id: String) {
        val snap = lib.document(id).get().await()
        val after = snap.data ?: return
        if (!needsEnrich(after)) return
        val title = after["title"] as String
        val year = (after["year"] as? Number)?.toInt()
        val isSeries = after["type"] == "series" || after["season"] != null || after["episode"] != null
        val kind = if (isSeries) "tv" else "movie"
        val known = (after["tmdbId"] as? Number)?.toLong()
        val m = if (known != null) detailsMemo(kind, known, year) else enrich(title, year, isSeries)
        if (m == null) {
            lib.document(id).update(mapOf("tmdbTried" to true, "enrichV" to ENRICH_V)).await()
            Log.i("enrich: $title — не найдено")
            return
        }
        val payload = payload(m)
        if (m.str("imdbId").isNotEmpty() && after["imdbRating"] == null) {
            Omdb.rating(LocalSettings.omdbKey(ctx), m.str("imdbId"))?.let { (r, v) ->
                payload["imdbRating"] = r; payload["imdbVotes"] = v
            }
        }
        lib.document(id).update(payload).await()
        Log.i("enrich: $title → ${m.str("title")} (${m.opt("year")})")
    }

    private fun detailsMemo(kind: String, id: Long, year: Int?): JSONObject {
        val key = "${kind}_$id"
        synchronized(memo) {
            memo[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < 10 * 60_000) return v }
        }
        val v = details(kind, id, year)
        synchronized(memo) {
            memo[key] = System.currentTimeMillis() to v
            if (memo.size > 50) memo.remove(memo.keys.first())
        }
        return v
    }

    private fun enrich(title: String, year: Int?, isSeries: Boolean): JSONObject? {
        val kind = if (isSeries) "tv" else "movie"
        val q = "search/$kind?include_adult=false&query=${enc(title)}" +
            (if (year != null) (if (kind == "tv") "&first_air_date_year=$year" else "&year=$year") else "")
        var results = tmdb(q).optJSONArray("results") ?: JSONArray()
        // A year in a file name is often off by one ("1961" vs TMDb 1962) — retry without it.
        if (results.length() == 0 && year != null)
            results = tmdb("search/$kind?include_adult=false&query=${enc(title)}").optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return null
        var top = results.getJSONObject(0)
        if (year != null) {
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val d = if (kind == "tv") r.str("first_air_date") else r.str("release_date")
                if (d.startsWith(year.toString())) { top = r; break }
            }
        }
        return details(kind, top.getLong("id"), year)
    }

    private fun names(arr: JSONArray?, take: Int, field: String = "name"): List<String> =
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it)?.str(field)?.ifEmpty { null } }.take(take)

    private fun objs(arr: JSONArray?): List<JSONObject> =
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }

    private fun pickTrailer(videos: JSONArray?): String? {
        val yt = objs(videos).filter { it.str("site") == "YouTube" && it.str("key").isNotEmpty() }
        fun score(v: JSONObject) = (when (v.str("type")) { "Trailer" -> 20; "Teaser" -> 10; else -> 0 }) +
            (if (v.optBoolean("official")) 2 else 0) + (if (v.str("iso_639_1") == "ru") 1 else 0)
        return yt.maxByOrNull { score(it) }?.str("key")
    }

    private fun pickAgeRating(kind: String, d: JSONObject): String? {
        val usMovie = mapOf("G" to "0+", "PG" to "6+", "PG-13" to "13+", "R" to "17+", "NC-17" to "18+")
        val usTv = mapOf("TV-Y" to "0+", "TV-Y7" to "7+", "TV-G" to "0+", "TV-PG" to "10+", "TV-14" to "14+", "TV-MA" to "18+")
        fun pick(cc: String): String? = if (kind == "tv") {
            objs(d.optJSONObject("content_ratings")?.optJSONArray("results"))
                .firstOrNull { it.str("iso_3166_1") == cc }?.str("rating")?.ifEmpty { null }
        } else {
            objs(d.optJSONObject("release_dates")?.optJSONArray("results"))
                .firstOrNull { it.str("iso_3166_1") == cc }
                ?.let { r -> objs(r.optJSONArray("release_dates")).map { it.str("certification") }.firstOrNull { it.isNotEmpty() } }
        }
        pick("RU")?.let { return it }
        val us = pick("US") ?: return null
        return (if (kind == "tv") usTv[us] else usMovie[us])
    }

    // Full record for a known tmdbId — the field set the TV page expects (see enrichPayload).
    private fun details(kind: String, id: Long, fallbackYear: Int?): JSONObject {
        val d = tmdb("$kind/$id?append_to_response=credits,images,videos,external_ids,keywords,release_dates,content_ratings" +
            "&include_image_language=ru,en,null&include_video_language=ru,en,null")
        val date = if (kind == "tv") d.str("first_air_date") else d.str("release_date")
        val images = d.optJSONObject("images")
        val backdrops = objs(images?.optJSONArray("backdrops")).take(6).map { it.str("file_path") }
        val ruPosters = objs(images?.optJSONArray("posters")).filter { it.str("iso_639_1") == "ru" }
            .sortedByDescending { it.optDouble("vote_average", 0.0) }
        val poster = ruPosters.firstOrNull()?.str("file_path") ?: d.s("poster_path")
        val credits = d.optJSONObject("credits")
        val crew = objs(credits?.optJSONArray("crew"))
        val cast = objs(credits?.optJSONArray("cast"))

        var collection: JSONObject? = null
        d.optJSONObject("belongs_to_collection")?.let { b ->
            collection = JSONObject().put("id", b.optLong("id")).put("name", b.str("name"))
                .put("poster", b.s("poster_path"))
            try {
                val c = tmdb("collection/${b.optLong("id")}")
                collection!!.put("overview", c.str("overview"))
                if (collection!!.isNull("poster")) collection!!.put("poster", c.s("poster_path"))
                collection!!.put("parts", JSONArray(objs(c.optJSONArray("parts")).map { p ->
                    JSONObject().put("tmdbId", p.optLong("id")).put("title", p.str("title"))
                        .put("year", p.str("release_date").take(4).toIntOrNull())
                        .put("poster", p.s("poster_path"))
                        .put("rating", p.optDouble("vote_average", 0.0))
                        .put("overview", p.str("overview"))
                }))
            } catch (_: Exception) {}
        }
        val directors = if (kind == "tv") objs(d.optJSONArray("created_by")) else crew.filter { it.str("job") == "Director" }
        val kw = d.optJSONObject("keywords")
        return JSONObject()
            .put("tmdbId", d.optLong("id"))
            .put("catalogId", "${kind}_${d.optLong("id")}")
            .put("animation", objs(d.optJSONArray("genres")).any { it.optInt("id") == 16 })
            .put("collection", collection ?: JSONObject.NULL)
            .put("title", if (kind == "tv") d.str("name") else d.str("title"))
            .put("year", date.take(4).toIntOrNull() ?: fallbackYear ?: JSONObject.NULL)
            .put("poster", poster ?: JSONObject.NULL)
            .put("backdrop", d.s("backdrop_path") ?: JSONObject.NULL)
            .put("backdrops", JSONArray(backdrops))
            .put("overview", d.str("overview"))
            .put("rating", d.optDouble("vote_average", 0.0))
            .put("cast", JSONArray(cast.take(8).map { it.str("name") }))
            .put("castX", JSONArray(cast.take(10).map {
                JSONObject().put("n", it.str("name")).put("c", it.str("character"))
                    .put("p", it.s("profile_path") ?: JSONObject.NULL)
            }))
            .put("genres", JSONArray(names(d.optJSONArray("genres"), 20)))
            .put("director", directors.map { it.str("name") }.take(2).joinToString(", "))
            .put("directorsX", JSONArray(directors.take(2).map {
                JSONObject().put("n", it.str("name")).put("p", it.s("profile_path") ?: JSONObject.NULL)
            }))
            .put("writers", crew.filter { it.str("job") in setOf("Screenplay", "Writer", "Story") }
                .map { it.str("name") }.distinct().take(3).joinToString(", "))
            .put("country", names(d.optJSONArray("production_countries"), 2).joinToString(", "))
            .put("studio", names(d.optJSONArray("production_companies"), 2).joinToString(", "))
            .put("keywords", JSONArray(names(kw?.optJSONArray("keywords") ?: kw?.optJSONArray("results"), 20)))
            .put("premiered", date.ifEmpty { null } ?: JSONObject.NULL)
            .put("votes", d.optLong("vote_count", 0))
            .put("tagline", d.str("tagline"))
            .put("runtime", d.optInt("runtime", 0).takeIf { it > 0 }
                ?: d.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0)
            .put("trailer", pickTrailer(d.optJSONObject("videos")?.optJSONArray("results")) ?: JSONObject.NULL)
            .put("budget", d.optLong("budget", 0))
            .put("revenue", d.optLong("revenue", 0))
            .put("imdbId", d.optJSONObject("external_ids")?.str("imdb_id")?.ifEmpty { null } ?: JSONObject.NULL)
            .put("ageRating", pickAgeRating(kind, d) ?: JSONObject.NULL)
    }

    private fun payload(m: JSONObject): HashMap<String, Any?> {
        val out = HashMap(LocalDb.fromJson(m))
        out["enrichV"] = ENRICH_V
        return out
    }

    // ---- TV page endpoints (/api/movie, /api/person) ----------------------------------------
    fun movie(kind: String, tmdbId: String): JSONObject {
        val m = details(if (kind == "tv") "tv" else "movie", tmdbId.toLong(), null)
        if (m.str("imdbId").isNotEmpty())
            Omdb.rating(LocalSettings.omdbKey(ctx), m.str("imdbId"))?.let { (r, v) ->
                m.put("imdbRating", r).put("imdbVotes", v)
            }
        return m
    }

    fun person(name: String): JSONObject? {
        val top = tmdb("search/person?include_adult=false&query=${enc(name)}").optJSONArray("results")?.optJSONObject(0)
            ?: return null
        val pid = top.optLong("id")
        val d = tmdb("person/$pid?append_to_response=combined_credits")
        var bio = d.str("biography")
        if (bio.isEmpty()) bio = runCatching { tmdb("person/$pid?language=en-US").str("biography") }.getOrDefault("")
        val deathYear = d.str("deathday").take(4).toIntOrNull()
        val cc = d.optJSONObject("combined_credits")
        val seen = LinkedHashMap<String, JSONObject>()
        for (c in objs(cc?.optJSONArray("cast")) + objs(cc?.optJSONArray("crew"))) {
            val mt = c.str("media_type")
            if (mt != "movie" && mt != "tv") continue
            val job = c.str("job")
            if (job.isNotEmpty() && job != "Director") continue
            val genres = c.optJSONArray("genre_ids")
            val isDoc = (0 until (genres?.length() ?: 0)).any { genres!!.optInt(it) == 99 }
            val character = c.str("character")
            if (job.isEmpty() && (SELF_RE.containsMatchIn(character) || (isDoc && character.isEmpty()))) continue
            val date = if (mt == "tv") c.str("first_air_date") else c.str("release_date")
            val cy = date.take(4).toIntOrNull()
            if (deathYear != null && cy != null && cy > deathYear + 2) continue
            val key = "${mt}_${c.optLong("id")}"
            val e = seen.getOrPut(key) {
                JSONObject().put("tmdbId", c.optLong("id")).put("kind", mt)
                    .put("title", if (mt == "tv") c.str("name") else c.str("title"))
                    .put("year", cy ?: JSONObject.NULL)
                    .put("poster", c.s("poster_path") ?: JSONObject.NULL)
                    .put("rating", c.optDouble("vote_average", 0.0))
                    .put("votes", c.optLong("vote_count", 0))
                    .put("overview", c.str("overview"))
                    .put("roles", JSONArray())
            }
            val role = character.ifEmpty { if (job == "Director") "режиссёр" else "" }
            val roles = e.getJSONArray("roles")
            if (role.isNotEmpty() && (0 until roles.length()).none { roles.optString(it) == role }) roles.put(role)
        }
        return JSONObject()
            .put("name", d.str("name").ifEmpty { name })
            .put("biography", bio)
            .put("birthday", d.s("birthday") ?: JSONObject.NULL)
            .put("deathday", d.s("deathday") ?: JSONObject.NULL)
            .put("place", d.str("place_of_birth"))
            .put("photo", d.s("profile_path") ?: JSONObject.NULL)
            .put("credits", JSONArray(seen.values.toList()))
    }
}
