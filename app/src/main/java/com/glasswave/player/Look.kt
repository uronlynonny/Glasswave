// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/** Colours to pick from for the highlight colour (Settings > Look). */
val AccentSwatches = listOf(
    0xFFC6F432, 0xFFB794F6, 0xFF7AA2FF, 0xFF4FD1C5, 0xFF68D391, 0xFFF6C744, 0xFFFF8A65, 0xFFF687B3, 0xFFFF5C7A, 0xFFFFFFFF,
).map { it.toInt() }

/** A bright, readable colour at [hue] (0..360), for the custom colour slider. */
fun accentFromHue(hue: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.62f, 1f))

/** Your own wallpaper, kept inside the app so it still works if the original is deleted. */
object WallpaperFile {
    fun isVideo(path: String) = path.endsWith(".mp4")

    /** Copies a picked picture, GIF or video in, and returns its new path (or null if it couldn't be read). */
    fun save(context: Context, uri: Uri): String? = runCatching {
        val type = context.contentResolver.getType(uri) ?: ""
        val ext = when {
            type.startsWith("video/") -> "mp4"
            type == "image/gif" -> "gif"
            type == "image/webp" -> "webp"
            type == "image/png" -> "png"
            else -> "jpg"
        }
        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val out = File(dir, "wall-${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        // Only keep the newest one.
        dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
        out.path
    }.getOrNull()

    fun remove(context: Context) {
        File(context.filesDir, "wallpaper").listFiles()?.forEach { it.delete() }
    }
}

/** Your wallpaper behind the app, softened and darkened by the amounts in Settings > Look. */
@Composable
fun Wallpaper(path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val blur = Settings.wallpaperBlur
    // Blurring needs Android 12+; older phones get a softer, smaller picture instead.
    val soft = if (Build.VERSION.SDK_INT >= 31 && blur > 0.01f) Modifier.blur((blur * 40).dp) else Modifier
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (WallpaperFile.isVideo(path)) {
            LoopingVideo(path, Modifier.fillMaxSize().then(soft))
        } else {
            val size = if (Build.VERSION.SDK_INT >= 31) 1080 else (1080 - blur * 900).toInt()
            val req = remember(path, size) { ImageRequest.Builder(ctx).data(File(path)).size(size).build() }
            AsyncImage(req, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(soft))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = Settings.wallpaperDim)))
    }
}

/** A silent video that plays round and round, filling its space. Pauses while the app is hidden. */
@OptIn(UnstableApi::class)
@Composable
private fun LoopingVideo(path: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    val lifecycle = @Suppress("DEPRECATION") androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val watch = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) player.pause()
            if (e == Lifecycle.Event.ON_START) player.play()
        }
        lifecycle.addObserver(watch)
        onDispose {
            lifecycle.removeObserver(watch)
            player.release()
        }
    }
    AndroidView(
        factory = { c ->
            TextureView(c).also { tv ->
                player.setVideoTextureView(tv)
                // Fill the screen (cropping the sides) like a centred cover.
                fun fit(v: VideoSize) {
                    if (v.width == 0 || v.height == 0 || tv.width == 0 || tv.height == 0) return
                    val vw = v.width * v.pixelWidthHeightRatio
                    val scale = maxOf(tv.width / vw, tv.height / v.height.toFloat())
                    val m = Matrix()
                    m.setScale(vw * scale / tv.width, v.height * scale / tv.height, tv.width / 2f, tv.height / 2f)
                    tv.setTransform(m)
                }
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) = fit(videoSize)
                })
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(player.videoSize) }
            }
        },
        modifier = modifier,
    )
}
