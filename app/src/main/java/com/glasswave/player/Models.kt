// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * One song. [url] is what the player loads:
 * - a YouTube watch page (looked up just before it plays), or
 * - a "gwmatch://" address for Spotify songs, which is matched to YouTube Music just before it plays.
 */
data class Track(
    val url: String,
    val title: String,
    val artist: String,
    val seconds: Long,
    val thumb: String?,
)

enum class Source(val label: String) { YT("YouTube Music"), SPOTIFY("Spotify"), LOCAL("Glasswave") }

/** A playlist's summary. Its songs are stored separately and only loaded when you open it. */
data class PlaylistInfo(
    val id: String,
    val name: String,
    val source: Source,
    val thumb: String?,
    val count: Int,
)

/** Anything YouTube Music can show in a list: a song, album, artist or playlist. */
sealed interface MusicItem {
    val title: String
    val subtitle: String
    val thumb: String?
}

/** [explicit]: YouTube Music marks it Explicit (the uncensored version). */
data class SongItem(val track: Track, override val subtitle: String, val explicit: Boolean = false) : MusicItem {
    override val title get() = track.title
    override val thumb get() = track.thumb
}

data class AlbumItem(val browseId: String, override val title: String, override val subtitle: String, override val thumb: String?, val explicit: Boolean = false) : MusicItem
data class ArtistItem(val browseId: String, override val title: String, override val subtitle: String, override val thumb: String?) : MusicItem
data class PlaylistItem(val browseId: String, override val title: String, override val subtitle: String, override val thumb: String?) : MusicItem

/** A Moods & genres page, like "Chill" or "Hip-Hop". [color] is its ARGB stripe colour. */
data class CategoryItem(val browseId: String, val params: String?, override val title: String, val color: Long) : MusicItem {
    override val subtitle get() = "Mood & genre"
    override val thumb: String? get() = null
}

/** A titled row of items, like "Quick picks" or "Albums". */
data class Section(val title: String, val items: List<MusicItem>)

/** An album, artist or playlist page. */
data class Page(
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val tracks: List<Track>,
    val sections: List<Section>,
)

fun Track.toMediaItem(): MediaItem {
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .apply { if (thumb != null) setArtworkUri(Uri.parse(thumb)) }
        .build()
    return MediaItem.Builder()
        .setMediaId(url)
        .setUri(url)
        .setMediaMetadata(meta)
        .build()
}

/** Address for a Spotify song: it gets matched to the best YouTube Music result when it plays. */
fun matchUrl(title: String, artist: String, seconds: Long): String =
    Uri.Builder()
        .scheme("gwmatch")
        .authority("t")
        .appendQueryParameter("q", "$title $artist".trim())
        .appendQueryParameter("d", seconds.toString())
        .build()
        .toString()

fun ytWatchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

/** A sharper version of a YouTube Music cover, for the big player. */
fun hiRes(url: String?): String? =
    // Data saver: a smaller picture (about a quarter of the data).
    url?.replace(Regex("=w\\d+-h\\d+"), if (Settings.savingData) "=w360-h360" else "=w720-h720")

fun fmtTime(totalSeconds: Long): String {
    val s = if (totalSeconds < 0) 0 else totalSeconds
    return "%d:%02d".format(s / 60, s % 60)
}

/** "3:45" or "1:02:03" to seconds. */
fun parseDuration(text: String?): Long {
    if (text.isNullOrBlank()) return 0
    return text.trim().split(":").fold(0L) { acc, part -> acc * 60 + (part.trim().toLongOrNull() ?: return 0) }
}
