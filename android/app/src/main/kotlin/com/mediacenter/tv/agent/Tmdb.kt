// TMDb and OMDb clients for standalone mode, with the user's own keys (in cloud mode only the
// server talks to TMDb). A TMDb key is either the short v3 "API key" or the long v4 "API Read
// Access Token" (starts with eyJ) — the account page shows both, users paste either.
package com.mediacenter.tv.agent

import org.json.JSONObject
import java.net.URLEncoder

class ApiKeyException(msg: String) : Exception(msg)

object Tmdb {
    const val LANG = "ru-RU"

    fun get(key: String, pathAndQuery: String): JSONObject {
        if (key.isEmpty()) throw ApiKeyException("TMDb key is not set")
        val sep = if (pathAndQuery.contains('?')) "&" else "?"
        // Do not add language= when the query already sets it explicitly (include_image_language=
        // is a different parameter — a naive contains() matched it and re-enriched in English).
        var url = "https://api.themoviedb.org/3/$pathAndQuery" +
            (if (Regex("[?&]language=").containsMatchIn(pathAndQuery)) "" else "${sep}language=$LANG")
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        if (key.startsWith("eyJ")) headers["Authorization"] = "Bearer $key"
        else url += "&api_key=" + URLEncoder.encode(key, "UTF-8")
        val r = httpFetch(url, headers = headers, timeoutMs = 20000)
        if (r.status == 401) throw ApiKeyException("TMDb rejected the key (401)")
        if (r.status >= 400) throw Exception("TMDb ${r.status}")
        return JSONObject(String(r.body, Charsets.UTF_8))
    }

    // null = key works; otherwise a message for the setup page.
    fun check(key: String): String? = try {
        get(key.trim(), "configuration"); null
    } catch (e: ApiKeyException) {
        "TMDb не принял ключ — проверьте, что скопирован целиком"
    } catch (e: Exception) {
        "Нет связи с TMDb: ${e.message}"
    }
}

object Omdb {
    // IMDb rating + votes for an imdb id, or null (no key, unknown title, quota exhausted).
    fun rating(key: String, imdbId: String): Pair<Double, Long>? {
        if (key.isEmpty() || imdbId.isEmpty()) return null
        return try {
            val r = httpFetch("https://www.omdbapi.com/?i=${URLEncoder.encode(imdbId, "UTF-8")}&apikey=" +
                URLEncoder.encode(key, "UTF-8"), timeoutMs = 15000)
            if (r.status != 200) return null
            val d = JSONObject(String(r.body, Charsets.UTF_8))
            val rating = d.optString("imdbRating").toDoubleOrNull() ?: return null
            if (rating <= 0) return null
            rating to (d.optString("imdbVotes").replace(Regex("\\D"), "").toLongOrNull() ?: 0L)
        } catch (_: Exception) { null }
    }

    fun check(key: String): String? = try {
        val r = httpFetch("https://www.omdbapi.com/?i=tt0111161&apikey=" +
            URLEncoder.encode(key.trim(), "UTF-8"), timeoutMs = 15000)
        val d = JSONObject(String(r.body, Charsets.UTF_8))
        if (d.optString("Response") == "True") null
        else when {
            d.optString("Error").contains("activated", true) ->
                "Ключ ещё не активирован — откройте ссылку из письма OMDb"
            else -> "OMDb не принял ключ: ${d.optString("Error")}"
        }
    } catch (e: Exception) { "Нет связи с OMDb: ${e.message}" }
}
