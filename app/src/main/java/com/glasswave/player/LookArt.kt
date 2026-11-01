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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * Small moving pictures for the Appearance settings, in the same style as the sound ones in
 * SoundArt.kt: each shows what its setting does and changes with it.
 *
 * - Each takes `playing` (is music playing right now) and the setting's value(s), and nothing is
 *   read from Settings here, so the same file works in the phone and PC apps.
 * - They fill the width, are [SettingArtHeight] tall, and clip themselves to rounded corners.
 * - Drawing happens only in the draw phase, with plain shapes and gradients (no blur, no pictures).
 */

/** The dark used for screens and icons on light buttons. */
private val LkDark = Color(0xFF0D0D11)

/** A rounded rectangle outline in [p] (reused, so nothing new is made each frame). */
private fun lkRound(p: Path, r: Rect, corner: Float): Path {
    p.reset()
    p.addRoundRect(RoundRect(r, CornerRadius(corner)))
    return p
}

/** 0..1, going round and round. */
private fun lkFrac(x: Float): Float = x - floor(x)

/** Goes 0 → 1 → 0 once per whole number of [x], resting a moment at each end (an eased back and forth). */
private fun lkEase(x: Float): Float {
    val k = lkFrac(x)
    val tri = if (k < 0.5f) k * 2f else 2f - k * 2f
    return smoothstep(0.12f, 0.88f, tri)
}

/** A plain album cover: the accent colour, a soft sun and two hills. [drift] moves what's on it. */
private fun DrawScope.lkCover(r: Rect, corner: Float, accent: Color, clip: Path, drift: Float = 0f, alpha: Float = 1f) {
    if (alpha <= 0.005f) return
    clipPath(lkRound(clip, r, corner)) {
        drawRect(
            Brush.linearGradient(
                listOf(lerp(accent, Color.White, 0.18f), lerp(accent, Color.Black, 0.6f)),
                start = r.topLeft, end = r.bottomRight,
            ),
            r.topLeft, r.size, alpha = alpha,
        )
        val w = r.width
        val sun = Offset(
            r.left + w * (0.68f + 0.06f * sin(drift * 0.7f)),
            r.top + r.height * (0.32f + 0.05f * cos(drift * 0.9f)),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f * alpha), Color.Transparent), sun, w * 0.34f),
            w * 0.34f, sun,
        )
        drawCircle(Color.White.copy(alpha = 0.8f * alpha), w * 0.13f, sun)
        val hill1 = Offset(r.left + w * (0.28f + 0.07f * sin(drift * 0.5f)), r.bottom + w * 0.32f)
        drawCircle(lerp(accent, Color.Black, 0.35f).copy(alpha = alpha), w * 0.58f, hill1)
        val hill2 = Offset(r.left + w * (0.9f - 0.06f * sin(drift * 0.5f + 1f)), r.bottom + w * 0.42f)
        drawCircle(lerp(accent, Color.Black, 0.62f).copy(alpha = alpha), w * 0.62f, hill2)
    }
}

/** A square in the middle of the picture, [inset] from the top and bottom. */
private fun DrawScope.lkSquare(inset: Float): Rect {
    val s = size.height - inset * 2
    val x = (size.width - s) / 2
    return Rect(x, inset, x + s, inset + s)
}

/* ---------------- Style: frosted glass ---------------- */

/**
 * A glass panel sliding over moving colour blobs. With frosted glass on, the blobs behind the panel
 * look soft and misty (lighter, or deeper with Dark); off, it's clear glass with a bright edge.
 */
