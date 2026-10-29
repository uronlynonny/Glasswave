// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The songs you keep searching for. Each time you play a song from a search, the search and the
 * song are noted. Once you've picked the same song from searches a couple of times, it comes up
 * at the top as soon as what you type matches (like "sli" for the "slide her" you always type).
 * Kept on this device only.
 */
object SearchPicks {
    private class Pick(val query: String, val track: Track, var count: Int, var at: Long)

    private const val KEEP = 150
    /** Picked this many times before it's offered while typing. */
    private const val OFTEN = 2

    private var prefs: SharedPreferences? = null
    private val picks = ArrayList<Pick>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("search_picks", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(prefs?.getString("picks", null) ?: return)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = Track(o.getString("u"), o.optString("t"), o.optString("a"), o.optLong("s"), o.optString("i").ifEmpty { null })
                picks += Pick(o.getString("q"), t, o.optInt("n", 1), o.optLong("at"))
            }
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** You played [t] from a search for [query]. */
    @Synchronized
    fun record(query: String, t: Track) {
        val q = norm(query)
        if (q.length < 2 || t.url.isEmpty()) return
        val p = picks.firstOrNull { it.query == q && it.track.url == t.url }
        if (p != null) { p.count++; p.at = System.currentTimeMillis() }
        else picks += Pick(q, t.copy(seconds = t.seconds), 1, System.currentTimeMillis())
        // Keep the most used and most recent.
        picks.sortByDescending { it.count * 10_000_000_000L + it.at / 1000 }
        while (picks.size > KEEP) picks.removeAt(picks.lastIndex)
        save()
    }

    /**
     * Songs you often pick for searches that start like [typed] (3 letters at least): the
     * typed words are the start of the words you usually type, or of the song's name.
     * Most picked first, at most [max].
     */
    @Synchronized
    fun match(typed: String, max: Int = 2): List<Track> {
        val q = norm(typed)
        if (q.length < 3) return emptyList()
        val byUrl = LinkedHashMap<String, Pair<Track, Int>>()
        for (p in picks) {
            val hit = p.query.startsWith(q) || norm(p.track.title).startsWith(q) || norm("${p.track.title} ${p.track.artist}").startsWith(q)
            if (!hit) continue
            val had = byUrl[p.track.url]
            byUrl[p.track.url] = p.track to ((had?.second ?: 0) + p.count)
        }
        return byUrl.values.filter { it.second >= OFTEN }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    fun clear() {
        synchronized(this) { picks.clear() }
        prefs?.edit()?.remove("picks")?.apply()
    }

    private fun save() {
        val arr = JSONArray()
        picks.forEach { p ->
            arr.put(JSONObject().put("q", p.query).put("n", p.count).put("at", p.at)
                .put("u", p.track.url).put("t", p.track.title).put("a", p.track.artist).put("s", p.track.seconds).put("i", p.track.thumb ?: ""))
        }
        prefs?.edit()?.putString("picks", arr.toString())?.apply()
    }
}
