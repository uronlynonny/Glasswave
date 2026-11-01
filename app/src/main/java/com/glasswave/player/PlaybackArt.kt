// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/*
 * Small moving pictures for the other settings (audio quality, data saver, automix, playback, and on
 * the PC the mini player and Discord), in the same look as the Audio Lab ones in SoundArt.kt and
 * using its helpers (rememberArtMotion, Phase, artSpec, ArtCanvas, backdrop, glow...).
 *
 * - Each takes `playing` (is music playing right now): they move gently while it plays, drift slowly
 *   while it doesn't, and stand still when Settings > Animations is off.
 * - Unlike the sound ones, the setting is always passed in (nothing is read from Settings here), so
 *   the same file works in the phone and the PC copies of the app.
 * - They fill the width and are [SettingArtHeight] tall. Drawing happens only in the draw phase, with
 *   plain shapes, no blur and no pictures.
 */

/** How long each song in the Playback picture plays before the queue moves on (picture seconds). */
private const val PB_SONG_TIME = 2.8f

/** How many songs are in the Playback picture's queue when autoplay is off. */
private const val PB_QUEUE = 4

/** Whole-number division that rounds down, also for negative numbers ([b] must be above 0). */
private fun pbFloorDiv(a: Int, b: Int): Int {
    val q = a / b
    return if (a % b != 0 && a < 0) q - 1 else q
}

/** A short rounded line, the "text" in the little cards. */
private fun DrawScope.pbTextLine(x: Float, y: Float, length: Float, thick: Float, c: Color) {
    if (length <= 0f) return
    drawLine(c, Offset(x, y), Offset(x + length, y), thick, cap = StrokeCap.Round)
}

/** A square "cover" with a soft accent gradient. */
private fun DrawScope.pbCover(tl: Offset, side: Float, accent: Color, alpha: Float, corner: Float) {
    drawRoundRect(
        Brush.linearGradient(
            listOf(lerp(accent, Color.White, 0.3f).copy(alpha = 0.95f * alpha), accent.copy(alpha = 0.45f * alpha)),
            start = tl, end = tl + Offset(side, side),
        ),
        tl, Size(side, side), CornerRadius(corner),
    )
}

/** A progress bar from [x0] to [x1]: a faint track with the played part in the accent. */
private fun DrawScope.pbProgress(x0: Float, x1: Float, y: Float, done: Float, accent: Color, alpha: Float, thick: Float) {
    if (x1 <= x0) return
    drawLine(Color.White.copy(alpha = 0.14f * alpha), Offset(x0, y), Offset(x1, y), thick, cap = StrokeCap.Round)
    val x = x0 + (x1 - x0) * done.coerceIn(0f, 1f)
    drawLine(accent.copy(alpha = alpha), Offset(x0, y), Offset(x, y), thick, cap = StrokeCap.Round)
}

/* ---------------- Audio quality ---------------- */

/**
 * A wave scrolling by: the real music as a faint white line, and what you hear on top in the accent.
 * At Low the heard wave is blocky, in big steps with its sample points showing; Normal has finer
 * steps; at High it follows the music smoothly.
 */
