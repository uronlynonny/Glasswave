// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import org.bytedeco.javacv.FFmpegFrameGrabber

/** Colours to pick from for the highlight colour (Settings > Look). */
val AccentSwatches = listOf(
    0xFFC6F432, 0xFFB794F6, 0xFF7AA2FF, 0xFF4FD1C5, 0xFF68D391, 0xFFF6C744, 0xFFFF8A65, 0xFFF687B3, 0xFFFF5C7A, 0xFFFFFFFF,
).map { it.toInt() }

/** A bright, readable colour at [hue] (0..360), for the custom colour slider. */
fun accentFromHue(hue: Float): Int = java.awt.Color.HSBtoRGB(hue / 360f, 0.62f, 1f)

/** The hue (0..360) of a colour, for placing the slider. */
fun hueOf(argb: Int): Float =
    java.awt.Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)[0] * 360f

/** Your own wallpaper, kept inside the app so it still works if the original is moved or deleted. */
object WallpaperFile {
    private val pictures = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val moving = setOf("gif", "mp4", "webm", "mov", "mkv", "avi")
    val extensions = pictures + moving

    fun isMoving(path: String) = File(path).extension.lowercase() in moving

    /** Lets you pick a picture, GIF or video. Returns its new path inside the app, or null. */
    fun pick(): String? {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a picture, GIF or video", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        dialog.file = extensions.joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return save(File(dialog.directory, name))
    }

    private fun save(src: File): String? = runCatching {
        val dir = File(android.content.AppDirs.data, "wallpaper").apply { mkdirs() }
        val out = File(dir, "wall-${System.currentTimeMillis()}.${src.extension.lowercase()}")
        src.copyTo(out)
        // Only keep the newest one.
        dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
        out.path
    }.getOrNull()

    fun remove() {
        File(android.content.AppDirs.data, "wallpaper").listFiles()?.forEach { it.delete() }
    }
}

/** A font file you imported, kept inside the app. */
object FontFile {
    private val dir get() = File(android.content.AppDirs.data, "font").apply { mkdirs() }

    /** The imported font, if there is one. */
    fun saved(): File? = dir.listFiles()?.firstOrNull()

    /** Lets you pick a font file. Returns its new path and a name to show, or null. */
    fun pick(): Pair<String, String>? {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a font file", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc") }
        dialog.file = "*.ttf;*.otf;*.ttc"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val src = File(dialog.directory, name)
        // Make sure it's a font the app can draw with.
        if (org.jetbrains.skia.FontMgr.default.makeFromFile(src.path) == null) {
            Menus.say("That isn't a font file Glasswave can use")
            return null
        }
        return runCatching {
            val out = File(dir, "font-${System.currentTimeMillis()}.${src.extension.lowercase()}")
            src.copyTo(out)
            dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
            out.path to src.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim()
        }.getOrNull()
    }
}

/** Your wallpaper behind the app, softened and darkened by the amounts in Settings > Look. */
@Composable
fun Wallpaper(path: String, modifier: Modifier = Modifier) {
    val blur = Settings.wallpaperBlur
    val soft = if (blur > 0.01f) Modifier.blur((blur * 40).dp) else Modifier
    Box(modifier.fillMaxSize().background(Color.Black)) {
        // A new wallpaper starts completely fresh (nothing kept from the last one).
        androidx.compose.runtime.key(path) {
            if (WallpaperFile.isMoving(path)) {
                MovingPicture(path, Modifier.fillMaxSize().then(soft))
            } else {
                AsyncImage(File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(soft))
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = Settings.wallpaperDim)))
    }
}

/** One decoded wallpaper frame: BGRA pixels, [w] by [h]. Two of these are reused over and over. */
private class WallFrame(val w: Int, val h: Int) {
    val pixels = ByteArray(w * h * 4)
}

/**
 * A GIF or video, silent and playing round and round, decoded by FFmpeg at a modest size (it sits
 * behind everything, softened, so full size would only cost power).
 * Memory stays flat: frames go into two buffers that are reused, and each picture made for the
 * screen is freed straight after it is drawn (making a new one per frame and leaving it for clean-up
 * later grew the app by hundreds of MB).
 */
@Composable
private fun MovingPicture(path: String, modifier: Modifier) {
    var frame by remember(path) { mutableStateOf<WallFrame?>(null) }
    DisposableEffect(path) {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread({
            var g: FFmpegFrameGrabber? = null
            try {
                // Read the size first, then decode scaled down to at most 640 wide.
                val probe = FFmpegFrameGrabber(File(path)).apply { start() }
                val w0 = probe.imageWidth.coerceAtLeast(1)
                val h0 = probe.imageHeight.coerceAtLeast(1)
                probe.stop(); probe.release()
                val scale = minOf(1f, 640f / w0)
                val w = (w0 * scale).toInt().coerceAtLeast(2) / 2 * 2
                val h = (h0 * scale).toInt().coerceAtLeast(2) / 2 * 2
                g = FFmpegFrameGrabber(File(path)).apply {
                    imageWidth = w
                    imageHeight = h
                    pixelFormat = org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
                    start()
                }
                val buffers = arrayOf(WallFrame(w, h), WallFrame(w, h))
                var turn = 0
                val fps = g.frameRate.takeIf { it in 1.0..60.0 } ?: 24.0
                var next = System.nanoTime()
                while (!stop.get()) {
                    val f = g.grabImage()
                    if (f == null) {
                        // The end: start again from the top.
                        g.setTimestamp(0)
                        continue
                    }
                    val src = f.image?.firstOrNull() as? java.nio.ByteBuffer ?: continue
                    val out = buffers[turn]
                    turn = 1 - turn
                    val row = w * 4
                    val stride = f.imageStride.takeIf { it >= row } ?: row
                    for (y in 0 until h) {
                        src.position(y * stride)
                        src.get(out.pixels, y * row, row)
                    }
                    frame = out
                    next += (1_000_000_000L / fps).toLong()
                    val wait = (next - System.nanoTime()) / 1_000_000
                    if (wait > 0) Thread.sleep(wait) else next = System.nanoTime()
                }
            } catch (_: InterruptedException) {
                // Closed: nothing to say.
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Couldn't play the wallpaper", e)
            } finally {
                runCatching { g?.stop(); g?.release() }
            }
        }, "wallpaper").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        onDispose {
            stop.set(true)
            worker.interrupt()
        }
    }
    val f = frame ?: return
    androidx.compose.foundation.Canvas(modifier) {
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
