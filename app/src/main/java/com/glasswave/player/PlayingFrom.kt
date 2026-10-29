package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * The playlist that was started as a whole: its Play or Shuffle button (or Play / Shuffle in its
 * menu). Its card shows the playing bars. Playing anything else, even one of its songs on its own,
 * clears it.
 */
object PlayingFrom {
    private var prefs: SharedPreferences? = null
    var id by mutableStateOf<String?>(null); private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playing_from", Context.MODE_PRIVATE)
        id = prefs?.getString("id", null)
    }

    fun set(value: String?) {
        if (id == value) return
        id = value
        prefs?.edit { if (value == null) remove("id") else putString("id", value) }
    }
}
