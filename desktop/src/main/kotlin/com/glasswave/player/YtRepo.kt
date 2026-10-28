// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Searching YouTube Music and finding the audio for a song.
 * If YouTube changes and playback breaks, this is the first place to look
 * (usually just a newer NewPipeExtractor version in app/build.gradle.kts).
 */
object YtRepo {
    /** One shared connection pool for the whole app. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ready = false

    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val URL_LIFETIME_MS = 3 * 60 * 60 * 1000L

    /** Spotify song -> YouTube Music song it was matched to. Saved so each song is only searched once. */
    private var matches: SharedPreferences? = null

    @Synchronized
    fun init() {
        if (!ready) {
            NewPipe.init(YtDownloader(http))
            ready = true
        }
    }

    fun initMatches(context: Context) {
        matches = context.getSharedPreferences("matches", Context.MODE_PRIVATE)
        explicit = context.getSharedPreferences("explicit", Context.MODE_PRIVATE)
        ageAlt = context.getSharedPreferences("age_alt", Context.MODE_PRIVATE)
    }

    /** 18+ song -> another upload of it that plays without signing in. Saved so it's found once. */
    private var ageAlt: SharedPreferences? = null

    private fun ageRestricted(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException }

    private val notTheSame = Regex("""\b(slowed|sped|speed|nightcore|reverb|remix|cover|instrumental|karaoke|8d|bass ?boosted|1 ?hour|loop)\b""", RegexOption.IGNORE_CASE)

    /**
     * YouTube marks some songs 18+, and those only play when signed in to an age-checked account.
     * Glasswave plays music, so it finds another upload of the same song (a lyric video, a re-upload)
     * that isn't marked: same name, about the same length, mentioning the artist if possible, and
     * not a slowed, sped up or remixed version. Gives that upload's audio, or null if there's none.
     */
    private fun otherUpload(videoUrl: String): List<org.schabi.newpipe.extractor.stream.AudioStream>? {
        val id = android.net.Uri.parse(videoUrl).getQueryParameter("v") ?: return null
        ageAlt?.getString(id, null)?.let { known ->
            runCatching { extractor(ytWatchUrl(known)).audioStreams }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val info = runCatching { YtMusicApi.trackInfo(id) }.getOrNull() ?: return null
        val lead = info.artist.substringBefore(",").substringBefore(" & ").trim()
        val leadKey = lead.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        class Upload(val id: String, val name: String, val by: String, val seconds: Long)
        fun mentionsArtist(c: Upload) = leadKey.isNotEmpty() &&
            "${c.name} ${c.by}".lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "").contains(leadKey)
        // Some uploads blank out part of the name ("Anime B" for an 18+ title). Those still count
        // when they're from the artist, share a word with the title and are the same length.
        val words = info.title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        fun censoredName(c: Upload) = mentionsArtist(c) && info.seconds > 0 && c.seconds > 0 &&
            abs(c.seconds - info.seconds) <= 2 && c.name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).any { it in words }
        fun pick(found: List<Upload>) = found.filter { c ->
            c.id != id && (sameSong(info.title, c.name) || censoredName(c)) &&
                (notTheSame.containsMatchIn(info.title) || !notTheSame.containsMatchIn(c.name)) &&
                (info.seconds <= 0 || c.seconds <= 0 || abs(c.seconds - info.seconds) <= 12)
        }.distinctBy { it.id }
            .sortedWith(compareBy({ if (mentionsArtist(it)) 0 else 1 }, { if (info.seconds > 0 && it.seconds > 0) abs(it.seconds - info.seconds) else 0 }))
        // YouTube Music's video search first (the same search the app uses everywhere), then YouTube's.
        fun musicVideos(q: String) = runCatching {
            kotlinx.coroutines.runBlocking { YtMusicApi.searchVideos(q) }.map { Upload(it.track.url.substringAfter("v=").substringBefore("&"), it.track.title, it.track.artist, it.track.seconds) }
        }.onFailure { android.util.Log.w("Glasswave", "18+ song: music video search failed ($it)") }.getOrNull().orEmpty()
        fun youtube(q: String) = runCatching {
            val yt = ServiceList.YouTube
            val handler = yt.searchQHFactory.fromQuery(q.trim(), listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
            SearchInfo.getInfo(yt, handler).relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { c ->
                android.net.Uri.parse(c.url).getQueryParameter("v")?.let { Upload(it, c.name, c.uploaderName.orEmpty(), c.duration) }
            }
        }.onFailure { android.util.Log.w("Glasswave", "18+ song: search for another upload failed ($it)") }.getOrNull().orEmpty()
        var candidates = emptyList<Upload>()
        // The full name, then just its first word (finds uploads that blank out the rest).
        val first = info.title.trim().substringBefore(' ')
        val queries = listOf("${info.title} $lead".trim(), "$first $lead".trim()).distinct()
        search@ for (q in queries) {
            for ((where, search) in listOf<Pair<String, (String) -> List<Upload>>>("YouTube Music" to ::musicVideos, "YouTube" to ::youtube)) {
                val found = search(q)
                candidates = pick(found)
                android.util.Log.i("Glasswave", "18+ song \"${info.title}\" on $where ($q): ${found.size} results, ${candidates.size} look like the same song")
                if (candidates.isNotEmpty()) break@search
            }
        }
        for (c in candidates.take(6)) {
            val streams = runCatching { extractor(ytWatchUrl(c.id)).audioStreams }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            android.util.Log.i("Glasswave", "18+ song: playing ${c.name} (${c.by}) instead")
            ageAlt?.edit { putString(id, c.id) }
            return streams
        }
        return null
    }

