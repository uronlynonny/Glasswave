// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Listening stats, kept on this phone only. The player logs a play whenever you listen to a song
 * for at least 30 seconds (or most of a short one), with how long you listened. Your top songs and
 * artists for this month, this year or all time are worked out from that log.
 */
object Stats {
    data class Play(val track: Track, val at: Long, val ms: Long)

    data class Ranked(val track: Track, val plays: Int, val ms: Long)
    data class Artist(val name: String, val plays: Int, val ms: Long, val thumb: String?)
    data class Summary(
        val minutes: Long,
        val plays: Int,
        val songs: Int,
        val topSongs: List<Ranked>,
        val topArtists: List<Artist>,
        val history: List<Play>,
        /** Minutes the week before (This week only; -1 otherwise). */
        val previous: Long = -1,
    )

    enum class Period(val label: String) { WEEK("This week"), MONTH("This month"), YEAR("This year"), ALL("All time") }

    /** "↑ 20% more than last week", for This week (null for the others, or with nothing to compare). */
    fun vsLastWeek(s: Summary): String? {
        val before = s.previous
        if (before < 0) return null
        if (before == 0L) return if (s.minutes > 0) "Nothing played last week" else null
        val pct = (s.minutes - before) * 100 / before
        return when {
            pct > 0 -> "↑ $pct% more than last week"
            pct < 0 -> "↓ ${-pct}% less than last week"
            else -> "Same as last week"
        }
    }

    private lateinit var file: File
    private val writer = Executors.newSingleThreadExecutor()

    /** Goes up after each logged play, so open stats refresh. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        file = File(context.filesDir, "plays.jsonl")
    }

    /** Called by the player when a song has been listened to. One short line per play. */
    fun record(t: Track, ms: Long) {
        if (!Settings.keepHistory || t.title.isBlank()) return
        val line = JSONObject()
            .put("u", t.url).put("t", t.title).put("a", t.artist).put("i", t.thumb ?: "")
            .put("at", System.currentTimeMillis()).put("ms", ms)
            .toString()
        writer.execute {
            runCatching {
                file.appendText(line + "\n")
                // Keep the log a sensible size (about the last 30,000 plays).
                if (file.length() > 6L * 1024 * 1024) {
                    val keep = file.readLines().takeLast(30_000)
                    file.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
            Sync.onPlayRecorded() // share it with this account's other devices
        }
    }

    /** Puts corrected song details into the log (see History.repairOnce). */
    fun repairTracks(fixed: Map<String, Track>, drop: Set<String>) {
        if (fixed.isEmpty() && drop.isEmpty()) return
        writer.execute {
            runCatching {
                if (!file.exists()) return@runCatching
                val out = file.readLines().mapNotNull { l ->
                    val o = runCatching { JSONObject(l) }.getOrNull() ?: return@mapNotNull null
                    val u = o.optString("u")
                    if (u in drop) return@mapNotNull null
                    fixed[u]?.let { t -> o.put("t", t.title).put("a", t.artist).put("i", t.thumb ?: "") }
                    o.toString()
                }
                file.writeText(out.joinToString("\n", postfix = "\n"))
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
        }
    }

    fun clear() {
        writer.execute { runCatching { file.delete(); Sync.syncedPlaysFile()?.delete() } }
        version++
    }

    /** Refresh open stats (plays arrived from another device). */
    fun bump() { android.os.Handler(android.os.Looper.getMainLooper()).post { version++ } }

    /** This device's own plays after [after] (ms), oldest first: what gets shared with other devices. */
    fun ownPlaysAfter(after: Long): List<Play> = readFile(file).filter { it.at > after }.sortedBy { it.at }

    /** This device's plays, plus (when signed in to YouTube Music) those from the account's other devices. */
    private fun readAll(): List<Play> = readFile(file) + (Sync.syncedPlaysFile()?.let { readFile(it) } ?: emptyList())

    private fun readFile(file: File): List<Play> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { l ->
            runCatching {
                val o = JSONObject(l)
                Play(
                    Track(o.getString("u"), o.optString("t"), o.optString("a"), 0, o.optString("i").ifEmpty { null }),
                    o.getLong("at"), o.optLong("ms"),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun startOf(p: Period): Long {
        if (p == Period.ALL) return 0L
        val c = Calendar.getInstance()
        when (p) {
            // Weeks start on Monday.
            Period.WEEK -> c.add(Calendar.DAY_OF_YEAR, -((c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
            Period.YEAR -> { c.set(Calendar.MONTH, Calendar.JANUARY); c.set(Calendar.DAY_OF_MONTH, 1) }
            else -> c.set(Calendar.DAY_OF_MONTH, 1)
        }
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    suspend fun summary(p: Period): Summary = withContext(Dispatchers.IO) {
        val from = startOf(p)
        val everything = readAll()
        val plays = everything.filter { it.at >= from }
        // This week: last week's minutes too, to compare.
        val previous = if (p != Period.WEEK) -1L else {
            val weekBefore = from - 7L * 24 * 60 * 60 * 1000
            everything.filter { it.at in weekBefore until from }.sumOf { it.ms } / 60_000
        }
        val bySong = plays.groupBy { it.track.url }.map { (_, list) ->
            val newest = list.maxBy { it.at }
            Ranked(newest.track, list.size, list.sumOf { it.ms })
        }.sortedWith(compareByDescending<Ranked> { it.plays }.thenByDescending { it.ms })
        val byArtist = plays.groupBy { it.track.artist.substringBefore(",").substringBefore(" & ").trim() }
            .filterKeys { it.isNotBlank() }
            .map { (name, list) ->
                val topSong = list.groupBy { it.track.url }.maxBy { it.value.size }.value.first().track
                Artist(name, list.size, list.sumOf { it.ms }, topSong.thumb)
            }
            .sortedWith(compareByDescending<Artist> { it.plays }.thenByDescending { it.ms })
        Summary(
            minutes = plays.sumOf { it.ms } / 60_000,
            plays = plays.size,
            songs = bySong.size,
            topSongs = bySong.take(50),
            topArtists = byArtist.take(20),
            history = plays.sortedByDescending { it.at }.take(300),
            previous = previous,
        )
    }
}
