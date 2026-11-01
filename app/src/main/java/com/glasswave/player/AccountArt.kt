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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Small moving pictures for the other settings (updates, help, frame rate, accounts, devices,
 * imports and sync), in the same look as the Audio Lab ones in SoundArt.kt.
 *
 * - Each takes `playing` plus the setting it shows, passed in by the settings screen (nothing is
 *   read from Settings here), so this file works the same in the phone and PC copies.
 * - They are [SettingArtHeight] tall, fill the width and clip to rounded glass corners.
 * - Drawing happens only in the draw phase, with plain shapes, Accent and soft white. No blur.
 */

private const val AC_PI = 3.1415927f

/** The part after the decimal point (0..1), also for negative numbers. */
private fun acFrac(x: Float) = x - floor(x)

/** This colour at [a] alpha, kept inside 0..1. */
private fun Color.acA(a: Float) = copy(alpha = a.coerceIn(0f, 1f))

/** A small music note; [at] is the middle of its round head, [s] about a third of its height. */
private fun DrawScope.acNote(at: Offset, s: Float, color: Color) {
    rotate(-22f, at) {
        drawOval(color, topLeft = Offset(at.x - s * 0.62f, at.y - s * 0.45f), size = Size(s * 1.24f, s * 0.9f))
    }
    val sx = at.x + s * 0.5f
    val top = at.y - s * 2.5f
    val sw = max(s * 0.22f, 1.dp.toPx())
    drawLine(color, Offset(sx, at.y - s * 0.15f), Offset(sx, top), sw, StrokeCap.Round)
    drawLine(color, Offset(sx, top), Offset(sx + s * 0.85f, top + s * 0.75f), sw, StrokeCap.Round)
}

