// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * One sung word (or part of a word): when it starts and ends, and which letters of the line it is
 * ([from] up to, not including, [to]).
 */
data class LyricWord(val ms: Long, val endMs: Long, val from: Int, val to: Int)

/**
 * One lyric line and when it starts. [ms] is -1 for lyrics without timing.
 * [words] has the timing of each word when the source has it (empty otherwise).
 */
data class LyricLine(val ms: Long, val text: String, val words: List<LyricWord> = emptyList())

/** [durationSec] is the length of the recording these lyrics were timed to (0 if unknown). */
/** [ref] says exactly which lyrics these are ("LRCLIB:123", "KuGou:456"…), so a report can rule them out. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String, val durationSec: Int = 0, val ref: String = "") {
    /** True when the words themselves are timed, not just the lines. */
    val wordTimed: Boolean get() = synced && lines.any { it.words.isNotEmpty() }
}

/**
 * Finds lyrics for a song, trying in order:
 * 1. LRCLIB, a free open lyrics library with time-synced lyrics,
 * 2. KuGou, a big lyrics catalogue (synced) that many open-source players use as a backup,
 * 3. YouTube Music's own lyrics, timed (for the exact song playing, so never a wrong match),
 * 4. NetEase Cloud Music, another big catalogue (synced).
 * Lyrics without timing are never used: they'd sit still instead of scrolling with the song.
 * KuGou often times every word, not just every line. When LRCLIB only times lines, KuGou's
 * word-by-word version is used instead if it's clearly the same song with the same timing.
 * Results are remembered for the session, and kept on the phone for downloaded songs so their
 * lyrics show offline too.
 */
object LyricsRepo {
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private val none = ConcurrentHashMap.newKeySet<String>()
    // Lookups that failed because a site was busy or down; those songs get asked about again later.
    private val failedCalls = java.util.concurrent.atomic.AtomicInteger()
    private const val UA = "Glasswave/0.9 (Android music player)"

