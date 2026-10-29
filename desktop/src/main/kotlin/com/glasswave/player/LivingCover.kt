// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.os.Build
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.collectAsState
import coil3.toBitmap
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Whether a music video is showing over the cover right now (then the living cover rests). */
object VideoState {
    var showing by mutableStateOf(false)
}

/**
 * Brings a still cover to life, like a short looping clip: the picture slowly ripples and drifts,
 * and a soft light passes over it now and then.
 * Made on the phone from the cover itself while the song plays; when it stops, the picture eases
 * back to still. Runs at about 30 frames a second to stay light.
 */
private const val LIVING = """
uniform shader image;
uniform float2 size;
uniform float t;
uniform float amt;
half4 main(float2 p) {
    float2 uv = p / size;
    float z = 1.0 + amt * 0.06 * (0.5 - 0.5 * cos(t * 0.33));
    float2 c = float2(0.5 + amt * 0.035 * sin(t * 0.21), 0.5 + amt * 0.03 * sin(t * 0.17));
    float2 q = (uv - c) / (z + amt * 0.03) + c;
    // A slow ripple flowing through the picture, like a short looping clip.
    q += amt * float2(
        0.0060 * sin(uv.y * 6.2 + t * 0.62) + 0.0040 * sin((uv.x * 3.1 + uv.y * 2.3) - t * 0.41),
        0.0060 * cos(uv.x * 5.4 - t * 0.53) + 0.0040 * sin((uv.y * 3.6 - uv.x * 1.7) + t * 0.37));
    q = clamp(q, float2(0.001), float2(0.999));
    half4 col = image.eval(q * size);
    float d = uv.x + uv.y * 0.6 - (fract(t * 0.055) * 2.4 - 0.4);
    float band = exp(-d * d * 18.0) * amt;
    col.rgb += half3(band * 0.09) * col.a;
    return col;
}
"""

/** The ripple at one point of the cover (in parts of its size): the same flow as the phone's shader. */
private fun flowX(u: Float, v: Float, t: Float) = 0.0060f * sin(v * 6.2f + t * 0.62f) + 0.0040f * sin((u * 3.1f + v * 2.3f) - t * 0.41f)
private fun flowY(u: Float, v: Float, t: Float) = 0.0060f * cos(u * 5.4f - t * 0.53f) + 0.0040f * sin((v * 3.6f - u * 1.7f) + t * 0.37f)

/**
 * The PC's living cover: the cover drawn as a fine grid whose points gently flow, so the picture
 * ripples like a looping clip, plus a slow drift and zoom and a soft passing light. Sits on top of
 * the still cover and only draws while it's alive (the still cover shows otherwise).
 */
@androidx.compose.runtime.Composable
fun LivingArt(url: String, active: Boolean, modifier: Modifier) {
    val painter = coil3.compose.rememberAsyncImagePainter(url)
    val state by painter.state.collectAsState()
    val image = (state as? coil3.compose.AsyncImagePainter.State.Success)?.result?.image
    val skia = remember(image) { image?.let { runCatching { org.jetbrains.skia.Image.makeFromBitmap(it.toBitmap()) }.getOrNull() } }
    var t by remember { mutableFloatStateOf(0f) }
    val amt by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 1200 else 600), label = "aliveArt")
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val step = if (Settings.highPerformance) 0L else 30_000_000L
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) last = now
                if (now - last >= step) {
                    t += (now - last) / 1_000_000_000f
                    last = now
                }
            }
        }
    }
    val grid = remember { MeshGrid(24) }
    androidx.compose.foundation.Canvas(modifier.graphicsLayer { clip = true }) {
        val img = skia ?: return@Canvas
        if (amt <= 0.001f) return@Canvas
        val w = size.width
        val h = size.height
        // Crop the picture to fill the square, like the still cover does.
        val iw = img.width.toFloat()
        val ih = img.height.toFloat()
        val side = minOf(iw, ih)
        val sx = (iw - side) / 2f
        val sy = (ih - side) / 2f
        // Slow drift and zoom, a little bigger than the frame so the edges never show.
        val z = 1.03f + amt * 0.06f * (0.5f - 0.5f * cos(t * 0.33f))
        val cx = 0.5f + amt * 0.035f * sin(t * 0.21f)
        val cy = 0.5f + amt * 0.03f * sin(t * 0.17f)
        val n = grid.n
        var k = 0
        for (j in 0..n) for (i in 0..n) {
            val u = i / n.toFloat()
            val v = j / n.toFloat()
            grid.pos[k] = u * w
            grid.pos[k + 1] = v * h
            // Which part of the picture shows at this point (zoomed, drifted, rippled).
            val qu = ((u - cx) / z + cx + amt * flowX(u, v, t)).coerceIn(0f, 1f)
            val qv = ((v - cy) / z + cy + amt * flowY(u, v, t)).coerceIn(0f, 1f)
            grid.tex[k] = sx + qu * side
            grid.tex[k + 1] = sy + qv * side
            k += 2
        }
        val paint = grid.paint(img)
        paint.alpha = (amt * 255).toInt().coerceIn(0, 255)
        drawIntoCanvas {
            it.nativeCanvas.drawVertices(org.jetbrains.skia.VertexMode.TRIANGLES, grid.pos, null, grid.tex, grid.idx, org.jetbrains.skia.BlendMode.MODULATE, paint)
        }
        // The soft light passing over.
        val pos = ((t * 0.055f) % 1f) * 2.4f - 0.4f
        val x = w * pos
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.08f * amt), Color.Transparent),
                start = Offset(x - w * 0.25f, 0f),
                end = Offset(x + w * 0.25f, h * 0.6f),
            ),
        )
    }
}

