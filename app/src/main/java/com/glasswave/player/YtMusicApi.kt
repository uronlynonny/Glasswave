// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SignInExpired(service: String) : IOException("$service sign-in expired")

/**
 * Talks to YouTube Music's own web API: home feed, search, album / artist / playlist pages,
 * and (when signed in) your library. Works without signing in too; signing in personalizes the home feed.
 * If YouTube changes its replies, this file is where fixes go.
 */
object YtMusicApi {
    private const val ORIGIN = "https://music.youtube.com"
    private const val CLIENT_VERSION = "1.20251015.01.00"
    private val JSON = "application/json".toMediaType()

    enum class Filter(val label: String, val params: String?) {
        ALL("All", null),
        SONGS("Songs", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
        ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
        ARTISTS("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
        PLAYLISTS("Featured playlists", "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU%3D"),
        COMMUNITY("Community playlists", "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"),
    }

    /* ---------------- Requests ---------------- */

    private fun cookieValue(cookie: String, name: String): String? =
        cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun authHeader(cookie: String): String? {
        val sapisid = cookieValue(cookie, "SAPISID") ?: cookieValue(cookie, "__Secure-3PAPISID") ?: return null
        val ts = System.currentTimeMillis() / 1000
        val sha = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$sha"
    }

    /**
     * One API call. [requireAuth] is for your library; other calls use your sign-in if there is one,
     * and quietly retry signed-out if the sign-in has expired.
     */
    private fun call(endpoint: String, body: JSONObject, query: String = "", requireAuth: Boolean = false): JSONObject {
        val cookie = Accounts.ytCookie
        if (requireAuth && cookie == null) throw SignInExpired("YouTube Music")
        body.put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", CLIENT_VERSION)
                    .put("hl", "en"),
            ),
        )
        fun send(withAuth: Boolean): Pair<Int, String> {
            val b = Request.Builder()
                .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false$query")
                .post(body.toString().toRequestBody(JSON))
                .header("User-Agent", YtDownloader.USER_AGENT)
                .header("X-Origin", ORIGIN)
                .header("Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
            if (withAuth && cookie != null) {
                b.header("Cookie", cookie).header("X-Goog-AuthUser", "0")
                authHeader(cookie)?.let { b.header("Authorization", it) }
            }
            YtRepo.http.newCall(b.build()).execute().use { r -> return r.code to (r.body?.string() ?: "{}") }
        }
        var (code, text) = send(true)
        if ((code == 401 || code == 403) && cookie != null) {
            if (requireAuth) throw SignInExpired("YouTube Music")
            val retry = send(false)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) throw IOException("YouTube Music replied $code")
        return JSONObject(text)
    }

    private fun browse(body: JSONObject, query: String = "", requireAuth: Boolean = false) =
        call("browse", body, query, requireAuth)

    /** Next page token. The Boolean says whether it's the newer kind that goes in the request body. */
    private fun nextPage(node: Any?): Pair<String, Boolean>? {
        collect(node, "continuationCommand").firstOrNull()?.optString("token")?.takeIf { it.isNotEmpty() }
            ?.let { return it to true }
        collect(node, "nextContinuationData").firstOrNull()?.optString("continuation")?.takeIf { it.isNotEmpty() }
            ?.let { return it to false }
        return null
    }

    private fun more(endpoint: String, token: Pair<String, Boolean>, requireAuth: Boolean = false): JSONObject =
        if (token.second) call(endpoint, JSONObject().put("continuation", token.first), requireAuth = requireAuth)
        else call(endpoint, JSONObject(), "&ctoken=${token.first}&continuation=${token.first}&type=next", requireAuth)

    /** Bigger artwork than the tiny default. */
    private fun sized(url: String?, px: Int): String? =
        url?.replace(Regex("=w\\d+-h\\d+"), "=w$px-h$px")

    /* ---------------- Parsing ---------------- */

    private val durationRx = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

    private fun pageType(endpoint: Any?): String =
        findString(endpoint, "pageType") ?: ""