    private val junk = Regex(
        "\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|remaster|explicit|clean|hd|4k|mv|live at)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val feat = Regex("\\s*[(\\[]?\\s*(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)

    private fun cleanTitle(t: String) = t.replace(junk, "").replace(feat, "").trim()
    private fun mainArtist(a: String) = a.replace(" - Topic", "").split(",", "&", " x ", " X ", " and ").first().trim()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Lookups running right now, so opening the lyrics joins one already under way. */
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Lyrics?>>()
    private val ahead = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** True when [url]'s lyrics are already here (no wait to show them). */
    fun ready(url: String) = cache.containsKey(url)

    /**
     * Looks a song's lyrics up in the background (the song that just started, and the next one),
     * so they're usually ready by the time the lyrics are opened, like Spotify's.
     */
    fun prefetch(t: Track) {
        if (t.title.isBlank() || cache.containsKey(t.url) || t.url in none || pending.containsKey(t.url)) return
        ahead.launch { runCatching { get(t.url, t.title, t.artist, t.seconds) } }
    }

    suspend fun get(url: String, title: String, artist: String, seconds: Long): Lyrics? {
        cache[url]?.let { return it }
        val job = pending.getOrPut(url) { ahead.async { try { load(url, title, artist, seconds) } finally { pending.remove(url) } } }
        return job.await()
    }

    private suspend fun load(url: String, title: String, artist: String, seconds: Long): Lyrics? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }
        val old = readSaved(url)
        if (old != null && savedVersion(url) >= SAVE_VERSION) { cache[url] = old; return@withContext old }
        if (url in none) return@withContext null
        val t = cleanTitle(title)
        val a = mainArtist(artist)
        // Songs added from YouTube Music playlists often come without a length. Without one, every
        // version of a song looks the same (album, live, sped up, other cuts), so look it up first.
        @Suppress("NAME_SHADOWING")
        val seconds = if (seconds > 0) seconds else runCatching { YtRepo.lengthOf(url) }.getOrDefault(0L)
        // Only lyrics that scroll with the song: LRCLIB, KuGou and NetEase all time their lines, and
        // are asked at the same time, not one after the other.
        val failedBefore = failedCalls.get()
        // Lyrics someone reported as wrong or out of sync for this song are never picked again.
        refreshShared()
        val skip = blocked(url)
        fun ok(l: Lyrics?) = l?.takeIf { it.ref.isEmpty() || it.ref !in skip }
        val fromKugou = async { runCatching { ok(kugou(t, a, seconds)) }.getOrNull() }
        val fromNetease = async { runCatching { ok(netease(t, a, seconds)) }.getOrNull() }
        val fromYoutube = async { runCatching { ok(youtubeTimed(url)) }.getOrNull() }
        val fromLrclib = runCatching { lrclib(t, a, seconds, skip) }.getOrNull()
        fun fits(k: Lyrics) = seconds <= 0 || (k.lines.lastOrNull()?.ms ?: 0) / 1000 <= seconds + 3
        val lrclibSynced = fromLrclib?.takeIf { it.synced }
        val found = if (lrclibSynced != null) {
            // LRCLIB usually times whole lines; KuGou often times every word. Use KuGou's only when
            // it's clearly the same song with the same timing, otherwise keep LRCLIB's.
            fromNetease.cancel()
            fromYoutube.cancel()
            if (lrclibSynced.wordTimed) lrclibSynced.also { fromKugou.cancel() }
            else fromKugou.await()
                ?.takeIf { k -> k.wordTimed && fits(k) && sameSong(lrclibSynced, k) && sameWords(lrclibSynced, k) >= 0.9 && sameLines(lrclibSynced, k) >= 0.9 }
                ?: lrclibSynced
        } else {
            // KuGou when it times every word; otherwise YouTube Music's (made for this exact song),
            // then KuGou's line timing, then NetEase.
            val k = fromKugou.await()?.takeIf { fits(it) && it.synced }
            k?.takeIf { it.wordTimed }
                ?: fromYoutube.await()?.takeIf { fits(it) }
                ?: k
                ?: fromNetease.await()?.takeIf { fits(it) }
        }
        // Filling in starred words keeps the same number of letters, so word timings still line up.
        val clean = found?.let { ly ->
            ly.copy(lines = ly.lines.map { l ->
                val t2 = uncensor(l.text)
                l.copy(text = t2, words = if (t2.length == l.text.length) l.words else emptyList())
            })
        }
        if (clean != null && clean.lines.isNotEmpty()) {
            cache[url] = clean
            if (Downloads.isDownloaded(url)) writeSaved(url, clean)
        } else if (old != null) {
            // Couldn't look them up again (offline?): the saved ones still beat none.
            cache[url] = old
            return@withContext old
        } else {
            if (failedCalls.get() == failedBefore) none += url
        }
        clean
    }

    /* ---------------- Kept with downloads ---------------- */

    private var savedDir: java.io.File? = null

    private fun savedFile(url: String): java.io.File? = savedDir?.let { d ->
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        java.io.File(d, "$name.json")
    }

    private fun readSaved(url: String): Lyrics? = runCatching {
        val f = savedFile(url)?.takeIf { it.exists() } ?: return null
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("l")
        val lines = (0 until arr.length()).map { i ->
            arr.getJSONArray(i).let { l ->
                // Each line is [ms, text], plus [[start, end, from, to], ...] when the words are timed
                // (older saves don't have that part).
                val text = l.getString(1)
                val w = l.optJSONArray(2)
                val words = if (w == null) emptyList() else (0 until w.length()).mapNotNull { j ->
                    w.optJSONArray(j)?.let { LyricWord(it.getLong(0), it.getLong(1), it.getInt(2), it.getInt(3)) }
                }.filter { it.from >= 0 && it.to <= text.length && it.from < it.to }
                LyricLine(l.getLong(0), text, words)
            }
        }
        Lyrics(lines, o.optBoolean("s"), o.optString("src"), o.optInt("d"), o.optString("ref"))
    }.getOrNull()?.takeIf { it.lines.isNotEmpty() && it.synced } // older saves can be untimed

    private fun writeSaved(url: String, ly: Lyrics) {
        runCatching {
            val f = savedFile(url) ?: return
            val arr = JSONArray()
            ly.lines.forEach { l ->
                val line = JSONArray().put(l.ms).put(l.text)
                if (l.words.isNotEmpty()) {
                    val w = JSONArray()
                    l.words.forEach { w.put(JSONArray().put(it.ms).put(it.endMs).put(it.from).put(it.to)) }
                    line.put(w)
                }
                arr.put(line)
            }
            val o = JSONObject().put("s", ly.synced).put("src", ly.source).put("d", ly.durationSec).put("v", SAVE_VERSION).put("ref", ly.ref).put("l", arr)
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    /** Saves from before the name and version checks (Nov 2026) have no "v": those get looked up again. */
    private const val SAVE_VERSION = 2

    private fun savedVersion(url: String): Int =
        runCatching { savedFile(url)?.takeIf { it.exists() }?.let { JSONObject(it.readText()).optInt("v", 1) } }.getOrNull() ?: 0

    /** Finds a downloaded song's lyrics and keeps them on the phone, so they show offline. */
    suspend fun keepFor(t: Track) {
        if (savedFile(t.url)?.exists() == true && savedVersion(t.url) >= SAVE_VERSION) return
        get(t.url, t.title, t.artist, t.seconds)?.let { writeSaved(t.url, it) }
    }

    /** A download was removed: its lyrics go too. */
    fun forget(url: String) {
        savedFile(url)?.delete()
    }

    fun forgetAll() {
        savedDir?.listFiles()?.forEach { it.delete() }
    }

    /* ---------------- No censoring ---------------- */

    /** The words lyric sites most often star out, so "f**k" can be put back as the artist wrote it. */
    private val swears = listOf(
        "fuck", "fucks", "fucked", "fucker", "fuckers", "fucking", "fuckin", "motherfucker", "motherfuckers", "motherfuckin",
        "shit", "shits", "shitty", "bullshit", "bitch", "bitches", "bitchin", "ass", "asses", "asshole", "assholes", "damn",
        "goddamn", "dick", "dicks", "pussy", "pussies", "cock", "cunt", "bastard", "bastards", "hoe", "hoes", "whore", "whores",
        "nigga", "niggas", "slut", "sluts", "piss", "pissed", "crap", "hell", "titties", "tits",
    )
    private val starred = Regex("[A-Za-z]*[*#]+[A-Za-z*#]*")

    /** Fills in starred-out words ("sh*t", "f***ing", "b----") when exactly one common word fits. */
    fun uncensor(line: String): String = starred.replace(line) { m ->
        val w = m.value
        if (w.none { it.isLetter() } && w.length < 3) return@replace w
        val hits = swears.filter { s ->
            s.length == w.length && s.indices.all { i -> !w[i].isLetter() || w[i].equals(s[i], ignoreCase = true) }
        }
        val pick = hits.singleOrNull() ?: return@replace w
        // Keep the original capitals.
        if (w.firstOrNull()?.isUpperCase() == true) pick.replaceFirstChar { it.uppercase() } else pick
    }

    private fun isCensored(text: String) = Regex("[A-Za-z][*#]+[A-Za-z]?").containsMatchIn(text)

    private fun fetch(u: String): String? {
        val req = Request.Builder().url(u).header("User-Agent", UA).build()
        // LRCLIB answers "busy" now and then; wait a moment and ask again rather than giving up.
        repeat(3) { attempt ->
            val reply = try { YtRepo.http.newCall(req).execute() } catch (e: java.io.IOException) { failedCalls.incrementAndGet(); throw e }
            val busy = reply.use { r ->
                if (r.isSuccessful) return r.body?.string()
                r.code == 503 || r.code == 429
            }
            if (!busy) return null
            Thread.sleep(600L * (attempt + 1))
        }
        failedCalls.incrementAndGet()
        return null
    }

    /* ---------------- LRCLIB ---------------- */

    private fun lrclib(title: String, artist: String, seconds: Long, skip: Set<String> = emptySet()): Lyrics? {
        // Exact match first (title, artist and length), then a search where the closest length wins:
        // the same song often exists as several cuts (radio edit, album, extended) with different timing.
        val exact = fetch(
            "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                if (seconds > 0) "&duration=$seconds" else "",
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
        // The exact match is just one candidate: on its own it can be a bad upload, so it gets
        // compared with the search results like everything else.
        val hits = mutableListOf<JSONObject>()
        exact?.let { hits += it }
        fun gap(o: JSONObject) = if (seconds <= 0) 0.0 else abs(o.optDouble("duration", 0.0) - seconds)
        fun hasSynced(o: JSONObject) = o.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" }
        for (q in listOf("track_name=${enc(title)}&artist_name=${enc(artist)}", "q=${enc("$artist $title")}")) {
            val arr = fetch("https://lrclib.net/api/search?$q")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: continue
            hits += (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            // Only stop early once there's a believable synced match of the right length.
            if (hits.count { hasSynced(it) && gap(it) <= 3 && believable(it, seconds) && thisSong(it.optString("trackName"), it.optString("artistName"), title, artist) } >= 3) break
        }
        pickSynced(
            hits.filter { hasSynced(it) && gap(it) <= 12 && believable(it, seconds) && thisSong(it.optString("trackName"), it.optString("artistName"), title, artist) && "LRCLIB:" + it.optLong("id") !in skip }
                .distinctBy { it.optLong("id") },
            ::gap,
        )
            ?.let { fromLrclib(it) }?.let { return it }
        // Lyrics without timing aren't used: they can't scroll with the song.
        return null
    }

    /**
     * Throws out made-up timings. LRCLIB has many uploads (Ravyn Lenae's "Love Me Not" is one)
     * where someone spread the words out evenly from 0:00, so the lines keep going after the song
     * has ended. Real timings end before the song does.
     */
    private fun believable(o: JSONObject, songSec: Long): Boolean {
        val raw = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return true
        val lines = parseLrc(raw).filter { it.text.isNotBlank() }
        if (lines.size < 4) return true
        val limit = listOf(o.optDouble("duration", 0.0), songSec.toDouble()).filter { it > 0 }.minOrNull()
        return limit == null || lines.last().ms / 1000.0 <= limit + 3
    }

    /**
     * Chooses between uploads of the same song:
     *  - Made-up timings start the first sung line at exactly 0:00. When other uploads start the
     *    singing later, those zero-start ones are dropped.
     *  - The length closest to this recording wins (within a few seconds counts as equal).
     *  - Among those, the timing most uploads agree on (same first-line time, to the second):
     *    real timings get copied around, one-off mistakes don't.
     *  - Then the uncensored one, then the very closest length.
     */
    private fun pickSynced(cands: List<JSONObject>, gap: (JSONObject) -> Double): JSONObject? {
        if (cands.isEmpty()) return null
        fun first(o: JSONObject) = parseLrc(o.optString("syncedLyrics")).firstOrNull { it.text.isNotBlank() }?.ms ?: 0L
        val starts = cands.associateWith { first(it) }
        val pool = if (cands.any { starts.getValue(it) > 2000 }) cands.filter { starts.getValue(it) > 0 } else cands
        val agree = pool.groupingBy { (starts.getValue(it) + 500) / 1000 }.eachCount()
        return pool.sortedWith(
            compareBy<JSONObject>(
                { Math.round(gap(it)) / 4 },
                { -agree.getValue((starts.getValue(it) + 500) / 1000) },
                { if (isCensored(it.optString("syncedLyrics"))) 1 else 0 },
                { gap(it) },
            ),
        ).firstOrNull()
    }

    private fun fromLrclib(o: JSONObject): Lyrics? {
        val dur = o.optDouble("duration", 0.0).toInt()
        val ref = "LRCLIB:" + o.optLong("id")
        if (o.optBoolean("instrumental")) return Lyrics(listOf(LyricLine(0, "♪ Instrumental ♪")), false, "LRCLIB", dur, ref)
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) parseLrc(synced).takeIf { it.isNotEmpty() }?.let { return Lyrics(it, true, "LRCLIB", dur, ref) }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Lyrics(plain.lines().map { LyricLine(-1, it.trim()) }, false, "LRCLIB", dur, ref)
    }

    /* ---------------- Per-song nudge ---------------- */

    private var prefs: android.content.SharedPreferences? = null
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("lyrics_offset", android.content.Context.MODE_PRIVATE)
        blockPrefs = context.getSharedPreferences("lyrics_blocked", android.content.Context.MODE_PRIVATE)
        savedDir = java.io.File(context.filesDir, "lyrics").apply { mkdirs() }
        // Songs downloaded before lyrics were kept with them: fetch theirs quietly in the background.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            kotlinx.coroutines.delay(8_000)
            val songs = withContext(Dispatchers.Main) { Downloads.tracks.toList() }
            for (t in songs) runCatching { keepFor(t) }
        }
    }

    /** How many milliseconds earlier (+) or later (−) this song's lyrics should show. Saved per song. */
    fun offset(url: String): Long = prefs?.getLong(url, 0L) ?: 0L
    fun setOffset(url: String, ms: Long) { prefs?.edit()?.putLong(url, ms.coerceIn(-10_000, 10_000))?.apply() }

    /* ---------------- Lyrics people reported ---------------- */

    /*
     * "Wrong lyrics" or "Out of sync" rules those exact lyrics out for that song and shows the next
     * best straight away. Your own reports work at once (kept on this device); everyone's reports
     * reach the shared list the hourly job keeps (lyrics-blocked.json on the "stats" branch), so one
     * report fixes a song for everybody, with nobody having to do anything.
     */
    private const val SHARED = "https://raw.githubusercontent.com/uronlynonny/Glasswave/stats/lyrics-blocked.json"
    private var blockPrefs: android.content.SharedPreferences? = null
    @Volatile private var shared: Map<String, Set<String>> = emptyMap()
    @Volatile private var sharedAt = 0L

    private fun blocked(url: String): Set<String> =
        (blockPrefs?.getString(url, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty() + shared[url].orEmpty()).toSet()

    /** Reads the shared list again when it's more than a few hours old. Blocking. */
    private fun refreshShared() {
        if (System.currentTimeMillis() - sharedAt < 3 * 3_600_000L) return
        sharedAt = System.currentTimeMillis()
        val o = runCatching { fetch(SHARED)?.let { JSONObject(it) } }.getOrNull() ?: return
        shared = o.keys().asSequence().associateWith { k ->
            o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        }
    }

    /** Lyrics changed for some song (a report found other ones): lyrics on screen load again. */
    var changes by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Never picks [ly] for this song again, and looks for the next best. Returns them, or null if there are none. */
    suspend fun reject(url: String, title: String, artist: String, seconds: Long, ly: Lyrics?): Lyrics? {
        if (ly != null && ly.ref.isNotEmpty()) {
            // This device's own list for the song, plus these.
            val now = blockPrefs?.getString(url, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty() + ly.ref
            blockPrefs?.edit()?.putString(url, now.distinct().joinToString("\n"))?.apply()
        }
        return lookAgain(url, title, artist, seconds)
    }

    /** "No lyrics": forgets that this song had none and looks again. */
    suspend fun lookAgain(url: String, title: String, artist: String, seconds: Long): Lyrics? {
        cache.remove(url)
        none.remove(url)
        forget(url)
        val found = get(url, title, artist, seconds)
        withContext(Dispatchers.Main) { changes++ }
        return found
    }

    /* ---------------- NetEase ---------------- */

    /**
     * NetEase Cloud Music: a big catalogue with timed lyrics for many songs the others miss
     * (afrobeats, UK rap). Only a song with the same name, artist and about the same length counts.
     */
    private fun netease(title: String, artist: String, seconds: Long): Lyrics? {
        val res = fetch("https://music.163.com/api/search/get?s=${enc("$title $artist")}&type=1&limit=10")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("result")?.optJSONArray("songs") ?: return null
        fun base(s: String) = s.substringBefore(" (").substringBefore(" [").substringBefore(" - ").trim().lowercase()
        val name = base(title)
        val lead = artist.lowercase().trim()
        val songs = (0 until res.length()).mapNotNull { res.optJSONObject(it) }.filter { s ->
            val artists = s.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("name").orEmpty().lowercase() } }.orEmpty()
            val sec = s.optLong("duration") / 1000
            base(s.optString("name")) == name &&
                (lead.isEmpty() || artists.any { it == lead || it.contains(lead) || (lead.contains(it) && it.length > 2) }) &&
                (seconds <= 0 || abs(sec - seconds) <= 8)
        }.sortedBy { if (seconds <= 0) 0L else abs(it.optLong("duration") / 1000 - seconds) }
        for (s in songs.take(2)) {
            val lrc = fetch("https://music.163.com/api/song/lyric?id=${s.optLong("id")}&lv=1&kv=1&tv=-1")
                ?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("lrc")?.optString("lyric").orEmpty()
            // It starts with credit lines ("作词 : ..."); leave those out.
            val lines = parseLrc(lrc).filterNot { l ->
                l.text.contains("：") || l.text.contains(" : ") ||
                    Regex("作词|作曲|编曲|制作|Lyricist|Composer|Producer", RegexOption.IGNORE_CASE).containsMatchIn(l.text)
            }
            if (lines.count { it.text.isNotBlank() } >= 4) return Lyrics(lines, true, "NetEase", (s.optLong("duration") / 1000).toInt(), "NetEase:" + s.optLong("id"))
        }
        return null
    }

    /* ---------------- KuGou ---------------- */

    private fun kugou(title: String, artist: String, seconds: Long): Lyrics? {
        val search = fetch(
            "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=" + enc("$artist - $title"),
        )?.let { JSONObject(it) } ?: return null
        val info = search.optJSONObject("data")?.optJSONArray("info") ?: return null
        val songs = (0 until info.length()).mapNotNull { info.optJSONObject(it) }
            .filter { seconds <= 0 || abs(it.optLong("duration") - seconds) <= 8 }
            .filter { thisSong(it.optString("songname"), it.optString("singername"), title, artist) }
            .sortedBy { if (seconds <= 0) 0L else abs(it.optLong("duration") - seconds) }
        for (s in songs.take(3)) {
            val hash = s.optString("hash")
            if (hash.isEmpty()) continue
            val dur = s.optLong("duration") * 1000
            val cands = fetch(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc("$artist - $title")}&duration=$dur&hash=$hash",
            )?.let { JSONObject(it).optJSONArray("candidates") } ?: continue
            val c = cands.optJSONObject(0) ?: continue
            val dl = "https://lyrics.kugou.com/download?charset=utf8&client=pc&ver=1&id=${c.optString("id")}&accesskey=${c.optString("accesskey")}"
            // KuGou starts with credit lines ("作词 : ..."); leave those out.
            val titleKey = title.substringBefore(" (").substringBefore(" [").trim().lowercase()
            fun credits(lines: List<LyricLine>) = lines.filterNot { l ->
                // The opening "Artist - Title" line (the title may be written differently, so the artist counts too).
                (l.ms < 5_000 && l.text.contains(" - ") && titleKey.isNotEmpty() && l.text.lowercase().contains(titleKey)) ||
                (l.ms < 5_000 && l.text.contains(" - ") && artist.length > 2 && l.text.lowercase().contains(artist.lowercase())) ||
                l.text.contains("：") || l.text.contains(" : ") || Regex("作词|作曲|编曲|制作|Lyricist|Composer|Producer", RegexOption.IGNORE_CASE).containsMatchIn(l.text)
            }
            // The word-by-word version (KRC) first; the plain timed version if that doesn't work out.
            val krc = runCatching {
                fetch("$dl&fmt=krc")?.let { JSONObject(it).optString("content") }
                    ?.let { krcText(Base64.decode(it, Base64.DEFAULT)) }
                    ?.let { credits(parseKrc(it)) }
            }.getOrNull()
            val lines = krc?.takeIf { it.size >= 4 } ?: run {
                val body = fetch("$dl&fmt=lrc")?.let { JSONObject(it) } ?: return@run null
                credits(parseLrc(String(Base64.decode(body.optString("content"), Base64.DEFAULT), Charsets.UTF_8)))
            } ?: continue
            if (lines.size >= 4) return Lyrics(lines, true, "KuGou", s.optLong("duration").toInt(), "KuGou:" + c.optString("id"))
        }
        return null
    }

    /**
     * Is KuGou's [other] the same song, timed the same way, as LRCLIB's [lrc]? Many of the same lines
     * must turn up at about the same time, so a different song, a translation or another cut is
     * turned down.
     */
    private fun sameSong(lrc: Lyrics, other: Lyrics): Boolean {
        // A line's words, lower case, without punctuation. Chinese and Japanese have no spaces, so
        // each of their characters counts as a word.
        fun words(s: String): Set<String> {
            val out = HashSet<String>()
            for (w in uncensor(s).lowercase().split(nonWord)) {
                val t = w.replace("'", "").replace("’", "")
                if (t.isEmpty()) continue
                if (t.any { Character.UnicodeScript.of(it.code) in cjk }) t.forEach { out += it.toString() } else out += t
            }
            return out
        }
        val a = lrc.lines.map { it to words(it.text) }.filter { it.second.isNotEmpty() }
        val b = other.lines.map { it to words(it.text) }.filter { it.second.isNotEmpty() }
        if (a.size < 4 || b.size < a.size * 0.5 || b.size > a.size * 2) return false
        // For each LRCLIB line: KuGou's lines that say mostly the same words, and how far apart in
        // time the nearest of those is. ("I must've called" and "I must have called" count as alike.)
        val gaps = a.mapNotNull { (l, w) ->
            b.filter { (_, v) -> 2.0 * w.count { it in v } / (w.size + v.size) >= 0.6 }
                .minOfOrNull { abs(it.first.ms - l.ms) }
        }.sorted()
        if (gaps.size < maxOf(4.0, a.size * 0.5)) return false
        return gaps[gaps.size / 2] <= 1500
    }

    /**
     * How much of KuGou's [other] says the same words, in the same order, as LRCLIB's [lrc] (0 to 1).
     * KuGou's English lyrics are often written down by ear ("Stay" for "Sad", whole lines changed), and
     * its lines can still line up in time, so [sameSong] alone let them through (Michael Jackson's
     * "Chicago" had 45 of 82 lines different). Backing vocals in brackets don't count: one site often
     * leaves them out. Chinese and Japanese count each character as a word.
     */
    private fun sameWords(lrc: Lyrics, other: Lyrics): Double {
        fun tokens(l: Lyrics): List<String> {
            val out = ArrayList<String>()
            for (line in l.lines) for (w in uncensor(line.text).replace(bracketed, " ").lowercase().split(nonWord)) {
                val t = w.replace("'", "").replace("’", "")
                if (t.isEmpty()) continue
                if (t.any { Character.UnicodeScript.of(it.code) in cjk }) t.forEach { out += it.toString() } else out += t
            }
            return out
        }
        val a = tokens(lrc)
        val b = tokens(other)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // Longest run of words in common, in order (two rows of a table, so it stays small).
        var prev = IntArray(b.size + 1)
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            for (j in 1..b.size) cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            val t = prev; prev = cur; cur = t
        }
        return 2.0 * prev[b.size] / (a.size + b.size)
    }

