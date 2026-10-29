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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The highlight colour everywhere (shuffle and repeat, the playing song, buttons): taken from the cover. */
val Accent: Color get() = CoverTint.accent
val Dim = Color.White.copy(alpha = 0.6f)
val Faint = Color.White.copy(alpha = 0.4f)

/**
 * Frosted glass (Settings > Look): calm frosted glass, as solid as the Glass panels slider says.
 * Light (the default): a milky see-through frost that lifts the cover's colours. Dark: a deeper
 * see-through dark fill.
 */
val CleanFill: Color get() =
    if (Settings.darkFrost) Color.Black.copy(alpha = (0.40f * Settings.glassOpacity).coerceIn(0.15f, 0.8f))
    else Color(0xFFC4CAD4).copy(alpha = (0.15f * Settings.glassOpacity).coerceIn(0.06f, 0.4f))
/** Frosted glass where nothing behind is blurred (floating bars, older phones): more solid, so content doesn't show through. */
val CleanSolid: Color get() =
    if (Settings.darkFrost) Color.Black.copy(alpha = (0.62f * Settings.glassOpacity).coerceIn(0.3f, 0.9f))
    else Color(0xFF5A606B).copy(alpha = (0.52f * Settings.glassOpacity).coerceIn(0.3f, 0.85f))
/**
 * Bars that float over the page (player bar, tab bar): with the light frost they get a little
 * darker, so their buttons stay readable when a bright cover scrolls under them.
 */
fun floatTint(normal: Color): Color = if (Settings.cleanLook && !Settings.darkFrost) Color.Black.copy(alpha = 0.46f) else normal
/** Frosted glass: the single hairline edge. */
val CleanEdge: Color get() = Color.White.copy(alpha = if (Settings.darkFrost) 0.09f else 0.17f)

/* ---------------- Motion ---------------- */

/**
 * A quick spring that settles without wobbling (kept calm on purpose), or no animation when
 * animations are turned off in Settings. [stiffness] is kept for callers but raised to stay snappy.
 */
fun <T> bouncy(stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
    if (Settings.animations) spring(dampingRatio = 1f, stiffness = maxOf(stiffness, Spring.StiffnessMedium)) else snap()

/** A short timed animation (capped at 250 ms so nothing drags), or none when animations are off. */
fun <T> smooth(ms: Int = 200): FiniteAnimationSpec<T> = if (Settings.animations) tween(minOf(ms, 250)) else snap()

/**
 * A slowly turning angle (0..2π over [periodMs]) for drifting backgrounds. Updated about 20 times a
 * second (every frame in high performance mode): these move so slowly that more is wasted work.
 * Pauses by itself when the app isn't on screen.
 */
@Composable
fun rememberDrift(periodMs: Int): androidx.compose.runtime.State<Float> {
    val v = remember { androidx.compose.runtime.mutableFloatStateOf(0.8f) }
    LaunchedEffect(periodMs, Settings.highPerformance, Settings.animations) {
        if (!Settings.animations) return@LaunchedEffect
        val step = if (Settings.highPerformance) 0L else 50_000_000L
        var last = 0L
        var t = v.floatValue.toDouble()
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = now - last
                if (dt >= step) {
                    t = (t + dt / 1_000_000.0 / periodMs * 2 * Math.PI) % (2 * Math.PI)
                    v.floatValue = t.toFloat()
                    last = now
                }
            }
        }
    }
    return v
}

