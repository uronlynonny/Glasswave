package com.glasswave.player

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Everything that touches YouTube lives in this one file.
 * If YouTube changes and playback breaks, this is the only place that should need work
 * (usually just a newer NewPipeExtractor version in app/build.gradle.kts).
 */
object YtRepo {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ready = false

    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val URL_LIFETIME_MS = 3 * 60 * 60 * 1000L

    @Synchronized
    fun init() {
        if (!ready) {
            NewPipe.init(YtDownloader(client))
            ready = true
        }
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        init()
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            "",
        )
        val info = SearchInfo.getInfo(yt, handler)
        info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                Track(
                    url = it.url,
                    title = it.name,
                    artist = it.uploaderName ?: "",
                    seconds = it.duration,
                    thumb = it.thumbnails.lastOrNull()?.url,
                )
            }
            .distinctBy { it.url }
    }

    /** Blocking. Called by the player on its loading thread, never on the main thread. */
    @Throws(IOException::class)
    fun streamUrl(videoUrl: String): String {
        init()
        val now = System.currentTimeMillis()
        urlCache[videoUrl]?.let { (u, at) -> if (now - at < URL_LIFETIME_MS) return u }

        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("YouTube lookup failed", e)
        }
        val best = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .maxByOrNull { it.averageBitrate }
            ?: throw IOException("No playable audio found")
        val u = best.content
        urlCache[videoUrl] = u to now
        return u
    }
}
