// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Tells nonny dev when a song won't play for someone, so it can be fixed. Sends the song, the error,
 * the phone model and the app version: nothing about the person. It goes to the same ntfy alerts as
 * the website's, and the hourly stats job keeps a list. At most once per song per day and 10 a day.
 * Can be turned off in Settings.
 */
object Problems {
    private const val TOPIC = "glasswave-nonny-re7mnsuqhuis"

    /** Connected to a network that actually reaches the internet. */
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    fun songFailed(context: Context, item: MediaItem, error: PlaybackException, tries: Int) {
        if (!Settings.reportProblems) return
        // Your own music files are private: never reported.
        if (PhoneMusic.isPhoneSong(item.mediaId)) return
        val url = item.mediaId
        val prefs = context.getSharedPreferences("problems", Context.MODE_PRIVATE)
        val day = System.currentTimeMillis() / 86_400_000
        if (prefs.getLong("day", -1) != day) prefs.edit().clear().putLong("day", day).apply()
        val count = prefs.getInt("count", 0)
        if (prefs.getBoolean(url, false) || count >= 10) return
        prefs.edit().putBoolean(url, true).putInt("count", count + 1).apply()

        val title = item.mediaMetadata.title?.toString() ?: "Unknown song"
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val why = buildString {
            append(error.errorCodeName)
            var c: Throwable? = error.cause
            var depth = 0
            while (c != null && depth < 4) {
                append("\n").append(c.javaClass.simpleName)
                c.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(200)) }
                c = c.cause
                depth++
            }
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val body = "\"$title\"" + (if (artist.isNotEmpty()) " by $artist" else "") + " wouldn't play (tried ${tries + 1} times).\n" +
            "$why\n\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, Glasswave $version\n$url"
        Thread {
            runCatching {
                val req = Request.Builder().url("https://ntfy.sh/$TOPIC")
                    .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .header("Title", "Song failed").header("Tags", "warning").header("Priority", "3")
                    .apply { if (url.startsWith("https://")) header("Click", url) }
                    .build()
                YtRepo.http.newCall(req).execute().close()
            }
        }.start()
    }
}
