// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Tells nonny dev when a song won't play for someone, so it can be fixed. Sends the song, the error,
 * "Windows" with its version and the app version: nothing about the person. It goes to the same ntfy
 * alerts as the phone app's, and the hourly stats job keeps a list. At most once per song per day
 * and 10 a day. Can be turned off in Settings.
 */
object Problems {
    private const val TOPIC = "glasswave-nonny-re7mnsuqhuis"

    /** Whether the internet can be reached (YouTube Music's address can be looked up). */
    fun online(): Boolean = runCatching { java.net.InetAddress.getByName("music.youtube.com"); true }.getOrDefault(false)

    fun songFailed(t: Track, error: Throwable, tries: Int) {
        if (!Settings.reportProblems) return
        // Your own music files are private: never reported.
        if (PcMusic.isPcSong(t.url)) return
        val url = t.url
        val prefs = android.content.AppContext.getSharedPreferences("problems", 0)
        val day = System.currentTimeMillis() / 86_400_000
        if (prefs.getLong("day", -1) != day) prefs.edit().clear().putLong("day", day).apply()
        val count = prefs.getInt("count", 0)
        if (prefs.getBoolean(url, false) || count >= 10) return
        prefs.edit().putBoolean(url, true).putInt("count", count + 1).apply()

        val why = buildString {
            var c: Throwable? = error
            var depth = 0
            while (c != null && depth < 5) {
                if (depth > 0) append("\n")
                append(c.javaClass.simpleName)
                c.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(200)) }
                c = c.cause
                depth++
            }
        }
        val os = "${System.getProperty("os.name")} (${System.getProperty("os.version")})"
        val body = "\"${t.title.ifBlank { "Unknown song" }}\"" + (if (t.artist.isNotEmpty()) " by ${t.artist}" else "") +
            " wouldn't play (tried ${tries + 1} times).\n$why\n\n$os, Glasswave for Windows ${Updater.currentVersion()}\n$url"
        Thread {
            runCatching {
                val req = Request.Builder().url("https://ntfy.sh/$TOPIC")
                    .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .header("Title", "Song failed").header("Tags", "warning").header("Priority", "3")
                    .apply { if (url.startsWith("https://")) header("Click", url) }
                    .build()
                YtRepo.http.newCall(req).execute().close()
            }
        }.apply { isDaemon = true }.start()
    }
}
