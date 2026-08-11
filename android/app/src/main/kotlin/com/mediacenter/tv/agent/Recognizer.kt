// File-name parsing: a compact port of what parse-torrent-title did for the Node agent.
// Only title/year/SxxEyy are needed — content TYPE always comes from the folder, and the real
// TMDb recognition happens on the server (Cloud Function enrichLibrary).
package com.mediacenter.tv.agent

data class ParsedName(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?
) { val isSeries: Boolean get() = season != null || episode != null }

object Recognizer {
    private val EXT = Regex(
        "\\.(mkv|mp4|avi|mov|wmv|m4v|mpg|mpeg|mpe|mpv|ts|m2ts|3gp|ogv|vob|flv|webm)$",
        RegexOption.IGNORE_CASE
    )
    private val SEASON_EP = Regex("\\b[Ss](\\d{1,2})\\s?[Ee](\\d{1,3})\\b")
    private val EP_X = Regex("\\b(\\d{1,2})x(\\d{1,3})\\b")
    private val YEAR = Regex("[\\[( .]((?:19|20)\\d{2})[\\]) .]?")
    // Release tags after which nothing belongs to the title
    private val CUT = Regex(
        "\\b(1080p|720p|2160p|480p|4k|uhd|bdrip|brrip|bluray|blu-ray|webrip|web-dl|webdl|hdrip|" +
        "dvdrip|dvd|hdtv|camrip|x264|x265|h264|h265|hevc|avc|aac|ac3|dts|mvo|dvo|avo|licence|" +
        "лицензия|дубляж|remux|proper|extended|unrated|imax|10bit|hdr)\\b",
        RegexOption.IGNORE_CASE
    )

    fun parse(fileName: String): ParsedName {
        var s = fileName.replace(EXT, "")

        var season: Int? = null
        var episode: Int? = null
        SEASON_EP.find(s)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            s = s.substring(0, m.range.first)
        } ?: EP_X.find(s)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            s = s.substring(0, m.range.first)
        }

        var year: Int? = null
        // Prefer the LAST year-looking token: "2001: A Space Odyssey (1968)" → 1968
        YEAR.findAll(s).lastOrNull()?.let { m ->
            year = m.groupValues[1].toIntOrNull()
            s = s.substring(0, m.range.first)
        }

        CUT.find(s)?.let { m -> s = s.substring(0, m.range.first) }

        val title = s
            .replace(Regex("[._]+"), " ")
            .replace(Regex("[\\[\\]()]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '.', ',')
        return ParsedName(title.ifEmpty { fileName }, year, season, episode)
    }
}