@Composable
fun AudioQualityArt(playing: Boolean, quality: Quality, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val detail by animateFloatAsState(
        when (quality) {
            Quality.LOW -> 0f
            Quality.NORMAL -> 0.55f
            Quality.HIGH -> 1f
        },
        artSpec(), label = "detail",
    )
    val scroll = remember { Phase(0f) }
    val truth = remember { Path() }
    val heard = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val d = detail
        val t = m.t
        val pulse = m.pulse
        val unit = 1.dp.toPx()
        backdrop(accent, glow = 0.6f + 0.5f * d)
        val mid = h / 2
        val amp = h * 0.32f * (1f + 0.15f * pulse)
        val lam = 64.dp.toPx()
        val pos = scroll.at(t, 24f) * unit

        // The music at point x along it (x - pos is where that is on screen): -1..1, quiet at both ends.
        fun signal(x: Float): Float {
            val k = x / lam * 2f * PI.toFloat()
            val env = sin(PI.toFloat() * ((x - pos) / w).coerceIn(0f, 1f)).coerceAtLeast(0f).pow(0.7f)
            return env * (0.6f * sin(k) + 0.28f * sin(2.3f * k + t * 0.6f + 1f) + 0.12f * sin(4.7f * k - t * 0.9f))
        }

        // The real music, smooth.
        truth.reset()
        val step = 2.dp.toPx()
        var x = 0f
        while (true) {
            val y = mid - amp * signal(x + pos)
            if (x == 0f) truth.moveTo(x, y) else truth.lineTo(x, y)
            if (x >= w) break
            x = min(x + step, w)
        }

        // What you hear: samples further apart and rounded to fewer levels at lower quality.
        val hold = mix(12f, 2.2f, d) * unit
        val levels = mix(3.5f, 20f, d)
        val flat = 1f - smoothstep(0.5f, 1f, d) // 1 = flat steps, 0 = samples joined smoothly
        val exact = smoothstep(0.6f, 1f, d)
        val first = floor(pos / hold).toInt()
        val n = (w / hold).toInt() + 2
        fun sampleX(i: Int) = (first + i) * hold - pos
        fun sampleY(i: Int): Float {
            val v = signal((first + i) * hold)
            return mid - amp * mix(round(v * levels) / levels, v, exact)
        }
        heard.reset()
        for (i in 0..n) {
            val sx = sampleX(i)
            val sy = sampleY(i)
            if (i == 0) heard.moveTo(sx, sy) else heard.lineTo(sx, sy)
            heard.lineTo(sx + hold * flat, sy)
        }

        drawPath(truth, Color.White.copy(alpha = 0.18f), style = Stroke(1.2.dp.toPx()))
        drawPath(
            heard, accent.copy(alpha = 0.18f + 0.12f * pulse),
            style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(heard, accent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // At low quality, the few sample points show as dots.
        val dots = 1f - smoothstep(0f, 0.6f, d)
        if (dots > 0.02f) {
            val dotColor = lerp(accent, Color.White, 0.55f).copy(alpha = 0.8f * dots)
            for (i in 0..n) drawCircle(dotColor, 2.2.dp.toPx(), Offset(sampleX(i), sampleY(i)))
        }
    }
}

/* ---------------- Data saver ---------------- */

/**
 * Little dots of data flowing out of a cloud into a phone, whose small meter shows how much data is
 * used. With data saver on, the stream thins out to a few dots in a narrower line and the meter drops.
 */
@Composable
fun DataSaverArt(playing: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val save by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "save")
    val flow = remember { Phase(0f) }
    val cloudPath = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val s = save
        val t = m.t
        val pulse = m.pulse
        val unit = 1.dp.toPx()
        val cy = h / 2
        backdrop(accent, glowX = 0.7f, glow = 1f - 0.4f * s)

        val cloud = Offset(max(34.dp.toPx(), w * 0.13f), cy)
        val phoneW = 26.dp.toPx()
        val phoneH = 50.dp.toPx()
        val phoneCx = w - max(32.dp.toPx(), w * 0.12f)
        val phoneLeft = phoneCx - phoneW / 2

        // The stream: three lanes of dots that fan out from the cloud and gather again into the phone.
        val start = cloud.x + 14.dp.toPx()
        val end = phoneLeft + 3.dp.toPx()
        val span = end - start
        val pitch = 13.dp.toPx()
        val pos = flow.at(t, mix(62f, 50f, s)) * unit
        val keep = mix(1f, 0.28f, s) // how many of the dots are still sent
        val r = mix(2.4f, 2f, s).dp.toPx() * (1f + 0.25f * pulse)
        val dotColor = lerp(accent, Color.White, 0.3f)
        if (span > pitch) {
            for (lane in 0 until 3) {
                val p = pos + lane * pitch * 0.37f
                val first = floor(p / pitch).toInt()
                val frac = p / pitch - first
                val laneY = (lane - 1) * 15.dp.toPx() * mix(1f, 0.4f, s)
                val n = (span / pitch).toInt() + 2
                for (i in 0..n) {
                    val x = start + (i + frac - 1f) * pitch
                    if (x < start || x > end) continue
                    // Each dot keeps the same number as it moves, so it stays shown or hidden.
                    val id = i - first + lane * 7919
                    val shown = smoothstep(0f, 0.12f, keep - hash(id))
                    if (shown <= 0f) continue
                    val u = (x - start) / span
                    val open = sin(PI.toFloat() * u).coerceAtLeast(0f)
                    val y = cy + laneY * open + 3.dp.toPx() * open * sin(u * 7f + t * 1.6f + lane)
                    val fade = smoothstep(0f, 0.1f, u) * smoothstep(1f, 0.9f, u)
                    val a = (shown * fade * (0.55f + 0.4f * hash(id + 3))).coerceIn(0f, 1f)
                    if (a < 0.01f) continue
                    val at = Offset(x, y)
                    glow(accent, at, r, a * 0.45f)
                    drawCircle(dotColor.copy(alpha = a), r, at)
                }
            }
        }

        // The cloud: a few round puffs on a flat base, drawn as one shape so the overlaps don't show.
        cloudPath.reset()
        cloudPath.addRoundRect(
            RoundRect(
                cloud.x - 16 * unit, cloud.y - 2 * unit, cloud.x + 16 * unit, cloud.y + 8 * unit,
                CornerRadius(5 * unit),
            ),
        )
        cloudPath.addOval(Rect(Offset(cloud.x - 8 * unit, cloud.y + 1 * unit), 7 * unit))
        cloudPath.addOval(Rect(Offset(cloud.x + 1 * unit, cloud.y - 4 * unit), 9 * unit))
        cloudPath.addOval(Rect(Offset(cloud.x + 9 * unit, cloud.y + 2 * unit), 6 * unit))
        drawPath(cloudPath, Color.White.copy(alpha = 0.24f))

        // The phone, with its data meter.
        val tl = Offset(phoneLeft, cy - phoneH / 2)
        glow(accent, Offset(phoneCx, cy), 11.dp.toPx() * (1f + 0.2f * pulse), 0.5f - 0.2f * s)
        drawRoundRect(Color.White.copy(alpha = 0.08f), tl, Size(phoneW, phoneH), CornerRadius(6.dp.toPx()))
        drawRoundRect(
            Color.White.copy(alpha = 0.7f), tl, Size(phoneW, phoneH), CornerRadius(6.dp.toPx()),
            style = Stroke(1.6.dp.toPx()),
        )
        drawLine(
            Color.White.copy(alpha = 0.4f), Offset(phoneCx - 3 * unit, tl.y + 4.5f * unit), Offset(phoneCx + 3 * unit, tl.y + 4.5f * unit),
            1.4.dp.toPx(), cap = StrokeCap.Round,
        )
        val meterTop = tl.y + 11.dp.toPx()
        val meterBottom = tl.y + phoneH - 8.dp.toPx()
        val used = mix(0.85f, 0.3f, s) * (0.96f + 0.04f * sin(t * 2f))
        drawLine(Color.White.copy(alpha = 0.14f), Offset(phoneCx, meterTop), Offset(phoneCx, meterBottom), 6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(
            accent, Offset(phoneCx, meterBottom), Offset(phoneCx, meterBottom - (meterBottom - meterTop) * used),
            6.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

/* ---------------- Automix ---------------- */

/**
 * Songs going by as waveforms past a "now" line, one after the other (the next song in a lighter
 * tint). With automix on, each song fades out while the next fades in, their waves woven together
 * through a long blend. With it off, one song stops and the next starts after a short hard gap.
 */
@Composable
fun AutomixArt(playing: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val blend by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "blend")
    val scroll = remember { Phase(0f) }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val b = blend
        val t = m.t
        val pulse = m.pulse
        val unit = 1.dp.toPx()
        backdrop(accent)
        val mid = h / 2
        val maxA = h * 0.36f * (1f + 0.12f * pulse)
        val head = w * 0.5f
        val songLen = max(w * 0.75f, 160.dp.toPx())
        val fadeLen = songLen * 0.5f * b
        val gap = 12.dp.toPx() * (1f - b)
        // Where the left edge of the picture is along the music. It starts with a change of song
        // just after the "now" line, so the still picture shows one.
        val left = scroll.at(t, 24f) * unit + songLen * 0.85f - head
        val pitch = 5.dp.toPx()
        val barW = 2.dp.toPx()

        fun ramp(x: Float, a: Float, z: Float): Float {
            if (z - a < 1f) return if (x >= z) 1f else 0f
            return smoothstep(a, z, x)
        }

        // How loud song k is at point x along the music: rising in, then falling out into the next.
        fun gain(k: Int, x: Float): Float {
            val s0 = k * songLen
            val e0 = s0 + songLen
            val fadeIn = ramp(x, s0 - fadeLen / 2 + gap / 2, s0 + fadeLen / 2 + gap / 2)
            val fadeOut = 1f - ramp(x, e0 - fadeLen / 2 - gap / 2, e0 + fadeLen / 2 - gap / 2)
            return fadeIn * fadeOut
        }

        // Each song's own loud and quiet stretches.
        fun loud(k: Int, x: Float): Float {
            val u = x / unit
            val ph = hash(k) * 6f
            return 0.55f + 0.22f * sin(u * 0.045f + ph) + 0.15f * sin(u * 0.17f + ph * 2f) + 0.06f * sin(u * 0.53f + t * 3f)
        }

        val kFrom = floor(left / songLen).toInt() - 1
        val kTo = floor((left + w) / songLen).toInt() + 1
        for (k in kFrom..kTo) {
            val color = if ((k and 1) == 0) accent else lerp(accent, Color.White, 0.55f)
            // The next song's bars sit between this one's, so a blend weaves the two together.
            val shift = if ((k and 1) == 0) 0f else pitch / 2
            val from = k * songLen - fadeLen / 2 + gap / 2
            val to = (k + 1) * songLen + fadeLen / 2 - gap / 2
            val jFrom = floor((max(from, left) - shift) / pitch).toInt()
            val jTo = floor((min(to, left + w) - shift) / pitch).toInt() + 1
            for (j in jFrom..jTo) {
                val x = j * pitch + shift
                val sx = x - left
                val g = gain(k, x)
                if (g <= 0.01f) continue
                val edge = smoothstep(0f, 0.07f, sx / w) * smoothstep(1f, 0.93f, sx / w)
                val a = g * edge * loud(k, x) * maxA
                if (a < 0.5f) continue
                drawLine(
                    color.copy(alpha = ((0.4f + 0.55f * g) * edge).coerceIn(0f, 1f)),
                    Offset(sx, mid - a), Offset(sx, mid + a), barW, cap = StrokeCap.Round,
                )
            }
        }

        // The "now" line.
        val top = mid - h * 0.42f
        drawLine(Color.White.copy(alpha = 0.22f), Offset(head, top), Offset(head, mid + h * 0.42f), 1.dp.toPx())
        drawCircle(Color.White.copy(alpha = 0.6f), 2.5.dp.toPx(), Offset(head, top))
    }
}

/* ---------------- Playback ---------------- */

/**
 * The queue as a row of song tiles. The song in the first slot plays (little bouncing bars and a
 * progress bar), then the row slides along by one. With autoplay on, new songs (dashed accent edge
 * and a small spark) keep arriving at the end, so the music never stops. With it off, the queue ends
 * at a small end mark, the first slot sits empty for a moment, then a new queue appears.
 */
@Composable
fun PlaybackArt(playing: Boolean, autoplay: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val more by animateFloatAsState(if (autoplay) 1f else 0f, artSpec(), label = "autoplay")
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val a = more
        val t = m.t
        val pulse = m.pulse
        backdrop(accent, glowX = 0.22f)

        // Each song plays for a while, then the queue slides along by one.
        val songs = t / PB_SONG_TIME
        val n = floor(songs).toInt()
        val f = songs - n
        val slide = smoothstep(0.78f, 1f, f)
        val at = n + slide
        val tile = 30.dp.toPx()
        val pitch = tile + 12.dp.toPx()
        val x0 = 22.dp.toPx() + tile * 0.7f
        val cy = h * 0.42f
        val lastSlot = (w - x0 - tile * 0.5f - 10.dp.toPx()) / pitch
        val cycle = PB_QUEUE + 2
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))
        val corner = CornerRadius(7.dp.toPx())

        // Without autoplay, a queue shows up once its first song is next. With it, every slot is filled.
        fun shownOf(k: Int): Float {
            val start = pbFloorDiv(k, cycle) * cycle
            return smoothstep(start - 1f, start - 0.4f, at)
        }
        fun queued(k: Int) = k - pbFloorDiv(k, cycle) * cycle < PB_QUEUE
        fun aliveOf(k: Int): Float = if (queued(k)) mix(shownOf(k), 1f, a) else a
        // Tiles fade in at the right end and out as they leave on the left.
        fun edgeOf(j: Float): Float = smoothstep(lastSlot + 0.9f, lastSlot - 0.1f, j) * smoothstep(-1f, -0.2f, j)

        val kFrom = floor(at).toInt() - 2
        val kTo = kFrom + lastSlot.toInt() + 4
        for (k in kFrom..kTo) {
            val j = k - at
            val alpha = aliveOf(k) * edgeOf(j)
            if (alpha < 0.01f) continue
            val near = (1f - abs(j)).coerceIn(0f, 1f)
            val sz = tile * (1f + 0.3f * near) * mix(0.85f, 1f, smoothstep(-1f, -0.2f, j))
            val c = Offset(x0 + j * pitch, cy)
            val tl = Offset(c.x - sz / 2, c.y - sz / 2)
            if (near > 0.01f) glow(accent, c, sz * 0.4f * (1f + 0.15f * pulse), near * alpha * 0.8f)
            if (queued(k)) {
                val tone = hash(k * 3 + 1)
                drawRoundRect(
                    Brush.linearGradient(
                        listOf(
                            lerp(accent, Color.White, 0.25f + 0.35f * tone).copy(alpha = (0.3f + 0.15f * tone) * alpha),
                            accent.copy(alpha = 0.06f * alpha),
                        ),
                        start = tl, end = tl + Offset(sz, sz),
                    ),
                    tl, Size(sz, sz), corner,
                )
                drawRoundRect(Color.White.copy(alpha = 0.16f * alpha), tl, Size(sz, sz), corner, style = Stroke(1.dp.toPx()))
            } else {
                // A song autoplay added: a dashed accent edge and a little spark in the corner.
                drawRoundRect(accent.copy(alpha = 0.14f * alpha), tl, Size(sz, sz), corner)
                drawRoundRect(accent.copy(alpha = 0.7f * alpha), tl, Size(sz, sz), corner, style = Stroke(1.4.dp.toPx(), pathEffect = dash))
                val tw = 0.6f + 0.4f * sin(t * 2.4f + k)
                val spark = Offset(tl.x + sz - 3.dp.toPx(), tl.y + 3.dp.toPx())
                glow(accent, spark, 2.dp.toPx(), alpha * tw)
                drawCircle(lerp(accent, Color.White, 0.6f).copy(alpha = (alpha * tw).coerceIn(0f, 1f)), 2.dp.toPx(), spark)
            }
            if (near > 0.01f) {
                drawRoundRect(accent.copy(alpha = 0.9f * near * alpha), tl, Size(sz, sz), corner, style = Stroke(1.6.dp.toPx()))
                // Little bars bouncing on the song that's playing.
                for (i in 0 until 3) {
                    val bh = sz * (0.14f + 0.2f * (0.5f + 0.5f * sin(t * 5f + i * 1.9f)) * (0.7f + 0.5f * pulse))
                    val bx = c.x + (i - 1) * 4.5.dp.toPx()
                    drawLine(
                        Color.White.copy(alpha = 0.85f * near * alpha), Offset(bx, c.y + sz * 0.2f), Offset(bx, c.y + sz * 0.2f - bh),
                        2.2.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
            // Without autoplay, a small end mark just after the last song of each queue.
            if (a < 0.99f && queued(k) && !queued(k + 1)) {
                val je = j + 0.55f
                val ea = (1f - a) * shownOf(k) * edgeOf(je)
                if (ea > 0.01f) {
                    val ex = x0 + je * pitch
                    drawLine(
                        Color.White.copy(alpha = 0.4f * ea), Offset(ex, cy - tile * 0.42f), Offset(ex, cy + tile * 0.42f),
                        2.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
        }

        // The playing song's progress, under the first slot (empty while nothing is playing).
        val pw = tile * 1.3f
        val py = cy + tile * 0.65f + 10.dp.toPx()
        pbProgress(
            x0 - pw / 2, x0 + pw / 2, py, (f / 0.78f).coerceIn(0f, 1f), accent,
            aliveOf(n) * (1f - slide), 2.5.dp.toPx(),
        )
    }
}

/* ---------------- Mini player (PC) ---------------- */

/**
 * A player window on the desktop. With the mini player on, it now and then shrinks into a small
 * floating player in the bottom right corner (cover, title and progress, softly glowing as it stays
 * on top), with a faint dashed outline where the big window was, then grows back.
 */
@Composable
fun MiniPlayerArt(playing: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val mini by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "mini")
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        val unit = 1.dp.toPx()
        // While on: big for a moment, shrink, wait small in the corner for a while, grow back.
        val loop = t / 7f - floor(t / 7f)
        val small = smoothstep(0.06f, 0.3f, loop) * (1f - smoothstep(0.82f, 0.98f, loop))
        val e = mini * small
        backdrop(accent, glowX = mix(0.5f, 0.82f, e))

        // The big window and the small player.
        val bw = min(w * 0.6f, 250.dp.toPx())
        val bh = h - 22.dp.toPx()
        val bx = (w - bw) / 2
        val by = 11.dp.toPx()
        val mw = min(112.dp.toPx(), w * 0.4f)
        val mh = 32.dp.toPx()
        val mx = w - mw - 12.dp.toPx()
        val my = h - mh - 10.dp.toPx()
        val l = mix(bx, mx, e)
        val top = mix(by, my, e)
        val r = mix(bx + bw, mx + mw, e)
        val bottom = mix(by + bh, my + mh, e)
        val corner = CornerRadius(mix(9f, 11f, e) * unit)

        if (e > 0.01f) {
            drawRoundRect(
                Color.White.copy(alpha = 0.12f * e), Offset(bx, by), Size(bw, bh), CornerRadius(9 * unit),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * unit, 4 * unit))),
            )
            glow(accent, Offset((l + r) / 2, (top + bottom) / 2), (bottom - top) * 0.55f * (1f + 0.15f * pulse), 0.55f * e)
        }
        drawRoundRect(Color.White.copy(alpha = mix(0.07f, 0.13f, e)), Offset(l, top), Size(r - l, bottom - top), corner)
        drawRoundRect(
            Color.White.copy(alpha = mix(0.2f, 0.3f, e)), Offset(l, top), Size(r - l, bottom - top), corner,
            style = Stroke(1.dp.toPx()),
        )

        // A title bar with three small dots, only on the big window.
        val bar = 11.dp.toPx() * (1f - e)
        if (e < 0.98f) {
            for (i in 0 until 3) drawCircle(Color.White.copy(alpha = 0.3f * (1f - e)), 2.dp.toPx(), Offset(l + (9 + i * 7) * unit, top + 7 * unit))
        }

        // The cover, title and artist.
        val pad = mix(9f, 5f, e) * unit
        val cl = l + pad
        val ct = top + bar + pad
        val cr = r - pad
        val cb = bottom - pad
        val cover = mix((cb - ct) * 0.5f, cb - ct, e).coerceAtMost((cr - cl) * 0.35f).coerceAtLeast(1f)
        pbCover(Offset(cl, ct), cover, accent, 1f, mix(5f, 4f, e) * unit)
        // Little bars bouncing on the cover.
        for (i in 0 until 3) {
            val bhh = cover * (0.15f + 0.3f * (0.5f + 0.5f * sin(t * 5f + i * 1.7f)) * (0.6f + 0.5f * pulse))
            val x = cl + cover * (0.32f + 0.18f * i)
            drawLine(
                Color.White.copy(alpha = 0.8f), Offset(x, ct + cover * 0.8f), Offset(x, ct + cover * 0.8f - bhh),
                max(1.5f * unit, cover * 0.09f), cap = StrokeCap.Round,
            )
        }
        val lx = cl + cover + mix(9f, 6f, e) * unit
        val thick = mix(4f, 3f, e) * unit
        pbTextLine(lx, ct + cover * 0.32f, (cr - lx) * 0.7f, thick, Color.White.copy(alpha = 0.6f))
        pbTextLine(lx, ct + cover * 0.66f, (cr - lx) * 0.45f, thick, Color.White.copy(alpha = 0.3f))

        // The progress bar: under the cover on the big window, along the bottom edge when small.
        val done = t * 0.04f + 0.3f - floor(t * 0.04f + 0.3f)
        pbProgress(
            mix(cl, l + 10 * unit, e), mix(cr, r - 10 * unit, e), mix(ct + cover + 9 * unit, bottom - 3.5f * unit, e),
            done, accent, 1f, 2.dp.toPx(),
        )

        // A couple of list rows on the big window, gone when small.
        if (e < 0.98f) {
            for (i in 0 until 2) {
                val y = ct + cover + (18 + i * 8) * unit
                if (y < cb) pbTextLine(cl, y, (cr - cl) * (0.8f - 0.2f * i), 3 * unit, Color.White.copy(alpha = 0.14f * (1f - e)))
            }
        }
    }
}

