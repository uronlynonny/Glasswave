// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The mixer's EQ for one song during a mix, on interleaved float samples:
 * - [bass] (0..1): a DJ "isolator". The sound is split at 180 Hz into bass and the rest (with
 *   filters that add back up to the original), and the bass is turned down by this much. This is
 *   what lets the bass change hands halfway through a blend, so two kick drums never pile up.
 * - [sweepHz]: a high-pass filter that rises as a song leaves (0 = off).
 * Changes glide across each block, so nothing clicks.
 */
class MixEq(private val rate: Int, private val channels: Int) {
    @Volatile var bass = 1f
    @Volatile var sweepHz = 0f

    private var bassNow = 1f
    private val lp = Array(channels) { arrayOf(Biquad(), Biquad()) }
    private val hp = Array(channels) { arrayOf(Biquad(), Biquad()) }
    private val sw = Array(channels) { arrayOf(Biquad(), Biquad()) }
    private var sweepOn = false

    init {
        for (c in 0 until channels) {
            lp[c].forEach { it.lowPass(180.0, rate) }
            hp[c].forEach { it.highPass(180.0, rate) }
        }
    }

    fun reset() {
        for (c in 0 until channels) { lp[c].forEach { it.clear() }; hp[c].forEach { it.clear() }; sw[c].forEach { it.clear() } }
        sweepOn = false
    }

    fun process(buf: FloatArray, off: Int, frames: Int) {
        if (frames <= 0) return
        val target = bass.coerceIn(0f, 1f)
        val from = bassNow
        val sweep = sweepHz
        if (sweep > 20f) {
            if (!sweepOn) { for (c in 0 until channels) sw[c].forEach { it.clear() }; sweepOn = true }
            for (c in 0 until channels) sw[c].forEach { it.highPass(sweep.toDouble().coerceAtMost(rate * 0.45), rate) }
        } else sweepOn = false
        for (f in 0 until frames) {
            val g = from + (target - from) * f / frames
            for (c in 0 until channels) {
                val i = off + f * channels + c
                val x = buf[i].toDouble()
                val lo = lp[c][1].run(lp[c][0].run(x))
                val hi = hp[c][1].run(hp[c][0].run(x))
                var y = g * lo + hi
                if (sweepOn) y = sw[c][1].run(sw[c][0].run(y))
                buf[i] = y.toFloat()
            }
        }
        bassNow = target
    }

    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0

        fun clear() { z1 = 0.0; z2 = 0.0 }

        fun lowPass(f: Double, rate: Int) = set(f, rate, low = true)
        fun highPass(f: Double, rate: Int) = set(f, rate, low = false)

        /** Butterworth (Q = 1/√2); two in a row make the Linkwitz–Riley pair that sums back flat. */
        private fun set(f: Double, rate: Int, low: Boolean) {
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 / sqrt(2.0))
            val c = cos(w)
            val a0 = 1 + alpha
            if (low) { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0 } else { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0 }
            b2 = b0
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
        }

        fun run(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}