/** One song row: a little cover and two lines (title and artist), [len] long. */
private fun DrawScope.acRow(left: Float, top: Float, len: Float, cover: Color, alpha: Float) {
    if (alpha < 0.01f) return
    val c = 14.dp.toPx()
    drawRoundRect(cover.acA(cover.alpha * alpha), Offset(left, top), Size(c, c), CornerRadius(3.5.dp.toPx()))
    val x = left + c + 8.dp.toPx()
    drawLine(Color.White.acA(0.6f * alpha), Offset(x, top + 4.dp.toPx()), Offset(x + len, top + 4.dp.toPx()), 3.5.dp.toPx(), StrokeCap.Round)
    drawLine(Color.White.acA(0.25f * alpha), Offset(x, top + 10.5.dp.toPx()), Offset(x + len * 0.55f, top + 10.5.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
}

/** A small tick mark (✓) centred on [c]. */
private fun DrawScope.acCheck(c: Offset, s: Float, color: Color, width: Float) {
    drawLine(color, Offset(c.x - s * 0.9f, c.y), Offset(c.x - s * 0.25f, c.y + s * 0.65f), width, StrokeCap.Round)
    drawLine(color, Offset(c.x - s * 0.25f, c.y + s * 0.65f), Offset(c.x + s, c.y - s * 0.75f), width, StrokeCap.Round)
}

/* ---------------- Updates ---------------- */

/**
 * Down arrows drop one after another into an open box while the little ring beside it fills up.
 * When the ring is full a tick shows, then it starts again.
 */
@Composable
fun UpdatesArt(playing: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val loop = remember { Phase(0.35f) }
    val tray = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val pulse = m.pulse
        val u = acFrac(loop.at(m.t, 1f / 4.2f))
        val progress = smoothstep(0.04f, 0.84f, u)
        val done = smoothstep(0.84f, 0.9f, u)
        val fade = 1f - smoothstep(0.93f, 1f, u)
        backdrop(accent, glow = 0.7f + 0.4f * done * fade)

        val trayW = 58.dp.toPx()
        val trayH = 20.dp.toPx()
        val bottom = h * 0.8f
        val top = bottom - trayH
        val left = cx - trayW / 2 - 14.dp.toPx()
        val mid = left + trayW / 2

        glow(accent, Offset(mid, bottom - trayH / 2), 13.dp.toPx(), 0.3f + 0.45f * done * fade + 0.3f * pulse)

        // Three arrows drop in while the download runs.
        if (u < 0.84f) {
            val s = acFrac(u / 0.84f * 3f)
            val tipY = mix(h * 0.14f + 26.dp.toPx(), bottom - 5.dp.toPx(), smoothstep(0f, 1f, s))
            val a = smoothstep(0f, 0.18f, s) * (1f - smoothstep(0.62f, 0.95f, s))
            if (a > 0.01f) {
                val col = lerp(accent, Color.White, 0.2f).acA(a)
                val sw = 2.6.dp.toPx()
                val head = 7.dp.toPx()
                drawLine(col, Offset(mid, tipY - 24.dp.toPx()), Offset(mid, tipY - 1.dp.toPx()), sw, StrokeCap.Round)
                drawLine(col, Offset(mid, tipY), Offset(mid - head, tipY - head), sw, StrokeCap.Round)
                drawLine(col, Offset(mid, tipY), Offset(mid + head, tipY - head), sw, StrokeCap.Round)
            }
        }

        // The open box.
        tray.reset()
        tray.moveTo(left, top)
        tray.lineTo(left, bottom)
        tray.lineTo(left + trayW, bottom)
        tray.lineTo(left + trayW, top)
        drawPath(tray, Color.White.acA(0.8f), style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // The progress ring beside it, with a tick when it's full.
        val rc = Offset(left + trayW + 30.dp.toPx(), bottom - trayH / 2)
        val rr = 10.dp.toPx()
        val ringW = 2.4.dp.toPx()
        drawCircle(Color.White.acA(0.12f), rr, rc, style = Stroke(ringW))
        if (progress > 0.005f) {
            drawArc(
                accent.acA(fade), -90f, 360f * progress, false,
                topLeft = Offset(rc.x - rr, rc.y - rr), size = Size(rr * 2, rr * 2),
                style = Stroke(ringW, cap = StrokeCap.Round),
            )
        }
        if (done * fade > 0.01f) {
            glow(accent, rc, rr * 0.7f, 0.5f * done * fade)
            acCheck(rc, 4.dp.toPx(), Color.White.acA(0.9f * done * fade), 2.dp.toPx())
        }
    }
}

/* ---------------- Help make Glasswave better ---------------- */

/**
 * Little lights float up from a music note (song reports) and a small bar chart (usage numbers)
 * into a softly beating heart. Each switch that's on sends a steady stream; with both off only a
 * faint one or two drift up and the heart dims.
 */
@Composable
fun HelpArt(playing: Boolean, report: Boolean, count: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val rep by animateFloatAsState(if (report) 1f else 0f, artSpec(), label = "report")
    val cnt by animateFloatAsState(if (count) 1f else 0f, artSpec(), label = "count")
    val flow = remember { Phase(0.2f) }
    val heart = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val t = m.t
        val pulse = m.pulse
        val both = (rep + cnt) / 2f
        backdrop(accent, glow = 0.45f + 0.7f * both)
        val hc = Offset(cx, h * 0.34f)
        val f0 = flow.at(t, 0.3f)
        val spread = min(w * 0.3f, 118.dp.toPx())
        val dot = 2.4.dp.toPx()
        val tint = lerp(accent, Color.White, 0.3f)

        for (side in 0..1) {
            val on = if (side == 0) rep else cnt
            val src = Offset(cx + (if (side == 0) -spread else spread), h * 0.8f)
            val iconCol = Color.White.acA(mix(0.25f, 0.7f, on))
            if (side == 0) {
                acNote(Offset(src.x - 2.dp.toPx(), src.y + 4.dp.toPx()), 4.5.dp.toPx(), iconCol)
            } else {
                for (b in 0 until 3) {
                    val x = src.x + (b - 1) * 4.5.dp.toPx()
                    val bh = (4f + b * 3.5f).dp.toPx()
                    drawLine(iconCol, Offset(x, src.y + 6.dp.toPx()), Offset(x, src.y + 6.dp.toPx() - bh), 2.6.dp.toPx(), StrokeCap.Round)
                }
            }
            // Five lights when on, one faint one when off.
            val shown = mix(1f, 5f, on)
            val n = 5
            for (i in 0 until n) {
                val vis = (shown - i).coerceIn(0f, 1f)
                if (vis < 0.01f) continue
                val f = acFrac(f0 + i.toFloat() / n + side * 0.1f)
                val x = mix(src.x, hc.x, smoothstep(0f, 1f, f)) + sin(f * 5f + i + side * 2f) * 5.dp.toPx() * (1f - f)
                val y = mix(src.y - 10.dp.toPx(), hc.y, f)
                val a = vis * mix(0.3f, 1f, on) * smoothstep(0f, 0.12f, f) * (1f - smoothstep(0.8f, 0.97f, f))
                if (a < 0.01f) continue
                for (j in 3 downTo 1) {
                    val fj = f - j * 0.025f
                    val xj = mix(src.x, hc.x, smoothstep(0f, 1f, fj)) + sin(fj * 5f + i + side * 2f) * 5.dp.toPx() * (1f - fj)
                    val yj = mix(src.y - 10.dp.toPx(), hc.y, fj)
                    drawCircle(accent.acA(a * (0.3f - j * 0.07f)), dot * (1f - j * 0.15f), Offset(xj, yj))
                }
                glow(accent, Offset(x, y), dot, a * 0.8f)
                drawCircle(tint.acA(a), dot, Offset(x, y))
            }
        }

        // The heart, beating gently (stronger with more switches on, and with the music).
        val beat = exp(-acFrac(t * 0.9f) * 9f)
        val s = 13.dp.toPx() * (1f + 0.08f * beat * mix(0.3f, 1f, both) + 0.06f * pulse)
        heart.reset()
        heart.moveTo(hc.x, hc.y + s * 0.9f)
        heart.cubicTo(hc.x - s * 1.6f, hc.y - s * 0.1f, hc.x - s * 0.9f, hc.y - s * 1.25f, hc.x, hc.y - s * 0.45f)
        heart.cubicTo(hc.x + s * 0.9f, hc.y - s * 1.25f, hc.x + s * 1.6f, hc.y - s * 0.1f, hc.x, hc.y + s * 0.9f)
        heart.close()
        glow(accent, hc, s * 0.9f, mix(0.25f, 0.85f, both) + 0.2f * pulse)
        drawPath(heart, lerp(accent, Color.White, 0.15f).acA(mix(0.45f, 1f, both)))
    }
}

/* ---------------- Frame rate ---------------- */

/**
 * A ball gliding back and forth, leaving faint copies of the frames just shown. At 60 Hz it moves
 * in clear steps with the copies spread apart; at 120 Hz the steps close up into one smooth trail.
 * (Slowed right down, so the difference is easy to see.)
 */
@Composable
fun FrameRateArt(playing: Boolean, fps: Int, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val smooth by animateFloatAsState(((fps - 60) / 60f).coerceIn(0f, 1f), artSpec(), label = "fps")
    val clock = remember { Phase(1.2f) }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val sm = smooth
        val pulse = m.pulse
        backdrop(accent, glow = 0.65f + 0.45f * sm)
        // Pictures shown per second here: few at 60 Hz, many at 120 Hz.
        val rate = mix(5f, 30f, sm.pow(1.3f))
        val time = clock.at(m.t, 1f)
        val shown = floor(time * rate) / rate
        val left = max(28.dp.toPx(), w * 0.12f)
        val right = w - left
        val cy = h * 0.5f

        // The track, with more marks the more frames there are.
        drawLine(Color.White.acA(0.08f), Offset(left, cy), Offset(right, cy), 1.5.dp.toPx(), StrokeCap.Round)
        val ticks = mix(8f, 24f, sm).roundToInt()
        for (i in 0..ticks) {
            val x = mix(left, right, i.toFloat() / ticks)
            drawLine(Color.White.acA(0.13f), Offset(x, cy + 14.dp.toPx()), Offset(x, cy + 19.dp.toPx()), 1.2.dp.toPx(), StrokeCap.Round)
        }

        // The frames just shown, oldest first, then the ball itself.
        val r = (7f + 1.5f * pulse).dp.toPx()
        val ghosts = 6
        for (j in ghosts downTo 0) {
            val tj = shown - j / rate
            val p = Offset(mix(left, right, 0.5f - 0.5f * cos(tj * 1.7f)), cy)
            if (j == 0) {
                glow(accent, p, r, 0.8f + 0.3f * pulse)
                drawCircle(lerp(accent, Color.White, 0.25f), r, p)
            } else {
                val a = (1f - j / (ghosts + 1f)) * 0.3f
                drawCircle(accent.acA(a), r * (1f - j * 0.05f), p)
            }
        }
    }
}

