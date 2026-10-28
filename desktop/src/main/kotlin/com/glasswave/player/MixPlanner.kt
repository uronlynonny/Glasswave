// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** How one song hands over to the next. */
enum class MixStyle {
    /** Tempos matched, beats lined up, bass swapped halfway: a proper DJ blend. */
    BEATMATCH,
    /** Tempos too far apart to match: the old song ends on a bar and the new one lands on its first beat. */
    BEAT_CUT,
    /** No steady beat: a blend that skips silence and fade-out tails. */
    SMART_FADE,
    /** Nothing known yet: the plain fixed-length blend. */
    PLAIN,
}

/** How loud each song is at one moment of a mix, how much of its bass comes through, and the old song's filter. */
class MixLevels(val oldGain: Float, val newGain: Float, val oldBass: Float, val newBass: Float, val oldSweepHz: Float)

/**
 * One planned transition. The mix starts when the old song reaches [outStartMs]; at that moment the
 * new song starts from [inStartMs] at [speed] (its tempo matched to the old one), and for [lengthMs]
 * the two play together following [levels]. Then the new song glides back to its own tempo.
 */
class MixPlan(
    val style: MixStyle,
    val outStartMs: Long,
    val inStartMs: Long,
    val lengthMs: Long,
    val speed: Float,
    private val oldFirstMs: Double,
    val oldBeatMs: Double,
    private val newFirstMs: Double,
    private val newBeatMs: Double,
    /** Where (0..1 through the mix) the bass changes hands, or where the new song's first beat lands. */
    private val pivot: Float,
    val description: String,
    /** How long after the mix starts the new song comes in (0 = at once). */
    val inDelayMs: Long = 0,
) {
    /** Keeps the two songs' beats lined up while they play together. */
    val locksBeat: Boolean get() = style == MixStyle.BEATMATCH

    fun levels(k: Float): MixLevels {
        val t = k.coerceIn(0f, 1f)
        return when (style) {
            MixStyle.BEATMATCH -> {
                val sw = pivot
                val width = (oldBeatMs / lengthMs).toFloat().coerceIn(0.02f, 0.15f)
                val inUp = ease(t / sw)
                val after = ((t - sw) / (1 - sw)).coerceIn(0f, 1f)
                val newGain = inUp * (0.85f + 0.15f * after)
                val oldGain = if (t < sw) 1f - 0.1f * (t / sw) else 0.9f * cos(after * PI.toFloat() / 2)
                val bass = smooth((t - (sw - width / 2)) / width)
                MixLevels(oldGain, newGain, 1f - bass, bass, 0f)
            }
            MixStyle.BEAT_CUT -> {
                // The old song filters away over its last bar, the new one lands on its first beat at [pivot].
                val hit = pivot
                val inK = inDelayMs.toFloat() / lengthMs
                val newGain = when {
                    t < inK -> 0f
                    hit - inK <= 0.001f -> 1f
                    else -> 0.4f + 0.6f * ease((t - inK) / (hit - inK))
                }.let { if (t >= hit) 1f else it }
                val oldGain = if (t <= hit) 1f - 0.3f * (t / hit) else 0.7f * cos(((t - hit) / (1 - hit)).coerceIn(0f, 1f) * PI.toFloat() / 2)
                val sweep = (30.0 * (1200.0 / 30.0).pow((t / hit).coerceIn(0f, 1f).toDouble())).toFloat()
                MixLevels(oldGain, newGain, if (t >= hit) 0f else 1f, 1f, sweep)
            }
            else -> MixLevels(cos(t * PI.toFloat() / 2), sin(t * PI.toFloat() / 2), 1f, 1f, 0f)
        }
    }

    /** How far the new song's beats are from the old song's, in beats (-0.5..0.5; above 0 means the new song is ahead). */
    fun phaseError(oldPosMs: Double, newPosMs: Double): Double {
        val op = (oldPosMs - oldFirstMs) / oldBeatMs
        val np = (newPosMs - newFirstMs) / newBeatMs
        val e = np - op
        return e - Math.round(e)
    }

    /** How long the new song takes to glide back to its own tempo after the mix. */
    val glideMs: Long get() = if (oldBeatMs > 0 && style == MixStyle.BEATMATCH) (16 * oldBeatMs).toLong() else 0L

    private fun ease(x: Float) = sin(x.coerceIn(0f, 1f) * PI.toFloat() / 2)
    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
}