@Composable
fun GlassStyleArt(playing: Boolean, frosted: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val frost by animateFloatAsState(if (frosted) 1f else 0f, artSpec(), label = "frost")
    val deep by animateFloatAsState(if (frosted && dark) 1f else 0f, artSpec(), label = "dark")
    val clip = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        backdrop(accent, glow = 0.8f)

        fun blobs(sharp: Float, soft: Float) {
            for (i in 0 until 4) {
                val c = Offset(
                    w * (0.17f + 0.22f * i) + w * 0.07f * sin(t * (0.45f + 0.1f * i) + i * 2.1f),
                    h * 0.5f + h * 0.24f * cos(t * (0.38f + 0.07f * i) + i * 1.3f),
                )
                val r = h * (0.17f + 0.05f * hash(i + 3)) * (1f + 0.08f * pulse)
                val col = if (i % 2 == 0) accent else lerp(accent, Color.White, 0.55f)
                if (sharp > 0.01f) drawCircle(col.copy(alpha = 0.85f * sharp), r, c)
                if (soft > 0.01f) {
                    // Frosted look: bigger, fainter circles that fade out at the edge.
                    val rr = r * 1.9f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(col.copy(alpha = 0.42f * soft), col.copy(alpha = 0.16f * soft), Color.Transparent), c, rr,
                        ),
                        rr, c,
                    )
                }
            }
        }
        blobs(sharp = 1f, soft = 0f)

        val pw = min(w * 0.46f, h * 1.9f)
        val ph = h * 0.64f
        val px = (w - pw) / 2 + w * 0.17f * sin(t * 0.35f)
        val py = (h - ph) / 2
        val panel = Rect(px, py, px + pw, py + ph)
        val corner = 16.dp.toPx()
        clipPath(lkRound(clip, panel, corner)) {
            if (frost > 0.01f) {
                // Hide the sharp blobs behind the panel and show soft ones instead.
                drawRect(lerp(Color(0xFF3A3A42), Color(0xFF0A0A0D), deep).copy(alpha = 0.88f * frost), panel.topLeft, panel.size)
                blobs(sharp = 0f, soft = frost * (1f - 0.45f * deep))
                drawRect(Color.White.copy(alpha = 0.08f * frost * (1f - deep)), panel.topLeft, panel.size)
            }
            // Clear glass: a light sheen across the top.
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.16f * (1f - frost)), Color.Transparent),
                    startY = py, endY = py + ph * 0.55f,
                ),
                panel.topLeft, panel.size,
            )
        }
        drawRoundRect(
            Color.White.copy(alpha = mix(0.38f, 0.16f, frost)), panel.topLeft, panel.size, CornerRadius(corner),
            style = Stroke(1.dp.toPx()),
        )
        if (frost < 0.99f) {
            drawLine(
                Color.White.copy(alpha = 0.6f * (1f - frost)),
                Offset(px + corner, py + 1.6.dp.toPx()), Offset(px + pw * 0.45f, py + 1.6.dp.toPx()),
                1.6.dp.toPx(), cap = StrokeCap.Round,
            )
        }

        // What sits on the panel: a song row.
        val cy = py + ph / 2
        val dotR = ph * 0.17f
        val dotX = px + ph * 0.3f
        drawCircle(accent.copy(alpha = 0.92f), dotR, Offset(dotX, cy))
        val tx = dotX + dotR + 9.dp.toPx()
        val room = px + pw - 12.dp.toPx() - tx
        if (room > 0f) {
            drawLine(Color.White.copy(alpha = 0.8f), Offset(tx, cy - 5.dp.toPx()), Offset(tx + room * 0.8f, cy - 5.dp.toPx()), 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(tx, cy + 5.dp.toPx()), Offset(tx + room * 0.5f, cy + 5.dp.toPx()), 2.4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/* ---------------- Layout ---------------- */

/**
 * Two little screens: Home on the left, Library on the right. Their tiles glide between a list
 * (small covers with lines of text) and big covers (a row for Home, a grid for Library).
 */
@Composable
fun LayoutArt(playing: Boolean, homeCovers: Boolean, libraryList: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val home by animateFloatAsState(if (homeCovers) 1f else 0f, artSpec(), label = "home")
    val lib by animateFloatAsState(if (libraryList) 0f else 1f, artSpec(), label = "library")
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        backdrop(accent)
        val pad = 10.dp.toPx()
        val gap = 10.dp.toPx()
        val half = (w - pad * 2 - gap) / 2
        val screenW = min(half, h * 1.7f)
        for (side in 0..1) {
            val cx = pad + half / 2 + side * (half + gap)
            val r = Rect(cx - screenW / 2, pad, cx + screenW / 2, h - pad)
            val corner = CornerRadius(12.dp.toPx())
            drawRoundRect(Color.White.copy(alpha = 0.05f), r.topLeft, r.size, corner)
            drawRoundRect(Color.White.copy(alpha = 0.12f), r.topLeft, r.size, corner, style = Stroke(1.dp.toPx()))
            val i = 9.dp.toPx()
            val inner = Rect(r.left + i, r.top + i, r.right - i, r.bottom - i)
            if (side == 0) lkTiles(inner, 3, 3, home, accent, m.t, m.pulse, 0)
            else lkTiles(inner, 4, 2, lib, accent, m.t, m.pulse, 10)
        }
    }
}

/** [n] tiles in [area]: a list at [grid] 0, a grid of [cols] columns at 1. One of them glows (what's playing). */
private fun DrawScope.lkTiles(area: Rect, n: Int, cols: Int, grid: Float, accent: Color, t: Float, pulse: Float, seed: Int) {
    val gap = 5.dp.toPx()
    val rows = (n + cols - 1) / cols
    val cell = min((area.width - gap * (cols - 1)) / cols, (area.height - gap * (rows - 1)) / rows)
    val gx = area.left + (area.width - (cell * cols + gap * (cols - 1))) / 2
    val gy = area.top + (area.height - (cell * rows + gap * (rows - 1))) / 2
    val rowGap = 4.dp.toPx()
    val rowH = (area.height - rowGap * (n - 1)) / n
    val lit = ((floor(t * 0.45f).toInt() % n) + n) % n
    val text = 1f - grid
    for (i in 0 until n) {
        val s = mix(rowH, cell, grid)
        val x = mix(area.left, gx + (i % cols) * (cell + gap), grid)
        val y = mix(area.top + i * (rowH + rowGap), gy + (i / cols) * (cell + gap), grid)
        val on = i == lit
        val tone = 0.35f + 0.45f * hash(seed + i)
        val corner = CornerRadius(mix(3f, 6f, grid).dp.toPx())
        if (on) glow(accent, Offset(x + s / 2, y + s / 2), s * 0.32f * (1f + 0.3f * pulse), 0.55f)
        drawRoundRect(
            Brush.linearGradient(
                listOf(lerp(accent, Color.White, 0.2f).copy(alpha = tone + 0.15f), lerp(accent, Color.Black, 0.5f).copy(alpha = tone)),
                start = Offset(x, y), end = Offset(x + s, y + s),
            ),
            Offset(x, y), Size(s, s), corner,
        )
        if (on) drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(x, y), Size(s, s), corner, style = Stroke(1.2.dp.toPx()))
        if (text > 0.01f) {
            val tx = x + s + 7.dp.toPx()
            val room = area.right - tx
            if (room > 4.dp.toPx()) {
                val first = if (on) accent else Color.White
                drawLine(first.copy(alpha = 0.6f * text), Offset(tx, y + s * 0.36f), Offset(tx + room * 0.75f, y + s * 0.36f), 2.5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.25f * text), Offset(tx, y + s * 0.7f), Offset(tx + room * 0.45f, y + s * 0.7f), 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/* ---------------- Wallpaper ---------------- */

/**
 * A little screen with two glass panels on it. With a wallpaper, the picture behind them gets
 * softer with Blur and darker with Darken; without one, the cover's colours glow behind them, as
 * strong as Colour strength. Glass panels makes the panels clearer or more solid.
 */
@Composable
fun WallpaperArt(
    playing: Boolean, hasWallpaper: Boolean, blur: Float, dim: Float, colorStrength: Float, glassPanels: Float,
    modifier: Modifier = Modifier,
) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val wp by animateFloatAsState(if (hasWallpaper) 1f else 0f, artSpec(), label = "wallpaper")
    val bl by animateFloatAsState(blur.coerceIn(0f, 1f), artSpec(), label = "blur")
    val dm by animateFloatAsState(dim.coerceIn(0f, 0.9f), artSpec(), label = "dim")
    val cs by animateFloatAsState(colorStrength.coerceIn(0.3f, 1.7f), artSpec(), label = "strength")
    val gp by animateFloatAsState(((glassPanels - 0.3f) / 1.5f).coerceIn(0f, 1f), artSpec(), label = "glass")
    val clip = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        backdrop(accent, glow = 0.6f)
        val sh = h - 16.dp.toPx()
        val sw = min(w * 0.62f, sh * 2.4f)
        val scr = Rect((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
        val corner = 14.dp.toPx()
        clipPath(lkRound(clip, scr, corner)) {
            drawRect(LkDark, scr.topLeft, scr.size)

            // No wallpaper: the cover's colours, as strong as Colour strength.
            val cover = 1f - wp
            if (cover > 0.01f) {
                val k = (cs / 1.7f) * cover
                for (i in 0..1) {
                    val c = Offset(
                        scr.left + sw * (0.3f + 0.4f * i) + sw * 0.08f * sin(t * 0.4f + i * 2.5f),
                        scr.top + sh * (0.4f + 0.2f * i) + sh * 0.12f * cos(t * 0.33f + i),
                    )
                    val col = if (i == 0) accent else lerp(accent, Color.White, 0.4f)
                    val r = sw * 0.42f
                    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = (0.55f - 0.25f * i) * k), Color.Transparent), c, r), r, c)
                }
            }

            // A wallpaper: sky, sun and hills, softened by Blur, dimmed by Darken.
            if (wp > 0.01f) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(lerp(accent, Color.Black, 0.5f).copy(alpha = wp), lerp(accent, Color.Black, 0.85f).copy(alpha = wp)),
                        startY = scr.top, endY = scr.bottom,
                    ),
                    scr.topLeft, scr.size,
                )
                val edge = (1f + 16f * bl).dp.toPx()
                fun soft(col: Color, c: Offset, r: Float) {
                    val big = r + edge / 2
                    drawCircle(
                        Brush.radialGradient(
                            0f to col, ((r - edge / 2) / big).coerceIn(0f, 0.99f) to col, 1f to Color.Transparent,
                            center = c, radius = big,
                        ),
                        big, c,
                    )
                }
                val sun = Offset(scr.left + sw * 0.7f, scr.top + sh * (0.34f + 0.04f * sin(t * 0.3f)))
                soft(Color.White.copy(alpha = 0.85f * wp), sun, sh * 0.13f)
                soft(lerp(accent, Color.Black, 0.3f).copy(alpha = wp), Offset(scr.left + sw * (0.3f + 0.03f * sin(t * 0.25f)), scr.bottom + sh * 0.5f), sh * 0.85f)
                soft(lerp(accent, Color.Black, 0.6f).copy(alpha = wp), Offset(scr.left + sw * (0.85f - 0.03f * sin(t * 0.25f + 1f)), scr.bottom + sh * 0.7f), sh * 0.95f)
                drawRect(Color.Black.copy(alpha = dm * wp), scr.topLeft, scr.size)
            }

            // The glass panels: a title bar and a mini player.
            val gi = 8.dp.toPx()
            val pc = CornerRadius(8.dp.toPx())
            val fill = Color.White.copy(alpha = mix(0.04f, 0.24f, gp))
            val line = Color.White.copy(alpha = mix(0.08f, 0.32f, gp))
            val top = Rect(scr.left + gi, scr.top + gi, scr.right - gi, scr.top + gi + sh * 0.22f)
            val bottom = Rect(scr.left + gi, scr.bottom - gi - sh * 0.3f, scr.right - gi, scr.bottom - gi)
            for (p in listOf(top, bottom)) {
                drawRoundRect(fill, p.topLeft, p.size, pc)
                drawRoundRect(line, p.topLeft, p.size, pc, style = Stroke(1.dp.toPx()))
            }
            drawLine(
                Color.White.copy(alpha = 0.75f), Offset(top.left + 9.dp.toPx(), top.center.y), Offset(top.left + top.width * 0.4f, top.center.y),
                2.6.dp.toPx(), cap = StrokeCap.Round,
            )
            val art = bottom.height - 8.dp.toPx()
            drawRoundRect(accent, Offset(bottom.left + 4.dp.toPx(), bottom.top + 4.dp.toPx()), Size(art, art), CornerRadius(4.dp.toPx()))
            val lx = bottom.left + art + 11.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.8f), Offset(lx, bottom.center.y - 3.5.dp.toPx()), Offset(lx + bottom.width * 0.32f, bottom.center.y - 3.5.dp.toPx()), 2.4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(lx, bottom.center.y + 3.5.dp.toPx()), Offset(lx + bottom.width * 0.2f, bottom.center.y + 3.5.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.9f), (5f + 1.2f * pulse).dp.toPx(), Offset(bottom.right - 14.dp.toPx(), bottom.center.y))
        }
        drawRoundRect(Color.White.copy(alpha = 0.16f), scr.topLeft, scr.size, CornerRadius(corner), style = Stroke(1.dp.toPx()))
    }
}

