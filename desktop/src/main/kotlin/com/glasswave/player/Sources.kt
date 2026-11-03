// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Where else a song can come from when its YouTube upload won't play (18+, taken down, private,
 * not in your country). [YtRepo] first tries another upload of the same song on YouTube; after
 * that, the song is looked for on SoundCloud, which has most music too. Everything here is
 * blocking: call it off the main thread.
 */
object Sources {
    /** A song as the queue knows it: its real name, artist and length. */
    class Hint(val title: String, val artist: String, val seconds: Long)

    private val hints = Collections.synchronizedMap(object : LinkedHashMap<String, Hint>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hint>?) = size > 500
    })

    private fun keyOf(url: String): String = android.net.Uri.parse(url).getQueryParameter("v") ?: url

    /**
     * Remembers what the queue calls the song at [url], so if that upload won't play, the song can
     * be found again by its real name (YouTube's own name for an upload can be anything).
     */
    fun hint(url: String, title: String, artist: String, seconds: Long) {
        if (title.isBlank()) return
        hints[keyOf(url)] = Hint(title, artist, seconds)
    }

    fun hintFor(url: String): Hint? = hints[keyOf(url)]

    /** The upload itself can't be played (18+, removed, private, blocked here), as opposed to a connection hiccup. */
    fun unplayable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is ContentNotAvailableException || (it.message?.let { m -> "UNPLAYABLE" in m || "age-restricted" in m } == true)
    }

    /** A playable copy of a song on another service. [hls]: the address is an HLS playlist, not a plain file. */
    class Found(val url: String, val hls: Boolean, val page: String, val name: String, val by: String)

    private val notTheSame = Regex("""\b(slowed|sped|speed|nightcore|reverb|remix|cover|instrumental|karaoke|8d|bass ?boosted|1 ?hour|loop|mashup|edit|live|acoustic)\b""", RegexOption.IGNORE_CASE)

    /** The SoundCloud page found for each song, so it's searched once per run ("" = nothing matched). */
    private val scPages = ConcurrentHashMap<String, String>()

    /** A copy found lately (its address is good for a while), so a switch to it is instant. */
    private val scFound = ConcurrentHashMap<String, Pair<Found, Long>>()
    private const val FOUND_LIFETIME_MS = 30L * 60 * 1000

    /** Background threads for looking a song up on several services at once. */
    val pool: java.util.concurrent.ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "sources").apply { isDaemon = true } }

    /** One song being looked up on SoundCloud right now, so two askers share the one search. */
    private val scRunning = ConcurrentHashMap<String, java.util.concurrent.Future<Found?>>()

    private fun scKey(title: String, artist: String) = YtRepo.songKey(title, artist) + "|" + artist.lowercase()

    /** Starts looking the song up on SoundCloud in the background, so a switch to it later is quick. */
    fun prewarmSoundCloud(title: String, artist: String, seconds: Long) {
        if (title.isBlank()) return
        val key = scKey(title, artist)
        if (scPages[key] == "" || scFound[key]?.let { System.currentTimeMillis() - it.second < FOUND_LIFETIME_MS } == true) return
        scRunning.computeIfAbsent(key) { pool.submit<Found?> { try { lookUpSoundCloud(title, artist, seconds) } finally { scRunning.remove(key) } } }
    }

    /**
     * The same song on SoundCloud: same name, about the same length, and not a slowed, sped up or
     * remixed version, preferring uploads from (or naming) the artist. Null if nothing matches.
     */
    fun soundCloud(title: String, artist: String, seconds: Long): Found? {
        if (title.isBlank()) return null
        val key = scKey(title, artist)
        scFound[key]?.let { (f, at) -> if (System.currentTimeMillis() - at < FOUND_LIFETIME_MS) return f }
        // Already being looked up (started early by YtRepo): wait for that answer instead of asking twice.
        scRunning[key]?.let { return runCatching { it.get() }.getOrNull() }
        return lookUpSoundCloud(title, artist, seconds)
    }

    private fun lookUpSoundCloud(title: String, artist: String, seconds: Long): Found? {
        YtRepo.init()
        val sc = ServiceList.SoundCloud
        val key = scKey(title, artist)
        val known = scPages[key]
        if (known == "") return null
        val lead = artist.substringBefore(",").substringBefore(" & ").trim()
        val leadKey = lead.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        fun mentions(c: StreamInfoItem) = leadKey.isNotEmpty() &&
            "${c.name} ${c.uploaderName}".lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "").contains(leadKey)
        val pages = if (known != null) listOf(known) else runCatching {
            val q = "$title $lead".trim()
            val handler = sc.searchQHFactory.fromQuery(q, listOf(SoundcloudSearchQueryHandlerFactory.TRACKS), "")
            val hits = SearchInfo.getInfo(sc, handler).relatedItems.filterIsInstance<StreamInfoItem>()
            val good = hits.filter { c ->
                YtRepo.sameSong(title, c.name) &&
                    (notTheSame.containsMatchIn(title) || !notTheSame.containsMatchIn(c.name)) &&
                    // The length must be close; a 30 s result is only a preview.
                    (if (seconds > 0) abs(c.duration - seconds) <= 10 else c.duration > 45)
            }.sortedWith(compareBy({ if (mentions(it)) 0 else 1 }, { if (seconds > 0) abs(it.duration - seconds) else 0 }))
            android.util.Log.i("Glasswave", "SoundCloud \"$title\" ($q): ${hits.size} results, ${good.size} look like the same song")
            good.take(4).map { it.url }
        }.onFailure { android.util.Log.w("Glasswave", "SoundCloud search failed", it) }.getOrNull() ?: return null
        for (page in pages) {
            val ex = runCatching { sc.getStreamExtractor(page).also { it.fetchPage() } }.getOrNull() ?: continue
            val streams = runCatching { ex.audioStreams }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val best = pickStream(streams) ?: continue
            scPages[key] = page
            val by = runCatching { ex.uploaderName }.getOrDefault("")
            android.util.Log.i("Glasswave", "SoundCloud: \"${ex.name}\" ($by) for \"$title\"")
            val found = Found(best.content, best.deliveryMethod == DeliveryMethod.HLS, page, ex.name, by)
            scFound[key] = found to System.currentTimeMillis()
            return found
        }
        if (known == null) scPages[key] = ""
        return null
    }

    /** A plain file first (any player reads it), then an HLS stream: AAC first, then MP3, then Opus. */
    private fun pickStream(streams: List<AudioStream>): AudioStream? =
        streams.firstOrNull { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            ?: streams.filter { it.deliveryMethod == DeliveryMethod.HLS && it.isUrl }.maxByOrNull { s ->
                (when (s.format) { MediaFormat.M4A -> 3; MediaFormat.MP3 -> 2; else -> 1 }) * 1000 + maxOf(s.averageBitrate, 0)
            }
}

