package androidx.palette.graphics

import org.jetbrains.skia.Bitmap
import kotlin.math.abs

/**
 * A small desktop version of Android's Palette: finds the main colours of a picture and picks
 * vibrant / muted, light / dark ones the same way (same targets and weights).
 */
class Palette private constructor(private val swatches: List<Swatch>) {
    class Swatch(val rgb: Int, val population: Int) {
        internal val hsl: FloatArray = hsl(rgb)
    }

    private val used = HashSet<Swatch>()
    private val maxPop = swatches.maxOfOrNull { it.population } ?: 1

    val dominantSwatch: Swatch? = swatches.maxByOrNull { it.population }
    val vibrantSwatch: Swatch? = pick(0.3f, 0.5f, 0.7f, 0.35f, 1f, 1f)
    val lightVibrantSwatch: Swatch? = pick(0.55f, 0.74f, 1f, 0.35f, 1f, 1f)
    val darkVibrantSwatch: Swatch? = pick(0f, 0.26f, 0.45f, 0.35f, 1f, 1f)
    val mutedSwatch: Swatch? = pick(0.3f, 0.5f, 0.7f, 0f, 0.3f, 0.4f)
    val lightMutedSwatch: Swatch? = pick(0.55f, 0.74f, 1f, 0f, 0.3f, 0.4f)
    val darkMutedSwatch: Swatch? = pick(0f, 0.26f, 0.45f, 0f, 0.3f, 0.4f)

    fun getDominantColor(default: Int): Int = dominantSwatch?.rgb ?: default

    private fun pick(minL: Float, tL: Float, maxL: Float, minS: Float, tS: Float, maxS: Float): Swatch? {
        val best = swatches
            .filter { it !in used && it.hsl[1] in minS..maxS && it.hsl[2] in minL..maxL }
            .maxByOrNull {
                0.24f * (1f - abs(it.hsl[1] - tS)) + 0.52f * (1f - abs(it.hsl[2] - tL)) + 0.24f * it.population / maxPop
            }
        if (best != null) used += best
        return best
    }

    class Builder(private val bitmap: Bitmap) {
        private var max = 16
        fun maximumColorCount(n: Int) = apply { max = n }

        fun generate(): Palette {
            // Count colours, rounded to 5 bits a channel, skipping see-through, near-white and near-black pixels.
            val counts = HashMap<Int, Int>()
            val sum = HashMap<Int, LongArray>()
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val c = bitmap.getColor(x, y)
                if ((c ushr 24) < 128) continue
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val h = hsl(c)
                if (h[2] > 0.95f || h[2] < 0.05f) continue
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                counts[key] = (counts[key] ?: 0) + 1
                val s = sum.getOrPut(key) { LongArray(3) }
                s[0] += r.toLong(); s[1] += g.toLong(); s[2] += b.toLong()
            }
            // Merge into at most [max] groups by joining nearby colours.
            var groups = counts.entries.map { (k, n) ->
                val s = sum[k]!!
                Triple(floatArrayOf(s[0].toFloat() / n, s[1].toFloat() / n, s[2].toFloat() / n), n, k)
            }.sortedByDescending { it.second }.map { it.first to it.second }.toMutableList()
            var limit = 24f
            while (groups.size > max && limit < 256) {
                val merged = mutableListOf<Pair<FloatArray, Int>>()
                for ((c, n) in groups) {
                    val near = merged.indexOfFirst { (m, _) -> abs(m[0] - c[0]) + abs(m[1] - c[1]) + abs(m[2] - c[2]) < limit }
                    if (near < 0) merged += c to n
                    else {
                        val (m, mn) = merged[near]
                        val t = mn + n
                        merged[near] = floatArrayOf((m[0] * mn + c[0] * n) / t, (m[1] * mn + c[1] * n) / t, (m[2] * mn + c[2] * n) / t) to t
                    }
                }
                groups = merged
                limit *= 1.5f
            }
            val swatches = groups.take(max).map { (c, n) ->
                Swatch((0xFF shl 24) or (c[0].toInt() shl 16) or (c[1].toInt() shl 8) or c[2].toInt(), n)
            }
            return Palette(swatches)
        }
    }

    companion object {
        fun from(bitmap: Bitmap) = Builder(bitmap)

        internal fun hsl(c: Int): FloatArray {
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val l = (mx + mn) / 2
            val d = mx - mn
            if (d == 0f) return floatArrayOf(0f, 0f, l)
            val s = d / (1 - abs(2 * l - 1))
            val h = when (mx) {
                r -> ((g - b) / d).mod(6f)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } * 60
            return floatArrayOf(h, s.coerceIn(0f, 1f), l)
        }
    }
}
