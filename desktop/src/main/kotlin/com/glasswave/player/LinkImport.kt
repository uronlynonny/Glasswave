// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.net.Uri
import android.util.Base64
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A problem with the link itself (not recognised, private, empty). The message is shown as is. */
class ImportProblem(message: String) : IOException(message)

/**
 * "Import from a link": paste a playlist or album link from Spotify, YouTube Music, YouTube,
 * SoundCloud, Apple Music, Audiomack or Deezer and get it as one of your own playlists. No sign-in needed;
 * everything here reads the same public pages and feeds a web browser would.
 *
 * Songs from YouTube keep their YouTube address. Songs from the other services get a "gwmatch://"
 * address and are matched to YouTube Music when they play, like Spotify songs.
 */
object LinkImport {
    /** A playlist or album read from a link. [note] replaces the usual "Imported" message when not every song came. */
    data class Imported(val name: String, val cover: String?, val tracks: List<Track>, val note: String? = null)

    /** The services a link can come from, in the order the app lists them. */
    val SERVICES = listOf("Spotify", "YouTube Music", "YouTube", "SoundCloud", "Apple Music", "Audiomack", "Deezer")

    /** "Spotify, YouTube Music, ... or Deezer", for plain sentences. */
    val servicesText = SERVICES.dropLast(1).joinToString(", ") + " or " + SERVICES.last()

    /** Big enough for nearly any playlist, small enough to stay quick. */
    private const val MAX = 1000

    private fun hostOf(link: String): String =
        runCatching { Uri.parse(link.trim().let { if ("://" in it) it else "https://$it" }).host }.getOrNull()?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: ""

    private fun isSpotify(h: String) = h == "open.spotify.com" || h == "play.spotify.com" || h == "spotify.link" || h.endsWith(".spotify.link")
    private fun isYouTube(h: String) = h == "youtube.com" || h == "music.youtube.com" || h == "youtu.be"
    private fun isSoundCloud(h: String) = h == "soundcloud.com" || h == "on.soundcloud.com" || h == "api.soundcloud.com"
    private fun isApple(h: String) = h == "music.apple.com" || h == "itunes.apple.com"
    private fun isDeezer(h: String) = h == "deezer.com" || h == "link.deezer.com" || h == "deezer.page.link"
    private fun isAudiomack(h: String) = h == "audiomack.com"

