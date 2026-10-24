// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import java.util.concurrent.Executors

/** Songs Autoplay added to the queue (by address), so they can be taken out again when it's turned off. */
object Autoplay {
    val added: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
}

/** Songs you queued yourself ("Play next", "Add to queue"). Automix never reorders these. */
object Queued {
    val ids: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
}

/** The desktop app streams songs without keeping a separate audio cache (yet). */
object AudioCache {
    fun sizeBytes(@Suppress("UNUSED_PARAMETER") context: Any?): Long = 0
    fun clear(@Suppress("UNUSED_PARAMETER") context: Any?) {}
}

/**
 * The desktop player: the queue, and two audio decks (one plays, the other gets the next song
 * ready, so songs follow each other without a gap and can blend for crossfade and Automix).
 * Same functions as the phone app's PlayerState, so the screens work unchanged. Everything here
 * runs on the window's thread; slow lookups happen on [worker].
 */
class PlayerState(@Suppress("UNUSED_PARAMETER") context: android.content.Context? = null) : Player {
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

    /** Playback speed isn't available on desktop yet; songs always play at normal speed. */
    fun changeSpeed(s: Float) {}

    /** Goes up whenever the queue changes, so the queue list knows to refresh. */
    var queueVersion by mutableStateOf(0)
        private set

    data class QueueEntry(val index: Int, val track: Track)

    /* ---- The queue ---- */

    /** Every song added since the last new list. Positions in here never change; [base] and [shuf] order them. */
    private val items = ArrayList<Track>()
    /** The queue in its normal order, and in shuffled order. Both hold positions in [items]. */
    private val base = ArrayList<Int>()
    private val shuf = ArrayList<Int>()
    private val order get() = if (shuffle) shuf else base
    private var cur = -1