/**
 * HLS streams cut into MP4 pieces (how SoundCloud streams): an "init" piece and then one piece
 * every few seconds. Joined back to back they make one ordinary MP4 file, which is how the PC
 * plays them and how both apps download them.
 */
object Hls {
    fun isPlaylist(url: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /** The pieces in order: the init piece (when there is one), then every segment, as full addresses. */
    @Throws(IOException::class)
    fun parts(playlistUrl: String): List<String> {
        val req = Request.Builder().url(playlistUrl).header("User-Agent", YtDownloader.USER_AGENT).build()
        val text = YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Stream playlist failed (${r.code})")
            r.body!!.string()
        }
        val base = java.net.URI(playlistUrl)
        fun full(u: String) = runCatching { base.resolve(u.trim()).toString() }.getOrDefault(u.trim())
        val out = ArrayList<String>()
        for (line in text.lines()) {
            val l = line.trim()
            when {
                l.startsWith("#EXT-X-MAP:") -> Regex("""URI="([^"]+)"""").find(l)?.groupValues?.get(1)?.let { out += full(it) }
                l.isNotEmpty() && !l.startsWith("#") -> out += full(l)
            }
        }
        if (out.isEmpty()) throw IOException("Empty stream playlist")
        return out
    }

    /** Each piece's size in bytes, asked for a few at a time. */
    @Throws(IOException::class)
    fun sizes(parts: List<String>): LongArray {
        val out = LongArray(parts.size)
        val pool = Executors.newFixedThreadPool(6)
        try {
            parts.mapIndexed { i, u -> pool.submit { out[i] = sizeOf(u) } }.forEach {
                try { it.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? IOException) ?: IOException(e.cause) }
            }
        } finally {
            pool.shutdown()
        }
        return out
    }

    private fun sizeOf(url: String): Long {
        val req = Request.Builder().url(url).header("User-Agent", YtDownloader.USER_AGENT).header("Range", "bytes=0-0").build()
        return YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Stream piece failed (${r.code})")
            r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 1 }
                ?: throw IOException("Stream piece has no size")
        }
    }

    /** Downloads the whole stream into [file] as one MP4, reporting progress from 0 to 1. */
    @Throws(IOException::class)
    fun writeTo(playlistUrl: String, file: File, progress: (Float) -> Unit = {}) {
        val parts = parts(playlistUrl)
        file.outputStream().buffered().use { out ->
            parts.forEachIndexed { i, u ->
                val req = Request.Builder().url(u).header("User-Agent", YtDownloader.USER_AGENT).build()
                YtRepo.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Stream piece failed (${r.code})")
                    r.body!!.byteStream().copyTo(out)
                }
                progress((i + 1).toFloat() / parts.size)
            }
        }
    }
}
