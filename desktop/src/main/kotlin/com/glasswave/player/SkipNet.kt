// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * Smart order: a small neural network that guesses how likely you are to listen to a song
 * instead of skipping it, and puts likelier songs earlier when Glasswave picks what to play.
 *
 * - The network (skipnet.json) was trained ahead of time on public listening data. It never
 *   changes on your device and nothing about your listening leaves it.
 * - What makes it personal is its inputs: how you listened to this song and this artist
 *   before, what you like, and how the last few songs went. Those come from listens.jsonl,
 *   a private log of every song you start, including the ones you skip.
 * - It only ever reorders songs Glasswave was already going to offer. With Smart order off,
 *   or before it knows enough about you, the order is random, as it always was.
 */
object SkipNet {

    /** The trained network: scale each input, then layers of weights with ReLU between them. */
    class Net(json: JSONObject) {
        private val mean = doubles(json.getJSONArray("mean"))
        private val std = doubles(json.getJSONArray("std"))
        private val weights = ArrayList<Array<DoubleArray>>()
        private val biases = ArrayList<DoubleArray>()
        private val k = json.getJSONObject("constants")
        val listenRatio = k.getDouble("listen_ratio")
        val p0 = k.getDouble("p0")
        val kUser = k.getDouble("k_user"); val kR5 = k.getDouble("k_r5"); val kR20 = k.getDouble("k_r20")
        val kTrack = k.getDouble("k_track"); val kArtist = k.getDouble("k_artist")
        val sessionGap = k.getLong("session_gap"); val quiet = k.getLong("quiet"); val gapCap = k.getLong("gap_cap"); val sinceCap = k.getLong("since_cap")
        val sessionCap = k.getInt("session_cap"); val streakCap = k.getInt("streak_cap")
        val lenMin = k.getDouble("len_min"); val lenMax = k.getDouble("len_max")
        val inputs = mean.size