/* ---------------- YouTube Music account ---------------- */

/**
 * An account circle on one side and music notes on the other. Signed in, a glowing line links
 * them with little lights running along it and the notes brighten; signed out, the line is a faint
 * broken dash and the notes are dim.
 */
@Composable
fun YouTubeAccountArt(playing: Boolean, signedIn: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val link by animateFloatAsState(if (signedIn) 1f else 0f, artSpec(), label = "link")
    val flow = remember { Phase(0.4f) }
    val clip = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val t = m.t
        val pulse = m.pulse
        val ln = link
        backdrop(accent, glow = 0.55f + 0.6f * ln)
        val d = min(w * 0.27f, 108.dp.toPx())
        val acc = Offset(cx - d, cy)
        val lib = Offset(cx + d, cy)
        val r = 21.dp.toPx()

        // The link between them: broken in the middle while signed out.
        val a = Offset(acc.x + r + 8.dp.toPx(), cy)
        val b = Offset(lib.x - 28.dp.toPx(), cy)
        val midX = (a.x + b.x) / 2
        val gap = (1f - ln) * 12.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))
        drawLine(Color.White.acA(0.15f), a, Offset(midX - gap, cy), 1.6.dp.toPx(), StrokeCap.Round, pathEffect = dash)
        drawLine(Color.White.acA(0.15f), Offset(midX + gap, cy), b, 1.6.dp.toPx(), StrokeCap.Round, pathEffect = dash)
        if (ln > 0.01f) {
            drawLine(accent.acA(0.5f * ln), a, b, 2.dp.toPx(), StrokeCap.Round)
            val f = flow.at(t, 0.45f)
            for (i in 0 until 3) {
                val p = acFrac(f + i / 3f)
                val at = Offset(mix(a.x, b.x, p), cy)
                val al = ln * sin(AC_PI * p)
                glow(accent, at, 2.5.dp.toPx(), al * 0.8f)
                drawCircle(lerp(accent, Color.White, 0.4f).acA(al), 2.4.dp.toPx(), at)
            }
        }

        // The account circle: a plain head and shoulders.
        glow(accent, acc, r * 0.8f, 0.2f + 0.5f * ln + 0.2f * pulse)
        drawCircle(Color.White.acA(0.07f), r, acc)
        clip.reset()
        clip.addOval(Rect(acc, r))
        clipPath(clip) {
            drawCircle(Color.White.acA(0.75f), 7.dp.toPx(), Offset(acc.x, acc.y - 5.dp.toPx()))
            drawCircle(Color.White.acA(0.75f), 13.dp.toPx(), Offset(acc.x, acc.y + 17.dp.toPx()))
        }
        drawCircle(lerp(Color.White.acA(0.35f), accent, ln), r, acc, style = Stroke(2.dp.toPx()))
        if (ln > 0.01f) {
            val bp = Offset(acc.x + r * 0.72f, acc.y + r * 0.72f)
            drawCircle(accent.acA(ln), 6.5.dp.toPx(), bp)
            acCheck(bp, 2.6.dp.toPx(), Color.Black.acA(0.8f * ln), 1.6.dp.toPx())
        }

        // The music, bobbing.
        for (i in 0 until 3) {
            val bob = sin(t * 1.3f + i * 1.9f) * 3.dp.toPx()
            val pos = Offset(lib.x + (i - 1) * 17.dp.toPx(), lib.y + (if (i == 1) -6f else 8f).dp.toPx() + bob)
            val col = lerp(Color.White, accent, 0.85f * ln).acA(mix(0.35f, 0.95f, ln))
            acNote(pos, (if (i == 1) 6.5f else 5f).dp.toPx(), col)
        }
    }
}

