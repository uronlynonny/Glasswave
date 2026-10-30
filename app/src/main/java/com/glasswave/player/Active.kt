// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Lets nonny dev count how many people use Glasswave: once a day the app says "in use today",
 * with a random id made when it was first opened (not linked to the person, the phone or any account),
 * the platform and the app version. Nothing else. The hourly stats job counts unique ids per day,
 * week and month. Can be turned off in Settings.
 */
object Active {
    private const val TOPIC = "glasswave-nonny-re7mnsuqhuis-active"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("active", Context.MODE_PRIVATE)
    }

    private fun id(): String = prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
        prefs.edit().putString("id", it).apply()
    }

    private fun today(): String = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()

    /** Checks in if it hasn't yet today (UTC). Safe to call often. */
    fun checkIn() {
        if (!::prefs.isInitialized || !Settings.countMe) return
        val day = today()
        if (prefs.getString("day", null) == day) return
        Thread {
            runCatching {
                val body = JSONObject().put("id", id()).put("p", "android").put("v", Updater.currentVersion()).toString()
                val req = Request.Builder().url("https://ntfy.sh/$TOPIC")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("Title", "active").header("Priority", "1")
                    .build()
                YtRepo.http.newCall(req).execute().use { r -> if (r.isSuccessful) prefs.edit().putString("day", day).apply() }
            }
        }.apply { isDaemon = true }.start()
    }

    /* ---- Live users ---- */

    /**
     * For nonny dev's live users page: while Glasswave is open or playing it says "still here"
     * every 2 minutes, and "bye" when it stops, with the same random id, the platform, the version
     * and whether it's playing. Nothing else. It goes to its own topic, so the daily count above
     * isn't touched. Off along with "count me" in Settings.
     */
    private const val LIVE_TOPIC = "glasswave-nonny-re7mnsuqhuis-live"
    @Volatile private var liveAt = 0L
    @Volatile private var liveOn = false
    @Volatile private var livePlaying = false

    /** Called now and then with whether the app is in use ([on]) and playing. Safe to call often. */
    fun live(on: Boolean, playing: Boolean) {
        if (!::prefs.isInitialized || !Settings.countMe) return
        val now = System.currentTimeMillis()
        when {
            on && (!liveOn || playing != livePlaying || now - liveAt >= 120_000) -> {
                liveOn = true
                livePlaying = playing
                liveAt = now
                sendLive(if (playing) "playing" else "open")
            }
            !on && liveOn -> {
                liveOn = false
                sendLive("bye")
            }
        }
    }

    /** Closing: "bye" right away (waits a moment at most). */
    fun liveBye() {
        if (!liveOn) return
        liveOn = false
        sendLive("bye").join(1500)
    }

    private fun sendLive(state: String): Thread = Thread {
        runCatching {
            val body = JSONObject().put("id", id()).put("p", "android").put("v", Updater.currentVersion()).put("s", state).toString()
            val req = Request.Builder().url("https://ntfy.sh/$LIVE_TOPIC")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Title", "live").header("Priority", "1")
                .build()
            YtRepo.http.newCall(req).execute().close()
        }
    }.apply { isDaemon = true; start() }
}
