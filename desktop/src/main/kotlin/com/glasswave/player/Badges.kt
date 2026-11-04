// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Badges, given out on the 1st of each month from what you listened to the month before:
 *  - a fan level for each artist you played a lot (Fan, Super fan, Top fan, Legend),
 *  - Top listener for your most played artist of the month,
 *  - milestones: a listening streak (days in a row) and hours listened.
 * Worked out from your own listening stats (this device, plus your other devices when signed in);
 * there's no server, so nothing here compares you with other people.
 */
object Badges {
    enum class Level(val title: String, val plays: Int, val minutes: Int, val color: Long, val about: String) {
        FAN("Fan", 10, 30, 0xFF64D2FF, "10 plays or 30 minutes of an artist in a month"),
        SUPER_FAN("Super fan", 30, 120, 0xFFFF8A65, "30 plays or 2 hours of an artist in a month"),
        TOP_FAN("Top fan", 75, 300, 0xFFC6F432, "75 plays or 5 hours of an artist in a month"),
        LEGEND("Legend", 150, 600, 0xFFC9A7FF, "150 plays or 10 hours of an artist in a month");

        companion object {
            fun of(plays: Int, minutes: Long): Level? = entries.lastOrNull { plays >= it.plays || minutes >= it.minutes }
        }
    }

    data class ArtistBadge(val artist: String, val level: Level, val topListener: Boolean, val plays: Int, val minutes: Long, val thumb: String?)

    /** One month's badges. [streak]: most days in a row you listened (0 below 7). [hours]: hours tier reached (0 below 10). */
    data class Month(val key: String, val artists: List<ArtistBadge>, val streak: Int, val hours: Int) {
        val count get() = artists.size + (if (streak > 0) 1 else 0) + (if (hours > 0) 1 else 0)
    }

    const val MAX_ARTISTS = 10
    val STREAKS = listOf(7, 14, 21)
    val HOURS = listOf(10, 25, 50, 100)

    /** Every month with badges, newest first. */
    var months by mutableStateOf<List<Month>>(emptyList())
        private set

    /** A month whose badges arrived and haven't been looked at yet (Home shows a card for it). */
    var unseen by mutableStateOf<String?>(null)
        private set