/* ---------------- Your devices ---------------- */

/**
 * A phone and a computer with a glowing song dot travelling between them; the screen it lands on
 * lights up and shows a note. With no other devices found, the dot stays on the phone and soft
 * rings go out from it, looking. Small dots under the computer show how many devices there are.
 */
@Composable
fun DevicesArt(playing: Boolean, count: Int, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val link by animateFloatAsState(if (count > 0) 1f else 0f, artSpec(), label = "link")
    val many by animateFloatAsState(count.coerceIn(0, 4).toFloat(), artSpec(), label = "count")
    val trip = remember { Phase(0.15f) }
    val route = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val t = m.t
        val pulse = m.pulse
        val ln = link
        backdrop(accent, glow = 0.55f + 0.5f * ln)
        val d = min(w * 0.27f, 110.dp.toPx())
        val pW = 24.dp.toPx()
        val pH = 42.dp.toPx()
        val mW = 66.dp.toPx()
        val mH = 40.dp.toPx()
        val phone = Offset(cx - d, h * 0.52f)
        val pc = Offset(cx + d, h * 0.45f)
        val p = trip.at(t, 0.2f)
        // 0 = on the phone, 1 = on the computer.
        val u = (0.5f - 0.5f * cos(p * 2f * AC_PI)) * ln
        val onPhone = 1f - u

        // The way between them.
        val a = Offset(phone.x, phone.y - pH / 2 - 6.dp.toPx())
        val b = Offset(pc.x, pc.y - mH / 2 - 6.dp.toPx())
        val lift = 20.dp.toPx()
        fun at(k: Float) = Offset(mix(a.x, b.x, k), mix(a.y, b.y, k) - sin(AC_PI * k) * lift)
        route.reset()
        for (i in 0..24) {
            val q = at(i / 24f)
            if (i == 0) route.moveTo(q.x, q.y) else route.lineTo(q.x, q.y)
        }
        drawPath(
            route, Color.White.acA(mix(0.07f, 0.16f, ln)),
            style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))),
        )

        // Looking for other devices.
        if (ln < 0.99f) {
            for (j in 0..1) {
                val q = acFrac(t * 0.5f + j * 0.5f)
                val rr = 10.dp.toPx() + q * 34.dp.toPx()
                drawCircle(accent.acA((1f - q) * 0.4f * (1f - ln)), rr, phone, style = Stroke(1.4.dp.toPx()))
            }
        }

        // The phone.
        val outline = Color.White.acA(0.75f)
        val sw = 2.dp.toPx()
        glow(accent, phone, 12.dp.toPx(), 0.15f + 0.45f * onPhone + 0.2f * pulse * onPhone)
        val pTl = Offset(phone.x - pW / 2, phone.y - pH / 2)
        drawRoundRect(accent.acA(0.06f + 0.3f * onPhone), pTl, Size(pW, pH), CornerRadius(5.dp.toPx()))
        drawRoundRect(outline, pTl, Size(pW, pH), CornerRadius(5.dp.toPx()), style = Stroke(sw))
        drawLine(outline, Offset(phone.x - 3.dp.toPx(), pTl.y + 4.dp.toPx()), Offset(phone.x + 3.dp.toPx(), pTl.y + 4.dp.toPx()), 1.6.dp.toPx(), StrokeCap.Round)
        acNote(Offset(phone.x - 1.5.dp.toPx(), phone.y + 5.dp.toPx()), 3.5.dp.toPx(), Color.White.acA(0.85f * onPhone))

        // The computer.
        glow(accent, pc, 14.dp.toPx(), 0.15f + 0.45f * u + 0.2f * pulse * u)
        val mTl = Offset(pc.x - mW / 2, pc.y - mH / 2)
        drawRoundRect(accent.acA(0.06f + 0.3f * u), mTl, Size(mW, mH), CornerRadius(4.dp.toPx()))
        drawRoundRect(outline, mTl, Size(mW, mH), CornerRadius(4.dp.toPx()), style = Stroke(sw))
        val standTop = pc.y + mH / 2
        drawLine(outline, Offset(pc.x, standTop), Offset(pc.x, standTop + 7.dp.toPx()), sw, StrokeCap.Round)
        drawLine(outline, Offset(pc.x - 11.dp.toPx(), standTop + 7.dp.toPx()), Offset(pc.x + 11.dp.toPx(), standTop + 7.dp.toPx()), sw, StrokeCap.Round)
        acNote(Offset(pc.x - 2.dp.toPx(), pc.y + 6.dp.toPx()), 4.dp.toPx(), Color.White.acA(0.85f * u))

        // One small dot per device found.
        val dots = 4
        val gapX = 6.dp.toPx()
        for (i in 0 until dots) {
            val lit = (many - i).coerceIn(0f, 1f)
            val x = pc.x + (i - (dots - 1) / 2f) * gapX
            drawCircle(lerp(Color.White.acA(0.12f), accent, lit), 1.8.dp.toPx(), Offset(x, standTop + 14.dp.toPx()))
        }

        // The song, moving between them.
        if (ln > 0.01f) {
            for (j in 4 downTo 1) {
                val uj = (0.5f - 0.5f * cos((p - j * 0.012f) * 2f * AC_PI)) * ln
                drawCircle(accent.acA(ln * (0.3f - j * 0.06f)), 3.dp.toPx() * (1f - j * 0.12f), at(uj))
            }
            val s = at(u)
            val r = (3.4f + 1.2f * pulse).dp.toPx()
            glow(accent, s, r, 0.9f * ln)
            drawCircle(lerp(accent, Color.White, 0.35f).acA(ln), r, s)
        }
    }
}

