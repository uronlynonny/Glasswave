// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/*
 * Liquid glass, desktop edition.
 *
 * Same idea and same lens shader as the phone: a "backdrop" records what's drawn behind the glass,
 * and each glass shape redraws the part under it, blurred, a little more colourful, and bent at
 * the edges like thick glass. On a computer Skia runs it all on the graphics card.
 */

class Backdrop(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marks this element's drawing as the backdrop that glass shapes can show through. */
fun Modifier.backdropSource(b: Backdrop): Modifier = this
    .onGloballyPositioned { b.origin = it.positionInRoot() }
    .drawWithContent {
        b.layer.record { this@drawWithContent.drawContent() }
        drawLayer(b.layer)
    }

private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;
uniform float amount;

float sdf(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - hs;
    float inside = -sdf(p, hs, radius);
    if (inside > depth || inside < 0.0) { return content.eval(coord); }
    float2 q = abs(p) - hs + radius;
    float2 s = sign(p);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * s : (q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y));
    float t = 1.0 - inside / depth;
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float2 o = n * bend * amount;
    half4 c = content.eval(coord - o);
    float key = pow(max(dot(n, normalize(float2(-0.45, -1.0))), 0.0), 2.0);
    float fill = 0.45 * pow(max(dot(n, normalize(float2(0.45, 1.0))), 0.0), 2.0);
    c.rgb += half3((key + fill) * pow(t, 3.0) * 0.45);
    return c;
}
"""

private val lensEffect: RuntimeEffect? by lazy {
    runCatching { RuntimeEffect.makeForShader(LENS) }
        .onFailure { android.util.Log.e("Glasswave", "Glass lens shader failed", it) }
        .getOrNull()
}

/** Computers can always afford the full glass. Kept so the shared code reads the same. */
object GlassTier {
    @Volatile
    var light = false
        private set

    fun init(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {}

    fun blur(px: Float) = px * 0.7f
}

private class GlassCache {
    var size = Size.Zero
    var key = 0f
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
    var outlineSize = Size.Zero
    var outlineR = -1f
}

/** Saturation boost, as a colour matrix (same weights Android's setSaturation uses). */
private fun saturation(s: Float): ColorMatrix {
    val r = 0.213f * (1 - s)
    val g = 0.715f * (1 - s)
    val b = 0.072f * (1 - s)
    return ColorMatrix(
        r + s, g, b, 0f, 0f,
        r, g + s, b, 0f, 0f,
        r, g, b + s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun buildEffect(size: Size, radius: Float, blur: Float, depth: Float, amount: Float): ImageFilter {
    val b = GlassTier.blur(blur)
    // Blur first, then more colour, then (at the edges) the lens.
    val blurred = if (b > 0.5f) ImageFilter.makeBlur(b, b, FilterTileMode.CLAMP, null, null) else null
    var fx = ImageFilter.makeColorFilter(ColorFilter.makeMatrix(saturation(1.6f)), blurred, null)
    val lens = lensEffect
    if (depth > 0f && lens != null) {
        val sb = RuntimeShaderBuilder(lens)
        sb.uniform("size", size.width, size.height)
        sb.uniform("radius", radius)
        sb.uniform("depth", (depth * 0.85f).coerceAtMost(radius))
        sb.uniform("amount", amount.coerceAtMost(size.minDimension / 2f))
        fx = ImageFilter.makeRuntimeShader(sb, "content", fx)
    }
    return fx
}

/**
 * A liquid glass surface showing [backdrop] through it.
 * [corner] is the corner radius (use a big value for a pill). [tint] colours the glass.
 * The glass must not be inside its own backdrop.
 */
/** Inside this, glass is drawn lighter: a plain see-through frosted panel, no bending (cheaper to scroll). */
val LocalLightGlass = androidx.compose.runtime.staticCompositionLocalOf { false }

fun Modifier.liquidGlass(
    backdrop: Backdrop,
    corner: Dp,
    tint: Color = Color.White.copy(alpha = 0.06f),
    blur: Dp = 8.dp,
    depth: Dp = 16.dp,
    bend: Dp = 22.dp,
    rim: Color = Color.White,
): Modifier = composed {
    val layer = rememberGraphicsLayer()
    val cache = remember { GlassCache() }
    val light = LocalLightGlass.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .drawBehind {
            val r = corner.toPx().coerceAtMost(size.minDimension / 2f)
            val cr = CornerRadius(r)
            if (light) {
                drawRoundRect(Color(0xFF0B0F18).copy(alpha = 0.26f), cornerRadius = cr)
                drawRoundRect(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.04f))),
                    cornerRadius = cr,
                )
                drawRoundRect(tint, cornerRadius = cr)
            } else if (Settings.glass && size.width >= 1f && size.height >= 1f) {
                val key = r + blur.toPx() * 31 + depth.toPx() * 17 + bend.toPx() * 7
                if (cache.size != size || cache.key != key || cache.effect == null) {
                    cache.size = size
                    cache.key = key
                    cache.effect = buildEffect(size, r, blur.toPx(), depth.toPx(), bend.toPx()).asComposeRenderEffect()
                }
                // Only touch the layer's settings when they change: each change asks for another
                // frame, and setting them on every draw kept the whole window redrawing nonstop.
                if (layer.renderEffect !== cache.effect) layer.renderEffect = cache.effect
                layer.record {
                    translate(backdrop.origin.x - pos.x, backdrop.origin.y - pos.y) {
                        drawLayer(backdrop.layer)
                    }
                }
                if (cache.outlineSize != size || cache.outlineR != r) {
                    cache.outlineSize = size
                    cache.outlineR = r
                    layer.setRoundRectOutline(Offset.Zero, size, r)
                }
                if (!layer.clip) layer.clip = true
                drawLayer(layer)
                drawRoundRect(tint, cornerRadius = cr)
            } else {
                drawRoundRect(Color(0xFF0B0F18).copy(alpha = 0.62f), cornerRadius = cr)
                drawRoundRect(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))),
                    cornerRadius = cr,
                )
            }
            val sw = 0.8.dp.toPx()
            drawRoundRect(
                Brush.linearGradient(
                    listOf(rim.copy(alpha = 0.50f), rim.copy(alpha = 0.05f), rim.copy(alpha = 0.20f)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                topLeft = Offset(sw / 2, sw / 2),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius((r - sw / 2).coerceAtLeast(0f)),
                style = Stroke(sw),
            )
        }
}
