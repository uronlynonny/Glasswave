// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Remembers which songs have a music video (and which don't) for this session. */
private object VideoLookup {
    private val found = ConcurrentHashMap<String, YtMusicApi.MusicVideo>()
    private val none = ConcurrentHashMap.newKeySet<String>()

    suspend fun find(trackUrl: String): YtMusicApi.MusicVideo? {
        found[trackUrl]?.let { return it }
        if (trackUrl in none) return null
        val result = try {
            val id = withContext(Dispatchers.IO) { YtRepo.videoIdFor(trackUrl) } ?: return null
            YtMusicApi.musicVideo(id)
        } catch (e: Exception) {
            android.util.Log.w("Glasswave", "Music video lookup failed", e)
            return null // network trouble: try again next time
        }
        if (result != null) found[trackUrl] = result else none += trackUrl
        return result
    }
}

/**
 * How tall the animated cover may be on the current connection, or null for "don't play one".
 * Uses the connection type (Wi-Fi or mobile data) and the phone's estimate of its speed.
 */
private fun videoHeight(ctx: Context): Int? {
    if (Settings.coverMotion == CoverMotion.OFF) return null
    // Data saver: the still cover only.
    if (Settings.savingData) return null
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
    val unmetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    val kbps = caps.linkDownstreamBandwidthKbps
    fun bySpeed(max: Int): Int? = when {
        kbps >= 15_000 -> minOf(720, max)
        kbps >= 5_000 -> minOf(480, max)
        kbps >= 1_500 -> minOf(360, max)
        else -> null // too slow: keep the still cover
    }
    return when {
        unmetered -> bySpeed(720)
        Settings.coverMotion == CoverMotion.AUTO -> bySpeed(480)
        else -> null
    }
}

/**
 * Whether a new animated cover may be downloaded on this connection, and how big (pixels across),
 * or null for "only ones already downloaded".
 */
private fun clipSide(ctx: Context): Int? {
    if (!AnimatedCovers.enabled || Settings.savingData) return null
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
    val unmetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    return when {
        unmetered -> 720
        AnimatedCovers.onMobileData -> 480
        else -> null
    }
}

/**
 * What covers the still cover on the full player: the album's animated cover (Apple Music's
 * "motion artwork") when it has one, otherwise the song's music video (see [SongVideoLayer]).
 * Draws nothing until the first frame is ready, then fades in. Drawn only while the full player is open.
 */
@Composable
fun MusicVideoLayer(ps: PlayerState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val trackUrl = ps.currentId ?: return
    var clip by remember { mutableStateOf<java.io.File?>(null) }
    // Until the animated cover has been looked for, the music video waits (so they don't swap).
    var looked by remember(trackUrl) { mutableStateOf(false) }
    LaunchedEffect(trackUrl, AnimatedCovers.enabled, AnimatedCovers.onMobileData) {
        val t = ps.currentTrack()?.takeIf { it.url == trackUrl && AnimatedCovers.enabled }
        if (t == null) {
            clip = null
        } else {
            // Already downloaded (e.g. the next song on the same album): straight away, on any connection.
            clip = AnimatedCovers.clipFor(t, 720, online = false)
            val side = clipSide(ctx)
            if (clip == null && side != null) clip = AnimatedCovers.clipFor(t, side, online = true)
        }
        looked = true
    }
    val f = clip
    if (f != null) AnimatedCoverClip(f, ps.playing, modifier)
    else if (looked) SongVideoLayer(ps, modifier)
}

