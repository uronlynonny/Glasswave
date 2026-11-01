// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The visualizer that moves with the music, in the highlight colour. Drawn every frame without
 * redrawing anything around it, and only measuring the sound while it's on screen.
 *
 * Each band glides on a soft spring, so it rises and falls smoothly instead of jumping, and
 * neighbouring bands blend a little so the row moves like one wave. The bass makes the whole row
 * swell, the low (left) bands most.
 *
 * [size], [tint] and [feel] are the Settings > Look choices. The strip's height comes from the
 * caller; use [visualizerHeight] for it so the chosen size also makes the strip taller or shorter.
 */
@Composable
fun Visualizer(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    style: VisualizerStyle = Settings.visualizer,
    size: VisualizerSize = Settings.visualizerSize,
    tint: VisualizerColor = Settings.visualizerColor,
    feel: VisualizerFeel = Settings.visualizerFeel,
) {
    if (style == VisualizerStyle.OFF) return
    VisualizerDrawing(playing, color, modifier, style, size, tint, feel, demo = false)
}

/**
 * A small moving sample of [style] for Settings: the real music while something plays,
 * otherwise a made-up beat so it always moves. Off shows nothing (but keeps its space).
 */
@Composable
fun VisualizerPreview(
    style: VisualizerStyle,
    modifier: Modifier = Modifier,
    color: Color = CoverTint.accent,
    size: VisualizerSize = Settings.visualizerSize,
    tint: VisualizerColor = Settings.visualizerColor,
    feel: VisualizerFeel = Settings.visualizerFeel,
) {
    if (style == VisualizerStyle.OFF) { Box(modifier); return }
    VisualizerDrawing(true, color, modifier, style, size, tint, feel, demo = true)
}

/** The strip's height for the chosen size: pass the usual height (e.g. 34.dp). */
fun visualizerHeight(base: Dp, size: VisualizerSize = Settings.visualizerSize): Dp = base * size.scale