/* ---------------- Discord status (PC) ---------------- */

/**
 * A plain profile status card: a round picture with a status dot, a name and a status line. With it
 * on, the dot lights up, small bars bounce beside "listening to", and a little box below shows the
 * song with its progress moving. With it off, the box is an empty dashed outline.
 */
@Composable
fun DiscordArt(playing: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val share by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "share")
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val s = share
        val t = m.t
        val pulse = m.pulse
        val unit = 1.dp.toPx()
        backdrop(accent, glow = 0.5f + 0.6f * s)

        // The card.
        val cw = min(w * 0.72f, 280.dp.toPx())
        val l = (w - cw) / 2
        val top = 10.dp.toPx()
        val r = l + cw
        val bottom = h - 10.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(l, top), Size(cw, bottom - top), CornerRadius(14 * unit))
        drawRoundRect(
            Color.White.copy(alpha = 0.14f), Offset(l, top), Size(cw, bottom - top), CornerRadius(14 * unit),
            style = Stroke(1.dp.toPx()),
        )

        // The round picture: a simple person shape.
        val av = Offset(l + 24 * unit, top + 22 * unit)
        val avR = 12.dp.toPx()
        drawCircle(lerp(accent, Color.White, 0.2f).copy(alpha = 0.4f), avR, av)
        drawCircle(Color.White.copy(alpha = 0.75f), 4.5f * unit, Offset(av.x, av.y - 3 * unit))
        drawArc(
            Color.White.copy(alpha = 0.75f), 180f, 180f, true,
            topLeft = Offset(av.x - 6.5f * unit, av.y + 3 * unit), size = Size(13 * unit, 12 * unit),
        )
        // The status dot: lit while sharing, an empty ring when not.
        val dot = Offset(av.x + 9 * unit, av.y + 9 * unit)
        drawCircle(Color.Black.copy(alpha = 0.55f), 5.5f * unit, dot)
        if (s > 0.01f) {
            glow(accent, dot, 3.5f * unit, s * (0.7f + 0.3f * sin(t * 2f)))
            drawCircle(accent.copy(alpha = s), 3.6f * unit, dot)
        }
        if (s < 0.99f) drawCircle(Color.White.copy(alpha = 0.4f * (1f - s)), 3f * unit, dot, style = Stroke(1.4.dp.toPx()))

        // The name, and the status line under it.
        val tx = av.x + avR + 10 * unit
        val room = r - 14 * unit - tx
        pbTextLine(tx, av.y - 5 * unit, min(90 * unit, room * 0.7f), 5 * unit, Color.White.copy(alpha = 0.75f))
        val sy = av.y + 6 * unit
        if (s > 0.01f) {
            for (i in 0 until 3) {
                val bh = (2f + 5f * (0.5f + 0.5f * sin(t * 6f + i * 1.8f)) * (0.6f + 0.5f * pulse)) * unit
                val bx = tx + i * 3.5f * unit
                drawLine(accent.copy(alpha = s), Offset(bx, sy + 3 * unit), Offset(bx, sy + 3 * unit - bh), 2 * unit, cap = StrokeCap.Round)
            }
        }
        pbTextLine(
            tx + 14 * unit * s, sy, min(mix(40f, 64f, s) * unit, room * 0.6f), 3.5f * unit,
            Color.White.copy(alpha = mix(0.25f, 0.4f, s)),
        )

        // The "listening to" box with the song and its progress.
        val bl = l + 12 * unit
        val br = r - 12 * unit
        val bt = top + 46 * unit + (1f - s) * 6 * unit
        val bb = bottom - 10 * unit + (1f - s) * 6 * unit
        if (bb - bt > 12 * unit && br > bl) {
            if (s < 0.99f) {
                drawRoundRect(
                    Color.White.copy(alpha = 0.12f * (1f - s)), Offset(bl, bt), Size(br - bl, bb - bt), CornerRadius(9 * unit),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * unit, 4 * unit))),
                )
            }
            if (s > 0.01f) {
                drawRoundRect(accent.copy(alpha = 0.1f * s), Offset(bl, bt), Size(br - bl, bb - bt), CornerRadius(9 * unit))
                drawRoundRect(
                    accent.copy(alpha = 0.28f * s), Offset(bl, bt), Size(br - bl, bb - bt), CornerRadius(9 * unit),
                    style = Stroke(1.dp.toPx()),
                )
                val side = bb - bt - 12 * unit
                pbCover(Offset(bl + 6 * unit, bt + 6 * unit), side, accent, s, 5 * unit)
                val lx = bl + 6 * unit + side + 9 * unit
                val lw = br - 10 * unit - lx
                pbTextLine(lx, bt + 10 * unit, lw * 0.6f, 3.5f * unit, Color.White.copy(alpha = 0.7f * s))
                pbTextLine(lx, bt + 17 * unit, lw * 0.4f, 3f * unit, Color.White.copy(alpha = 0.35f * s))
                val done = t / 30f + 0.35f - floor(t / 30f + 0.35f)
                val py = bb - 7 * unit
                pbProgress(lx, lx + lw, py, done, accent, s, 2.5f * unit)
                if (lw > 0f) drawCircle(Color.White.copy(alpha = 0.9f * s), 2.6f * unit, Offset(lx + lw * done, py))
            }
        }
    }
}
