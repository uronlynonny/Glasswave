// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext as LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.toBitmap
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The highlight colour everywhere (shuffle and repeat, the playing song, buttons): taken from the cover. */
val Accent: Color get() = CoverTint.accent
val Dim = Color.White.copy(alpha = 0.6f)
val Faint = Color.White.copy(alpha = 0.4f)

/* ---------------- Motion ---------------- */

/**
 * A quick spring that settles without wobbling (kept calm on purpose), or no animation when
 * animations are turned off in Settings. [stiffness] is kept for callers but raised to stay snappy.
 */
fun <T> bouncy(stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
    if (Settings.animations) spring(dampingRatio = 1f, stiffness = maxOf(stiffness, Spring.StiffnessMedium) * 2.2f) else snap()

/** A short timed animation (capped at 250 ms so nothing drags), or none when animations are off. */
fun <T> smooth(ms: Int = 200): FiniteAnimationSpec<T> = if (Settings.animations) tween((minOf(ms, 250) * Motion.SPEED).toInt(), easing = Motion.ease) else snap()

/**
 * A slowly turning angle (0..2π over [periodMs]) for drifting backgrounds. Updated about 20 times a
 * second (every frame in high performance mode): these move so slowly that more is wasted work.
 * Pauses by itself when the app isn't on screen.
 */
@Composable
fun rememberDrift(periodMs: Int, running: Boolean = true): androidx.compose.runtime.State<Float> {
    val v = remember { androidx.compose.runtime.mutableFloatStateOf(0.8f) }
    LaunchedEffect(periodMs, running, Settings.highPerformance, Settings.animations) {
        if (!Settings.animations || !running) return@LaunchedEffect
        // Waits between updates instead of asking for every frame, so the screen can rest in between.
        val stepMs = if (Settings.highPerformance) 0L else 50L
        var last = 0L
        var t = v.floatValue.toDouble()
        while (true) {
            if (stepMs > 0) kotlinx.coroutines.delay(stepMs)
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = now - last
                t = (t + dt / 1_000_000.0 / periodMs * 2 * Math.PI) % (2 * Math.PI)
                v.floatValue = t.toFloat()
                last = now
            }
        }
    }
    return v
}

/** A tablet, or a phone on its side: wide enough for two columns. */
@Composable
fun isWide(): Boolean = WindowSize.widthDp >= 600f

/* ---------------- Colours from the album cover ---------------- */

/** The accent of what's playing: a bright, readable colour from the cover (lime when nothing plays). */
object CoverTint {
    var accent by mutableStateOf(Color(0xFFC6F432))
        internal set
}

/** Lifts a colour until it reads well on the dark glass. */
fun readable(c: Color): Color {
    var x = c
    repeat(5) { if (x.luminance() < 0.42f) x = lerp(x, Color.White, 0.22f) }
    return x
}

@Immutable
data class CoverColors(val a: Color, val b: Color, val c: Color, val base: Color)

/** The app's own lime-and-black look, used until a song is playing (then the cover's colours take over). */
val Lime = Color(0xFFC6F432)
val DefaultColors = CoverColors(Color(0xFF9FD12A), Color(0xFF24400C), Lime, Color(0xFF0A0B08))

/**
 * Reads the main colours out of [art] (loaded tiny, so it's quick) and smoothly fades to them
 * whenever the song changes.
 */
@Composable
fun rememberCoverColors(art: String?): CoverColors {
    val ctx = LocalContext.current
    val target = remember { mutableStateOf(DefaultColors) }
    LaunchedEffect(art) {
        if (art == null) {
            target.value = DefaultColors
            CoverTint.accent = Lime
            return@LaunchedEffect
        }
        val req = ImageRequest.Builder(ctx).data(art).size(96).build()
        val bmp = (SingletonImageLoader.get(ctx).execute(req) as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val p = withContext(Dispatchers.Default) { Palette.from(bmp).maximumColorCount(16).generate() }
        val fallback = p.getDominantColor(0xFF2B4CFF.toInt())
        fun pick(vararg c: Int?): Color = Color(c.firstOrNull { it != null && it != 0 } ?: fallback)
        val a = pick(p.vibrantSwatch?.rgb, p.lightVibrantSwatch?.rgb, p.dominantSwatch?.rgb)
        val b = pick(p.darkVibrantSwatch?.rgb, p.mutedSwatch?.rgb, p.dominantSwatch?.rgb)
        val c = pick(p.lightMutedSwatch?.rgb, p.lightVibrantSwatch?.rgb, p.mutedSwatch?.rgb)
        val base = lerp(pick(p.darkMutedSwatch?.rgb, p.darkVibrantSwatch?.rgb, p.dominantSwatch?.rgb), Color.Black, 0.72f)
        target.value = CoverColors(a, b, c, base)
        CoverTint.accent = readable(a)
    }
    val spec: androidx.compose.animation.core.AnimationSpec<Color> = if (Settings.animations) tween(900) else snap()
    val a by animateColorAsState(target.value.a, spec, label = "a")
    val b by animateColorAsState(target.value.b, spec, label = "b")
    val c by animateColorAsState(target.value.c, spec, label = "c")
    val base by animateColorAsState(target.value.base, spec, label = "base")
    return CoverColors(a, b, c, base)
}

/* ---------------- Background ---------------- */

private fun DrawScope.blob(color: Color, cx: Float, cy: Float, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset(cx, cy), radius = r),
        radius = r,
        center = Offset(cx, cy),
    )
}

