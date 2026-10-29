// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import org.bytedeco.javacv.FFmpegFrameGrabber

/** How many pixels across the animated cover is downloaded and decoded at. */
private const val CLIP_SIDE = 700

/**
 * The album's animated cover (Apple Music's "motion artwork"), when it has one: a short silent
 * clip playing round and round in place of the still cover. Draws nothing until the first frame is
 * ready, then fades in over the cover. Drawn only while the full player is open.
 */
@Composable
fun MusicVideoLayer(ps: PlayerState, modifier: Modifier = Modifier) {
    // Tell the living cover when a clip is (or isn't) covering it.
    DisposableEffect(Unit) { onDispose { VideoState.showing = false } }
    val trackUrl = ps.currentId
    var clip by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(trackUrl, AnimatedCovers.enabled) {
        val t = ps.currentTrack()?.takeIf { it.url == trackUrl && AnimatedCovers.enabled }
        if (t == null) {
            clip = null
            return@LaunchedEffect
        }
        // Already downloaded (e.g. the next song on the same album): straight away, without a blink.
        clip = AnimatedCovers.clipFor(t, CLIP_SIDE, online = false)
        if (clip == null) clip = AnimatedCovers.clipFor(t, CLIP_SIDE, online = true)
    }
    // Paused for a while: let the clip go (the still cover shows) until the song plays again.
    var resting by remember { mutableStateOf(false) }
    LaunchedEffect(ps.playing) {
        if (ps.playing) resting = false else { delay(60_000); resting = true }
    }
    val f = clip?.takeIf { AnimatedCovers.enabled && !resting }
    LaunchedEffect(f) { if (f == null) VideoState.showing = false }
    if (f != null) CoverClip(f, ps.playing, modifier)
}

/** Three reused picture buffers: one being drawn, one waiting, one being filled. */
private class ClipFrame(val w: Int, val h: Int) {
    val pixels = ByteArray(w * h * 4)
}

/**
 * Plays [file] silently, round and round, decoded by FFmpeg on its own thread at the clip's own
 * frame rate. Holds still while the song is paused or the window is minimized (no decoding then).
 * Memory stays flat: frames go into three buffers that are reused, and each picture made for the
 * screen is freed straight after it is drawn.
 */
@Composable
private fun CoverClip(file: File, playing: Boolean, modifier: Modifier) {
    var frame by remember(file) { mutableStateOf<ClipFrame?>(null) }
    var ready by remember(file) { mutableStateOf(false) }
    val moving = remember(file) { AtomicBoolean(playing) }
    SideEffect { moving.set(playing) }
    DisposableEffect(file) {
        val stop = AtomicBoolean(false)
        val worker = Thread({
            var g: FFmpegFrameGrabber? = null
            try {
                fun open(): FFmpegFrameGrabber {
                    val probe = FFmpegFrameGrabber(file).apply { start() }
                    val w0 = probe.imageWidth.coerceAtLeast(1)
                    val h0 = probe.imageHeight.coerceAtLeast(1)
                    probe.stop(); probe.release()
                    // Decode at about the size it's downloaded at (never bigger than CLIP_SIDE + a little).
                    val scale = minOf(1f, (CLIP_SIDE * 1.25f) / minOf(w0, h0))
                    return FFmpegFrameGrabber(file).apply {
                        imageWidth = (w0 * scale).toInt().coerceAtLeast(2) / 2 * 2
                        imageHeight = (h0 * scale).toInt().coerceAtLeast(2) / 2 * 2
                        pixelFormat = org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
                        start()
                    }
                }
                g = open()
                val w = g.imageWidth
                val h = g.imageHeight
                val buffers = Array(3) { ClipFrame(w, h) }
                var turn = 0
                val fps = g.frameRate.takeIf { it in 1.0..60.0 } ?: 30.0
                val step = (1_000_000_000L / fps).toLong()
                var next = System.nanoTime()
                var lastLook = 0L
                var down = false
                var emptyGrabs = 0
                while (!stop.get()) {
                    // Hold still while the song is paused or the window is down.
                    val now = System.nanoTime()
                    if (now - lastLook > 500_000_000L) { down = windowDown(); lastLook = now }
                    if (!moving.get() || down) {
                        Thread.sleep(100)
                        next = System.nanoTime()
                        continue
                    }
                    val f = g!!.grabImage()
                    if (f == null) {
                        // The end: start again from the top (or reopen it if it won't go back).
                        emptyGrabs++
                        if (emptyGrabs == 1) {
                            runCatching { g.setTimestamp(0) }
                        } else {
                            runCatching { g.stop(); g.release() }
                            g = open()
                            emptyGrabs = 0
                        }
                        continue
                    }
                    val src = f.image?.firstOrNull() as? java.nio.ByteBuffer ?: continue
                    if (f.imageWidth != w || f.imageHeight != h) continue
                    emptyGrabs = 0
                    val out = buffers[turn]
                    turn = (turn + 1) % buffers.size
                    val row = w * 4
                    val stride = f.imageStride.takeIf { it >= row } ?: row
                    for (y in 0 until h) {
                        src.position(y * stride)
                        src.get(out.pixels, y * row, row)
                    }
                    frame = out
                    ready = true
                    next += step
                    val wait = (next - System.nanoTime()) / 1_000_000
                    if (wait > 0) Thread.sleep(wait) else if (wait < -200) next = System.nanoTime()
                }
            } catch (_: InterruptedException) {
                // Closed: nothing to say.
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Couldn't play the animated cover", e)
            } finally {
                runCatching { g?.stop(); g?.release() }
            }
        }, "animated-cover").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        onDispose {
            stop.set(true)
            worker.interrupt()
        }
    }
    LaunchedEffect(ready) { VideoState.showing = ready }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, smooth(400), label = "clipFade")
    if (!ready) return
    Canvas(modifier.graphicsLayer { this.alpha = alpha }) {
        val f = frame ?: return@Canvas
        val info = org.jetbrains.skia.ImageInfo(f.w, f.h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)
        // Fill the space like ContentScale.Crop: scale up to cover it, centred.
        val k = maxOf(size.width / f.w, size.height / f.h)
        val dw = f.w * k
        val dh = f.h * k
        val dst = org.jetbrains.skia.Rect.makeXYWH((size.width - dw) / 2f, (size.height - dh) / 2f, dw, dh)
        drawIntoCanvas { c ->
            org.jetbrains.skia.Image.makeRaster(info, f.pixels, f.w * 4).use { img ->
                c.nativeCanvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(f.w.toFloat(), f.h.toFloat()), dst, org.jetbrains.skia.SamplingMode.LINEAR, null, true)
            }
        }
    }
}

/** Every Glasswave window is minimized or hidden (nothing would show the clip). */
private fun windowDown(): Boolean = java.awt.Frame.getFrames().none {
    it.isVisible && (it.extendedState and java.awt.Frame.ICONIFIED) == 0
}
