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
}
