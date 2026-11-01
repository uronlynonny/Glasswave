// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Small moving pictures for the sound settings, one per feature (the "Audio Lab" look). Each one
 * shows what its setting does and changes with it: wider orbits for wider spatial sound, a longer
 * slower wave for Slowed, further-reaching ripples for more reverb, an even waveform with volume
 * leveling on...
 *
 * How to use them:
 * - Each takes `playing` (is music playing right now). They move gently while it plays, drift
 *   slowly while it doesn't, and stand still when Settings > Animations is off.
 * - Their setting is read from [Sound] / [Settings] by default (the parameters after `modifier`),
 *   so `SpatialArt(playing)` is enough. Pass a value yourself to show something else.
 * - They fill the width, are [SoundArtHeight] tall unless your modifier sets a height, and clip
 *   themselves to rounded corners with a faint glass tint, so put them straight on a dark card.
 * - Drawing happens only in the draw phase (no recomposing every frame), with plain shapes and
 *   gradients, no blur.
 */

/** How tall the pictures are unless the modifier you pass sets a height. */
val SoundArtHeight = 136.dp

internal val ArtShape = RoundedCornerShape(18.dp)

/** How fast the pictures drift when nothing is playing (1 = while playing). */
internal const val IDLE_RATE = 0.22f

/** The picture time used for the still frame when animations are off. */
internal const val STILL_T = 2.3f

/** A picture's clock and the music's beat. Read them while drawing, so only the drawing repeats. */
@Stable
class ArtMotion internal constructor() {
    internal val clock = mutableFloatStateOf(STILL_T)
    internal val beat = mutableFloatStateOf(0f)

    /** Seconds of picture time: full speed while music plays, slow when it doesn't, still with animations off. */
    val t: Float get() = clock.floatValue

    /** 0..1: how hard the music is hitting right now (0 when nothing plays). */
    val pulse: Float get() = beat.floatValue
}

/** Runs the clock and listens to the live sound (see [Spectrum]) while music plays. */
@Composable
fun rememberArtMotion(playing: Boolean): ArtMotion {
    val m = remember { ArtMotion() }
    val alive = Settings.animations
    val playingNow by rememberUpdatedState(playing)
    val listen = playing && alive
    DisposableEffect(listen) {
        if (listen) Spectrum.watch()
        onDispose { if (listen) Spectrum.unwatch() }
    }
    LaunchedEffect(alive) {
        if (!alive) {
            m.clock.floatValue = STILL_T
            m.beat.floatValue = 0f
            return@LaunchedEffect
        }
        val raw = FloatArray(Spectrum.BANDS)
        var last = 0L
        var rate = if (playingNow) 1f else IDLE_RATE
        var usual = 0.35f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.064f)
                last = now
                val f = dt / 0.016f
                // Speed up and slow down softly when the music starts or stops.
                val target = if (playingNow) 1f else IDLE_RATE
                rate += (target - rate) * (1f - 0.95f.pow(f))
                m.clock.floatValue += dt * rate

                // The beat: the low notes against their usual level, quick to rise, slow to fall.
                var hit = 0f
                if (playingNow && Spectrum.now(raw)) {
                    var e = 0f
                    for (i in 0 until 8) e += raw[i]
                    e /= 8f
                    usual += (e - usual) * (1f - 0.985f.pow(f))
                    hit = ((e - usual) * 3f + e * 0.35f).coerceIn(0f, 1f)
                }
                val b = m.beat.floatValue
                m.beat.floatValue = b + (hit - b) * (1f - (if (hit > b) 0.5f else 0.9f).pow(f))
            }
        }
    }
    return m
}

/**
 * Something that moves at a speed that can change. (A plain `t * speed` would leap when the speed
 * changes.) Kept outside of Compose state: it's only stepped while drawing.
 */
internal class Phase(var value: Float) {
    private var lastT = Float.NaN

    fun at(t: Float, speed: Float): Float {
        if (!lastT.isNaN()) {
            val d = t - lastT
            if (d > 0f && d < 1f) value += d * speed
        }
        lastT = t
        return value
    }
}