/* ---------------- Import from a link ---------------- */

/**
 * A chain link pops in as if just pasted, then little lights carry it across and it turns into a
 * list of songs, one row at a time. The list fades and it begins again.
 */
@Composable
fun ImportLinkArt(playing: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val loop = remember { Phase(0.55f) }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val pulse = m.pulse
        backdrop(accent, glowX = 0.42f)
        val u = acFrac(loop.at(m.t, 1f / 4.2f))
        val gap = min(w * 0.22f, 84.dp.toPx())
        val lc = Offset(cx - gap - 6.dp.toPx(), cy)
        val rowsW = min(w * 0.32f, 132.dp.toPx())
        val rowsL = cx - 4.dp.toPx()
        val pitch = 19.dp.toPx()
        val rows = 4
        val rowsTop = cy - ((rows - 1) * pitch + 14.dp.toPx()) / 2

        // The link: pops when pasted, dims as it becomes the list.
        val pop = exp(-((u - 0.06f) / 0.05f).pow(2))
        val la = 1f - 0.55f * smoothstep(0.3f, 0.75f, u) * (1f - smoothstep(0.88f, 1f, u))
        val s = 9.dp.toPx() * (1f + 0.12f * pop + 0.05f * pulse)
        glow(accent, lc, s * 1.2f, 0.25f + 0.5f * pop + 0.2f * pulse)
        val linkCol = lerp(accent, Color.White, 0.2f).acA(la)
        val ring = Stroke(2.4.dp.toPx())
        rotate(-40f, lc) {
            drawRoundRect(linkCol, Offset(lc.x - s * 1.85f, lc.y - s * 0.5f), Size(s * 2.2f, s), CornerRadius(s * 0.5f), style = ring)
            drawRoundRect(linkCol, Offset(lc.x - s * 0.35f, lc.y - s * 0.5f), Size(s * 2.2f, s), CornerRadius(s * 0.5f), style = ring)
        }

        for (i in 0 until rows) {
            val top = rowsTop + i * pitch
            val due = 0.18f + i * 0.13f
            // A light carrying this row over.
            val q = (u - (due - 0.11f)) / 0.11f
            if (q in 0f..1f) {
                val end = Offset(rowsL + 7.dp.toPx(), top + 7.dp.toPx())
                val at = Offset(mix(lc.x, end.x, q), mix(lc.y, end.y, q) - sin(AC_PI * q) * 8.dp.toPx())
                val al = sin(AC_PI * q)
                glow(accent, at, 2.5.dp.toPx(), al * 0.8f)
                drawCircle(lerp(accent, Color.White, 0.4f).acA(al), 2.4.dp.toPx(), at)
            }
            // The row, sliding in.
            val show = smoothstep(due, due + 0.07f, u) * (1f - smoothstep(0.86f, 0.96f, u))
            val len = (rowsW - 22.dp.toPx()) * (0.5f + 0.4f * hash(i * 5 + 2))
            acRow(rowsL - (1f - show) * 10.dp.toPx(), top, len, lerp(accent, Color.White, 0.12f * i).acA(0.85f), show)
        }
    }
}