    private val worker = Executors.newFixedThreadPool(3) { r -> Thread(r, "player").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private fun nextIndex(from: Int = cur, mode: Int = repeat): Int? {
        val o = order
        val p = o.indexOf(from)
        if (p < 0) return null
        if (p + 1 < o.size) return o[p + 1]
        return if (mode == Player.REPEAT_MODE_ALL && o.isNotEmpty()) o[0] else null
    }

    private fun prevIndex(from: Int = cur): Int? {
        val o = order
        val p = o.indexOf(from)
        if (p > 0) return o[p - 1]
        return if (repeat == Player.REPEAT_MODE_ALL && o.isNotEmpty()) o.last() else null
    }

    private fun changed() {
        queueVersion++
        maybeAutoplay()
        prefetchAhead()
    }

    fun upcoming(limit: Int = 5000): List<QueueEntry> {
        queueVersion // read so callers refresh when the queue changes
        val out = mutableListOf<QueueEntry>()
        var i = cur
        while (out.size < limit) {
            i = nextIndex(i, Player.REPEAT_MODE_OFF) ?: break
            out += QueueEntry(i, items[i])
        }
        return out
    }

    fun currentTrack(): Track? = items.getOrNull(cur)?.let { it.copy(seconds = durationMs / 1000) }

    fun playAt(index: Int) {
        if (index !in items.indices) return
        moveTo(index, play = true, auto = false)
    }

    fun removeAt(index: Int) {
        if (index == cur) {
            val next = nextIndex(cur, Player.REPEAT_MODE_OFF)
            base.remove(index); shuf.remove(index)
            if (next != null) moveTo(next, play = playing, auto = false) else stopAll()
        } else {
            base.remove(index); shuf.remove(index)
            if (spare.tag == index) spare.stop()
        }
        changed()
    }

    /** Moves a song to play right after the current one. */
    fun playNext(index: Int) {
        if (index == cur) return
        for (o in listOf(base, shuf)) {
            if (!o.remove(index)) continue
            o.add(o.indexOf(cur) + 1, index)
        }
        if (spare.tag != null && spare.tag != index) spare.stop()
        changed()
    }

    /** Where songs you queue go: after the current song and your other queued songs, but before Autoplay's. */
    private fun queueEnd(o: List<Int>): Int {
        val from = o.indexOf(cur) + 1
        for (p in from until o.size) if (items[o[p]].url in Autoplay.added) return p
        return o.size
    }

    private fun insert(t: Track, at: (List<Int>) -> Int) {
        items += t
        val i = items.lastIndex
        base.add(at(base), i)
        shuf.add(at(shuf), i)
        spare.stop() // what plays next may have changed
        changed()
    }

    fun addToQueue(t: Track) {
        if (!hasItem) return playList(listOf(t), 0)
        Queued.ids += t.url
        insert(t) { queueEnd(it) }
    }

    fun playNextTrack(t: Track) {
        if (!hasItem) return playList(listOf(t), 0)
        Queued.ids += t.url
        insert(t) { it.indexOf(cur) + 1 }
    }

    /** Jumps to a song already in the queue. */
    fun playUrl(url: String) {
        val i = order.firstOrNull { items[it].url == url } ?: return
        moveTo(i, play = true, auto = false)
    }

    /** Adds songs to the very end of the queue. */
    fun appendTracks(tracks: List<Track>) {
        for (t in tracks) {
            items += t
            base += items.lastIndex
            shuf += items.lastIndex
        }
        changed()
    }

    fun playList(tracks: List<Track>, index: Int, shuffle: Boolean = false, fromFlow: Boolean = false) {
        if (tracks.isEmpty()) return
        error = null
        Autoplay.added.clear()
        Queued.ids.clear()
        autoplayFor = null
        if (!fromFlow) Flow.stop()
        items.clear(); base.clear(); shuf.clear()
        items += tracks
        base += tracks.indices
        val start = if (shuffle) tracks.indices.random() else index.coerceIn(tracks.indices)
        shuf += listOf(start) + tracks.indices.filter { it != start }.shuffled()
        this.shuffle = shuffle
        spare.stop()
        moveTo(start, play = true, auto = false)
        changed()
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        if (shuffle) {
            // Fresh shuffle: what's playing stays put, everything else is mixed.
            val rest = base.filter { it != cur }.shuffled()
            shuf.clear()
            if (cur >= 0) shuf += cur
            shuf += rest
        }
        spare.stop()
        changed()
    }

    /** Off -> whole queue -> this song -> off. */
    fun cycleRepeat() {
        repeat = when (repeat) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        spare.stop()
        changed()
    }

    /* ---- Transport ---- */

    private var deck = AudioDeck("a")
    private var spare = AudioDeck("b")
    /** The song finished and nothing came after it. */
    private var atEnd = false

    fun toggle() {
        if (!hasItem) return
        if (playing) pause() else play()
    }

    fun play() {
        if (!hasItem) return
        // Finished, or nothing loaded (an error): start this song again.
        if (atEnd || (!buffering && deck.tag == null)) {
            atEnd = false
            moveTo(cur, play = true, auto = false)
            return
        }
        deck.play()
        markPlaying(true)
    }

    override fun pause() {
        deck.pause()
        if (mixing) endMix()
        markPlaying(false)
    }

    fun next() {
        val n = nextIndex() ?: return
        moveTo(n, play = true, auto = false)
    }

    fun previous() {
        if (deck.positionMs > 3000) seekTo(0)
        else prevIndex()?.let { moveTo(it, play = true, auto = false) } ?: seekTo(0)
    }

    fun seekTo(ms: Long) {
        if (mixing) endMix()
        atEnd = false
        deck.seekTo(ms)
        positionMs = ms
        if (playing) deck.play()
    }

    fun livePosition(): Long = if (deck.ready) deck.positionMs else positionMs

    fun tick() {
        positionMs = livePosition()
        if (deck.durationMs > 0) durationMs = deck.durationMs
    }

    fun connect() {
        deck.listener = deckListener
        spare.listener = deckListener
        Settings.listeners += { maybeAutoplay(); applyLevel() }
        SleepTimer.player = this
    }

    fun release() {
        logPlay()
        deck.stop()
        spare.stop()
        worker.shutdownNow()
    }

    private var listenedMs = 0L
    private var playingSince = 0L

    private fun markPlaying(on: Boolean) {
        if (on == playing) return
        playing = on
        val now = SystemClock.elapsedRealtime()
        if (on) {
            playingSince = now
            main.removeCallbacks(fader)
            main.post(fader)
        } else if (playingSince > 0) {
            listenedMs += now - playingSince
            playingSince = 0
        }
    }

    private fun stopAll() {
        deck.stop(); spare.stop()
        markPlaying(false)
        hasItem = false
        cur = -1
        currentId = null
        title = ""; artist = ""; art = null
    }

    /** Looks up where a song's audio is. Blocking. */
    private fun resolve(t: Track): AudioSource {
        Downloads.fileFor(t.url)?.let { return AudioSource.LocalFile(it) }
        val uri = android.net.Uri.parse(t.url)
        val page = if (uri.scheme == "gwmatch") {
            YtRepo.match(uri.getQueryParameter("q") ?: throw java.io.IOException("Empty song"), uri.getQueryParameter("d")?.toLongOrNull() ?: 0)
        } else t.url
        return AudioSource.Remote(YtRepo.streamUrl(page))
    }

    /** Each song change gets a number, so a slow lookup for a song you've skipped past is ignored. */
    private var request = 0
    private var retried = false

    /** Makes song [i] the current one, and starts it (or the deck that already has it ready). */
    private fun moveTo(i: Int, play: Boolean, auto: Boolean) {
        if (i !in items.indices) return
        if (!(auto && mixing)) {
            if (mixing) endMix()
        }
        logPlay()
        val t = items[i]
        cur = i
        atEnd = false
        hasItem = true
        error = null
        title = t.title
        artist = t.artist
        art = t.thumb
        positionMs = 0
        durationMs = t.seconds * 1000
        if (t.title.isNotBlank()) History.add(t.copy(seconds = 0))
        val changedSong = currentId != t.url
        currentId = t.url
        if (changedSong) Flow.onTrack(t.url, this)
        Queued.ids.remove(t.url)
        retried = false
        val req = ++request

        if (spare.tag == i && spare.ready && !mixing) {
            // The next song is already loaded on the other deck: swap over, no wait.
            val old = deck
            deck = spare
            spare = old
            old.stop()
            buffering = false
            if (deck.durationMs > 0) durationMs = deck.durationMs
            deck.volume = startVolume()
            if (play) deck.play()
            markPlaying(play)
        } else if (!mixing) {
            if (spare.tag == i) spare.stop()
            deck.stop()
            buffering = true
            markPlaying(play)
            load(deck, t, i, req, play)
        }

        if (auto) SleepTimer.onSongEnded(this)
        if (Settings.automix) pickNextForMix()
        verifyCurrent(t)
        applyLevel()
        changed()
    }

    private fun load(d: AudioDeck, t: Track, i: Int, req: Int, play: Boolean) {
        worker.execute {
            val src = try {
                resolve(t)
            } catch (e: Exception) {
                main.post { if (req == request) fail(e) }
                return@execute
            }
            main.post {
                if (req != request) return@post
                d.volume = startVolume()
                d.load(src, 0, play && playing, i)
            }
        }
    }

    private fun fail(e: Exception) {
        android.util.Log.e("Glasswave", "Playback failed", e)
        buffering = false
        markPlaying(false)
        deck.stop()
        error = if (e is java.net.UnknownHostException) "No internet connection."
        else "Couldn't play this one. If it keeps happening, YouTube may have changed and the app needs an update."
    }

    private val deckListener = object : AudioDeck.Listener {
        override fun onReady(d: AudioDeck) {
            if (d !== deck) return
            buffering = false
            if (d.durationMs > 0) durationMs = d.durationMs
        }

        override fun onEnded(d: AudioDeck) {
            if (d !== deck) return // the old song's tail in a mix
            if (repeat == Player.REPEAT_MODE_ONE) {
                SleepTimer.onSongEnded(this@PlayerState)
                if (!playing) return
                deck.seekTo(0)
                deck.play()
                logPlay()
                return
            }
            val n = nextIndex()
            if (n == null) {
                logPlay()
                SleepTimer.onSongEnded(this@PlayerState)
                atEnd = true
                markPlaying(false)
                positionMs = durationMs
                return
            }
            moveTo(n, play = true, auto = true)
        }

        override fun onError(d: AudioDeck, e: Exception) {
            if (d === spare) { spare.stop(); return }
            val t = items.getOrNull(cur) ?: return
            // YouTube's audio addresses expire: look it up afresh, once.
            if (!retried && e is HttpStatus || (!retried && e.cause is HttpStatus)) {
                retried = true
                YtRepo.forgetStreamUrls()
                load(deck, t, cur, request, true)
                return
            }
            fail(e)
        }
    }

    /* ---- Getting the next song ready, crossfade and Automix ---- */

    private fun mixOn() = Settings.automix && repeat != Player.REPEAT_MODE_ONE && !SleepTimer.endOfSong

    private var mixing = false
    private var mixOld: AudioDeck? = null
    private var mixStartAt = 0L
    private var mixLength = 0L
    private var mixOutGain = 1f
    private var levelGain = 1f

    /** The volume a song starts at: silent when it's going to fade in. */
    private fun startVolume() = if (Settings.crossfade > 0 && !mixing) 0f else levelGain

    private val fader = object : Runnable {
        override fun run() {
            if (mixing) stepMix()
            else {
                val pos = deck.positionMs
                val dur = deck.durationMs
                prepareNext(pos, dur)
                if (!mixStep(pos, dur)) {
                    val fadeMs = Settings.crossfade * 1000L
                    if (fadeMs > 0 && deck.ready) {
                        var v = 1f
                        if (pos < fadeMs) v = pos.toFloat() / fadeMs
                        if (dur > 0 && dur - pos < fadeMs) v = minOf(v, (dur - pos).toFloat() / fadeMs)
                        deck.volume = v.coerceIn(0f, 1f) * levelGain
                    } else if (deck.ready) {
                        deck.volume = levelGain
                    }
                }
            }
            if (playing || mixing) main.postDelayed(this, 40)
        }
    }

    /** About 15 seconds before the end, load the next song on the spare deck (paused), so it starts at once. */
    private fun prepareNext(pos: Long, dur: Long) {
        if (dur <= 0 || dur - pos > 15_000 || repeat == Player.REPEAT_MODE_ONE) return
        val n = nextIndex() ?: return
        if (spare.tag == n || n == cur) return
        val t = items[n]
        spare.stop()
        spare.tag = n
        val req = request
        worker.execute {
            val src = runCatching { resolve(t) }.getOrNull() ?: return@execute
            main.post {
                if (req != request || spare.tag != n) return@post
                spare.volume = 0f
                spare.load(src, 0, false, n)
            }
        }
    }

    /** Starts a mix at the right moment. True if it did. */
    private fun mixStep(pos: Long, dur: Long): Boolean {
        if (!mixOn() || !playing || dur < 45_000) return false
        val n = nextIndex() ?: return false
        val len = minOf(Settings.mixSeconds * 1000L, dur / 5)
        val at = dur - len
        if (pos < at || pos > at + 1_500) return false
        if (spare.tag != n || !spare.ready) return false // not loaded in time: a plain change
        mixOutGain = levelGain
        mixOld = deck
        deck = spare
        spare = mixOld!!
        mixing = true
        mixStartAt = SystemClock.elapsedRealtime()
        mixLength = dur - pos
        deck.volume = 0f
        deck.play()
        moveTo(n, play = true, auto = true)
        return true
    }

    /** One step of a mix: the old song fades out as the new one fades in, on an equal-power curve. */
    private fun stepMix() {
        val k = ((SystemClock.elapsedRealtime() - mixStartAt).toFloat() / mixLength).coerceIn(0f, 1f)
        val a = (k * Math.PI / 2).toFloat()
        mixOld?.volume = kotlin.math.cos(a) * mixOutGain
        deck.volume = kotlin.math.sin(a) * levelGain
        if (k >= 1f || mixOld?.ended == true) endMix()
    }

    private fun endMix() {
        mixing = false
        mixOld?.stop()
        mixOld = null
        deck.volume = levelGain
    }

    /** Automix, like a DJ: the next song is picked from the next few, preferring a different artist. */
    private fun pickNextForMix() {
        if (shuffle || repeat == Player.REPEAT_MODE_ONE) return
        val p = base.indexOf(cur)
        if (p < 0 || p + 2 >= base.size) return
        if (items[base[p + 1]].url in Queued.ids) return
        fun artist(i: Int) = items[i].artist.substringBefore(",").trim().lowercase()
        val now = artist(cur)
        val window = (p + 1 until minOf(base.size, p + 9)).filter { items[base[it]].url !in Queued.ids }
        if (window.size < 2) return
        val pool = window.filter { artist(base[it]) != now }.ifEmpty { window }
        val pick = pool.random()
        if (pick != p + 1) base.add(p + 1, base.removeAt(pick))
    }

    /* ---- Volume leveling ---- */

    private fun applyLevel() {
        val t = items.getOrNull(cur) ?: return
        if (!Settings.volumeLeveling) { levelGain = 1f; if (!mixing && Settings.crossfade == 0) deck.volume = 1f; return }
        val url = t.url
        worker.execute {
            val db = runCatching { YtRepo.videoIdFor(url)?.let { Loudness.fetch(it) } }.getOrNull() ?: return@execute
            main.post {
                if (items.getOrNull(cur)?.url != url || !Settings.volumeLeveling) return@post
                // On a computer the volume can be lifted as well as lowered (up to double).
                levelGain = Math.pow(10.0, Loudness.gainDb(db) / 20.0).toFloat().coerceIn(0.1f, 2f)
                if (!mixing && Settings.crossfade == 0) deck.volume = levelGain
            }
        }
    }

    /* ---- Listening stats ---- */

    private fun logPlay() {
        val now = SystemClock.elapsedRealtime()
        val ms = listenedMs + if (playingSince > 0) now - playingSince else 0
        val t = items.getOrNull(cur)
        if (t != null && t.url.isNotEmpty()) {
            val dur = durationMs
            if (ms >= 30_000 || (ms >= 10_000 && dur in 1..(ms * 2))) Stats.record(t.copy(seconds = 0), ms)
        }
        listenedMs = 0
        playingSince = if (playing) now else 0
    }

    /* ---- Look the next songs up early, so skipping is instant ---- */

    private fun prefetchAhead() {
        var i = cur
        repeat(2) {
            i = nextIndex(i, Player.REPEAT_MODE_OFF) ?: return
            val t = items[i]
            if (Downloads.fileFor(t.url) == null) worker.execute { YtRepo.prefetch(android.net.Uri.parse(t.url)) }
        }
    }

    /* ---- Autoplay: when the queue runs out, keep going with similar songs ---- */

    private var autoplayFor: String? = null

    private fun maybeAutoplay() {
        if (!Settings.autoplay) {
            if (Autoplay.added.isNotEmpty()) {
                val now = order.indexOf(cur)
                for (o in listOf(base, shuf)) o.removeAll { p -> o.indexOf(p) > now && items[p].url in Autoplay.added }
                Autoplay.added.clear()
                autoplayFor = null
                queueVersion++
            }
            return
        }
        if (repeat != Player.REPEAT_MODE_OFF || !hasItem) return
        if (nextIndex() != null) return
        val current = items.getOrNull(cur)?.url ?: return
        if (autoplayFor == current) return
        autoplayFor = current
        worker.execute {
            val tracks = try {
                val id = YtRepo.videoIdFor(current) ?: return@execute
                YtMusicApi.radio(id)
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Autoplay lookup failed", e)
                main.post { if (autoplayFor == current) autoplayFor = null }
                return@execute
            }
            main.post {
                val queued = order.map { items[it].url }.toSet()
                val keys = order.mapTo(HashSet()) { YtRepo.songKey(items[it].title, items[it].artist) }
                val fresh = tracks
                    .filter { it.url !in queued && keys.add(YtRepo.songKey(it.title, it.artist)) }
                    .take(25)
                    .let { if (Settings.automix) it.shuffled() else it }
                if (fresh.isNotEmpty() && Settings.autoplay) {
                    Autoplay.added += fresh.map { it.url }
                    appendTracks(fresh)
                }
            }
        }
    }

    /* ---- Make sure the song playing is the song you picked ---- */

    private val verified = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun verifyCurrent(t: Track) {
        val id = t.url
        if (id.isEmpty() || !verified.add(id) || Downloads.fileFor(id) != null) return
        val title = t.title.takeIf { it.isNotBlank() } ?: return
        worker.execute {
            try {
                val videoId = YtRepo.videoIdFor(id) ?: return@execute
                val real = YtMusicApi.trackInfo(videoId) ?: return@execute
                if (YtRepo.sameSong(title, real.title)) return@execute
                val right = YtRepo.findSong(title, t.artist, real.seconds) ?: return@execute
                val rightId = android.net.Uri.parse(right.url).getQueryParameter("v")
                if (rightId == null || rightId == videoId) return@execute
                val uri = android.net.Uri.parse(id)
                if (uri.scheme == "gwmatch") uri.getQueryParameter("q")?.let { YtRepo.setMatch(it, right.url) }
                main.post {
                    val i = items.indexOfFirst { it.url == id }
                    if (i < 0) return@post
                    items[i] = items[i].copy(url = right.url)
                    verified += right.url
                    History.removeUrl(id)
                    // Swap the right song in if it's the one playing.
                    if (i == cur) moveTo(i, play = playing, auto = false)
                }
            } catch (e: Exception) {
                verified.remove(id)
            }
        }
    }
}