/** Setting changes glide into the picture (or jump, with animations off). */
@Composable
internal fun artSpec(): AnimationSpec<Float> =
    if (Settings.animations) tween(700, easing = FastOutSlowInEasing) else snap()

internal fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

internal fun smoothstep(a: Float, b: Float, x: Float): Float {
    val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/** A steady 0..1 "random" number for each whole number. */
internal fun hash(k: Int): Float {
    var x = k * 0x27d4eb2d
    x = x xor (x ushr 15)
    x *= 0x2c1b3c6d
    x = x xor (x ushr 12)
    return (x ushr 8) / 16777216f
}

@Composable
internal fun ArtCanvas(modifier: Modifier, height: androidx.compose.ui.unit.Dp = SoundArtHeight, onDraw: DrawScope.() -> Unit) {
    Canvas(modifier.fillMaxWidth().height(height).clip(ArtShape), onDraw = onDraw)
}

/** How tall the pictures on the other settings pages are (a little shorter than the sound ones). */
val SettingArtHeight = 112.dp

/** The faint glass tint and a soft glow of the accent behind each picture. */
internal fun DrawScope.backdrop(accent: Color, glowX: Float = 0.5f, glow: Float = 1f) {
    drawRect(Color.White.copy(alpha = 0.035f))
    val r = max(size.width * 0.55f, size.height)
    drawRect(
        Brush.radialGradient(
            listOf(accent.copy(alpha = (0.13f * glow).coerceIn(0f, 1f)), Color.Transparent),
            center = Offset(size.width * glowX, size.height * 0.5f),
            radius = r,
        ),
    )
}

/** A soft glow with a bright centre. */
internal fun DrawScope.glow(c: Color, at: Offset, r: Float, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(c.copy(alpha = (0.4f * alpha).coerceIn(0f, 1f)), Color.Transparent), center = at, radius = r * 3.2f),
        radius = r * 3.2f, center = at,
    )
}

/* ---------------- Spatial sound ---------------- */

/**
 * A head with headphones and glowing dots circling it: a tight huddle when spatial sound is Off,
 * spreading wider for Subtle, Natural and Wide. Dots going behind the head dim.
 */
@Composable
fun SpatialArt(playing: Boolean, modifier: Modifier = Modifier, spatial: Spatial = Sound.spatial) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val spread by animateFloatAsState(
        when (spatial) {
            Spatial.OFF -> 0f
            Spatial.SUBTLE -> 0.38f
            Spatial.NATURAL -> 0.7f
            Spatial.WIDE -> 1f
        },
        artSpec(), label = "spread",
    )
    val phase = remember { Phase(0.6f) }
    ArtCanvas(modifier) {
        val w = size.width
        val h = size.height
        val c = center
        val s = spread
        val pulse = m.pulse
        backdrop(accent, glow = 0.6f + 0.7f * s)
        val head = 13.dp.toPx()
        val maxRx = min(w * 0.44f, h * 1.7f)
        // Wider stages turn a little slower, like sound in a bigger space.
        val turn = phase.at(m.t, 0.62f - 0.2f * s)

        // The orbit lines, faint.
        for (k in 0 until 3) {
            val (rx, ry) = orbit(k, s, head, maxRx, h)
            rotate((k - 1) * 4f * s, c) {
                drawOval(
                    Color.White.copy(alpha = 0.05f + 0.05f * s),
                    topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2, ry * 2),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }

        // Dots behind the head first, then the head, then the ones in front.
        for (front in 0..1) {
            if (front == 1) drawHead(c, head, accent, pulse)
            for (k in 0 until 3) {
                val (rx, ry) = orbit(k, s, head, maxRx, h)
                val n = k + 2
                val tilt = ((k - 1) * 4f * s) * (PI.toFloat() / 180f)
                for (i in 0 until n) {
                    val a0 = turn * (1f + 0.22f * k) + i * 2f * PI.toFloat() / n + k * 1.3f
                    val z = sin(a0)
                    if ((z >= 0f) != (front == 1)) continue
                    val near = (z + 1f) / 2f
                    val r = mix(2.2f, 3.4f, s).dp.toPx() * (0.7f + 0.45f * near) + pulse * 1.2.dp.toPx()
                    val alpha = 0.35f + 0.65f * near
                    // A short fading trail behind each dot.
                    for (j in 4 downTo 1) {
                        val p = orbitPoint(c, a0 - j * (0.07f + 0.06f * s), rx, ry, tilt)
                        drawCircle(accent.copy(alpha = alpha * (0.32f - j * 0.06f)), r * (1f - j * 0.12f), p)
                    }
                    val p = orbitPoint(c, a0, rx, ry, tilt)
                    glow(accent, p, r, alpha * (0.8f + 0.4f * pulse))
                    drawCircle(lerp(accent, Color.White, 0.25f + 0.45f * near).copy(alpha = alpha), r, p)
                }
            }
        }
    }
}

