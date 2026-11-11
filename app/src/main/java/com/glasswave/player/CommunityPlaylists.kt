// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Playlists people made and shared ("Community playlists" in search).
 *
 * YouTube Music's own community search is mostly beat packs, instrumentals, kids' songs and sped-up
 * edits, and stops at 20. So it's asked together with regular YouTube's playlist search (which finds
 * the playlists of real songs people make, like "Drake greatest hits" or "Best of 90s R&B"), the two
 * are joined, and the junk is left out unless it's what you searched for. Each one says who made it
 * and how many songs it has or how many times it's been played.
 */
object CommunityPlaylists {
    /** Left out unless the search asks for it. */
    private val junk = listOf(
        "type ?beats?", "instrumentals?", "free\\b.*\\bbeats?", "for profit", "\\(sold\\)", "kids?", "children", "nursery",
        "bible", "karaoke", "slowed", "reverb", "sped ?up", "speed ?up", "nightcore", "8d", "affirmations?", "manifest\\w*",
        "meditation", "speech(es)?", "podcasts?", "birthday", "worship", "lo-?fi", "sleep", "study", "white noise", "remix(es)?",
        "covers?", "lessons?", "episodes?", "tutorials?", "reactions?", "bgm", "no copyright", "quotes?", "asmr",
    ).map { Regex("\\b$it\\b", RegexOption.IGNORE_CASE) }

    fun isJunk(text: String, query: String): Boolean = junk.any { it.containsMatchIn(text) && !it.containsMatchIn(query) }

    /** [music] is YouTube Music's own community search; regular YouTube's is asked at the same time. */
    suspend fun search(query: String, music: suspend () -> List<MusicItem>): List<Section> = coroutineScope {
        val tube = async(Dispatchers.IO) { runCatching { youtube(query) }.getOrDefault(emptyList()) }
        val fromMusic = runCatching { music() }.getOrDefault(emptyList()).filterIsInstance<PlaylistItem>()
            .map { it.copy(subtitle = it.subtitle.replace(" • ", " · ")) }
            // The most played first.
            .sortedByDescending { plays(it.subtitle) }
        val fromTube = tube.await()
        // Taking turns, so both kinds show near the top; each playlist once.
        val seen = HashSet<String>()
        val all = mutableListOf<PlaylistItem>()
        for (i in 0 until maxOf(fromMusic.size, fromTube.size)) {
            for (p in listOfNotNull(fromMusic.getOrNull(i), fromTube.getOrNull(i))) {
                if (seen.add(p.browseId.removePrefix("VL"))) all += p
            }
        }
        val kept = all.filterNot { isJunk(it.title + " " + it.subtitle.substringBefore(" · "), query) }
        if (kept.isEmpty()) emptyList() else listOf(Section("Community playlists", kept))
    }

    /** Home's "Playlists for you": people's playlists around [artists] (a few each, taking turns). */
    suspend fun forArtists(artists: List<String>): List<PlaylistItem> = coroutineScope {
        val lists = artists.map { a ->
            async(Dispatchers.IO) {
                runCatching { youtube(a) }.getOrDefault(emptyList())
                    // About the artist (in the name), and not junk.
                    .filter { it.title.contains(a, ignoreCase = true) && !isJunk(it.title, a) }
                    .take(6)
            }
        }.map { it.await() }
        (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.distinctBy { it.browseId }
    }

    private val playsRx = Regex("""([\d.,]+)\s*([KMB])?\s+(views|plays)""", RegexOption.IGNORE_CASE)

    private fun plays(text: String): Long {
        val m = playsRx.find(text) ?: return 0
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        return (n * when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }).toLong()
    }

    private val countRx = Regex("""([\d,]+)\s+(videos|episodes|lessons)""")

    /** Regular YouTube's playlist search: people's own playlists (not YouTube Music's, which the other filter has). */
    private fun youtube(query: String): List<PlaylistItem> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", "2.20251015.01.00").put("hl", "en")))
            .put("query", query)
            .put("params", "EgIQAw==") // Playlists only.
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", YtDownloader.USER_AGENT)
            .build()
        val json = YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            JSONObject(r.body?.string() ?: return emptyList())
        }
        return collect(json, "lockupViewModel").mapNotNull { l ->
            val id = l.optString("contentId")
            // PL: someone's playlist. (RDCLAK: YouTube Music's own; RD: an endless mix.)
            if (!id.startsWith("PL")) return@mapNotNull null
            val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return@mapNotNull null
            val title = meta.optJSONObject("title")?.optString("content").orEmpty().ifEmpty { return@mapNotNull null }
            val by = (meta.path("metadata.contentMetadataViewModel.metadataRows.0.metadataParts.0.text.content") as? String).orEmpty().trim()
            val image = l.optJSONObject("contentImage")
            // "49 videos" on the cover; podcasts and courses ("episodes", "lessons") aren't music.
            val count = countRx.find(badgeText(image))
            if (count != null && count.groupValues[2] != "videos") return@mapNotNull null
            val songs = count?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            // Two songs or a few thousand videos aren't really playlists to listen to.
            if (songs != null && songs !in 5..2000) return@mapNotNull null
            val sources = (image?.path("collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel.image.sources") as? org.json.JSONArray)
            val thumb = sources?.let { s -> (0 until s.length()).mapNotNull { s.optJSONObject(it) }.maxByOrNull { it.optInt("width") }?.optString("url") }
            val line = listOfNotNull(by.ifEmpty { null }, songs?.let { if (it == 1) "1 song" else "$it songs" }).joinToString(" · ")
            PlaylistItem("VL$id", title, line, thumb)
        }
    }

    /** Every "text" string inside [node], joined (the cover's badge, like "49 videos"). */
    private fun badgeText(node: Any?): String = buildString {
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> n.keys().forEach { k -> val v = n.opt(k); if (k == "text" && v is String) append(v).append(' ') else walk(v) }
                is org.json.JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(node)
    }
}
