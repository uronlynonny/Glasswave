// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Songs you've played are kept on disk, so replays and seeking within them are instant. */
@OptIn(UnstableApi::class)
object AudioCache {
    @Volatile
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "audio"),
        LeastRecentlyUsedCacheEvictor(Settings.cacheBytes(context)),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }

    fun sizeBytes(context: Context): Long = runCatching { get(context).cacheSpace }.getOrDefault(0)

    fun clear(context: Context) {
        val c = get(context)
        for (key in c.keys.toList()) runCatching { c.removeResource(key) }
    }
}

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

/**
 * Reads a downloaded song from its file, and a song on this phone ("On this phone") straight from
 * the phone; everything else goes through the cache and the internet.
 */
@OptIn(UnstableApi::class)
private class LocalFirstDataSource(private val online: DataSource, private val file: FileDataSource) : DataSource {
    private var active: DataSource? = null
    private val listeners = ArrayList<TransferListener>()
    /** Opens content:// songs from the phone's music library. Made when first needed. */
    private var phone: DataSource? = null

    override fun addTransferListener(listener: TransferListener) {
        online.addTransferListener(listener)
        file.addTransferListener(listener)
        listeners += listener
        phone?.addTransferListener(listener)
    }

    private fun phoneSource(): DataSource = phone ?: run {
        val app = PhoneMusic.app ?: throw IOException("Glasswave isn't ready to open songs on this phone yet")
        androidx.media3.datasource.ContentDataSource(app).also { s ->
            listeners.forEach { s.addTransferListener(it) }
            phone = s
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        // Songs on this phone: open them directly, not through the cache or the internet.
        when (dataSpec.uri.scheme) {
            "content" -> phoneSource().let { active = it; return it.open(dataSpec) }
            "file" -> { active = file; return file.open(dataSpec) }
        }
        val local = Downloads.fileFor(dataSpec.uri.toString())
        return if (local != null) {
            active = file
            file.open(dataSpec.withUri(Uri.fromFile(local)))
        } else {
            active = online
            online.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() {
        try { active?.close() } finally { active = null }
    }
}

/**
 * YouTube slows a song down to playing speed when it's fetched in one long request, and sometimes
 * cuts it off partway, so the song stops. Fetched in 2 MB pieces it comes at full speed (a whole song
 * in about 3 seconds instead of 2 minutes). Only YouTube's audio servers are fetched this way.
 */
@OptIn(UnstableApi::class)
private class ChunkedDataSource(private val upstream: DataSource) : DataSource {
    private val unset = C.LENGTH_UNSET.toLong()
    private var spec: DataSpec? = null
    private var pos = 0L
    /** Where this read ends (exclusive), or unset when not known. */
    private var stop = unset
    /** Bytes still to come from the open piece. */
    private var left = 0L
    private var readInPiece = 0L
    /** The open piece reaches the end. */
    private var last = false
    private var direct = false

    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)

    override fun open(dataSpec: DataSpec): Long {
        direct = dataSpec.uri.host?.endsWith("googlevideo.com") != true
        if (direct) return upstream.open(dataSpec)
        spec = dataSpec
        pos = dataSpec.position
        val songBytes = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        stop = when {
            dataSpec.length != unset -> dataSpec.position + dataSpec.length
            songBytes != null && songBytes > pos -> songBytes
            else -> unset
        }
        openPiece()
        return if (stop != unset) stop - dataSpec.position else unset
    }

    private fun openPiece() {
        val s = spec ?: throw IOException("Not open")
        val want = if (stop != unset) minOf(PIECE, stop - pos) else PIECE
        val got = upstream.open(s.subrange(pos - s.position, want))
        left = if (got != unset) minOf(got, want) else want
        last = (stop != unset && pos + want >= stop) || (got != unset && got < want)
        readInPiece = 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (direct) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (left == 0L) {
            if (last) return C.RESULT_END_OF_INPUT
            upstream.close()
            openPiece()
        }
        val n = upstream.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (n == C.RESULT_END_OF_INPUT) {
            // The piece ended early: carry on with the next one (unless nothing came at all).
            if (last || stop == unset) return C.RESULT_END_OF_INPUT
            if (readInPiece == 0L) throw IOException("YouTube sent an empty piece at byte $pos")
            left = 0
            return read(buffer, offset, length)
        }
        pos += n
        left -= n
        readInPiece += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        try { upstream.close() } finally { spec = null }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    companion object {
        const val PIECE = 2L * 1024 * 1024
    }
}

/**
 * Plays audio in the background with a lock-screen / notification player.
 * Queue items carry a YouTube page address (or a Spotify song to match); the real audio
 * address is looked up just before it plays.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val settingsListener: () -> Unit = { session?.player?.let { applySettings(it); maybeAutoplay(it); applyLevel(it) } }
    private val soundListener: () -> Unit = { session?.player?.let { applyPitch(it) } }

    /** A player whose sound goes through Glasswave's own effects (equalizer, bass, reverb...). */
    private fun withEffects(measure: Boolean) = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(SoundProcessor(measure)))
                .build()
    }