@Composable
private fun VisualizerDrawing(
    playing: Boolean,
    color: Color,
    modifier: Modifier,
    style: VisualizerStyle,
    big: VisualizerSize,
    tint: VisualizerColor,
    feel: VisualizerFeel,
    demo: Boolean,
) {
    val n = Spectrum.BANDS
    val raw = remember { FloatArray(n) }
    val pos = remember { FloatArray(n) }
    val vel = remember { FloatArray(n) }
    val usual = remember { FloatArray(n) { 0.4f } }
    val bassUsual = remember { floatArrayOf(0.4f) }
    // The little caps (Peaks) and the faint echo (Wave): the highest point lately, and how long it waits before falling.
    val peak = remember { FloatArray(n) }
    val hold = remember { FloatArray(n) }
    val swell = remember { mutableFloatStateOf(0f) }
    val tick = remember { mutableLongStateOf(0L) }
    val xs = remember { FloatArray(n) }
    val ys = remember { FloatArray(n) }
    val line = remember { Path() }
    val area = remember { Path() }
    val feelNow by rememberUpdatedState(feel)
    val rainbow = remember { Array(n) { Color.hsv((350f + 280f * it / (n - 1)) % 360f, 0.6f, 1f) } }
    val rainbowList = remember { rainbow.toList() }
    // Glow's soft lights, made once (one per band for Rainbow) and stretched to size when drawn.
    val glows = remember(color, tint) {
        Array(if (tint == VisualizerColor.RAINBOW) n else 1) {
            val c = when (tint) { VisualizerColor.SONG -> color; VisualizerColor.WHITE -> Color.White; VisualizerColor.RAINBOW -> rainbow[it] }
            Brush.radialGradient(
                0f to c.copy(alpha = 0.9f), 0.35f to c.copy(alpha = 0.4f), 1f to c.copy(alpha = 0f),
                center = Offset.Zero, radius = GLOW_R,
            )
        }
    }
    DisposableEffect(Unit) {
        Spectrum.watch()
        onDispose { Spectrum.unwatch() }
    }
    LaunchedEffect(playing, demo) {
        var last = 0L
        val target = FloatArray(n)
        while (true) {
            val settled = withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 64f)
                last = now
                val f = dt / 16f
                val got = if (demo) Spectrum.now(raw) || fakeSpectrum(raw, now / 1_000_000) else playing && Spectrum.now(raw)
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
                val pull = feelNow.stiffness
                val calm = feelNow.damping
                var moving = 0f
                repeat(steps) {
                    for (i in 0 until n) {
                        vel[i] += (target[i] - pos[i]) * pull * h
                        vel[i] *= calm.pow(h)
                        pos[i] = (pos[i] + vel[i] * h).coerceIn(0f, 1f)
                    }
                }
                // Caps jump up with the music, wait a moment, then float down.
                for (i in 0 until n) {
                    if (pos[i] >= peak[i]) { peak[i] = pos[i]; hold[i] = 220f }
                    else if (hold[i] > 0f) hold[i] -= dt
                    else peak[i] = maxOf(pos[i], peak[i] - 0.0011f * dt)
                }
                for (i in 0 until n) moving = maxOf(moving, pos[i], peak[i], kotlin.math.abs(vel[i]))
                tick.longValue = now
                !demo && !playing && moving < 0.004f
            }
            if (settled) break
        }
    }
    Box(
        modifier.drawBehind {
            tick.longValue // redraw on every new frame
            val w = size.width
            val h = size.height
            val gain = big.gain
            val sw = swell.floatValue
            val solid = when (tint) { VisualizerColor.SONG -> color; VisualizerColor.WHITE -> Color.White; VisualizerColor.RAINBOW -> Color.Unspecified }
            val rainbowBrush = if (tint == VisualizerColor.RAINBOW) Brush.horizontalGradient(rainbowList, 0f, w) else null
            // Top to bottom: full colour fading a little, like the bars always had.
            val brush = rainbowBrush ?: Brush.verticalGradient(
                listOf(solid, solid.copy(alpha = 0.45f + sw * 0.15f)),
                startY = 0f, endY = h,
            )
            val paint: Brush = rainbowBrush ?: SolidColor(solid)
            val pitch = w / n
            when (style) {
                VisualizerStyle.BARS, VisualizerStyle.MIRROR -> {
                    val gap = pitch * 0.34f
                    val bw = pitch - gap
                    val mirror = style == VisualizerStyle.MIRROR
                    for (i in 0 until n) {
                        val v = reach(0.05f + pos[i] * 0.95f, gain)
                        val bh = maxOf(bw, v * h)
                        val x = i * pitch + gap / 2
                        val y = if (mirror) (h - bh) / 2 else h - bh
                        drawRoundRect(brush, Offset(x, y), Size(bw, bh), CornerRadius(bw / 2))
                    }
                }
                VisualizerStyle.WAVE -> {
                    val stroke = 2.dp.toPx()
                    val top = stroke
                    for (i in 0 until n) xs[i] = w * i / (n - 1)
                    // A faint echo of the recent highs behind the wave.
                    for (i in 0 until n) ys[i] = h - stroke / 2 - reach(0.03f + peak[i] * 0.97f, gain) * (h - top)
                    line.reset(); line.smooth(xs, ys, n, start = true)
                    drawPath(line, paint, alpha = 0.3f, style = Stroke(stroke * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    for (i in 0 until n) ys[i] = h - stroke / 2 - reach(0.04f + pos[i] * 0.96f, gain) * (h - top)
                    line.reset(); line.smooth(xs, ys, n, start = true)
                    area.reset(); area.smooth(xs, ys, n, start = true)
                    area.lineTo(w, h); area.lineTo(0f, h); area.close()
                    if (rainbowBrush != null) drawPath(area, rainbowBrush, alpha = 0.28f)
                    else drawPath(area, Brush.verticalGradient(listOf(solid.copy(alpha = 0.42f + sw * 0.12f), solid.copy(alpha = 0.03f)), startY = top, endY = h))
                    drawPath(line, paint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                VisualizerStyle.LINE -> {
                    val stroke = 1.5.dp.toPx()
                    val mid = h / 2
                    val half = mid - stroke / 2
                    for (i in 0 until n) xs[i] = w * i / (n - 1)
                    // The band between the two lines, softly filled; then the lines themselves.
                    for (i in 0 until n) ys[i] = mid - reach(0.03f + pos[i] * 0.97f, gain) * half
                    line.reset(); line.smooth(xs, ys, n, start = true)
                    area.reset(); area.smooth(xs, ys, n, start = true)
                    for (i in 0 until n) ys[i] = h - ys[i]
                    area.smooth(xs, ys, n, start = false, reverse = true); area.close()
                    line.smooth(xs, ys, n, start = true)
                    drawPath(area, paint, alpha = 0.16f + sw * 0.08f)
                    drawPath(line, paint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                VisualizerStyle.DOTS -> {
                    val rows = (h / 7.dp.toPx()).roundToInt().coerceIn(3, 6)
                    val step = h / rows
                    val r = minOf(pitch * 0.55f, step * 0.78f) / 2
                    for (i in 0 until n) {
                        val c = if (tint == VisualizerColor.RAINBOW) rainbow[i] else solid
                        val lit = reach(0.08f + pos[i] * 0.92f, gain) * rows
                        val x = i * pitch + pitch / 2
                        for (k in 0 until rows) {
                            val on = (lit - k).coerceIn(0f, 1f)
                            drawCircle(c, r, Offset(x, h - step * (k + 0.5f)), alpha = 0.13f + 0.87f * on)
                        }
                    }
                }
                VisualizerStyle.GLOW -> {
                    for (i in 0 until n) {
                        val v = reach(pos[i], gain)
                        val x = i * pitch + pitch / 2
                        val rad = h * (0.2f + 0.6f * v) + pitch * 0.4f
                        val y = h * (0.68f - 0.4f * v)
                        val glow = glows[if (glows.size > 1) i else 0]
                        translate(x, y) {
                            scale(rad / GLOW_R, rad / GLOW_R, pivot = Offset.Zero) {
                                drawCircle(glow, GLOW_R, Offset.Zero, alpha = 0.14f + 0.66f * v)
                            }
                        }
                    }
                    // A bright little light at the heart of each glow.
                    for (i in 0 until n) {
                        val v = reach(pos[i], gain)
                        val c = if (tint == VisualizerColor.RAINBOW) rainbow[i] else solid
                        val x = i * pitch + pitch / 2
                        val y = h * (0.68f - 0.4f * v)
                        drawCircle(lerpWhite(c, 0.35f), 1.2.dp.toPx() + v * 2.dp.toPx(), Offset(x, y), alpha = 0.35f + 0.65f * v)
                    }
                }
                VisualizerStyle.PEAKS -> {
                    val bw = pitch * 0.42f
                    val capH = 2.dp.toPx()
                    val air = 1.5.dp.toPx()
                    val room = h - capH - air
                    for (i in 0 until n) {
                        val x = i * pitch + (pitch - bw) / 2
                        val bh = maxOf(1.dp.toPx(), reach(pos[i], gain) * room)
                        drawRoundRect(brush, Offset(x, h - bh), Size(bw, bh), CornerRadius(bw / 2), alpha = 0.75f)
                        val cy = (h - reach(peak[i], gain) * room - air - capH).coerceAtLeast(0f)
                        val c = if (tint == VisualizerColor.RAINBOW) rainbow[i] else solid
                        drawRoundRect(lerpWhite(c, 0.25f), Offset(x - pitch * 0.06f, cy), Size(bw + pitch * 0.12f, capH), CornerRadius(capH / 2))
                    }
                }
                VisualizerStyle.OFF -> Unit
            }
        },
    )
}

/** The radius Glow's lights are made at, before they're stretched. */
private const val GLOW_R = 100f

/** How far a band reaches for the chosen size; the usual size is left exactly as it was. */
private fun reach(v: Float, gain: Float): Float {
    val x = v * gain
    return if (gain <= 1f) x else (if (x < 0.8f) x else 0.8f + (x - 0.8f) * 0.55f).coerceAtMost(1f)
}

private fun lerpWhite(c: Color, t: Float) = Color(
    c.red + (1f - c.red) * t, c.green + (1f - c.green) * t, c.blue + (1f - c.blue) * t, c.alpha,
)

/** A smooth line through the points, added to the path (backwards with [reverse]). */
private fun Path.smooth(x: FloatArray, y: FloatArray, n: Int, start: Boolean, reverse: Boolean = false) {
    fun at(k: Int) = if (reverse) n - 1 - k else k
    val a = at(0)
    if (start) moveTo(x[a], y[a]) else lineTo(x[a], y[a])
    for (k in 1 until n - 1) {
        val p = at(k)
        val q = at(k + 1)
        quadraticTo(x[p], y[p], (x[p] + x[q]) / 2, (y[p] + y[q]) / 2)
    }
    val z = at(n - 1)
    lineTo(x[z], y[z])
}

/** A made-up beat for the Settings preview when nothing is playing: a kick, a snare and a drifting tune. */
private fun fakeSpectrum(into: FloatArray, ms: Long): Boolean {
    val t = (ms % 600_000L) / 1000f
    val beat = t * 2f // 120 beats a minute
    val kick = exp(-(beat % 1f) * 3.5f)
    val snare = if (beat.toInt() % 2 == 1) exp(-(beat % 1f) * 5f) else 0f
    val last = into.size - 1
    for (i in into.indices) {
        val x = i.toFloat() / last
        val tune = 0.16f * sin(t * 1.7f + x * 7f) + 0.1f * sin(t * 3.1f - x * 11f)
        val low = 0.62f * kick * (1f - x) * (1f - x)
        val mid = 0.36f * snare * exp(-(x - 0.55f) * (x - 0.55f) * 10f)
        val hats = 0.1f * exp(-((t * 4f) % 1f) * 10f) * x
        into[i] = (0.45f - 0.12f * x + tune + low + mid + hats).coerceIn(0f, 1f)
    }
    return true
}
