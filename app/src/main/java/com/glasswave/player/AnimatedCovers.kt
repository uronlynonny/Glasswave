// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Apple Music's animated covers ("motion artwork"): a short silent clip some albums show in place
 * of the still picture. Found through Apple's public search and album pages (no account needed).
 * Each song and each album is looked up once and remembered, "has none" included. The clip itself
 * is downloaded once into the cache, then plays round and round, even offline.
 */
object AnimatedCovers {
    private var lookups: SharedPreferences? = null
    private var settings: SharedPreferences? = null
    private var cacheDir: File? = null

    fun init(context: Context) {
        lookups = context.getSharedPreferences("animated_covers", Context.MODE_PRIVATE)
        settings = context.getSharedPreferences("animated_cover_settings", Context.MODE_PRIVATE)
        cacheDir = File(context.cacheDir, "animated")
        on = settings?.getBoolean("enabled", true) ?: true
        mobile = settings?.getBoolean("mobile_data", false) ?: false
    }

    private var on by mutableStateOf(true)
    private var mobile by mutableStateOf(false)

    /** Show animated covers on the full player. */
    var enabled: Boolean
        get() = on
        set(v) { on = v; settings?.edit()?.putBoolean("enabled", v)?.apply() }

    /** Download new ones on mobile data too (phone only; ones already downloaded always play). */
    var onMobileData: Boolean
        get() = mobile
        set(v) { mobile = v; settings?.edit()?.putBoolean("mobile_data", v)?.apply() }

    private const val SONG = "s:"
    private const val ALBUM = "a:"
    /** "Has none" is asked again after this many days (Apple adds new ones now and then). */
    private const val NONE_DAYS = 30
    /** The downloaded clips are kept under this size; the ones played longest ago go first. */
    private const val CACHE_LIMIT = 200L shl 20
    /** A clip bigger than this is something else: skip it. */
    private const val MAX_CLIP = 40L shl 20

    /** Song url or album id -> clip address, or "" for none. */
    private val known = ConcurrentHashMap<String, String>()

    /**
     * The address of the animated cover (an HLS playlist) for [track]'s album, or null if it has
     * none or it can't be found right now. Looks on the internet only the first time.
     */
    suspend fun videoFor(track: Track): String? {
        if (track.title.isBlank()) return null
        recall(SONG + track.url)?.let { return it.ifEmpty { null } }
        return withContext(Dispatchers.IO) {
            try {
                val found = search(track)
                keep(SONG + track.url, found)
                found
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Animated cover lookup failed", e)
                null // network trouble: try again next time
            }
        }
    }

    /** What was remembered for [track], without going online ("" = has none, null = not looked up). */
    fun rememberedFor(track: Track): String? = recall(SONG + track.url)

    /**
     * The animated cover for [track] as a file ready to play, about [side] pixels across, or null.
     * When [online] is false only what's already looked up and downloaded is used.
     */
    suspend fun clipFor(track: Track, side: Int, online: Boolean): File? {
        val master = (if (online) videoFor(track) else rememberedFor(track)) ?: return null
        if (master.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            saved(master)?.let { return@withContext it }
            if (!online) return@withContext null
            // One download per clip: a second ask waits for the first, then uses its file.
            synchronized(busy.getOrPut(master) { Any() }) {
                saved(master) ?: try {
                    download(master, side)
                } catch (e: Exception) {
                    android.util.Log.w("Glasswave", "Animated cover download failed", e)
                    null
                }
            }
        }
    }

    private val busy = ConcurrentHashMap<String, Any>()

    // ---- Finding it ----

    private fun search(track: Track): String? {
        val title = track.title.replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("\\s(feat\\.?|ft\\.?|featuring|with)\\s.*$", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        val term = "$title ${mainArtist(track.artist)}".trim()
        val json = JSONObject(fetch("https://itunes.apple.com/search?term=" + URLEncoder.encode(term, "UTF-8") + "&entity=song&limit=15"))
        val results = json.optJSONArray("results") ?: return null
        // The albums with this song by this artist, best match first (Apple lists the original album first).
        val albums = LinkedHashMap<String, String>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            if (!YtRepo.sameSong(track.title, r.optString("trackName"))) continue
            if (!sameArtist(track.artist, r.optString("artistName"))) continue
            val id = r.optLong("collectionId").takeIf { it > 0 }?.toString() ?: continue
            val page = r.optString("collectionViewUrl").substringBefore('?').ifEmpty { null } ?: continue
            albums.putIfAbsent(id, page)
        }
        // Look at the first few (the deluxe edition often has no clip while the original does).
        for ((id, page) in albums.entries.take(3)) {
            val clip = recall(ALBUM + id) ?: fromAlbumPage(page).also { keep(ALBUM + id, it) } ?: ""
            if (clip.isNotEmpty()) return clip
        }
        return null
    }