/** The size of orbit [k] (0 = inner): all close to the head when [s] is 0, spread out at 1. */
private fun DrawScope.orbit(k: Int, s: Float, head: Float, maxRx: Float, h: Float): Pair<Float, Float> {
    val ring = (k + 1) / 3f
    val tight = head + 8.dp.toPx() + k * 4.dp.toPx()
    val rx = mix(tight, maxRx * (0.42f + 0.58f * ring), s)
    val ry = mix(tight * 0.85f, h * (0.13f + 0.16f * ring), s)
    return rx to ry
}

private fun orbitPoint(c: Offset, a: Float, rx: Float, ry: Float, tilt: Float): Offset {
    val x = cos(a) * rx
    val y = sin(a) * ry
    return Offset(c.x + x * cos(tilt) - y * sin(tilt), c.y + x * sin(tilt) + y * cos(tilt))
}

private fun DrawScope.drawHead(c: Offset, head: Float, accent: Color, pulse: Float) {
    glow(accent, c, head * (0.9f + 0.25f * pulse), 0.55f)
    drawCircle(Color.White.copy(alpha = 0.9f), head, c)
    // Headphones: the band over the top and a cup on each side.
    val band = head + 4.dp.toPx()
    drawArc(
        accent, 200f, 140f, false,
        topLeft = Offset(c.x - band, c.y - band), size = Size(band * 2, band * 2),
        style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round),
    )
    val cupW = 5.dp.toPx()
    val cupH = 13.dp.toPx()
    for (side in listOf(-1f, 1f)) {
        val x = if (side < 0) c.x - head - cupW + 1.dp.toPx() else c.x + head - 1.dp.toPx()
        drawRoundRect(accent, Offset(x, c.y - cupH / 2 - 1.dp.toPx()), Size(cupW, cupH), CornerRadius(cupW / 2))
    }
}

/* ---------------- Speed ---------------- */

/**
 * A flowing wave: longer, slower and a little lower for Slowed, shorter, quicker and higher for
 * Sped up. Slowed + reverb adds a soft echo trailing behind it.
 */
