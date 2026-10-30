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

    /**
     * Picks what Autoplay adds from YouTube's similar songs. YouTube starts every such list with the
     * same few popular songs, so taken in its order one song came back after almost everything you
     * played. This leaves out songs you've heard lately (unless that leaves too few), mixes up the
     * order, and keeps one artist from playing twice in a row. [taken]: songs already in the queue.
     */
    fun pick(radio: List<Track>, taken: MutableSet<String>, playingArtist: String): List<Track> {
        val heard = History.tracks.take(50).mapTo(HashSet()) { YtRepo.songKey(it.title, it.artist) }
        val all = radio.filter { taken.add(YtRepo.songKey(it.title, it.artist)) }
        val unheard = all.filter { YtRepo.songKey(it.title, it.artist) !in heard }
        val pool = (if (unheard.size >= 6) unheard else all).take(25).shuffled().toMutableList()
        val out = mutableListOf<Track>()
        var last = playingArtist
        while (pool.isNotEmpty()) {
            val i = pool.indexOfFirst { !sameArtist(it.artist, last) }.takeIf { it >= 0 } ?: 0
            val t = pool.removeAt(i)
            out += t
            last = t.artist
        }
        return out
    }

    private fun mainArtist(a: String) =
        a.lowercase().split(",", "&", " x ", " feat", " ft.", " and ").first().trim()

    private fun sameArtist(a: String, b: String) = mainArtist(a).isNotEmpty() && mainArtist(a) == mainArtist(b)
}

/** Songs you queued yourself ("Play next", "Add to queue"). Automix never reorders these. */
object Queued {
    val ids: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
}

/** The songs kept on disk as they play (see [SongCache]), for Settings' storage page. */
object AudioCache {
    fun sizeBytes(@Suppress("UNUSED_PARAMETER") context: Any?): Long = SongCache.sizeBytes()
    fun clear(@Suppress("UNUSED_PARAMETER") context: Any?) = SongCache.clear()
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

