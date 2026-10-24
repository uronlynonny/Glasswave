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
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Whether a music video is showing over the cover right now (then the living cover rests). */
object VideoState {
    var showing by mutableStateOf(false)
}

/**
 * Brings a still cover to life, like a short looping live photo: a slow drift and zoom, and a soft
 * light passing over it now and then. The picture itself is never bent or rippled.
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
    float2 q = (uv - c) / z + c;
    q = clamp(q, float2(0.001), float2(0.999));
    half4 col = image.eval(q * size);
    float d = uv.x + uv.y * 0.6 - (fract(t * 0.055) * 2.4 - 0.4);
    float band = exp(-d * d * 18.0) * amt;
    col.rgb += half3(band * 0.09) * col.a;
    return col;
}
"""

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