/* ---------------- Player style ---------------- */

/** Where things sit on the little player screen, in screen widths (the screen is 1 wide, 2 tall). */
private class LkPlayerLook(
    val x: Float, val y: Float, val w: Float, val h: Float, val corner: Float, val fade: Float, val panel: Float,
    val titleY: Float, val titleW: Float, val titleT: Float, val progY: Float, val ctrlY: Float,
)

private fun lkPlayerLook(s: PlayerStyle): LkPlayerLook = when (s) {
    PlayerStyle.IMMERSIVE -> LkPlayerLook(0f, 0f, 1f, 1.1f, 0f, 0.9f, 0f, 1.3f, 0.55f, 0.06f, 1.55f, 1.75f)
    PlayerStyle.CANVAS -> LkPlayerLook(0f, 0f, 1f, 2f, 0f, 0.85f, 0f, 1.3f, 0.55f, 0.06f, 1.55f, 1.75f)
    PlayerStyle.CARD -> LkPlayerLook(0.1f, 0.2f, 0.8f, 0.8f, 0.07f, 0f, 0f, 1.2f, 0.55f, 0.06f, 1.5f, 1.73f)
    PlayerStyle.PANEL -> LkPlayerLook(0f, 0f, 1f, 1.5f, 0f, 0.35f, 1f, 1.24f, 0.5f, 0.055f, 1.5f, 1.72f)
    PlayerStyle.MINIMAL -> LkPlayerLook(0.27f, 0.3f, 0.46f, 0.46f, 0.05f, 0f, 0f, 1f, 0.72f, 0.1f, 1.45f, 1.7f)
}

