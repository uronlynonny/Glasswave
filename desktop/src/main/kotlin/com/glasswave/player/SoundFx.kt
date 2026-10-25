// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * The sound effects from Settings > Sound & effects, worked out sample by sample. Plain Kotlin with
 * nothing phone-specific, so the phone app and Glasswave for Windows use this same file.
 */

/**
 * The equalizer, bass and treble, distortion, stereo width, reverb, balance and mono from [Sound],
 * for one stream of stereo sound at [rate] samples a second. Call [process] for each pair of
 * samples (-1..1); the result is in [outL] and [outR].
 */
class SoundFx(private val rate: Int) {
    private var seen = -1

    // Filters: 10 equalizer bands, then bass and treble shelves. Per side (left, right).
    private val n = Sound.BANDS.size + 2
    private val b0 = FloatArray(n); private val b1 = FloatArray(n); private val b2 = FloatArray(n)
    private val a1 = FloatArray(n); private val a2 = FloatArray(n)
    private val on = BooleanArray(n)
    private val z1 = Array(2) { FloatArray(n) }
    private val z2 = Array(2) { FloatArray(n) }
    private var preGain = 1f

    // Settings as last read (on the audio thread).
    private var drive = 0f
    private var driveNorm = 1f
    private var width = 0f
    private var wet = 0f
    private var dry = 1f
    private var gainL = 1f
    private var gainR = 1f
    private var mono = false

    /** False when nothing is switched on, so the sound can be passed straight through. */
    var active = false
        private set

    var outL = 0f
        private set
    var outR = 0f
        private set

    private var reverb: Reverb? = null

    /** Forget the sound so far (after a jump in the song). */
    fun clear() {
        for (c in 0..1) { z1[c].fill(0f); z2[c].fill(0f) }
        reverb?.clear()
    }

    /** Picks up changed settings. Cheap to call often: it only does work after a change. */
    fun update() {
        if (seen == Sound.version) return
        seen = Sound.version
        val fs = rate.toFloat()
        var boost = 0f
        val eq = Sound.eqOn
        Sound.BANDS.forEachIndexed { i, f ->
            val db = if (eq) Sound.gains.getOrElse(i) { 0f } else 0f
            on[i] = db != 0f && f < fs * 0.45f
            if (on[i]) peaking(i, f, 1.4f, db, fs)
            boost = maxOf(boost, db)
        }
        val bassDb = Sound.bass * 12f
        on[n - 2] = bassDb > 0f
        if (on[n - 2]) shelf(n - 2, 90f, bassDb, fs, low = true)
        val trebleDb = Sound.treble * 9f
        on[n - 1] = trebleDb > 0f && 5500f < fs * 0.45f
        if (on[n - 1]) shelf(n - 1, 5500f, trebleDb, fs, low = false)
        // Leave room for the boosts so loud songs don't crackle.
        val total = boost + bassDb * 0.6f + trebleDb * 0.3f
        preGain = 10f.pow(-maxOf(0f, total) * 0.55f / 20f)

        drive = Sound.drive
        driveNorm = 1f / tanh(1f + drive * 14f)
        width = Sound.width
        val r = Sound.reverb
        wet = r * 1.6f
        dry = 1f - r * 0.35f
        if (r > 0f) {
            val rv = reverb ?: Reverb(rate).also { reverb = it }
            rv.set(Sound.room.size, Sound.room.damp)
        }
        val bal = Sound.balance
        gainL = if (bal > 0) 1f - bal else 1f
        gainR = if (bal < 0) 1f + bal else 1f
        mono = Sound.mono
        active = on.any { it } || drive > 0f || width > 0f || r > 0f || bal != 0f || mono
    }

    private fun peaking(i: Int, f: Float, q: Float, db: Float, fs: Float) {
        val a = 10f.pow(db / 40f)
        val w = 2f * PI.toFloat() * f / fs
        val alpha = sin(w) / (2f * q)
        val c = cos(w)
        val a0 = 1f + alpha / a
        b0[i] = (1f + alpha * a) / a0
        b1[i] = (-2f * c) / a0
        b2[i] = (1f - alpha * a) / a0
        a1[i] = (-2f * c) / a0
        a2[i] = (1f - alpha / a) / a0
    }