        init {
            val layers = json.getJSONArray("layers")
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i)
                val w = l.getJSONArray("w")
                weights += Array(w.length()) { doubles(w.getJSONArray(it)) }
                biases += doubles(l.getJSONArray("b"))
            }
        }

        /** The chance (0..1) that a song with these inputs is listened to. */
        fun chance(x: DoubleArray): Double {
            var h = DoubleArray(inputs) { (x[it] - mean[it]) / std[it] }
            for (layer in weights.indices) {
                val w = weights[layer]
                val b = biases[layer]
                val last = layer == weights.lastIndex
                val out = DoubleArray(w.size)
                for (o in w.indices) {
                    var sum = b[o]
                    val row = w[o]
                    for (i in row.indices) sum += row[i] * h[i]
                    out[o] = if (last) sum else max(sum, 0.0)
                }
                h = out
            }
            return 1.0 / (1.0 + exp(-h[0]))
        }

        private fun doubles(a: org.json.JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
    }

    /**
     * What is remembered about how one person listens, and the network's inputs worked out
     * from it. This copies `UserState` in the training code line for line; `skipCheck`
     * replays a recorded history through it and fails if a single number differs.
     */
    class Listener(private val c: Net) {
        private class Seen(var plays: Int, var listened: Double, var lastAt: Long, var lastOutcome: Double)

        var count = 0; private set
        private var total = 0.0
        private val recent = ArrayDeque<Double>()   // the last 20 outcomes, oldest first
        /** When the last song ended (seconds), or null with no history. */
        var lastAt: Long? = null; private set
        private var lastArtist: String? = null
        private var sessionCount = 0
        private var skipStreak = 0
        private val tracks = HashMap<String, Seen>()
        private val artists = HashMap<String, Seen>()

        private val logGap = ln1p(c.gapCap.toDouble())
        private val logSince = ln1p(c.sinceCap.toDouble())
        private val logSession = ln1p(c.sessionCap.toDouble())

        /**
         * The inputs for a song about to start at [now] (seconds). [lengthSec] 0 = not known.
         * Every "time since" is measured from when the earlier song ENDED to this one starting,
         * and anything under a minute counts as no time at all.
         */
        fun features(track: String, artist: String, now: Long, lengthSec: Long, organic: Boolean, liked: Boolean, artistLikes: Int): DoubleArray {
            val f = DoubleArray(c.inputs)
            val n = count
            val last5 = recent.takeLast(5)
            f[0] = ln1p(n.toDouble())
            f[1] = (total + c.p0 * c.kUser) / (n + c.kUser)
            f[2] = (last5.sum() + c.p0 * c.kR5) / (last5.size + c.kR5)
            f[3] = (recent.sum() + c.p0 * c.kR20) / (recent.size + c.kR20)
            f[4] = if (n >= 1) recent[recent.size - 1] else c.p0
            f[5] = if (n >= 2) recent[recent.size - 2] else c.p0
            f[6] = if (n >= 3) recent[recent.size - 3] else c.p0
            val before = lastAt
            val inSession: Boolean
            if (before == null) {
                f[7] = 1.0
                inSession = false
            } else {
                val gap = max(now - before, 0L)
                f[7] = ln1p(min(if (gap >= c.quiet) gap else 0L, c.gapCap).toDouble()) / logGap
                inSession = gap <= c.sessionGap
            }
            f[8] = ln1p(min(if (inSession) sessionCount else 0, c.sessionCap).toDouble()) / logSession
            f[9] = min(skipStreak, c.streakCap).toDouble() / c.streakCap

            val t = tracks[track]
            if (t == null) {
                f[10] = 0.0; f[11] = c.p0; f[12] = 0.0; f[13] = 1.0; f[14] = c.p0
            } else {
                f[10] = ln1p(t.plays.toDouble())
                f[11] = (t.listened + c.p0 * c.kTrack) / (t.plays + c.kTrack)
                f[12] = 1.0
                val since = max(now - t.lastAt, 0L)
                f[13] = ln1p(min(if (since >= c.quiet) since else 0L, c.sinceCap).toDouble()) / logSince
                f[14] = t.lastOutcome
            }
            val a = artists[artist]
            if (a == null) {
                f[15] = 0.0; f[16] = c.p0; f[17] = 0.0; f[18] = 1.0; f[19] = c.p0; f[20] = 0.0
            } else {
                f[15] = ln1p(a.plays.toDouble())
                f[16] = (a.listened + c.p0 * c.kArtist) / (a.plays + c.kArtist)
                f[17] = 1.0
                val since = max(now - a.lastAt, 0L)
                f[18] = ln1p(min(if (since >= c.quiet) since else 0L, c.sinceCap).toDouble()) / logSince
                f[19] = a.lastOutcome
                f[20] = a.plays.toDouble() / max(n, 1)
            }
            f[21] = if (n >= 1 && artist == lastArtist) 1.0 else 0.0
            f[22] = if (liked) 1.0 else 0.0
            f[23] = ln1p(artistLikes.toDouble())
            if (lengthSec > 0) {
                f[24] = (ln(min(max(lengthSec.toDouble(), c.lenMin), c.lenMax)) - ln(c.lenMin)) / (ln(c.lenMax) - ln(c.lenMin))
                f[25] = 1.0
            } else {
                f[24] = 0.5; f[25] = 0.0
            }
            f[26] = if (organic) 1.0 else 0.0
            return f
        }

        /** Remembers a song that was just listened to or skipped: when it started and ended (seconds). */
        fun addListen(track: String, artist: String, started: Long, ended: Long, listened: Boolean) {
            val y = if (listened) 1.0 else 0.0
            val at = ended
            val before = lastAt
            val inSession = before != null && max(started - before, 0L) <= c.sessionGap
            sessionCount = (if (inSession) sessionCount else 0) + 1
            skipStreak = if (listened) 0 else skipStreak + 1
            count++
            total += y
            recent.addLast(y)
            if (recent.size > 20) recent.removeFirst()
            for ((table, key) in listOf(tracks to track, artists to artist)) {
                val s = table[key]
                if (s == null) table[key] = Seen(1, y, at, y)
                else { s.plays++; s.listened += y; s.lastAt = at; s.lastOutcome = y }
            }
            lastAt = at
            lastArtist = artist
        }
    }

    /** Fewer listens than this and the network knows too little about you to reorder anything. */
    private const val MIN_LISTENS = 20
    /** How strongly the order follows the network. 0 = plain shuffle; large = strict best-first. */
    private const val STRENGTH = 2.0
    /** A song counts once a second of it has played. Shorter than that it never really started. */
    private const val MIN_MS = 1_000L
    /** With the length unknown, a minute of listening counts as listened. */
    private const val LISTENED_MS_UNKNOWN_LENGTH = 60_000L
    private const val MAX_LINES = 20_000

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "skipnet").apply { isDaemon = true } }
    private var file: File? = null
    private var net: Net? = null
    private var me: Listener? = null
    @Volatile private var likedByArtist: Map<String, Int> = emptyMap()
    @Volatile private var likedAt = 0L

    /** True once the network and your history are loaded and there is enough history to use. */
    val ready: Boolean get() = synchronized(lock) { net != null && (me?.count ?: 0) >= MIN_LISTENS }

    fun init(context: Context) {
        val f = File(context.filesDir, "listens.jsonl")
        file = f
        io.execute {
            runCatching {
                val n = loadNet()
                if (!f.exists()) seed(File(context.filesDir, "plays.jsonl"), f)
                val l = Listener(n)
                if (f.exists()) f.forEachLine { line -> runCatching { replay(n, l, JSONObject(line)) } }
                synchronized(lock) { net = n; me = l }
                refreshLikes()
            }.onFailure { android.util.Log.w("Glasswave", "Smart order is off: couldn't load", it) }
        }
    }

    /** The bundled network (the same file on the phone and the computer). */
    fun loadNet(): Net {
        val stream = SkipNet::class.java.classLoader?.getResourceAsStream("skipnet.json") ?: error("skipnet.json is missing")
        return Net(JSONObject(stream.use { it.readBytes().toString(Charsets.UTF_8) }))
    }

    /**
     * Called by the player whenever a song stops being the current one. Unlike the listening
     * stats, this keeps skips too: they are what the network learns your taste from.
     * [pickedByYou]: false when Autoplay or Flow chose the song.
     */
    fun record(t: Track, listenedMs: Long, durationMs: Long, pickedByYou: Boolean) {
        val f = file ?: return
        if (!Settings.keepHistory || t.title.isBlank() || listenedMs < MIN_MS) return
        val o = JSONObject()
            .put("u", t.url).put("t", t.title).put("a", t.artist)
            .put("at", System.currentTimeMillis() - listenedMs).put("ms", listenedMs)
            .put("d", max(durationMs, 0L)).put("o", if (pickedByYou) 1 else 0)
        io.execute {
            runCatching {
                f.appendText(o.toString() + "\n")
                if (f.length() > 4L * 1024 * 1024) f.writeText(f.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
                synchronized(lock) { val n = net; val l = me; if (n != null && l != null) replay(n, l, o) }
            }
        }
    }

    /** Waits until everything recorded so far has been saved and taken in (for checks). */
    fun settle() { io.submit {}.get() }

    /** Forgets everything (Settings › Privacy › clear listening history). */
    fun clear() {
        io.execute {
            runCatching { file?.delete() }
            synchronized(lock) { net?.let { me = Listener(it) } }
        }
    }

    /**
     * [list] in a new order: songs you're likelier to listen to tend to come earlier, with
     * enough chance left in it that the order differs every time and unfamiliar songs still
     * get their turn. A plain shuffle when Smart order is off or not ready.
     */
    fun arrange(list: List<Track>): List<Track> = arrange(list) { it }

    /** The same for any kind of item; [track] gives the song an item stands for, or null. */
    fun <T> arrange(list: List<T>, track: (T) -> Track?): List<T> {
        if (list.size < 2) return list
        val chances = chances(list.map(track)) ?: return list.shuffled()
        val key = DoubleArray(list.size) { i ->
            val p = min(max(chances[i], 0.001), 0.999)
            // Gumbel noise makes this a weighted shuffle: likelier songs usually, not always, first.
            STRENGTH * ln(p / (1 - p)) - ln(-ln(Math.random().coerceIn(1e-12, 1.0 - 1e-12)))
        }
        return list.indices.sortedByDescending { key[it] }.map { list[it] }
    }

    /** Each song's chance of being listened to if Glasswave played it now, or null when not ready. */
    fun chances(songs: List<Track?>): DoubleArray? {
        if (!Settings.smartOrder) return null
        if (System.currentTimeMillis() - likedAt > 10 * 60_000L) io.execute { refreshLikes() }
        val likes = likedByArtist
        synchronized(lock) {
            val n = net ?: return null
            val l = me ?: return null
            if (l.count < MIN_LISTENS) return null
            // While you're in the middle of listening, the next song follows straight on from the last
            // one. After a real break (30 minutes), the time away counts, as it did in training.
            val clock = System.currentTimeMillis() / 1000
            val lastEnd = l.lastAt
            val now = if (lastEnd != null && clock - lastEnd <= n.sessionGap) max(lastEnd, 0L) else clock
            return DoubleArray(songs.size) { i ->
                val t = songs[i] ?: return@DoubleArray 0.5
                val artist = artistKey(t.artist)
                n.chance(l.features(trackKey(t), artist, now, t.seconds, organic = false, liked = Library.isLiked(t.url), artistLikes = likes[artist] ?: 0))
            }
        }
    }

    private fun replay(n: Net, l: Listener, o: JSONObject) {
        val ms = o.optLong("ms")
        val d = o.optLong("d")
        val listened = if (d > 0) ms >= d * n.listenRatio else ms >= LISTENED_MS_UNKNOWN_LENGTH
        val started = o.optLong("at")
        l.addListen(trackKey(o.optString("t"), o.optString("a")), artistKey(o.optString("a")), started / 1000, (started + ms) / 1000, listened)
    }

    /** First run: start from the listening stats you already have, so it isn't a blank slate. */
    private fun seed(plays: File, into: File) {
        if (!plays.exists()) return
        val lines = plays.readLines().mapNotNull { line ->
            runCatching {
                val p = JSONObject(line)
                val ms = p.optLong("ms")
                JSONObject().put("u", p.optString("u")).put("t", p.optString("t")).put("a", p.optString("a"))
                    .put("at", p.getLong("at") - ms).put("ms", ms).put("d", 0).put("o", 1)
            }.getOrNull()
        }.sortedBy { it.getLong("at") }.takeLast(MAX_LINES)
        if (lines.isNotEmpty()) into.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun refreshLikes() {
        likedAt = System.currentTimeMillis()
        runCatching {
            val liked = kotlinx.coroutines.runBlocking { Library.tracks(Library.LIKED_ID) }
            likedByArtist = liked.groupingBy { artistKey(it.artist) }.eachCount()
        }
    }

    /** One key per song, so the album track and its music video count as the same song. */
    private fun trackKey(t: Track) = trackKey(t.title, t.artist)
    private fun trackKey(title: String, artist: String) = YtRepo.songKey(title, artist)

    /** The first artist named, in lower case: "Burna Boy, Dave" and "Burna Boy" are one artist. */
    fun artistKey(artist: String) = artist.lowercase().split(",", "&", " x ", " feat", " ft.", " and ").first().trim()
}
