// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
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

/*
 * Liquid glass.
 *
 * A "backdrop" records what is drawn behind the glass. Each glass shape then redraws the part of
 * the backdrop under it, blurred, a little more colourful, and bent at the edges like thick glass
 * (Android 13+). Android 12 gets blur without the bending; older phones get plain frosted glass.
 *
 * All of this runs on the GPU from recorded drawing commands, so it adds no bitmaps to app memory.
 */

class Backdrop(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

private val liquidSupported = Build.VERSION.SDK_INT >= 31

/** Marks this element's drawing as the backdrop that glass shapes can show through. */
fun Modifier.backdropSource(b: Backdrop): Modifier =
    if (!liquidSupported) this
    else this
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
    float2 n = normalize(float2(
        sdf(p + float2(1.0, 0.0), hs, radius) - sdf(p - float2(1.0, 0.0), hs, radius),
        sdf(p + float2(0.0, 1.0), hs, radius) - sdf(p - float2(0.0, 1.0), hs, radius)) + 0.0001);
    float t = 1.0 - inside / depth;
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float2 o = n * bend * amount;
    half4 g = content.eval(coord - o);
    half r = content.eval(coord - o * 1.10).r;
    half b = content.eval(coord - o * 0.90).b;
    half4 c = half4(r, g.g, b, g.a);
    float key = pow(max(dot(n, normalize(float2(-0.45, -1.0))), 0.0), 2.0);
    float fill = 0.45 * pow(max(dot(n, normalize(float2(0.45, 1.0))), 0.0), 2.0);
    c.rgb += half3((key + fill) * pow(t, 3.0) * 0.45);
    return c;
}
"""

@Volatile
private var lensWorks = true

private class GlassCache {
    var size = Size.Zero
    var key = 0f
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
}

@RequiresApi(31)
private fun buildEffect(size: Size, radius: Float, blur: Float, depth: Float, amount: Float): RenderEffect {
    val sat = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })
    var fx = RenderEffect.createColorFilterEffect(sat)
    if (blur > 0f) fx = RenderEffect.createChainEffect(fx, RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
    if (Build.VERSION.SDK_INT >= 33 && depth > 0f && lensWorks) {
        try {
            val lens = RuntimeShader(LENS).apply {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("radius", radius)
                setFloatUniform("depth", depth.coerceAtMost(radius))
                setFloatUniform("amount", amount.coerceAtMost(size.minDimension / 2f))
            }
            fx = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), fx)
        } catch (e: Exception) {
            // Some phone's graphics driver didn't like the shader. Keep the blur, skip the bending.
            android.util.Log.e("Glasswave", "Glass lens shader failed", e)
            lensWorks = false
        }
    }
    return fx
}

/**
 * A liquid glass surface showing [backdrop] through it.
 * [corner] is the corner radius (use a big value for a pill). [tint] colours the glass.
 * The glass must not be inside its own backdrop.
 */
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
    var pos by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .drawBehind {
            val r = corner.toPx().coerceAtMost(size.minDimension / 2f)
            val cr = CornerRadius(r)
            if (liquidSupported && Settings.glass && size.width >= 1f && size.height >= 1f) {
                val key = r + blur.toPx() * 31 + depth.toPx() * 17 + bend.toPx() * 7
                if (cache.size != size || cache.key != key || cache.effect == null) {
                    cache.size = size
                    cache.key = key
                    cache.effect = buildEffect(size, r, blur.toPx(), depth.toPx(), bend.toPx()).asComposeRenderEffect()
                }
                layer.renderEffect = cache.effect
                layer.record {
                    translate(backdrop.origin.x - pos.x, backdrop.origin.y - pos.y) {
                        drawLayer(backdrop.layer)
                    }
                }
                layer.setRoundRectOutline(Offset.Zero, size, r)
                layer.clip = true
                drawLayer(layer)
                drawRoundRect(tint, cornerRadius = cr)
            } else {
                // Older phones: frosted look without sampling what's behind.
                drawRoundRect(Color(0xFF0B0F18).copy(alpha = 0.62f), cornerRadius = cr)
                drawRoundRect(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))),
                    cornerRadius = cr,
                )
            }
            // Thin rim that catches the light, tinted by [rim] (e.g. the cover's colour).
            val sw = 0.8.dp.toPx()
            drawRoundRect(
                Brush.linearGradient(
                    listOf(
                        rim.copy(alpha = 0.50f),
                        rim.copy(alpha = 0.05f),
                        rim.copy(alpha = 0.20f),
                    ),
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
