package com.glasswave.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** Connects the screen to the background player and exposes its state to Compose. */
class PlayerState(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var art by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var hasItem by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var currentId by mutableStateOf<String?>(null)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
        override fun onPlayerError(e: PlaybackException) {
            error = "Couldn't play this one. If it keeps happening, YouTube may have changed and the app needs an update."
        }
    }

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                sync()
            } catch (e: Exception) {
                error = "Couldn't start the player."
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun sync() {
        val c = controller ?: return
        val md = c.mediaMetadata
        title = md.title?.toString() ?: ""
        artist = md.artist?.toString() ?: ""
        art = md.artworkUri?.toString()
        playing = c.isPlaying
        hasItem = c.mediaItemCount > 0
        buffering = c.playbackState == Player.STATE_BUFFERING
        currentId = c.currentMediaItem?.mediaId
        val d = c.duration
        durationMs = if (d == C.TIME_UNSET || d < 0) 0L else d
        positionMs = c.currentPosition
        if (c.playbackState == Player.STATE_READY) error = null
    }

    fun tick() {
        val c = controller ?: return
        positionMs = c.currentPosition
        val d = c.duration
        durationMs = if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    fun playList(tracks: List<Track>, index: Int) {
        val c = controller ?: return
        error = null
        c.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
    }
}
