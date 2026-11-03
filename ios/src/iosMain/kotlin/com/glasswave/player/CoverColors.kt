// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Two colours from a cover: [a] its most vivid colour, [b] a deep shade for the bottom of the screen. */
data class CoverColors(val a: Color, val b: Color)

val PlainColors = CoverColors(Color(0xFF2B3040), Color(0xFF0E0F13))

private val found = HashMap<String, CoverColors>()

@Composable
fun rememberCoverColors(url: String?): CoverColors {
    val context = LocalPlatformContext.current
    var target by remember(url) { mutableStateOf(url?.let { found[it] } ?: PlainColors) }
    LaunchedEffect(url) {
        if (url == null || found.containsKey(url)) return@LaunchedEffect
        val result = runCatching {
            SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).size(48).build())
        }.getOrNull()
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val colors = withContext(Dispatchers.Default) {
            val px = ArrayList<Int>(bitmap.width * bitmap.height)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) px += bitmap.getColor(x, y)
            colorsOf(px)
        }
        found[url] = colors
        target = colors
    }
    val a by animateColorAsState(target.a, tween(700))
    val b by animateColorAsState(target.b, tween(700))
    return CoverColors(a, b)
}

private fun colorsOf(px: List<Int>): CoverColors {
    if (px.isEmpty()) return PlainColors
    data class P(val r: Float, val g: Float, val b: Float, val score: Float)
    val all = px.map { c ->
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val sat = if (hi <= 0f) 0f else (hi - lo) / hi
        P(r, g, b, sat * (0.35f + hi))
    }
    fun avg(list: List<P>): Triple<Float, Float, Float> =
        Triple(list.map { it.r }.average().toFloat(), list.map { it.g }.average().toFloat(), list.map { it.b }.average().toFloat())
    val vivid = avg(all.sortedByDescending { it.score }.take(max(1, all.size / 5)))
    val overall = avg(all)
    // Keep the colour rich but never so bright that white text stops reading on it.
    fun shade(c: Triple<Float, Float, Float>, brightness: Float): Color {
        val hi = max(c.first, max(c.second, c.third)).coerceAtLeast(0.01f)
        val k = brightness / hi
        return Color((c.first * k).coerceIn(0f, 1f), (c.second * k).coerceIn(0f, 1f), (c.third * k).coerceIn(0f, 1f))
    }
    return CoverColors(shade(vivid, 0.62f), shade(overall, 0.2f))
}
