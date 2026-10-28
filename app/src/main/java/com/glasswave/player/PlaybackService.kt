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
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
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

/** Automix is changing the tempo for a moment (to match beats), so the speed shown stays your own. */
object MixTempo {
    @Volatile var busy = false
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
    /** Saves the next songs whole, one at a time, so a bad connection doesn't stop the music. */
    private val saver = Executors.newSingleThreadExecutor()
    private var cacheFactory: CacheDataSource.Factory? = null
    private val main = Handler(Looper.getMainLooper())
    private val settingsListener: () -> Unit = { session?.player?.let { applySettings(it); maybeAutoplay(it); applyLevel(it); listenAhead(it) } }
    private val mixListener: (String) -> Unit = { url -> main.post { session?.player?.let { onMixKnown(it, url) } } }
    private val soundListener: () -> Unit = { session?.player?.let { applyPitch(it) } }

    /** A player whose sound goes through Glasswave's own effects (equalizer, bass, reverb...) and Automix's mix EQ. */
    private fun withEffects(measure: Boolean, mix: MixFilter) = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(mix, SoundProcessor(measure)))
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
        cacheFactory = cached
        val sources = DataSource.Factory { LocalFirstDataSource(cached.createDataSource(), FileDataSource()) }
        mediaSources = sources
        MixLibrary.decoder = MixAudio(sources)
        MixLibrary.listeners += mixListener

        // Start making sound after a quarter of a second of audio, then download the rest of the
        // song (and the next one) in the background. Keep what's played so seeking back is instant.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 15 * 60_000, 250, 1_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15 * 60_000, true)
            .build()

        val player = ExoPlayer.Builder(this, withEffects(measure = true, mix = mainFilter))
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
                val own = mixOwnTransition
                if (mixing && !own) endMix(player)
                mixOwnTransition = false
                if (!mixing) mixPreparedFor = null
                // A song you picked plays at its own tempo straight away.
                if (!own && setSpeedTo != 1f) { glideMs = 0; setSpeed(player, 1f) }
                mediaItem?.mediaId?.let { Queued.ids.remove(it) }
                if (mixOn(player)) pickNextForMix(player)
                listenAhead(player)
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
                listenAhead(player)
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // You jumped somewhere mid-mix: let the old song go.
                if (reason != Player.DISCONTINUITY_REASON_SEEK || !mixing) return
                if (mixOwnSeek) mixOwnSeek = false else endMix(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Paused (not just loading the next song) in the middle of a mix: let the old song go.
                if (!isPlaying && mixing && !player.playWhenReady) endMix(player)
                main.removeCallbacks(fader)
                if (isPlaying || mixing) main.post(fader)
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
        if (player.mediaItemCount > 0) main.postDelayed(forgetIdle, LastPlayed.FORGET_AFTER_MS)
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
                    main.removeCallbacks(forgetIdle)
                    if (p.isPlaying) main.postDelayed(saveTick, 5_000)
                    else if (p.mediaItemCount > 0) main.postDelayed(forgetIdle, LastPlayed.FORGET_AFTER_MS)
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

    /** Paused (or stopped) for an hour: the song and queue are cleared and not brought back. */
    private val forgetIdle = Runnable {
        val p = session?.player ?: return@Runnable
        if (p.isPlaying) return@Runnable
        p.stop()
        p.clearMediaItems()
        LastPlayed.clear()
    }

    /** While playing, saves the spot in the song every 5 seconds (a crash loses at most that). */
    private val saveTick = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            LastPlayed.save(p)
            if (p.isPlaying) main.postDelayed(this, 5_000)
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

    /**
     * Looks up the next two songs' audio in the background so skipping is instant, and saves them
     * whole (unless saving data), so with a bad connection the music keeps going for a few songs.
     */
    private fun prefetchAhead(p: Player) {
        var i = p.currentMediaItemIndex
        repeat(2) {
            i = p.currentTimeline.takeIf { !it.isEmpty }?.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
                ?: C.INDEX_UNSET
            if (i == C.INDEX_UNSET || i >= p.mediaItemCount) return
            val uri = p.getMediaItemAt(i).localConfiguration?.uri ?: return
            if (Downloads.fileFor(uri.toString()) == null) {
                worker.execute { YtRepo.prefetch(uri) }
                val f = cacheFactory
                if (f != null && !Settings.savingData) saver.execute {
                    runCatching { CacheWriter(f.createDataSource(), DataSpec(uri), null, null).cache() }
                }
            }
        }
    }

    /* ---- No internet: carry on with songs saved on the phone ---- */

    /** Songs put in the queue because the internet went (taken out again when it's back). */
    private val offlineAdded = HashSet<String>()
    private var offlineCheckAt = 0L

    /** The whole song is in the cache (at the quality the player asks for), so it plays offline. */
    private fun cachedWhole(url: String): Boolean = runCatching {
        val c = AudioCache.get(this)
        val key = url + "|" + Settings.quality.name
        val len = ContentMetadata.getContentLength(c.getContentMetadata(key))
        len > 0 && c.isCached(key, 0, len)
    }.getOrDefault(false)

    private fun isLocal(url: String) = Downloads.fileFor(url) != null || PhoneMusic.isPhoneSong(url) || cachedWhole(url)

    /**
     * Now and then near the end of a song: if the internet is gone and the next song isn't on the
     * phone, line up songs that are (downloaded or kept in the cache), so the music carries on.
     * Once it's back, take them out again.
     */
    private fun offlineWatch(p: Player) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - offlineCheckAt < if (offlineAdded.isNotEmpty()) 10_000 else 4_000) return
        if (offlineAdded.isEmpty()) {
            val dur = p.duration
            if (dur == C.TIME_UNSET || dur <= 0 || dur - p.currentPosition > 30_000) return
        }
        offlineCheckAt = now
        val online = Problems.online(this)
        if (online && offlineAdded.isNotEmpty()) backOnline(p)
        else if (!online && offlineAdded.isEmpty()) {
            val next = p.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { p.getMediaItemAt(it).mediaId }
            if (next == null || !isLocal(next)) goOffline(p, skip = false)
        }
    }

    /** Lines up saved songs to play next, in an order that flows ([MixPlanner.offlineMix]). [skip] moves on to them now. */
    private fun goOffline(p: Player, skip: Boolean) {
        val cur = p.currentMediaItem
        val current = cur?.let { Track(it.mediaId, it.mediaMetadata.title?.toString() ?: "", it.mediaMetadata.artist?.toString() ?: "", 0, it.mediaMetadata.artworkUri?.toString()) }
        // What each song is called, from what's been played and downloaded.
        val known = LinkedHashMap<String, Track>()
        for (t in Downloads.tracks.toList() + History.tracks.toList()) known.putIfAbsent(t.url, t)
        val downloaded = Downloads.tracks.toList()
        val recent = History.tracks.take(15).map { it.url }.toSet()
        val quality = "|" + Settings.quality.name
        offlineCheckAt = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            val cached = runCatching {
                AudioCache.get(this).keys.filter { it.endsWith(quality) }.map { it.removeSuffix(quality) }.filter { cachedWhole(it) }
            }.getOrDefault(emptyList())
            val available = downloaded + cached.mapNotNull { known[it] }
            val list = MixPlanner.offlineMix(current, available, recent)
            main.post {
                if (list.isEmpty()) { Menus.say("No internet connection"); return@post }
                if (p.currentMediaItem?.mediaId != current?.url) return@post
                offlineAdded += list.map { it.url }
                p.addMediaItems(p.currentMediaItemIndex + 1, list.map { it.toMediaItem() })
                Menus.say("No internet, so songs saved on your phone are playing next")
                if (skip) nextLocal(p)?.let { p.seekTo(it, 0); p.prepare() }
            }
        }
    }

    /** The next song in play order that's on the phone. */
    private fun nextLocal(p: Player): Int? {
        val tl = p.currentTimeline.takeIf { !it.isEmpty } ?: return null
        var i = p.currentMediaItemIndex
        repeat(p.mediaItemCount) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) return null
            if (isLocal(p.getMediaItemAt(i).mediaId)) return i
        }
        return null
    }

    /** The internet is back: take out the saved songs that haven't played yet, so your queue carries on. */
    private fun backOnline(p: Player) {
        val now = p.currentMediaItemIndex
        for (i in p.mediaItemCount - 1 downTo now + 1) if (p.getMediaItemAt(i).mediaId in offlineAdded) p.removeMediaItem(i)
        offlineAdded.clear()
        Menus.say("Back online")
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
            // No internet: carry on with songs saved on the phone instead of stopping.
            if (offlineAdded.isEmpty()) { goOffline(p, skip = true); return }
            nextLocal(p)?.let { p.seekTo(it, 0); p.prepare(); return }
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
            glide(p)
            offlineWatch(p)
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
            val near = plan?.let { p.currentPosition > it.outStartMs - 3_000 } == true
            if (p.isPlaying) main.postDelayed(this, if (mixOn(p) && (near || p.duration > 0 && p.duration - p.currentPosition < 20_000)) 40 else 80)
        }
    }

    /* ---- Automix: DJ-style transitions (see MixPlanner) ---- */

    private var mediaSources: DataSource.Factory? = null
    /** A second player that carries the outgoing song's ending while the main player moves on. */
    private var mixer: ExoPlayer? = null
    /** The mix EQ on each player. */
    private val mainFilter = MixFilter()
    private val mixerFilter = MixFilter()
    private var mixPreparedFor: String? = null
    private var mixing = false
    /** The main player has moved to the new song (before that, the old song's first moments of the mix play on it). */
    private var handedOff = false
    private var mixOwnTransition = false
    private var mixOwnSeek = false
    private var mixPlan: MixPlan? = null
    private var mixNext = C.INDEX_UNSET
    /** When (elapsed ms) the old song was at the mix point. */
    private var mixT0 = 0L
    private var mixOutGain = 1f
    private var phaseEma = 0.0
    private var lastSpeedAt = 0L
    private var lagChecked = false

    /** The planned change from this song to the next, and whether the second player is already loaded for it. */
    private var plan: MixPlan? = null
    private var planKey = ""
    private var planHad = 0
    private var planLocked = false

    /** After a tempo-matched mix, the new song eases back to its own tempo. */
    private var glideFrom = 1f
    private var glideAt = 0L
    private var glideMs = 0L
    private var setSpeedTo = 1f

    /** How long the main player takes to start a song after a jump (learned, so beats land together from the start). */
    private val mixPrefs by lazy { getSharedPreferences("mix", Context.MODE_PRIVATE) }
    private var startLag = -1L
        get() { if (field < 0) field = mixPrefs.getLong("start_lag", 120); return field }

    /** Automix is on (or Flow is playing, which always mixes), and nothing asks for songs to end on their own. */
    private fun mixOn(p: Player): Boolean {
        return (Settings.automix || Flow.active) && p.repeatMode != Player.REPEAT_MODE_ONE && !SleepTimer.endOfSong
    }

    private fun mixerFor(): ExoPlayer? {
        mixer?.let { return it }
        val src = mediaSources ?: return null
        return ExoPlayer.Builder(this, withEffects(measure = false, mix = mixerFilter))
            .setMediaSourceFactory(DefaultMediaSourceFactory(src))
            // Same sound settings as the main player, but it never takes audio focus of its own.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
            .also { mixer = it }
    }

    /** The plan from this song to the next, made again whenever more becomes known about either (until the second player is loaded for it). */
    private fun planFor(cur: String, next: String, dur: Long, canStretch: Boolean): MixPlan {
        val key = "$cur>$next"
        val tail = MixLibrary.profile(cur, MixPart.TAIL)
        val head = MixLibrary.profile(next, MixPart.HEAD)
        val had = (if (tail != null) 1 else 0) or (if (head != null) 2 else 0)
        plan?.let { if (planKey == key && (planLocked || had == planHad)) return it }
        val p = MixPlanner.plan(dur, tail, head, canStretch = canStretch)
        plan = p
        planKey = key
        planHad = had
        planLocked = false
        return p
    }

    /**
     * One step of Automix. About 15 seconds before the mix, the second player gets the old song ready
     * from the moment it hands over. At the mix point the old song's side of the mix begins; at the
     * handover the second player carries on with the old song while the main player (and the
     * notification, lock screen and app) moves to the new song, tempo-matched and on the beat. Over
     * the mix the volumes and bass follow the plan. Returns true while a mix is under way.
     */
    private fun stepMix(p: Player): Boolean {
        if (mixing) { stepActive(p); return true }
        if (!mixOn(p) || !p.isPlaying) return false
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur < 45_000) return false
        val nextIdx = p.nextMediaItemIndex
        if (nextIdx == C.INDEX_UNSET) return false
        val item = p.currentMediaItem ?: return false
        val next = p.getMediaItemAt(nextIdx)
        val pl = planFor(item.mediaId, next.mediaId, dur, p.playbackParameters.speed == 1f && glideMs == 0L)
        val handoff = pl.outStartMs + pl.inDelayMs
        val pos = p.currentPosition
        if (pos < pl.outStartMs - 15_000) return false

        // Get the old song's ending ready ahead of time (from the cache, so it's quick).
        val tag = "${item.mediaId}@$handoff"
        if (mixPreparedFor != tag) {
            val m = mixerFor() ?: return false
            m.setMediaItem(item)
            m.prepare()
            m.seekTo(handoff)
            m.playWhenReady = false
            m.volume = levelGain
            // Same speed and pitch as the song it carries on.
            m.playbackParameters = p.playbackParameters
            mixerFilter.bass = 1f
            mixerFilter.sweepHz = 0f
            mixPreparedFor = tag
            planLocked = true
            return false
        }
        // The mix point. Only right at it: after a seek past it, the song just ends normally.
        if (pos >= pl.outStartMs - 20 && pos < pl.outStartMs + 1_500) {
            val m = mixer ?: return false
            if (m.playbackState != Player.STATE_READY) return false // not loaded in time: plain change
            mixPlan = pl
            mixNext = nextIdx
            mixOutGain = levelGain
            mixing = true
            handedOff = false
            mixT0 = android.os.SystemClock.elapsedRealtime() - (pos - pl.outStartMs)
            phaseEma = 0.0
            lagChecked = false
            lastSpeedAt = 0
            glideMs = 0
            if (pl.inDelayMs <= 0) handOff(p, pl)
            return true
        }
        return false
    }

    /** The second player takes over the old song; the main player jumps to the new one. */
    private fun handOff(p: Player, pl: MixPlan) {
        val m = mixer ?: return endMix(p)
        m.volume = p.volume
        mixerFilter.bass = mainFilter.bass
        mixerFilter.sweepHz = mainFilter.sweepHz
        m.play()
        handedOff = true
        mixOwnTransition = true
        mixOwnSeek = true
        p.volume = 0f
        mainFilter.sweepHz = 0f
        mainFilter.bass = pl.levels(0f).newBass
        if (pl.locksBeat) setSpeed(p, pl.speed)
        val idx = mixNext.takeIf { it in 0 until p.mediaItemCount } ?: p.nextMediaItemIndex
        if (idx == C.INDEX_UNSET) return endMix(p)
        p.seekTo(idx, pl.inStartMs + if (pl.locksBeat) startLag else 0L)
    }

    private fun stepActive(p: Player) {
        val pl = mixPlan ?: return endMix(p)
        val now = android.os.SystemClock.elapsedRealtime()
        val heard = now - mixT0
        val lv = pl.levels(heard.toFloat() / pl.lengthMs)
        if (!handedOff) {
            // The old song is still on the main player: its first moments of the mix happen here.
            p.volume = lv.oldGain * mixOutGain
            mainFilter.bass = lv.oldBass
            mainFilter.sweepHz = lv.oldSweepHz
            if (heard >= pl.inDelayMs) handOff(p, pl)
            return
        }
        val m = mixer
        m?.volume = lv.oldGain * mixOutGain
        mixerFilter.bass = lv.oldBass
        mixerFilter.sweepHz = lv.oldSweepHz
        p.volume = lv.newGain * levelGain
        mainFilter.bass = lv.newBass
        if (pl.locksBeat && m != null && p.isPlaying && heard > pl.inDelayMs + 400) {
            // Phase lock: nudge the new song a touch faster or slower until its beats sit on the old song's.
            val e = pl.phaseError(m.currentPosition.toDouble(), p.currentPosition.toDouble())
            if (!lagChecked && heard > pl.inDelayMs + 900) {
                lagChecked = true
                startLag = (startLag - (e * pl.oldBeatMs * 0.5).toLong()).coerceIn(0L, 600L)
                mixPrefs.edit().putLong("start_lag", startLag).apply()
            }
            phaseEma = 0.7 * phaseEma + 0.3 * e
            if (now - lastSpeedAt >= 400) {
                setSpeed(p, (pl.speed * (1 - (0.25 * phaseEma).coerceIn(-0.04, 0.04))).toFloat())
                lastSpeedAt = now
            }
        }
        if (heard >= pl.lengthMs + 200 || m == null || m.playbackState == Player.STATE_ENDED || m.playbackState == Player.STATE_IDLE) endMix(p)
    }

    private fun setSpeed(p: Player, s: Float) {
        if (kotlin.math.abs(s - setSpeedTo) < 0.0005f && p.playbackParameters.speed == setSpeedTo) return
        MixTempo.busy = s != 1f
        setSpeedTo = s
        // Only the tempo: the pitch stays wherever Sound settings put it.
        p.playbackParameters = PlaybackParameters(s, p.playbackParameters.pitch)
    }

    /** Eases the new song back to its own tempo after a mix, a step every 0.4 s. */
    private fun glide(p: Player) {
        if (glideMs <= 0) return
        // You changed the speed yourself: leave it be.
        if (kotlin.math.abs(p.playbackParameters.speed - setSpeedTo) > 0.001f) { glideMs = 0; MixTempo.busy = false; return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSpeedAt < 400) return
        lastSpeedAt = now
        val t = ((now - glideAt).toFloat() / glideMs).coerceIn(0f, 1f)
        setSpeed(p, if (t >= 1f) 1f else glideFrom + (1f - glideFrom) * t)
        if (t >= 1f) { glideMs = 0; MixTempo.busy = false }
    }

    /**
     * With Automix on, the next song isn't simply the next one in the list: like a DJ, it's picked
     * from the next few by how well it follows this one (tempo, key, energy), preferring a different
     * artist, with a pinch of chance. Songs you queued yourself, and shuffle, are left alone.
     */
    private fun pickNextForMix(p: Player) {
        if (p.shuffleModeEnabled || p.repeatMode == Player.REPEAT_MODE_ONE) return
        val next = p.currentMediaItemIndex + 1
        val count = p.mediaItemCount
        if (next >= count - 1) return
        if (p.getMediaItemAt(next).mediaId in Queued.ids) return
        val cur = p.currentMediaItem ?: return
        fun artist(i: Int) = p.getMediaItemAt(i).mediaMetadata.artist?.toString() ?: ""
        val window = (next until minOf(count, next + 8)).filter { p.getMediaItemAt(it).mediaId !in Queued.ids }
        if (window.size < 2) return
        val i = MixPlanner.choose(cur.mediaId, cur.mediaMetadata.artist?.toString() ?: "", window.map { p.getMediaItemAt(it).mediaId to artist(it) }, p.currentMediaItemIndex)
        val pick = window.getOrNull(i) ?: return
        if (pick != next) p.moveMediaItem(pick, next)
    }

    /** Asks for this song's ending and the next songs' starts to be listened to: 8 songs ahead on Wi-Fi, 2 on mobile data (those are fetched ahead anyway). */
    private fun listenAhead(p: Player) {
        if (!mixOn(p)) return
        val cur = p.currentMediaItem ?: return
        val jobs = ArrayList<Pair<String, MixPart>>()
        jobs += cur.mediaId to MixPart.TAIL
        val ahead = if (Downloads.onWifi()) 8 else 2
        var i = p.currentMediaItemIndex
        repeat(ahead) {
            i = p.currentTimeline.takeIf { !it.isEmpty }?.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled) ?: C.INDEX_UNSET
            if (i == C.INDEX_UNSET || i >= p.mediaItemCount) return@repeat
            jobs += p.getMediaItemAt(i).mediaId to MixPart.HEAD
        }
        MixLibrary.request(jobs)
    }

    /** Something new is known about a song: if it's this one or one coming up, the order may get better. */
    private fun onMixKnown(p: Player, url: String) {
        if (!mixOn(p) || mixing || planLocked) return
        val idx = p.currentMediaItemIndex
        val near = (idx until minOf(p.mediaItemCount, idx + 9)).any { p.getMediaItemAt(it).mediaId == url }
        if (near) pickNextForMix(p)
    }

    private fun endMix(p: Player) {
        val pl = mixPlan
        mixing = false
        mixPlan = null
        mixPreparedFor = null
        planLocked = false
        mixer?.let { m -> m.pause(); m.stop(); m.clearMediaItems() }
        mixerFilter.bass = 1f
        mixerFilter.sweepHz = 0f
        mainFilter.bass = 1f
        mainFilter.sweepHz = 0f
        p.volume = levelGain
        // Ease back to the song's own tempo over a few bars.
        if (setSpeedTo != 1f) {
            glideFrom = setSpeedTo
            glideAt = android.os.SystemClock.elapsedRealtime()
            glideMs = pl?.glideMs?.coerceAtLeast(2_000) ?: 4_000
        }
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
        MixLibrary.listeners -= mixListener
        MixTempo.busy = false
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
        saver.shutdownNow()
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
