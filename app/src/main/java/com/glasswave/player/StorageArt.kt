// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/*
 * Small moving pictures for the Storage & privacy settings, in the same look as the Audio Lab ones
 * (see SoundArt.kt for the clock, the beat and the shared helpers).
 *
 * - Each takes `playing` (is music playing right now) and the setting it shows, passed in by the
 *   settings screen, so the phone and PC copies can each give their own values.
 * - They fill the width and are [SettingArtHeight] tall unless your modifier sets a height.
 * - Drawing happens only in the draw phase, with plain shapes and gradients, no blur.
 */

/* ---------------- Downloads ---------------- */

/**
 * Songs dropping one by one into a jar, with a little ripple where each one lands. The jar is
 * filled up to [fill] (0 = empty, 1 = full) and glides there when it changes.
 */
@Composable
fun DownloadsArt(playing: Boolean, fill: Float, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val level by animateFloatAsState(fill.coerceIn(0f, 1f), artSpec(), label = "fill")
    val inside = remember { Path() }
    val liquid = remember { Path() }
    val edge = remember { Path() }
    val rim = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        backdrop(accent, glow = 0.7f + 0.5f * level)
        val cx = w / 2
        val jw = min(w * 0.24f, 78.dp.toPx())
        val bottom = h - 12.dp.toPx()
        val top = bottom - h * 0.6f
        val left = cx - jw / 2
        val right = cx + jw / 2
        val corner = 12.dp.toPx()
        val inset = 3.dp.toPx()
        val surface = bottom - inset - level * (bottom - top - inset * 2)

        // A faint shelf the jar stands on.
        drawLine(Color.White.copy(alpha = 0.07f), Offset(w * 0.22f, bottom + 1.dp.toPx()), Offset(w * 0.78f, bottom + 1.dp.toPx()), 1.dp.toPx())
        glow(accent, Offset(cx, mix(bottom, top, 0.2f + level * 0.5f)), jw * 0.32f, 0.3f + 0.45f * level + 0.2f * pulse)

        // What's inside, with a gently moving top.
        if (level > 0.003f) {
            inside.reset()
            inside.addRoundRect(RoundRect(left, top - 20.dp.toPx(), right, bottom, CornerRadius(corner)))
            liquid.reset()
            edge.reset()
            val amp = (1.1f + 1.6f * pulse).dp.toPx() * smoothstep(0f, 0.08f, level)
            val step = 3.dp.toPx()
            liquid.moveTo(left, bottom)
            var x = left
            while (true) {
                val y = surface + amp * sin((x - left) / jw * 2f * PI.toFloat() * 1.3f + t * 2.2f)
                liquid.lineTo(x, y)
                if (x == left) edge.moveTo(x, y) else edge.lineTo(x, y)
                if (x >= right) break
                x = min(x + step, right)
            }
            liquid.lineTo(right, bottom)
            liquid.close()
            clipPath(inside) {
                drawPath(
                    liquid,
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.7f), accent.copy(alpha = 0.28f)), startY = surface, endY = bottom),
                )
                drawPath(edge, lerp(accent, Color.White, 0.4f).copy(alpha = 0.8f), style = Stroke(1.4.dp.toPx()))
            }
        }

        // Little marks up the side, like a measuring jar.
        for (i in 1..3) {
            val y = mix(bottom, top, i / 4f)
            drawLine(Color.White.copy(alpha = 0.16f), Offset(right - 8.dp.toPx(), y), Offset(right - 3.dp.toPx(), y), 1.dp.toPx(), cap = StrokeCap.Round)
        }

        // The jar: open at the top, rounded at the bottom.
        rim.reset()
        rim.moveTo(left, top)
        rim.lineTo(left, bottom - corner)
        rim.cubicTo(left, bottom - corner * 0.45f, left + corner * 0.45f, bottom, left + corner, bottom)
        rim.lineTo(right - corner, bottom)
        rim.cubicTo(right - corner * 0.45f, bottom, right, bottom - corner * 0.45f, right, bottom - corner)
        rim.lineTo(right, top)
        drawPath(rim, Color.White.copy(alpha = 0.38f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Songs falling in, then a ripple where each one lands.
        val noteS = 13.dp.toPx()
        val land = 0.72f
        for (i in 0 until 3) {
            val raw = t * 0.42f + i / 3f
            val k = floor(raw).toInt()
            val a = raw - k
            val x = cx + (hash(k * 3 + i) - 0.5f) * jw * 0.5f
            if (a < land) {
                val f = a / land
                val y = mix(-noteS, surface - noteS * 0.35f, f * f)
                val alpha = smoothstep(0f, 0.12f, f) * (1f - 0.6f * smoothstep(0.85f, 1f, f))
                glow(accent, Offset(x, y), noteS * 0.3f, alpha * (0.5f + 0.5f * pulse))
                stNote(Offset(x, y), noteS, lerp(Color.White, accent, 0.25f).copy(alpha = alpha))
            } else {
                val f = (a - land) / (1f - land)
                val rx = mix(3f, 14f, f).dp.toPx()
                val ry = rx * 0.28f
                drawOval(
                    Color.White.copy(alpha = 0.5f * (1f - f)), Offset(x - rx, surface - ry), Size(rx * 2, ry * 2),
                    style = Stroke(1.2.dp.toPx()),
                )
            }
        }
    }
}

