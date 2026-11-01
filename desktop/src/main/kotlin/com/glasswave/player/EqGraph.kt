// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/** Equalizer sounds you saved yourself ("Save" under the equalizer), kept on this device. */
internal object EqCustom {
    private var prefs: SharedPreferences? = null

    /** Name and band gains (dB), in the order they were saved. */
    var presets by mutableStateOf<List<Pair<String, FloatArray>>>(emptyList()); private set

    fun init() {
        prefs = android.content.AppContext.getSharedPreferences("eq_custom", 0)
        presets = runCatching {
            val arr = JSONArray(prefs?.getString("list", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val g = o.getJSONArray("g")
                o.getString("n") to FloatArray(g.length()) { g.getDouble(it).toFloat() }
            }
        }.getOrDefault(emptyList())
    }

    private fun write() {
        val arr = JSONArray()
        presets.forEach { (n, g) -> arr.put(JSONObject().put("n", n).put("g", JSONArray(g.map { it.toDouble() }))) }
        prefs?.edit()?.putString("list", arr.toString())?.apply()
    }

    fun save(name: String, gains: FloatArray) {
        presets = presets.filter { it.first != name } + (name to gains.copyOf())
        write()
    }

    fun delete(name: String) {
        presets = presets.filter { it.first != name }
        write()
    }

    /** The saved sound whose bands match the equalizer right now, if any. */
    fun matching(gains: FloatArray): String? = presets.firstOrNull { it.second.contentEquals(gains) }?.first

    fun apply(gains: FloatArray) {
        if (!Sound.eqOn) Sound.updateEqOn(true)
        gains.forEachIndexed { i, g -> if (i < Sound.BANDS.size) Sound.setBand(i, g) }
    }
}

// Same filter shape as SoundFx's equalizer, so the curve shows what you actually hear.
private const val EQ_Q = 1.4f
private const val FS = 48_000f
private const val LOW_HZ = 20f
private const val HIGH_HZ = 20_000f

/** How much (dB) the equalizer's bands together change the sound at [f] Hz. */
internal fun eqResponseDb(gains: FloatArray, f: Float): Float {
    var sum = 0f
    val w = 2f * PI.toFloat() * f / FS
    for (i in gains.indices) {
        val db = gains[i]
        if (db == 0f) continue
        val a = 10f.pow(db / 40f)
        val w0 = 2f * PI.toFloat() * Sound.BANDS[i] / FS
        val alpha = sin(w0) / (2f * EQ_Q)
        val c = cos(w0)
        val b0 = 1f + alpha * a; val b1 = -2f * c; val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a; val a1 = -2f * c; val a2 = 1f - alpha / a
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w); val ni = -(b1 * sin(w) + b2 * sin(2 * w))
        val dr = a0 + a1 * cos(w) + a2 * cos(2 * w); val di = -(a1 * sin(w) + a2 * sin(2 * w))
        sum += 20f * log10(hypot(nr, ni) / hypot(dr, di))
    }
    return sum
}

/** Where [f] Hz sits across the graph, 0..1 (low notes on the left, on a musical scale). */
private fun xOf(f: Float): Float = (ln(f / LOW_HZ) / ln(HIGH_HZ / LOW_HZ))

/**
 * The colours of the equalizer's points: while music plays they come from the song's cover (a
 * spread of shades around its colour, bright enough to stand out); with nothing playing it's plain
 * black and white.
 */
@Composable
internal fun eqColors(playing: Boolean): List<Color> {
    val base = readable(CoverTint.accent)
    val argb = base.toArgb()
    val hsb = java.awt.Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)
    return List(Sound.BANDS.size) { i ->
        val want = if (!playing) {
            Color.White
        } else {
            val hue = (hsb[0] * 360f - 45f + 90f * i / (Sound.BANDS.size - 1) + 360f) % 360f
            Color(java.awt.Color.HSBtoRGB(hue / 360f, hsb[1].coerceIn(0.45f, 0.8f), 0.97f) or (0xFF shl 24))
        }
        animateColorAsState(want, tween(600), label = "eqColor$i").value
    }
}

/**
 * The equalizer as a curve you draw on: one point per band, dragged up for more and down for less,
 * with the curve showing the sound that comes out. Tap or drag a point to pick it.
 */