/** Plans transitions, and judges which songs flow best into each other. */
object MixPlanner {
    /**
     * Plans the change from a song of [oldMs] to the next, from what's known about the old song's
     * ending ([tail]) and the new song's start ([head]); either can be missing. [mixSeconds] is how
     * long you like blends. [canStretch] is false when the tempo can't be touched (a speed you set).
     */
    /**
     * How long a blend should last, from the songs' tempo, the way a DJ counts it in beats:
     * fast songs (118 BPM and up) blend over 32 beats, mid-tempo ones (85 to 118) over 16,
     * slow ones over 8. So 128 BPM house gets about 15 s, a 100 BPM Afrobeats song about
     * 10 s, a slow ballad about 6 s. With no tempo known yet, 8 s.
     */
    fun blendMs(tail: MixProfile?, head: MixProfile?): Long {
        val beat = listOfNotNull(tail, head).firstOrNull { it.steady && it.beatMs > 0 }?.beatMs ?: return 8_000
        val bpm = 60_000 / beat
        val beats = when {
            bpm >= 118 -> 32
            bpm >= 85 -> 16
            else -> 8
        }
        return (beats * beat).toLong().coerceIn(5_000, 16_000)
    }

    fun plan(oldMs: Long, tail: MixProfile?, head: MixProfile?, mixSeconds: Int = 0, canStretch: Boolean = true): MixPlan {
        val want = if (mixSeconds > 0) mixSeconds * 1000L else blendMs(tail, head)
        val plainLen = min(want, oldMs / 5)
        val plain = simple(MixStyle.PLAIN, oldMs - plainLen, 0, plainLen, "Plain blend, ${plainLen / 1000} s")
        if (oldMs < 45_000) return plain
        val end = (tail?.soundEndMs ?: oldMs).coerceIn(oldMs / 2, oldMs)
        val start = (head?.soundStartMs ?: 0L).coerceIn(0L, 20_000L)
        // Never cut away more than the last half of a song.
        val minOut = max(oldMs / 2, tail?.fromMs ?: 0L)

        if (tail != null && head != null && tail.steady && head.steady && tail.bars >= 6 && head.bars >= 2) {
            var oldBeat = tail.beatMs
            var newBeat = head.beatMs
            var oldFirst = tail.firstBeatMs
            var r = newBeat / oldBeat
            // One song may count twice as many beats as the other: line up on the shared pulse.
            if (r > 1.4) { newBeat /= 2; r /= 2 } else if (r < 0.71) { oldBeat /= 2; r *= 2 }
            val keys = keyMatch(tail, head)
            val entry = firstBar(head, start)

            if (canStretch && abs(r - 1) <= MAX_STRETCH && entry != null) {
                var beats = snapBeats(want / oldBeat)
                if (keys < 0) beats = max(8, beats / 2)
                // A quiet intro made for mixing: give it room to come in.
                val quietIntro = head.bars >= 4 && head.barDb.take(4).average() < head.loudDb - 8
                if (quietIntro && keys >= 0 && beats < 16) beats = 16
                while (beats >= 8) {
                    val len = beats * tail.beatMs
                    val exit = pickBar(tail, minOut.toDouble(), min(oldMs.toDouble(), end + 4 * tail.beatMs) - len, end - len - 32 * tail.beatMs)
                    if (exit != null) {
                        val bpmOld = 60_000 / tail.beatMs
                        return MixPlan(
                            MixStyle.BEATMATCH, exit.toLong(), entry.toLong(), len.toLong(), r.toFloat(),
                            oldFirst, oldBeat, head.firstBeatMs, newBeat, 0.5f,
                            "Beatmatched, $beats beats at %.1f BPM (new song %+.1f%%), keys %s→%s".format(bpmOld, (r - 1) * 100, tail.keyName(), head.keyName()),
                        )
                    }
                    beats /= 2
                }
            }
            if (entry != null) {
                // Tempos too far apart: over the old song's last bar it filters away, and the new song's
                // first beat lands right where the old song's next bar would have started.
                val bar = pickBar(tail, minOut + 4 * tail.beatMs, end.toDouble(), end - 16 * tail.beatMs)
                if (bar != null) {
                    val fadeIn = 4 * tail.beatMs
                    val lead = min(2 * head.beatMs, entry)
                    val out = (bar - fadeIn).toLong()
                    val len = (fadeIn + 2 * tail.beatMs).toLong()
                    if (out >= minOut && out + len <= oldMs) {
                        return MixPlan(
                            MixStyle.BEAT_CUT, out, (entry - lead).toLong(), len, 1f,
                            oldFirst, oldBeat, head.firstBeatMs, newBeat, (fadeIn / len).toFloat(),
                            "Beat-timed handover (%.0f → %.0f BPM)".format(60_000 / tail.beatMs, 60_000 / head.beatMs),
                            inDelayMs = (fadeIn - lead).toLong(),
                        )
                    }
                }
            }
        }
        if (tail != null || head != null) {
            val len = min(want, oldMs / 5)
            val out = (end - len / 2).coerceIn(minOut, oldMs - len)
            return simple(MixStyle.SMART_FADE, out, max(0L, start - 30), len, "Smooth blend, ${len / 1000} s, silence skipped")
        }
        return plain
    }

