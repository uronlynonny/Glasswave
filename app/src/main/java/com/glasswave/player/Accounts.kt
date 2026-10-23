// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Sign-in details for YouTube Music and Spotify, kept in the app's private storage. */
object Accounts {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    }

    /** YouTube Music browser cookies captured after signing in. */
    var ytCookie: String?
        get() = prefs.getString("yt_cookie", null)
        set(v) = prefs.edit { putString("yt_cookie", v) }

    var spotifyClientId: String
        get() = prefs.getString("sp_client", "") ?: ""
        set(v) = prefs.edit { putString("sp_client", v.trim()) }

    var spotifyRefresh: String?
        get() = prefs.getString("sp_refresh", null)
        set(v) = prefs.edit { putString("sp_refresh", v) }

    var spotifyAccess: String?
        get() = prefs.getString("sp_access", null)
        set(v) = prefs.edit { putString("sp_access", v) }

    var spotifyExpiresAt: Long
        get() = prefs.getLong("sp_expires", 0)
        set(v) = prefs.edit { putLong("sp_expires", v) }

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(v) = prefs.edit { putLong("last_sync", v) }

    var recentSearches: List<String>
        get() = prefs.getString("recent", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit { putString("recent", v.joinToString("\n")) }

    val ytSignedIn get() = !ytCookie.isNullOrEmpty()
    val spotifySignedIn get() = !spotifyRefresh.isNullOrEmpty()

    fun signOutSpotify() = prefs.edit {
        remove("sp_refresh")
        remove("sp_access")
        remove("sp_expires")
    }
}
