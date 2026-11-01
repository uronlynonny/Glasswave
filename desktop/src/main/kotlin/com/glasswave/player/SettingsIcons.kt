// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

/** How a settings icon moves when the menu appears. */
internal enum class IconMotion(val ms: Int) {
    WIGGLE(700), BOUNCE(650), BARS(1100), SWEEP(800), POP(500), SPIN(750), DROP(550), NOD(600),
}

/**
 * A settings icon that does a small move of its own when it shows up ([delayMs] staggers a list
 * of them): the palette wiggles, the note bounces, the sync arrows spin and so on. [live] keeps
 * the sound bars dancing gently (while music plays).
 */
@Composable
internal fun MovingIcon(icon: ImageVector, motion: IconMotion, size: Dp, tint: Color, delayMs: Int = 0, live: Boolean = false) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.snapTo(0f)
        delay(delayMs.toLong())
        p.animateTo(1f, tween(motion.ms, easing = if (motion == IconMotion.SPIN) FastOutSlowInEasing else LinearEasing))
    }
    if (motion == IconMotion.BARS) {
        Bars(size, tint, p.value, live)
        return
    }
    val t = p.value
    val fade = 1f - t
    Box(
        Modifier.size(size).graphicsLayer {
            when (motion) {
                IconMotion.WIGGLE -> rotationZ = 16f * sin(t * 3f * PI.toFloat()) * fade
                IconMotion.BOUNCE -> translationY = -size.toPx() * 0.3f * abs(sin(t * 2f * PI.toFloat())) * fade
                IconMotion.SWEEP -> rotationZ = -42f * fade * kotlin.math.cos(t * 2f * PI.toFloat())
                IconMotion.POP -> {
                    val s = if (t < 0.35f) 0.55f + (1.2f - 0.55f) * (t / 0.35f) else 1.2f - 0.2f * ((t - 0.35f) / 0.65f)
                    scaleX = s; scaleY = s
                }
                IconMotion.SPIN -> rotationZ = 360f * t
                IconMotion.DROP -> {
                    translationY = -size.toPx() * 0.45f * fade * fade
                    alpha = 0.3f + 0.7f * t
                }
                IconMotion.NOD -> translationY = size.toPx() * 0.12f * sin(t * 4f * PI.toFloat()) * fade
                IconMotion.BARS -> Unit
            }
        },
        contentAlignment = Alignment.Center,
    ) { Ico(icon, tint, size) }
}

/** Four rounded bars like an equalizer: they jump when the menu opens, and keep dancing while [live]. */
@Composable
private fun Bars(size: Dp, tint: Color, t: Float, live: Boolean) {
    val loop = rememberInfiniteTransition(label = "bars")
    val beat by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "beat")
    val rest = floatArrayOf(0.45f, 0.8f, 0.6f, 0.35f)
    val phase = floatArrayOf(0f, 1.3f, 2.6f, 3.9f)
    Canvas(Modifier.size(size)) {
        val n = 4
        val gap = this.size.width * 0.1f
        val w = (this.size.width - gap * (n + 1)) / n
        for (i in 0 until n) {
            // Opening jump, fading out; then a soft dance while music plays.
            val jump = 0.45f * sin(t * 3f * PI.toFloat() + phase[i]) * (1f - t)
            val dance = if (live) 0.22f * sin(beat * 2f * PI.toFloat() * (1 + i % 2) + phase[i]) else 0f
            val h = (rest[i] + jump + dance).coerceIn(0.15f, 1f) * this.size.height * 0.86f
            drawRoundRect(
                tint,
                Offset(gap + i * (w + gap), this.size.height * 0.93f - h),
                Size(w, h),
                CornerRadius(w / 2),
            )
        }
    }
}