    private fun simple(style: MixStyle, out: Long, inAt: Long, len: Long, text: String) =
        MixPlan(style, out, inAt, len, 1f, 0.0, 0.0, 0.0, 1.0, 0.5f, text)

    /** 8, 16 or 32 beats, whichever is nearest. */
    private fun snapBeats(b: Double): Int {
        val opts = listOf(8, 16, 32)
        return opts.minByOrNull { abs(ln(it / b.coerceAtLeast(1.0))) } ?: 16
    }

    /** The first bar of the song once its sound has started. */
    private fun firstBar(head: MixProfile, soundStart: Long): Double? {
        for (j in 0 until head.bars) {
            val t = head.barMs(j)
            if (t >= soundStart - head.beatMs / 4) return if (t > 30_000) null else t.coerceAtLeast(0.0)
        }
        return null
    }

    /**
     * The best bar to start the mix on, between [lo] and [hi] (ms), not earlier than [notBefore]:
     * where the music changes (a new section, often the outro), and the later the better.
     */
    private fun pickBar(tail: MixProfile, lo: Double, hi: Double, notBefore: Double): Double? {
        val from = max(lo, notBefore)
        val cands = (2 until tail.bars).filter { val t = tail.barMs(it); t >= from && t <= hi }
        if (cands.isEmpty()) return null
        val first = tail.barMs(cands.first())
        val last = tail.barMs(cands.last())
        val span = (last - first).coerceAtLeast(1.0)
        val maxChange = cands.maxOf { tail.barChange[it] }.coerceAtLeast(0.01f)
        val best = cands.maxByOrNull { (tail.barChange[it] / maxChange) + 0.8 * (tail.barMs(it) - first) / span } ?: return null
        return tail.barMs(best)
    }

    /** How well two keys go together: 1 the same, 0.8 next door on the Camelot wheel, below 0 a clash. 0 when unknown. */
    fun keyMatch(a: MixProfile?, b: MixProfile?): Float {
        if (a == null || b == null || !a.keyKnown || !b.keyKnown) return 0f
        val d = abs(a.key - b.key).let { min(it, 12 - it) }
        return if (a.minor == b.minor) when (d) { 0 -> 1f; 1 -> 0.8f; 2 -> 0.2f; else -> -0.5f }
        else when (d) { 0 -> 0.8f; 1 -> 0.3f; else -> -0.5f }
    }

