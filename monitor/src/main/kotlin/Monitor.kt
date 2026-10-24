// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
//
// The YouTube Music watchdog. Every half hour (on GitHub) it makes the same requests the app makes,
// with the same settings, and checks that the replies still have what the app reads. When something
// breaks it sends an alert on ntfy and the status page turns red, with a hint on where the fix goes.
//
// Run it on a PC:   gradlew -p monitor run
// Options:          --prev <status.json>  --out <status.json>
// Environment:      NTFY_TOPIC (send alerts), RUNNER (shown on the page), STATUS_URL (alert link)

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.TimeUnit

/* ---------------- What the app sends (read from the app's own source) ---------------- */

val SRC = "app/src/main/java/com/glasswave/player"
fun srcConst(file: String, rx: String, fallback: String): String =
    runCatching { Regex(rx).find(File("$SRC/$file").readText())!!.groupValues[1] }.getOrDefault(fallback)

val CLIENT_VERSION = srcConst("YtMusicApi.kt", "CLIENT_VERSION = \"([^\"]+)\"", "1.20251015.01.00")
val USER_AGENT = srcConst("YtDownloader.kt", "USER_AGENT =\\s*\"([^\"]+)\"", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
val IOS_VERSION = srcConst("Loudness.kt", "\"clientVersion\", \"([^\"]+)\"", "20.10.4")
val NEWPIPE = System.getenv("NEWPIPE_VERSION") ?: "unknown"
const val ORIGIN = "https://music.youtube.com"

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
val JSON_TYPE = "application/json".toMediaType()

/* ---------------- Results ---------------- */

/** A check passed but something is off (shown in yellow, small heads-up only). */
class Warn(msg: String) : Exception(msg)
/** YouTube asked the test server itself to prove it isn't a bot. Says nothing about the app's users. */
class Blocked(msg: String) : Exception(msg)
class Fail(msg: String) : Exception(msg)

class Check(
    val id: String,
    val group: String,
    val name: String,
    /** What people using the app would notice if this breaks. */
    val impact: String,
    /** Where the fix usually goes. */
    val fix: String,
    val critical: Boolean = true,
    val body: () -> String,
)

class Outcome(val check: Check, val status: String, val detail: String, val ms: Long)

fun fail(msg: String): Nothing = throw Fail(msg)
fun need(ok: Boolean, msg: () -> String) { if (!ok) fail(msg()) }

/* ---------------- Requests ---------------- */

fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
    val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
    headers.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { r -> return r.code to (r.body?.string() ?: "") }
}

/** One YouTube Music call, built exactly like YtMusicApi.call (signed out). */
fun yt(endpoint: String, body: JSONObject, query: String = ""): JSONObject {
    body.put("context", JSONObject().put("client", JSONObject()
        .put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "en")))
    val req = Request.Builder()
        .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false$query")
        .post(body.toString().toRequestBody(JSON_TYPE))
        .header("User-Agent", USER_AGENT)
        .header("X-Origin", ORIGIN).header("Origin", ORIGIN).header("Referer", "$ORIGIN/")
        .build()
    http.newCall(req).execute().use { r ->
        val text = r.body?.string() ?: ""
        if (r.code == 429) throw Blocked("YouTube Music rate-limited the test server (429)")
        if (r.code !in 200..299) {
            val why = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            fail("YouTube Music replied ${r.code}" + (why?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
        }
        return runCatching { JSONObject(text) }.getOrElse { fail("YouTube Music's reply wasn't readable") }
    }
}

/* ---------------- Reading replies (same rules as YtMusicApi) ---------------- */

fun collect(node: Any?, key: String, out: MutableList<JSONObject> = mutableListOf()): List<JSONObject> {
    when (node) {
        is JSONObject -> for (k in node.keys()) {
            val v = node.opt(k)
            if (k == key && v is JSONObject) out += v
            collect(v, key, out)
        }
        is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
    }
    return out
}

fun JSONObject.path(p: String): Any? {
    var cur: Any? = this
    for (part in p.split('.')) cur = when (cur) {
        is JSONObject -> cur.opt(part)
        is JSONArray -> part.toIntOrNull()?.let { cur.opt(it) }
        else -> return null
    }
    return cur
}

fun ytText(node: Any?): String = when (node) {
    is JSONObject -> node.optString("simpleText").ifEmpty {
        node.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text") ?: "" } } ?: ""
    }
    else -> ""
}

fun findString(node: Any?, key: String): String? {
    when (node) {
        is JSONObject -> {
            (node.opt(key) as? String)?.let { return it }
            for (k in node.keys()) findString(node.opt(k), key)?.let { return it }
        }
        is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key)?.let { return it }
    }
    return null
}

