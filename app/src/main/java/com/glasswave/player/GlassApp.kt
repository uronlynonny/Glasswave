// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class GlassApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        LastPlayed.init(this)
        Sound.init(this)
        Accounts.init(this)
        Library.init(this)
        History.init(this)
        Downloads.init(this)
        NotInterested.init(this)
        GenreArt.init(this)
        ArtistArt.init(this)
        LyricsRepo.init(this)
        Loudness.init(this)
        MixLibrary.init(this)
        GlassTier.init(this)
        Stats.init(this)
        Sync.init(this)
        SyncPlatform.init(this)
        Updater.init(this)
        Active.init(this)
        Profile.init(this)
        Profile.refresh()
        YtRepo.initMatches(this)
        val lastPlayed = History.tracks.firstOrNull()?.url
        Thread {
            YtRepo.init()
            // Fix song names saved wrongly by older versions (runs once).
            History.repairOnce(this)
            // Warm up: the first YouTube lookup loads YouTube's player code, which is slow.
            // Doing it now (with the song you played last) makes your first tap play sooner.
            lastPlayed?.let { YtRepo.prefetch(android.net.Uri.parse(it)) }
        // Give songs imported from Spotify playlists their own pictures (runs once).
        History.fixCoversOnce()
        }.start()
    }

    /** Artwork: a small in-memory cache (10% of the app's memory) and a 128 MB cache on disk (covers work offline). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // High performance mode keeps twice as many covers in memory, so scrolling back never reloads them.
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (Settings.highPerformanceRaw(this)) 0.20 else 0.12).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("art")).maxSizeBytes(128L * 1024 * 1024).build() }
            .okHttpClient(YtRepo.http)
            .respectCacheHeaders(false)
            .crossfade(true)
            // GIF wallpapers move.
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
            }
            .build()
}
