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
import androidx.compose.ui.graphics.lerp

/**
 * Bars that move with the music, in the highlight colour. Drawn every frame without
 * redrawing anything around it, and only measuring the sound while it's on screen.
 *
 * Lively on purpose: each bar shows how much louder its notes are than they've been lately (so
 * beats jump out even in loud songs), every bass hit pumps the bars up and brightens them, the
 * bars drop fast, and a small cap on each one falls more slowly.
 */
@Composable
fun Visualizer(playing: Boolean, color: Color, modifier: Modifier = Modifier, style: VisualizerStyle = Settings.visualizer) {
    if (style == VisualizerStyle.OFF) return
    val raw = remember { FloatArray(Spectrum.BANDS) }
    val shown = remember { FloatArray(Spectrum.BANDS) }
    val caps = remember { FloatArray(Spectrum.BANDS) }
    val usual = remember { FloatArray(Spectrum.BANDS) { 0.4f } }
    val bassUsual = remember { floatArrayOf(0.4f) }
    val kick = remember { mutableFloatStateOf(0f) }
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
                val f = dt / 16.0
                val got = playing && Spectrum.now(raw)
                // Bass hits: the lowest notes jumping above their usual level.
                var bass = 0f
                for (i in 0 until 5) bass += raw[i]
                bass /= 5f
                if (got) {
                    bassUsual[0] += (bass - bassUsual[0]) * (1f - Math.pow(0.97, f).toFloat())
                    val hit = ((bass - bassUsual[0]) * 5f).coerceIn(0f, 1f)
                    kick.floatValue = maxOf(hit, kick.floatValue * Math.pow(0.82, f).toFloat())
                } else {
                    kick.floatValue *= Math.pow(0.82, f).toFloat()
                }
                val k = kick.floatValue
                val n = shown.size
                var peak = 0f
                for (i in shown.indices) {
                    val target = if (got) {
                        // How loud it is, plus how much louder than lately: steady sound sits
                        // lower, every change jumps.
                        usual[i] += (raw[i] - usual[i]) * (1f - Math.pow(0.975, f).toFloat())
                        val lift = (raw[i] - usual[i]) * 2.6f
                        val low = 1f - i.toFloat() / n
                        ((raw[i] * 0.62f + lift) * (0.82f + k * (0.25f + 0.45f * low))).coerceIn(0f, 1f)
                    } else 0f
                    // Up at once, down fast.
                    val speed = if (target > shown[i]) 1f - Math.pow(0.12, f).toFloat() else 1f - Math.pow(0.74, f).toFloat()
                    shown[i] += (target - shown[i]) * speed
                    caps[i] = maxOf(shown[i], caps[i] - 0.011f * dt / 16f)
                    peak = maxOf(peak, caps[i])
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
            val k = kick.floatValue
            val top = lerp(color, Color.White, k * 0.35f)
            val brush = Brush.verticalGradient(listOf(top, color.copy(alpha = 0.35f + k * 0.2f)), startY = 0f, endY = h)
            val capColor = lerp(color, Color.White, 0.55f)
            when (style) {
                VisualizerStyle.BARS, VisualizerStyle.MIRROR -> {
                    val gap = w / n * 0.32f
                    val bw = w / n - gap
                    val capH = maxOf(2f, bw * 0.45f)
                    val room = h - capH * 2
                    val mirror = style == VisualizerStyle.MIRROR
                    for (i in 0 until n) {
                        val v = (0.04f + shown[i] * 0.96f).coerceIn(0f, 1f)
                        val bh = maxOf(bw, v * room)
                        val x = i * (bw + gap) + gap / 2
                        val y = if (mirror) (h - bh) / 2 else h - bh
                        drawRoundRect(brush, Offset(x, y), Size(bw, bh), CornerRadius(bw / 2))
                        // The cap, a little above the bar while it falls back.
                        if (caps[i] > shown[i] + 0.03f) {
                            val ch = (0.04f + caps[i] * 0.96f) * room
                            val cy = if (mirror) (h - ch) / 2 - capH * 1.5f else h - ch - capH * 1.5f
                            drawRoundRect(capColor, Offset(x, cy), Size(bw, capH), CornerRadius(capH / 2))
                            if (mirror) drawRoundRect(capColor, Offset(x, h - cy - capH), Size(bw, capH), CornerRadius(capH / 2))
                        }
                    }
                }
                VisualizerStyle.OFF -> Unit
            }
        },
    )
}
