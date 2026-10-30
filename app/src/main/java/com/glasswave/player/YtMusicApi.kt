// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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
        // Searches never go through your account: YouTube would save every one (including the
        // ones Glasswave makes on its own, like finding a song's explicit version) in your
        // YouTube search history.
        val anon = endpoint == "search" || endpoint.endsWith("get_search_suggestions")
        var (code, text) = send(!anon)
        if ((code == 401 || code == 403) && cookie != null && !anon) {
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

    private fun songFrom(videoId: String, title: String, line: Any?, thumb: String?, fallbackArtist: String = "", explicit: Boolean = false, plays: Long = 0): SongItem {
        val (artist, seconds) = artistAndLength(line)
        val a = artist.ifEmpty { fallbackArtist }
        return SongItem(Track(ytWatchUrl(videoId), title.ifEmpty { "Unknown" }, a, seconds, thumb), a, explicit, plays.takeIf { it > 0 } ?: playsIn(ytText(line)))
    }

    private val playsRx = Regex("""([\d.,]+)\s*([KMB])?\s+(plays|views)""", RegexOption.IGNORE_CASE)

    /** "483M plays" / "1.4B views" / "35 plays" -> the number (0 when there's none). */
    private fun playsIn(text: String): Long {
        val m = playsRx.find(text) ?: return 0
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        val times = when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
        return (n * times).toLong()
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
                videoId != null -> songFrom(videoId, title, line, thumb,
                    plays = playsIn(ytText(o.path("flexColumns.2.musicResponsiveListItemFlexColumnRenderer.text"))))
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
    /** Likes (or un-likes) a song on your YouTube Music account, so both stay in step. Blocking. */
    fun rate(videoId: String, like: Boolean) {
        call(if (like) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)), requireAuth = true)
    }

    data class Account(val name: String, val handle: String?, val photo: String?)

    /** The signed-in account's name, @handle (or email) and picture. */
    suspend fun account(): Account? = withContext(Dispatchers.IO) {
        val j = call("account/account_menu", JSONObject(), requireAuth = true)
        val h = collect(j, "activeAccountHeaderRenderer").firstOrNull() ?: return@withContext null
        val name = ytText(h.opt("accountName")).ifEmpty { return@withContext null }
        val handle = ytText(h.opt("channelHandle")).ifEmpty { ytText(h.opt("email")) }.ifEmpty { null }
        // Ask for a sharper picture than the tiny default.
        val photo = lastThumb(h.opt("accountPhoto"))?.replace(Regex("=s\\d+"), "=s240")
        Account(name, handle, photo)
    }

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

    /** YouTube Music's Videos search (lyric videos, fan and label uploads). */
    suspend fun searchVideos(query: String): List<SongItem> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", query).put("params", "EgWKAQIQAWoKEAkQChAFEAMQBA==")
        sections(call("search", body)).flatMap { it.items }.filterIsInstance<SongItem>()
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
        // What people most often search starting with these words ("last last" -> "last last burna boy").
        val popularJob = async(Dispatchers.IO) { runCatching { suggestions(query).queries }.getOrNull().orEmpty() }
        val mixed = sections(mixedJob.await())
        val songsJson = songsJob.await()
        val ytTop = mixed.firstOrNull { it.title == "Top result" }?.items?.firstOrNull()
        // Typed a line from a song: YouTube Music finds the song from its lyrics, so trust its picks.
        val lyrics = ytTop is SongItem && SearchRank.looksLikeLyrics(query, ytTop)
        val rank = SearchRank(query, popularJob.await(), played, lyrics)

        val songs = LinkedHashMap<String, SongItem>()
        val shelf = mixed.filter { it.title.equals("Songs", true) || (lyrics && it.title == "Top result") }.flatMap { it.items }.filterIsInstance<SongItem>()
        // With lyrics, the mixed search's songs (the ones the words come from) go first.
        if (lyrics) shelf.forEach { songs.putIfAbsent(it.track.url, it) }
        songsJson?.let { j -> sections(j).flatMap { it.items }.filterIsInstance<SongItem>().forEach { songs.putIfAbsent(it.track.url, it) } }
        shelf.forEach { songs.putIfAbsent(it.track.url, it) }
        // Best match first: how well it matches the words, how popular it is, and whether you've
        // played it. Songs hardly anyone has played go to the bottom.
        val ordered = rank.sort(preferExplicit(songs.values.toList()).filterIsInstance<SongItem>())
        // The top result is the best song, unless YouTube's top is an artist, album or playlist the
        // search names, or a song that scores better.
        val best = ordered.firstOrNull()
        val top = when {
            ytTop !is SongItem && ytTop != null -> ytTop
            // The song the lyrics are from (its song version rather than the music video, if there is one).
            lyrics -> ordered.firstOrNull { SearchRank.same(it, ytTop as SongItem) } ?: ytTop
            best == null -> ytTop
            // YouTube's top pick is a song the search isn't about (one from your history): use the best match.
            ytTop is SongItem && !rank.relevant(ytTop) -> best
            ytTop is SongItem && rank.score(ytTop, 0) >= rank.score(best, 0) -> ytTop
            else -> best
        }
        // The top result doesn't show again right under it (nor its song/video twin).
        val rest = (top as? SongItem)?.let { t -> ordered.filterNot { SearchRank.same(it, t) } } ?: ordered
        
        val out = mutableListOf<Section>()
        if (top != null) out += Section("Top result", listOf(top))
        if (rest.isNotEmpty()) out += Section("Songs", rest.take(8))
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
        val more = rest.drop(8)
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
        // Songs hardly anyone has played go to the end of each page.
        val list = items(json).sortedBy { (it as? SongItem)?.let { s -> if (SearchRank.rare(s)) 1 else 0 } ?: 0 }
        SearchPage(listOf(Section("", list)), nextPage(json))
    }

    /** What YouTube Music suggests as you type: finished searches, plus a few direct hits. */
    data class Suggestions(val queries: List<String>, val items: List<MusicItem>)

    suspend fun suggestions(input: String): Suggestions = withContext(Dispatchers.IO) {
        val json = call("music/get_search_suggestions", JSONObject().put("input", input))
        val queries = collect(json, "searchSuggestionRenderer").map { ytText(it.opt("suggestion")) }.filter { it.isNotBlank() }.distinct()
        Suggestions(queries.take(8), items(json).take(4))
    }

    /** A Moods & genres tile for browsing ("Chill", "Hip-Hop", "Workout"...). [mood]: from the "Moods & moments" group. */
    data class Genre(val title: String, val item: CategoryItem, val mood: Boolean = false)

    suspend fun genres(): List<Genre> = withContext(Dispatchers.IO) {
        val json = browse(JSONObject().put("browseId", "FEmusic_moods_and_genres"))
        fun buttons(node: Any?, mood: Boolean) = collect(node, "musicNavigationButtonRenderer").mapNotNull { b ->
            val title = ytText(b.opt("buttonText"))
            val ep = b.path("clickCommand.browseEndpoint") as? JSONObject ?: return@mapNotNull null
            val id = ep.optString("browseId").ifEmpty { return@mapNotNull null }
            val color = (b.path("solid.leftStripeColor") as? Number)?.toLong() ?: 0xFF5E5CE6
            if (title.isEmpty()) null else Genre(title, CategoryItem(id, ep.optString("params").ifEmpty { null }, title, color), mood)
        }
        // The page has a "Moods & moments" group and a "Genres" group; tell them apart by the group's name.
        val groups = collect(json, "gridRenderer")
        val all = if (groups.isEmpty()) buttons(json, false) else groups.flatMap { g ->
            buttons(g, ytText(g.path("header.gridHeaderRenderer.title")).contains("mood", true))
        }
        all.ifEmpty { buttons(json, false) }.distinctBy { it.title }
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
            val top = songs?.items?.filterIsInstance<SongItem>()?.map { it.track } ?: emptyList()
            // Every song by the artist: the playlist behind Top songs' "Show all".
            val allId = collect(json, "musicShelfRenderer").firstNotNullOfOrNull { s ->
                listOf(s.opt("bottomEndpoint"), s.opt("title")).firstNotNullOfOrNull { n ->
                    findString(n, "browseId")?.takeIf { it.startsWith("VL") }
                }
            }
            val topUrls = top.map { it.url }.toSet()
            // A music player: no Videos or Live performances rows (their songs are in Songs).
            val kept = all.filter { it !== songs }.filterNot { s ->
                s.title.lowercase().let { t -> "video" in t || "live performance" in t }
            }
            // All the songs, and the same-name check, are looked up at the same time.
            val (rest, checked) = kotlinx.coroutines.coroutineScope {
                val restJob = async(Dispatchers.IO) {
                    allId?.let { id ->
                        runCatching { tracksFrom(browse(JSONObject().put("browseId", id)), max = 300) }.getOrNull()
                    }.orEmpty().filter { it.url !in topUrls }
                }
                val checkJob = async(Dispatchers.IO) { sameNameOnly(songs?.items?.filterIsInstance<SongItem>().orEmpty(), kept) }
                restJob.await() to checkJob.await()
            }
            val (others, wrongSongs) = checked
            val shelf = if (rest.isEmpty()) emptyList() else listOf(Section("Songs", rest.filter { it.url !in wrongSongs }.map { SongItem(it, it.artist) }))
            // Play goes through the top songs, then all the others.
            Page(title, h.subtitle, cover, top + rest.filter { it.url !in wrongSongs }, shelf + others, shown = top.size)
        }
    }

    private val yearRx = Regex("""\b(19|20)\d{2}\b""")

    /**
     * YouTube Music sometimes puts several artists who share a name on one page (searching
     * "Marino" gives an alt-pop singer whose Albums row held 1990s albums by someone else).
     * Each album and single on the page is opened and its songs' play counts read. One is someone
     * else's when its songs have under a thousandth of the plays of the artist's top songs AND it
     * came out more than four years away from any of the artist's well-played releases. Brand-new
     * releases (no plays yet, same years) always stay. Gives the rows without those, and the
     * addresses of their songs (to leave out of Songs too).
     */
    private suspend fun sameNameOnly(top: List<SongItem>, rows: List<Section>): Pair<List<Section>, Set<String>> {
        val topPlays = top.maxOfOrNull { it.plays } ?: 0L
        val releases = rows.flatMap { it.items }.filterIsInstance<AlbumItem>().distinctBy { it.browseId }.take(30)
        if (topPlays < 100_000 || releases.isEmpty()) return rows to emptySet()
        val gate = kotlinx.coroutines.sync.Semaphore(10)
        val songsOf: Map<String, List<SongItem>?> = kotlinx.coroutines.coroutineScope {
            releases.map { a ->
                async(Dispatchers.IO) {
                    a.browseId to gate.withPermit {
                        runCatching { sections0(browse(JSONObject().put("browseId", a.browseId))).flatMap { it.items }.filterIsInstance<SongItem>() }.getOrNull()
                    }
                }
            }.awaitAll().toMap()
        }
        fun most(a: AlbumItem): Long = songsOf[a.browseId]?.maxOfOrNull { it.plays } ?: -1L
        fun year(a: AlbumItem): Int? = yearRx.find(a.subtitle)?.value?.toIntOrNull()
        val anchors = releases.filter { most(it) * 100 >= topPlays }.mapNotNull(::year)
        if (anchors.isEmpty()) return rows to emptySet()
        val wrong = releases.filter { a ->
            val plays = most(a)
            val y = year(a) ?: return@filter false
            plays in 0 until topPlays / 1000 && anchors.minOf { kotlin.math.abs(it - y) } > 4
        }.map { it.browseId }.toSet()
        if (wrong.isEmpty()) return rows to emptySet()
        val wrongSongs = wrong.flatMap { songsOf[it].orEmpty() }.map { it.track.url }.toSet()
        val cleaned = rows.map { s -> s.copy(items = s.items.filterNot { it is AlbumItem && it.browseId in wrong }) }.filter { it.items.isNotEmpty() }
        return cleaned to wrongSongs
    }

    /**
     * The uncensored version of a clean song: same name, same main artist, about the same length,
     * marked Explicit. Null when the song is already explicit, isn't a song, or has no explicit
     * version. Blocking.
     */
    fun explicitFor(videoId: String): String? {
        val json = call("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true))
        val r = collect(json, "playlistPanelVideoRenderer").firstOrNull { it.optString("videoId") == videoId } ?: return null
        if (isExplicit(r) || isSong(r) == false) return null
        val title = ytText(r.opt("title")).ifEmpty { return null }
        val (artist, seconds) = artistAndLength(r.opt("longBylineText") ?: r.opt("shortBylineText"))
        val lead = artist.substringBefore(",").substringBefore(" & ").trim().lowercase()
        val body = JSONObject().put("query", "$title $artist".trim())
            .put("params", java.net.URLDecoder.decode(Filter.SONGS.params!!, "UTF-8"))
        return sections0(call("search", body)).flatMap { it.items }.filterIsInstance<SongItem>().take(12).firstOrNull { s ->
            s.explicit && YtRepo.sameSong(title, s.track.title) && sameVersion(title, s.track.title) &&
                (lead.isEmpty() || s.track.artist.lowercase().contains(lead)) &&
                (seconds <= 0 || s.track.seconds <= 0 || kotlin.math.abs(s.track.seconds - seconds) <= 20)
        }?.let { YtRepo.videoIdFor(it.track.url) }?.takeIf { it != videoId }
    }

    private val versionWord = Regex("""\b(remix|mix|live|acoustic|version|edit|instrumental|sped|slowed|demo|remaster(ed)?|extended|cover|reprise|unplugged|acapella|a cappella|clean)\b""", RegexOption.IGNORE_CASE)

    /** Same version of a song: a remix only matches a remix, a live one a live one, and so on. */
    private fun sameVersion(a: String, b: String): Boolean {
        fun words(t: String) = versionWord.findAll(t).map { it.value.lowercase() }.filter { it != "clean" }.toSet()
        return words(a) == words(b)
    }

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

    private fun radioReply(videoId: String) = call(
        "next",
        JSONObject()
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true),
    )

    /** True for a song, false for a music video or upload, null when YouTube didn't say. */
    private fun isSong(r: JSONObject): Boolean? =
        (r.path("navigationEndpoint.watchEndpoint.watchEndpointMusicSupportedConfigs.watchEndpointMusicConfig.musicVideoType") as? String)
            ?.let { it == "MUSIC_VIDEO_TYPE_ATV" }

    /** The song version of a music video: YouTube Music's own Song/Video switch, else a song search. */
    private fun songFor(videoId: String, reply: JSONObject): String? {
        collect(reply, "counterpartRenderer").mapNotNull { it.optJSONObject("playlistPanelVideoRenderer") }
            .firstOrNull { isSong(it) == true }?.optString("videoId")?.takeIf { it.isNotEmpty() }?.let { return it }
        val me = collect(reply, "playlistPanelVideoRenderer").firstOrNull { it.optString("videoId") == videoId } ?: return null
        val title = ytText(me.opt("title")).ifEmpty { return null }
        val (artist, seconds) = artistAndLength(me.opt("longBylineText") ?: me.opt("shortBylineText"))
        return YtRepo.findSong(title, artist, seconds)?.let { YtRepo.videoIdFor(it.url) }
    }

    /**
     * Songs similar to [videoId] (YouTube Music's "radio" for it), for Autoplay, Automix and the
     * For you shelves. Songs only: no music videos or uploads. Leaves out the song itself. Blocking.
     */
    fun radio(videoId: String): List<Track> {
        var json = radioReply(videoId)
        var seed = videoId
        // Started from a music video, YouTube's radio is all videos: start from the song instead.
        val panel = collect(json, "playlistPanelVideoRenderer")
        if (panel.count { isSong(it) == true } < panel.size / 2) {
            songFor(videoId, json)?.let { song -> runCatching { radioReply(song) }.getOrNull()?.let { json = it; seed = song } }
        }
        val out = LinkedHashMap<String, Track>()
        for (r in collect(json, "playlistPanelVideoRenderer")) {
            val id = r.optString("videoId")
            if (id.isEmpty() || id == videoId || id == seed) continue
            if (isSong(r) == false) continue
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

    private fun tracksFrom(first: JSONObject, requireAuth: Boolean = false, max: Int = Int.MAX_VALUE): List<Track> {
        val out = LinkedHashMap<String, Track>()
        // Only read the playlist's own list, not the "suggestions" shown under it.
        var scope: Any = collect(first, "musicPlaylistShelfRenderer").firstOrNull()
            ?: collect(first, "musicShelfRenderer").firstOrNull()
            ?: first
        repeat(200) { i ->
            val before = out.size
            for (s in items(scope).filterIsInstance<SongItem>()) out.putIfAbsent(s.track.url, s.track)
            if (out.size >= max) return out.values.take(max)
            val next = nextPage(scope) ?: return out.values.toList()
            if (out.size == before && i > 0) return out.values.toList()
            scope = more("browse", next, requireAuth)
        }
        return out.values.toList()
    }

    /**
     * A playlist or album from a shared link, read without signing in. Follows YouTube's
     * "load more" pages up to [max] songs. [id] is a playlist id (PL..., OLAK5uy_...) or an album id (MPREb...).
     */
    suspend fun linkPage(id: String, max: Int = 1000): Page = withContext(Dispatchers.IO) {
        if (id.startsWith("MPREb")) return@withContext page(AlbumItem(id, "", "", null))
        val json = browse(JSONObject().put("browseId", if (id.startsWith("VL")) id else "VL$id"))
        val h = header(json)
        // An album's own playlist (OLAK5uy_...) has no title; open the album instead, for its name and cover.
        if (id.removePrefix("VL").startsWith("OLAK5uy_")) {
            val album = collect(json, "browseEndpoint").firstNotNullOfOrNull { e -> e.optString("browseId").takeIf { it.startsWith("MPREb") } }
            if (album != null) return@withContext page(AlbumItem(album, "", "", null))
        }
        val out = LinkedHashMap<String, Track>()
        var scope: Any = collect(json, "musicPlaylistShelfRenderer").firstOrNull()
            ?: collect(json, "musicShelfRenderer").firstOrNull()
            ?: json
        for (i in 0 until 100) {
            val before = out.size
            for (s in items(scope).filterIsInstance<SongItem>()) out.putIfAbsent(s.track.url, s.track)
            if (out.size >= max) break
            val next = nextPage(scope) ?: break
            if (out.size == before && i > 0) break
            scope = more("browse", next)
        }
        Page(h.title, h.subtitle, h.thumb, out.values.take(max), emptyList())
    }

    /** All songs in one of your playlists. [browseId] starts with "VL". */
    fun tracks(browseId: String): List<Track> =
        tracksFrom(browse(JSONObject().put("browseId", browseId), requireAuth = true), requireAuth = true)
}
