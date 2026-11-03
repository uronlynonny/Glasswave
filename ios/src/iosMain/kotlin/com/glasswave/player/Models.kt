// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/** A song. [videoId] is its YouTube id. */
data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val seconds: Long,
    val thumb: String?,
    val explicit: Boolean = false,
)

/** A sharper version of a YouTube Music cover, for the big player. */
fun hiRes(url: String?): String? = url?.replace(Regex("=w\\d+-h\\d+"), "=w720-h720")

fun fmtTime(totalSeconds: Long): String {
    val s = if (totalSeconds < 0) 0 else totalSeconds
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** "3:45" or "1:02:03" to seconds. */
fun parseDuration(text: String?): Long {
    if (text.isNullOrBlank()) return 0
    return text.trim().split(":").fold(0L) { acc, part -> acc * 60 + (part.trim().toLongOrNull() ?: return 0) }
}