    private var last: String? = null
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "badges.json")
        load()
    }

    /* ---------------- Months ---------------- */

    private fun key(c: Calendar) = "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)

    private fun startOf(key: String): Calendar = Calendar.getInstance().apply {
        clear()
        set(key.substring(0, 4).toInt(), key.substring(5, 7).toInt() - 1, 1)
    }

    private fun thisMonthStart(): Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** "October 2026". */
    fun label(key: String): String {
        val c = startOf(key)
        return java.text.DateFormatSymbols.getInstance().months[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR)
    }

    /** "1 December": when this month's badges arrive. */
    fun nextAwardDay(): String {
        val c = thisMonthStart().apply { add(Calendar.MONTH, 1) }
        return "1 " + java.text.DateFormatSymbols.getInstance().months[c.get(Calendar.MONTH)]
    }

    fun artistOf(credit: String) = credit.substringBefore(",").substringBefore(" & ").trim()

    /* ---------------- Giving them out ---------------- */

    /** Gives out the badges for any month that ended since last time. Cheap when there's nothing to do. */
    suspend fun awardIfDue() = withContext(Dispatchers.IO) {
        if (file == null) return@withContext
        val prev = thisMonthStart().apply { add(Calendar.MONTH, -1) }
        val prevKey = key(prev)
        val done = last
        if (done != null && done >= prevKey) return@withContext
        val plays = Stats.allPlays()
        if (plays.isEmpty()) {
            last = prevKey
            save()
            return@withContext
        }
        // First time: just last month. After that, every month since the last one given out (up to a year).
        val cur = if (done == null) prev.clone() as Calendar else startOf(done).apply { add(Calendar.MONTH, 1) }
        val earliest = (prev.clone() as Calendar).apply { add(Calendar.MONTH, -11) }
        if (cur.before(earliest)) cur.timeInMillis = earliest.timeInMillis
        val fresh = mutableListOf<Month>()
        while (!cur.after(prev)) {
            val from = cur.timeInMillis
            val to = (cur.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
            val m = compute(key(cur), from, to, plays)
            if (m.count > 0) fresh += m
            cur.add(Calendar.MONTH, 1)
        }
        val all = (fresh.reversed() + months).distinctBy { it.key }.sortedByDescending { it.key }
        val newest = fresh.maxByOrNull { it.key }?.key
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            months = all
            if (newest != null) unseen = newest
        }
        last = prevKey
        save(all, newest ?: unseen)
    }

    private fun compute(key: String, from: Long, to: Long, all: List<Stats.Play>): Month {
        val plays = all.filter { it.at in from until to }
        val ranked = plays.groupBy { artistOf(it.track.artist) }
            .filterKeys { it.isNotBlank() }
            .map { (name, list) -> Triple(name, list, list.sumOf { it.ms } / 60_000) }
            .sortedWith(compareByDescending<Triple<String, List<Stats.Play>, Long>> { it.second.size }.thenByDescending { it.third })
        // Fan badges for your top 10 artists at most, so they stay special.
        val artists = ranked.take(MAX_ARTISTS).mapIndexedNotNull { i, (name, list, minutes) ->
            val level = Level.of(list.size, minutes) ?: return@mapIndexedNotNull null
            val thumb = list.groupBy { it.track.url }.maxBy { it.value.size }.value.first().track.thumb
            ArtistBadge(name, level, topListener = i == 0, plays = list.size, minutes = minutes, thumb = thumb)
        }
        // Longest run of days in a row with at least one play.
        val cal = Calendar.getInstance()
        val days = plays.map { cal.timeInMillis = it.at; cal.get(Calendar.DAY_OF_MONTH) }.toSortedSet()
        var best = 0
        var run = 0
        var prevDay = -10
        for (d in days) {
            run = if (d == prevDay + 1) run + 1 else 1
            best = maxOf(best, run)
            prevDay = d
        }
        val hours = (plays.sumOf { it.ms } / 3_600_000).toInt()
        return Month(
            key,
            artists,
            streak = STREAKS.lastOrNull { best >= it }?.let { if (best > 21) best else it } ?: 0,
            hours = HOURS.lastOrNull { hours >= it } ?: 0,
        )
    }

    /** Plays and minutes of [artist] so far this month: how it's going toward the badge on the 1st. */
    suspend fun thisMonth(artist: String): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val name = artistOf(artist)
        val from = thisMonthStart().timeInMillis
        val plays = Stats.allPlays().filter { it.at >= from && artistOf(it.track.artist).equals(name, ignoreCase = true) }
        plays.size to plays.sumOf { it.ms } / 60_000
    }

    /** The newest badge you have for [artist] (and which month), or null. */
    fun forArtist(artist: String): Pair<String, ArtistBadge>? {
        val name = artistOf(artist)
        for (m in months) m.artists.firstOrNull { it.artist.equals(name, ignoreCase = true) }?.let { return m.key to it }
        return null
    }

    fun markSeen() {
        if (unseen == null) return
        unseen = null
        Thread { save(months, null) }.start()
    }

    /* ---------------- Saving ---------------- */

    private fun load() {
        val f = file ?: return
        runCatching {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            last = o.optString("last").ifEmpty { null }
            unseen = o.optString("unseen").ifEmpty { null }
            val arr = o.optJSONArray("months") ?: JSONArray()
            months = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val a = m.optJSONArray("a") ?: JSONArray()
                Month(
                    m.getString("m"),
                    (0 until a.length()).mapNotNull { j ->
                        val b = a.optJSONObject(j) ?: return@mapNotNull null
                        val level = runCatching { Level.valueOf(b.getString("l")) }.getOrNull() ?: return@mapNotNull null
                        ArtistBadge(b.getString("n"), level, b.optBoolean("t"), b.optInt("p"), b.optLong("min"), b.optString("i").ifEmpty { null })
                    },
                    m.optInt("s"),
                    m.optInt("h"),
                )
            }.sortedByDescending { it.key }
        }
    }

    private fun save(list: List<Month> = months, unseenKey: String? = unseen) {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            list.forEach { m ->
                val a = JSONArray()
                m.artists.forEach { b ->
                    a.put(JSONObject().put("n", b.artist).put("l", b.level.name).put("t", b.topListener).put("p", b.plays).put("min", b.minutes).put("i", b.thumb ?: ""))
                }
                arr.put(JSONObject().put("m", m.key).put("a", a).put("s", m.streak).put("h", m.hours))
            }
            val o = JSONObject().put("last", last ?: "").put("unseen", unseenKey ?: "").put("months", arr)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
