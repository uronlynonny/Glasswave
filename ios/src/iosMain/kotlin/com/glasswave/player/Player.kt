// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The queue and what's playing. The sound itself comes from the Swift side ([NativeAudio]). */
class Player(private val audio: NativeAudio) : AudioListener {
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var index by mutableStateOf(-1)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    /** A short message for the bottom of the screen ("Couldn't play ..."), cleared after a while. */
    var notice by mutableStateOf<String?>(null)

    val current: Track? get() = queue.getOrNull(index)

    private val scope = MainScope()
    private var radioFor: String? = null
    private var failuresInARow = 0
    private var seekingUntil = 0L
    private var clock = 0L

    init {
        audio.setListener(this)
        scope.launch {
            while (true) {
                clock += 250
                if (clock > seekingUntil) {
                    val p = audio.position()
                    if (!p.isNaN() && p >= 0) positionMs = (p * 1000).toLong()
                }
                val d = audio.duration()
                if (!d.isNaN() && d > 0) durationMs = (d * 1000).toLong()
                delay(250)
            }
        }
    }

    /** Plays [tracks] from [start]. Up next fills itself with similar songs (YouTube Music's radio). */
    fun playList(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        index = start.coerceIn(0, tracks.size - 1)
        radioFor = null
        load()
        fillUpNext()
    }

    fun jumpTo(i: Int) {
        if (i !in queue.indices) return
        index = i
        load()
        fillUpNext()
    }

    fun toggle() {
        if (current == null) return
        if (playing) audio.pause() else audio.play()
    }

    fun next() {
        if (index < queue.size - 1) jumpTo(index + 1)
    }

    fun previous() {
        if (positionMs > 3000 || index <= 0) seekTo(0) else jumpTo(index - 1)
    }

    fun seekTo(ms: Long) {
        positionMs = ms
        seekingUntil = clock + 750
        audio.seekTo(ms / 1000.0)
    }

    private fun load() {
        val t = current ?: return
        loading = true
        positionMs = 0
        durationMs = t.seconds * 1000
        Log.i("load ${t.videoId} \"${t.title}\" by ${t.artist}")
        audio.load(t.videoId, t.title, t.artist, hiRes(t.thumb), t.seconds.toDouble())
    }

    /** Keeps a few songs ahead in Up next. */
    private fun fillUpNext() {
        val seed = current ?: return
        if (queue.size - index > 4 || radioFor == seed.videoId) return
        radioFor = seed.videoId
        scope.launch {
            runCatching { YtMusic.radio(seed.videoId) }
                .onSuccess { more ->
                    val have = queue.map { it.videoId }.toSet()
                    queue = queue + more.filter { it.videoId !in have }.take(25)
                    Log.i("up next +${more.size} for ${seed.videoId}")
                }
                .onFailure { Log.e("up next failed: $it") }
        }
    }

    private fun say(text: String) {
        notice = text
        scope.launch {
            delay(4000)
            if (notice == text) notice = null
        }
    }

    override fun onReady() {
        loading = false
        failuresInARow = 0
        Log.i("ready ${current?.videoId}")
    }

    override fun onEnded() {
        Log.i("ended ${current?.videoId}")
        if (index < queue.size - 1) next() else playing = false
    }

    override fun onFailed(message: String) {
        loading = false
        Log.e("failed ${current?.videoId}: $message")
        say("Couldn't play \"${current?.title ?: "this song"}\"")
        // Skip ahead, but don't run through the whole queue if nothing plays (no internet, say).
        if (++failuresInARow < 3 && index < queue.size - 1) next()
    }

    override fun onRemoteNext() = next()

    override fun onRemotePrevious() = previous()

    override fun onPlayingChanged(playing: Boolean) {
        this.playing = playing
    }
}