    /** Pitch from Sound settings, keeping whatever speed is set. */
    private fun applyPitch(p: Player) {
        val want = Sound.pitchFactor()
        if (p.playbackParameters.pitch != want) p.playbackParameters = PlaybackParameters(p.playbackParameters.speed, want)
    }

    /* ---- Volume leveling: every song at the same loudness ---- */

    /** How loud this song should play (0..1), multiplied into the crossfade volume. */
    private var levelGain = 1f
    /** Lifts quiet songs (the player's volume can only go down, not up). */
    private var boost: android.media.audiofx.LoudnessEnhancer? = null
    private var boostSession = 0

    private fun applyLevel(p: Player) {
        val item = p.currentMediaItem ?: return
        if (!Settings.volumeLeveling) { setLevel(p, 0f); return }
        val next = p.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { p.getMediaItemAt(it).mediaId }
        worker.execute {
            val db = runCatching { YtRepo.videoIdFor(item.mediaId)?.let { Loudness.fetch(it) } }.getOrNull()
            if (db != null) main.post { if (p.currentMediaItem?.mediaId == item.mediaId && Settings.volumeLeveling) setLevel(p, Loudness.gainDb(db)) }
            // Look the next song up now, so its level is ready the moment it starts.
            next?.let { runCatching { YtRepo.videoIdFor(it)?.let { id -> Loudness.fetch(id) } } }
        }
    }

    private fun setLevel(p: Player, gainDb: Float) {
        levelGain = if (gainDb < 0f) Math.pow(10.0, gainDb / 20.0).toFloat() else 1f
        runCatching {
            val sid = (p as? ExoPlayer)?.audioSessionId ?: 0
            if (gainDb > 0.2f && sid != 0) {
                if (boost == null || boostSession != sid) {
                    boost?.release()
                    boost = android.media.audiofx.LoudnessEnhancer(sid)
                    boostSession = sid
                }
                boost?.setTargetGain((gainDb * 100).toInt())
                boost?.enabled = true
            } else {
                boost?.enabled = false
            }
        }
        // While crossfading the fader sets the volume itself (and uses levelGain).
        if ((Settings.crossfade == 0 || !p.isPlaying) && !mixing) p.volume = levelGain
    }

