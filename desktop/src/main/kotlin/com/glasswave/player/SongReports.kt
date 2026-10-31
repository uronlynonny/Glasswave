// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Won't play? Report it": tells nonny dev a song won't play, and tells you back once it's fixed.
 *
 * The report goes to nonny dev's ntfy alerts with a "Mark fixed" button. Tapping it posts the song
 * to the fixes topic. ntfy only keeps messages for 12 hours, so the hourly stats job also copies
 * each fix into fixed.json on the "stats" branch. While you have songs waiting for a fix, the app
 * looks at both every half hour and says when one is fixed. Only the song, the computer type and
 * the app version are sent: nothing about you.
 */
object SongReports {
    private const val TOPIC = "glasswave-nonny-re7mnsuqhuis"
    private const val FIXED_TOPIC = "glasswave-nonny-re7mnsuqhuis-fixed"
    private const val FIXED_FILE = "https://raw.githubusercontent.com/uronlynonny/Glasswave/stats/fixed.json"
    private const val PER_DAY = 10

    // Song address -> {"t": title, "a": artist, "at": when reported, "s": sent yet}.
    private val prefs by lazy { android.content.AppContext.getSharedPreferences("song_reports", 0) }
    private val limits by lazy { android.content.AppContext.getSharedPreferences("song_reports_limit", 0) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    /** Changes whenever the list of reported songs does, so menus show the right label. */
    var version by mutableStateOf(0); private set

    fun isReported(url: String): Boolean = prefs.contains(url)

    /** Reports [t] and returns what to tell the person. Sends in the background. */
    fun report(t: Track): String {
        if (isReported(t.url)) return "Already reported. You'll hear back when it's fixed."
        val day = System.currentTimeMillis() / 86_400_000
        if (limits.getLong("day", -1) != day) limits.edit().clear().putLong("day", day).apply()
        val count = limits.getInt("count", 0)
        if (count >= PER_DAY) return "That's a lot of reports for today. Try again tomorrow."
        limits.edit().putInt("count", count + 1).apply()
        prefs.edit().putString(
            t.url,
            JSONObject().put("t", t.title).put("a", t.artist).put("at", System.currentTimeMillis()).put("s", false).toString(),
        ).apply()
        version++
        // A fresh start for the song in case something stale is in the way.
        YtRepo.forget(t.url)
        scope.launch {
            sendWaiting()
            watch()
        }
        return "Reported to nonny dev. You'll hear back here when it's fixed."
    }

    /** At start-up: picks up any songs still waiting for a fix. */
    fun start() {
        if (prefs.all.isEmpty()) return
        scope.launch {
            delay(15_000)
            watch()
        }
    }

    private data class Waiting(val url: String, val title: String, val artist: String, val at: Long, val sent: Boolean)

    private fun waiting(): List<Waiting> = prefs.all.mapNotNull { (url, v) ->
        val o = runCatching { JSONObject(v as String) }.getOrNull() ?: return@mapNotNull null
        Waiting(url, o.optString("t"), o.optString("a"), o.optLong("at"), o.optBoolean("s"))
    }

    @Synchronized
    private fun watch() {
        if (watching?.isActive == true) return
        watching = scope.launch {
            while (waiting().isNotEmpty()) {
                runCatching { sendWaiting() }
                runCatching { checkFixed() }
                delay(30 * 60_000L)
            }
        }
    }

    /** Sends reports that haven't gone yet (no internet at the time, say). */
    private fun sendWaiting() {
        for (w in waiting().filter { !it.sent }) {
            if (!send(w)) return
            prefs.getString(w.url, null)?.let { JSONObject(it).put("s", true).toString() }
                ?.let { prefs.edit().putString(w.url, it).apply() }
        }
    }

    private fun send(w: Waiting): Boolean {
        val device = "${System.getProperty("os.name")} (${System.getProperty("os.version")}), Glasswave for ${if (Os.isMac) "Mac" else "Windows"} ${Updater.currentVersion()}"
        val msg = "\"${w.title}\"" + (if (w.artist.isNotBlank()) " by ${w.artist}" else "") +
            " won't play for someone. They reported it.\n\n$device\n${w.url}\n\nTap Mark fixed once it plays again: they'll be told."
        val fix = JSONObject().put("u", w.url).put("t", w.title).put("a", w.artist).toString()
        val json = JSONObject()
            .put("topic", TOPIC)
            .put("title", "Song won't play (reported)")
            .put("message", msg)
            .put("tags", JSONArray().put("sos"))
            .put("priority", 4)
            .put(
                "actions",
                JSONArray().put(
                    JSONObject().put("action", "http").put("label", "Mark fixed")
                        .put("url", "https://ntfy.sh/$FIXED_TOPIC").put("method", "POST")
                        .put("body", fix).put("clear", true),
                ),
            )
        if (w.url.startsWith("https://")) json.put("click", w.url)
        return runCatching {
            val req = Request.Builder().url("https://ntfy.sh/")
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            YtRepo.http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun fetch(url: String): String? = runCatching {
        YtRepo.http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute()
            .use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    /** Song address -> when nonny dev marked it fixed (ms). */
    private fun fixes(): Map<String, Long> {
        val out = HashMap<String, Long>()
        fun add(url: String?, at: Long) {
            if (url.isNullOrEmpty()) return
            if (at > (out[url] ?: 0L)) out[url] = at
        }
        // The last 12 hours, straight from ntfy...
        fetch("https://ntfy.sh/$FIXED_TOPIC/json?poll=1&since=12h")?.lineSequence()?.forEach { line ->
            val m = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            if (m.optString("event") != "message") return@forEach
            add(runCatching { JSONObject(m.optString("message")).optString("u") }.getOrNull(), m.optLong("time") * 1000)
        }
        // ...and older ones, kept by the hourly job.
        fetch(FIXED_FILE)?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it.optString("u"), it.optLong("at") * 1000) }
        }
        return out
    }

    /** For checks: looks for fixes right away instead of waiting for the half-hourly look. */
    internal suspend fun checkNow() = checkFixed()

    private suspend fun checkFixed() {
        val fixed = fixes()
        if (fixed.isEmpty()) return
        for (w in waiting()) {
            val at = fixed[w.url] ?: continue
            // Fixed before this report: that was an earlier fix and it broke again, so keep waiting.
            if (at < w.at - 60_000) continue
            prefs.edit().remove(w.url).apply()
            YtRepo.forget(w.url)
            withContext(Dispatchers.Main) {
                version++
                Menus.say("nonny dev fixed \"${w.title}\". It plays now.")
            }
            delay(4_000)
        }
    }
}
