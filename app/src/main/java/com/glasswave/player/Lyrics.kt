// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.util.Base64
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** One lyric line and when it starts. [ms] is -1 for lyrics without timing. */
data class LyricLine(val ms: Long, val text: String)

/** [durationSec] is the length of the recording these lyrics were timed to (0 if unknown). */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String, val durationSec: Int = 0)

/**
 * Finds lyrics for a song, trying in order:
 * 1. LRCLIB, a free open lyrics library with time-synced lyrics,
 * 2. KuGou, a big lyrics catalogue (synced) that many open-source players use as a backup,
 * 3. YouTube Music's own lyrics (not timed).
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

    suspend fun get(url: String, title: String, artist: String, seconds: Long): Lyrics? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }
        if (url in none) return@withContext null
        val t = cleanTitle(title)
        val a = mainArtist(artist)
        val found = runCatching { lrclib(t, a, seconds) }.getOrNull()
            ?: runCatching { kugou(t, a, seconds) }.getOrNull()
            ?: runCatching { youtube(url) }.getOrNull()
        val clean = found?.let { ly -> ly.copy(lines = ly.lines.map { it.copy(text = uncensor(it.text)) }) }
        if (clean != null && clean.lines.isNotEmpty()) cache[url] = clean else none += url
        clean
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
        return YtRepo.http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    /* ---------------- LRCLIB ---------------- */

    private fun lrclib(title: String, artist: String, seconds: Long): Lyrics? {
        // Exact match first (title, artist and length), then a search where the closest length wins:
        // the same song often exists as several cuts (radio edit, album, extended) with different timing.
        val exact = fetch(
            "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                if (seconds > 0) "&duration=$seconds" else "",
        )?.let { JSONObject(it) }
        exact?.let { fromLrclib(it) }?.let { return it }
        val hits = mutableListOf<JSONObject>()
        for (q in listOf("track_name=${enc(title)}&artist_name=${enc(artist)}", "q=${enc("$artist $title")}")) {
            val arr = fetch("https://lrclib.net/api/search?$q")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: continue
            hits += (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            if (hits.any { seconds <= 0 || abs(it.optDouble("duration", 0.0) - seconds) <= 3 }) break
        }
        fun gap(o: JSONObject) = if (seconds <= 0) 0.0 else abs(o.optDouble("duration", 0.0) - seconds)
        // Closest length first; between equally close ones, the uncensored version.
        val synced = hits.filter { it.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" } && gap(it) <= 12 }
            .sortedWith(compareBy({ Math.round(gap(it)) }, { if (isCensored(it.optString("syncedLyrics"))) 1 else 0 }))
        synced.firstOrNull()?.let { fromLrclib(it) }?.let { return it }
        val plain = hits.filter { it.optString("plainLyrics").let { s -> s.isNotBlank() && s != "null" } }.minByOrNull { gap(it) }
        return plain?.let { fromLrclib(it) }
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
    fun init(context: android.content.Context) { prefs = context.getSharedPreferences("lyrics_offset", android.content.Context.MODE_PRIVATE) }

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
            val body = fetch(
                "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=${c.optString("id")}&accesskey=${c.optString("accesskey")}",
            )?.let { JSONObject(it) } ?: continue
            val text = String(Base64.decode(body.optString("content"), Base64.DEFAULT), Charsets.UTF_8)
            // KuGou starts with credit lines ("作词 : ..."); leave those out.
            val lines = parseLrc(text).filterNot { l ->
                l.text.contains("：") || l.text.contains(" : ") || Regex("作词|作曲|编曲|制作|Lyricist|Composer|Producer", RegexOption.IGNORE_CASE).containsMatchIn(l.text)
            }
            if (lines.size >= 4) return Lyrics(lines, true, "KuGou", s.optLong("duration").toInt())
        }
        return null
    }

    /* ---------------- YouTube Music ---------------- */

    private suspend fun youtube(url: String): Lyrics? {
        val id = YtRepo.videoIdFor(url) ?: return null
        val text = YtMusicApi.lyrics(id) ?: return null
        return Lyrics(text.lines().map { LyricLine(-1, it.trim()) }, false, "YouTube Music")
    }

    /* ---------------- LRC ---------------- */

    private val tag = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val offsetTag = Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    /** Reads "[01:02.34] words" lines. A line can carry several times; word timings are dropped. */
    fun parseLrc(lrc: String): List<LyricLine> {
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val times = tag.findAll(raw).toList()
            if (times.isEmpty()) continue
            val text = raw.substring(times.last().range.last + 1).replace(wordTag, "").trim()
            for (m in times) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out += LyricLine((min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0), text)
            }
        }
        return out.sortedBy { it.ms }
    }
}