@Composable
fun SpeedArt(playing: Boolean, modifier: Modifier = Modifier, preset: SpeedPreset = Sound.speedPreset) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val speed by animateFloatAsState(preset.speed, artSpec(), label = "speed")
    val echo by animateFloatAsState(if (preset.reverb > 0f) 1f else 0f, artSpec(), label = "echo")
    val phase = remember { Phase(1.1f) }
    val paths = remember { Array(7) { Path() } }
    ArtCanvas(modifier) {
        val w = size.width
        val h = size.height
        val sp = speed
        val pulse = m.pulse
        backdrop(accent)
        val lam = 92.dp.toPx() / sp.pow(1.6f)
        val flow = phase.at(m.t, 2.2f * sp.pow(2.2f))
        val amp = h * 0.2f / sp.pow(0.8f) * (1f + 0.25f * pulse)
        // Slower sits lower, faster sits higher, like the pitch.
        val mid = h / 2 + (1f - sp) * h * 0.3f
        val step = 3.dp.toPx()

        fun wave(p: Path, a: Float, ph: Float, wobble: Float) {
            p.reset()
            var x = 0f
            while (true) {
                val u = x / w
                val env = sin(PI.toFloat() * u).coerceAtLeast(0f).pow(1.3f)
                val k = (x - w / 2) / lam * 2f * PI.toFloat()
                val y = mid + env * a * (0.82f * sin(k - ph) + wobble * sin(2.3f * k - 1.7f * ph + 1f))
                if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y)
                if (x >= w) break
                x = min(x + step, w)
            }
        }

        // The echo, trailing behind and fading.
        if (echo > 0.01f) {
            for (j in 3 downTo 1) {
                val p = paths[3 + j]
                wave(p, amp * (1f - 0.1f * j), flow - j * 0.6f, 0.18f)
                drawPath(p, accent.copy(alpha = 0.17f * echo * (1f - (j - 1) * 0.3f)), style = Stroke((2.5f + j * 1.6f).dp.toPx(), cap = StrokeCap.Round))
            }
        }
        wave(paths[0], amp * 0.55f, flow * 0.8f + 2f, 0.3f)
        drawPath(paths[0], Color.White.copy(alpha = 0.13f), style = Stroke(1.4.dp.toPx()))
        wave(paths[1], amp * 0.8f, flow + 1.1f, 0.25f)
        drawPath(paths[1], Color.White.copy(alpha = 0.26f), style = Stroke(1.4.dp.toPx()))
        wave(paths[2], amp, flow, 0.18f)
        drawPath(paths[2], accent.copy(alpha = 0.2f + 0.15f * pulse), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(paths[2], accent, style = Stroke(mix(3.2f, 2.2f, (sp - 0.85f) / 0.4f).dp.toPx(), cap = StrokeCap.Round))
    }
}

/* ---------------- Reverb ---------------- */

/**
 * A small speaker with sound arcs spreading out of it. More reverb sends them further and lets them
 * ring longer; a bigger room (Small room, Concert hall, Arena) spreads them wider and slower.
 * With reverb off, only a couple of short dry arcs stay close to the speaker.
 */
@Composable
fun ReverbArt(playing: Boolean, modifier: Modifier = Modifier, reverb: Float = Sound.reverb, room: Room = Sound.room) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val wet by animateFloatAsState(reverb.coerceIn(0f, 1f), artSpec(), label = "wet")
    val big by animateFloatAsState(
        when (room) {
            Room.SMALL -> 0f
            Room.HALL -> 0.55f
            Room.ARENA -> 1f
        },
        artSpec(), label = "room",
    )
    val phase = remember { Phase(0.35f) }
    ArtCanvas(modifier) {
        val w = size.width
        val h = size.height
        val pulse = m.pulse
        val cy = h / 2
        val sx = max(34.dp.toPx(), w * 0.14f)
        backdrop(accent, glowX = 0.3f, glow = 0.7f + 0.5f * wet)

        val mouth = sx + 6.dp.toPx()
        val r0 = 9.dp.toPx()
        val roomF = wet * big
        val reach = mix(26.dp.toPx(), (w - mouth - 12.dp.toPx()) * mix(0.5f, 1f, big), wet)
        // How fast a ring fades: lower = it rings longer.
        val fade = mix(3f, 1.1f, wet * (0.45f + 0.55f * big))
        val period = mix(1.5f, 3.2f, roomF)
        val sweep = mix(70f, 108f, big)
        val n = 6
        val cycle = phase.at(m.t, 1f / period)
        for (i in 0 until n) {
            var a = cycle + i.toFloat() / n
            a -= floor(a)
            val r = r0 + a * reach
            val alpha = (1f - a).pow(fade) * smoothstep(0f, 0.06f, a) * (0.75f + 0.35f * pulse)
            if (alpha < 0.01f) continue
            val sw = mix(2.8f, 1.2f, a).dp.toPx()
            drawArc(
                accent.copy(alpha = alpha.coerceIn(0f, 1f)), -sweep / 2, sweep, false,
                topLeft = Offset(mouth - r, cy - r), size = Size(r * 2, r * 2),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            // With reverb, each ring has a fainter echo just behind it.
            if (wet > 0.02f) {
                val r2 = r - (4f + 6f * wet).dp.toPx()
                if (r2 > r0) {
                    drawArc(
                        Color.White.copy(alpha = (alpha * 0.45f * wet).coerceIn(0f, 1f)), -sweep / 2, sweep, false,
                        topLeft = Offset(mouth - r2, cy - r2), size = Size(r2 * 2, r2 * 2),
                        style = Stroke(sw * 0.7f, cap = StrokeCap.Round),
                    )
                }
            }
        }
        drawSpeaker(Offset(sx, cy), accent, pulse)
    }
}

