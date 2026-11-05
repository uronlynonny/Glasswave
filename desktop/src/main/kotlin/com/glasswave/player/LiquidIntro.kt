// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The opening: the liquid glass logo. A glass drop lands, opens into the Glasswave tile with the
 * wave inside, the glass melts away and the wave stays, then everything opens up into the app.
 * It plays from a recording (resources/intro.webp, 60 frames a second, every frame complete on its
 * own; repeated frames are stored once with a longer duration), so it starts at once. Tap to skip.
 */
@Composable
internal fun Intro(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val out = remember { Animatable(0f) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            out.animateTo(1f, tween(if (Settings.animations) 260 else 1))
            done()
        }
    }
    LaunchedEffect(Unit) {
        val codec = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("intro.webp")!!.use { it.readBytes() }
                org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes))
            }.getOrNull()
        }
        if (codec == null) { leave(); return@LaunchedEffect }
        val count = codec.frameCount
        val log = System.getProperty("gw.introLog") != null
        if (log) System.err.println("GW intro: " + count + " frames, animations=" + Settings.animations + " at " + System.nanoTime() / 1_000_000)
        fun decode(i: Int): ImageBitmap? = runCatching {
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocPixels(codec.imageInfo)
            codec.readPixels(bmp, i)
            bmp.setImmutable()
            bmp.asComposeImageBitmap()
        }.getOrNull()
        if (!Settings.animations) {
            // Animations off: just the finished logo, briefly.
            frame = withContext(Dispatchers.Default) { decode(count - 1) }
            delay(500)
            leave()
            return@LaunchedEffect
        }
        // Each frame is decoded off the main thread just before it's due, and shown on time.
        withContext(Dispatchers.Default) {
            // When each frame is due: frames that repeat (the empty start, the held wave) are stored
            // once with a longer duration, so go by the file's own durations.
            val durations = runCatching { codec.framesInfo.map { it.duration.coerceAtLeast(1) } }.getOrNull() ?: List(count) { 17 }
            val due = LongArray(count)
            for (k in 1 until count) due[k] = due[k - 1] + durations[k - 1] * 1_000_000L
            val start = System.nanoTime()
            for (i in 0 until count) {
                if (leaving) break
                val next = decode(i) ?: continue
                val wait = start + due[i] - System.nanoTime()
                if (wait > 0) delay(wait / 1_000_000)
                frame = next
                if (log && i % 15 == 0) System.err.println("GW intro frame " + i + " at " + (System.nanoTime() - start) / 1_000_000 + " ms")
            }
        }
        // Hold the wave for the last frame's time, then open into the app.
        delay(runCatching { codec.framesInfo.last().duration.toLong() }.getOrDefault(0L).coerceIn(150L, 600L))
        leave()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - out.value
                val s = 1f + 0.08f * out.value
                scaleX = s
                scaleY = s
            }
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) { leave() },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { Image(it, contentDescription = null, modifier = Modifier.size(260.dp)) }
    }
}

/** Glasswave's icon (the glass tile from the opening), for the windows and the taskbar. */
internal val AppIconPainter: androidx.compose.ui.graphics.painter.Painter by lazy {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("glasswave-icon.png")!!.use { it.readBytes() }
    androidx.compose.ui.graphics.painter.BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
