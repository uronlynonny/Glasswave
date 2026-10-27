// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Decoded mono audio from part of a song. Sample 0 sits [startMs] into the song. */
class Pcm(val samples: FloatArray, val rate: Int, val startMs: Double, val songMs: Long)

/** The two ends of a song Automix listens to: how it starts, and how it ends. */
enum class MixPart { HEAD, TAIL }

/**
 * What Automix knows about one end of a song (its first minute, or its last minute and a half),
 * found by listening to it: the tempo and where every beat falls, where bars start, how loud each
 * bar is and where the music changes, where the sound really starts and ends, and the key.
 * All times are in ms from the start of the song.
 */
class MixProfile(
    val fromMs: Long,
    val toMs: Long,
    val songMs: Long,
    /** Beats per minute, folded into 80..160. 0 when there's no beat to find. */
    val bpm: Float,
    /** 0..1: how sure we are the beat is steady enough to mix on. */
    val confidence: Float,
    /** A beat on the grid (the first one in this part of the song) and the time between beats. */
    val firstBeatMs: Double,
    val beatMs: Double,
    /** Which beat (0..3, counted from [firstBeatMs]) starts a bar. */
    val downbeat: Int,
    /** Loudness of each bar (dB), and how much each bar differs from the ones just before it. */
    val barDb: FloatArray,
    val barChange: FloatArray,
    /** Where the sound starts (after any silence) and where the song is effectively over. */
    val soundStartMs: Long,
    val soundEndMs: Long,
    /** How loud the loud parts are (dB). */
    val loudDb: Float,
    /** Camelot key number (1..12, 0 = unknown) and whether it's minor ("A") or major ("B"). */
    val key: Int,
    val minor: Boolean,
    val keyStrength: Float,
) {
    val steady: Boolean get() = bpm > 0f && confidence >= STEADY
    val bars: Int get() = barDb.size
    /** Where bar [j] starts. */
    fun barMs(j: Int): Double = firstBeatMs + (downbeat + 4.0 * j) * beatMs
    val keyKnown: Boolean get() = key > 0 && keyStrength >= KEY_MIN
    fun keyName(): String = if (key == 0) "?" else "$key${if (minor) "A" else "B"}"

    fun toJson(): JSONObject = JSONObject()
        .put("v", MixAnalysis.VERSION)
        .put("from", fromMs).put("to", toMs).put("song", songMs)
        .put("bpm", bpm.toDouble()).put("conf", confidence.toDouble())
        .put("first", firstBeatMs).put("beat", beatMs).put("down", downbeat)
        .put("barDb", JSONArray(barDb.map { (it * 10).roundToInt() / 10.0 }))
        .put("barChange", JSONArray(barChange.map { (it * 100).roundToInt() / 100.0 }))
        .put("start", soundStartMs).put("end", soundEndMs).put("loud", loudDb.toDouble())
        .put("key", key).put("minor", minor).put("keyStrength", keyStrength.toDouble())

    companion object {
        const val STEADY = 0.5f
        const val KEY_MIN = 0.65f

        fun fromJson(j: JSONObject): MixProfile? {
            if (j.optInt("v") != MixAnalysis.VERSION) return null
            fun floats(name: String): FloatArray {
                val a = j.optJSONArray(name) ?: return FloatArray(0)
                return FloatArray(a.length()) { a.optDouble(it).toFloat() }
            }
            return MixProfile(
                j.optLong("from"), j.optLong("to"), j.optLong("song"),
                j.optDouble("bpm").toFloat(), j.optDouble("conf").toFloat(),
                j.optDouble("first"), j.optDouble("beat"), j.optInt("down"),
                floats("barDb"), floats("barChange"),
                j.optLong("start"), j.optLong("end"), j.optDouble("loud").toFloat(),
                j.optInt("key"), j.optBoolean("minor"), j.optDouble("keyStrength").toFloat(),
            )
        }
    }
}

/** A small radix-2 FFT, in place. */
internal class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun run(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val c = cosT[k]; val s = sinT[k]
                    val l = j + half
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}

