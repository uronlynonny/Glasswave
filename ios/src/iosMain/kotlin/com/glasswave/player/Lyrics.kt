// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlin.math.abs

data class LyricLine(val ms: Long, val text: String)

/** Timed lyrics from LRCLIB (the same free source the phone app tries first). */
object Lyrics {
    private val cache = HashMap<String, List<LyricLine>?>()
    private val tag = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    suspend fun find(t: Track): List<LyricLine>? {
        if (cache.containsKey(t.videoId)) return cache[t.videoId]
        val title = t.title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()
        val artist = t.artist.substringBefore(",").substringBefore("&").trim()
        val hits = mutableListOf<JSONObject>()
        runCatching {
            val (code, text) = Net.get("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
            if (code == 200) {
                val a = JSONArray(text)
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { hits += it }
            }
        }
        fun gap(o: JSONObject) = if (t.seconds <= 0) 0.0 else abs(o.optDouble("duration", 0.0) - t.seconds)
        val best = hits
            .filter { o -> o.optString("syncedLyrics").let { it.isNotBlank() && it != "null" } && gap(o) <= 8 }
            .minByOrNull { gap(it) }
        val lines = best?.let { parse(it.optString("syncedLyrics")) }?.takeIf { it.size >= 4 }
        cache[t.videoId] = lines
        return lines
    }

    fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val times = tag.findAll(raw).toList()
            if (times.isEmpty()) continue
            val text = raw.substring(times.last().range.last + 1).trim()
            for (m in times) {
                val frac = m.groupValues[3]
                val ms = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 +
                    (if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong())
                out += LyricLine(ms, text)
            }
        }
        return out.sortedBy { it.ms }
    }
}