    /**
     * How many of LRCLIB's sung lines (two words or more) turn up word for word in KuGou's [other],
     * near the same time (0 to 1). A few changed words spread over a song still let [sameWords]
     * score high ("Go" for "So", a line turned into "Ami" in Bruno Mars' "That's What I Like"), so
     * every line has to be right too. KuGou's line breaks may differ, so its words count as one stream.
     */
    private fun sameLines(lrc: Lyrics, other: Lyrics): Double {
        val stream = other.lines.flatMap { l -> wordsOf(l.text).map { l.ms to it } }
        val words = stream.map { it.second }
        var good = 0
        var total = 0
        for (l in lrc.lines) {
            val w = wordsOf(l.text)
            if (w.size < 2) continue
            total++
            val first = stream.indexOfFirst { abs(it.first - l.ms) <= 6000 }
            if (first < 0) continue
            val last = stream.indexOfLast { abs(it.first - l.ms) <= 6000 }
            val seg = words.subList(maxOf(0, first - 5), minOf(words.size, last + 30))
            if ((0..seg.size - w.size).any { i -> seg.subList(i, i + w.size) == w }) good++
        }
        return if (total == 0) 0.0 else good.toDouble() / total
    }

    /** A line's words for comparing: lower case, no punctuation or bracketed backing vocals; Chinese and Japanese by character. */
    private fun wordsOf(text: String): List<String> {
        val out = ArrayList<String>()
        for (w in uncensor(text).replace(bracketed, " ").lowercase().split(nonWord)) {
            val t = w.replace("'", "").replace("’", "")
            if (t.isEmpty()) continue
            if (t.any { Character.UnicodeScript.of(it.code) in cjk }) t.forEach { out += it.toString() } else out += t
        }
        return out
    }