/** The grid of points the PC's living cover is drawn with (made once, reused every frame). */
private class MeshGrid(val n: Int) {
    val pos = FloatArray((n + 1) * (n + 1) * 2)
    val tex = FloatArray((n + 1) * (n + 1) * 2)
    val idx = ShortArray(n * n * 6).also { a ->
        var k = 0
        for (j in 0 until n) for (i in 0 until n) {
            val p = j * (n + 1) + i
            a[k++] = p.toShort(); a[k++] = (p + 1).toShort(); a[k++] = (p + n + 1).toShort()
            a[k++] = (p + 1).toShort(); a[k++] = (p + n + 2).toShort(); a[k++] = (p + n + 1).toShort()
        }
    }
    private var forImage: org.jetbrains.skia.Image? = null
    private val p = org.jetbrains.skia.Paint().apply { isAntiAlias = true }
    fun paint(img: org.jetbrains.skia.Image): org.jetbrains.skia.Paint {
        if (forImage !== img) {
            p.shader = img.makeShader(org.jetbrains.skia.FilterTileMode.CLAMP, org.jetbrains.skia.FilterTileMode.CLAMP, org.jetbrains.skia.SamplingMode.LINEAR, null)
            forImage = img
        }
        return p
    }
}

fun Modifier.livingCover(active: Boolean): Modifier = composed {
    var t by remember { mutableFloatStateOf(0f) }
    val amt by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 1200 else 600), label = "alive")
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // About 30 frames a second (every frame in high performance mode); stops when off screen.
        val step = if (Settings.highPerformance) 0L else 30_000_000L
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) last = now
                if (now - last >= step) {
                    t += (now - last) / 1_000_000_000f
                    last = now
                }
            }
        }
    }
    // Desktop uses the drawn version below (the shader route draws nothing on some graphics cards).
    if (false) {
        // Same shader as the phone, run by Skia on the computer's graphics card.
        val shader = remember { runCatching { RuntimeShaderBuilder(RuntimeEffect.makeForShader(LIVING)) }.getOrNull() }
        if (shader != null) {
            return@composed this.graphicsLayer {
                if (amt > 0.001f) {
                    shader.uniform("size", size.width, size.height)
                    shader.uniform("t", t)
                    shader.uniform("amt", amt)
                    renderEffect = ImageFilter.makeRuntimeShader(shader, "image", null).asComposeRenderEffect()
                } else {
                    renderEffect = null
                }
            }
        }
    }
    // Older phones: the same drift, zoom and light.
    this
        .graphicsLayer {
            val z = 1f + amt * 0.06f * (0.5f - 0.5f * cos(t * 0.33f))
            scaleX = z
            scaleY = z
            translationX = size.width * 0.03f * amt * sin(t * 0.21f)
            translationY = size.height * 0.025f * amt * sin(t * 0.17f)
        }
        .drawWithContent {
            drawContent()
            if (amt > 0.001f) {
                val pos = ((t * 0.055f) % 1f) * 2.4f - 0.4f
                val x = size.width * pos
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.09f * amt), Color.Transparent),
                        start = Offset(x - size.width * 0.25f, 0f),
                        end = Offset(x + size.width * 0.25f, size.height * 0.6f),
                    ),
                )
            }
        }
}