    /** Playing speed, 0.5x to 2x. Pitch comes from Settings > Sound & effects. */
    fun changeSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2f)
        AudioDeck.speed = speed
    }

    init {
        AudioDeck.pitch = Sound.pitchFactor()
        Sound.listeners += { AudioDeck.pitch = Sound.pitchFactor() }
    }

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
        saveSoon()
        queueVersion++
        maybeAutoplay()
        prefetchAhead()
        listenAhead()
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

    /** Moves a song within Up next: [from] and [to] are places in the upcoming list. */
    fun moveUpcoming(from: Int, to: Int) {
        val o = order
        val p = o.indexOf(cur)
        if (p < 0) return
        val up = o.subList(p + 1, o.size)
        if (from !in up.indices || to !in up.indices || from == to) return
        up.add(to, up.removeAt(from))
        // What plays next may have changed.
        if (from == 0 || to == 0) spare.stop()
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

    /** [from]: the playlist started as a whole (its Play or Shuffle), so its card shows it playing. */
    fun playList(tracks: List<Track>, index: Int, shuffle: Boolean = false, fromFlow: Boolean = false, startMs: Long = 0, from: String? = null) {
        if (tracks.isEmpty()) return
        error = null
        Autoplay.added.clear()
        Queued.ids.clear()
        autoplayFor = null
        if (!fromFlow) Flow.stop()
        PlayingFrom.set(from)
        items.clear(); base.clear(); shuf.clear()
        items += tracks
        base += tracks.indices
        val start = if (shuffle) tracks.indices.random() else index.coerceIn(tracks.indices)
        shuf += listOf(start) + tracks.indices.filter { it != start }.shuffled()
        this.shuffle = shuffle
        spare.stop()
        moveTo(start, play = true, auto = false, startMs = startMs)
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

    private var deck = AudioDeck("a").also { AudioDeck.current = it }
        set(v) { field = v; AudioDeck.current = v }
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
        deck.automation = null
        atEnd = false
        deck.seekTo(ms)
        positionMs = ms
        if (playing) deck.play()
    }

    fun livePosition(): Long = if (deck.ready) deck.positionMs else positionMs
    internal fun debugPosition(): Long = deck.positionMs
    internal fun debugFrames(): Pair<Long, Long> = deck.debugFrames

    fun tick() {
        // While playing, the spot in the song is saved every 5 seconds.
        if (SystemClock.elapsedRealtime() - lastSave > 5_000) saveSession()
        positionMs = livePosition()
        if (deck.durationMs > 0) durationMs = deck.durationMs
    }

    fun connect() {
        deck.listener = deckListener
        spare.listener = deckListener
        Settings.listeners += { maybeAutoplay(); applyLevel() }
        MixLibrary.listeners += { url -> main.post { onMixKnown(url) } }
        SleepTimer.player = this
        // What was playing when Glasswave last closed (or crashed) comes back, paused.
        main.post { restoreSession() }
    }

    /* ---- Pick up where you left off ---- */

    private val sessionFile get() = java.io.File(android.content.AppDirs.data, "last_played.json")
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "save-queue").apply { isDaemon = true } }
    private var lastSave = 0L
    private val saveLater = Runnable { saveSession() }

    private fun saveSoon() {
        main.removeCallbacks(saveLater)
        main.postDelayed(saveLater, 500)
    }

    /** Writes the queue (both orders), the song and the spot in it. Main thread; the writing happens off it. */
    fun saveSession(now: Boolean = false) {
        lastSave = SystemClock.elapsedRealtime()
        if (!Settings.rememberPlaying) { saver.execute { runCatching { sessionFile.delete() } }; return }
        if (cur !in items.indices) return
        val o = org.json.JSONObject()
            .put("items", org.json.JSONArray(items.map { t ->
                org.json.JSONObject().put("u", t.url).put("t", t.title).put("a", t.artist).put("s", t.seconds).put("i", t.thumb ?: "")
            }))
            .put("base", org.json.JSONArray(base.toList()))
            .put("shuf", org.json.JSONArray(shuf.toList()))
            .put("cur", cur)
            .put("pos", if (atEnd) 0L else livePosition())
            .put("shuffle", shuffle)
            .put("repeat", repeat)
            .put("at", System.currentTimeMillis())
            .put("from", PlayingFrom.id ?: "")
        val text = o.toString()
        val write = Runnable {
            runCatching {
                val f = sessionFile
                val tmp = java.io.File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }
        // Closing: write it now, before the app exits. Otherwise off the main thread.
        if (now) write.run() else saver.execute(write)
    }

    /** Puts the saved queue back, paused at the saved spot. Does nothing if something is already playing. */
    private fun restoreSession() {
        if (!Settings.rememberPlaying || hasItem) return
        val o = runCatching { org.json.JSONObject(sessionFile.readText()) }.getOrNull() ?: return
        // Not used for over an hour: start fresh instead.
        if (System.currentTimeMillis() - o.optLong("at", 0L) > FORGET_AFTER_MS) { runCatching { sessionFile.delete() }; return }
        val arr = o.optJSONArray("items") ?: return
        val tracks = (0 until arr.length()).mapNotNull { n ->
            val t = arr.optJSONObject(n) ?: return@mapNotNull null
            Track(t.optString("u"), t.optString("t"), t.optString("a"), t.optLong("s"), t.optString("i").ifEmpty { null })
        }.filter { it.url.isNotEmpty() }
        if (tracks.isEmpty() || tracks.size != arr.length()) return
        fun order(key: String): List<Int> {
            val a = o.optJSONArray(key) ?: return tracks.indices.toList()
            val list = (0 until a.length()).map { a.optInt(it, -1) }.filter { it in tracks.indices }
            return list.ifEmpty { tracks.indices.toList() }
        }
        items.clear(); base.clear(); shuf.clear()
        items += tracks
        base += order("base")
        shuf += order("shuf")
        shuffle = o.optBoolean("shuffle", false)
        repeat = o.optInt("repeat", Player.REPEAT_MODE_OFF)
        PlayingFrom.set(o.optString("from").ifEmpty { null })
        moveTo(o.optInt("cur", 0).coerceIn(tracks.indices), play = false, auto = false, startMs = o.optLong("pos", 0L))
        changed()
        main.postDelayed(forgetIdle, FORGET_AFTER_MS)
    }

    /** Left alone longer than this (nothing playing), the song and queue are cleared and not brought back. */
    private val FORGET_AFTER_MS = (System.getProperty("gw.forgetAfterMs")?.toLongOrNull() ?: (60 * 60 * 1000L))

    private val forgetIdle = Runnable {
        if (playing || !hasItem) return@Runnable
        stopAll()
        items.clear(); base.clear(); shuf.clear()
        Flow.stop()
        PlayingFrom.set(null)
        queueVersion++
        main.removeCallbacks(saveLater)
        saver.execute { runCatching { sessionFile.delete() } }
    }

    fun release() {
        main.removeCallbacks(saveLater)
        saveSession(now = true)
        logPlay()
        SongCache.flush()
        deck.stop()
        spare.stop()
        worker.shutdownNow()
    }

    private var listenedMs = 0L
    private var playingSince = 0L

    private fun markPlaying(on: Boolean) {
        if (on == playing) return
        playing = on
        if (!on) saveSoon()
        main.removeCallbacks(forgetIdle)
        if (!on) main.postDelayed(forgetIdle, FORGET_AFTER_MS)
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
        PcMusic.fileOf(t.url)?.let { return AudioSource.LocalFile(it) } // a music file on this PC
        SongCache.fileFor(t.url)?.let { return AudioSource.LocalFile(it) } // played before, kept on this PC
        val uri = android.net.Uri.parse(t.url)
        val page = if (uri.scheme == "gwmatch") {
            YtRepo.match(uri.getQueryParameter("q") ?: throw java.io.IOException("Empty song"), uri.getQueryParameter("d")?.toLongOrNull() ?: 0)
        } else t.url
        return AudioSource.Remote(YtRepo.streamUrl(page), t)
    }

    /** Each song change gets a number, so a slow lookup for a song you've skipped past is ignored. */
    private var request = 0

    init { Settings.onQuality += { main.post { requalify() } } }

    /**
     * The quality setting changed: the song that's on switches to it right away, from the same
     * spot. It keeps playing while the new stream is found, so there's only a blink.
     */
    private fun requalify() {
        val t = items.getOrNull(cur) ?: return
        if (mixing || Downloads.fileFor(t.url) != null || PcMusic.fileOf(t.url) != null) return
        val req = request
        worker.execute {
            val src = try {
                val uri = android.net.Uri.parse(t.url)
                val page = if (uri.scheme == "gwmatch") {
                    YtRepo.match(uri.getQueryParameter("q") ?: return@execute, uri.getQueryParameter("d")?.toLongOrNull() ?: 0)
                } else t.url
                AudioSource.Remote(YtRepo.streamUrl(page), t)
            } catch (e: Exception) {
                return@execute
            }
            main.post {
                if (req != request || items.getOrNull(cur)?.url != t.url || mixing) return@post
                spare.stop()
                val at = if (atEnd) 0L else livePosition()
                deck.volume = startVolume()
                deck.eqReady = mixOn()
                deck.stretchReady = false
                deck.automation = null
                buffering = true
                deck.load(src, at, playing, cur)
            }
        }
    }
    /** How many times the current song has been picked back up after an error (see [recover]). */
    private var recoveries = 0

    /** Makes song [i] the current one, and starts it (or the deck that already has it ready). */
    private fun moveTo(i: Int, play: Boolean, auto: Boolean, startMs: Long = 0) {
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
        positionMs = startMs
        durationMs = t.seconds * 1000
        // Into Recently played once its audio has loaded (a song stuck loading never goes in).
        historyPending = t
        val changedSong = currentId != t.url
        currentId = t.url
        if (changedSong) Flow.onTrack(t.url, this)
        Queued.ids.remove(t.url)
        recoveries = 0
        val req = ++request
        if (changedSong) { plan = null; planKey = ""; planLocked = false }
        if (!auto) glideMs = 0

        if (spare.tag == i && spare.ready && !mixing && startMs == 0L) {
            // The next song is already loaded on the other deck: swap over, no wait.
            val old = deck
            deck = spare
            spare = old
            old.stop()
            buffering = false
            if (deck.durationMs > 0) durationMs = deck.durationMs
            deck.automation = null
            deck.volume = startVolume()
            if (play) deck.play()
            markPlaying(play)
            addPendingHistory()
        } else if (!mixing) {
            if (spare.tag == i) spare.stop()
            deck.stop()
            buffering = true
            markPlaying(play)
            load(deck, t, i, req, play, startMs)
        } else {
            addPendingHistory()
        }

        if (auto) SleepTimer.onSongEnded(this)
        if (mixOn()) pickNextForMix()
        listenAhead()
        verifyCurrent(t)
        applyLevel()
        changed()
    }

    /** The song waiting to go into Recently played (see [moveTo]). */
    private var historyPending: Track? = null

    private fun addPendingHistory() {
        val t = historyPending ?: return
        historyPending = null
        if (t.title.isNotBlank() && t.url == currentId) History.add(t.copy(seconds = 0))
    }

    private fun load(d: AudioDeck, t: Track, i: Int, req: Int, play: Boolean, startMs: Long = 0) {
        worker.execute {
            val src = try {
                resolve(t)
            } catch (e: Exception) {
                main.post { if (req == request) recover(e) }
                return@execute
            }
            main.post {
                if (req != request) return@post
                d.volume = startVolume()
                d.eqReady = mixOn()
                d.stretchReady = false
                d.automation = null
                d.load(src, startMs, play && playing, i)
            }
        }
    }

    /**
     * The song stopped with an error (usually YouTube dropping the connection or its audio address
     * expiring). Look it up again and carry on from the same spot, up to 3 times. If it still won't
     * play, report it (see [Problems]) and move on to the next song. Offline: just say so.
     */
    private fun recover(e: Exception) {
        val t = items.getOrNull(cur) ?: return fail(e)
        // A music file on this PC doesn't need the internet: if it won't play, skip it.
        val pc = PcMusic.isPcSong(t.url)
        if (pc) recoveries = maxOf(recoveries, 3)
        if (!pc && !Problems.online()) {
            // No internet: carry on with songs on this computer instead of stopping.
            if (offlineAdded.isEmpty() && goOffline(skip = true)) return
            if (offlineAdded.isNotEmpty() && nextIndex() != null) { next(); return }
            fail(java.net.UnknownHostException("offline"))
            return
        }
        if (!pc) YtRepo.forgetStreamUrls()
        if (recoveries < 3) {
            recoveries++
            val at = livePosition()
            val req = request
            val play = playing
            buffering = true
            android.util.Log.w("Glasswave", "Playback error, picking it back up at $at ms (try $recoveries)", e)
            main.postDelayed({
                if (req == request && items.getOrNull(cur)?.url == t.url) load(deck, t, cur, req, play, at)
            }, 700L * recoveries)
            return
        }
        Problems.songFailed(t, e, recoveries)
        if (nextIndex() != null) {
            Menus.say("Couldn't play \"${t.title}\", so it was skipped")
            next()
        } else {
            Menus.say("Couldn't play \"${t.title}\"")
            fail(e)
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
            addPendingHistory()
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
            recover(e)
        }
    }

    /* ---- Getting the next song ready, crossfade and Automix ---- */

    /** Automix is on (or Flow is playing, which always mixes), and nothing asks for songs to end on their own. */
    private fun mixOn() = (Settings.automix || Flow.active || mixedPlaylist() != null) && repeat != Player.REPEAT_MODE_ONE && !SleepTimer.endOfSong

    /** The playlist with Mix on that's playing now, if the queue is one (see [PlaylistMix]). */
    private fun mixedPlaylist(): String? = if (Flow.active) null else PlaylistMix.forQueue(items.map { it.url })

    private var mixing = false
    private var mixOld: AudioDeck? = null
    private var mixPlan: MixPlan? = null
    /** When (elapsed ms) the old song was heard at the mix point. */
    private var mixT0 = 0L
    private var mixNewStarted = false
    private var mixOutGain = 1f
    private var levelGain = 1f
    private var phaseEma = 0.0

    /** The planned change from this song to the next (see [MixPlanner]), and whether the next song is already loaded for it. */
    private var plan: MixPlan? = null
    private var planKey = ""
    private var planHad = 0
    private var planLocked = false
    /** Where in the next song the spare deck was loaded to start. */
    private var spareStart = 0L

    /** After a tempo-matched mix, the new song eases back to its own tempo. */
    private var glideFrom = 1f
    private var glideAt = 0L
    private var glideMs = 0L

    /** The volume a song starts at: silent when it's going to fade in. */
    private fun startVolume() = if (Settings.crossfade > 0 && !mixing) 0f else levelGain

    /** For the checks: told what each mix does, step by step. */
    internal var mixLog: ((String) -> Unit)? = null
    internal val nextPlan: MixPlan? get() = plan

    private val fader = object : Runnable {
        override fun run() {
            if (mixing) stepMix()
            else {
                val pos = deck.positionMs
                val dur = deck.durationMs
                prepareNext(pos, dur)
                armOld(pos)
                offlineWatch(pos, dur)
                if (!mixStep(pos, dur)) {
                    val fadeMs = Settings.crossfade * 1000L
                    if (fadeMs > 0 && deck.ready && !mixOn()) {
                        var v = 1f
                        if (pos < fadeMs) v = pos.toFloat() / fadeMs
                        if (dur > 0 && dur - pos < fadeMs) v = minOf(v, (dur - pos).toFloat() / fadeMs)
                        deck.volume = v.coerceIn(0f, 1f) * levelGain
                    } else if (deck.ready) {
                        deck.volume = levelGain
                    }
                }
                glide()
            }
            if (playing || mixing) main.postDelayed(this, 40)
        }
    }

    /** The plan for moving from this song to song [n], made again whenever more becomes known about either (until the next song is loaded for it). */
    private fun planFor(n: Int, dur: Long): MixPlan {
        val key = "$cur>$n"
        val tail = MixLibrary.profile(items[cur].url, MixPart.TAIL)
        val head = MixLibrary.profile(items[n].url, MixPart.HEAD)
        val had = (if (tail != null) 1 else 0) or (if (head != null) 2 else 0)
        plan?.let { if (planKey == key && (planLocked || had == planHad)) return it }
        // Tempo matching only at normal speed and pitch (a speed you set stays yours).
        val stretch = AudioDeck.speed == 1f && AudioDeck.pitch == 1f
        val p = mixedPlaylist()?.let { id -> MixPlanner.custom(dur, tail, head, PlaylistMix.transition(id, items[cur].url, items[n].url), stretch) }
            ?: MixPlanner.plan(dur, tail, head, canStretch = stretch)
        plan = p
        planKey = key
        planHad = had
        planLocked = false
        return p
    }

    /** Before the mix (or about 15 seconds before the end), load the next song on the spare deck, paused, right where it should start. */
    private fun prepareNext(pos: Long, dur: Long) {
        if (dur <= 0 || repeat == Player.REPEAT_MODE_ONE) return
        val n = nextIndex() ?: return
        if (n == cur) return
        val p = if (mixOn() && dur >= 45_000) planFor(n, dur) else null
        val lead = if (p != null) p.outStartMs - 15_000 else dur - 15_000
        if (pos < lead) return
        val startAt = p?.inStartMs ?: 0L
        if (spare.tag == n && spareStart == startAt) { planLocked = p != null; return }
        val t = items[n]
        spare.stop()
        spare.tag = n
        spareStart = startAt
        planLocked = p != null
        val req = request
        worker.execute {
            val src = runCatching { resolve(t) }.getOrNull() ?: return@execute
            main.post {
                if (req != request || spare.tag != n) return@post
                spare.volume = 0f
                spare.eqReady = p != null
                spare.stretchReady = p?.locksBeat == true
                spare.automation = p?.let { inAutomation(it) }
                spare.load(src, startAt, false, n)
            }
        }
    }

    /** A few seconds before the mix, once the next song is ready, the old song's side of the mix is set up, so it's exact even for the sound already queued. */
    private fun armOld(pos: Long) {
        val p = plan ?: return
        if (!planLocked || deck.automation != null || !spare.ready || spareStart != p.inStartMs) return
        if (pos < p.outStartMs - 3_000 || pos > p.outStartMs) return
        deck.automation = outAutomation(p)
    }

    private fun outAutomation(p: MixPlan) = AudioDeck.Automation { ms, out ->
        if (ms < p.outStartMs) { out[0] = 1f; out[1] = 1f; out[2] = 0f; return@Automation }
        val lv = p.levels(((ms - p.outStartMs) / p.lengthMs).toFloat())
        out[0] = lv.oldGain; out[1] = lv.oldBass; out[2] = lv.oldSweepHz
    }

    private fun inAutomation(p: MixPlan) = AudioDeck.Automation { ms, out ->
        val wall = (ms - p.inStartMs) / p.speed + p.inDelayMs
        val lv = p.levels((wall / p.lengthMs).toFloat())
        out[0] = lv.newGain; out[1] = lv.newBass; out[2] = 0f
    }

    /** Starts a mix right at the planned moment. True if it did. */
    private fun mixStep(pos: Long, dur: Long): Boolean {
        if (!mixOn() || !playing || dur < 45_000) return false
        val n = nextIndex() ?: return false
        val p = plan?.takeIf { planKey == "$cur>$n" } ?: return false
        val at = p.outStartMs
        if (pos < at - 20) return false
        if (pos > at + 1_500) { deck.automation = null; return false }
        if (spare.tag != n || !spare.ready || spareStart != p.inStartMs) {
            // Not loaded in time: a plain change at the end instead.
            deck.automation = null
            return false
        }
        if (deck.automation == null) deck.automation = outAutomation(p)
        mixPlan = p
        mixOutGain = levelGain
        mixOld = deck
        deck = spare
        spare = mixOld!!
        mixing = true
        mixT0 = SystemClock.elapsedRealtime() - (pos - at)
        phaseEma = 0.0
        glideMs = 0
        mixOld!!.volume = mixOutGain
        deck.volume = levelGain
        deck.mixSpeed = p.speed
        mixNewStarted = p.inDelayMs <= 0
        if (mixNewStarted) deck.play()
        mixLog?.invoke("mix starts at $pos ms: ${p.description}")
        moveTo(n, play = true, auto = true)
        return true
    }

    /** One step of a mix: start the new song when it's due, and keep the beats lined up. The decks do the fades themselves. */
    private fun stepMix() {
        val p = mixPlan ?: return endMix()
        val old = mixOld ?: return endMix()
        val heard = SystemClock.elapsedRealtime() - mixT0
        if (!mixNewStarted && heard >= p.inDelayMs) {
            deck.play()
            mixNewStarted = true
        }
        if (p.locksBeat && mixNewStarted && heard > p.inDelayMs + 300 && deck.ready) {
            // Phase lock: nudge the new song a touch faster or slower until its beats sit on the old song's.
            val e = p.phaseError(old.positionMs.toDouble(), deck.positionMs.toDouble())
            phaseEma = 0.6 * phaseEma + 0.4 * e
            deck.mixSpeed = (p.speed * (1 - (0.25 * phaseEma).coerceIn(-0.04, 0.04))).toFloat()
            mixLog?.invoke("  %5d ms: beats off by %+.3f beat (%+.0f ms), new song at %.4fx".format(heard, e, e * p.oldBeatMs, deck.mixSpeed))
        }
        if (heard >= p.lengthMs + 200 || old.ended) endMix()
    }

    private fun endMix() {
        val p = mixPlan
        mixing = false
        mixPlan = null
        mixOld?.let { it.automation = null; it.stop() }
        mixOld = null
        deck.automation = null
        deck.bass = 1f
        deck.volume = levelGain
        if (!mixNewStarted) { mixNewStarted = true; if (playing) deck.play() }
        // Ease back to the song's own tempo over a few bars.
        mixLog?.invoke("mix ends")
        if (deck.mixSpeed != 1f) {
            glideFrom = deck.mixSpeed
            glideAt = SystemClock.elapsedRealtime()
            glideMs = p?.glideMs?.coerceAtLeast(2_000) ?: 4_000
        }
    }

    private fun glide() {
        if (glideMs <= 0) return
        val t = ((SystemClock.elapsedRealtime() - glideAt).toFloat() / glideMs).coerceIn(0f, 1f)
        deck.mixSpeed = glideFrom + (1f - glideFrom) * t
        if (t >= 1f) { deck.mixSpeed = 1f; glideMs = 0 }
    }


    /* ---- No internet: carry on with songs on this computer ---- */

    /** Songs put in the queue because the internet went (taken out again when it's back). */
    private val offlineAdded = HashSet<String>()
    private var offlineCheckAt = 0L
    private var offlineBusy = false

    private fun isLocal(url: String) = Downloads.fileFor(url) != null || SongCache.fileFor(url) != null || PcMusic.fileOf(url) != null

    /**
     * Now and then near the end of a song: if the internet is gone and the next song isn't on this
     * computer, line up songs that are, so the music carries on. Once it's back, take them out again.
     */
    private fun offlineWatch(pos: Long, dur: Long) {
        val now = SystemClock.elapsedRealtime()
        if (offlineBusy || now - offlineCheckAt < if (offlineAdded.isNotEmpty()) 10_000 else 4_000) return
        if (offlineAdded.isEmpty()) {
            if (dur <= 0 || dur - pos > 30_000) return
            val next = nextIndex()?.let { items[it].url }
            if (next != null && isLocal(next)) return
        }
        offlineCheckAt = now
        offlineBusy = true
        worker.execute {
            val online = Problems.online()
            main.post {
                offlineBusy = false
                if (online && offlineAdded.isNotEmpty()) backOnline()
                else if (!online && offlineAdded.isEmpty()) goOffline(skip = false)
            }
        }
    }

    /** Lines up songs on this computer to play next, in an order that flows ([MixPlanner.offlineMix]). [skip] moves on to them now. */
    private fun goOffline(skip: Boolean): Boolean {
        val current = items.getOrNull(cur)
        val available = (Downloads.tracks.toList() + SongCache.tracks).filter { isLocal(it.url) }
        val recent = History.tracks.take(15).map { it.url }.toSet()
        val list = MixPlanner.offlineMix(current, available, recent)
        if (list.isEmpty()) return false
        for ((k, t) in list.withIndex()) {
            items += t
            val i = items.lastIndex
            base.add(base.indexOf(cur) + 1 + k, i)
            shuf.add(shuf.indexOf(cur) + 1 + k, i)
            offlineAdded += t.url
        }
        spare.stop()
        Menus.say("No internet, so songs saved on this computer are playing next")
        changed()
        if (skip) next()
        return true
    }

    /** The internet is back: take out the saved songs that haven't played yet, so your queue carries on. */
    private fun backOnline() {
        for (o in listOf(base, shuf)) {
            val now = o.indexOf(cur)
            val drop = o.filterIndexed { i, p -> i > now && items[p].url in offlineAdded }.toSet()
            o.removeAll { it in drop }
        }
        offlineAdded.clear()
        spare.stop()
        Menus.say("Back online")
        changed()
    }

    /* ---- Listening ahead: what Automix needs to know about the songs coming up ---- */

    /** Asks for this song's ending and the next few songs' starts to be listened to. */
    private fun listenAhead() {
        if (!mixOn() || cur < 0) return
        val jobs = ArrayList<Pair<String, MixPart>>()
        items.getOrNull(cur)?.let { jobs += it.url to MixPart.TAIL }
        var i = cur
        repeat(LOOK_AHEAD) {
            i = nextIndex(i, Player.REPEAT_MODE_OFF) ?: return@repeat
            jobs += items[i].url to MixPart.HEAD
        }
        MixLibrary.request(jobs)
    }

    /** Something new is known about a song: if it's this one or one coming up, the order and the plan may get better. */
    private fun onMixKnown(url: String) {
        if (!mixOn() || mixing || planLocked) return
        val upcoming = upcoming(LOOK_AHEAD).map { it.track.url }
        if (url != items.getOrNull(cur)?.url && url !in upcoming) return
        val before = nextIndex()
        pickNextForMix()
        if (nextIndex() != before) { spare.stop(); queueVersion++ }
    }

    /**
     * Automix, like a DJ: the next song is picked from the next few by how well it follows this one
     * (tempo, key, energy), preferring a different artist, with a pinch of chance. Songs you queued
     * yourself, and shuffle, are left alone.
     */
    private fun pickNextForMix() {
        if (shuffle || repeat == Player.REPEAT_MODE_ONE) return
        val p = base.indexOf(cur)
        if (p < 0 || p + 2 >= base.size) return
        if (items[base[p + 1]].url in Queued.ids) return
        val window = (p + 1 until minOf(base.size, p + 9)).filter { items[base[it]].url !in Queued.ids }
        if (window.size < 2) return
        val c = items[cur]
        val i = MixPlanner.choose(c.url, c.artist, window.map { items[base[it]].let { t -> t.url to t.artist } }, cur)
        val pick = window.getOrNull(i) ?: return
        if (pick != p + 1) base.add(p + 1, base.removeAt(pick))
    }

    private companion object {
        /** How many songs ahead Automix listens to, to pick the best next one. */
        const val LOOK_AHEAD = 8
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
                NotInterested.thin(YtMusicApi.radio(id))
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Autoplay lookup failed", e)
                main.post { if (autoplayFor == current) autoplayFor = null }
                return@execute
            }
            main.post {
                val queued = order.map { items[it].url }.toSet()
                val keys = order.mapTo(HashSet()) { YtRepo.songKey(items[it].title, items[it].artist) }
                val fresh = Autoplay.pick(tracks.filter { it.url !in queued }, keys, items.getOrNull(cur)?.artist ?: "")
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