    private val bracketed = Regex("\\([^)]*\\)")

    /** Words that mark a different version of a song: its lyrics or timing differ from the original. */
    private val versionWords = listOf("remix", "live", "acoustic", "instrumental", "karaoke", "cover", "sped", "slowed", "nightcore", "unplugged", "demo", "spanglish", "reverb", "8d")

    private fun versionOf(name: String): Set<String> {
        val n = name.lowercase()
        return versionWords.filter { Regex("\\b" + it + "\\b").containsMatchIn(n) }.toSet()
    }

    /** A title boiled down for comparing: no "(feat. …)" or "[Official Video]", no punctuation, lower case. */
    private fun titleKey(s: String) = cleanTitle(s).replace(bracketed, " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        .lowercase().replace(nonWord, " ").trim().replace(Regex("\\s+"), " ")

    /**
     * Is a lyric site's [name] by [by] this song ([title] by [artist])? Same name (uploads called
     * "Artist - Title" count), same artist when the site names one we can compare, and the same version:
     * a remix, live, slowed or sped-up upload has different words or timing.
     */
    private fun thisSong(name: String, by: String, title: String, artist: String): Boolean {
        if (versionOf(name) != versionOf(title)) return false
        val want = titleKey(title)
        val lead = titleKey(artist)
        var got = titleKey(name)
        if (lead.isNotEmpty() && got.startsWith("$lead ")) got = got.removePrefix("$lead ").trim()
        if (want.isEmpty() || got.isEmpty()) return true
        val nameOk = got == want || got.contains(want) || (want.contains(got) && got.length >= 4)
        if (!nameOk) return false
        val who = titleKey(by)
        // Artists written in other scripts can't be compared letter by letter; leave those be.
        val comparable = lead.isNotEmpty() && who.isNotEmpty() && who.all { it.code < 0x2E80 } && lead.all { it.code < 0x2E80 }
        return !comparable || who.contains(lead) || lead.contains(who) || titleKey(name).contains(lead)
    }

    private val nonWord = Regex("[^\\p{L}\\p{N}'’]+")
    private val cjk = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

    /* ---------------- KuGou word timing (KRC) ---------------- */

    private val krcKey = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    /** Unpacks a KRC file: "krc1", then the rest scrambled with a fixed key and zipped. */
    fun krcText(raw: ByteArray): String? {
        if (raw.size <= 4 || String(raw, 0, 4, Charsets.US_ASCII) != "krc1") return null
        val data = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor krcKey[i % krcKey.size]).toByte() }
        val inflater = java.util.zip.Inflater()
        return try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 3)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break // ran out (or a broken file)
                out.write(buf, 0, n)
            }
            if (!inflater.finished()) null else String(out.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private val krcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val krcWord = Regex("<(\\d+),(\\d+),\\d+>")

    /**
     * Reads KRC lines: "[12345,3000]<0,500,0>Hel<500,400,0>lo" is a line starting at 12.345s, with
     * "Hel" sung from its start for half a second, then "lo". Word times count from the line's start.
     * Other lines ("[ti:...]", "[language:...]") are left out.
     */
    fun parseKrc(krc: String): List<LyricLine> {
        val offset = offsetTag.find(krc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricLine>()
        for (raw in krc.lines()) {
            val m = krcLine.find(raw.trim()) ?: continue
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val body = m.groupValues[3]
            val tags = krcWord.findAll(body).toList()
            val sb = StringBuilder()
            val words = mutableListOf<LyricWord>()
            sb.append(body, 0, tags.firstOrNull()?.range?.first ?: body.length)
            tags.forEachIndexed { i, t ->
                val seg = body.substring(t.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: body.length)
                val ws = start + t.groupValues[1].toLong() - offset
                val we = ws + t.groupValues[2].toLong()
                val from = sb.length
                sb.append(seg)
                if (seg.isNotBlank()) words += LyricWord(ws.coerceAtLeast(0), we.coerceAtLeast(0), from, sb.length)
            }
            out += trimmed((start - offset).coerceAtLeast(0), sb.toString(), words)
        }
        return out.sortedBy { it.ms }
    }

    /** Trims spaces off a line, keeping its word positions pointing at the same letters. */
    private fun trimmed(ms: Long, text: String, words: List<LyricWord>): LyricLine {
        val lead = text.length - text.trimStart().length
        val t = text.trim()
        val w = if (t.isEmpty()) emptyList() else words.mapNotNull { x ->
            val from = (x.from - lead).coerceIn(0, t.length)
            val to = (x.to - lead).coerceIn(0, t.length)
            if (from < to && t.substring(from, to).isNotBlank()) x.copy(from = from, to = to) else null
        }
        return LyricLine(ms, t, w)
    }

    /* ---------------- YouTube Music ---------------- */

    /** YouTube Music's timed lyrics for the song (its explicit version when it's a clean one). */
    private fun youtubeTimed(url: String): Lyrics? {
        val id = YtRepo.videoIdFor(runCatching { YtRepo.uncensored(url) }.getOrDefault(url)) ?: return null
        val lines = YtMusicApi.timedLyrics(id) ?: return null
        return Lyrics(lines.map { (ms, text) -> LyricLine(ms, text.trim()) }, true, "YouTube Music", 0, "YouTube")
    }

    private suspend fun youtube(url: String): Lyrics? {
        val id = YtRepo.videoIdFor(url) ?: return null
        val text = YtMusicApi.lyrics(id) ?: return null
        return Lyrics(text.lines().map { LyricLine(-1, it.trim()) }, false, "YouTube Music")
    }

    /* ---------------- LRC ---------------- */

    private val tag = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val offsetTag = Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    /** "mm", "ss" and the fraction of a time tag, in milliseconds. */
    private fun tagMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min * 60_000 + sec * 1000 + ms
    }

    /**
     * Reads "[01:02.34] words" lines. A line can carry several times. Lines can also time each word
     * ("enhanced" LRC): "[00:12.00]<00:12.00>Hello <00:12.50>world <00:13.10>", where each <time>
     * is when the next word starts (and the last one is when the line's singing ends).
     */
    fun parseLrc(lrc: String): List<LyricLine> {
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val times = tag.findAll(raw).toList()
            if (times.isEmpty()) continue
            val body = raw.substring(times.last().range.last + 1)
            val wt = wordTag.findAll(body).toList()
            if (wt.isEmpty()) {
                val text = body.trim()
                for (m in times) out += LyricLine((tagMs(m) - offset).coerceAtLeast(0), text)
                continue
            }
            // Word times, as written for the line's first time; -1 = end not given (filled in below).
            val sb = StringBuilder()
            val words = mutableListOf<LyricWord>()
            sb.append(body, 0, wt.first().range.first)
            wt.forEachIndexed { i, t ->
                val seg = body.substring(t.range.last + 1, wt.getOrNull(i + 1)?.range?.first ?: body.length)
                val from = sb.length
                sb.append(seg)
                if (seg.isNotBlank()) words += LyricWord(tagMs(t), wt.getOrNull(i + 1)?.let { tagMs(it) } ?: -1L, from, sb.length)
            }
            val first = tagMs(times.first())
            for (m in times) {
                // A repeated line (several times up front) sings its words the same way each time.
                val shift = tagMs(m) - first - offset
                val moved = words.map { w ->
                    w.copy(ms = (w.ms + shift).coerceAtLeast(0), endMs = if (w.endMs < 0) -1 else (w.endMs + shift).coerceAtLeast(0))
                }
                out += trimmed((tagMs(m) - offset).coerceAtLeast(0), sb.toString(), moved)
            }
        }
        val sorted = out.sortedBy { it.ms }
        // A last word with no end time ends when the next line starts (at most a few seconds on).
        return sorted.mapIndexed { i, l ->
            if (l.words.none { it.endMs < 0 }) l
            else {
                val next = sorted.getOrNull(i + 1)?.ms ?: Long.MAX_VALUE
                l.copy(words = l.words.map { w -> if (w.endMs >= 0) w else w.copy(endMs = minOf(next, w.ms + 3000).coerceAtLeast(w.ms)) })
            }
        }
    }
}
