// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/** The last 60 songs you played, newest first. Shown as "Recently played" on Home. */
object History {
    private lateinit var prefs: SharedPreferences
    val tracks = mutableStateListOf<Track>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(prefs.getString("tracks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tracks += Track(o.getString("u"), o.optString("t"), o.optString("a"), o.optLong("s"), o.optString("i").ifEmpty { null })
            }
        }
    }

    fun add(t: Track) {
        if (!Settings.keepHistory || t.title.isBlank()) return
        tracks.removeAll { it.url == t.url }
        tracks.add(0, t)
        while (tracks.size > 60) tracks.removeAt(tracks.lastIndex)
        save()
    }

    fun clear() {
        tracks.clear()
        save()
    }

    /** Takes a song out (e.g. an entry that pointed at the wrong song). */
    fun removeUrl(url: String) {
        if (tracks.removeAll { it.url == url }) save()
    }

    /**
     * One-time repair for history saved by older versions, where a song's address could be stored
     * under the name and cover of the song before it. Each YouTube song's real details are fetched
     * again; Spotify songs whose saved name doesn't match their own search text are dropped.
     * Fixed details are also written into your own playlists and the stats log.
     */
    fun repairOnce(context: Context) {
        if (prefs.getBoolean("repaired_v1", false)) return
        Thread {
            val fixed = HashMap<String, Track>()
            val drop = HashSet<String>()
            var failed = 0
            for (t in tracks.toList()) {
                val uri = android.net.Uri.parse(t.url)
                if (uri.scheme == "gwmatch") {
                    val q = uri.getQueryParameter("q") ?: continue
                    if (!q.startsWith(t.title, ignoreCase = true)) drop += t.url
                    continue
                }
                val id = uri.getQueryParameter("v") ?: continue
                val real = try {
                    YtMusicApi.trackInfo(id)
                } catch (e: Exception) {
                    failed++
                    null
                } ?: continue
                if (real.title != t.title || real.artist != t.artist) fixed[t.url] = real
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                for (i in tracks.indices.reversed()) {
                    val t = tracks[i]
                    when {
                        t.url in drop -> tracks.removeAt(i)
                        fixed[t.url] != null -> tracks[i] = fixed.getValue(t.url).copy(seconds = t.seconds)
                    }
                }
                save()
                Library.repairTracks(fixed, drop)
                Stats.repairTracks(fixed, drop)
                // Offline or YouTube didn't answer: try again next launch.
                if (failed == 0) prefs.edit { putBoolean("repaired_v1", true) }
            }
        }.start()
    }

    private fun save() {
        val arr = JSONArray()
        tracks.forEach {
            arr.put(JSONObject().put("u", it.url).put("t", it.title).put("a", it.artist).put("s", it.seconds).put("i", it.thumb ?: ""))
        }
        prefs.edit { putString("tracks", arr.toString()) }
    }
}

/** Pauses the music after a set time, or at the end of the current song. Runs inside the player service. */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    internal var player: Player? = null

    /** When the timer ends (milliseconds since 1970), or 0 when off. */
    var endsAt by mutableLongStateOf(0L); private set
    var endOfSong by mutableStateOf(false); private set

    private val fire = Runnable {
        player?.pause()
        endsAt = 0
    }

    fun start(minutes: Int) {
        cancel()
        endsAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(fire, minutes * 60_000L)
    }

    fun startEndOfSong() {
        cancel()
        endOfSong = true
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        endsAt = 0
        endOfSong = false
    }

    /** Called by the service when a song finishes on its own. */
    internal fun onSongEnded(p: Player) {
        if (endOfSong) {
            p.pause()
            endOfSong = false
        }
    }

    val active get() = endsAt > 0 || endOfSong
}