    /** Clean song -> its explicit version ("" when it has none). Saved so each song is checked once. */
    private var explicit: SharedPreferences? = null
    private val explicitNow = ConcurrentHashMap<String, String>()

    /**
     * No censored songs: a clean version plays as its explicit (original) version when YouTube
     * Music has one. Blocking; asks the internet once per song, then remembers.
     */
    fun uncensored(videoUrl: String): String {
        val id = android.net.Uri.parse(videoUrl).getQueryParameter("v") ?: return videoUrl
        val known = explicitNow[id] ?: explicit?.getString(id, null)
        val swap = known ?: (runCatching { YtMusicApi.explicitFor(id) ?: "" }.getOrNull() ?: return videoUrl).also {
            explicitNow[id] = it
            explicit?.edit { putString(id, it) }
        }
        return if (swap.isEmpty()) videoUrl else ytWatchUrl(swap)
    }

    private fun searchBlocking(query: String): List<Track> {
        init()
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            "",
        )
        val info = SearchInfo.getInfo(yt, handler)
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                Track(
                    url = it.url,
                    title = it.name,
                    artist = it.uploaderName ?: "",
                    seconds = it.duration,
                    thumb = it.thumbnails.lastOrNull()?.url,
                )
            }
            .distinctBy { it.url }
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) { searchBlocking(query) }

    /**
     * Finds the YouTube Music song for a Spotify song. Of the top results, picks the one whose
     * length is closest to the Spotify length. Blocking.
     */
    @Throws(IOException::class)
    fun match(query: String, seconds: Long): String {
        matches?.getString(query, null)?.let { return it }
        val found = try {
            searchBlocking(query)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("YouTube search failed", e)
        }
        // Never pick a clean / censored upload when the original is among the results.
        val censored = Regex("clean|edited|censored|radio edit", RegexOption.IGNORE_CASE)
        val wantsClean = censored.containsMatchIn(query)
        val top = found.take(6).filter { wantsClean || !censored.containsMatchIn(it.title) }.ifEmpty { found.take(5) }
        val best = (if (seconds > 0) top.minByOrNull { abs(it.seconds - seconds) } else top.firstOrNull())
            ?: throw IOException("No match on YouTube Music for \"$query\"")
        matches?.edit { putString(query, best.url) }
        return best.url
    }

    /** Remembers the right YouTube song for a Spotify song (after a wrong match was caught). */
    fun setMatch(query: String, url: String) { matches?.edit { putString(query, url) } }

    /**
     * One key per song, the same for every upload of it: "SLOW DANCING IN THE DARK",
     * "Slow Dancing in the Dark (Official Video)" and "Joji - SLOW DANCING IN THE DARK" all match.
     * Used to keep the same song from being queued twice.
     */
    fun songKey(title: String, artist: String): String {
        var t = title.lowercase()
        // "Artist - Title" (how music videos are often named): keep the title part.
        val dash = t.indexOf(" - ")
        if (dash > 0) {
            val before = t.substring(0, dash)
            val a = artist.lowercase()
            if (a.isNotEmpty() && (a.contains(before.trim()) || before.contains(a.split(",", "&").first().trim()))) t = t.substring(dash + 3)
        }
        val key = t
            .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("\\s(feat\\.?|ft\\.?|featuring|with)\\s.*$"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")
        return key.ifEmpty { title.lowercase() }
    }

    /** Two titles name the same song (ignoring case, brackets, "feat." and punctuation). */
    fun sameSong(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase()
            .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("\\s(feat\\.?|ft\\.?|featuring|with)\\s.*$"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return true // can't tell (e.g. only symbols): don't second-guess
        return x == y || (minOf(x.length, y.length) >= 3 && (x.contains(y) || y.contains(x)))
    }

    /**
     * Finds the YouTube Music song called [title] by [artist]: same title, then the right artist,
     * then the closest length. Null if nothing really matches. Blocking.
     */
    fun findSong(title: String, artist: String, seconds: Long): Track? {
        val page = runCatching {
            kotlinx.coroutines.runBlocking { YtMusicApi.search("$title $artist".trim(), YtMusicApi.Filter.SONGS) }
        }.getOrNull() ?: return null
        val songs = page.sections.flatMap { it.items }.filterIsInstance<SongItem>().take(10)
        val lead = artist.substringBefore(",").substringBefore(" & ").trim().lowercase()
        return songs.filter { sameSong(title, it.track.title) }
            .sortedWith(
                compareBy<SongItem>(
                    { if (lead.isNotEmpty() && it.track.artist.lowercase().contains(lead)) 0 else 1 },
                    { if (it.explicit) 0 else 1 },
                    { if (seconds > 0 && it.track.seconds > 0) abs(it.track.seconds - seconds) else 0 },
                ),
            )
            .firstOrNull()?.track
    }

    fun forgetStreamUrls() = urlCache.clear()

    /** Forgets which YouTube song each Spotify song was matched to. */
    fun clearMatches() { matches?.edit { clear() } }

    /** One lock per song, so the player and the prefetcher never look the same song up twice at once. */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Reads just the stream list for a video. Faster than building the full StreamInfo,
     * which also parses related videos, comments links, subtitles and more.
     */
    private fun extractor(url: String): org.schabi.newpipe.extractor.stream.StreamExtractor = try {
        ServiceList.YouTube.getStreamExtractor(url).also { it.fetchPage() }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("YouTube lookup failed", e)
    }

    /** Blocking. Called by the player on its loading thread, never on the main thread. */
    @Throws(IOException::class)
    fun streamUrl(videoUrl: String, quality: Quality = Settings.quality): String {
        init()
        val key = "$videoUrl|${quality.name}"
        urlCache[key]?.let { (u, at) -> if (System.currentTimeMillis() - at < URL_LIFETIME_MS) return u }
        synchronized(locks.getOrPut(key) { Any() }) {
            // Someone else may have just finished looking it up.
            urlCache[key]?.let { (u, at) -> if (System.currentTimeMillis() - at < URL_LIFETIME_MS) return u }
            return lookUpAudio(uncensored(videoUrl), quality, key)
        }
    }

    private fun lookUpAudio(videoUrl: String, quality: Quality, key: String): String {
        val streams = try {
            extractor(videoUrl).audioStreams
        } catch (e: Exception) {
            val err = e as? IOException ?: IOException("YouTube lookup failed", e)
            // An 18+ song: play another upload of it instead.
            if (!ageRestricted(e)) throw err
            otherUpload(videoUrl) ?: throw err
        }
        val now = System.currentTimeMillis()
        val playable = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        // Pick by format number first: YouTube doesn't always say the bitrate, which used to
        // make "best" land on a low-quality stream.
        val byRank = quality.itags.firstNotNullOfOrNull { tag -> playable.firstOrNull { it.itag == tag } }
        val kbps: (org.schabi.newpipe.extractor.stream.AudioStream) -> Int = { maxOf(it.averageBitrate, it.bitrate / 1000) }
        val best = byRank
            ?: (if (quality == Quality.LOW) playable.minByOrNull(kbps) else playable.maxByOrNull(kbps))
            ?: throw IOException("No playable audio found")
        val u = best.content
        urlCache[key] = u to now
        return u
    }

    private val videoCache = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * A silent video stream for a YouTube video, at most [maxHeight] pixels tall. Prefers MP4,
     * which every phone can decode in hardware. Blocking.
     */
    @Throws(IOException::class)
    fun videoUrl(videoId: String, maxHeight: Int): String? {
        init()
        val key = "$videoId|$maxHeight"
        val now = System.currentTimeMillis()
        videoCache[key]?.let { (u, at) -> if (now - at < URL_LIFETIME_MS) return u }
        val videos = try {
            extractor("https://www.youtube.com/watch?v=$videoId").videoOnlyStreams
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("YouTube lookup failed", e)
        }
        val ok = videos.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.height in 1..maxHeight
        }
        val best = ok.filter { it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 }.maxByOrNull { it.height }
            ?: ok.maxByOrNull { it.height }
            ?: return null
        videoCache[key] = best.content to now
        return best.content
    }

    /** The YouTube video id behind a queue item: read from the address, or matched for Spotify songs. Blocking. */
    fun videoIdFor(trackUrl: String): String? {
        val uri = android.net.Uri.parse(trackUrl)
        val page = if (uri.scheme == "gwmatch") {
            runCatching { match(uri.getQueryParameter("q") ?: return null, uri.getQueryParameter("d")?.toLongOrNull() ?: 0) }.getOrNull() ?: return null
        } else trackUrl
        return android.net.Uri.parse(page).getQueryParameter("v")
    }

    /** Looks up a song's audio address ahead of time so the next song starts without a wait. */
    fun prefetch(uri: android.net.Uri) {
        try {
            val page = when {
                uri.scheme == "gwmatch" -> match(uri.getQueryParameter("q") ?: return, uri.getQueryParameter("d")?.toLongOrNull() ?: 0)
                uri.host?.endsWith("youtube.com") == true -> uri.toString()
                else -> return
            }
            streamUrl(page)
        } catch (e: Exception) {
            android.util.Log.w("Glasswave", "Prefetch failed for $uri", e)
        }
    }
}