/** Plays the animated cover [file] silently, round and round, cropped to fill like the cover. */
@OptIn(UnstableApi::class)
@Composable
private fun AnimatedCoverClip(file: java.io.File, playing: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    var visible by remember(file) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { VideoState.showing = false } }
    LaunchedEffect(visible) { VideoState.showing = visible }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, smooth(250), label = "clipFade")
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
        }
    }
    var texture by remember(file) { mutableStateOf<TextureView?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { visible = true }
            override fun onVideoSizeChanged(size: VideoSize) { texture?.let { cropToFill(it, size) } }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { visible = false }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Moves while the song plays, holds still while it's paused.
    LaunchedEffect(player, playing) { player.playWhenReady = playing }
    key(file) {
        AndroidView(
            factory = { c ->
                TextureView(c).also { tv ->
                    texture = tv
                    player.setVideoTextureView(tv)
                    tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> cropToFill(v as TextureView, player.videoSize) }
                }
            },
            modifier = modifier.graphicsLayer { this.alpha = alpha },
        )
    }
}

/**
 * The song's music video, muted, playing in step with the song. Draws nothing until the first
 * frame is ready, then fades in over the cover.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SongVideoLayer(ps: PlayerState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val trackUrl = ps.currentId ?: return
    var match by remember(trackUrl) { mutableStateOf<YtMusicApi.MusicVideo?>(null) }
    var streamUrl by remember(trackUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(trackUrl, Settings.coverMotion) {
        val height = videoHeight(ctx)
        if (height == null) {
            streamUrl = null
            return@LaunchedEffect
        }
        val m = VideoLookup.find(trackUrl) ?: return@LaunchedEffect
        val url = try {
            withContext(Dispatchers.IO) { YtRepo.videoUrl(m.videoId, height) }
        } catch (e: Exception) {
            android.util.Log.w("Glasswave", "Music video stream failed", e)
            null
        } ?: return@LaunchedEffect
        match = m
        streamUrl = url
    }

    // Tell the living cover when a video is (or isn't) covering it.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { VideoState.showing = false } }
    LaunchedEffect(streamUrl) { if (streamUrl == null) VideoState.showing = false }

    val url = streamUrl ?: return
    val m = match ?: return
    var visible by remember(url) { mutableStateOf(false) }
    LaunchedEffect(visible) { VideoState.showing = visible }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, smooth(250), label = "videoFade")

    val player = remember(url) {
        val http = DefaultHttpDataSource.Factory().setUserAgent(YtDownloader.USER_AGENT).setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(http))
            .build()
            .apply {
                volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                (m.videoTime(ps.positionMs))?.let { seekTo(it) }
                prepare()
            }
    }
    var texture by remember(url) { mutableStateOf<TextureView?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { visible = true }
            override fun onVideoSizeChanged(size: VideoSize) { texture?.let { cropToFill(it, size) } }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) visible = false }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { visible = false }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Follow the song: play/pause with it, and re-align if they drift apart (or you seek).
    LaunchedEffect(player, ps.playing) {
        player.playWhenReady = ps.playing
        while (true) {
            val target = m.videoTime(ps.positionMs)
            if (target == null) {
                // This part of the song isn't in the video (e.g. an extra verse): show the cover.
                visible = false
                player.pause()
            } else {
                if (abs(player.currentPosition - target) > 700) player.seekTo(target)
                player.playWhenReady = ps.playing
                if (player.playbackState == Player.STATE_READY && player.videoSize.width > 0) visible = true
            }
            delay(1000)
        }
    }

    // Rebuilt for each video so it always shows the current player.
    key(url) {
    AndroidView(
        factory = { c ->
            TextureView(c).also { tv ->
                texture = tv
                player.setVideoTextureView(tv)
                tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> cropToFill(v as TextureView, player.videoSize) }
            }
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
    }
}

/** Scales the video to fill the view, cropping the edges, like the cover image does. */
private fun cropToFill(tv: TextureView, size: VideoSize) {
    val vw = tv.width.toFloat()
    val vh = tv.height.toFloat()
    if (size.width <= 0 || size.height <= 0 || vw <= 0f || vh <= 0f) return
    val videoW = size.width * size.pixelWidthHeightRatio
    val videoH = size.height.toFloat()
    val scale = maxOf(vw / videoW, vh / videoH)
    val m = Matrix()
    m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f)
    tv.setTransform(m)
}
