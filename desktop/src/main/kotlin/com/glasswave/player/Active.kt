// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Lets nonny dev count how many people use Glasswave: once a day the app says "in use today",
 * with a random id made when it was first opened (not linked to the person, the PC or any account),
 * the platform and the app version. Nothing else. The hourly stats job counts unique ids per day,
 * week and month. Can be turned off in Settings.
 */
object Active {
    private const val TOPIC = "glasswave-nonny-re7mnsuqhuis-active"
    private val prefs by lazy { android.content.AppContext.getSharedPreferences("active", 0) }

    private fun id(): String = prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
        prefs.edit().putString("id", it).apply()
    }

    private fun today(): String = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()

    /** Checks in if it hasn't yet today (UTC). Safe to call often. */
    fun checkIn() {
        if (!Settings.countMe || System.getProperty("gw.noCheckIn") != null) return
        val day = today()
        if (prefs.getString("day", null) == day) return
        Thread {
            runCatching {
                val body = JSONObject().put("id", id()).put("p", "windows").put("v", Updater.currentVersion()).toString()
                val req = Request.Builder().url("https://ntfy.sh/$TOPIC")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("Title", "active").header("Priority", "1")
                    .build()
                YtRepo.http.newCall(req).execute().use { r -> if (r.isSuccessful) prefs.edit().putString("day", day).apply() }
            }
        }.apply { isDaemon = true }.start()
    }
}