    private fun shelf(i: Int, f: Float, db: Float, fs: Float, low: Boolean) {
        val a = 10f.pow(db / 40f)
        val w = 2f * PI.toFloat() * f / fs
        val c = cos(w)
        val alpha = sin(w) / 2f * sqrt(2f)
        val s = 2f * sqrt(a) * alpha
        if (low) {
            val a0 = (a + 1) + (a - 1) * c + s
            b0[i] = a * ((a + 1) - (a - 1) * c + s) / a0
            b1[i] = 2 * a * ((a - 1) - (a + 1) * c) / a0
            b2[i] = a * ((a + 1) - (a - 1) * c - s) / a0
            a1[i] = -2 * ((a - 1) + (a + 1) * c) / a0
            a2[i] = ((a + 1) + (a - 1) * c - s) / a0
        } else {
            val a0 = (a + 1) - (a - 1) * c + s
            b0[i] = a * ((a + 1) + (a - 1) * c + s) / a0
            b1[i] = -2 * a * ((a - 1) + (a + 1) * c) / a0
            b2[i] = a * ((a + 1) + (a - 1) * c - s) / a0
            a1[i] = 2 * ((a - 1) - (a + 1) * c) / a0
            a2[i] = ((a + 1) - (a - 1) * c - s) / a0
        }
    }

    private fun filter(ch: Int, x: Float): Float {
        var v = x
        val s1 = z1[ch]
        val s2 = z2[ch]
        for (i in 0 until n) {
            if (!on[i]) continue
            // Transposed direct form II: steady and cheap.
            val y = b0[i] * v + s1[i]
            s1[i] = b1[i] * v - a1[i] * y + s2[i]
            s2[i] = b2[i] * v - a2[i] * y
            v = y
        }
        return v
    }

    /** One pair of samples through every effect that's on. */
    fun process(left: Float, right: Float) {
        if (!active) { outL = left; outR = right; return }
        var l = filter(0, left * preGain)
        var r = filter(1, right * preGain)
        if (drive > 0f) {
            val pre = 1f + drive * 14f
            val k = driveNorm * (1f - drive * 0.35f)
            l = tanh(l * pre) * k
            r = tanh(r * pre) * k
        }
        if (width > 0f) {
            val m = (l + r) * 0.5f
            val s = (l - r) * 0.5f * (1f + width * 1.6f)
            l = m + s
            r = m - s
        }
        val rv = if (wet > 0f) reverb else null
        if (rv != null) {
            rv.process(l, r)
            l = l * dry + rv.outL * wet
            r = r * dry + rv.outR * wet
        }
        if (mono) {
            val m = (l + r) * 0.5f
            l = m; r = m
        }
        outL = soft(l * gainL)
        outR = soft(r * gainR)
    }

    /** Rounds off peaks instead of clipping them harshly. */
    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.92f) return x
        val y = 0.92f + 0.08f * tanh((a - 0.92f) / 0.08f)
        return if (x < 0) -y else y
    }
}

/**
 * A light reverb (the classic "Freeverb" design): eight echoing delay lines and four smearing
 * ones per side, slightly different on the left and right so it sounds wide.
 */
private class Reverb(rate: Int) {
    private val scale = rate / 44100f
    private val combL = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb((it * scale).toInt().coerceAtLeast(8)) }
    private val combR = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb(((it + 23) * scale).toInt().coerceAtLeast(8)) }
    private val apL = intArrayOf(556, 441, 341, 225).map { AllPass((it * scale).toInt().coerceAtLeast(4)) }
    private val apR = intArrayOf(556, 441, 341, 225).map { AllPass(((it + 23) * scale).toInt().coerceAtLeast(4)) }
    var outL = 0f; private set
    var outR = 0f; private set

    fun set(size: Float, damp: Float) {
        for (c in combL) { c.feedback = size; c.damp = damp }
        for (c in combR) { c.feedback = size; c.damp = damp }
    }

    fun clear() {
        combL.forEach { it.clear() }; combR.forEach { it.clear() }
        apL.forEach { it.clear() }; apR.forEach { it.clear() }
    }

    fun process(l: Float, r: Float) {
        val x = (l + r) * 0.015f
        var a = 0f
        var b = 0f
        for (c in combL) a += c.process(x)
        for (c in combR) b += c.process(x)
        for (p in apL) a = p.process(a)
        for (p in apR) b = p.process(b)
        outL = a
        outR = b
    }

    private class Comb(len: Int) {
        private val buf = FloatArray(len)
        private var i = 0
        private var store = 0f
        var feedback = 0.84f
        var damp = 0.25f
        fun clear() { buf.fill(0f); store = 0f }
        fun process(x: Float): Float {
            val y = buf[i]
            store = y * (1f - damp) + store * damp
            buf[i] = x + store * feedback
            if (++i >= buf.size) i = 0
            return y
        }
    }

    private class AllPass(len: Int) {
        private val buf = FloatArray(len)
        private var i = 0
        fun clear() = buf.fill(0f)
        fun process(x: Float): Float {
            val b = buf[i]
            val y = -x + b
            buf[i] = x + b * 0.5f
            if (++i >= buf.size) i = 0
            return y
        }
    }
}

