// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * The songs you know: the ones you keep searching for, and the ones you play most. Each time you
 * play a song from a search, the search and the song are noted; the listening stats say how often
 * you've played each song. A song you know well comes up at the top as soon as what you type is
 * the start of its name (like "sli" for the "slide her" you always play). Kept on this device only.
 */
object SearchPicks {
    private class Pick(val query: String, val track: Track, var count: Int, var at: Long)

    /** How often you've played a song (from the listening stats) and when you last did. */
    private class Played(var track: Track, var count: Int, var at: Long)

    /**
     * A song you know: [plays] times played (listening stats; at least 1 when it's in Recently
     * played), [picks] times picked from a search, last one at [last] (ms, 0 when unknown), and
     * the searches you found it with.
     */
    class Known(val track: Track, val plays: Int, val picks: Int, val last: Long, val queries: List<String>) {
        /**
         * Played or picked enough to come first when you type the start of its name: picked from a
         * search twice, played three times, or picked once and played twice.
         */
        val often get() = picks >= 2 || plays >= 3 || (picks >= 1 && plays >= 2)

        /** How well you know it: plays, and picks counting double, worth less the longer ago. */
        val weight: Double = run {
            val days = if (last > 0) (System.currentTimeMillis() - last).coerceAtLeast(0) / 86_400_000.0 else 120.0
            (plays + 2.0 * picks) * (0.4 + 0.6 * exp(-days / 60.0))
        }
    }

    /** An artist whose page you've opened: how often, when last, and the searches you found them with. */
    private class Opened(var artist: ArtistItem, var count: Int, var at: Long, val queries: LinkedHashSet<String>)

    /**
     * An artist you know: their page opened [opens] times (from search or anywhere), [plays] plays
     * of their songs, last one at [last] (ms, 0 when unknown), and the searches you found them
     * with. [artist] has their page's address once you've opened it; from plays alone it's null.
     */
    class KnownArtist(val name: String, val artist: ArtistItem?, val opens: Int, val plays: Int, val last: Long, val queries: List<String>) {
        /** Opened or played enough to come first when you type the start of their name. */
        val often get() = opens >= 2 || plays >= 3 || (opens >= 1 && plays >= 2)

        /** How well you know them: opens count double, plays of their songs half (there are many songs). */
        val weight: Double = run {
            val days = if (last > 0) (System.currentTimeMillis() - last).coerceAtLeast(0) / 86_400_000.0 else 120.0
            (0.5 * plays + 2.0 * opens) * (0.4 + 0.6 * exp(-days / 60.0))
        }
    }

    private const val KEEP = 150