@Composable
internal fun EqCurve(
    gains: FloatArray,
    selected: Int,
    colors: List<Color>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onSet: (Int, Float) -> Unit,
) {
    val max = Sound.MAX_DB
    val n = Sound.BANDS.size
    val pick by rememberUpdatedState(onSelect)
    val set by rememberUpdatedState(onSet)
    var dragging by androidx.compose.runtime.remember { mutableStateOf(-1) }
    fun nearest(x: Float, w: Float): Int = (0 until n).minBy { kotlin.math.abs(xOf(Sound.BANDS[it]) * w - x) }
    fun dbAt(y: Float, h: Float): Float = (Math.round((1f - (y / h).coerceIn(0f, 1f)) * 2 * max - max) * 2 / 2f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(210.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> pick(nearest(o.x, size.width.toFloat())) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> dragging = nearest(o.x, size.width.toFloat()); pick(dragging) },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                    onDrag = { ch, _ ->
                        ch.consume()
                        if (dragging >= 0) set(dragging, dbAt(ch.position.y, size.height.toFloat()))
                    },
                )
            }
            .drawBehind {
                val w = size.width
                val h = size.height
                val dim = if (enabled) 1f else 0.4f
                fun y(db: Float) = (1f - (db + max) / (2 * max)) * h
                // Lines every 6 dB (the middle one brighter: no change), and one at each band.
                for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
                    drawLine(Color.White.copy(alpha = if (db == 0f) 0.22f else 0.07f), Offset(0f, y(db)), Offset(w, y(db)), 1.dp.toPx())
                }
                for (i in 0 until n) {
                    val x = xOf(Sound.BANDS[i]) * w
                    drawLine(Color.White.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
                }
                // The curve: what the equalizer does across every note.
                val steps = 160
                val line = Path()
                val fill = Path()
                for (s in 0..steps) {
                    val f = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(s / steps.toFloat())
                    val px = s / steps.toFloat() * w
                    val py = y(eqResponseDb(gains, f).coerceIn(-max, max))
                    if (s == 0) { line.moveTo(px, py); fill.moveTo(px, y(0f)); fill.lineTo(px, py) } else { line.lineTo(px, py); fill.lineTo(px, py) }
                }
                fill.lineTo(w, y(0f))
                fill.close()
                val stops = colors.mapIndexed { i, c -> xOf(Sound.BANDS[i]) to c }.toTypedArray()
                drawPath(fill, Brush.horizontalGradient(*stops.map { it.first to it.second.copy(alpha = 0.16f * dim) }.toTypedArray(), endX = w))
                drawPath(line, Brush.horizontalGradient(*stops.map { it.first to it.second.copy(alpha = dim) }.toTypedArray(), endX = w), style = Stroke(2.5.dp.toPx()))
                // The points, one per band: a dark ring so they stand out over the curve.
                for (i in 0 until n) {
                    val c = Offset(xOf(Sound.BANDS[i]) * w, y(gains[i]))
                    val big = i == selected || i == dragging
                    val r = (if (big) 10.dp else 7.dp).toPx()
                    if (big) drawCircle(colors[i].copy(alpha = 0.25f * dim), r * 2f, c)
                    drawCircle(Color.Black.copy(alpha = 0.55f), r + 2.dp.toPx(), c)
                    drawCircle(colors[i].copy(alpha = dim), r, c)
                }
            },
    )
}

/** "31 Hz", "1 kHz"… */
internal fun hzLabel(f: Float): String = if (f >= 1000f) "${(f / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} kHz" else "${f.toInt()} Hz"

/** The bands as small chips, each sitting right under its point on the curve; the picked one is white. */
@Composable
internal fun EqChips(selected: Int, colors: List<Color>, onSelect: (Int) -> Unit) {
    androidx.compose.ui.layout.Layout(
        content = {
            Sound.BANDS.forEachIndexed { i, f ->
                val on = i == selected
                Box(
                    Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (on) Color.White else Color.Transparent)
                        .pressable(0.9f) { onSelect(i) }
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    val label = if (f >= 1000f) "${(f / 1000f).toInt()}k" else f.toInt().toString()
                    GText(label, 11, if (on) Color.Black else colors[i].copy(alpha = 0.85f), androidx.compose.ui.text.font.FontWeight.SemiBold, 1)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w, h) {
            placeables.forEachIndexed { i, p ->
                val x = (xOf(Sound.BANDS[i]) * w - p.width / 2f).toInt().coerceIn(0, w - p.width)
                p.place(x, 0)
            }
        }
    }
}