/** A small speaker facing right, centred on [c]. */
private fun DrawScope.drawSpeaker(c: Offset, accent: Color, pulse: Float) {
    glow(accent, c, 10.dp.toPx() * (1f + 0.2f * pulse), 0.6f)
    val bodyW = 8.dp.toPx()
    val bodyH = 14.dp.toPx()
    drawRoundRect(
        Color.White.copy(alpha = 0.9f), Offset(c.x - 6.dp.toPx() - bodyW, c.y - bodyH / 2), Size(bodyW, bodyH),
        CornerRadius(2.5.dp.toPx()),
    )
    val cone = Path().apply {
        moveTo(c.x - 5.dp.toPx(), c.y - 5.dp.toPx())
        lineTo(c.x + 6.dp.toPx(), c.y - 12.dp.toPx())
        lineTo(c.x + 6.dp.toPx(), c.y + 12.dp.toPx())
        lineTo(c.x - 5.dp.toPx(), c.y + 5.dp.toPx())
        close()
    }
    drawPath(cone, Color.White.copy(alpha = 0.9f))
}

/* ---------------- Volume leveling ---------------- */

/**
 * A waveform drifting by, with loud and quiet stretches. With volume leveling on, it evens out to
 * one steady height between two faint guide lines.
 */
@Composable
fun LevelingArt(playing: Boolean, modifier: Modifier = Modifier, on: Boolean = Settings.volumeLeveling) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val lev by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "level")
    val scroll = remember { Phase(40f) }
    ArtCanvas(modifier) {
        val w = size.width
        val h = size.height
        val l = lev
        val pulse = m.pulse
        backdrop(accent)
        val pitch = 5.5.dp.toPx()
        val barW = 3.dp.toPx()
        val mid = h / 2
        val maxA = h * 0.4f
        val even = 0.5f
        val pos = scroll.at(m.t, 9f)
        val first = floor(pos).toInt()
        val frac = pos - first

        if (l > 0.01f) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            for (side in listOf(-1f, 1f)) {
                val y = mid + side * maxA * even * 1.06f
                drawLine(
                    Color.White.copy(alpha = 0.2f * l), Offset(10.dp.toPx(), y), Offset(w - 10.dp.toPx(), y),
                    1.dp.toPx(), pathEffect = dash,
                )
            }
        }

        val n = (w / pitch).toInt() + 2
        for (i in 0 until n) {
            val x = (i - frac) * pitch + pitch / 2
            val fadeIn = smoothstep(0f, 0.08f, x / w) * smoothstep(1f, 0.92f, x / w)
            if (fadeIn <= 0f) continue
            val k = first + i
            val jit = hash(k)
            // Loud and quiet stretches, slowly changing.
            val s1 = 0.5f + 0.5f * sin(k * 0.045f + 2f * sin(k * 0.013f))
            val s2 = 0.5f + 0.5f * sin(k * 0.11f + 1f)
            val loud = (0.75f * smoothstep(0f, 1f, s1) + 0.25f * s2).coerceIn(0f, 1f)
            val raw = (0.08f + 0.92f * loud) * (0.55f + 0.45f * jit)
            val flat = even * (0.86f + 0.14f * jit)
            val a = (mix(raw, flat, l) * (1f + 0.12f * pulse)).coerceAtMost(1f)
            // Without leveling, the too-loud peaks glow whiter.
            val hot = ((raw - even) * 2.2f).coerceIn(0f, 1f) * (1f - l)
            val col = lerp(accent, Color.White, hot * 0.6f)
            drawLine(
                col.copy(alpha = (0.4f + 0.55f * a) * fadeIn), Offset(x, mid - a * maxA), Offset(x, mid + a * maxA),
                barW, cap = StrokeCap.Round,
            )
        }
    }
}