    private class Resolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri
            val page = when {
                uri.scheme == "gwmatch" -> YtRepo.match(
                    uri.getQueryParameter("q") ?: throw IOException("Empty song"),
                    uri.getQueryParameter("d")?.toLongOrNull() ?: 0,
                )
                uri.host?.endsWith("youtube.com") == true -> uri.toString()
                else -> return dataSpec
            }
            val real = try {
                YtRepo.streamUrl(page)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e)
            }
            return dataSpec.withUri(Uri.parse(real))
        }
    }

    private fun applySettings(p: Player) {
        (p as? ExoPlayer)?.skipSilenceEnabled = Settings.skipSilence
        if (Settings.crossfade == 0) p.volume = levelGain
    }

    override fun onCreate() {
        super.onCreate()
        // A changed copy of the app doesn't get to play music.
        check(Integrity.ok(this)) { "This copy of Glasswave has been modified. Get the original from github.com/uronlynonny/Glasswave" }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(YtDownloader.USER_AGENT)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(ChunkedDataSource.Factory(http), Resolver())
        val cached = CacheDataSource.Factory()
            .setCache(AudioCache.get(this))
            .setUpstreamDataSourceFactory(resolving)
            // Same song at a different quality is stored separately.
            .setCacheKeyFactory { spec -> spec.uri.toString() + "|" + Settings.streamQuality().name }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val sources = DataSource.Factory { LocalFirstDataSource(cached.createDataSource(), FileDataSource()) }
        mediaSources = sources

        // Start making sound after a quarter of a second of audio, then download the rest of the
        // song (and the next one) in the background. Keep what's played so seeking back is instant.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 15 * 60_000, 250, 1_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15 * 60_000, true)
            .build()

        val player = ExoPlayer.Builder(this, withEffects(measure = true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources))
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        applySettings(player)
        applyPitch(player)
        Settings.listeners += settingsListener
        Sound.listeners += soundListener

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Skipped or picked another song mid-mix: stop the old song's tail straight away.
                if (mixing && !mixOwnTransition) endMix(player)
                mixOwnTransition = false
                if (!mixing) mixPreparedFor = null
                mediaItem?.mediaId?.let { Queued.ids.remove(it) }
                if (Settings.automix) pickNextForMix(player)
                logPlay(player)
                listening = mediaItem
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) SleepTimer.onSongEnded(player)
                prefetchAhead(player)
                maybeAutoplay(player)
                verifyCurrent(player)
                applyLevel(player)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                prefetchAhead(player)
                maybeAutoplay(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Paused (not just loading the next song) in the middle of a mix: let the old song go.
                if (!isPlaying && mixing && !player.playWhenReady) endMix(player)
                main.removeCallbacks(fader)
                if (isPlaying) main.post(fader)
                // Count only time actually spent playing.
                val now = android.os.SystemClock.elapsedRealtime()
                if (isPlaying) {
                    if (listening == null) listening = player.currentMediaItem
                    playingSince = now
                } else if (playingSince > 0) {
                    listenedMs += now - playingSince
                    playingSince = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) = recover(player, error)

            override fun onShuffleModeEnabledChanged(shuffle: Boolean) = maybeAutoplay(player)
            override fun onRepeatModeChanged(mode: Int) = maybeAutoplay(player)
        })
        SleepTimer.player = player

        // Bring back what was playing last time (paused), and keep it saved as things change.
        LastPlayed.restore(player)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) saveSoon()
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    main.removeCallbacks(saveTick)
                    if (p.isPlaying) main.postDelayed(saveTick, 15_000)
                }
            }

            // A song brought back from last time isn't loaded yet: load it when play is pressed.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 && player.playerError == null) player.prepare()
            }
        })

        // Let the phone's equalizer attach to our audio.
        audioSessionId = player.audioSessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
        // Tapping the song in the notification, lock screen or the phone's media bar opens the player.
        val openPlayer = PendingIntent.getActivity(
            this, 7,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openPlayer).build()

        // The home-screen widget follows the song and the play state.
        livePlayer = player
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    )
                ) pushWidget(p)
            }
        })
        pushWidget(player)
    }

    private fun pushWidget(p: Player) {
        val item = p.currentMediaItem
        val md = item?.mediaMetadata
        Widget.update(this, md?.title?.toString(), md?.artist?.toString(), md?.artworkUri?.toString(), p.isPlaying, live = true)
    }

    /* ---- Remember what was playing (see LastPlayed) ---- */

    private val saveNow = Runnable { session?.player?.let { LastPlayed.save(it) } }

    /** Saves shortly after a change (several changes at once are saved together). */
    private fun saveSoon() {
        main.removeCallbacks(saveNow)
        main.postDelayed(saveNow, 500)
    }

    /** While playing, saves the spot in the song every 15 seconds. */
    private val saveTick = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            LastPlayed.save(p)
            if (p.isPlaying) main.postDelayed(this, 15_000)
        }
    }

    /* ---- Make sure the song playing is the song you picked ---- */

    private val verified = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Checks, in the background, that the audio behind the playing entry really is the song its name
     * says (a wrong Spotify match, or an entry mislabelled by an older version, could point at another
     * song). If it isn't, the right song is found by its name and artist and swapped in straight away,
     * the right match is remembered, and the wrong audio is dropped from the cache.
     */
    private fun verifyCurrent(p: Player) {
        val item = p.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isEmpty() || !verified.add(id) || Downloads.fileFor(id) != null) return
        val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return
        val artist = item.mediaMetadata.artist?.toString() ?: ""
        worker.execute {
            try {
                val videoId = YtRepo.videoIdFor(id) ?: return@execute
                val real = YtMusicApi.trackInfo(videoId) ?: return@execute
                if (YtRepo.sameSong(title, real.title)) return@execute
                android.util.Log.w("Glasswave", "\"$title\" was playing \"${real.title}\"; finding the right song")
                val right = YtRepo.findSong(title, artist, real.seconds) ?: return@execute
                val rightId = android.net.Uri.parse(right.url).getQueryParameter("v")
                if (rightId == null || rightId == videoId) return@execute
                val uri = Uri.parse(id)
                if (uri.scheme == "gwmatch") uri.getQueryParameter("q")?.let { YtRepo.setMatch(it, right.url) }
                // Drop the wrong audio kept for this entry.
                Quality.entries.forEach { q -> runCatching { AudioCache.get(this).removeResource(id + "|" + q.name) } }
                main.post {
                    val idx = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == id } ?: return@post
                    val fixed = item.buildUpon().setMediaId(right.url).setUri(right.url).build()
                    verified += right.url
                    History.removeUrl(id)
                    p.replaceMediaItem(idx, fixed)
                }
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Couldn't check the playing song", e)
                verified.remove(id) // try again next time it plays
            }
        }
    }

    /* ---- Listening stats: log a play once a song has really been listened to ---- */

    private var listening: MediaItem? = null
    private var listenedMs = 0L
    private var playingSince = 0L

    /** Logs the song that was playing (if listened to for 30 s, or most of a short song), then starts counting afresh. */
    private fun logPlay(p: Player) {
        val now = android.os.SystemClock.elapsedRealtime()
        val ms = listenedMs + if (playingSince > 0) now - playingSince else 0
        val item = listening
        if (item != null && item.mediaId.isNotEmpty()) {
            val md = item.mediaMetadata
            val enough = ms >= 30_000 || (ms >= 10_000 && p.duration in 1..(ms * 2))
            if (enough) {
                Stats.record(Track(item.mediaId, md.title?.toString() ?: "", md.artist?.toString() ?: "", 0, md.artworkUri?.toString()), ms)
            }
        }
        listenedMs = 0
        playingSince = if (p.isPlaying) now else 0
    }

    /** Looks up the next two songs' audio in the background so skipping is instant. */
    private fun prefetchAhead(p: Player) {
        var i = p.currentMediaItemIndex
        repeat(2) {
            i = p.currentTimeline.takeIf { !it.isEmpty }?.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
                ?: C.INDEX_UNSET
            if (i == C.INDEX_UNSET || i >= p.mediaItemCount) return
            val uri = p.getMediaItemAt(i).localConfiguration?.uri ?: return
            if (Downloads.fileFor(uri.toString()) == null) worker.execute { YtRepo.prefetch(uri) }
        }
    }

    /* ---- Autoplay: when the queue runs out, keep going with similar songs ---- */

    private var autoplayFor: String? = null

    /** Autoplay was switched off: take out the songs it added that haven't played yet. */
    private fun removeAutoplayed(p: Player) {
        val now = p.currentMediaItemIndex
        for (i in p.mediaItemCount - 1 downTo 0) {
            if (i > now && p.getMediaItemAt(i).mediaId in Autoplay.added) p.removeMediaItem(i)
        }
        Autoplay.added.clear()
        autoplayFor = null
    }

    /** The song that last stopped with an error, and how many times it's been picked back up. */
    private var recoveringFor: String? = null
    private var recoveries = 0

    /**
     * A song stopped with an error (usually YouTube dropping the connection). Look its audio up again
     * and carry on from the same spot. If it still won't play after a few tries, tell nonny dev
     * (see [Problems]) and move on to the next song.
     */
    private fun recover(p: ExoPlayer, error: PlaybackException) {
        val item = p.currentMediaItem ?: return
        if (recoveringFor != item.mediaId) {
            recoveringFor = item.mediaId
            recoveries = 0
        }
        // A song stored on the phone doesn't need the internet: if it won't play, skip it.
        val phone = PhoneMusic.isPhoneSong(item.mediaId)
        if (phone) recoveries = maxOf(recoveries, 3)
        if (!phone && !Problems.online(this)) {
            Menus.say("No internet connection")
            return
        }
        if (!phone) YtRepo.forgetStreamUrls()
        if (recoveries < 3) {
            recoveries++
            main.postDelayed({
                if (p.currentMediaItem?.mediaId == item.mediaId && p.playbackState == Player.STATE_IDLE) p.prepare()
            }, 700L * recoveries)
            return
        }
        Problems.songFailed(this, item, error, recoveries)
        val title = item.mediaMetadata.title?.toString() ?: "this song"
        if (p.hasNextMediaItem()) {
            Menus.say("Couldn't play \"$title\", so it was skipped")
            p.seekToNextMediaItem()
            p.prepare()
        } else {
            Menus.say("Couldn't play \"$title\"")
        }
    }

    private fun maybeAutoplay(p: Player) {
        if (!Settings.autoplay) {
            if (Autoplay.added.isNotEmpty()) removeAutoplayed(p)
            return
        }
        if (p.repeatMode != Player.REPEAT_MODE_OFF || p.mediaItemCount == 0) return
        if (p.nextMediaItemIndex != C.INDEX_UNSET) return // more songs still queued
        val current = p.currentMediaItem?.mediaId ?: return
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
                val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
                val queued = items.map { it.mediaId }.toSet()
                // YouTube's radio often lists the same song twice (the album track and its music
                // video, or "Artist - Title" uploads). One of each song, and none already queued.
                val keys = items.mapTo(HashSet()) {
                    YtRepo.songKey(it.mediaMetadata.title?.toString() ?: "", it.mediaMetadata.artist?.toString() ?: "")
                }
                val playing = p.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val fresh = Autoplay.pick(tracks.filter { it.url !in queued }, keys, playing)
                    .map { it.toMediaItem() }
                if (fresh.isNotEmpty() && Settings.autoplay) {
                    Autoplay.added += fresh.map { it.mediaId }
                    p.addMediaItems(fresh)
                }
            }
        }
    }

    /* ---- Crossfade: fade each song out at its end and in at its start ---- */

    private val fader = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            if (stepMix(p)) {
                if (p.isPlaying || mixing) main.postDelayed(this, 40)
                return
            }
            val fadeMs = Settings.crossfade * 1000L
            if (fadeMs > 0 && p.isPlaying && !mixOn(p)) {
                val pos = p.currentPosition
                val dur = p.duration
                var v = 1f
                if (pos < fadeMs) v = pos.toFloat() / fadeMs
                if (dur > 0 && dur - pos < fadeMs) v = minOf(v, (dur - pos).toFloat() / fadeMs)
                p.volume = v.coerceIn(0f, 1f) * levelGain
            } else if (p.volume != levelGain) {
                p.volume = levelGain
            }
            // Check more often close to a mix point, so the blend starts right on time.
            if (p.isPlaying) main.postDelayed(this, if (mixOn(p) && p.duration > 0 && p.duration - p.currentPosition < Settings.mixSeconds * 1000L + 3000) 40 else 80)
        }
    }

    /* ---- Smooth mixing: the end of one song plays under the start of the next ---- */

    private var mediaSources: DataSource.Factory? = null
    /** A second player that carries the last seconds of the outgoing song while the main player moves on. */
    private var mixer: ExoPlayer? = null
    private var mixPreparedFor: String? = null
    private var mixAt = 0L
    private var mixing = false
    private var mixOwnTransition = false
    private var mixStartAt = 0L
    private var mixLength = 0L
    private var mixOutGain = 1f

    private fun mixOn(p: Player): Boolean {
        return Settings.automix && p.repeatMode != Player.REPEAT_MODE_ONE && !SleepTimer.endOfSong
    }

    private fun mixerFor(): ExoPlayer? {
        mixer?.let { return it }
        val src = mediaSources ?: return null
        return ExoPlayer.Builder(this, withEffects(measure = false))
            .setMediaSourceFactory(DefaultMediaSourceFactory(src))
            // Same sound settings as the main player, but it never takes audio focus of its own.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
            .also { mixer = it }
    }

    /**
     * One step of mixing. About 15 seconds before a song ends, its last part is loaded into the
     * second player. At the mix point that player takes over the outgoing song's ending, while the
     * main player (and the notification, lock screen and app) moves to the next song from its
     * start. Over the overlap the old song fades out and the new one fades in, on an equal-power
     * curve so the loudness doesn't dip in the middle. Returns true while it's in charge of the volume.
     */
    private fun stepMix(p: Player): Boolean {
        if (mixing) {
            val m = mixer
            val k = ((android.os.SystemClock.elapsedRealtime() - mixStartAt).toFloat() / mixLength).coerceIn(0f, 1f)
            val a = (k * Math.PI / 2).toFloat()
            m?.volume = kotlin.math.cos(a) * mixOutGain
            p.volume = kotlin.math.sin(a) * levelGain
            if (k >= 1f || m == null || m.playbackState == Player.STATE_ENDED || m.playbackState == Player.STATE_IDLE) endMix(p)
            return true
        }
        if (!mixOn(p) || !p.isPlaying) return false
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur < 45_000) return false
        if (p.nextMediaItemIndex == C.INDEX_UNSET) return false
        val item = p.currentMediaItem ?: return false
        val len = minOf(Settings.mixSeconds * 1000L, dur / 5)
        val start = dur - len
        val pos = p.currentPosition
        if (pos < start - 15_000) return false

        // Get the ending ready ahead of time (from the cache, so it's quick).
        if (mixPreparedFor != item.mediaId) {
            val m = mixerFor() ?: return false
            m.setMediaItem(item)
            m.prepare()
            m.seekTo(start)
            m.playWhenReady = false
            m.volume = levelGain
            // Same speed and pitch as the song it carries on.
            m.playbackParameters = p.playbackParameters
            mixPreparedFor = item.mediaId
            mixAt = start
            return false
        }
        // The mix point. Only right at it: after a seek past it, the song just ends normally.
        if (pos >= mixAt && pos < mixAt + 1_500) {
            val m = mixer ?: return false
            if (m.playbackState != Player.STATE_READY) return false // not loaded in time: plain change
            mixOutGain = levelGain
            m.volume = levelGain
            m.play()
            mixing = true
            mixOwnTransition = true
            mixStartAt = android.os.SystemClock.elapsedRealtime()
            mixLength = dur - mixAt
            p.volume = 0f
            p.seekToNextMediaItem()
            return true
        }
        return false
    }

    /**
     * With Automix on, the next song isn't simply the next one in the list: like a DJ, it's picked
     * at random from the next few, preferring a different artist from the one playing. Everything
     * still gets played, just not in an order you can see coming. Songs you queued yourself, and
     * shuffle (already random), are left alone.
     */
    private fun pickNextForMix(p: Player) {
        if (p.shuffleModeEnabled || p.repeatMode == Player.REPEAT_MODE_ONE) return
        val next = p.currentMediaItemIndex + 1
        val count = p.mediaItemCount
        if (next >= count - 1) return
        if (p.getMediaItemAt(next).mediaId in Queued.ids) return
        fun artist(i: Int) = p.getMediaItemAt(i).mediaMetadata.artist?.toString()?.substringBefore(",")?.trim()?.lowercase() ?: ""
        val now = artist(p.currentMediaItemIndex)
        val window = (next until minOf(count, next + 8)).filter { p.getMediaItemAt(it).mediaId !in Queued.ids }
        if (window.size < 2) return
        val pool = window.filter { artist(it) != now }.ifEmpty { window }
        val pick = pool.random()
        if (pick != next) p.moveMediaItem(pick, next)
    }

    private fun endMix(p: Player) {
        mixing = false
        mixPreparedFor = null
        mixer?.let { m -> m.pause(); m.stop(); m.clearMediaItems() }
        p.volume = levelGain
    }

    companion object {
        /** The running player, for the home-screen widget's buttons. Null when the service isn't running. */
        @Volatile
        var livePlayer: Player? = null
            private set

        /** The player's audio session, for opening the phone's equalizer. 0 when not playing yet. */
        @Volatile
        var audioSessionId = 0
            private set
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(saveNow)
        main.removeCallbacks(saveTick)
        session?.player?.let { LastPlayed.save(it); logPlay(it) }
        runCatching { boost?.release() }
        boost = null
        runCatching { mixer?.release() }
        mixer = null
        // The widget keeps the last song but its buttons now open the app.
        session?.player?.let { p ->
            val md = p.currentMediaItem?.mediaMetadata
            Widget.update(this, md?.title?.toString(), md?.artist?.toString(), md?.artworkUri?.toString(), false, live = false)
        }
        livePlayer = null
        Settings.listeners -= settingsListener
        Sound.listeners -= soundListener
        main.removeCallbacks(fader)
        worker.shutdownNow()
        SleepTimer.cancel()
        SleepTimer.player = null
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName),
        )
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