/**
 * A tiny phone showing the player. It changes shape with each style: cover across the top
 * (Immersive), over the whole screen (Full screen), a rounded card that shrinks a little when
 * paused (Card), controls on a glass panel (Glass panel), or a small cover and a big title (Minimal).
 */
@Composable
fun PlayerStyleArt(playing: Boolean, style: PlayerStyle, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val look = lkPlayerLook(style)
    val spec = artSpec()
    val cx by animateFloatAsState(look.x, spec, label = "coverX")
    val cy by animateFloatAsState(look.y, spec, label = "coverY")
    val cw by animateFloatAsState(look.w, spec, label = "coverW")
    val ch by animateFloatAsState(look.h, spec, label = "coverH")
    val cc by animateFloatAsState(look.corner, spec, label = "corner")
    val fade by animateFloatAsState(look.fade, spec, label = "fade")
    val panel by animateFloatAsState(look.panel, spec, label = "panel")
    val titleY by animateFloatAsState(look.titleY, spec, label = "titleY")
    val titleW by animateFloatAsState(look.titleW, spec, label = "titleW")
    val titleT by animateFloatAsState(look.titleT, spec, label = "titleT")
    val progY by animateFloatAsState(look.progY, spec, label = "progY")
    val ctrlY by animateFloatAsState(look.ctrlY, spec, label = "ctrlY")
    val shrink by animateFloatAsState(if (style == PlayerStyle.CARD && !playing) 1f else 0f, spec, label = "shrink")
    val clip = remember { Path() }
    val coverClip = remember { Path() }
    val tri = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        backdrop(accent)
        val u = (h - 12.dp.toPx()) / 2
        val sx = (w - u) / 2
        val sy = (h - 2 * u) / 2
        val scr = Rect(sx, sy, sx + u, sy + 2 * u)
        val bg = lerp(accent, Color.Black, 0.85f)
        glow(accent, scr.center, u * 0.45f, 0.45f)
        clipPath(lkRound(clip, scr, u * 0.16f)) {
            drawRect(bg, scr.topLeft, scr.size)
            val k = 1f - 0.12f * shrink
            val cover = Rect(
                sx + (cx + cw * (1f - k) / 2) * u, sy + (cy + ch * (1f - k) / 2) * u,
                sx + (cx + cw * (1f - k) / 2 + cw * k) * u, sy + (cy + ch * (1f - k) / 2 + ch * k) * u,
            )
            lkCover(cover, cc * u, accent, coverClip, drift = t * 0.3f)
            if (fade > 0.01f) {
                val from = cover.top + cover.height * 0.45f
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = fade)), startY = from, endY = cover.bottom),
                    Offset(cover.left, from), Size(cover.width, cover.bottom - from),
                )
            }
            if (panel > 0.01f) {
                val p = Rect(sx + 0.07f * u, sy + 1.08f * u, sx + 0.93f * u, sy + 1.92f * u)
                drawRoundRect(Color.White.copy(alpha = 0.14f * panel), p.topLeft, p.size, CornerRadius(0.1f * u))
                drawRoundRect(Color.White.copy(alpha = 0.28f * panel), p.topLeft, p.size, CornerRadius(0.1f * u), style = Stroke(0.8.dp.toPx()))
            }
            // Title and artist.
            val tl = sx + 0.14f * u
            val ty = sy + titleY * u
            drawLine(Color.White.copy(alpha = 0.92f), Offset(tl, ty), Offset(tl + titleW * u, ty), titleT * u, cap = StrokeCap.Round)
            val ay = ty + titleT * u * 0.5f + 0.08f * u
            drawLine(Color.White.copy(alpha = 0.4f), Offset(tl, ay), Offset(tl + titleW * 0.6f * u, ay), 0.035f * u, cap = StrokeCap.Round)
            // Progress.
            val py = sy + progY * u
            val pr = sx + 0.86f * u
            drawLine(Color.White.copy(alpha = 0.22f), Offset(tl, py), Offset(pr, py), 0.025f * u, cap = StrokeCap.Round)
            drawLine(accent, Offset(tl, py), Offset(mix(tl, pr, lkFrac(t * 0.05f)), py), 0.025f * u, cap = StrokeCap.Round)
            // Controls.
            val ky = sy + ctrlY * u
            val pc = Offset(sx + 0.5f * u, ky)
            val pr2 = 0.11f * u * (1f + 0.08f * pulse)
            drawCircle(Color.White, pr2, pc)
            tri.reset()
            tri.moveTo(pc.x - pr2 * 0.32f, pc.y - pr2 * 0.45f)
            tri.lineTo(pc.x + pr2 * 0.5f, pc.y)
            tri.lineTo(pc.x - pr2 * 0.32f, pc.y + pr2 * 0.45f)
            tri.close()
            drawPath(tri, LkDark)
            for (side in listOf(-1f, 1f)) drawCircle(Color.White.copy(alpha = 0.6f), 0.035f * u, Offset(pc.x + side * 0.26f * u, ky))
        }
        drawRoundRect(Color.White.copy(alpha = 0.28f), scr.topLeft, scr.size, CornerRadius(u * 0.16f), style = Stroke(1.4.dp.toPx()))
    }
}