    /**
     * Reads an album page for its clips. There are usually two, square and tall (3:4), each named
     * just before it ("motionDetailSquare", "motionDetailTall"). The square one is wanted; the
     * tall one will do (it's cropped when drawn).
     */
    private fun fromAlbumPage(page: String): String? {
        val html = fetch(page).replace("\\/", "/")
        val found = Regex("""https://mvod\.itunes\.apple\.com/[^"\\\s<>]+?\.m3u8""").findAll(html).toList()
        if (found.isEmpty()) return null
        fun rank(at: Int): Int {
            val before = html.substring(maxOf(0, at - 1500), at)
            val square = before.lastIndexOf("Square")
            val tall = before.lastIndexOf("Tall")
            return when {
                square > tall -> 0
                tall > square -> 2
                else -> 1
            }
        }
        val clips = found.sortedBy { rank(it.range.first) }.map { it.value }.distinct()
        // Make sure by the picture size it lists, in case the names change.
        for (c in clips.take(3)) {
            val v = runCatching { variants(c, fetch(c)) }.getOrNull() ?: continue
            if (v.any { it.w == it.h }) return c
        }
        return clips.first()
    }

    private fun mainArtist(a: String) = a.replace(Regex("\\s-\\sTopic$"), "")
        .split(",", " & ", " x ", " X ", " feat", " ft.", " with ").first().trim()

    private fun sameArtist(ours: String, theirs: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        val a = norm(mainArtist(ours))
        val b = norm(mainArtist(theirs))
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || norm(theirs).contains(a) || norm(ours).contains(b)
    }

    // ---- Remembering ----

    private fun today() = System.currentTimeMillis() / 86_400_000L

    private fun recall(key: String): String? {
        known[key]?.let { return it }
        val v = lookups?.getString(key, null) ?: return null
        // "-1234": none, as of that day.
        if (v.startsWith("-")) {
            val day = v.drop(1).toLongOrNull() ?: 0
            if (today() - day > NONE_DAYS) return null
            known[key] = ""
            return ""
        }
        known[key] = v
        return v
    }

    private fun keep(key: String, clip: String?) {
        known[key] = clip ?: ""
        lookups?.edit()?.putString(key, clip ?: "-${today()}")?.apply()
    }

    // ---- Downloading ----

    private class Variant(val url: String, val w: Int, val h: Int, val bandwidth: Long, val avc: Boolean)

