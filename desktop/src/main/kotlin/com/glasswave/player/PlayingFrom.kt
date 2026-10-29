package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The playlist that was started as a whole: its Play or Shuffle button (or Play / Shuffle in its
 * menu). Its card shows the playing bars. Playing anything else, even one of its songs on its own,
 * clears it.
 */
object PlayingFrom {
    var id by mutableStateOf<String?>(null); private set

    fun set(value: String?) { id = value }
}