/* ---------------- Bass, treble and stereo width ---------------- */

/**
 * A gently moving sound curve from low notes (left) to high notes (right). Bass boost lifts the left
 * end with a warm glow, treble boost lifts the right end with a few sparkles, and stereo width
 * spreads the whole curve wider, with faint copies to each side.
 */
@Composable
fun ToneArt(
    playing: Boolean, modifier: Modifier = Modifier,
    bass: Float = Sound.bass, treble: Float = Sound.treble, width: Float = Sound.width,
) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val lo by animateFloatAsState(bass.coerceIn(0f, 1f), artSpec(), label = "bass")
    val hi by animateFloatAsState(treble.coerceIn(0f, 1f), artSpec(), label = "treble")
    val wide by animateFloatAsState(width.coerceIn(0f, 1f), artSpec(), label = "width")
    val fill = remember { Path() }
    val line = remember { Path() }
    ArtCanvas(modifier) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        backdrop(accent)
        val span = w * mix(0.62f, 0.94f, wide)
        val left = (w - span) / 2
        val baseY = h * 0.78f
        val step = 3.dp.toPx()

        fun lift(u: Float): Float {
            val taper = smoothstep(0f, 0.06f, u) * smoothstep(1f, 0.94f, u)
            val ripple = 0.16f + 0.07f * (1f + 0.6f * pulse) *
                (0.6f * sin(u * 9f + t * 1.4f) + 0.4f * sin(u * 17f - t * 2.1f + 1f))
            val bump = lo * 0.36f * exp(-((u - 0.12f) / 0.13f).pow(2)) + hi * 0.3f * exp(-((u - 0.88f) / 0.13f).pow(2))
            return h * (ripple + bump) * taper
        }

        fun trace(p: Path, dx: Float, closed: Boolean) {
            p.reset()
            var x = 0f
            while (true) {
                val y = baseY - lift(x / span)
                if (x == 0f) p.moveTo(left + x + dx, y) else p.lineTo(left + x + dx, y)
                if (x >= span) break
                x = min(x + step, span)
            }
            if (closed) {
                p.lineTo(left + span + dx, baseY)
                p.lineTo(left + dx, baseY)
                p.close()
            }
        }

        // The bass glow, low on the left.
        if (lo > 0.01f) {
            val gr = h * (0.35f + 0.45f * lo) * (1f + 0.15f * pulse)
            val at = Offset(left + span * 0.12f, baseY)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.32f * lo), Color.Transparent), at, gr), gr, at)
        }
        if (hi > 0.01f) {
            val gr = h * (0.25f + 0.3f * hi)
            val at = Offset(left + span * 0.88f, baseY - h * 0.2f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f * hi), Color.Transparent), at, gr), gr, at)
        }

        drawLine(Color.White.copy(alpha = 0.1f), Offset(left, baseY), Offset(left + span, baseY), 1.dp.toPx())

        // Stereo width: faint copies of the curve to each side.
        if (wide > 0.01f) {
            for (side in listOf(-1f, 1f)) {
                trace(line, side * wide * 14.dp.toPx(), false)
                drawPath(line, Color.White.copy(alpha = 0.22f * wide), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        trace(fill, 0f, true)
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.02f)), startY = h * 0.2f, endY = baseY))
        trace(line, 0f, false)
        drawPath(line, accent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))

        // Treble sparkles, twinkling above the right end.
        if (hi > 0.01f) {
            for (i in 0 until 7) {
                val u = 0.76f + 0.2f * hash(i * 7 + 1)
                val x = left + span * u
                val y = baseY - lift(u) - (4f + 14f * hash(i * 7 + 3)).dp.toPx()
                val tw = 0.5f + 0.5f * sin(t * (2.2f + hash(i) * 2f) + i * 1.7f)
                drawCircle(Color.White.copy(alpha = (hi * tw * 0.85f).coerceIn(0f, 1f)), (1.2f + 1.3f * tw).dp.toPx(), Offset(x, y))
            }
        }
    }
}

