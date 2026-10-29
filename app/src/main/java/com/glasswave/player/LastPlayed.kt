// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Remember what was playing": the queue, the song and the spot in it are saved on the phone,
 * so the next time Glasswave opens the same song is waiting, paused, ready to carry on.
 */
object LastPlayed {
    /** At most this many songs are kept (some before the current song, the rest after). */
    private const val MAX = 200
    private const val BEFORE = 50
    /** Left alone longer than this (nothing playing), it's forgotten: Glasswave opens fresh. */
    const val FORGET_AFTER_MS = 60 * 60 * 1000L

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("last_played", Context.MODE_PRIVATE)
    }

    /** Saves the queue in the order it will play (shuffle included). */
    fun save(p: Player) {
        if (!Settings.rememberPlaying) return
        val tl = p.currentTimeline
        if (tl.isEmpty || p.mediaItemCount == 0) return clear()
        val shuffle = p.shuffleModeEnabled
        val order = ArrayList<Int>(p.mediaItemCount)
        var i = tl.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && order.size < p.mediaItemCount) {
            order += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        val at = order.indexOf(p.currentMediaItemIndex).coerceAtLeast(0)
        val from = (at - BEFORE).coerceAtLeast(0)
        val keep = order.subList(from, minOf(order.size, from + MAX))
        val arr = JSONArray()
        var current = 0
        for (idx in keep) {
            val item = p.getMediaItemAt(idx)
            if (item.mediaId.isEmpty()) continue
            if (idx == p.currentMediaItemIndex) current = arr.length()
            val md = item.mediaMetadata
            arr.put(
                JSONObject()
                    .put("u", item.mediaId)
                    .put("t", md.title?.toString() ?: "")
                    .put("a", md.artist?.toString() ?: "")
                    .put("i", md.artworkUri?.toString() ?: ""),
            )
        }
        // A finished queue starts its last song from the top next time.
        val pos = if (p.playbackState == Player.STATE_ENDED) 0L else p.currentPosition.coerceAtLeast(0)
        prefs.edit {
            putString("tracks", arr.toString())
            putInt("index", current)
            putLong("position", pos)
            putBoolean("shuffle", shuffle)
            putInt("repeat", p.repeatMode)
            putLong("at", System.currentTimeMillis())
        }
    }

    fun clear() {
        prefs.edit { clear() }
        PlayingFrom.set(null)
    }

    /** Puts the saved queue back into the player, paused. Does nothing if the setting is off or nothing was saved. */
    @OptIn(UnstableApi::class)
    fun restore(p: ExoPlayer) {
        if (!Settings.rememberPlaying || p.mediaItemCount > 0) return
        try {
            val sp = prefs
            // Not used for over an hour: start fresh instead.
            if (System.currentTimeMillis() - sp.getLong("at", 0L) > FORGET_AFTER_MS) return clear()
            val arr = JSONArray(sp.getString("tracks", null) ?: return)
            val tracks = (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                Track(o.getString("u"), o.optString("t"), o.optString("a"), 0, o.optString("i").ifEmpty { null })
            }
            if (tracks.isEmpty()) return
            val index = sp.getInt("index", 0).coerceIn(0, tracks.lastIndex)
            p.setMediaItems(tracks.map { it.toMediaItem() }, index, sp.getLong("position", 0))
            p.repeatMode = sp.getInt("repeat", Player.REPEAT_MODE_OFF)
            if (sp.getBoolean("shuffle", false)) {
                // The songs were saved in their shuffled order, so keep playing them in that order.
                p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(IntArray(tracks.size) { it }, System.nanoTime()))
                p.shuffleModeEnabled = true
            }
            p.playWhenReady = false
        } catch (e: Exception) {
            android.util.Log.w("Glasswave", "Couldn't bring back what was playing", e)
        }
    }
}
