// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Bars that move with the music, in the highlight colour. Drawn every frame without
 * redrawing anything around it, and only measuring the sound while it's on screen.
 */
@Composable
fun Visualizer(playing: Boolean, color: Color, modifier: Modifier = Modifier, style: VisualizerStyle = Settings.visualizer) {
    if (style == VisualizerStyle.OFF) return
    val raw = remember { FloatArray(Spectrum.BANDS) }
    val shown = remember { FloatArray(Spectrum.BANDS) }
    val tick = remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        Spectrum.watch()
        onDispose { Spectrum.unwatch() }
    }
    LaunchedEffect(playing) {
        var last = 0L
        while (true) {
            val settled = withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 64f)
                last = now
                val got = playing && Spectrum.now(raw)
                var peak = 0f
                for (i in shown.indices) {
                    val target = if (got) raw[i] else 0f
                    // Jump up fast, fall back slowly, like a real meter.
                    val k = if (target > shown[i]) 1f - Math.pow(0.35, dt / 16.0).toFloat() else 1f - Math.pow(0.86, dt / 16.0).toFloat()
                    shown[i] += (target - shown[i]) * k
                    peak = maxOf(peak, shown[i])
                }
                tick.longValue = now
                !playing && peak < 0.005f
            }
            if (settled) break
        }
    }
    Box(
        modifier.drawBehind {
            tick.longValue // redraw on every new frame
            val n = shown.size
            val w = size.width
            val h = size.height
            val brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.35f)), startY = 0f, endY = h)
            when (style) {
                VisualizerStyle.BARS, VisualizerStyle.MIRROR -> {
                    val gap = w / n * 0.32f
                    val bw = w / n - gap
                    for (i in 0 until n) {
                        val v = (0.04f + shown[i] * 0.96f).coerceIn(0f, 1f)
                        val bh = maxOf(bw, v * h)
                        val x = i * (bw + gap) + gap / 2
                        val y = if (style == VisualizerStyle.MIRROR) (h - bh) / 2 else h - bh
                        drawRoundRect(brush, Offset(x, y), Size(bw, bh), CornerRadius(bw / 2))
                    }
                }
                VisualizerStyle.OFF -> Unit
            }
        },
    )
}