    /** The video versions an HLS master playlist offers. */
    private fun variants(master: String, text: String): List<Variant> {
        val out = mutableListOf<Variant>()
        val lines = text.lines()
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#EXT-X-STREAM-INF:")) continue
            val url = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
            val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)
            val bw = Regex("[,:]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val codecs = Regex("CODECS=\"([^\"]*)\"").find(l)?.groupValues?.get(1).orEmpty()
            out += Variant(
                URI(master).resolve(url).toString(),
                res?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                res?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                bw,
                codecs.startsWith("avc1") || codecs.isEmpty(),
            )
        }
        return out
    }

    private fun nameFor(master: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(master.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)

    /** The clip downloaded earlier for this address, if any. */
    private fun saved(master: String): File? {
        val f = File(cacheDir ?: return null, nameFor(master) + ".mp4")
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /**
     * Downloads the H.264 version closest to [side] pixels across (H.265 isn't safe everywhere)
     * as one file. Apple's playlists are pieces of one MP4 file, so the pieces are fetched in
     * order and joined back together.
     */
    private fun download(master: String, side: Int): File? {
        val dir = cacheDir ?: return null
        dir.mkdirs()
        val all = variants(master, fetch(master)).filter { it.avc && it.w > 0 }
        if (all.isEmpty()) return null
        val big = all.filter { minOf(it.w, it.h) >= side }
        val pick = (big.minWithOrNull(compareBy<Variant> { minOf(it.w, it.h) }.thenByDescending { it.bandwidth })
            ?: all.maxWithOrNull(compareBy<Variant> { minOf(it.w, it.h) }.thenByDescending { it.bandwidth }))!!

        // The pieces, in order: (address, start byte, length or -1 for "to the end").
        val pieces = mutableListOf<Triple<String, Long, Long>>()
        var lastUrl = ""
        var lastEnd = 0L
        var range: Pair<Long, Long?>? = null
        fun parseRange(s: String): Pair<Long, Long?> {
            val len = s.substringBefore('@').trim().toLong()
            val at = s.substringAfter('@', "").trim().toLongOrNull()
            return len to at
        }
        fun add(url: String, r: Pair<Long, Long?>?) {
            val abs = URI(pick.url).resolve(url).toString()
            if (r == null) {
                pieces += Triple(abs, 0L, -1L)
            } else {
                val start = r.second ?: if (abs == lastUrl) lastEnd else 0L
                pieces += Triple(abs, start, r.first)
                lastEnd = start + r.first
            }
            lastUrl = abs
        }
        for (l in fetch(pick.url).lines().map { it.trim() }) {
            when {
                l.startsWith("#EXT-X-MAP:") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1) ?: continue
                    val br = Regex("BYTERANGE=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                    add(uri, br?.let { parseRange(it) })
                }
                l.startsWith("#EXT-X-BYTERANGE:") -> range = parseRange(l.substringAfter(':'))
                l.startsWith("#EXT-X-KEY:") && !l.contains("METHOD=NONE") -> return null // locked: can't use it
                l.isEmpty() || l.startsWith("#") -> {}
                else -> { add(l, range); range = null }
            }
        }
        if (pieces.isEmpty()) return null
        // Join pieces that follow on from each other in the same file into one request.
        val joined = mutableListOf<Triple<String, Long, Long>>()
        for (p in pieces) {
            val last = joined.lastOrNull()
            if (last != null && last.first == p.first && last.third >= 0 && p.third >= 0 && last.second + last.third == p.second) {
                joined[joined.size - 1] = Triple(last.first, last.second, last.third + p.third)
            } else {
                joined += p
            }
        }

        val out = File(dir, nameFor(master) + ".mp4")
        val part = File(dir, nameFor(master) + "." + System.nanoTime() + ".part")
        try {
            part.outputStream().buffered().use { o ->
                var total = 0L
                for ((url, start, len) in joined) {
                    val req = Request.Builder().url(url).header("User-Agent", YtDownloader.USER_AGENT)
                    if (len >= 0) req.header("Range", "bytes=$start-${start + len - 1}")
                    YtRepo.http.newCall(req.build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                        val body = r.body!!.byteStream()
                        // The server ignored the range: skip to the piece by hand.
                        if (r.code == 200 && len >= 0) {
                            var skip = start
                            while (skip > 0) { val n = body.skip(skip); if (n <= 0) throw java.io.IOException("short"); skip -= n }
                        }
                        val buf = ByteArray(64 * 1024)
                        var left = if (len >= 0) len else Long.MAX_VALUE
                        while (left > 0) {
                            val n = body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            o.write(buf, 0, n)
                            left -= n
                            total += n
                            if (total > MAX_CLIP) throw java.io.IOException("too big")
                        }
                    }
                }
            }
            if (!part.renameTo(out)) {
                // Fetched twice at once: the other copy is already in place (and may be playing).
                if (out.isFile && out.length() > 0) return out
                if (!part.renameTo(out)) return null
            }
        } finally {
            part.delete()
        }
        trim(dir)
        return out
    }

    /** Keeps the downloaded clips under [CACHE_LIMIT], forgetting the ones played longest ago. */
    private fun trim(dir: File) {
        val all = dir.listFiles()?.filter { it.isFile } ?: return
        // Pieces left by a download cut short (the app closed partway).
        all.filter { it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > 3_600_000L }.forEach { it.delete() }
        val files = all.filter { it.exists() }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (f in files) {
            if (size <= CACHE_LIMIT) break
            size -= f.length()
            f.delete()
        }
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", YtDownloader.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            return r.body!!.string()
        }
    }
}
