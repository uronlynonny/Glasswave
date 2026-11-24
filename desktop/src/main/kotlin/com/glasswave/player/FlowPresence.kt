// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Flow, in the player. It sits over the cover (every player style draws its cover through one
 * place, so this works in all of them) and shows only while Flow is saying something: the cover
 * gives way to Flow's visualizer, the line comes up on it word by word as if spoken, and when
 * Flow is done the cover is back and it's the normal player again.
 */
@Composable
fun FlowPresence(ps: PlayerState, modifier: Modifier = Modifier) {
    val line = FlowTalk.speaking
    AnimatedVisibility(line != null, modifier, enter = fadeIn(smooth(250)), exit = fadeOut(smooth(250))) {
        // Keeps the last line while it fades away.
        var kept by remember { mutableStateOf(line) }
        if (line != null) kept = line
        val now = kept ?: return@AnimatedVisibility
        val accent = CoverTint.accent
        // How much of the line has been "said" so far (0..1): the words light up, and the wave talks, until it reaches 1.
        val said = remember(now.id) { Animatable(if (Settings.animations) 0f else 1f) }
        LaunchedEffect(now.id) { said.animateTo(1f, tween(now.sayMs.toInt(), easing = LinearEasing)) }
        val dark = Color(0xFF0B0D12)
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(dark, accent, 0.34f), lerp(dark, accent, 0.12f))))) {
            FlowWave(
                talking = line != null && said.value < 1f, sounding = ps.playing, thinking = Flow.building,
                color = accent, modifier = Modifier.fillMaxSize(),
            )
            val small = maxWidth < 300.dp
            Column(Modifier.align(BiasAlignment(-1f, if (small) -0.7f else -0.5f)).padding(horizontal = if (small) 14.dp else 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowLevels(accent, line != null && said.value < 1f, Modifier.size(if (small) 20.dp else 26.dp))
                    Spacer(Modifier.width(8.dp))
                    GText("FLOW", if (small) 11 else 12, accent, FontWeight.Bold, 1)
                }
                Spacer(Modifier.height(if (small) 6.dp else 10.dp))
                FlowWords(now, said.value, if (small) 15 else 23)
            }
        }
    }
}

/** The line, lit up as far as it has been said (whole words at a time); the rest waits, faint. */
@Composable
private fun FlowWords(line: FlowTalk.Line, said: Float, size: Int) {
    val text = line.text
    var upTo = (said * text.length).toInt().coerceIn(0, text.length)
    while (upTo < text.length && text[upTo] != ' ') upTo++
    val strong = line.strong
    val from = if (strong.isNullOrEmpty()) -1 else text.indexOf(strong)
    val to = if (from < 0 || strong == null) -1 else from + strong.length
    BasicText(
        buildAnnotatedString {
            // Cut where the said part ends and where the bold part starts and ends.
            val cuts = listOf(0, upTo, from, to, text.length).filter { it in 0..text.length }.distinct().sorted()
            for (k in 0 until cuts.size - 1) {
                val a = cuts[k]
                val b = cuts[k + 1]
                withStyle(
                    SpanStyle(
                        color = Color.White.copy(alpha = if (a < upTo) 1f else 0.26f),
                        fontWeight = if (from in 0..a && a < to) FontWeight.ExtraBold else FontWeight.SemiBold,
                    ),
                ) { append(text.substring(a, b)) }
            }
        },
        style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontFamily = AppFontFamily ?: TextFont, letterSpacing = if (size >= 22) (-0.3).sp else 0.sp),
    )
}

/**
 * Flow's visualizer: three layers of liquid, one behind the other, rolling slowly sideways.
 * [talking]: the middle of each layer rises and falls in bursts, like a voice.
 * [sounding]: the music lifts it too (the low notes in the middle of a layer, the high notes at its ends).
 * [thinking]: a calm ripple while Flow builds a set.
 * With none of them it settles into still water and stops redrawing.
 */
