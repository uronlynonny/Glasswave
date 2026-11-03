// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/**
 * YouTube Music's own web API: song search, suggestions and "radio" (songs like this one, for Up next).
 * Trimmed from the phone app's YtMusicApi.kt. No account is used, so nothing goes into anyone's history.
 */
object YtMusic {
    private const val ORIGIN = "https://music.youtube.com"
    private const val CLIENT_VERSION = "1.20251015.01.00"
    private const val SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

    private suspend fun call(endpoint: String, body: JSONObject): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "en")))
        val (code, text) = Net.postJson(
            "$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false",
            body.toString(),
            mapOf("X-Origin" to ORIGIN, "Origin" to ORIGIN, "Referer" to "$ORIGIN/"),
        )
        if (code !in 200..299) throw HttpError(code, "YouTube Music replied $code")
        return JSONObject(text)
    }

    private val durationRx = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

    private fun pageType(endpoint: Any?): String = findString(endpoint, "pageType") ?: ""

    /** Bigger artwork than the tiny default. */
    private fun sized(url: String?, px: Int): String? = url?.replace(Regex("=w\\d+-h\\d+"), "=w$px-h$px")

    /** Artist names and the length from a "Song • Artist • Album • 3:45" style line. */
    private fun artistAndLength(line: Any?): Pair<String, Long> {
        val runs = (line as? JSONObject)?.optJSONArray("runs") ?: return "" to 0L
        val artists = mutableListOf<String>()
        var seconds = 0L
        val plain = mutableListOf<String>()
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            val text = r.optString("text").trim()
            if (text.isEmpty() || text == "•" || text == "&" || text == ",") continue
            when {
                durationRx.matches(text) -> seconds = parseDuration(text)
                pageType(r.opt("navigationEndpoint")).contains("ARTIST") -> artists += text
                r.opt("navigationEndpoint") == null -> plain += text
            }
        }
        val artist = artists.joinToString(", ").ifEmpty {
            plain.firstOrNull { it !in setOf("Song", "Video", "Single", "EP", "Album") && !it.contains("views") && !it.contains("plays") } ?: ""
        }
        return artist to seconds
    }

    private fun isExplicit(o: JSONObject): Boolean =
        collect(o, "musicInlineBadgeRenderer").any { findString(it, "iconType") == "MUSIC_EXPLICIT_BADGE" }

    private val cleanTag = Regex("\\s*[(\\[]?\\s*(clean( version)?|edited|radio edit|censored)\\s*[)\\]]?\\s*$", RegexOption.IGNORE_CASE)

    /** Songs for [query], best first. Clean versions give way to the explicit original, like the phone app. */
    suspend fun searchSongs(query: String): List<Track> {
        val json = call("search", JSONObject().put("query", query).put("params", SONGS))
        val out = mutableListOf<Track>()
        for (o in collect(json, "musicResponsiveListItemRenderer")) {
            val title = ytText(o.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text"))
            val line = o.path("flexColumns.1.musicResponsiveListItemFlexColumnRenderer.text")
            val videoId = (o.path("playlistItemData.videoId") as? String)
                ?: (o.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.watchEndpoint.videoId") as? String)
                ?: continue
            val (artist, seconds) = artistAndLength(line)
            out += Track(videoId, title.ifEmpty { "Unknown" }, artist, seconds, sized(lastThumb(o.opt("thumbnail")), 226), isExplicit(o))
        }
        // The same song clean and explicit: keep the explicit one.
        fun key(t: Track) = t.title.replace(cleanTag, "").lowercase() + "|" + t.artist.lowercase()
        val explicitKeys = out.filter { it.explicit }.map { key(it) }.toSet()
        return out.filterNot { !it.explicit && key(it) in explicitKeys }.distinctBy { it.videoId }
    }

    /** YouTube Music's search suggestions while typing. */
    suspend fun suggestions(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val json = call("music/get_search_suggestions", JSONObject().put("input", input))
        return collect(json, "searchSuggestionRenderer").map { ytText(it.opt("suggestion")) }.filter { it.isNotBlank() }.distinct().take(8)
    }

    /** True for a song, false for a music video or upload, null when YouTube didn't say. */
    private fun isSong(r: JSONObject): Boolean? =
        (r.path("navigationEndpoint.watchEndpoint.watchEndpointMusicSupportedConfigs.watchEndpointMusicConfig.musicVideoType") as? String)
            ?.let { it == "MUSIC_VIDEO_TYPE_ATV" }

    /** Songs similar to [videoId] (YouTube Music's "radio" for it), for Up next. Leaves out the song itself. */
    suspend fun radio(videoId: String): List<Track> {
        val json = call(
            "next",
            JSONObject().put("videoId", videoId).put("playlistId", "RDAMVM$videoId").put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true),
        )
        val out = LinkedHashMap<String, Track>()
        for (r in collect(json, "playlistPanelVideoRenderer")) {
            val id = r.optString("videoId")
            if (id.isEmpty() || id == videoId) continue
            if (isSong(r) == false) continue
            val title = ytText(r.opt("title"))
            if (title.isEmpty()) continue
            val (artist, _) = artistAndLength(r.opt("longBylineText") ?: r.opt("shortBylineText"))
            val seconds = parseDuration(ytText(r.opt("lengthText")))
            out.getOrPut(id) { Track(id, title, artist.ifEmpty { ytText(r.opt("shortBylineText")) }, seconds, sized(lastThumb(r.opt("thumbnail")), 226)) }
        }
        return out.values.toList()
    }
}
