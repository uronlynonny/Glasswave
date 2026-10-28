// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.util.Base64
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
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String, val durationSec: Int = 0) {
    /** True when the words themselves are timed, not just the lines. */
    val wordTimed: Boolean get() = synced && lines.any { it.words.isNotEmpty() }
}

/**
 * Finds lyrics for a song, trying in order:
 * 1. LRCLIB, a free open lyrics library with time-synced lyrics,
 * 2. KuGou, a big lyrics catalogue (synced) that many open-source players use as a backup,
 * 3. YouTube Music's own lyrics (not timed).
 * KuGou often times every word, not just every line. When LRCLIB only times lines, KuGou's
 * word-by-word version is used instead if it's clearly the same song with the same timing.
 * Results are remembered for the session.
 */
object LyricsRepo {
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private val none = ConcurrentHashMap.newKeySet<String>()
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
        // A downloaded song keeps its lyrics on this PC, so they show offline too.
        saved(url)?.let { cache[url] = it; return@withContext it }
        if (url in none) return@withContext null
        val t = cleanTitle(title)
        val a = mainArtist(artist)
        // Synced lyrics from anywhere beat unsynced ones; unsynced are the last resort.
        // LRCLIB and KuGou are asked at the same time, not one after the other.
        val fromKugou = async { runCatching { kugou(t, a, seconds) }.getOrNull() }
        val fromLrclib = runCatching { lrclib(t, a, seconds) }.getOrNull()
        fun fits(k: Lyrics) = seconds <= 0 || (k.lines.lastOrNull()?.ms ?: 0) / 1000 <= seconds + 3
        val lrclibSynced = fromLrclib?.takeIf { it.synced }
        val found = if (lrclibSynced != null) {
            // LRCLIB usually times whole lines; KuGou often times every word. Use KuGou's only when
            // it's clearly the same song with the same timing, otherwise keep LRCLIB's.
            if (lrclibSynced.wordTimed) lrclibSynced.also { fromKugou.cancel() }
            else fromKugou.await()
                ?.takeIf { k -> k.wordTimed && fits(k) && sameSong(lrclibSynced, k) }
                ?: lrclibSynced
        } else {
            fromKugou.await()?.takeIf { fits(it) }
                ?: fromLrclib
                ?: runCatching { youtube(url) }.getOrNull()
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
            if (Downloads.isDownloaded(url)) save(url, clean)
        } else none += url
        clean
    }

    /* ---------------- Lyrics kept with downloaded songs ---------------- */

    // One small file per downloaded song: lyrics/<sha1 of the song's address>.json, shaped like the
    // phone app's {"s": synced, "src": source, "d": seconds, "l": [[ms, text], ...]}.
    private val dir by lazy { java.io.File(android.content.AppDirs.data, "lyrics").apply { mkdirs() } }
    private val keeper = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun fileFor(url: String): java.io.File {
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$sha.json")
    }

    private fun saved(url: String): Lyrics? = runCatching {
        val f = fileFor(url)
        if (!f.exists()) return null
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
        Lyrics(lines, o.optBoolean("s"), o.optString("src"), o.optInt("d"))
    }.getOrNull()?.takeIf { it.lines.isNotEmpty() }

    private fun save(url: String, ly: Lyrics) {
        runCatching {
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
            val json = JSONObject().put("s", ly.synced).put("src", ly.source).put("d", ly.durationSec).put("l", arr)
            val f = fileFor(url)
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** After a song downloads: find its lyrics now and keep them with it. */
    fun keepFor(t: Track) {
        keeper.launch {
            if (saved(t.url) != null) return@launch
            none -= t.url
            get(t.url, t.title, t.artist, t.seconds)?.let { save(t.url, it) }
        }
    }

    /** A download was removed: its lyrics go too. */
    fun forget(url: String) { runCatching { fileFor(url).delete() } }

    fun forgetAll() { runCatching { dir.listFiles()?.forEach { it.delete() } } }

    /** Songs downloaded before lyrics were kept get theirs too, quietly, a little after start. */
    private fun fillIn() {
        keeper.launch {
            kotlinx.coroutines.delay(8_000)
            for (t in Downloads.tracks.toList()) {
                if (saved(t.url) != null) continue
                runCatching { get(t.url, t.title, t.artist, t.seconds)?.let { save(t.url, it) } }
                kotlinx.coroutines.delay(500)
            }
        }
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
            val busy = YtRepo.http.newCall(req).execute().use { r ->
                if (r.isSuccessful) return r.body?.string()
                r.code == 503 || r.code == 429
            }
            if (!busy) return null
            Thread.sleep(600L * (attempt + 1))
        }
        return null
    }

    /* ---------------- LRCLIB ---------------- */

    private fun lrclib(title: String, artist: String, seconds: Long): Lyrics? {
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
            if (hits.count { hasSynced(it) && gap(it) <= 3 && believable(it, seconds) } >= 3) break
        }
        pickSynced(hits.filter { hasSynced(it) && gap(it) <= 12 && believable(it, seconds) }.distinctBy { it.optLong("id") }, ::gap)
            ?.let { fromLrclib(it) }?.let { return it }
        val plain = hits.filter { it.optString("plainLyrics").let { s -> s.isNotBlank() && s != "null" } }.minByOrNull { gap(it) }
        return plain?.let { fromLrclib(it) }
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
        if (o.optBoolean("instrumental")) return Lyrics(listOf(LyricLine(0, "♪ Instrumental ♪")), false, "LRCLIB", dur)
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) parseLrc(synced).takeIf { it.isNotEmpty() }?.let { return Lyrics(it, true, "LRCLIB", dur) }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Lyrics(plain.lines().map { LyricLine(-1, it.trim()) }, false, "LRCLIB", dur)
    }

    /* ---------------- Per-song nudge ---------------- */

    private var prefs: android.content.SharedPreferences? = null
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("lyrics_offset", android.content.Context.MODE_PRIVATE)
        fillIn()
    }

    /** How many milliseconds earlier (+) or later (−) this song's lyrics should show. Saved per song. */
    fun offset(url: String): Long = prefs?.getLong(url, 0L) ?: 0L
    fun setOffset(url: String, ms: Long) { prefs?.edit()?.putLong(url, ms.coerceIn(-10_000, 10_000))?.apply() }

    /* ---------------- KuGou ---------------- */

    private fun kugou(title: String, artist: String, seconds: Long): Lyrics? {
        val search = fetch(
            "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=" + enc("$artist - $title"),
        )?.let { JSONObject(it) } ?: return null
        val info = search.optJSONObject("data")?.optJSONArray("info") ?: return null
        val songs = (0 until info.length()).mapNotNull { info.optJSONObject(it) }
            .filter { seconds <= 0 || abs(it.optLong("duration") - seconds) <= 8 }
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
            fun credits(lines: List<LyricLine>) = lines.filterNot { l ->
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
            if (lines.size >= 4) return Lyrics(lines, true, "KuGou", s.optLong("duration").toInt())
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
