// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Bars that move with the music, in the highlight colour. Drawn every frame without
 * redrawing anything around it, and only measuring the sound while it's on screen.
 *
 * Each bar glides on a soft spring, so it rises and falls smoothly instead of jumping, and
 * neighbouring bars blend a little so the row moves like one wave. The bass makes the whole row
 * swell, the low (left) bars most.
 */
@Composable
fun Visualizer(playing: Boolean, color: Color, modifier: Modifier = Modifier, style: VisualizerStyle = Settings.visualizer) {
    if (style == VisualizerStyle.OFF) return
    val n = Spectrum.BANDS
    val raw = remember { FloatArray(n) }
    val pos = remember { FloatArray(n) }
    val vel = remember { FloatArray(n) }
    val usual = remember { FloatArray(n) { 0.4f } }
    val bassUsual = remember { floatArrayOf(0.4f) }
    val swell = remember { mutableFloatStateOf(0f) }
    val tick = remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        Spectrum.watch()
        onDispose { Spectrum.unwatch() }
    }
    LaunchedEffect(playing) {
        var last = 0L
        val target = FloatArray(n)
        while (true) {
            val settled = withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 64f)
                last = now
                val f = dt / 16f
                val got = playing && Spectrum.now(raw)
                if (!got) raw.fill(0f)

                // Bass: the lowest notes against their usual level, smoothed into a swell.
                var bass = 0f
                for (i in 0 until 5) bass += raw[i]
                bass /= 5f
                if (got) bassUsual[0] += (bass - bassUsual[0]) * (1f - 0.985f.pow(f))
                val hit = if (got) ((bass - bassUsual[0]) * 4f + bass * 0.35f).coerceIn(0f, 1f) else 0f
                val s = swell.floatValue
                swell.floatValue = s + (hit - s) * (1f - (if (hit > s) 0.55f else 0.88f).pow(f))
                val sw = swell.floatValue

                for (i in 0 until n) {
                    // Blend with the neighbours so the row moves as one wave.
                    val a = raw[(i - 1).coerceAtLeast(0)]
                    val b = raw[(i + 1).coerceAtMost(n - 1)]
                    val v = raw[i] * 0.5f + (a + b) * 0.25f
                    if (got) usual[i] += (v - usual[i]) * (1f - 0.98f.pow(f))
                    val lift = (v - usual[i]) * 1.3f
                    val low = 1f - i.toFloat() / n
                    val x = (v * 0.72f + lift) * (0.8f + sw * (0.05f + 0.45f * low))
                    // Soft top: loud moments still rise, but the row never flattens at full height.
                    target[i] = if (got) (if (x < 0.7f) x else 0.7f + (x - 0.7f) * 0.35f).coerceIn(0f, 0.98f) else 0f
                }
                // The springs, in small steps so they stay smooth at any frame rate.
                val steps = ceil(dt / 8f).toInt().coerceAtLeast(1)
                val h = dt / steps / 16f
                var moving = 0f
                repeat(steps) {
                    for (i in 0 until n) {
                        vel[i] += (target[i] - pos[i]) * 0.34f * h
                        vel[i] *= 0.74f.pow(h)
                        pos[i] = (pos[i] + vel[i] * h).coerceIn(0f, 1f)
                    }
                }
                for (i in 0 until n) moving = maxOf(moving, pos[i], kotlin.math.abs(vel[i]))
                tick.longValue = now
                !playing && moving < 0.004f
            }
            if (settled) break
        }
    }
    Box(
        modifier.drawBehind {
            tick.longValue // redraw on every new frame
            val w = size.width
            val h = size.height
            val sw = swell.floatValue
            val brush = Brush.verticalGradient(
                listOf(color, color.copy(alpha = 0.45f + sw * 0.15f)),
                startY = 0f, endY = h,
            )
            when (style) {
                VisualizerStyle.BARS, VisualizerStyle.MIRROR -> {
                    val gap = w / n * 0.34f
                    val bw = w / n - gap
                    val mirror = style == VisualizerStyle.MIRROR
                    for (i in 0 until n) {
                        val v = (0.05f + pos[i] * 0.95f).coerceIn(0f, 1f)
                        val bh = maxOf(bw, v * h)
                        val x = i * (bw + gap) + gap / 2
                        val y = if (mirror) (h - bh) / 2 else h - bh
                        drawRoundRect(brush, Offset(x, y), Size(bw, bh), CornerRadius(bw / 2))
                    }
                }
                VisualizerStyle.OFF -> Unit
            }
        },
    )
}
