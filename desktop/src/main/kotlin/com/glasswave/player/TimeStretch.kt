// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Plays a song a little faster or slower without changing its pitch (WSOLA), so Automix can match
 * one song's tempo to another's. Stereo float samples in, stereo float samples out. At speed 1 it
 * gives back the song unchanged. It also says which moment of the song each block of output is,
 * so the player's position stays exact.
 */
class TimeStretch(private val channels: Int = 2) {
    @Volatile var speed = 1.0

    private val win = WINDOW
    private val hop = WINDOW / 2
    private val w = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / win) }

    private var buf = FloatArray(win * channels * 16)
    /** Song frame of buf's first frame, and how many frames it holds. */
    private var base = 0L
    private var count = 0
    private val ola = FloatArray(win * channels)
    private var pos = 0.0
    private var last = -1L

    /** The song frame of the start of the block [pull] last gave back. */
    var blockFrame = 0L
        private set

    /** Starts over at song frame [frame] (after loading or seeking). */
    fun reset(frame: Long) {
        base = frame
        count = 0
        pos = frame.toDouble()
        last = -1
        ola.fill(0f)
    }

    /** Adds [frames] frames of interleaved samples. */
    fun push(src: FloatArray, frames: Int) {
        // Drop what's no longer needed, then make room.
        val keep = minOf(pos.toLong() - SEEK - 1, if (last >= 0) last else Long.MAX_VALUE) - base
        if (keep > 0) {
            val k = minOf(keep, count.toLong()).toInt()
            System.arraycopy(buf, k * channels, buf, 0, (count - k) * channels)
            count -= k
            base += k
        }
        val need = (count + frames) * channels
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
        System.arraycopy(src, 0, buf, count * channels, frames * channels)
        count += frames
    }

    /** Puts the next [hop] frames of output in [out] if there's enough input. Returns frames written (0 or hop). */
    fun pull(out: FloatArray): Int {
        val target = pos.roundToLong()
        val natural = if (last >= 0) last + hop else target
        val endNeeded = maxOf(target + SEEK + win, natural + hop)
        if (endNeeded > base + count) return 0
        val start = if (last < 0) target else bestStart(target, natural)
        val s = ((start - base) * channels).toInt()
        for (n in 0 until win) {
            val g = w[n].toFloat()
            for (c in 0 until channels) ola[n * channels + c] += g * buf[s + n * channels + c]
        }
        System.arraycopy(ola, 0, out, 0, hop * channels)
        System.arraycopy(ola, hop * channels, ola, 0, (win - hop) * channels)
        java.util.Arrays.fill(ola, (win - hop) * channels, win * channels, 0f)
        blockFrame = start
        last = start
        pos += hop * speed
        return hop
    }

    /** The window start near [target] whose sound best continues from [natural]. */
    private fun bestStart(target: Long, natural: Long): Long {
        if (target == natural) return target
        val nat = ((natural - base) * channels).toInt()
        fun score(cand: Long, step: Int): Double {
            val c = ((cand - base) * channels).toInt()
            var xy = 0.0
            var yy = 0.0
            var n = 0
            while (n < hop) {
                val a = buf[nat + n * channels] + buf[nat + n * channels + 1]
                val b = buf[c + n * channels] + buf[c + n * channels + 1]
                xy += a * b
                yy += b * b
                n += step
            }
            return if (yy <= 1e-12) 0.0 else xy / sqrt(yy)
        }
        var best = target
        var bestScore = Double.NEGATIVE_INFINITY
        var d = -SEEK
        while (d <= SEEK) {
            val cand = target + d
            if (cand >= base) {
                val sc = score(cand, 4)
                if (sc > bestScore) { bestScore = sc; best = cand }
            }
            d += 4
        }
        // Then sample by sample around the best rough match.
        val coarse = best
        bestScore = Double.NEGATIVE_INFINITY
        for (d2 in -3..3) {
            val cand = coarse + d2
            if (cand < base || cand + win > base + count) continue
            val sc = score(cand, 1)
            if (sc > bestScore) { bestScore = sc; best = cand }
        }
        return best
    }

    companion object {
        /** 40 ms windows at 48 kHz, searched ±10 ms. */
        const val WINDOW = 1920
        const val SEEK = 480
        const val HOP = WINDOW / 2
    }
}