/* ---------------- Balance and mono ---------------- */

/**
 * Two ear cups with the sound between them: it slides towards the left or right one with the
 * balance, and that side lights up. In stereo it's two glowing blobs (left and right); mono
 * merges them into one.
 */
@Composable
fun BalanceArt(playing: Boolean, modifier: Modifier = Modifier, balance: Float = Sound.balance, mono: Boolean = Sound.mono) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val bal by animateFloatAsState(balance.coerceIn(-1f, 1f), artSpec(), label = "balance")
    val merge by animateFloatAsState(if (mono) 1f else 0f, artSpec(), label = "mono")
    ArtCanvas(modifier) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        val cx = w / 2
        val cy = h / 2
        val d = min(w * 0.36f, 150.dp.toPx())
        backdrop(accent, glowX = 0.5f + bal * 0.2f)
        val leftGain = 1f - max(0f, bal)
        val rightGain = 1f + min(0f, bal)
        val bx = cx + bal * d * 0.7f
        val by = cy + 3.dp.toPx() * sin(t * 0.9f)

        // Sound travelling out to each ear, as much as that ear gets.
        val cupW = 16.dp.toPx()
        for (side in listOf(-1f, 1f)) {
            val gain = if (side < 0) leftGain else rightGain
            val ear = cx + side * d
            val dist = abs(ear - bx) - cupW
            if (gain < 0.02f || dist < 10.dp.toPx()) continue
            for (j in 0 until 3) {
                var f = t * 0.55f + j / 3f
                f -= floor(f)
                val r = 8.dp.toPx() + f * (dist - 8.dp.toPx())
                val alpha = gain * sin(PI.toFloat() * f) * 0.55f
                val start = if (side < 0) 180f - 28f else -28f
                drawArc(
                    accent.copy(alpha = alpha.coerceIn(0f, 1f)), start, 56f, false,
                    topLeft = Offset(bx - r, by - r), size = Size(r * 2, r * 2),
                    style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        // The ear cups.
        val cupH = 36.dp.toPx()
        for (side in listOf(-1f, 1f)) {
            val gain = if (side < 0) leftGain else rightGain
            val ear = Offset(cx + side * d, cy)
            glow(accent, ear, 11.dp.toPx() * (0.6f + 0.5f * gain) * (1f + 0.25f * pulse * gain), 0.75f * gain)
            val tl = Offset(ear.x - cupW / 2, ear.y - cupH / 2)
            drawRoundRect(Color.White.copy(alpha = 0.08f + 0.22f * gain), tl, Size(cupW, cupH), CornerRadius(cupW / 2))
            drawRoundRect(
                accent.copy(alpha = 0.25f + 0.65f * gain), tl, Size(cupW, cupH), CornerRadius(cupW / 2),
                style = Stroke(1.8.dp.toPx()),
            )
        }

        // The sound: two blobs (left and right) that merge in mono.
        val sep = 13.dp.toPx() * (1f - merge) * (1f + 0.15f * sin(t * 1.3f))
        val r = (6f + 1.5f * pulse).dp.toPx()
        val tint = lerp(accent, Color.White, 0.45f)
        for ((dx, c) in listOf(-sep to accent, sep to tint)) {
            val p = Offset(bx + dx, by + (1f - merge) * 2.dp.toPx() * sin(t * 1.1f + (if (dx < 0f) 0f else 2f)))
            glow(c, p, r * 1.3f, 0.8f)
            drawCircle(c.copy(alpha = 0.92f), r, p)
        }
    }
}
