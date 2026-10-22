package com.glasswave.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val url: String,
    val title: String,
    val artist: String,
    val seconds: Long,
    val thumb: String?,
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

fun fmtTime(totalSeconds: Long): String {
    val s = if (totalSeconds < 0) 0 else totalSeconds
    return "%d:%02d".format(s / 60, s % 60)
}