    /** The first web link in [text], so a whole "Check out this playlist: https://..." message can be pasted. */
    private fun firstLink(text: String): String =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'') ?: text.trim()

    /** The link in [text] if it's one Glasswave knows how to import, else null. Used to fill in the box from the clipboard. */
    fun supportedLink(text: String?): String? {
        if (text.isNullOrBlank() || text.length > 2000) return null
        val link = firstLink(text)
        val h = hostOf(link)
        return link.takeIf { isSpotify(h) || isYouTube(h) || isSoundCloud(h) || isApple(h) || isAudiomack(h) || isDeezer(h) }
    }

    /** Reads the playlist, album or song behind [link]. Throws [ImportProblem] with a plain message if it can't. */
    suspend fun importLink(link: String): Imported {
        val url = firstLink(link).let { if ("://" in it) it else "https://$it" }
        val h = hostOf(url)
        val got = when {
            isSpotify(h) -> spotify(url)
            isYouTube(h) -> youtube(url)
            isSoundCloud(h) -> soundCloud(url)
            isApple(h) -> apple(url)
            isDeezer(h) -> deezer(url)
            isAudiomack(h) -> audiomack(url)
            else -> throw ImportProblem(
                "That link isn't one Glasswave can read. Paste a playlist or album link from $servicesText.",
            )
        }
        return if (got.note == null && got.tracks.size >= MAX) got.copy(note = "Imported the first $MAX songs of ${got.name}. That's as many as one import brings in.") else got
    }

    /* ---------------- Small helpers ---------------- */

    private class Web(val url: String, val text: String, val code: Int)

    /** Fetches a web page like a browser would, following redirects (short links). Blocking. */
    private fun fetch(url: String, headers: Map<String, String> = emptyMap()): Web {
        val b = Request.Builder().url(url).header("User-Agent", YtDownloader.USER_AGENT).header("Accept-Language", "en")
        headers.forEach { (k, v) -> b.header(k, v) }
        YtRepo.http.newCall(b.build()).execute().use { r ->
            return Web(r.request.url.toString(), r.body?.string() ?: "", r.code)
        }
    }

    /** Text under [key], or "" when it's missing or null (kept from the phone app, where optString gives "null" for null). */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

    /** "PT3M28S" to seconds. */
    private fun isoSeconds(s: String?): Long {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(s ?: return 0) ?: return 0
        val (h, min, sec) = m.destructured
        return (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
    }

    /* ---------------- Spotify ---------------- */

    private suspend fun spotify(url: String): Imported {
        var link = url
        // spotify.link short links: follow them to the real open.spotify.com address first.
        if ("spotify.link" in hostOf(url)) link = withContext(Dispatchers.IO) { fetch(url) }.let { p ->
            Regex("open\\.spotify\\.com/[^\"'\\s<>]+").find(p.url + " " + p.text)?.value ?: p.url
        }
        if (Regex("/(track|episode|show|artist)/").containsMatchIn(link) && !Regex("(playlist|album)[/:]").containsMatchIn(link)) {
            throw ImportProblem("That's a Spotify song or artist. Share a playlist or album instead.")
        }
        val got = try {
            SpotifyApi.importLink(link)
        } catch (e: IOException) {
            // Spotify's own "not found / private" answers; internet trouble is passed on as is.
            if (e.javaClass != IOException::class.java) throw e
            throw ImportProblem(e.message ?: "Spotify didn't share this one (is it private?)")
        }
        return Imported(
            got.name, got.cover, got.tracks,
            if (got.cut) "Imported the first ${got.tracks.size} songs of ${got.name}. Spotify only shares 100 by link; sign in to Spotify in Settings to get them all." else null,
        )
    }

    /* ---------------- YouTube Music and YouTube ---------------- */

    private suspend fun youtube(url: String): Imported {
        val uri = Uri.parse(url)
        val list = uri.getQueryParameter("list")?.takeIf { it.isNotBlank() }
        val browse = Regex("/browse/((?:MPREb|VL)[A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
        val video = uri.getQueryParameter("v")
            ?: Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
            ?: Regex("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
        val id = browse ?: list
        if (id != null) {
            if (id.startsWith("RD")) throw ImportProblem("That's a YouTube mix, which YouTube makes fresh for each person, so it can't be copied. Share a normal playlist instead.")
            if (id == "LL" || id == "WL" || id == "LM") throw ImportProblem("That's a private list on someone's account. Make a playlist from it in YouTube and share that instead.")
            val page = try {
                YtMusicApi.linkPage(id, MAX)
            } catch (e: IOException) {
                if (e.javaClass != IOException::class.java) throw e
                throw ImportProblem("Couldn't open that YouTube playlist. It may be private or deleted.")
            }
            if (page.tracks.isEmpty()) throw ImportProblem("That YouTube playlist is empty, private or deleted.")
            val name = page.title.ifEmpty { "YouTube playlist" }
            return Imported(name, page.thumb, page.tracks)
        }
        if (video != null) {
            val t = withContext(Dispatchers.IO) { runCatching { YtMusicApi.trackInfo(video) }.getOrNull() }
                ?: throw ImportProblem("Couldn't find that YouTube video.")
            return Imported(t.title, hiRes(t.thumb), listOf(t))
        }
        throw ImportProblem("That YouTube link isn't a playlist, album or song.")
    }

    /* ---------------- SoundCloud ---------------- */

    /** SoundCloud's own page data: everything in its "window.__sc_hydration" block, by kind. */
    private fun scHydration(html: String): Map<String, Any?> {
        val marker = "window.__sc_hydration = "
        val at = html.indexOf(marker)
        if (at < 0) return emptyMap()
        val arr = runCatching { JSONTokener(html.substring(at + marker.length)).nextValue() as? JSONArray }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Any?>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out[o.str("hydratable")] = o.opt("data")
        }
        return out
    }

    /** SoundCloud's public app key, read from its page scripts when the page itself doesn't carry one. */
    private fun scClientId(html: String): String? {
        for (src in Regex("https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js").findAll(html).map { it.value }.toList().asReversed()) {
            val js = runCatching { fetch(src).text }.getOrNull() ?: continue
            Regex("client_id\\s*:\\s*\"([A-Za-z0-9]{20,})\"").find(js)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** Fills in songs SoundCloud only gave as numbers, 50 at a time. Returns them by id. */
    private fun scTracks(ids: List<Long>, clientId: String): Map<Long, JSONObject> {
        val out = HashMap<Long, JSONObject>()
        for (chunk in ids.chunked(50)) {
            val p = fetch("https://api-v2.soundcloud.com/tracks?ids=${chunk.joinToString(",")}&client_id=$clientId")
            if (p.code == 401 || p.code == 403) throw IOException("SoundCloud key refused")
            val arr = runCatching { JSONArray(p.text) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out[it.optLong("id")] = it }
        }
        return out
    }

    private val scNoise = Regex(
        "\\s*[\\[({*]\\s*(free\\s*(dl|download)|out now|official\\s*(music\\s*)?(audio|video)|lyrics?(\\s*video)?|visuali[sz]er|audio|hq|hd)[^\\])}*]*[\\])}*]",
        RegexOption.IGNORE_CASE,
    )

    /**
     * SoundCloud titles are often "Artist - Title" uploaded by someone else, with extras like
     * "[Free Download]". This tidies them into a clean title and artist for matching.
     */
    private fun scTrack(t: JSONObject): Track? {
        var title = t.str("title").replace(scNoise, "").substringBefore(" | ").trim().ifEmpty { return null }
        val uploader = t.optJSONObject("user")?.str("username").orEmpty()
        val credited = t.optJSONObject("publisher_metadata")?.str("artist").orEmpty().trim()
        var artist = credited.ifEmpty { uploader }
        val dash = Regex("\\s+[-–—]\\s+").find(title)
        if (dash != null) {
            val left = title.substring(0, dash.range.first).trim()
            val right = title.substring(dash.range.last + 1).trim()
            val firstCredit = credited.split(',', '&').first().trim()
            val sameArtist = (firstCredit.isNotEmpty() && left.contains(firstCredit, true)) || (uploader.isNotEmpty() && left.contains(uploader, true))
            if (right.isNotEmpty() && (credited.isEmpty() || sameArtist)) {
                title = right
                if (credited.isEmpty()) artist = left
            }
        }
        // Go+ songs report a 30-second preview; the full length is kept separately.
        val ms = t.optLong("full_duration").takeIf { it > 0 } ?: t.optLong("duration")
        val art = (t.str("artwork_url").takeIf { it.startsWith("http") }
            ?: t.optJSONObject("user")?.str("avatar_url")?.takeIf { it.startsWith("http") && "default_avatar" !in it })
            ?.replace("-large.", "-t300x300.")
        return Track(matchUrl(title, artist, ms / 1000), title, artist, ms / 1000, art)
    }

    private suspend fun soundCloud(link: String): Imported = withContext(Dispatchers.IO) {
        val url = link.replace("://m.soundcloud.com", "://soundcloud.com")
        val page = fetch(url)
        val data = scHydration(page.text)
        val playlist = data["playlist"] as? JSONObject
        val sound = data["sound"] as? JSONObject
        if (playlist == null && sound == null) {
            val parts = Uri.parse(page.url).path.orEmpty().split('/').filter { it.isNotEmpty() }
            throw ImportProblem(
                if (parts.size == 1 && data["user"] != null) "That's a SoundCloud profile. Open one of its playlists and share that instead."
                else "Couldn't open that SoundCloud link. It may be private or deleted.",
            )
        }
        if (playlist == null) {
            val t = scTrack(sound!!) ?: throw ImportProblem("Couldn't read that SoundCloud song.")
            return@withContext Imported(t.title, sound.str("artwork_url").takeIf { it.startsWith("http") }?.replace("-large.", "-t500x500.") ?: t.thumb, listOf(t))
        }
        val rows = playlist.optJSONArray("tracks") ?: JSONArray()
        val all = (0 until minOf(rows.length(), MAX)).mapNotNull { rows.optJSONObject(it) }
        val missing = all.filter { !it.has("title") }.map { it.optLong("id") }.filter { it > 0 }
        var filled = emptyMap<Long, JSONObject>()
        if (missing.isNotEmpty()) {
            val pageKey = (data["apiClient"] as? JSONObject)?.str("id")?.ifEmpty { null }
            filled = try {
                scTracks(missing, pageKey ?: scClientId(page.text) ?: throw IOException("no key"))
            } catch (e: IOException) {
                // The page's own key can expire; try the one in SoundCloud's scripts.
                runCatching { scClientId(page.text)?.let { scTracks(missing, it) } }.getOrNull() ?: emptyMap()
            }
        }
        val tracks = all.mapNotNull { row -> (if (row.has("title")) row else filled[row.optLong("id")])?.let(::scTrack) }
        if (tracks.isEmpty()) throw ImportProblem("That SoundCloud playlist is empty or private.")
        val name = playlist.str("title").ifEmpty { "SoundCloud playlist" }
        val cover = playlist.str("artwork_url").takeIf { it.startsWith("http") }?.replace("-large.", "-t500x500.")
            ?: tracks.firstOrNull()?.thumb
        val total = minOf(maxOf(playlist.optInt("track_count", rows.length()), rows.length()), MAX)
        Imported(name, cover, tracks, if (tracks.size < total) "Imported ${songs(tracks.size)} of $name. SoundCloud didn't share the other ${total - tracks.size}." else null)
    }

    /* ---------------- Apple Music ---------------- */

    /** Apple's artwork addresses have {w}x{h} and {f} for the size and format. */
    private fun appleArt(template: String?, px: Int): String? =
        template?.takeIf { it.startsWith("http") }?.replace("{w}", "$px")?.replace("{h}", "$px")?.replace("{f}", "jpg")

    /** Apple's web player key, read from the page's main script, for the songs past the first 100. */
    private fun appleToken(html: String): String? {
        val src = Regex("src=\"(/assets/index[^\"]*\\.js)\"").find(html)?.groupValues?.get(1) ?: return null
        val js = runCatching { fetch("https://music.apple.com$src").text }.getOrNull() ?: return null
        val tokens = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").findAll(js).map { it.value }.toList()
        return tokens.firstOrNull { tok ->
            runCatching { String(Base64.decode(tok.substringBefore('.'), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)) }
                .getOrDefault("").contains("WebPlay")
        } ?: tokens.firstOrNull()
    }

    /** Songs [from] onwards of a long Apple Music playlist. Best effort: returns what it got. */
    private fun appleMore(html: String, country: String, id: String, from: Int, room: Int): List<Track> {
        val token = appleToken(html) ?: return emptyList()
        val out = mutableListOf<Track>()
        var next: String? = "/v1/catalog/$country/playlists/$id/tracks?offset=$from"
        var guard = 0
        while (next != null && out.size < room && guard++ < 20) {
            val p = runCatching {
                fetch("https://amp-api.music.apple.com$next${if ('?' in next!!) "&" else "?"}limit=100", mapOf("Authorization" to "Bearer $token", "Origin" to "https://music.apple.com"))
            }.getOrNull() ?: break
            if (p.code !in 200..299) break
            val j = runCatching { JSONObject(p.text) }.getOrNull() ?: break
            val rows = j.optJSONArray("data") ?: break
            for (i in 0 until rows.length()) {
                val a = rows.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val title = a.str("name")
                if (title.isEmpty()) continue
                val artist = a.str("artistName")
                val s = a.optLong("durationInMillis") / 1000
                out += Track(matchUrl(title, artist, s), title, artist, s, appleArt(a.optJSONObject("artwork")?.str("url"), 300))
            }
            next = j.str("next").ifEmpty { null }
        }
        return out.take(room)
    }

    private suspend fun apple(link: String): Imported = withContext(Dispatchers.IO) {
        val m = Regex("music\\.apple\\.com/([a-z]{2})/(playlist|album|song)/(?:[^/?#]+/)?(pl\\.[A-Za-z0-9._-]+|\\d+)").find(link)
            ?: throw ImportProblem("That Apple Music link isn't a playlist, album or song.")
        val (country, kind, id) = m.destructured
        val page = fetch(link)
        if (page.code == 404) throw ImportProblem("Couldn't open that Apple Music link. It may be private or deleted.")
        val html = page.text

        if (kind == "song") {
            val ld = Regex("<script id=schema:song type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
                ?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: throw ImportProblem("Couldn't read that Apple Music song.")
            val a = ld.optJSONObject("audio") ?: ld
            val title = a.str("name").ifEmpty { ld.str("name") }.ifEmpty { throw ImportProblem("Couldn't read that Apple Music song.") }
            val by = a.optJSONArray("byArtist")
            val artist = (0 until (by?.length() ?: 0)).mapNotNull { by!!.optJSONObject(it)?.str("name")?.ifEmpty { null } }.joinToString(", ")
            val s = isoSeconds(a.str("duration").ifEmpty { ld.str("timeRequired") })
            val img = a.optJSONObject("inAlbum")?.str("image")?.ifEmpty { null } ?: a.str("image").ifEmpty { null }
            return@withContext Imported(title, img, listOf(Track(matchUrl(title, artist, s), title, artist, s, img)))
        }

        val start = html.indexOf("id=\"serialized-server-data\"")
        if (start < 0) throw ImportProblem("Apple Music didn't share this one. It may be private.")
        val jsonStart = html.indexOf('>', start) + 1
        val jsonEnd = html.indexOf("</script>", jsonStart)
        val data = runCatching { JSONObject(html.substring(jsonStart, jsonEnd)) }.getOrNull()
            ?: throw ImportProblem("Apple Music didn't share this one. It may be private.")
        val sections = collect(data, "data").firstNotNullOfOrNull { it.optJSONArray("sections") } ?: JSONArray()
        var name = ""
        var cover: String? = null
        var albumArtist = ""
        var count = 0
        val tracks = mutableListOf<Track>()
        val onlySong = if (kind == "album") Uri.parse(link).getQueryParameter("i") else null
        for (i in 0 until sections.length()) {
            val s = sections.optJSONObject(i) ?: continue
            val items = s.optJSONArray("items") ?: continue
            when (s.str("itemKind")) {
                "containerDetailHeaderLockup" -> items.optJSONObject(0)?.let { h ->
                    name = h.str("title")
                    cover = appleArt(h.optJSONObject("artwork")?.optJSONObject("dictionary")?.str("url"), 600)
                    albumArtist = h.optJSONArray("subtitleLinks")?.optJSONObject(0)?.str("title").orEmpty()
                    count = h.optInt("trackCount")
                }
                "trackLockup" -> for (k in 0 until items.length()) {
                    val t = items.optJSONObject(k) ?: continue
                    if (onlySong != null && t.optJSONObject("contentDescriptor")?.optJSONObject("identifiers")?.str("storeAdamID") != onlySong) continue
                    val title = t.str("title")
                    if (title.isEmpty()) continue
                    val artist = t.str("artistName").ifEmpty { albumArtist }
                    val sec = t.optLong("duration") / 1000
                    val art = appleArt(t.optJSONObject("artwork")?.optJSONObject("dictionary")?.str("url"), 300) ?: cover
                    tracks += Track(matchUrl(title, artist, sec), title, artist, sec, art)
                }
            }
        }
        if (tracks.isEmpty()) throw ImportProblem("No songs found on that Apple Music page. It may be private.")
        if (onlySong != null) return@withContext Imported(tracks[0].title, cover, tracks.take(1))
        var note: String? = null
        // The page only lists the first 100 songs of a long playlist; ask Apple's web player for the rest.
        if (kind == "playlist" && count > tracks.size && tracks.size < MAX) {
            tracks += appleMore(html, country, id, tracks.size, MAX - tracks.size)
            if (tracks.size < minOf(count, MAX)) note = "Imported the first ${tracks.size} songs of $name. Apple Music didn't share the rest."
        }
        Imported(name.ifEmpty { "Apple Music playlist" }, cover, tracks.take(MAX), note)
    }

    /* ---------------- Audiomack ---------------- */

    /**
     * Audiomack's page carries its data in the "self.__next_f.push" pieces the website uses to draw
     * itself. This joins them back into one text.
     */
    private fun nextData(html: String): String {
        val out = StringBuilder()
        val marker = "self.__next_f.push([1,"
        var at = html.indexOf(marker)
        while (at >= 0) {
            val q = at + marker.length
            val end = html.indexOf("])</script>", q)
            if (end < 0) break
            (runCatching { JSONTokener(html.substring(q, end)).nextValue() }.getOrNull() as? String)?.let(out::append)
            at = html.indexOf(marker, end)
        }
        return out.toString()
    }

    /** The playlist, album or song called [slug] inside the page data, read whole. */
    private fun amFind(data: String, slug: String, kind: String): JSONObject? {
        var from = 0
        while (true) {
            val at = data.indexOf("\"url_slug\":${JSONObject.quote(slug)}", from)
            if (at < 0) return null
            // Step back to the start of the object this belongs to.
            var p = at
            var tries = 0
            while (tries++ < 400) {
                p = data.lastIndexOf('{', p - 1)
                if (p < 0) break
                val o = runCatching { JSONTokener(data.substring(p)).nextValue() as? JSONObject }.getOrNull()
                if (o != null && o.str("url_slug") == slug) {
                    if (o.str("type") == kind) return o
                    break
                }
            }
            from = at + 1
        }
    }

    private fun amTrack(t: JSONObject, cover: String?): Track? {
        val title = t.str("title").ifEmpty { return null }
        val artist = t.optJSONObject("artist")?.str("name") ?: t.str("artist")
        val s = t.str("duration").toLongOrNull() ?: 0
        val art = t.str("image").takeIf { it.startsWith("http") }?.let { "$it?width=300" } ?: cover
        return Track(matchUrl(title, artist, s), title, artist, s, art)
    }

    private suspend fun audiomack(link: String): Imported = withContext(Dispatchers.IO) {
        val m = Regex("audiomack\\.com/([^/?#]+)/(playlist|album|song)/([^/?#]+)").find(link)
            ?: throw ImportProblem("That Audiomack link isn't a playlist, album or song.")
        val (_, kind, slug) = m.destructured
        val page = fetch(link)
        if (page.code == 404) throw ImportProblem("Couldn't open that Audiomack link. It may be private or deleted.")
        val o = amFind(nextData(page.text), slug, kind)
            ?: throw ImportProblem("Couldn't open that Audiomack link. It may be private or deleted.")
        val cover = o.str("image").takeIf { it.startsWith("http") }
        if (kind == "song") {
            val t = amTrack(o, cover) ?: throw ImportProblem("Couldn't read that Audiomack song.")
            return@withContext Imported(t.title, cover, listOf(t))
        }
        val rows = o.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until minOf(rows.length(), MAX)).mapNotNull { i -> rows.optJSONObject(i)?.let { amTrack(it, cover) } }
        if (tracks.isEmpty()) throw ImportProblem("That Audiomack ${if (kind == "album") "album" else "playlist"} is empty or private.")
        val name = o.str("title").ifEmpty { "Audiomack $kind" }
        val total = minOf(o.optInt("track_count", rows.length()), MAX)
        Imported(name, cover, tracks, if (tracks.size < total) "Imported ${songs(tracks.size)} of $name. Audiomack didn't share the other ${total - tracks.size}." else null)
    }

    /* ---------------- Deezer ---------------- */

    private fun deezerJson(url: String): JSONObject {
        val j = runCatching { JSONObject(fetch(url).text) }.getOrNull() ?: throw IOException("Deezer didn't answer")
        if (j.has("error")) throw ImportProblem("Couldn't open that Deezer link. It may be private or deleted.")
        return j
    }

    private fun deezerTrack(t: JSONObject, cover: String?): Track? {
        val title = t.str("title").ifEmpty { return null }
        val artist = t.optJSONObject("artist")?.str("name").orEmpty()
        val s = t.optLong("duration")
        return Track(matchUrl(title, artist, s), title, artist, s, t.optJSONObject("album")?.str("cover_medium")?.ifEmpty { null } ?: cover)
    }

    private suspend fun deezer(link: String): Imported = withContext(Dispatchers.IO) {
        val rx = Regex("deezer\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(playlist|album|track)/(\\d+)")
        var m = rx.find(link)
        // Short share links (link.deezer.com/...) lead to the real address.
        if (m == null) m = fetch(link).let { p -> rx.find(p.url) ?: rx.find(p.text) }
        if (m == null) throw ImportProblem("That Deezer link isn't a playlist, album or song.")
        val (kind, id) = m.destructured
        val info = deezerJson("https://api.deezer.com/$kind/$id")
        if (kind == "track") {
            val cover = info.optJSONObject("album")?.str("cover_xl")?.ifEmpty { null }
            val t = deezerTrack(info, cover) ?: throw ImportProblem("Couldn't read that Deezer song.")
            return@withContext Imported(t.title, cover, listOf(t))
        }
        val cover = info.str("picture_xl").ifEmpty { info.str("cover_xl") }.ifEmpty { null }
        val tracks = mutableListOf<Track>()
        var next: String? = "https://api.deezer.com/$kind/$id/tracks?limit=100"
        var guard = 0
        while (next != null && tracks.size < MAX && guard++ < 20) {
            val page = deezerJson(next)
            val rows = page.optJSONArray("data") ?: break
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { deezerTrack(it, cover) }?.let(tracks::add)
            next = page.str("next").ifEmpty { null }
        }
        if (tracks.isEmpty()) throw ImportProblem("That Deezer ${if (kind == "album") "album" else "playlist"} is empty or private.")
        Imported(info.str("title").ifEmpty { "Deezer playlist" }, cover, tracks.take(MAX))
    }
}
