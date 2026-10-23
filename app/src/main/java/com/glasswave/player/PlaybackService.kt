// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

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
import androidx.media3.exoplayer.DefaultLoadControl
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
}

/** Reads a downloaded song from its file; everything else goes through the cache and the internet. */
@OptIn(UnstableApi::class)
private class LocalFirstDataSource(private val online: DataSource, private val file: FileDataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(listener: TransferListener) {
        online.addTransferListener(listener)
        file.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
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
 * Plays audio in the background with a lock-screen / notification player.
 * Queue items carry a YouTube page address (or a Spotify song to match); the real audio
 * address is looked up just before it plays.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val settingsListener: () -> Unit = { session?.player?.let { applySettings(it); maybeAutoplay(it) } }

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
        if (Settings.crossfade == 0) p.volume = 1f
    }

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(YtDownloader.USER_AGENT)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(http, Resolver())
        val cached = CacheDataSource.Factory()
            .setCache(AudioCache.get(this))
            .setUpstreamDataSourceFactory(resolving)
            // Same song at a different quality is stored separately.
            .setCacheKeyFactory { spec -> spec.uri.toString() + "|" + Settings.quality.name }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val sources = DataSource.Factory { LocalFirstDataSource(cached.createDataSource(), FileDataSource()) }

        // Start making sound after a quarter of a second of audio, then download the rest of the
        // song (and the next one) in the background. Keep what's played so seeking back is instant.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 15 * 60_000, 250, 1_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15 * 60_000, true)
            .build()

        val player = ExoPlayer.Builder(this)
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
        Settings.listeners += settingsListener

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                logPlay(player)
                listening = mediaItem
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) SleepTimer.onSongEnded(player)
                prefetchAhead(player)
                maybeAutoplay(player)
                verifyCurrent(player)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                prefetchAhead(player)
                maybeAutoplay(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
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

            override fun onShuffleModeEnabledChanged(shuffle: Boolean) = maybeAutoplay(player)
            override fun onRepeatModeChanged(mode: Int) = maybeAutoplay(player)
        })
        SleepTimer.player = player

        // Let the phone's equalizer attach to our audio.
        audioSessionId = player.audioSessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
        session = MediaSession.Builder(this, player).build()
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
                YtMusicApi.radio(id)
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Autoplay lookup failed", e)
                main.post { if (autoplayFor == current) autoplayFor = null }
                return@execute
            }
            main.post {
                val queued = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()
                val fresh = tracks.filter { it.url !in queued }.take(25).map { it.toMediaItem() }
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
            val fadeMs = Settings.crossfade * 1000L
            if (fadeMs > 0 && p.isPlaying) {
                val pos = p.currentPosition
                val dur = p.duration
                var v = 1f
                if (pos < fadeMs) v = pos.toFloat() / fadeMs
                if (dur > 0 && dur - pos < fadeMs) v = minOf(v, (dur - pos).toFloat() / fadeMs)
                p.volume = v.coerceIn(0f, 1f)
            } else if (p.volume != 1f) {
                p.volume = 1f
            }
            if (p.isPlaying) main.postDelayed(this, 80)
        }
    }

    companion object {
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
        session?.player?.let { logPlay(it) }
        Settings.listeners -= settingsListener
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
