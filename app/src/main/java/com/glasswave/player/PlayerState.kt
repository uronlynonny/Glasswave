// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
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
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
    var speed by mutableStateOf(1f)

    /** Set when the app was opened from the notification, so the full player opens. */
    var openFull by mutableStateOf(false)

    fun changeSpeed(s: Float) { controller?.setPlaybackSpeed(s) }

    /** Goes up whenever the queue changes, so the queue list knows to refresh. */
    var queueVersion by mutableStateOf(0)
        private set

    /** One upcoming song: its place in the player's list, and the song. */
    data class QueueEntry(val index: Int, val track: Track)

    /** The songs coming up after the current one, in the order they'll actually play (shuffle included). */
    fun upcoming(limit: Int = 5000): List<QueueEntry> {
        queueVersion // read so callers refresh when the queue changes
        val c = controller ?: return emptyList()
        val tl = c.currentTimeline
        if (tl.isEmpty) return emptyList()
        val out = mutableListOf<QueueEntry>()
        var i = c.currentMediaItemIndex
        while (out.size < limit) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            val item = c.getMediaItemAt(i)
            val md = item.mediaMetadata
            out += QueueEntry(i, Track(item.mediaId, md.title?.toString() ?: "", md.artist?.toString() ?: "", 0, md.artworkUri?.toString()))
        }
        return out
    }

    /** The song that's playing now, as a Track (for downloading it). */
    fun currentTrack(): Track? {
        val c = controller ?: return null
        val item = c.currentMediaItem ?: return null
        val md = item.mediaMetadata
        return Track(item.mediaId, md.title?.toString() ?: "", md.artist?.toString() ?: "", durationMs / 1000, md.artworkUri?.toString())
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0)
        c.play()
    }

    fun removeAt(index: Int) { controller?.removeMediaItem(index) }

    /** Moves a song to play right after the current one. */
    fun playNext(index: Int) {
        val c = controller ?: return
        val target = c.currentMediaItemIndex + 1
        if (index == target || index == c.currentMediaItemIndex) return
        c.moveMediaItem(index, if (index < target) target - 1 else target)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync()
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) queueVersion++
        }
        override fun onPlayerError(e: PlaybackException) {
            android.util.Log.e("Glasswave", "Playback failed", e)
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
        // Title, artist and cover come from the very same queue item as the address. (The player's
        // overall metadata can lag a moment behind during a song change; mixing the two used to save
        // one song's address under another song's name, which then played the wrong song in Rewind and Flow.)
        val item = c.currentMediaItem
        val md = item?.mediaMetadata ?: c.mediaMetadata
        title = md.title?.toString() ?: ""
        artist = md.artist?.toString() ?: ""
        art = md.artworkUri?.toString()
        playing = c.isPlaying
        hasItem = c.mediaItemCount > 0
        buffering = c.playbackState == Player.STATE_BUFFERING
        val id = item?.mediaId
        if (id != null && id != currentId && title.isNotBlank()) {
            History.add(Track(id, title, artist, 0, md.artworkUri?.toString()))
        }
        if (id != currentId) Flow.onTrack(id, this)
        currentId = id
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
        // Automix nudges the tempo for a moment while it matches beats; the speed shown stays yours.
        if (!MixTempo.busy) speed = c.playbackParameters.speed
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

    /** Where songs you queue go: after the current song and your other queued songs, but before Autoplay's. */
    private fun queueEnd(c: MediaController): Int {
        val from = c.currentMediaItemIndex + 1
        for (i in from until c.mediaItemCount) if (c.getMediaItemAt(i).mediaId in Autoplay.added) return i
        return c.mediaItemCount
    }

    /** Adds a song to the end of your queue (or plays it if nothing is playing). */
    fun addToQueue(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playList(listOf(t), 0)
        Queued.ids += t.url
        c.addMediaItem(queueEnd(c), t.toMediaItem())
    }

    /** Puts a song right after the one that's playing. */
    fun playNextTrack(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playList(listOf(t), 0)
        Queued.ids += t.url
        c.addMediaItem(c.currentMediaItemIndex + 1, t.toMediaItem())
    }

    /** Jumps to a song already in the queue. */
    fun playUrl(url: String) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) if (c.getMediaItemAt(i).mediaId == url) {
            c.seekTo(i, 0)
            c.play()
            return
        }
    }

    /** Adds songs to the very end of the queue. */
    fun appendTracks(tracks: List<Track>) {
        controller?.addMediaItems(tracks.map { it.toMediaItem() })
    }

    fun playList(tracks: List<Track>, index: Int, shuffle: Boolean = false, fromFlow: Boolean = false, startMs: Long = 0) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        error = null
        Autoplay.added.clear()
        Queued.ids.clear()
        if (!fromFlow) Flow.stop()
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else index
        c.setMediaItems(tracks.map { it.toMediaItem() }, start, startMs)
        c.prepare()
        c.play()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Off -> whole queue -> this song -> off. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun play() { controller?.play() }
    fun pause() { controller?.pause() }

    fun next() { controller?.seekToNextMediaItem() }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    /** The exact position right now (for lyrics), not the copy refreshed a few times a second. */
    fun livePosition(): Long = controller?.currentPosition ?: positionMs

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
    }
}
