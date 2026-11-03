// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Play this song" requests from other apps: Android's standard play-from-search, the same
 * request Google Assistant sends ("play Essence by Wizkid on Glasswave"). Cue uses it for
 * songs set to play at a certain time. Glasswave searches, picks the best match and plays it.
 */
object PlayRequest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val censored = Regex("clean|edited|censored|radio edit", RegexOption.IGNORE_CASE)

    fun isOne(i: Intent?) = i?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH

    fun handle(context: Context, i: Intent, ps: PlayerState) {
        val title = i.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE).orEmpty().trim()
        val artist = i.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST).orEmpty().trim()
        val query = i.getStringExtra(SearchManager.QUERY).orEmpty().trim()
            .ifBlank { listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" ") }
        // No song named ("play some music"): carry on with whatever was playing.
        if (query.isBlank()) {
            ps.resumeSoon()
            return
        }
        ps.openFull = true
        scope.launch {
            val found = try {
                YtRepo.search(query)
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Play request search failed", e)
                Toast.makeText(context, "Couldn't search for \"$query\". Check the internet connection.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val wantsClean = censored.containsMatchIn(query)
            val pick = found.take(6).firstOrNull { wantsClean || !censored.containsMatchIn(it.title) } ?: found.firstOrNull()
            if (pick == null) {
                Toast.makeText(context, "Couldn't find \"$query\"", Toast.LENGTH_LONG).show()
            } else {
                ps.playSoon(listOf(pick))
            }
        }
    }
}