@Composable
internal fun FlowWave(talking: Boolean, sounding: Boolean, thinking: Boolean, color: Color, modifier: Modifier) {
    val n = Spectrum.BANDS
    val raw = remember { FloatArray(n) }
    val level = remember { FloatArray(n) }
    val phase = remember { mutableFloatStateOf(0.8f) }
    val clock = remember { mutableFloatStateOf(0f) }
    val energy = remember { mutableFloatStateOf(0f) }
    val frame = remember { mutableLongStateOf(0L) }
    val xs = remember { FloatArray(POINTS) }
    val ys = remember { FloatArray(POINTS) }
    val fill = remember { Path() }
    val crest = remember { Path() }
    val live = (talking || sounding || thinking) && Settings.animations

    DisposableEffect(sounding) {
        if (sounding) Spectrum.watch()
        onDispose { if (sounding) Spectrum.unwatch() }
    }
    LaunchedEffect(live, talking, sounding, thinking) {
        var last = 0L
        while (true) {
            val moving = withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 64f)
                last = now
                val f = dt / 16f
                val t = clock.floatValue + dt / 1000f
                clock.floatValue = t
                val got = live && sounding && Spectrum.now(raw)
                if (!got) raw.fill(0f)
                // A voice: syllables a few times a second, in phrases, never quite the same twice.
                val voice = if (live && talking) (abs(sin(t * 7.3f)) * (0.55f + 0.45f * sin(t * 2.1f + 0.7f)) * (0.7f + 0.3f * sin(t * 13.7f))).coerceIn(0f, 1f) else 0f
                var sum = 0f
                var still = true
                for (i in 0 until n) {
                    // Blended with its neighbours, so the surface moves as one.
                    var v = (raw[i] * 0.5f + (raw[max(i - 1, 0)] + raw[min(i + 1, n - 1)]) * 0.25f) * (if (talking) 0.4f else 0.75f)
                    if (voice > 0f) v = max(v, (0.12f + 0.88f * voice) * exp(-(i / 9f).pow(2)))
                    if (live && thinking && !talking) v = max(v, 0.3f + 0.16f * sin(t * 2.4f + i * 0.5f))
                    val p = level[i]
                    val q = p + (v - p) * (1f - (if (v > p) 0.6f else 0.9f).pow(f))
                    level[i] = q
                    sum += q
                    if (abs(q - v) > 0.003f) still = false
                }
                energy.floatValue = sum / n
                if (live) phase.floatValue = (phase.floatValue + dt / 1000f * (0.6f + energy.floatValue * 1.5f)) % 6283.185f
                frame.longValue = now
                live || !still
            }
            if (!moving) break
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val redraw = frame.longValue
        val w = size.width
        val h = size.height
        val ph = phase.floatValue
        val roll = h * (0.025f + 0.03f * energy.floatValue)
        for (layer in 0 until 3) {
            val middle = WAVE_MIDDLE[layer]
            val base = h * WAVE_BASE[layer]
            val reach = h * WAVE_REACH[layer]
            for (k in 0 until POINTS) {
                val x = k / (POINTS - 1f)
                val d = abs(x - middle) / max(middle, 1f - middle)
                val at = d * (n - 1) * 0.8f
                val i = at.toInt().coerceIn(0, n - 1)
                val v = level[i] + (level[min(i + 1, n - 1)] - level[i]) * (at - i)
                xs[k] = x * w
                ys[k] = base - reach * v * (1f - 0.3f * d) - roll * sin(ph * (1f + layer * 0.37f) + x * (5.2f + layer * 1.7f) + layer * 2.1f)
            }
            crest.rewind()
            crest.moveTo(xs[0], ys[0])
            for (k in 1 until POINTS - 1) crest.quadraticTo(xs[k], ys[k], (xs[k] + xs[k + 1]) / 2, (ys[k] + ys[k + 1]) / 2)
            crest.lineTo(xs[POINTS - 1], ys[POINTS - 1])
            fill.rewind()
            fill.addPath(crest)
            fill.lineTo(w, h)
            fill.lineTo(0f, h)
            fill.close()
            val a = WAVE_ALPHA[layer]
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = a), color.copy(alpha = a * 0.3f)), startY = base - reach, endY = h))
            // Light catching the top of each wave, brightest on the front one.
            drawPath(crest, Color.White.copy(alpha = 0.14f + 0.2f * layer), style = Stroke(1.dp.toPx()))
        }
    }
}

private const val POINTS = 16
private val WAVE_MIDDLE = floatArrayOf(0.66f, 0.32f, 0.5f)
private val WAVE_BASE = floatArrayOf(0.66f, 0.76f, 0.87f)
private val WAVE_REACH = floatArrayOf(0.34f, 0.34f, 0.32f)
private val WAVE_ALPHA = floatArrayOf(0.24f, 0.36f, 0.62f)