    /** Artist names and the length from a "Song • Artist • Album • 3:45" style line. */
    private fun artistAndLength(line: Any?): Pair<String, Long> {
        val runs = (line as? JSONObject)?.optJSONArray("runs") ?: return "" to 0
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

    private fun songFrom(videoId: String, title: String, line: Any?, thumb: String?, fallbackArtist: String = "", explicit: Boolean = false): SongItem {
        val (artist, seconds) = artistAndLength(line)
        val a = artist.ifEmpty { fallbackArtist }
        return SongItem(Track(ytWatchUrl(videoId), title.ifEmpty { "Unknown" }, a, seconds, thumb), a, explicit)
    }

    /** YouTube Music's "E" badge: the explicit (uncensored) version. */
    private fun isExplicit(o: JSONObject): Boolean =
        collect(o, "musicInlineBadgeRenderer").any { findString(it, "iconType") == "MUSIC_EXPLICIT_BADGE" }

    private fun item(o: JSONObject, kind: String): MusicItem? = item0(o, kind)?.let { i ->
        if (!isExplicit(o)) i else when (i) {
            is SongItem -> i.copy(explicit = true)
            is AlbumItem -> i.copy(explicit = true)
            else -> i
        }
    }

    private fun item0(o: JSONObject, kind: String): MusicItem? = when (kind) {
        "musicTwoRowItemRenderer" -> {
            val title = ytText(o.opt("title"))
            val subtitle = ytText(o.opt("subtitle"))
            val thumb = sized(lastThumb(o.opt("thumbnailRenderer")), 400)
            val nav = o.optJSONObject("navigationEndpoint")
            val videoId = nav?.path("watchEndpoint.videoId") as? String
            val browseId = nav?.path("browseEndpoint.browseId") as? String
            when {
                videoId != null -> songFrom(videoId, title, o.opt("subtitle"), thumb)
                browseId != null -> typed(browseId, pageType(nav), title, subtitle, thumb)
                else -> null
            }
        }
        "musicResponsiveListItemRenderer" -> {
            val title = ytText(o.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text"))
            val line = o.path("flexColumns.1.musicResponsiveListItemFlexColumnRenderer.text")
            val thumb = sized(lastThumb(o.opt("thumbnail")), 226)
            val videoId = (o.path("playlistItemData.videoId") as? String)
                ?: (o.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.watchEndpoint.videoId") as? String)
                ?: findString(o.opt("overlay"), "videoId")
            val nav = o.optJSONObject("navigationEndpoint")
            val browseId = nav?.path("browseEndpoint.browseId") as? String
            when {
                browseId != null && !pageType(nav).contains("UNKNOWN") && videoId == null ->
                    typed(browseId, pageType(nav), title, ytText(line), thumb)
                videoId != null -> songFrom(videoId, title, line, thumb)
                else -> null
            }
        }
        "musicCardShelfRenderer" -> {
            val title = ytText(o.opt("title"))
            val thumb = sized(lastThumb(o.opt("thumbnail")), 400)
            val nav = o.path("title.runs.0.navigationEndpoint")
            val videoId = (nav as? JSONObject)?.path("watchEndpoint.videoId") as? String
            val browseId = (nav as? JSONObject)?.path("browseEndpoint.browseId") as? String
            when {
                videoId != null -> songFrom(videoId, title, o.opt("subtitle"), thumb)
                browseId != null -> typed(browseId, pageType(nav), title, ytText(o.opt("subtitle")), thumb)
                else -> null
            }
        }
        else -> null
    }

    private fun typed(browseId: String, type: String, title: String, subtitle: String, thumb: String?): MusicItem? = when {
        type.contains("ALBUM") -> AlbumItem(browseId, title, subtitle, thumb)
        type.contains("ARTIST") || type.contains("USER_CHANNEL") -> ArtistItem(browseId, title, subtitle, thumb)
        type.contains("PLAYLIST") || browseId.startsWith("VL") -> PlaylistItem(browseId, title, subtitle, thumb)
        else -> null
    }

    private val itemKinds = listOf("musicTwoRowItemRenderer", "musicResponsiveListItemRenderer")
    private val shelfKinds = listOf(
        "musicCardShelfRenderer", "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer",
        "musicShelfRenderer", "gridRenderer", "musicPlaylistShelfRenderer",
    )

    private fun items(node: Any?): List<MusicItem> {
        val out = mutableListOf<MusicItem>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    for (k in itemKinds) n.optJSONObject(k)?.let { r -> item(r, k)?.let(out::add); return }
                    val keys = n.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key == "menu" || key == "header") continue
                        walk(n.opt(key))
                    }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(node)
        return out
    }

    private val cleanTag = Regex("\\s*[(\\[]?\\s*(clean( version)?|edited|radio edit|censored)\\s*[)\\]]?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * No censored versions: when the same song or album shows up both clean and explicit, only the
     * explicit one is kept, and songs labelled "(Clean)" / "(Radio Edit)" give way to the original.
     */
    private fun preferExplicit(items: List<MusicItem>): List<MusicItem> {
        fun key(i: MusicItem) = when (i) {
            is SongItem -> "s|" + i.title.replace(cleanTag, "").lowercase().trim() + "|" + i.track.artist.substringBefore(",").lowercase().trim()
            is AlbumItem -> "a|" + i.title.replace(cleanTag, "").lowercase().trim()
            else -> null
        }
        val explicitKeys = items.filter { (it is SongItem && it.explicit) || (it is AlbumItem && it.explicit) }.mapNotNull(::key).toSet()
        val originals = items.filter { !cleanTag.containsMatchIn(it.title) }.mapNotNull(::key).toSet()
        return items.filter { i ->
            val k = key(i) ?: return@filter true
            val isExplicit = (i is SongItem && i.explicit) || (i is AlbumItem && i.explicit)
            when {
                isExplicit -> true
                k in explicitKeys -> false // an explicit version is right there
                cleanTag.containsMatchIn(i.title) && k in originals -> false
                else -> true
            }
        }
    }

    /** Every shelf on a page, in order, as titled sections (clean duplicates removed). */
    private fun sections(json: JSONObject): List<Section> = sections0(json).map { s -> s.copy(items = preferExplicit(s.items)) }

    private fun sections0(json: JSONObject): List<Section> {
        val out = mutableListOf<Section>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    for (k in shelfKinds) {
                        val shelf = n.optJSONObject(k) ?: continue
                        if (k == "musicCardShelfRenderer") {
                            val top = item(shelf, k)
                            val rest = items(shelf.opt("contents"))
                            out += Section("Top result", listOfNotNull(top) + rest)
                        } else {
                            val title = ytText(shelf.path("header.musicCarouselShelfBasicHeaderRenderer.title"))
                                .ifEmpty { ytText(shelf.path("header.musicImmersiveCarouselShelfBasicHeaderRenderer.title")) }
                                .ifEmpty { ytText(shelf.opt("title")) }
                                .ifEmpty { ytText(shelf.path("header.gridHeaderRenderer.title")) }
                            val list = items(shelf.opt("contents") ?: shelf.opt("items"))
                            if (list.isNotEmpty()) out += Section(title, list)
                        }
                        return
                    }
                    val keys = n.keys()
                    while (keys.hasNext()) walk(n.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        return out
    }

    private data class Header(val title: String, val subtitle: String, val thumb: String?)

    private fun header(json: JSONObject): Header {
        val kinds = listOf(
            "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer",
            "musicVisualHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
        )
        for (k in kinds) {
            val h = collect(json, k).firstOrNull() ?: continue
            val inner = h.optJSONObject("header")?.let { collect(it, "musicDetailHeaderRenderer").firstOrNull() ?: collect(it, "musicResponsiveHeaderRenderer").firstOrNull() } ?: h
            val title = ytText(inner.opt("title"))
            val sub = listOf(ytText(inner.opt("straplineTextOne")), ytText(inner.opt("subtitle")))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            val thumb = lastThumb(inner.opt("thumbnail") ?: inner.opt("foregroundThumbnail"))
            if (title.isNotEmpty()) return Header(title, sub, sized(thumb, 720))
        }
        return Header("", "", null)
    }

    /* ---------------- Public calls (all blocking; use from a background thread) ---------------- */

    /** A mood or genre chip at the top of Home ("Energize", "Relax"...). */
    data class Chip(val title: String, val params: String)

    data class HomeFeed(val chips: List<Chip>, val sections: List<Section>)

    /** Home feed, optionally filtered by a mood chip. */
    suspend fun home(chipParams: String? = null): HomeFeed = withContext(Dispatchers.IO) {
        val body = JSONObject().put("browseId", "FEmusic_home")
        if (chipParams != null) body.put("params", chipParams)
        val first = browse(body)
        val chips = collect(first, "chipCloudChipRenderer").mapNotNull { c ->
            val title = ytText(c.opt("text"))
            val params = c.path("navigationEndpoint.browseEndpoint.params") as? String
            if (title.isNotEmpty() && params != null) Chip(title, params) else null
        }
        HomeFeed(chips, homeSections(first))
    }

    private fun homeSections(first: JSONObject): List<Section> {
        val out = sections(first).toMutableList()
        var token = nextPage(collect(first, "sectionListRenderer").firstOrNull())
        repeat(3) {
            val t = token ?: return@repeat
            val extra = try { more("browse", t) } catch (e: Exception) { return@repeat }
            out += sections(extra)
            token = nextPage(extra)
        }
        return out.filter { it.items.isNotEmpty() }
    }

    /** One page of search results, and the token for the next page (null when there's no more). */
    data class SearchPage(val sections: List<Section>, val next: Pair<String, Boolean>?)

    suspend fun search(query: String, filter: Filter): SearchPage = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", query)
        filter.params?.let { body.put("params", java.net.URLDecoder.decode(it, "UTF-8")) }
        val json = call("search", body)
        // Only a single-type search (Songs, Albums...) can keep scrolling.
        SearchPage(sections(json), if (filter != Filter.ALL) nextPage(json) else null)
    }

    private val skipInAll = listOf("listen again", "from your library", "library", "episode", "podcast", "profile", "video")

    /**
     * The "All" search, made fuller: YouTube Music's mixed results plus its Songs search (asked at the
     * same time), arranged as Top result, the best songs, artists, albums, videos and playlists, then a
     * long list of more songs that keeps loading as you scroll. Songs you've played before move up.
     */
    suspend fun searchAll(query: String, played: Set<String>): SearchPage = kotlinx.coroutines.coroutineScope {
        fun body(params: String?) = JSONObject().put("query", query).also { b ->
            params?.let { b.put("params", java.net.URLDecoder.decode(it, "UTF-8")) }
        }
        val mixedJob = async(Dispatchers.IO) { call("search", body(null)) }
        val songsJob = async(Dispatchers.IO) { runCatching { call("search", body(Filter.SONGS.params)) }.getOrNull() }
        val mixed = sections(mixedJob.await())
        val songsJson = songsJob.await()

        val top = mixed.firstOrNull { it.title == "Top result" }?.items?.firstOrNull()
        val songs = LinkedHashMap<String, SongItem>()
        // The Songs search is the long, popularity-ordered list; the mixed one's Songs shelf fills gaps.
        songsJson?.let { j -> sections(j).flatMap { it.items }.filterIsInstance<SongItem>().forEach { songs.putIfAbsent(it.track.url, it) } }
        mixed.filter { it.title.equals("Songs", true) }.flatMap { it.items }.filterIsInstance<SongItem>()
            .forEach { songs.putIfAbsent(it.track.url, it) }
        (top as? SongItem)?.let { songs.remove(it.track.url) }
        // Songs you've listened to before go first among the top ones (otherwise YouTube's order).
        val ordered = preferExplicit(songs.values.toList()).filterIsInstance<SongItem>().let { all ->
            val head = all.take(20)
            head.sortedBy { if (it.track.url in played) 0 else 1 } + all.drop(20)
        }

        val out = mutableListOf<Section>()
        if (top != null) out += Section("Top result", listOf(top))
        if (ordered.isNotEmpty()) out += Section("Songs", ordered.take(8))
        val order = listOf("artist", "album", "featured playlist", "community playlist", "playlist")
        mixed.filter { s ->
            val t = s.title.lowercase()
            s.title != "Top result" && !t.equals("songs") && skipInAll.none { t.contains(it) } && s.items.isNotEmpty()
        }.sortedBy { s ->
            val t = s.title.lowercase()
            order.indexOfFirst { t.contains(it) }.let { if (it < 0) order.size else it }
        }.forEach { s ->
            val items = if (s.items.all { it is SongItem }) s.items.take(4) else s.items
            out += Section(s.title, items)
        }
        val more = ordered.drop(8)
        val token = songsJson?.let { nextPage(it) }
        if (more.isNotEmpty() || token != null) out += Section("More songs", more)
        SearchPage(out, if (out.lastOrNull()?.title == "More songs") token else null)
    }

    /** A song's album and artists, for "Go to album" / "Go to artist". */
    data class SongLinks(val album: AlbumItem?, val artists: List<ArtistItem>)

    suspend fun songLinks(videoId: String): SongLinks = withContext(Dispatchers.IO) {
        val json = call("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true))
        val r = collect(json, "playlistPanelVideoRenderer").firstOrNull { it.optString("videoId") == videoId }
            ?: collect(json, "playlistPanelVideoRenderer").firstOrNull()
            ?: return@withContext SongLinks(null, emptyList())
        val runs = (r.opt("longBylineText") as? JSONObject)?.optJSONArray("runs") ?: JSONArray()
        var album: AlbumItem? = null
        val artists = mutableListOf<ArtistItem>()
        val thumb = sized(lastThumb(r.opt("thumbnail")), 400)
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val nav = run.optJSONObject("navigationEndpoint") ?: continue
            val id = nav.path("browseEndpoint.browseId") as? String ?: continue
            val type = pageType(nav)
            val text = run.optString("text")
            when {
                type.contains("ALBUM") -> album = AlbumItem(id, text, "", thumb)
                type.contains("ARTIST") || type.contains("USER_CHANNEL") -> artists += ArtistItem(id, text, "", null)
            }
        }
        SongLinks(album, artists)
    }

    /** YouTube Music's own lyrics for a song (plain text, no timing), or null if it has none. */
    suspend fun lyrics(videoId: String): String? = withContext(Dispatchers.IO) {
        val next = call("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true))
        val id = collect(next, "browseEndpoint").map { it.optString("browseId") }.firstOrNull { it.startsWith("MPLYt") }
            ?: return@withContext null
        val page = browse(JSONObject().put("browseId", id))
        collect(page, "musicDescriptionShelfRenderer").firstOrNull()?.let { ytText(it.opt("description")) }?.takeIf { it.isNotBlank() }
    }

    /** A picture for a Moods & genres tile: the cover of the first playlist on its page. */
    suspend fun genreArt(item: CategoryItem): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("browseId", item.browseId)
        item.params?.let { body.put("params", it) }
        val json = browse(body)
        sections(json).flatMap { it.items }.firstNotNullOfOrNull { it.thumb }?.let { sized(it, 400) }
    }

    /** The next page of a filtered search. */
    suspend fun searchMore(token: Pair<String, Boolean>): SearchPage = withContext(Dispatchers.IO) {
        val json = more("search", token)
        SearchPage(listOf(Section("", items(json))), nextPage(json))
    }

    /** What YouTube Music suggests as you type: finished searches, plus a few direct hits. */
    data class Suggestions(val queries: List<String>, val items: List<MusicItem>)

    suspend fun suggestions(input: String): Suggestions = withContext(Dispatchers.IO) {
        val json = call("music/get_search_suggestions", JSONObject().put("input", input))
        val queries = collect(json, "searchSuggestionRenderer").map { ytText(it.opt("suggestion")) }.filter { it.isNotBlank() }.distinct()
        Suggestions(queries.take(8), items(json).take(4))
    }

    /** A Moods & genres tile for browsing ("Chill", "Hip-Hop", "Workout"...). */
    data class Genre(val title: String, val item: CategoryItem)

    suspend fun genres(): List<Genre> = withContext(Dispatchers.IO) {
        val json = browse(JSONObject().put("browseId", "FEmusic_moods_and_genres"))
        collect(json, "musicNavigationButtonRenderer").mapNotNull { b ->
            val title = ytText(b.opt("buttonText"))
            val ep = b.path("clickCommand.browseEndpoint") as? JSONObject ?: return@mapNotNull null
            val id = ep.optString("browseId").ifEmpty { return@mapNotNull null }
            val color = (b.path("solid.leftStripeColor") as? Number)?.toLong() ?: 0xFF5E5CE6
            if (title.isEmpty()) null else Genre(title, CategoryItem(id, ep.optString("params").ifEmpty { null }, title, color))
        }.distinctBy { it.title }
    }

    /** An album, artist, playlist or mood/genre page. */
    suspend fun page(item: MusicItem): Page = withContext(Dispatchers.IO) {
        if (item is CategoryItem) {
            val body = JSONObject().put("browseId", item.browseId)
            item.params?.let { body.put("params", it) }
            val json = browse(body)
            return@withContext Page(item.title, "Mood & genre", null, emptyList(), sections(json))
        }
        val id = when (item) {
            is AlbumItem -> item.browseId
            is ArtistItem -> item.browseId
            is PlaylistItem -> item.browseId
            is CategoryItem -> item.browseId
            is SongItem -> throw IllegalArgumentException("Songs don't have pages")
        }
        if (item is PlaylistItem) {
            val json = browse(JSONObject().put("browseId", id))
            val h = header(json)
            val tracks = tracksFrom(json)
            return@withContext Page(h.title.ifEmpty { item.title }, h.subtitle.ifEmpty { item.subtitle }, h.thumb ?: item.thumb, tracks, emptyList())
        }
        val json = browse(JSONObject().put("browseId", id))
        val h = header(json)
        val title = h.title.ifEmpty { item.title }
        val cover = h.thumb ?: sized(item.thumb, 720)
        val all = sections(json)
        if (item is AlbumItem) {
            val albumArtist = h.subtitle.substringBefore(" · ")
            val tracks = all.flatMap { it.items }.filterIsInstance<SongItem>().map {
                it.track.copy(
                    thumb = it.track.thumb ?: cover,
                    artist = it.track.artist.ifEmpty { albumArtist },
                )
            }.distinctBy { it.url }
            Page(title, h.subtitle, cover, tracks, emptyList())
        } else {
            // Artist: the first list of songs is "Top songs"; the rest are albums, singles, similar artists...
            val songs = all.firstOrNull { s -> s.items.isNotEmpty() && s.items.all { it is SongItem } }
            val tracks = songs?.items?.filterIsInstance<SongItem>()?.map { it.track } ?: emptyList()
            Page(title, h.subtitle, cover, tracks, all.filter { it !== songs })
        }
    }

    /**
     * Songs similar to [videoId] (YouTube Music's "radio" for it), for Autoplay. Leaves out the song
     * itself. Blocking.
     */
    /** The real title, artist, length and cover of a song, straight from YouTube Music. Blocking. */
    fun trackInfo(videoId: String): Track? {
        val json = call("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true))
        val r = collect(json, "playlistPanelVideoRenderer").firstOrNull { it.optString("videoId") == videoId } ?: return null
        val title = ytText(r.opt("title")).ifEmpty { return null }
        val (artist, _) = artistAndLength(r.opt("longBylineText") ?: r.opt("shortBylineText"))
        return Track(
            ytWatchUrl(videoId), title, artist.ifEmpty { ytText(r.opt("shortBylineText")) },
            parseDuration(ytText(r.opt("lengthText"))), sized(lastThumb(r.opt("thumbnail")), 226),
        )
    }

    fun radio(videoId: String): List<Track> {
        val json = call(
            "next",
            JSONObject()
                .put("videoId", videoId)
                .put("playlistId", "RDAMVM$videoId")
                .put("isAudioOnly", true)
                .put("enablePersistentPlaylistPanel", true),
        )
        val out = LinkedHashMap<String, Track>()
        for (r in collect(json, "playlistPanelVideoRenderer")) {
            val id = r.optString("videoId")
            if (id.isEmpty() || id == videoId) continue
            val title = ytText(r.opt("title"))
            if (title.isEmpty()) continue
            val (artist, _) = artistAndLength(r.opt("longBylineText") ?: r.opt("shortBylineText"))
            val seconds = parseDuration(ytText(r.opt("lengthText")))
            out.putIfAbsent(id, Track(ytWatchUrl(id), title, artist.ifEmpty { ytText(r.opt("shortBylineText")) }, seconds, sized(lastThumb(r.opt("thumbnail")), 226)))
        }
        return out.values.toList()
    }

    /** Part of a music video lined up against the song: song time [songStart, songStart+length) = video time videoStart+. */
    data class Segment(val songStart: Long, val videoStart: Long, val length: Long)

    /** A song's official music video, with how its timing lines up against the song. */
    data class MusicVideo(val videoId: String, val segments: List<Segment>) {
        /** Where the video should be for a given song position, or null if the video has no matching part. */
        fun videoTime(songMs: Long): Long? {
            if (segments.isEmpty()) return songMs
            val s = segments.firstOrNull { songMs >= it.songStart && songMs < it.songStart + it.length } ?: return null
            return s.videoStart + (songMs - s.songStart)
        }
    }

    /**
     * Finds the music video for a song using YouTube Music's own "Song / Video" switch.
     * Returns the song itself if it already is a video, or null if it has none.
     */
    suspend fun musicVideo(videoId: String): MusicVideo? = withContext(Dispatchers.IO) {
        val json = call(
            "next",
            JSONObject()
                .put("videoId", videoId)
                .put("isAudioOnly", true)
                .put("enablePersistentPlaylistPanel", true),
        )
        for (w in collect(json, "playlistPanelVideoWrapperRenderer")) {
            val primary = w.path("primaryRenderer.playlistPanelVideoRenderer.videoId") as? String
            if (primary != videoId) continue
            val cp = w.path("counterpart.0") as? JSONObject ?: continue
            val vid = cp.path("counterpartRenderer.playlistPanelVideoRenderer.videoId") as? String ?: continue
            val segs = (cp.path("segmentMap.segment") as? JSONArray)?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    Segment(
                        s.optString("primaryVideoStartTimeMilliseconds").toLongOrNull() ?: return@mapNotNull null,
                        s.optString("counterpartVideoStartTimeMilliseconds").toLongOrNull() ?: return@mapNotNull null,
                        s.optString("durationMilliseconds").toLongOrNull() ?: return@mapNotNull null,
                    )
                }
            } ?: emptyList()
            return@withContext MusicVideo(vid, segs)
        }
        // No counterpart: if this "song" is itself an official video, use it directly.
        for (r in collect(json, "playlistPanelVideoRenderer")) {
            if (r.optString("videoId") != videoId) continue
            if (findString(r, "musicVideoType") == "MUSIC_VIDEO_TYPE_OMV") return@withContext MusicVideo(videoId, emptyList())
        }
        null
    }

    /** Your saved and created playlists, including Liked Music. Needs sign-in. */
    fun playlists(): List<PlaylistInfo> {
        val out = LinkedHashMap<String, PlaylistInfo>()
        var json: JSONObject = browse(JSONObject().put("browseId", "FEmusic_liked_playlists"), requireAuth = true)
        repeat(20) {
            val found = collect(json, "musicTwoRowItemRenderer").map { it to "musicTwoRowItemRenderer" } +
                collect(json, "musicResponsiveListItemRenderer").map { it to "musicResponsiveListItemRenderer" }
            for ((r, kind) in found) {
                val p = item(r, kind) as? PlaylistItem ?: continue
                out[p.browseId] = PlaylistInfo("yt_${p.browseId}", p.title, Source.YT, p.thumb, 0)
            }
            val next = nextPage(json) ?: return out.values.toList()
            json = more("browse", next, requireAuth = true)
        }
        return out.values.toList()
    }

    private fun tracksFrom(first: JSONObject, requireAuth: Boolean = false): List<Track> {
        val out = LinkedHashMap<String, Track>()
        // Only read the playlist's own list, not the "suggestions" shown under it.
        var scope: Any = collect(first, "musicPlaylistShelfRenderer").firstOrNull()
            ?: collect(first, "musicShelfRenderer").firstOrNull()
            ?: first
        repeat(200) { i ->
            val before = out.size
            for (s in items(scope).filterIsInstance<SongItem>()) out.putIfAbsent(s.track.url, s.track)
            val next = nextPage(scope) ?: return out.values.toList()
            if (out.size == before && i > 0) return out.values.toList()
            scope = more("browse", next, requireAuth)
        }
        return out.values.toList()
    }

    /** All songs in one of your playlists. [browseId] starts with "VL". */
    fun tracks(browseId: String): List<Track> =
        tracksFrom(browse(JSONObject().put("browseId", browseId), requireAuth = true), requireAuth = true)
}