/* ---------------- Play button ---------------- */

/**
 * The previous, play and next buttons. The play button changes with the style: plain white icon
 * (Classic), a glass bubble (Glass), a white button (Solid) or one in the highlight colour (Tinted).
 * It shows pause while music plays and pulses with the beat.
 */
@Composable
fun PlayButtonArt(playing: Boolean, style: ButtonStyle, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val glass by animateFloatAsState(if (style == ButtonStyle.GLASS) 1f else 0f, artSpec(), label = "glass")
    val solid by animateFloatAsState(if (style == ButtonStyle.SOLID) 1f else 0f, artSpec(), label = "solid")
    val tint by animateFloatAsState(if (style == ButtonStyle.TINTED) 1f else 0f, artSpec(), label = "tint")
    val playF by animateFloatAsState(if (playing) 1f else 0f, artSpec(), label = "playing")
    val tri = remember { Path() }
    val phase = remember { Phase(0.3f) }
    ArtCanvas(modifier, SettingArtHeight) {
        val h = size.height
        val t = m.t
        val pulse = m.pulse
        val c = center
        backdrop(accent, glow = 0.7f + 0.4f * tint)
        val big = h * 0.26f
        val r = big * (1f + 0.03f * sin(t * 1.6f) + 0.07f * pulse)
        val filled = (glass + solid + tint).coerceAtMost(1f)

        // Soft rings spreading out from the button.
        val cycle = phase.at(t, 0.45f)
        for (i in 0 until 2) {
            val a = lkFrac(cycle + i * 0.5f)
            val rr = big * (1.05f + 1.1f * a)
            val alpha = (1f - a) * smoothstep(0f, 0.1f, a) * (0.18f + 0.4f * pulse)
            drawCircle(accent.copy(alpha = alpha.coerceIn(0f, 1f)), rr, c, style = Stroke(1.4.dp.toPx()))
        }
        glow(accent, c, big * 0.6f, 0.3f + 0.4f * tint + 0.4f * pulse)

        if (glass > 0.01f) {
            drawCircle(Color.White.copy(alpha = 0.12f * glass), r, c)
            drawCircle(Color.White.copy(alpha = 0.32f * glass), r, c, style = Stroke(1.2.dp.toPx()))
            val hr = r * 0.78f
            drawArc(
                Color.White.copy(alpha = 0.5f * glass), 200f, 70f, false,
                topLeft = Offset(c.x - hr, c.y - hr), size = Size(hr * 2, hr * 2),
                style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        if (solid > 0.01f) drawCircle(Color.White.copy(alpha = solid), r, c)
        if (tint > 0.01f) drawCircle(accent.copy(alpha = tint), r, c)

        val ink = lerp(Color.White, LkDark, (solid + tint).coerceAtMost(1f))
        val s = r * mix(0.95f, 0.62f, filled)
        // Play (paused) and pause (playing), crossfading.
        if (playF < 0.99f) {
            tri.reset()
            tri.moveTo(c.x - s * 0.32f, c.y - s * 0.45f)
            tri.lineTo(c.x + s * 0.48f, c.y)
            tri.lineTo(c.x - s * 0.32f, c.y + s * 0.45f)
            tri.close()
            drawPath(tri, ink.copy(alpha = 1f - playF))
        }
        if (playF > 0.01f) {
            val bw = s * 0.2f
            for (side in listOf(-1f, 1f)) {
                drawRoundRect(
                    ink.copy(alpha = playF), Offset(c.x + side * s * 0.2f - bw / 2, c.y - s * 0.42f), Size(bw, s * 0.84f),
                    CornerRadius(bw / 2),
                )
            }
        }

        // Previous and next.
        val ss = big * 0.32f
        for (side in listOf(-1f, 1f)) {
            val x = c.x + side * big * 2.7f
            for (j in 0..1) {
                val tip = x + side * j * ss
                val base = tip - side * ss
                tri.reset()
                tri.moveTo(tip, c.y)
                tri.lineTo(base, c.y - ss * 0.6f)
                tri.lineTo(base, c.y + ss * 0.6f)
                tri.close()
                drawPath(tri, Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

/* ---------------- Music videos behind the cover ---------------- */

/**
 * A cover. With music videos on, it gives way to a moving video scene (stage lights sweeping over
 * a singer, a little progress line), with a Wi-Fi sign beside it, plus a phone signal for "Wi-Fi
 * and mobile data". Off, the still cover stays.
 */
@Composable
fun MusicVideoArt(playing: Boolean, motion: CoverMotion, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val v by animateFloatAsState(if (motion != CoverMotion.OFF) 1f else 0f, artSpec(), label = "video")
    val data by animateFloatAsState(if (motion == CoverMotion.AUTO) 1f else 0f, artSpec(), label = "data")
    val clip = remember { Path() }
    val coverClip = remember { Path() }
    val beam = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val t = m.t
        val pulse = m.pulse
        backdrop(accent, glow = 0.6f + 0.5f * v)
        val r = lkSquare(11.dp.toPx())
        val s = r.width
        val corner = 12.dp.toPx()
        if (v > 0.01f) glow(accent, r.center, s * 0.3f, v * (0.45f + 0.4f * pulse))
        clipPath(lkRound(clip, r, corner)) {
            if (v > 0.01f) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(lerp(accent, Color.Black, 0.72f).copy(alpha = v), lerp(accent, Color.Black, 0.92f).copy(alpha = v)),
                        startY = r.top, endY = r.bottom,
                    ),
                    r.topLeft, r.size,
                )
                // Stage lights sweeping from the top.
                for (k in 0 until 3) {
                    val ox = r.left + s * (0.2f + 0.3f * k)
                    val oy = r.top - 2.dp.toPx()
                    val a = PI.toFloat() / 2 + 0.45f * sin(t * (0.6f + 0.15f * k) + k * 1.9f)
                    val len = s * 1.25f
                    beam.reset()
                    beam.moveTo(ox, oy)
                    beam.lineTo(ox + cos(a - 0.13f) * len, oy + sin(a - 0.13f) * len)
                    beam.lineTo(ox + cos(a + 0.13f) * len, oy + sin(a + 0.13f) * len)
                    beam.close()
                    val col = if (k == 1) accent.copy(alpha = ((0.22f + 0.3f * pulse) * v).coerceIn(0f, 1f)) else Color.White.copy(alpha = 0.1f * v)
                    drawPath(beam, col)
                }
                // The floor and a singer, swaying.
                drawOval(
                    lerp(accent, Color.White, 0.3f).copy(alpha = 0.16f * v),
                    Offset(r.left + s * 0.15f, r.bottom - s * 0.2f), Size(s * 0.7f, s * 0.16f),
                )
                val fx = r.center.x + s * 0.05f * sin(t * 0.9f)
                val headR = s * 0.07f
                val headY = r.bottom - s * 0.46f
                val ink = Color.Black.copy(alpha = 0.8f * v)
                drawCircle(ink, headR, Offset(fx, headY))
                drawRoundRect(
                    ink, Offset(fx - s * 0.1f, headY + headR + 1.5.dp.toPx()), Size(s * 0.2f, s * 0.36f),
                    CornerRadius(s * 0.07f),
                )
                // A little progress line, like a video playing.
                val ly = r.bottom - 6.dp.toPx()
                val l0 = r.left + 8.dp.toPx()
                val l1 = r.right - 8.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.25f * v), Offset(l0, ly), Offset(l1, ly), 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(accent.copy(alpha = v), Offset(l0, ly), Offset(mix(l0, l1, lkFrac(t * 0.06f)), ly), 2.dp.toPx(), cap = StrokeCap.Round)
            }
            // The still cover, giving way when videos are on.
            lkCover(r, corner, accent, coverClip, alpha = 1f - v)
        }
        drawRoundRect(Color.White.copy(alpha = 0.14f), r.topLeft, r.size, CornerRadius(corner), style = Stroke(1.dp.toPx()))

        // Wi-Fi, and the phone signal for "Wi-Fi and mobile data".
        if (v > 0.01f) {
            val gx = r.right + 24.dp.toPx()
            val base = r.center.y + 6.dp.toPx()
            val ink = Color.White.copy(alpha = 0.6f * v)
            for (i in 1..3) {
                val rr = (i * 4.5f).dp.toPx()
                drawArc(
                    ink, 225f, 90f, false, topLeft = Offset(gx - rr, base - rr), size = Size(rr * 2, rr * 2),
                    style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(ink, 1.7.dp.toPx(), Offset(gx, base))
            if (data > 0.01f) {
                val bx = gx + 18.dp.toPx()
                val bw = 3.dp.toPx()
                for (i in 0 until 4) {
                    val bh = (4f + 3.5f * i).dp.toPx()
                    drawRoundRect(
                        Color.White.copy(alpha = 0.6f * v * data), Offset(bx + i * (bw + 2.dp.toPx()), base + 1.dp.toPx() - bh),
                        Size(bw, bh), CornerRadius(bw / 2),
                    )
                }
            }
        }
    }
}

/* ---------------- Animated covers ---------------- */

/**
 * A cover whose picture slowly moves (the sun drifts, the hills roll, light glides across) with a
 * small looping arrow beside it. Off, it settles and stays still.
 */
@Composable
fun AnimatedCoverArt(playing: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val anim by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "animated")
    val phase = remember { Phase(0f) }
    val clip = remember { Path() }
    val shine = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        backdrop(accent)
        val r = lkSquare(11.dp.toPx())
        val s = r.width
        val corner = 12.dp.toPx()
        val drift = phase.at(m.t, 1.1f * anim)
        glow(accent, r.center, s * 0.3f, 0.45f + 0.3f * anim * m.pulse)
        lkCover(r, corner, accent, clip, drift = drift)
        // Soft light gliding across, only while it moves.
        if (anim > 0.01f) {
            clipPath(lkRound(shine, r, corner)) {
                for (k in 0..1) {
                    val x = r.left - s * 0.3f + lkFrac(drift * 0.12f + k * 0.5f) * s * 1.6f
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.16f * anim), Color.Transparent),
                            startX = x - s * 0.15f, endX = x + s * 0.15f,
                        ),
                        Offset(x - s * 0.15f, r.top), Size(s * 0.3f, s),
                    )
                }
            }
        }
        drawRoundRect(Color.White.copy(alpha = 0.14f), r.topLeft, r.size, CornerRadius(corner), style = Stroke(1.dp.toPx()))

        // The loop sign, turning while the cover moves.
        val lc = Offset(r.right + 22.dp.toPx(), r.center.y)
        val lr = 8.dp.toPx()
        val ink = Color.White.copy(alpha = mix(0.25f, 0.7f, anim))
        rotate(drift * 40f, lc) {
            drawArc(ink, 20f, 290f, false, topLeft = Offset(lc.x - lr, lc.y - lr), size = Size(lr * 2, lr * 2), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            // The arrow head at the end of the arc (at 310°).
            val a = 310f * PI.toFloat() / 180f
            val tip = Offset(lc.x + cos(a) * lr, lc.y + sin(a) * lr)
            val hs = 3.5.dp.toPx()
            drawLine(ink, tip, Offset(tip.x - hs, tip.y - hs * 0.09f), 1.8.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x - hs * 0.26f, tip.y - hs * 0.97f), 1.8.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/* ---------------- Living cover ---------------- */

/**
 * A still cover brought to life: it gently breathes and tilts with the music, its picture drifts,
 * and a soft light passes over it now and then. Off, it stays flat and still.
 */
@Composable
fun LivingCoverArt(playing: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val life by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "living")
    val phase = remember { Phase(0f) }
    val clip = remember { Path() }
    val shine = remember { Path() }
    ArtCanvas(modifier, SettingArtHeight) {
        val t = m.t
        val pulse = m.pulse
        backdrop(accent, glow = 0.7f + 0.4f * life)
        val r = lkSquare(13.dp.toPx())
        val s = r.width
        val corner = 12.dp.toPx()
        val breathe = 1f + life * (0.025f * sin(t * 0.9f) + 0.05f * pulse)
        val tilt = life * 3f * sin(t * 0.55f)
        val drift = phase.at(t, 0.3f * life)
        glow(accent, Offset(r.center.x + tilt * 1.5.dp.toPx(), r.center.y + 6.dp.toPx()), s * 0.3f * breathe, 0.4f + 0.35f * life * pulse)
        rotate(tilt, r.center) {
            scale(breathe, r.center) {
                lkCover(r, corner, accent, clip, drift = drift)
                if (life > 0.01f) {
                    val cyc = lkFrac(t * 0.16f)
                    val a = sin(PI.toFloat() * cyc) * life
                    clipPath(lkRound(shine, r, corner)) {
                        rotate(-25f, r.center) {
                            val bx = r.left - s * 0.5f + cyc * s * 2f
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.24f * a), Color.Transparent),
                                    startX = bx - s * 0.18f, endX = bx + s * 0.18f,
                                ),
                                Offset(bx - s * 0.18f, r.top - s), Size(s * 0.36f, s * 3f),
                            )
                        }
                    }
                }
                drawRoundRect(Color.White.copy(alpha = 0.14f + 0.1f * life), r.topLeft, r.size, CornerRadius(corner), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/* ---------------- Effects: animations ---------------- */

/**
 * A switch flicking on and off, a card sliding across and a ball bouncing, all smoothly eased with
 * a faint trail. With animations off they stand still.
 */
@Composable
fun AnimationsArt(playing: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val m = rememberArtMotion(playing)
    val accent = Accent
    val alive by animateFloatAsState(if (on) 1f else 0f, artSpec(), label = "alive")
    val phase = remember { Phase(0.25f) }
    ArtCanvas(modifier, SettingArtHeight) {
        val w = size.width
        val h = size.height
        val pulse = m.pulse
        backdrop(accent)
        val p = phase.at(m.t, 0.5f * alive)
        val cy = h / 2

        // A switch: the knob glides from side to side.
        val c1 = Offset(w / 6f, cy)
        val tw = 42.dp.toPx()
        val th = 24.dp.toPx()
        val kr = th / 2 - 3.dp.toPx()
        val e1 = mix(1f, lkEase(p), alive)
        drawRoundRect(lerp(Color.White.copy(alpha = 0.15f), accent, e1), Offset(c1.x - tw / 2, cy - th / 2), Size(tw, th), CornerRadius(th / 2))
        val k0 = c1.x - tw / 2 + th / 2
        val k1 = c1.x + tw / 2 - th / 2
        if (alive > 0.01f) {
            for (j in 3 downTo 1) {
                val ej = lkEase(p - j * 0.025f)
                drawCircle(Color.White.copy(alpha = 0.12f * alive), kr, Offset(mix(k0, k1, ej), cy))
            }
        }
        drawCircle(Color.White, kr, Offset(mix(k0, k1, e1), cy))

        // A card sliding across, like a page opening.
        val c2 = Offset(w / 2f, cy)
        val cw = 30.dp.toPx()
        val chh = 40.dp.toPx()
        val travel = 26.dp.toPx() * alive
        fun card(x: Float, alpha: Float) {
            drawRoundRect(Color.White.copy(alpha = 0.1f * alpha), Offset(x - cw / 2, cy - chh / 2), Size(cw, chh), CornerRadius(7.dp.toPx()))
            drawRoundRect(accent.copy(alpha = alpha), Offset(x - cw / 2, cy - chh / 2), Size(cw, chh), CornerRadius(7.dp.toPx()), style = Stroke(1.6.dp.toPx()))
            drawLine(Color.White.copy(alpha = 0.6f * alpha), Offset(x - cw * 0.28f, cy - chh * 0.15f), Offset(x + cw * 0.28f, cy - chh * 0.15f), 2.4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.3f * alpha), Offset(x - cw * 0.28f, cy + chh * 0.08f), Offset(x + cw * 0.08f, cy + chh * 0.08f), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        if (alive > 0.01f) {
            for (j in 3 downTo 1) card(c2.x + (lkEase(p * 0.8f + 0.3f - j * 0.025f) - 0.5f) * 2f * travel, 0.12f * alive)
        }
        card(c2.x + (lkEase(p * 0.8f + 0.3f) - 0.5f) * 2f * travel, 1f)

        // A ball bouncing, squashing a little as it lands.
        val c3x = w * 5f / 6f
        val floorY = cy + 22.dp.toPx()
        val br = 8.dp.toPx() * (1f + 0.1f * pulse)
        val hop = abs(sin(p * PI.toFloat() * 1.4f))
        val lift = hop * 34.dp.toPx() * alive
        val squash = 1f + 0.18f * smoothstep(0.25f, 0f, hop) * alive
        drawOval(
            Color.Black.copy(alpha = 0.25f * (1f - 0.6f * hop * alive)),
            Offset(c3x - br * 1.2f, floorY - 2.dp.toPx()), Size(br * 2.4f, 4.dp.toPx()),
        )
        if (alive > 0.01f) {
            for (j in 3 downTo 1) {
                val hj = abs(sin((p - j * 0.02f) * PI.toFloat() * 1.4f))
                drawCircle(accent.copy(alpha = 0.12f * alive), br, Offset(c3x, floorY - br - hj * 34.dp.toPx() * alive))
            }
        }
        val by = floorY - br * (2f - squash) - lift
        glow(accent, Offset(c3x, by), br * 0.8f, 0.5f + 0.4f * pulse)
        drawOval(accent, Offset(c3x - br * squash, by - br * (2f - squash)), Size(br * 2f * squash, br * 2f * (2f - squash)))
    }
}