/* ---------------- Spotify ---------------- */

/**
 * Songs flow from a plain round "other app" circle along a gentle path into a stack of cards (your
 * Glasswave library), which glows as each one arrives. Not connected, the path is a faint dash and
 * nothing moves along it.
 */
@Composable
fun SpotifyArt(playing: Boolean, connected: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val link by animateFloatAsState(if (connected) 1f else 0f, artSpec(), label = "link")
    val flow = remember { Phase(0.3f) }
    val route = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val pulse = m.pulse
        val ln = link
        backdrop(accent, glow = 0.5f + 0.6f * ln)
        val d = min(w * 0.27f, 108.dp.toPx())
        val src = Offset(cx - d, cy)
        val dst = Offset(cx + d, cy)
        val r = 20.dp.toPx()
        val a = Offset(src.x + r + 6.dp.toPx(), cy)
        val b = Offset(dst.x - 32.dp.toPx(), cy)
        val wave = 8.dp.toPx()
        fun at(k: Float) = Offset(mix(a.x, b.x, k), cy - sin(2f * AC_PI * k) * wave)

        route.reset()
        for (i in 0..28) {
            val q = at(i / 28f)
            if (i == 0) route.moveTo(q.x, q.y) else route.lineTo(q.x, q.y)
        }
        drawPath(
            route, Color.White.acA(mix(0.1f, 0.16f, ln)),
            style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))),
        )

        // The other app: a plain circle with a note.
        glow(accent, src, r * 0.7f, 0.15f + 0.25f * ln)
        drawCircle(Color.White.acA(0.06f), r, src)
        drawCircle(Color.White.acA(mix(0.3f, 0.6f, ln)), r, src, style = Stroke(2.dp.toPx()))
        acNote(Offset(src.x - 2.dp.toPx(), src.y + 6.dp.toPx()), 5.dp.toPx(), Color.White.acA(mix(0.4f, 0.85f, ln)))

        // Songs on their way.
        var arrive = 0f
        if (ln > 0.01f) {
            val f = flow.at(m.t, 0.35f)
            val n = 4
            val sz = 8.dp.toPx()
            for (i in 0 until n) {
                val p = acFrac(f + i.toFloat() / n)
                arrive = max(arrive, smoothstep(0.82f, 0.95f, p) * (1f - smoothstep(0.95f, 1f, p)))
                val al = ln * smoothstep(0f, 0.1f, p) * (1f - smoothstep(0.86f, 1f, p))
                if (al < 0.01f) continue
                val pos = at(p)
                glow(accent, pos, 3.dp.toPx(), al * 0.6f)
                drawRoundRect(
                    lerp(accent, Color.White, 0.25f * hash(i)).acA(al),
                    Offset(pos.x - sz / 2, pos.y - sz / 2), Size(sz, sz), CornerRadius(2.5.dp.toPx()),
                )
            }
        }

        // Your library: a small stack of cards, the front one with a song on it.
        val cw = 46.dp.toPx()
        val ch = 30.dp.toPx()
        glow(accent, dst, 14.dp.toPx(), 0.15f + 0.5f * arrive * ln + 0.2f * pulse)
        for (k in 2 downTo 0) {
            val tl = Offset(dst.x - cw / 2 + k * 5.dp.toPx(), dst.y - ch / 2 - k * 5.dp.toPx())
            val edge = if (k == 0) lerp(Color.White.acA(0.4f), accent, ln) else Color.White.acA(0.22f - k * 0.06f)
            drawRoundRect(Color.White.acA(0.05f + (if (k == 0) 0.04f + 0.1f * arrive * ln else 0f)), tl, Size(cw, ch), CornerRadius(6.dp.toPx()))
            drawRoundRect(edge, tl, Size(cw, ch), CornerRadius(6.dp.toPx()), style = Stroke(1.8.dp.toPx()))
            if (k == 0) {
                val c = 10.dp.toPx()
                val ct = Offset(tl.x + 6.dp.toPx(), tl.y + (ch - c) / 2)
                drawRoundRect(accent.acA(mix(0.35f, 0.9f, ln)), ct, Size(c, c), CornerRadius(2.5.dp.toPx()))
                val x = ct.x + c + 5.dp.toPx()
                drawLine(Color.White.acA(0.55f), Offset(x, ct.y + 2.5.dp.toPx()), Offset(x + 16.dp.toPx(), ct.y + 2.5.dp.toPx()), 2.6.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White.acA(0.25f), Offset(x, ct.y + 7.5.dp.toPx()), Offset(x + 9.dp.toPx(), ct.y + 7.5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/* ---------------- Sync ---------------- */

/**
 * Two round arrows turning, beside a playlist. Every couple of turns a soft light sweeps down the
 * list and each row it passes refreshes: its cover flashes and its lines change a little.
 */
@Composable
fun SyncArt(playing: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val spin = remember { Phase(0.1f) }
    val head = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val pulse = m.pulse
        backdrop(accent, glowX = 0.4f)
        val gap = min(w * 0.2f, 80.dp.toPx())
        val sc = Offset(cx - gap - 4.dp.toPx(), cy)
        val r = 19.dp.toPx()
        val turns = spin.at(m.t, 0.42f)
        val deg = turns * 360f

        // The two arrows.
        glow(accent, sc, r * 0.6f, 0.3f + 0.3f * pulse)
        val sw = 3.dp.toPx()
        for (k in 0..1) {
            val col = if (k == 0) accent else lerp(accent, Color.White, 0.45f)
            val start = deg + k * 180f + 20f
            val sweep = 130f
            drawArc(
                col, start, sweep, false,
                topLeft = Offset(sc.x - r, sc.y - r), size = Size(r * 2, r * 2),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            val e = (start + sweep) * AC_PI / 180f
            val nx = cos(e)
            val ny = sin(e)
            val p = Offset(sc.x + nx * r, sc.y + ny * r)
            val tan = Offset(-ny, nx)
            val nrm = Offset(nx, ny)
            val tip = p + tan * 5.5.dp.toPx()
            val b1 = p + nrm * 5.dp.toPx() - tan * 1.5.dp.toPx()
            val b2 = p - nrm * 5.dp.toPx() - tan * 1.5.dp.toPx()
            head.reset()
            head.moveTo(tip.x, tip.y)
            head.lineTo(b1.x, b1.y)
            head.lineTo(b2.x, b2.y)
            head.close()
            drawPath(head, col)
        }

        // The playlist, refreshed by a sweep every two turns.
        val rowsW = min(w * 0.34f, 140.dp.toPx())
        val rowsL = cx - 14.dp.toPx()
        val pitch = 19.dp.toPx()
        val rows = 4
        val rowsTop = cy - ((rows - 1) * pitch + 14.dp.toPx()) / 2
        val rowsH = (rows - 1) * pitch + 14.dp.toPx()
        val cyc = turns * 0.5f
        val round = floor(cyc).toInt()
        val q = ((cyc - round) / 0.6f).coerceIn(0f, 1f)
        val maxLen = rowsW - 22.dp.toPx()
        for (i in 0 until rows) {
            val top = rowsTop + i * pitch
            val fi = (i + 0.5f) / rows
            val blend = smoothstep(fi, fi + 0.12f, q)
            val old = maxLen * (0.4f + 0.5f * hash((round - 1) * 7 + i * 3 + 11))
            val new = maxLen * (0.4f + 0.5f * hash(round * 7 + i * 3 + 11))
            val flash = smoothstep(fi - 0.1f, fi, q) * (1f - smoothstep(fi, fi + 0.25f, q))
            val cover = lerp(accent.acA(0.7f), Color.White.acA(0.95f), flash * 0.6f)
            if (flash > 0.01f) glow(accent, Offset(rowsL + 7.dp.toPx(), top + 7.dp.toPx()), 5.dp.toPx(), flash * 0.8f)
            acRow(rowsL, top, mix(old, new, blend), cover, 1f)
        }
        if (q > 0f && q < 1f) {
            val y = rowsTop - 4.dp.toPx() + q * (rowsH + 8.dp.toPx())
            val al = sin(AC_PI * q) * 0.55f
            drawLine(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.acA(al), Color.Transparent),
                    startX = rowsL - 8.dp.toPx(), endX = rowsL + rowsW + 8.dp.toPx(),
                ),
                Offset(rowsL - 8.dp.toPx(), y), Offset(rowsL + rowsW + 8.dp.toPx(), y), 1.6.dp.toPx(), StrokeCap.Round,
            )
        }
    }
}