/**
 * The moving gradient behind everything, in the colours of the song's cover (like Apple Music).
 * The blobs move in the draw step only, inside their own layer, so animating them never redraws
 * the rest of the screen. On Android 12+ the cover itself is also blurred in, loaded tiny.
 */
@Composable
fun GlassBackground(colors: CoverColors, art: String?, modifier: Modifier = Modifier, artAlpha: Float = 0.35f, moving: Boolean = true) {
    // Held still when [moving] is off: a drifting background makes every glass surface redraw constantly.
    val anim = if (moving) rememberDrift(40_000).value else 0.8f
    val ctx = LocalContext.current
    val request = remember(art) { art?.let { ImageRequest.Builder(ctx).data(it).size(96).build() } }
    Box(modifier.fillMaxSize().background(colors.base)) {
        if (request != null && Build.VERSION.SDK_INT >= 31) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(80.dp).alpha(artAlpha),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    val t = if (Settings.animations) anim else 0.8f
                    val w = size.width
                    val h = size.height
                    blob(colors.a.copy(alpha = 0.55f), w * (0.25f + 0.25f * cos(t)), h * (0.18f + 0.08f * sin(t * 2)), w * 1.0f)
                    blob(colors.b.copy(alpha = 0.60f), w * (0.80f + 0.18f * sin(t)), h * (0.48f + 0.10f * cos(t)), w * 0.95f)
                    blob(colors.c.copy(alpha = 0.40f), w * (0.30f + 0.25f * sin(t + 1.7f)), h * (0.86f + 0.06f * cos(t * 2)), w * 0.85f)
                    blob(colors.a.copy(alpha = 0.25f), w * (0.70f + 0.20f * cos(t + 2.4f)), h * (0.10f + 0.05f * sin(t)), w * 0.6f)
                    drawRect(Brush.verticalGradient(listOf(Color(0x22000000), Color(0x99000000))))
                },
        )
    }
}

/* ---------------- Glass and text ---------------- */

/** Cheap frosted glass for things inside scrolling lists (no backdrop sampling, so long lists stay smooth). */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    base: Float = 0.25f,
    light: Float = 0.12f,
): Modifier = this
    .clip(shape)
    .background(Color(0xFF0B0F18).copy(alpha = base))
    .background(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = light), Color.White.copy(alpha = light * 0.25f)),
        ),
    )
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.40f),
                Color.White.copy(alpha = 0.04f),
                Color.White.copy(alpha = 0.18f),
            ),
        ),
        shape,
    )

@Composable
fun GText(
    text: String,
    size: Int,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontFamily = if (size >= 20) DisplayFont else TextFont,
            fontWeight = weight,
            // Big bold titles sit a little tighter, like Apple's.
            letterSpacing = if (size >= 22) (-0.4).sp else 0.sp,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A tinted icon from the rounded icon set. */
@Composable
fun Ico(icon: ImageVector, color: Color = Color.White, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = modifier.size(size))
}

/* ---------------- Desktop: motion and type ---------------- */

/**
 * On a computer things should answer a mouse click at once: every timed animation runs at 65% of
 * the phone's length, with a crisp ease-out (fast start, gentle landing) so nothing loses its polish.
 */
object Motion {
    const val SPEED = 0.65f
    val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
}

/** Windows 11's own type (closest to Apple's San Francisco); older Windows falls back to Segoe UI. */
val TextFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Text", FontWeight.Normal),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Text", FontWeight.Medium),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Text", FontWeight.SemiBold),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Text", FontWeight.Bold),
)
val DisplayFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Display", FontWeight.Normal),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Display", FontWeight.Medium),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Display", FontWeight.SemiBold),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Display", FontWeight.Bold),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Variable Display", FontWeight.ExtraBold),
)