    /** How well [head] (the next song's start) follows [tail] (this song's end): tempo, key and energy. Higher is better. */
    fun compat(tail: MixProfile?, head: MixProfile?): Float {
        if (tail == null || head == null) return 0f
        var s = 0f
        if (tail.steady && head.steady) {
            var r = head.beatMs / tail.beatMs
            if (r > 1.4) r /= 2 else if (r < 0.71) r *= 2
            val d = abs(r - 1)
            s += when { d < 0.02 -> 1f; d < 0.05 -> 0.7f; d <= MAX_STRETCH -> 0.4f; else -> -0.3f }
        }
        s += 0.5f * keyMatch(tail, head)
        if (tail.bars > 0 && head.bars > 0) {
            val endDb = tail.barDb.takeLast(8).average()
            val startDb = head.barDb.take(8).average()
            s -= 0.3f * (abs(endDb - startDb) / 12).toFloat().coerceAtMost(1f)
        }
        return s
    }

    /**
     * Picks which of [candidates] (url to artist, in queue order) should play after the song at
     * [currentUrl]: the best fit by tempo, key and energy, preferring another artist, nudged a little
     * towards songs nearer the front and by a pinch of chance so it never gets predictable.
     */
    fun choose(currentUrl: String, currentArtist: String, candidates: List<Pair<String, String>>, salt: Int): Int {
        if (candidates.isEmpty()) return -1
        val tail = MixLibrary.profile(currentUrl, MixPart.TAIL) ?: MixLibrary.any(currentUrl)
        fun artist(a: String) = a.substringBefore(",").trim().lowercase()
        val now = artist(currentArtist)
        val pool = candidates.indices.filter { artist(candidates[it].second) != now }.ifEmpty { candidates.indices.toList() }
        val n = candidates.size
        return pool.maxByOrNull { i ->
            val url = candidates[i].first
            val head = MixLibrary.profile(url, MixPart.HEAD) ?: MixLibrary.any(url)
            compat(tail, head) + 0.6f * jitter(url, salt) + 0.15f * (n - i).toFloat() / n
        } ?: pool.first()
    }

    /** A steady 0..1 "random" number for a song in one particular moment, so picks don't flicker. */
    private fun jitter(url: String, salt: Int): Float {
        var h = url.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong() + salt * 0xBF58476D1CE4E5B9uL.toLong()
        h = h xor (h ushr 31)
        h *= 0x94D049BB133111EBuL.toLong()
        h = h xor (h ushr 29)
        return ((h ushr 11) % 1_000_000).toFloat() / 1_000_000f
    }

    /**
     * The songs to play next with no internet: from those on the device ([available]), leaving out
     * the ones played lately where it can, a mix of up to [max] that flows on from [current] by tempo
     * and key (not just shuffled).
     */
    fun offlineMix(current: Track?, available: List<Track>, recent: Set<String>, max: Int = 40): List<Track> {
        val pool = available.filter { it.url != current?.url && it.url.isNotEmpty() }.distinctBy { it.url }
        val fresh = pool.filter { it.url !in recent }.ifEmpty { pool }
        val pick = fresh.shuffled().take(max)
        val salt = (System.currentTimeMillis() / 1000).toInt()
        return if (current == null) chain(pick, salt) else chain(listOf(current) + pick, salt).drop(1)
    }

    /** Orders a list so each song flows into the next where tempos and keys are known (Flow uses this within a segment). */
    fun chain(list: List<Track>, salt: Int): List<Track> {
        if (list.size < 3) return list
        val pool = list.toMutableList()
        val out = mutableListOf(pool.removeAt(0))
        while (pool.isNotEmpty()) {
            val last = out.last()
            val i = choose(last.url, last.artist, pool.map { it.url to it.artist }, salt + out.size)
            out += pool.removeAt(if (i < 0) 0 else i)
        }
        return out
    }

    /** The most a song is sped up or slowed down to match the one before (10%). */
    const val MAX_STRETCH = 0.10
}