    private var prefs: SharedPreferences? = null
    private val picks = ArrayList<Pick>()
    private val played = HashMap<String, Played>()
    private val opened = LinkedHashMap<String, Opened>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("search_picks", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(prefs?.getString("artists", null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val a = ArtistItem(o.getString("id"), o.optString("t"), o.optString("s"), o.optString("i").ifEmpty { null })
                val qs = o.optJSONArray("q")?.let { q -> (0 until q.length()).map { q.getString(it) } }.orEmpty()
                opened[artistKey(a.title)] = Opened(a, o.optInt("n", 1), o.optLong("at"), LinkedHashSet(qs))
            }
        }
        runCatching {
            val arr = JSONArray(prefs?.getString("picks", null) ?: return)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = Track(o.getString("u"), o.optString("t"), o.optString("a"), o.optLong("s"), o.optString("i").ifEmpty { null })
                picks += Pick(o.getString("q"), t, o.optInt("n", 1), o.optLong("at"))
            }
        }
    }

    /** An artist's name as it's matched: the main artist only ("Burna Boy" in "Burna Boy, Ed Sheeran"). */
    fun artistKey(name: String) = norm(name.substringBefore(",").substringBefore(" & ").substringBefore(" feat"))

    /** You opened [a]'s page. */
    @Synchronized
    fun openedArtist(a: ArtistItem) {
        val k = artistKey(a.title)
        if (k.isEmpty() || a.browseId.isEmpty()) return
        val o = opened[k]
        if (o != null) { o.count++; o.at = System.currentTimeMillis(); o.artist = a }
        else opened[k] = Opened(a, 1, System.currentTimeMillis(), LinkedHashSet())
        saveArtists()
    }

    /** You found [a] by searching [query] (call it before opening their page). */
    @Synchronized
    fun searchedArtist(query: String, a: ArtistItem) {
        val q = norm(query)
        val k = artistKey(a.title)
        if (q.length < 2 || k.isEmpty()) return
        val o = opened.getOrPut(k) { Opened(a, 0, System.currentTimeMillis(), LinkedHashSet()) }
        o.queries.remove(q)
        o.queries.add(q)
        while (o.queries.size > 8) o.queries.remove(o.queries.first())
        saveArtists()
    }

    /** The artists you know, by [artistKey]: pages you've opened, and the artists of songs you've played. */
    fun knownArtists(): Map<String, KnownArtist> {
        val out = HashMap<String, KnownArtist>()
        synchronized(this) {
            val plays = HashMap<String, Pair<Int, Long>>()
            val names = HashMap<String, String>()
            for (p in played.values) {
                val k = artistKey(p.track.artist)
                if (k.isEmpty()) continue
                val had = plays[k]
                plays[k] = ((had?.first ?: 0) + p.count) to maxOf(had?.second ?: 0L, p.at)
                names.putIfAbsent(k, p.track.artist.substringBefore(",").substringBefore(" & ").trim())
            }
            for (k in plays.keys + opened.keys) {
                val o = opened[k]
                val pl = plays[k]
                out[k] = KnownArtist(
                    o?.artist?.title ?: names[k] ?: k, o?.artist?.takeIf { o.count > 0 }, o?.count ?: 0, pl?.first ?: 0,
                    maxOf(o?.at ?: 0L, pl?.second ?: 0L), o?.queries?.toList().orEmpty(),
                )
            }
        }
        return out
    }

    /**
     * Artists you know well whose name (or a search you found them with) starts like [typed]
     * (3 letters at least), best known first. Only ones whose page you've opened (so it can open again).
     */
    fun matchArtists(typed: String, max: Int = 1, known: Map<String, KnownArtist> = knownArtists()): List<ArtistItem> {
        val rank = SearchRank(typed, emptyList(), artists = known)
        return rank.favouriteArtists().mapNotNull { it.artist }.take(max)
    }

    private fun norm(s: String) = SearchRank.norm(s)

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

    /** Every play in the listening stats (the stats hand them over when they're read or change). */
    @Synchronized
    fun usePlays(plays: List<Stats.Play>) {
        played.clear()
        plays.forEach { addPlay(it.track, it.at) }
    }

    /** One more play of [t] at [at] (ms). */
    @Synchronized
    fun addPlay(t: Track, at: Long) {
        if (t.url.isEmpty()) return
        val p = played[t.url]
        if (p == null) played[t.url] = Played(t, 1, at)
        else { p.count++; if (at >= p.at) { p.at = at; p.track = t } }
    }

    /**
     * The songs you know, by address: everything you've played (listening stats and Recently
     * played) or picked from a search. Call it on the main thread (Recently played lives there).
     */
    fun known(recent: List<Track> = runCatching { History.tracks.toList() }.getOrDefault(emptyList())): Map<String, Known> {
        val out = HashMap<String, Known>()
        val inRecent = recent.associateBy { it.url }
        synchronized(this) {
            val pickBy = picks.groupBy { it.track.url }
            val urls = LinkedHashSet<String>().apply { addAll(played.keys); addAll(pickBy.keys); addAll(inRecent.keys) }
            for (u in urls) {
                if (u.isEmpty()) continue
                val pl = played[u]
                val ps = pickBy[u].orEmpty()
                val track = pl?.track ?: ps.firstOrNull()?.track ?: inRecent[u] ?: continue
                val plays = maxOf(pl?.count ?: 0, if (u in inRecent) 1 else 0)
                val last = maxOf(pl?.at ?: 0L, ps.maxOfOrNull { it.at } ?: 0L)
                out[u] = Known(track, plays, ps.sumOf { it.count }, last, ps.map { it.query })
            }
        }
        return out
    }

    /**
     * Songs you know well whose name (or a search you found them with) starts like [typed]
     * (3 letters at least). Best known first, at most [max].
     */
    fun match(typed: String, max: Int = 2, known: Map<String, Known> = known()): List<Track> {
        val rank = SearchRank(typed, emptyList(), known)
        return known.values.filter { rank.favourite(it.track, it) }
            .sortedByDescending { it.weight }
            .distinctBy { SearchRank.key(it.track) }
            .take(max).map { it.track }
    }

    fun clear() {
        synchronized(this) { picks.clear(); opened.clear() }
        prefs?.edit()?.remove("picks")?.remove("artists")?.apply()
    }

    private fun saveArtists() {
        // Keep the most opened and most recent.
        val keep = opened.values.sortedByDescending { it.count * 10_000_000_000L + it.at / 1000 }.take(KEEP)
        val arr = JSONArray()
        keep.forEach { o ->
            arr.put(JSONObject().put("id", o.artist.browseId).put("t", o.artist.title).put("s", o.artist.subtitle)
                .put("i", o.artist.thumb ?: "").put("n", o.count).put("at", o.at).put("q", JSONArray(o.queries.toList())))
        }
        if (keep.size < opened.size) opened.entries.retainAll { e -> keep.any { it === e.value } }
        prefs?.edit()?.putString("artists", arr.toString())?.apply()
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
