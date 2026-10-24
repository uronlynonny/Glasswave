// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Volume leveling. YouTube measures how loud every song is compared with its reference level
 * ("loudnessDb": +3.4 means 3.4 dB louder). Glasswave turns each song by that much, so loud and
 * quiet songs come out the same, like Spotify's "Normalize volume". Values are remembered.
 */
object Loudness {
    private var prefs: SharedPreferences? = null
    private val JSON = "application/json".toMediaType()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("loudness", Context.MODE_PRIVATE)
    }

    /** Remembered loudness for a YouTube video id, or null if not looked up yet. */
    fun cached(videoId: String): Float? = prefs?.takeIf { it.contains(videoId) }?.getFloat(videoId, 0f)

    /** Looks it up (blocking). Null when YouTube didn't say. */
    fun fetch(videoId: String): Float? {
        cached(videoId)?.let { return it }
        val body = JSONObject()
            .put("videoId", videoId)
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject().put("clientName", "IOS").put("clientVersion", "20.10.4")
                        .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
                        .put("osName", "iPhone").put("osVersion", "18.3.2.22D82").put("hl", "en"),
                ),
            )
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
            .build()
        val j = YtRepo.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: return null) else return null }
        // Per stream first (the most exact), then the song's overall figure.
        val formats = j.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
        var db: Double? = null
        if (formats != null) for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optString("mimeType").startsWith("audio/") && f.has("loudnessDb")) { db = f.optDouble("loudnessDb"); break }
        }
        if (db == null) {
            val p = j.optJSONObject("playerConfig")?.optJSONObject("audioConfig")?.optDouble("perceptualLoudnessDb", Double.NaN)
            if (p != null && !p.isNaN()) db = p + 14.0
        }
        val v = db?.takeIf { !it.isNaN() }?.toFloat() ?: return null
        prefs?.edit()?.putFloat(videoId, v)?.apply()
        return v
    }

    /** How much to turn a song by, in dB: down by up to 12 dB, up by up to 6 dB. */
    fun gainDb(loudnessDb: Float): Float = (-loudnessDb).coerceIn(-12f, 6f)
}