/** Listens to part of a song and works out its [MixProfile]. Pure maths, no I/O. */
object MixAnalysis {
    /** Bump when the analysis changes, so songs are listened to again. */
    const val VERSION = 1

    /** Last run's raw numbers, for the checks. */
    @Volatile var lastDebug = ""

    private fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

    fun analyse(pcm: Pcm): MixProfile {
        val x = pcm.samples
        val sr = pcm.rate
        val n = if (sr <= 24_000) 1024 else 2048
        val hop = n / 4
        val frames = if (x.size < n) 0 else (x.size - n) / hop + 1
        val fps = sr.toDouble() / hop
        val fromMs = pcm.startMs.toLong()
        val toMs = (pcm.startMs + x.size * 1000.0 / sr).toLong()
        /** Song time of a frame's onset (ms). */
        fun frameMs(f: Double) = pcm.startMs + (f * hop + n / 2.0 - hop / 2.0) * 1000.0 / sr
        if (frames < fps * 10) return empty(fromMs, toMs, pcm.songMs)

        // 1. Spectrum: how much new sound arrives in each frame (all of it, and just the bass), and loudness.
        val win = hann(n)
        val fft = Fft(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val bins = n / 2
        val maxBin = min(bins, (8000.0 * n / sr).toInt())
        val lowBin = max(2, (150.0 * n / sr).roundToInt())
        var prev = DoubleArray(bins)
        var cur = DoubleArray(bins)
        val flux = DoubleArray(frames)
        val low = DoubleArray(frames)
        val db = FloatArray(frames)
        val norm = n / 4.0
        for (f in 0 until frames) {
            val o = f * hop
            var e = 0.0
            for (i in 0 until n) {
                val s = x[o + i].toDouble()
                e += s * s
                re[i] = s * win[i]
                im[i] = 0.0
            }
            db[f] = (10 * log10(e / n + 1e-12)).toFloat()
            fft.run(re, im)
            var fl = 0.0
            var lo = 0.0
            for (k in 1 until maxBin) {
                val m = ln(1.0 + 100.0 * sqrt(re[k] * re[k] + im[k] * im[k]) / norm)
                cur[k] = m
                val d = m - prev[k]
                if (d > 0) {
                    fl += d
                    if (k <= lowBin) lo += d
                }
            }
            if (f > 0) { flux[f] = fl; low[f] = lo }
            val t = prev; prev = cur; cur = t
        }

        // 2. Onset strength: new sound above the local average.
        val onset = localPeaks(flux, (fps * 0.25).roundToInt())
        val lowOn = localPeaks(low, (fps * 0.25).roundToInt())
        normalise(onset)
        normalise(lowOn)
        val onsetMean = onset.average().coerceAtLeast(1e-9)

        // 3. Tempo: the beat length that best explains how the onsets repeat.
        val maxLag = (60 * fps / 70 * 4).toInt() + 2
        val acf = DoubleArray(maxLag + 2)
        for (l in 1..maxLag) {
            var s = 0.0
            for (t in 0 until frames - l) s += onset[t] * onset[t + l]
            acf[l] = s / (frames - l)
        }
        fun ac(l: Double): Double {
            val i = l.toInt()
            if (i + 1 > maxLag || i < 1) return 0.0
            val fr = l - i
            return acf[i] * (1 - fr) + acf[i + 1] * fr
        }
        var bestScore = 0.0
        var bestBpm = 0.0
        var scoreSum = 0.0
        var scoreCount = 0
        var b = 70.0
        while (b <= 180.0) {
            val lag = 60 * fps / b
            var s = ac(lag) + ac(2 * lag) / 2 + ac(3 * lag) / 3 + ac(4 * lag) / 4
            val oct = ln(b / 120) / ln(2.0)
            s *= 0.7 + 0.3 * exp(-0.5 * oct * oct)
            scoreSum += s
            scoreCount++
            if (s > bestScore) { bestScore = s; bestBpm = b }
            b += 0.05
        }
        val clarity = if (scoreSum > 0) bestScore / (scoreSum / scoreCount) else 0.0
        var bpm = bestBpm
        while (bpm > 0 && bpm < 80) bpm *= 2
        while (bpm >= 160) bpm /= 2
        if (bpm <= 0) return empty(fromMs, toMs, pcm.songMs)

        // 4. Beats: the best path through the onsets at that tempo, then a straight grid through them.
        val period = 60 * fps / bpm
        val beats = trackBeats(onset, period)
        val grid = fitGrid(beats, period) ?: return empty(fromMs, toMs, pcm.songMs)
        var g0 = grid.first
        val p = grid.second
        while (g0 - p >= 0) g0 -= p
        while (g0 < 0) g0 += p
        val count = ((frames - 1 - g0) / p).toInt() + 1
        val residMs = grid.third / fps * 1000

        // How strongly the onsets sit on the grid, compared with everywhere else.
        var hit = 0.0
        for (k in 0 until count) {
            val c = (g0 + k * p).roundToInt()
            var m = 0.0
            for (d in -1..1) m = max(m, onset.getOrElse(c + d) { 0.0 })
            hit += m
        }
        val hitRatio = hit / count / onsetMean
        val confidence = (((hitRatio - 1.6) / 1.4).coerceIn(0.0, 1.0) *
            (1 - (residMs - 10) / 30).coerceIn(0.0, 1.0) *
            ((clarity - 1.05) / 0.35).coerceIn(0.0, 1.0)).toFloat()

        // 5. Chroma (how much of each of the 12 notes), for bars and the key.
        val chroma = chroma(x, sr)
        val chromaHop = 4096.0 * 1000 / sr
        fun chromaAt(ms0: Double, ms1: Double): DoubleArray {
            val out = DoubleArray(12)
            val a = ((ms0 - pcm.startMs) / chromaHop).toInt().coerceAtLeast(0)
            val z = ((ms1 - pcm.startMs) / chromaHop).toInt().coerceAtMost(chroma.size - 1)
            for (c in a..z) for (i in 0 until 12) out[i] += chroma[c][i]
            return out
        }

        // 6. Per beat: loudness, bass hit, and how much the harmony changes. Then the downbeat.
        val beatDb = FloatArray(count)
        val beatLow = DoubleArray(count)
        val beatChroma = Array(count) { DoubleArray(12) }
        for (k in 0 until count) {
            val a = (g0 + k * p).roundToInt()
            val z = min(frames, (g0 + (k + 1) * p).roundToInt())
            var s = 0.0
            var c = 0
            for (f in a until z) { s += db[f]; c++ }
            beatDb[k] = if (c > 0) (s / c).toFloat() else -90f
            var m = 0.0
            for (d in -1..1) m = max(m, lowOn.getOrElse(a + d) { 0.0 })
            beatLow[k] = m
            beatChroma[k] = chromaAt(frameMs(a.toDouble()), frameMs(z.toDouble()))
        }
        val downScore = DoubleArray(4)
        for (k in 1 until count) {
            val change = 1 - cosine(beatChroma[k - 1], beatChroma[k])
            downScore[k % 4] += 2 * change + 0.5 * beatLow[k]
        }
        val downbeat = downScore.indices.maxByOrNull { downScore[it] } ?: 0

        // 7. Bars: loudness and how different each one is from the bars before (where sections change).
        val bars = max(0, (count - downbeat) / 4)
        val barDb = FloatArray(bars)
        val barChroma = Array(bars) { DoubleArray(12) }
        for (j in 0 until bars) {
            var s = 0f
            for (q in 0 until 4) {
                val k = downbeat + 4 * j + q
                s += beatDb[k]
                for (i in 0 until 12) barChroma[j][i] += beatChroma[k][i]
            }
            barDb[j] = s / 4
        }
        val barChange = FloatArray(bars)
        for (j in 2 until bars) {
            val after = if (j + 1 < bars) (barDb[j] + barDb[j + 1]) / 2 else barDb[j]
            val before = (barDb[j - 1] + barDb[j - 2]) / 2
            val ca = DoubleArray(12) { barChroma[j][it] + (if (j + 1 < bars) barChroma[j + 1][it] else 0.0) }
            val cb = DoubleArray(12) { barChroma[j - 1][it] + barChroma[j - 2][it] }
            barChange[j] = (abs(after - before) / 6 + (1 - cosine(ca, cb)) * 3).toFloat()
        }

        // 8. Where the sound starts and where the song is effectively over.
        val sorted = db.filter { it > -70f }.sorted()
        val loud = if (sorted.isEmpty()) -20f else sorted[((sorted.size - 1) * 0.95).toInt()]
        val shortWin = max(1, (fps * 0.2).roundToInt())
        val longWin = max(1, (fps * 1.0).roundToInt())
        var start = 0
        run {
            var s = 0.0
            for (f in 0 until frames) {
                s += db[f]
                if (f >= shortWin) s -= db[f - shortWin]
                if (f >= shortWin - 1 && s / shortWin > loud - 30) { start = f - shortWin + 1; return@run }
            }
        }
        var end = frames - 1
        run {
            var s = 0.0
            for (f in frames - 1 downTo 0) {
                s += db[f]
                if (f + longWin < frames) s -= db[f + longWin]
                if (frames - f >= longWin && s / longWin > loud - 18) { end = f + longWin; return@run }
            }
        }

        // 9. Key: the notes played most, against the classic major and minor key profiles.
        val total = DoubleArray(12)
        for (c in chroma.indices) {
            val f = ((c * 4096.0 + 2048) / hop).toInt().coerceIn(0, frames - 1)
            if (db[f] > loud - 20) for (i in 0 until 12) total[i] += chroma[c][i]
        }
        var keyR = -1.0
        var keyPc = 0
        var keyMinor = false
        for (pc in 0 until 12) for (minor in listOf(false, true)) {
            val prof = if (minor) MINOR else MAJOR
            val r = pearson(DoubleArray(12) { total[(it + pc) % 12] }, prof)
            if (r > keyR) { keyR = r; keyPc = pc; keyMinor = minor }
        }
        val camelot = if (total.sum() <= 0) 0 else camelot(keyPc, keyMinor)

        lastDebug = "bpm %.2f clarity %.2f hits %.2f resid %.1fms conf %.2f key %d%s r=%.2f".format(
            60000 / (p / fps * 1000), clarity, hitRatio, residMs, confidence, camelot, if (keyMinor) "A" else "B", keyR,
        )

        val beatMs = p / fps * 1000
        return MixProfile(
            fromMs, toMs, pcm.songMs,
            (60_000 / beatMs).toFloat(), confidence,
            frameMs(g0), beatMs, downbeat,
            barDb, barChange,
            frameMs(start.toDouble()).toLong().coerceAtLeast(0), frameMs(end.toDouble()).toLong().coerceAtMost(pcm.songMs),
            loud, camelot, keyMinor, keyR.toFloat(),
        )
    }

    private fun empty(from: Long, to: Long, song: Long) =
        MixProfile(from, to, song, 0f, 0f, from.toDouble(), 500.0, 0, FloatArray(0), FloatArray(0), from, to, -20f, 0, false, 0f)

    /** Rectified difference from a moving average of ±[half] frames. */
    private fun localPeaks(v: DoubleArray, half: Int): DoubleArray {
        val pre = DoubleArray(v.size + 1)
        for (i in v.indices) pre[i + 1] = pre[i] + v[i]
        return DoubleArray(v.size) { i ->
            val a = max(0, i - half)
            val z = min(v.size, i + half + 1)
            max(0.0, v[i] - (pre[z] - pre[a]) / (z - a))
        }
    }

    private fun normalise(v: DoubleArray) {
        val m = v.average()
        var s = 0.0
        for (x in v) s += (x - m) * (x - m)
        val sd = sqrt(s / v.size).coerceAtLeast(1e-9)
        for (i in v.indices) v[i] /= sd
    }

    /** Dynamic-programming beat tracker: onsets reward a beat, uneven spacing costs. Returns beat frames (fractional). */
    private fun trackBeats(onset: DoubleArray, period: Double): List<Double> {
        val n = onset.size
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        val lo = max(1, (period / 2).roundToInt())
        val hi = (period * 2).roundToInt()
        for (t in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var bi = -1
            for (tau in max(0, t - hi)..(t - lo)) {
                val r = ln((t - tau) / period)
                val v = score[tau] - TIGHTNESS * r * r
                if (v > best) { best = v; bi = tau }
            }
            if (bi >= 0 && best > 0) {
                score[t] = onset[t] + best
                back[t] = bi
            } else score[t] = onset[t]
        }
        var t = (max(0, n - period.roundToInt()) until n).maxByOrNull { score[it] } ?: return emptyList()
        val out = ArrayList<Double>()
        while (t >= 0) {
            out += refine(onset, t)
            t = back[t]
        }
        out.reverse()
        return out
    }

    /** Moves a beat to the nearby onset peak, between frames. */
    private fun refine(onset: DoubleArray, t: Int): Double {
        var c = t
        for (d in -2..2) if (onset.getOrElse(t + d) { -1.0 } > onset[c]) c = t + d
        if (c <= 0 || c >= onset.size - 1) return c.toDouble()
        val a = onset[c - 1]
        val m = onset[c]
        val z = onset[c + 1]
        val den = a - 2 * m + z
        return if (abs(den) < 1e-12) c.toDouble() else c + (0.5 * (a - z) / den).coerceIn(-0.5, 0.5)
    }

    /** A straight beat grid through the tracked beats: (first beat frame, period, rms error in frames). */
    private fun fitGrid(beats: List<Double>, period: Double): Triple<Double, Double, Double>? {
        if (beats.size < 8) return null
        val idx = beats.map { ((it - beats[0]) / period).roundToInt().toDouble() }
        var use = beats.indices.toList()
        var a = beats[0]
        var p = period
        repeat(4) {
            if (use.size < 8) return null
            val mi = use.map { idx[it] }.average()
            val mt = use.map { beats[it] }.average()
            var sxy = 0.0
            var sxx = 0.0
            for (i in use) { sxy += (idx[i] - mi) * (beats[i] - mt); sxx += (idx[i] - mi) * (idx[i] - mi) }
            if (sxx <= 0) return null
            p = sxy / sxx
            a = mt - p * mi
            use = beats.indices.filter { abs(beats[it] - (a + p * idx[it])) < 0.12 * p }
        }
        if (use.size < 8 || abs(p / period - 1) > 0.05) return null
        var r = 0.0
        for (i in use) { val d = beats[i] - (a + p * idx[i]); r += d * d }
        return Triple(a, p, sqrt(r / use.size))
    }

    /** 12-note chroma, one frame per 4096 samples, each frame summing to 1 (or 0 when silent). */
    private fun chroma(x: FloatArray, sr: Int): Array<DoubleArray> {
        val n = 4096
        val count = x.size / n
        val win = hann(n)
        val fft = Fft(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val pcOf = IntArray(n / 2) { k ->
            val f = k.toDouble() * sr / n
            if (f < 110 || f > 2100) -1 else (((12 * ln(f / 440) / ln(2.0)).roundToInt() + 9) % 12 + 12) % 12
        }
        return Array(count) { c ->
            for (i in 0 until n) { re[i] = x[c * n + i] * win[i]; im[i] = 0.0 }
            fft.run(re, im)
            val out = DoubleArray(12)
            for (k in 1 until n / 2) {
                val pc = pcOf[k]
                if (pc >= 0) out[pc] += sqrt(re[k] * re[k] + im[k] * im[k])
            }
            val s = out.sum()
            if (s > 1e-6) for (i in 0 until 12) out[i] /= s else out.fill(0.0)
            out
        }
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa <= 0 || bb <= 0) 1.0 else ab / sqrt(aa * bb)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb) }
        return if (aa <= 0 || bb <= 0) 0.0 else ab / sqrt(aa * bb)
    }

    /** Camelot wheel number for a key (pitch class 0 = C). */
    fun camelot(pc: Int, minor: Boolean): Int {
        val major = if (minor) (pc + 3) % 12 else pc
        return (7 * major % 12 + 7) % 12 + 1
    }

    private const val TIGHTNESS = 100.0
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
}