/** A tablet, or a phone on its side: wide enough for two columns. */
@Composable
fun isWide(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600

/* ---------------- Colours from the album cover ---------------- */

/**
 * The accent of what's playing: a bright, readable colour from the cover (lime when nothing plays),
 * or the colour you picked in Settings > Look.
 */
object CoverTint {
    private var fromCover by mutableStateOf(Color(0xFFC6F432))
    var accent: Color
        get() = Settings.accentColor?.let { Color(it) } ?: fromCover
        internal set(v) { fromCover = v }
}

/** The font you imported in Settings > Look (null for the standard one), loaded once. */
private val fontFamilies = HashMap<String, androidx.compose.ui.text.font.FontFamily?>()

val AppFontFamily: androidx.compose.ui.text.font.FontFamily?
    get() {
        val path = Settings.fontFile ?: return null
        return fontFamilies.getOrPut(path) {
            runCatching { androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.createFromFile(path)) }.getOrNull()
        }
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

/** The whole app in one colour you picked (Settings > Look) instead of the cover's. Lime gives Glasswave's own look. */
fun ownColors(argb: Int): CoverColors {
    val c = Color(argb)
    return CoverColors(lerp(c, Color.Black, 0.18f), lerp(c, Color.Black, 0.72f), c, lerp(c, Color.Black, 0.94f))
}

/**
 * Reads the main colours out of [art] (loaded tiny, so it's quick) and smoothly fades to them
 * whenever the song changes.
 */
@Composable
fun rememberCoverColors(art: String?): CoverColors {
    val ctx = LocalContext.current
    val target = remember { mutableStateOf(DefaultColors) }
    val own = Settings.accentColor
    LaunchedEffect(art, own) {
        if (own != null) {
            target.value = ownColors(own)
            return@LaunchedEffect
        }
        if (art == null) {
            target.value = DefaultColors
            CoverTint.accent = Lime
            return@LaunchedEffect
        }
        val req = ImageRequest.Builder(ctx).data(art).size(96).allowHardware(false).build()
        val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
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
fun GlassBackground(colors: CoverColors, art: String?, modifier: Modifier = Modifier, artAlpha: Float = 0.35f, wallpaper: Boolean = false) {
    val wall = if (wallpaper) Settings.wallpaper else null
    if (wall != null) {
        Wallpaper(wall, modifier)
        return
    }
    val anim by rememberDrift(40_000)
    val ctx = LocalContext.current
    // With your own colour, the cover isn't blurred in behind either.
    val art = if (Settings.accentColor != null) null else art
    val request = remember(art) { art?.let { ImageRequest.Builder(ctx).data(it).size(96).build() } }
    // How strong the colours are (Settings > Look).
    val k = Settings.colorStrength
    Box(modifier.fillMaxSize().background(colors.base)) {
        if (request != null && Build.VERSION.SDK_INT >= 31) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(80.dp).alpha((artAlpha * k).coerceIn(0f, 1f)),
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
                    fun al(x: Float) = (x * k).coerceIn(0f, 1f)
                    blob(colors.a.copy(alpha = al(0.55f)), w * (0.25f + 0.25f * cos(t)), h * (0.18f + 0.08f * sin(t * 2)), w * 1.0f)
                    blob(colors.b.copy(alpha = al(0.60f)), w * (0.80f + 0.18f * sin(t)), h * (0.48f + 0.10f * cos(t)), w * 0.95f)
                    blob(colors.c.copy(alpha = al(0.40f)), w * (0.30f + 0.25f * sin(t + 1.7f)), h * (0.86f + 0.06f * cos(t * 2)), w * 0.85f)
                    blob(colors.a.copy(alpha = al(0.25f)), w * (0.70f + 0.20f * cos(t + 2.4f)), h * (0.10f + 0.05f * sin(t)), w * 0.6f)
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
): Modifier = if (Settings.cleanLook) this.clip(shape).background(CleanFill).border(0.5.dp, CleanEdge, shape) else this
    .clip(shape)
    // How solid the glass is (Settings > Look).
    .background(Color(0xFF0B0F18).copy(alpha = (base * Settings.glassOpacity).coerceIn(0f, 0.95f)))
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
    align: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            fontFamily = AppFontFamily,
            // Big bold titles sit a little tighter, like Apple's.
            letterSpacing = if (size >= 22) (-0.4).sp else 0.sp,
            textAlign = align,
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