/* ---------------- Download quality ---------------- */

/**
 * A Wi-Fi sign (or mobile signal bars when [wifi] is false) sending sound into a waveform. Low
 * quality ([level] 0) makes it blocky and rough; higher quality fills in the detail until it's smooth.
 */
@Composable
fun DownloadQualityArt(playing: Boolean, level: Float, wifi: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val q by animateFloatAsState(level.coerceIn(0f, 1f), artSpec(), label = "quality")
    val onWifi by animateFloatAsState(if (wifi) 1f else 0f, artSpec(), label = "wifi")
    val phase = remember { Phase(0.8f) }
    val line = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        val mid = h / 2
        val ix = max(40.dp.toPx(), w * 0.15f)
        backdrop(accent, glowX = 0.55f, glow = 0.7f + 0.5f * q)
        stConnection(Offset(ix, mid + 13.dp.toPx()), onWifi, t, accent, pulse)

        val x0 = ix + 34.dp.toPx()
        val x1 = w - 16.dp.toPx()

        // Bits of sound on their way from the connection to the wave.
        for (i in 0 until 3) {
            var a = t * 0.9f + i / 3f
            a -= floor(a)
            val x = mix(ix + 20.dp.toPx(), x0 - 2.dp.toPx(), a)
            drawCircle(accent.copy(alpha = (sin(PI.toFloat() * a) * 0.7f).coerceIn(0f, 1f)), 1.8.dp.toPx(), Offset(x, mid))
        }
        if (x1 - x0 < 20.dp.toPx()) return@ArtCanvas

        drawLine(Color.White.copy(alpha = 0.08f), Offset(x0, mid), Offset(x1, mid), 1.dp.toPx())

        // The wave, sampled in steps: wide, coarse steps for low quality, fine ones for high.
        val amp = h * 0.27f * (1f + 0.15f * pulse)
        val flow = phase.at(t, 1.6f)
        val lam = 70.dp.toPx()
        val step = mix(11f, 1.6f, q).dp.toPx()
        val levels = mix(3f, 48f, q * q)
        line.reset()
        var x = x0
        var first = true
        while (x < x1) {
            val u = (x - x0) / (x1 - x0)
            val env = sin(PI.toFloat() * u).coerceAtLeast(0f).pow(0.8f)
            val k = (x - x0) / lam * 2f * PI.toFloat() - flow
            var s = 0.62f * sin(k) + 0.25f * sin(2.3f * k + 1.3f)
            s += q * 0.18f * sin(5.1f * k - 0.7f) + q * q * 0.1f * sin(9.7f * k + 2f)
            val y = mid - round(s * env * levels) / levels * amp
            val nx = min(x + step, x1)
            if (first) {
                line.moveTo(x, y)
                first = false
            } else {
                line.lineTo(x, y)
            }
            line.lineTo(nx, y)
            x = nx
        }
        drawPath(line, accent.copy(alpha = 0.16f + 0.12f * pulse), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, accent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A Wi-Fi sign ([wifi] 1) or mobile signal bars ([wifi] 0), sitting on [base], lighting up in turn. */
private fun DrawScope.stConnection(base: Offset, wifi: Float, t: Float, accent: Color, pulse: Float) {
    glow(accent, Offset(base.x, base.y - 10.dp.toPx()), 10.dp.toPx(), 0.5f + 0.3f * pulse)
    if (wifi > 0.01f) {
        drawCircle(Color.White.copy(alpha = 0.9f * wifi), 2.6.dp.toPx(), base)
        for (i in 0 until 3) {
            val r = (9f + 7f * i).dp.toPx()
            val lit = 0.5f + 0.5f * sin(t * 3f - i * 1.1f)
            drawArc(
                lerp(Color.White, accent, lit).copy(alpha = ((0.4f + 0.55f * lit) * wifi).coerceIn(0f, 1f)), 225f, 90f, false,
                topLeft = Offset(base.x - r, base.y - r), size = Size(r * 2, r * 2),
                style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
    val cell = 1f - wifi
    if (cell > 0.01f) {
        val bw = 4.5.dp.toPx()
        val gap = 3.dp.toPx()
        val total = bw * 4 + gap * 3
        val floorY = base.y + 2.6.dp.toPx()
        for (i in 0 until 4) {
            val bh = (6f + 5.5f * i).dp.toPx()
            val lit = 0.5f + 0.5f * sin(t * 3f - i * 0.9f)
            drawRoundRect(
                lerp(Color.White, accent, lit).copy(alpha = ((0.4f + 0.55f * lit) * cell).coerceIn(0f, 1f)),
                Offset(base.x - total / 2 + i * (bw + gap), floorY - bh), Size(bw, bh), CornerRadius(bw / 2),
            )
        }
    }
}

/* ---------------- Played songs (temporary) ---------------- */

/**
 * Songs travelling past a small glass box: they slow down inside it (kept for a while, so they
 * play again instantly), then carry on out the other side and fade away. A little clock on the
 * box shows they only stay there for a while.
 */
@Composable
fun PlayedSongsArt(playing: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        backdrop(accent)
        val cx = w / 2
        val cy = h / 2
        val bw = 54.dp.toPx()
        val bh = 44.dp.toPx()
        val boxTl = Offset(cx - bw / 2, cy - bh / 2)
        val radius = CornerRadius(10.dp.toPx())

        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))
        drawLine(Color.White.copy(alpha = 0.08f), Offset(16.dp.toPx(), cy), Offset(w - 16.dp.toPx(), cy), 1.dp.toPx(), pathEffect = dash)

        // The songs, quick on the way in and out, slow in the middle.
        val noteS = 14.dp.toPx()
        val tau = 2f * PI.toFloat()
        var near = 0f
        for (i in 0 until 4) {
            var u = t * 0.11f + i / 4f
            u -= floor(u)
            val p = u + 0.8f * sin(tau * u) / tau
            val x = mix(-noteS, w + noteS, p)
            val y = cy + 3.dp.toPx() * sin(t * 1.3f + i * 1.9f)
            val closeBy = 1f - smoothstep(bw * 0.2f, bw * 0.9f, abs(x - cx))
            near = max(near, closeBy)
            val kept = smoothstep(0.45f, 0.55f, u)
            val alpha = smoothstep(0.02f, 0.12f, u) * (1f - smoothstep(0.72f, 0.97f, u))
            if (alpha < 0.01f) continue
            glow(accent, Offset(x, y), noteS * 0.28f, alpha * (0.45f + 0.35f * pulse))
            stNote(
                Offset(x, y), noteS * (1f - 0.15f * kept),
                lerp(Color.White, accent, 0.25f + 0.6f * kept).copy(alpha = alpha * (1f - 0.45f * closeBy)),
            )
        }

        // The box, brighter while a song is inside.
        glow(accent, Offset(cx, cy), 14.dp.toPx(), 0.35f + 0.45f * near + 0.2f * pulse)
        drawRoundRect(Color.White.copy(alpha = 0.06f + 0.05f * near), boxTl, Size(bw, bh), radius)
        drawRoundRect(accent.copy(alpha = 0.45f + 0.4f * near), boxTl, Size(bw, bh), radius, style = Stroke(1.6.dp.toPx()))

        // A small clock on its corner.
        val cc = Offset(boxTl.x + bw - 3.dp.toPx(), boxTl.y + 3.dp.toPx())
        val cr = 6.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.45f), cr, cc)
        drawCircle(Color.White.copy(alpha = 0.7f), cr, cc, style = Stroke(1.2.dp.toPx()))
        val hand = t * 1.2f - PI.toFloat() / 2
        drawLine(
            Color.White.copy(alpha = 0.85f), cc, Offset(cc.x + cos(hand) * cr * 0.65f, cc.y + sin(hand) * cr * 0.65f),
            1.2.dp.toPx(), cap = StrokeCap.Round,
        )
        drawLine(Color.White.copy(alpha = 0.85f), cc, Offset(cc.x, cc.y - cr * 0.5f), 1.2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/* ---------------- Space for played songs ---------------- */

/**
 * A slowly turning disc with a ring around it. The lit part of the ring shows how much space the
 * played songs may use ([fraction] 0..1) and glides there when the size changes. Five small marks
 * around it stand for the five size choices.
 */
@Composable
fun CacheSpaceArt(playing: Boolean, fraction: Float, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), artSpec(), label = "space")
    val spin = remember { Phase(0f) }
    ArtCanvas(modifier, SettingArtHeight) {
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        val c = center
        backdrop(accent, glow = 0.6f + 0.6f * f)
        val r = h * mix(0.3f, 0.35f, f)
        val sw = 7.dp.toPx()
        val tl = Offset(c.x - r, c.y - r)
        val sz = Size(r * 2, r * 2)

        // The disc: faint grooves and two soft glints turning slowly.
        for (g in 1..3) {
            drawCircle(Color.White.copy(alpha = 0.05f), r * (0.28f + 0.17f * g), c, style = Stroke(1.dp.toPx()))
        }
        val gr = r * 0.6f
        rotate(spin.at(t, 40f), c) {
            for (a in listOf(-25f, 155f)) {
                drawArc(
                    Color.White.copy(alpha = 0.07f), a, 50f, false,
                    topLeft = Offset(c.x - gr, c.y - gr), size = Size(gr * 2, gr * 2),
                    style = Stroke(r * 0.36f),
                )
            }
        }
        glow(accent, c, 5.dp.toPx() * (1f + 0.25f * pulse), 0.6f)
        drawCircle(accent, 5.dp.toPx(), c)
        drawCircle(Color.White.copy(alpha = 0.85f), 2.dp.toPx(), c)

        // The ring: the empty track, then the lit part.
        drawCircle(Color.White.copy(alpha = 0.08f), r, c, style = Stroke(sw))
        if (f > 0.001f) {
            drawArc(accent.copy(alpha = 0.18f + 0.15f * pulse), -90f, 360f * f, false, tl, sz, style = Stroke(sw * 2.2f, cap = StrokeCap.Round))
            drawArc(accent, -90f, 360f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            // A glint running along the lit part.
            var g = t * 0.35f
            g -= floor(g)
            val ang = (-90f + 360f * f * g) * PI.toFloat() / 180f
            drawCircle(
                Color.White.copy(alpha = (sin(PI.toFloat() * g) * 0.8f).coerceIn(0f, 1f)), sw * 0.42f,
                Offset(c.x + cos(ang) * r, c.y + sin(ang) * r),
            )
        }

        // One mark for each size choice, lit once the ring reaches it.
        val r1 = r + sw + 3.dp.toPx()
        val r2 = r1 + 4.dp.toPx()
        for (j in 1..5) {
            val ang = (-90f + 72f * j) * PI.toFloat() / 180f
            val on = smoothstep(j / 5f - 0.06f, j / 5f - 0.01f, f)
            drawLine(
                lerp(Color.White.copy(alpha = 0.22f), accent, on), Offset(c.x + cos(ang) * r1, c.y + sin(ang) * r1),
                Offset(c.x + cos(ang) * r2, c.y + sin(ang) * r2), 1.6.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

/* ---------------- Privacy ---------------- */

/**
 * A shield with a lock. When [keep] (remember what I play) is on, small dots for the songs you
 * play line up beside it, newest closest to the shield. Turn it off and they shrink and fade away.
 */
@Composable
fun PrivacyArt(playing: Boolean, keep: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val k by animateFloatAsState(if (keep) 1f else 0f, artSpec(), label = "keep")
    val scroll = remember { Phase(0.3f) }
    val shield = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        val sx = max(48.dp.toPx(), w * 0.22f)
        val cy = h / 2
        backdrop(accent, glowX = mix(0.25f, 0.5f, k))

        // The history: dots lining up beside the shield, newest nearest it.
        if (k > 0.01f) {
            val x0 = sx + 34.dp.toPx()
            val x1 = w - 18.dp.toPx()
            val gap = 18.dp.toPx()
            val pos = scroll.at(t, 0.45f * k)
            val first = floor(pos).toInt()
            val frac = pos - first
            val drop = (1f - k) * 8.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.1f * k), Offset(x0, cy + drop), Offset(x1, cy + drop), 1.dp.toPx())
            val n = ((x1 - x0) / gap).toInt() + 2
            for (i in 0 until n) {
                val slot = i + frac
                val x = x0 + slot * gap
                if (x > x1) break
                val id = first - i
                val grow = smoothstep(0f, 0.6f, slot)
                val fade = 1f - smoothstep(x1 - gap * 3f, x1, x)
                val a = grow * fade * k
                if (a < 0.01f) continue
                val r = (2.2f + 1.8f * hash(id)).dp.toPx() * (0.5f + 0.5f * grow) * (0.5f + 0.5f * k)
                val newest = 1f - smoothstep(0f, 1.5f, slot)
                val p = Offset(x, cy + drop)
                glow(accent, p, r, a * (0.35f + 0.5f * newest * (0.6f + pulse)))
                drawCircle(lerp(Color.White, accent, 0.4f + 0.5f * hash(id * 5 + 2)).copy(alpha = (a * 0.9f).coerceIn(0f, 1f)), r, p)
            }
        }

        // The shield, glowing a little more when nothing is kept.
        val c = Offset(sx, cy)
        stShield(shield, c, 38.dp.toPx(), 46.dp.toPx())
        glow(accent, c, 14.dp.toPx() * (1f + 0.15f * pulse), 0.5f + 0.3f * (1f - k))
        drawPath(shield, accent.copy(alpha = 0.16f + 0.12f * (1f - k)))
        drawPath(shield, accent, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        stLock(Offset(sx, cy + 2.dp.toPx()), accent)
    }
}

/** Builds a shield shape, [sw] wide and [sh] tall, centred on [c], into [p]. */
private fun stShield(p: Path, c: Offset, sw: Float, sh: Float) {
    val top = c.y - sh / 2
    val bottom = c.y + sh / 2
    val left = c.x - sw / 2
    val right = c.x + sw / 2
    p.reset()
    p.moveTo(c.x, top)
    p.cubicTo(c.x + sw * 0.22f, top + sh * 0.1f, right - sw * 0.08f, top + sh * 0.12f, right, top + sh * 0.14f)
    p.lineTo(right, top + sh * 0.45f)
    p.cubicTo(right, top + sh * 0.75f, c.x + sw * 0.2f, bottom - sh * 0.06f, c.x, bottom)
    p.cubicTo(c.x - sw * 0.2f, bottom - sh * 0.06f, left, top + sh * 0.75f, left, top + sh * 0.45f)
    p.lineTo(left, top + sh * 0.14f)
    p.cubicTo(left + sw * 0.08f, top + sh * 0.12f, c.x - sw * 0.22f, top + sh * 0.1f, c.x, top)
    p.close()
}

/** A small closed padlock centred on [c]. */
private fun DrawScope.stLock(c: Offset, accent: Color) {
    val bw = 14.dp.toPx()
    val bh = 11.dp.toPx()
    val bodyTop = c.y - bh / 2 + 2.dp.toPx()
    val sr = 4.5.dp.toPx()
    val lift = 2.dp.toPx()
    val white = Color.White.copy(alpha = 0.92f)
    val shackle = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
    drawArc(white, 180f, 180f, false, topLeft = Offset(c.x - sr, bodyTop - lift - sr), size = Size(sr * 2, sr * 2), style = shackle)
    drawLine(white, Offset(c.x - sr, bodyTop - lift), Offset(c.x - sr, bodyTop), 2.dp.toPx())
    drawLine(white, Offset(c.x + sr, bodyTop - lift), Offset(c.x + sr, bodyTop), 2.dp.toPx())
    drawRoundRect(white, Offset(c.x - bw / 2, bodyTop), Size(bw, bh), CornerRadius(2.5.dp.toPx()))
    drawCircle(accent, 1.7.dp.toPx(), Offset(c.x, bodyTop + bh * 0.45f))
}

/* ---------------- Shared ---------------- */

/** A small music note about [s] tall, centred on [c]. */
private fun DrawScope.stNote(c: Offset, s: Float, color: Color) {
    val r = s * 0.24f
    val head = Offset(c.x - r * 0.6f, c.y + s * 0.3f)
    drawOval(color, topLeft = Offset(head.x - r * 1.2f, head.y - r * 0.9f), size = Size(r * 2.4f, r * 1.8f))
    val stemX = head.x + r * 1.05f
    val top = c.y - s * 0.5f
    val sw = max(1f, s * 0.1f)
    drawLine(color, Offset(stemX, head.y), Offset(stemX, top), sw, cap = StrokeCap.Round)
    drawLine(color, Offset(stemX, top), Offset(stemX + s * 0.3f, top + s * 0.24f), sw, cap = StrokeCap.Round)
}