data class Item(val title: String, val line: String, val videoId: String?, val browseId: String?, val type: String)
data class Shelf(val title: String, val items: List<Item>)

val itemKinds = listOf("musicTwoRowItemRenderer", "musicResponsiveListItemRenderer")
val shelfKinds = listOf(
    "musicCardShelfRenderer", "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer",
    "musicShelfRenderer", "gridRenderer", "musicPlaylistShelfRenderer",
)

fun item(o: JSONObject, kind: String): Item? {
    fun pageType(nav: Any?) = findString(nav, "pageType") ?: ""
    return when (kind) {
        "musicTwoRowItemRenderer" -> {
            val nav = o.optJSONObject("navigationEndpoint")
            val v = nav?.path("watchEndpoint.videoId") as? String
            val b = nav?.path("browseEndpoint.browseId") as? String
            if (v == null && b == null) null else Item(ytText(o.opt("title")), ytText(o.opt("subtitle")), v, b, pageType(nav))
        }
        "musicResponsiveListItemRenderer" -> {
            val title = ytText(o.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text"))
            val line = ytText(o.path("flexColumns.1.musicResponsiveListItemFlexColumnRenderer.text"))
            val v = (o.path("playlistItemData.videoId") as? String)
                ?: (o.path("flexColumns.0.musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.watchEndpoint.videoId") as? String)
                ?: findString(o.opt("overlay"), "videoId")
            val nav = o.optJSONObject("navigationEndpoint")
            val b = nav?.path("browseEndpoint.browseId") as? String
            if (v == null && b == null) null else Item(title, line, v, b, pageType(nav))
        }
        "musicCardShelfRenderer" -> {
            val nav = o.path("title.runs.0.navigationEndpoint") as? JSONObject
            val v = nav?.path("watchEndpoint.videoId") as? String
            val b = nav?.path("browseEndpoint.browseId") as? String
            if (v == null && b == null) null else Item(ytText(o.opt("title")), ytText(o.opt("subtitle")), v, b, pageType(nav))
        }
        else -> null
    }
}

fun items(node: Any?): List<Item> {
    val out = mutableListOf<Item>()
    fun walk(n: Any?) {
        when (n) {
            is JSONObject -> {
                for (k in itemKinds) n.optJSONObject(k)?.let { r -> item(r, k)?.let(out::add); return }
                for (k in n.keys()) if (k != "menu" && k != "header") walk(n.opt(k))
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
        }
    }
    walk(node)
    return out
}

fun shelves(json: JSONObject): List<Shelf> {
    val out = mutableListOf<Shelf>()
    fun walk(n: Any?) {
        when (n) {
            is JSONObject -> {
                for (k in shelfKinds) {
                    val s = n.optJSONObject(k) ?: continue
                    if (k == "musicCardShelfRenderer") {
                        out += Shelf("Top result", listOfNotNull(item(s, k)) + items(s.opt("contents")))
                    } else {
                        val title = ytText(s.path("header.musicCarouselShelfBasicHeaderRenderer.title"))
                            .ifEmpty { ytText(s.path("header.musicImmersiveCarouselShelfBasicHeaderRenderer.title")) }
                            .ifEmpty { ytText(s.opt("title")) }
                            .ifEmpty { ytText(s.path("header.gridHeaderRenderer.title")) }
                        val list = items(s.opt("contents") ?: s.opt("items"))
                        if (list.isNotEmpty()) out += Shelf(title, list)
                    }
                    return
                }
                for (k in n.keys()) walk(n.opt(k))
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
        }
    }
    walk(json)
    return out
}

fun nextPage(node: Any?): Boolean =
    collect(node, "continuationCommand").any { it.optString("token").isNotEmpty() } ||
        collect(node, "nextContinuationData").any { it.optString("continuation").isNotEmpty() }

val durationRx = Regex("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b")
fun hasLength(line: String) = durationRx.containsMatchIn(line)

/* ---------------- Shared between checks ---------------- */

const val CLASSIC = "dQw4w9WgXcQ" // Never Gonna Give You Up: never going away
var searchSong: Item? = null
var homeSong: Item? = null

fun songsFilter() = java.net.URLDecoder.decode("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D", "UTF-8")
fun albumsFilter() = java.net.URLDecoder.decode("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D", "UTF-8")
fun artistsFilter() = java.net.URLDecoder.decode("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D", "UTF-8")

fun panel(json: JSONObject) = collect(json, "playlistPanelVideoRenderer")

/* ---------------- Playing (NewPipeExtractor, like YtRepo) ---------------- */

class PcDownloader : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
        val data = request.dataToSend()
        val body: RequestBody? = data?.toRequestBody(null, 0, data.size)
        val b = Request.Builder().method(request.httpMethod(), body).url(request.url()).addHeader("User-Agent", USER_AGENT)
        for ((name, values) in request.headers()) { b.removeHeader(name); for (v in values) b.addHeader(name, v) }
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return org.schabi.newpipe.extractor.downloader.Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

val botWords = listOf("not a bot", "confirm you", "sign in to confirm", "login_required", "recaptcha", "captcha")
fun looksBlocked(e: Throwable): Boolean {
    var c: Throwable? = e
    while (c != null) {
        if (c is ReCaptchaException) return true
        val m = (c.message ?: "").lowercase()
        if (botWords.any { m.contains(it) }) return true
        c = c.cause
    }
    return false
}

fun rootMessage(e: Throwable): String {
    var c: Throwable = e
    while (c.cause != null && c.cause !== c) c = c.cause!!
    val outer = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    val inner = c.message?.takeIf { it.isNotBlank() } ?: c.javaClass.simpleName
    return (if (c === e) outer else "$outer: $inner").take(300)
}

/** Picks the audio the way YtRepo does, then downloads the first 256 KB to prove it plays. */
fun playable(videoId: String, label: String): String {
    val t0 = System.currentTimeMillis()
    val ex = try {
        ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId").also { it.fetchPage() }
    } catch (e: Exception) {
        if (looksBlocked(e)) throw Blocked("YouTube asked the test server to prove it isn't a bot")
        fail("Couldn't read the song's streams: ${rootMessage(e)}")
    }
    val title = runCatching { ex.name }.getOrDefault(label)
    val streams = try { ex.audioStreams } catch (e: Exception) {
        if (looksBlocked(e)) throw Blocked("YouTube asked the test server to prove it isn't a bot")
        fail("Couldn't read the audio list for \"$title\": ${rootMessage(e)}")
    }
    val ok = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
    need(ok.isNotEmpty()) { "\"$title\" has no audio the app can play (${streams.size} streams, none direct)" }
    val best = listOf(251, 140, 250, 249).firstNotNullOfOrNull { tag -> ok.firstOrNull { it.itag == tag } }
        ?: ok.maxByOrNull { maxOf(it.averageBitrate, it.bitrate / 1000) }!!
    val lookup = System.currentTimeMillis() - t0
    val (code, bytes) = http.newCall(
        Request.Builder().url(best.content).header("User-Agent", USER_AGENT).header("Range", "bytes=0-262143").build(),
    ).execute().use { r -> r.code to (r.body?.bytes()?.size ?: 0) }
    need(code == 200 || code == 206) { "YouTube refused the audio for \"$title\" ($code). Songs would stop or skip." }
    need(bytes >= 100_000) { "Audio for \"$title\" came back almost empty ($bytes bytes)" }
    val kbps = maxOf(best.averageBitrate, best.bitrate / 1000)
    val detail = "\"$title\" plays (format ${best.itag}${if (kbps > 0) ", $kbps kbps" else ""}, found in ${"%.1f".format(lookup / 1000.0)} s)"
    if (lookup > 9000) throw Warn("$detail. Slow: songs take long to start.")
    return detail
}

/* ---------------- The checks ---------------- */

const val FIX_PLAY = "Move NewPipeExtractor in app/build.gradle.kts to the newest version, then release."
const val FIX_YT = "YouTube changed its replies: YtMusicApi.kt reads them (see the detail for what's missing)."

fun checks(): List<Check> = listOf(
    Check("home", "Home & search", "Home feed", "Home would be empty or missing shelves", FIX_YT) {
        val j = yt("browse", JSONObject().put("browseId", "FEmusic_home"))
        val s = shelves(j).filter { it.items.isNotEmpty() }
        need(s.size >= 2) { "Only ${s.size} shelf came back (the app expects several)" }
        val songs = s.flatMap { it.items }.filter { it.videoId != null }
        need(songs.size >= 5) { "Home has only ${songs.size} playable songs" }
        homeSong = songs.firstOrNull { it.videoId != CLASSIC }
        val names = s.map { it.title }.filter { it.isNotBlank() }.take(3).joinToString(", ")
        val detail = "${s.size} shelves, ${songs.size} songs ($names)"
        if (!nextPage(collect(j, "sectionListRenderer").firstOrNull())) throw Warn("$detail. No 'more' link, so Home is shorter than usual.")
        detail
    },
    Check("search", "Home & search", "Search songs", "Search would show nothing", FIX_YT) {
        val j = yt("search", JSONObject().put("query", "Ravyn Lenae Love Me Not").put("params", songsFilter()))
        val songs = shelves(j).flatMap { it.items }.filter { it.videoId != null }
        need(songs.size >= 5) { "Only ${songs.size} songs came back" }
        searchSong = songs.firstOrNull { it.title.contains("love me not", true) } ?: songs.first()
        val withLength = songs.count { hasLength(it.line) }
        need(withLength >= songs.size / 2) { "Song lengths are missing from results (${withLength}/${songs.size})" }
        val detail = "${songs.size} songs, top: ${searchSong!!.title} · ${searchSong!!.line.substringBefore(" • ")}"
        if (!nextPage(j)) throw Warn("$detail. No 'load more' link, so results stop at one page.")
        detail
    },
    Check("search_all", "Home & search", "Search (All tab)", "The All tab would lose its Top result or sections", FIX_YT, critical = false) {
        val j = yt("search", JSONObject().put("query", "Ravyn Lenae"))
        val s = shelves(j)
        need(s.isNotEmpty()) { "No sections came back" }
        val others = s.filter { it.title != "Top result" }
        val loose = collect(j, "itemSectionRenderer").count { sec -> items(sec.opt("contents")).isNotEmpty() }
        need(others.isNotEmpty()) {
            if (loose > 0) "YouTube now sends All results as one flat list ($loose rows, no Songs/Artists/Albums shelves). The All tab only shows Top result and songs."
            else "Only the Top result came back; no Songs/Artists/Albums shelves"
        }
        val detail = "${s.size} sections: " + s.joinToString(", ") { it.title.ifBlank { "untitled" } }.take(120)
        if (s.none { it.title == "Top result" }) throw Warn("$detail. No Top result card.")
        detail
    },
    Check("suggest", "Home & search", "Search suggestions", "No suggestions while typing", FIX_YT, critical = false) {
        val j = yt("music/get_search_suggestions", JSONObject().put("input", "love me"))
        val q = collect(j, "searchSuggestionRenderer").map { ytText(it.opt("suggestion")) }.filter { it.isNotBlank() }
        need(q.size >= 3) { "Only ${q.size} suggestions" }
        "${q.size} suggestions (${q.take(3).joinToString(", ")})"
    },
    Check("song_info", "Song pages", "Song details", "Titles, artists and 'Go to album/artist' would break", FIX_YT) {
        val id = searchSong?.videoId ?: CLASSIC
        val r = panel(yt("next", JSONObject().put("videoId", id).put("isAudioOnly", true))).firstOrNull { it.optString("videoId") == id }
            ?: fail("The song wasn't in YouTube's reply")
        val title = ytText(r.opt("title"))
        val by = ytText(r.opt("longBylineText") ?: r.opt("shortBylineText"))
        need(title.isNotBlank()) { "The song came back without a title" }
        need(by.isNotBlank()) { "The song came back without an artist" }
        need(ytText(r.opt("lengthText")).isNotBlank()) { "The song came back without its length" }
        "$title · ${by.substringBefore(" • ")} · ${ytText(r.opt("lengthText"))}"
    },
    Check("radio", "Autoplay & Automix", "Autoplay radio", "Autoplay and Automix would stop after the queue ends", FIX_YT) {
        val id = searchSong?.videoId ?: CLASSIC
        val j = yt("next", JSONObject().put("videoId", id).put("playlistId", "RDAMVM$id")
            .put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true))
        val ids = panel(j).mapNotNull { r -> r.optString("videoId").takeIf { it.isNotEmpty() && it != id && ytText(r.opt("title")).isNotBlank() } }.distinct()
        need(ids.size >= 10) { "Only ${ids.size} songs in the radio (the app expects about 50)" }
        "${ids.size} similar songs"
    },
    Check("album", "Song pages", "Album pages", "Albums would open empty", FIX_YT) {
        val res = yt("search", JSONObject().put("query", "Ravyn Lenae Bird's Eye").put("params", albumsFilter()))
        val album = shelves(res).flatMap { it.items }.firstOrNull { it.browseId?.startsWith("MPRE") == true }
            ?: fail("Album search came back without albums")
        val j = yt("browse", JSONObject().put("browseId", album.browseId))
        val tracks = shelves(j).flatMap { it.items }.filter { it.videoId != null }
        need(tracks.size >= 3) { "\"${album.title}\" opened with ${tracks.size} songs" }
        val headers = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")
        val detail = "\"${album.title}\": ${tracks.size} songs"
        if (headers.none { collect(j, it).isNotEmpty() }) throw Warn("$detail. The album header (title and cover) is missing.")
        detail
    },
    Check("artist", "Song pages", "Artist pages", "Artist pages would open empty", FIX_YT) {
        val res = yt("search", JSONObject().put("query", "Ravyn Lenae").put("params", artistsFilter()))
        val artist = shelves(res).flatMap { it.items }.firstOrNull { it.browseId?.startsWith("UC") == true }
            ?: fail("Artist search came back without artists")
        val s = shelves(yt("browse", JSONObject().put("browseId", artist.browseId))).filter { it.items.isNotEmpty() }
        need(s.size >= 2) { "\"${artist.title}\" opened with ${s.size} sections" }
        "\"${artist.title}\": ${s.size} sections (${s.take(3).joinToString(", ") { it.title }})"
    },
    Check("genres", "Song pages", "Moods & genres", "The Moods & genres tiles in Search would disappear", FIX_YT, critical = false) {
        val j = yt("browse", JSONObject().put("browseId", "FEmusic_moods_and_genres"))
        val g = collect(j, "musicNavigationButtonRenderer").filter { (it.path("clickCommand.browseEndpoint.browseId") as? String)?.isNotEmpty() == true }
        need(g.size >= 10) { "Only ${g.size} tiles" }
        "${g.size} tiles"
    },
    Check("play_search", "Playing songs", "Play a searched song", "Songs wouldn't play", FIX_PLAY) {
        playable(searchSong?.videoId ?: CLASSIC, searchSong?.title ?: "song")
    },
    Check("play_classic", "Playing songs", "Play an older song", "Songs wouldn't play", FIX_PLAY) {
        playable(CLASSIC, "Never Gonna Give You Up")
    },
    Check("play_home", "Playing songs", "Play a song from Home", "Songs wouldn't play", FIX_PLAY) {
        val s = homeSong ?: throw Warn("Skipped: Home didn't load")
        playable(s.videoId!!, s.title)
    },
    Check("video", "Playing songs", "Music video backgrounds", "The moving video behind the cover wouldn't show", FIX_PLAY, critical = false) {
        val ex = try {
            ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$CLASSIC").also { it.fetchPage() }
        } catch (e: Exception) {
            if (looksBlocked(e)) throw Blocked("YouTube asked the test server to prove it isn't a bot")
            fail(rootMessage(e))
        }
        val v = ex.videoOnlyStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.height in 1..480 }
        need(v.isNotEmpty()) { "No small video streams" }
        "${v.size} video sizes" + if (v.none { it.format == MediaFormat.MPEG_4 }) " (no MP4)" else ""
    },
    Check("yt_lyrics", "Lyrics", "YouTube Music lyrics", "Lyrics for songs missing from LRCLIB and KuGou would disappear", FIX_YT, critical = false) {
        val text = listOfNotNull(searchSong?.videoId, CLASSIC).firstNotNullOfOrNull { id ->
            val next = yt("next", JSONObject().put("videoId", id).put("isAudioOnly", true))
            collect(next, "browseEndpoint").map { it.optString("browseId") }.firstOrNull { it.startsWith("MPLYt") }?.let { lid ->
                collect(yt("browse", JSONObject().put("browseId", lid)), "musicDescriptionShelfRenderer")
                    .firstOrNull()?.let { ytText(it.opt("description")) }?.takeIf { it.isNotBlank() }
            }
        } ?: fail("No lyrics came back for two songs that have them")
        "${text.lines().size} lines"
    },
    Check("lrclib", "Lyrics", "Synced lyrics (LRCLIB)", "Most synced lyrics would be missing", "LRCLIB changed or is down: Lyrics.kt (lrclib).", critical = false) {
        val (code, body) = get("https://lrclib.net/api/search?track_name=Love%20Me%20Not&artist_name=Ravyn%20Lenae")
        if (code == 429 || code == 503) throw Warn("LRCLIB is busy ($code)")
        need(code == 200) { "LRCLIB replied $code" }
        val arr = runCatching { JSONArray(body) }.getOrElse { fail("LRCLIB's reply wasn't readable") }
        val synced = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.count { it.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" } }
        need(synced > 0) { "No synced lyrics in ${arr.length()} results" }
        "$synced synced versions found"
    },
    Check("kugou", "Lyrics", "Synced lyrics (KuGou)", "Backup synced lyrics would be missing", "KuGou changed: Lyrics.kt (kugou).", critical = false) {
        val kw = URLEncoder.encode("Ravyn Lenae - Love Me Not", "UTF-8")
        val s = JSONObject(get("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$kw").second)
        val info = s.optJSONObject("data")?.optJSONArray("info") ?: fail("KuGou search came back empty")
        val song = (0 until info.length()).mapNotNull { info.optJSONObject(it) }.firstOrNull { it.optString("hash").isNotEmpty() }
            ?: fail("KuGou found no songs")
        val c = JSONObject(get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$kw&duration=${song.optLong("duration") * 1000}&hash=${song.optString("hash")}").second)
            .optJSONArray("candidates")?.optJSONObject(0) ?: fail("KuGou had no lyrics for the test song")
        val d = JSONObject(get("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=${c.optString("id")}&accesskey=${c.optString("accesskey")}").second)
        val text = String(Base64.getDecoder().decode(d.optString("content")), Charsets.UTF_8)
        val lines = text.lines().count { it.matches(Regex("\\[\\d+:\\d+.*\\].+")) }
        need(lines >= 4) { "KuGou's lyrics came back with $lines lines" }
        "$lines timed lines"
    },
    Check("loudness", "Volume & extras", "Volume leveling", "Loud and quiet songs wouldn't be evened out", "YouTube changed the iOS player reply: Loudness.kt.", critical = false) {
        val body = JSONObject().put("videoId", CLASSIC).put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "IOS").put("clientVersion", IOS_VERSION).put("deviceMake", "Apple")
            .put("deviceModel", "iPhone16,2").put("osName", "iPhone").put("osVersion", "18.3.2.22D82").put("hl", "en")))
        val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", "com.google.ios.youtube/$IOS_VERSION (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)").build()
        val j = http.newCall(req).execute().use { r ->
            need(r.isSuccessful) { "YouTube replied ${r.code}" }
            JSONObject(r.body?.string() ?: "{}")
        }
        val status = j.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status == "LOGIN_REQUIRED") throw Blocked("YouTube asked the test server to sign in")
        val f = j.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
        val db = f?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) }.firstOrNull { o -> o.optString("mimeType").startsWith("audio/") && o.has("loudnessDb") }?.optDouble("loudnessDb") }
            ?: j.optJSONObject("playerConfig")?.optJSONObject("audioConfig")?.optDouble("perceptualLoudnessDb", Double.NaN)?.takeIf { !it.isNaN() }?.plus(14)
        need(db != null) { "No loudness figure in YouTube's reply (status: ${status.ifEmpty { "none" }})" }
        "Loudness ${"%+.1f".format(db)} dB"
    },
    Check("spotify", "Spotify", "Spotify playlist links", "Importing Spotify links and syncing followed playlists would fail", "Spotify changed its embed page: SpotifyApi.kt (importLink / publicPlaylist).") {
        val (code, html) = get("https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M")
        need(code == 200) { "Spotify replied $code" }
        val start = html.indexOf("<script id=\"__NEXT_DATA__\"")
        need(start >= 0) { "The page no longer has the song list the app reads (__NEXT_DATA__)" }
        val js = html.indexOf('>', start) + 1
        val data = JSONObject(html.substring(js, html.indexOf("</script>", js)))
        fun findArray(n: Any?, key: String): JSONArray? {
            when (n) {
                is JSONObject -> { (n.opt(key) as? JSONArray)?.let { return it }; for (k in n.keys()) findArray(n.opt(k), key)?.let { return it } }
                is JSONArray -> for (i in 0 until n.length()) findArray(n.opt(i), key)?.let { return it }
            }
            return null
        }
        val list = findArray(data, "trackList") ?: fail("No trackList in the page")
        val good = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .count { it.optString("title").isNotBlank() && it.optString("subtitle").isNotBlank() && it.optLong("duration") > 0 }
        need(good >= 20) { "Only $good songs readable (title, artist, length)" }
        "$good songs read from a public playlist"
    },
    Check("updates", "App updates", "In-app updates", "The app couldn't find or download updates", "Check the newest GitHub release has an asset named Glasswave.apk.") {
        val (code, body) = get("https://api.github.com/repos/uronlynonny/Glasswave/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
        if (code == 403 || code == 429) throw Warn("GitHub's rate limit hit ($code)")
        need(code == 200) { "GitHub replied $code (is there a release?)" }
        val r = JSONObject(body)
        val apk = r.optJSONArray("assets")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("name") == "Glasswave.apk" } }
            ?: fail("The newest release (${r.optString("tag_name")}) has no Glasswave.apk")
        val dl = http.newCall(Request.Builder().url(apk.optString("browser_download_url")).head().build()).execute().use { it.code }
        need(dl == 200) { "The APK download replied $dl" }
        "${r.optString("tag_name")}: Glasswave.apk, ${"%.1f".format(apk.optLong("size") / 1048576.0)} MB"
    },
    Check("newpipe_version", "Heads-up", "YouTube library version", "Nothing yet: newer versions often fix YouTube changes before they hit", FIX_PLAY, critical = false) {
        val (code, body) = get("https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
        if (code != 200) throw Warn("Couldn't ask GitHub ($code)")
        val latest = JSONObject(body).optString("tag_name")
        fun nums(v: String) = v.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        val newer = nums(latest).zip(nums(NEWPIPE)).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: (nums(latest).size > nums(NEWPIPE).size)
        if (newer) throw Warn("$latest is out; the app uses $NEWPIPE")
        "The app uses the newest ($NEWPIPE)"
    },
    Check("client_version", "Heads-up", "YouTube Music web version", "Nothing yet: YouTube may stop accepting very old versions", "Update CLIENT_VERSION in YtMusicApi.kt.", critical = false) {
        val (_, html) = get("$ORIGIN/", mapOf("Accept-Language" to "en"))
        val live = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([0-9.]+)\"").find(html)?.groupValues?.get(1)
            ?: throw Warn("Couldn't read YouTube Music's current version")
        fun date(v: String) = runCatching { LocalDate.parse(v.split('.')[1].take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        val days = date(CLIENT_VERSION)?.let { a -> date(live)?.let { b -> ChronoUnit.DAYS.between(a, b) } }
        val detail = "YouTube Music is on $live; the app sends $CLIENT_VERSION" + (days?.let { " ($it days older)" } ?: "")
        if ((days ?: 0) > 300) throw Warn(detail)
        detail
    },
)

/* ---------------- Running, history and alerts ---------------- */

fun runOne(c: Check): Outcome {
    var last: Throwable? = null
    val t0 = System.currentTimeMillis()
    for (attempt in 0 until 3) {
        try {
            return Outcome(c, "ok", c.body(), System.currentTimeMillis() - t0)
        } catch (w: Warn) {
            return Outcome(c, "warn", w.message ?: "", System.currentTimeMillis() - t0)
        } catch (b: Blocked) {
            return Outcome(c, "blocked", b.message ?: "", System.currentTimeMillis() - t0)
        } catch (e: Throwable) {
            last = e
            if (looksBlocked(e)) return Outcome(c, "blocked", "YouTube asked the test server to prove it isn't a bot", System.currentTimeMillis() - t0)
            if (attempt < 2) Thread.sleep(if (attempt == 0) 5_000 else 20_000)
        }
    }
    val msg = (last as? Fail)?.message ?: rootMessage(last!!)
    return Outcome(c, "fail", msg, System.currentTimeMillis() - t0)
}

const val KEEP = 336 // a week of half-hourly runs
val code = mapOf("ok" to 'o', "warn" to 'w', "fail" to 'x', "blocked" to 'b')

fun main(args: Array<String>) {
    fun arg(name: String) = args.toList().zipWithNext().firstOrNull { it.first == name }?.second
    val prevFile = arg("--prev")?.let(::File)
    val outFile = arg("--out")?.let(::File)
    val topic = System.getenv("NTFY_TOPIC")?.takeIf { it.isNotBlank() }
    val runner = System.getenv("RUNNER") ?: "PC"
    val statusUrl = System.getenv("STATUS_URL") ?: "https://uronlynonny.github.io/Glasswave/status.html"

    NewPipe.init(PcDownloader())
    val now = System.currentTimeMillis() / 1000
    val results = checks().map { c ->
        runOne(c).also { o -> println("${o.status.uppercase().padEnd(7)} ${c.name}: ${o.detail} (${o.ms} ms)") }
    }

    val prev = prevFile?.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
    val prevChecks = prev?.optJSONArray("checks")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getString("id") to o } } } ?: emptyMap()
    val runs = (prev?.optJSONArray("runs")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()) + now
    val keptRuns = runs.takeLast(KEEP)

    val broke = mutableListOf<Outcome>()
    val fixed = mutableListOf<Outcome>()
    val stillBroken = mutableListOf<Outcome>()
    val newWarn = mutableListOf<Outcome>()

    val out = JSONArray()
    for (o in results) {
        val p = prevChecks[o.check.id]
        val pStatus = p?.optString("status") ?: "none"
        // History line: one letter per run, padded so it lines up with "runs".
        val oldH = p?.optString("h").orEmpty().padStart(runs.size - 1, '-')
        val h = (oldH + code.getValue(o.status)).takeLast(KEEP)
        var since = if (pStatus == o.status) p?.optLong("since") ?: now else now
        var alertedAt = p?.optLong("alertedAt") ?: 0L
        when {
            o.status == "fail" && pStatus != "fail" -> { broke += o; alertedAt = now }
            o.status == "fail" && now - alertedAt >= 6 * 3600 -> { stillBroken += o; alertedAt = now }
            o.status != "fail" && pStatus == "fail" && o.status != "blocked" -> fixed += o
            o.status == "warn" && pStatus != "warn" && pStatus != "none" && o.check.group == "Heads-up" -> newWarn += o
        }
        if (o.status == "blocked" && pStatus == "fail") since = p?.optLong("since") ?: now // a block doesn't mean fixed
        out.put(JSONObject()
            .put("id", o.check.id).put("group", o.check.group).put("name", o.check.name)
            .put("impact", o.check.impact).put("fix", o.check.fix).put("critical", o.check.critical)
            .put("status", o.status).put("detail", o.detail).put("ms", o.ms)
            .put("since", since).put("alertedAt", alertedAt).put("h", h))
    }

    val status = JSONObject()
        .put("updated", now).put("runner", runner)
        .put("app", JSONObject().put("newpipe", NEWPIPE).put("clientVersion", CLIENT_VERSION).put("iosVersion", IOS_VERSION))
        .put("runs", JSONArray(keptRuns)).put("checks", out)
    outFile?.let { it.parentFile?.mkdirs(); it.writeText(status.toString(1)) }

    val failing = results.count { it.status == "fail" }
    val blocked = results.count { it.status == "blocked" }
    println("\n${results.size} checks: ${results.count { it.status == "ok" }} ok, ${results.count { it.status == "warn" }} heads-up, $failing broken, $blocked blocked")

    if (topic == null) return
    fun line(o: Outcome) = "• ${o.check.name}: ${o.detail}\n  Users notice: ${o.check.impact}\n  Fix: ${o.check.fix}"
    if (broke.isNotEmpty()) {
        val crit = broke.any { it.check.critical }
        notify(topic, statusUrl,
            title = if (broke.size == 1) "Glasswave: ${broke[0].check.name} broke" else "Glasswave: ${broke.size} things broke",
            body = broke.joinToString("\n\n", transform = ::line),
            priority = if (crit) 5 else 4, tags = "rotating_light")
    }
    if (stillBroken.isNotEmpty()) notify(topic, statusUrl,
        title = "Glasswave: still broken",
        body = stillBroken.joinToString("\n\n", transform = ::line),
        priority = 3, tags = "warning")
    if (fixed.isNotEmpty()) notify(topic, statusUrl,
        title = "Glasswave: working again",
        body = fixed.joinToString("\n") { "• ${it.check.name}: ${it.detail}" },
        priority = 3, tags = "white_check_mark")
    if (newWarn.isNotEmpty()) notify(topic, statusUrl,
        title = "Glasswave: heads-up",
        body = newWarn.joinToString("\n") { "• ${it.check.name}: ${it.detail}\n  ${it.check.fix}" },
        priority = 2, tags = "eyes")
}

fun notify(topic: String, click: String, title: String, body: String, priority: Int, tags: String) {
    val req = Request.Builder().url("https://ntfy.sh/$topic")
        .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        .header("Title", title).header("Priority", priority.toString()).header("Tags", tags).header("Click", click)
        .build()
    runCatching { http.newCall(req).execute().close() }.onFailure { println("Couldn't send the alert: ${it.message}") }
}