/**
 * What the visualizer draws: how loud each part of the sound is (bass on the left, treble on the
 * right), measured as the sound goes to the speaker. Each reading is stamped with the time it was
 * measured; the speaker plays it a little later, so the screen shows readings from [latencyMs] ago.
 */
object Spectrum {
    const val BANDS = 32
    private const val SIZE = 1024
    private const val HOP = 512
    private const val KEEP = 160

    /** How long sound waits between being measured and coming out of the speaker. */
    @Volatile
    var latencyMs = 300L

    /** How many visualizers are on screen. Measuring only happens while there's at least one. */
    @Volatile
    private var viewers = 0
    val wanted: Boolean get() = viewers > 0

    @Synchronized fun watch() { viewers++ }
    @Synchronized fun unwatch() { viewers = maxOf(0, viewers - 1) }

    // Audio thread only.
    private val ring = FloatArray(SIZE)
    private var filled = 0
    private var primed = false
    private var sinceHop = 0
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }
    private val rev = IntArray(SIZE).also { r ->
        for (i in 0 until SIZE) {
            var x = i; var y = 0
            repeat(10) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }
    private val cosT = FloatArray(SIZE / 2) { cos(2 * PI * it / SIZE).toFloat() }
    private val sinT = FloatArray(SIZE / 2) { -sin(2 * PI * it / SIZE).toFloat() }
    private val reading = FloatArray(BANDS)

    // Shared between the audio thread and the screen.
    private val times = LongArray(KEEP)
    private val levels = Array(KEEP) { FloatArray(BANDS) }
    private var head = 0
    private var count = 0

    private fun clock() = System.nanoTime() / 1_000_000

    /** One sample (left and right mixed) on its way to the speaker. */
    fun feed(sample: Float, rate: Int) {
        ring[filled] = sample
        filled = (filled + 1) % SIZE
        if (filled == 0) primed = true
        if (++sinceHop < HOP || !primed) return
        sinceHop = 0
        analyse(rate)
    }

    private fun analyse(rate: Int) {
        val start = filled
        for (i in 0 until SIZE) {
            val j = rev[i]
            re[j] = ring[(start + i) % SIZE] * window[i]
            im[j] = 0f
        }
        var len = 2
        while (len <= SIZE) {
            val half = len / 2
            val step = SIZE / len
            var i = 0
            while (i < SIZE) {
                for (k in 0 until half) {
                    val c = cosT[k * step]; val s = sinT[k * step]
                    val a = i + k; val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
        val binHz = rate.toFloat() / SIZE
        val lo = 45f
        val hi = minOf(15000f, rate * 0.45f)
        val full = 10f * log10((SIZE / 4f) * (SIZE / 4f))
        for (b in 0 until BANDS) {
            val f0 = lo * (hi / lo).pow(b.toFloat() / BANDS)
            val f1 = lo * (hi / lo).pow((b + 1).toFloat() / BANDS)
            val k0 = (f0 / binHz).toInt().coerceIn(1, SIZE / 2 - 1)
            val k1 = (f1 / binHz).toInt().coerceIn(k0, SIZE / 2 - 1)
            var peak = 0f
            for (k in k0..k1) peak = maxOf(peak, re[k] * re[k] + im[k] * im[k])
            val db = 10f * log10(peak + 1e-9f) - full
            // Higher notes carry less energy in music: tilt so the right side isn't always flat.
            val tilt = 31.5f * b / BANDS
            reading[b] = ((db + tilt + 62f) / 56f).coerceIn(0f, 1f)
        }
        synchronized(this) {
            times[head] = clock()
            reading.copyInto(levels[head])
            head = (head + 1) % KEEP
            if (count < KEEP) count++
        }
    }

    /** The reading the speaker is playing right now, copied into [into]. False if there's none. */
    @Synchronized
    fun now(into: FloatArray): Boolean {
        val target = clock() - latencyMs
        var best = -1
        for (n in 0 until count) {
            val i = (head - 1 - n + KEEP) % KEEP
            if (times[i] <= target) { best = i; break }
        }
        if (best < 0 || target - times[best] > 400) return false
        levels[best].copyInto(into)
        return true
    }
}