/** Decodes part of a song for listening to. Each app has its own (FFmpeg on PC, MediaCodec on the phone). */
fun interface MixDecoder {
    /** Mono samples of the song's [part], or null if it can't be read right now. Blocking. */
    fun decode(url: String, part: MixPart): Pcm?
}

/**
 * Remembers what Automix has learned about each song (one small file per song end, so each song is
 * only ever listened to once), and listens to the songs coming up, one at a time in the background.
 */
object MixLibrary {
    /** How much of each end of a song is listened to. */
    const val HEAD_MS = 60_000L
    const val TAIL_MS = 90_000L

    @Volatile var decoder: MixDecoder? = null
    /** Told (on the listening thread) whenever a song end has been worked out. */
    val listeners: MutableList<(String) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    private var dir: File? = null
    private val known = ConcurrentHashMap<String, MixProfile>()
    /** Song ends that couldn't be read, and when; tried again after a while (the internet may be back). */
    private val missing = ConcurrentHashMap<String, Long>()
    private fun gaveUp(k: String): Boolean = (missing[k] ?: 0L) > System.currentTimeMillis() - 10 * 60_000
    private val queue = LinkedHashSet<Pair<String, MixPart>>()
    private val lock = Object()
    private var thread: Thread? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "mix").apply { mkdirs() }
    }

    private fun key(url: String, part: MixPart) = "${part.name[0]}|$url"

    /** What's known about this end of the song right now (never blocks), or null. */
    fun profile(url: String, part: MixPart): MixProfile? = known[key(url, part)]

    /** Remembers a profile worked out elsewhere (the checks). */
    fun remember(url: String, part: MixPart, p: MixProfile) { known[key(url, part)] = p }

    /** Either end, for a rough idea of the song's tempo and key. */
    fun any(url: String): MixProfile? = profile(url, MixPart.HEAD) ?: profile(url, MixPart.TAIL)

    /** Loads what's already saved for these songs (no listening). Blocking, so off the main thread. */
    fun loadSaved(urls: List<String>) {
        for (u in urls) for (part in MixPart.values()) if (known[key(u, part)] == null) runCatching { readSaved(u, part) }
    }

    /** One file per song end, named after the song's address (no lookups, so it works offline). */
    private fun file(url: String, part: MixPart): File? {
        val d = dir ?: return null
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(d, "${part.name.lowercase()}_$hash.json")
    }

    private fun readSaved(url: String, part: MixPart): MixProfile? {
        val f = file(url, part)?.takeIf { it.exists() } ?: return null
        val p = MixProfile.fromJson(JSONObject(f.readText())) ?: return null
        known[key(url, part)] = p
        return p
    }

    /** Listens to these song ends, in this order, replacing whatever was still waiting. */
    fun request(jobs: List<Pair<String, MixPart>>) {
        synchronized(lock) {
            queue.clear()
            for (j in jobs) if (known[key(j.first, j.second)] == null && !gaveUp(key(j.first, j.second))) queue += j
            if (queue.isNotEmpty()) lock.notifyAll()
            if (thread == null) {
                thread = Thread({ loop() }, "mix-listen").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                    start()
                }
            }
        }
    }

    private fun loop() {
        while (true) {
            val job = synchronized(lock) {
                while (queue.isEmpty()) lock.wait()
                queue.first().also { queue.remove(it) }
            }
            val (url, part) = job
            val k = key(url, part)
            if (known[k] != null) continue
            try {
                val p = readSaved(url, part) ?: run {
                    val pcm = decoder?.decode(url, part) ?: return@run null
                    val prof = MixAnalysis.analyse(pcm)
                    known[k] = prof
                    file(url, part)?.writeText(prof.toJson().toString())
                    prof
                }
                if (p == null) missing[k] = System.currentTimeMillis() else listeners.forEach { runCatching { it(url) } }
            } catch (e: Throwable) {
                missing[k] = System.currentTimeMillis()
                android.util.Log.w("Glasswave", "Couldn't listen to ${part.name.lowercase()} of $url", e)
            }
        }
    }
}
