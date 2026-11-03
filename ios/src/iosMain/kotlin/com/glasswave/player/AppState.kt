// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything the screens show apart from the player: search, and which panels are open. */
class AppState(val player: Player) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
        private set
    var searched by mutableStateOf("")
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set

    var fullOpen by mutableStateOf(false)
    var showLyrics by mutableStateOf(false)
    var showQueue by mutableStateOf(false)

    var lyrics by mutableStateOf<List<LyricLine>?>(null)
        private set
    var lyricsFor by mutableStateOf<String?>(null)
        private set

    private val scope = MainScope()
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun typed(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(200)
            suggestions = runCatching { YtMusic.suggestions(text) }.getOrDefault(emptyList())
        }
    }

    fun search(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        suggestions = emptyList()
        suggestJob?.cancel()
        searchJob?.cancel()
        searching = true
        searchError = null
        searchJob = scope.launch {
            runCatching { YtMusic.searchSongs(text) }
                .onSuccess {
                    results = it
                    searched = text
                    Log.i("search \"$text\": ${it.size} songs")
                }
                .onFailure {
                    searchError = "Couldn't search. Check your internet connection."
                    Log.e("search failed: $it")
                }
            searching = false
        }
    }

    fun clearSearch() {
        query = ""
        results = emptyList()
        searched = ""
        suggestions = emptyList()
    }

    /** Looks up timed lyrics for the song playing, once per song. */
    fun loadLyrics(t: Track?) {
        if (t == null || lyricsFor == t.videoId) return
        lyricsFor = t.videoId
        lyrics = null
        scope.launch {
            val found = Lyrics.find(t)
            if (lyricsFor == t.videoId) lyrics = found
            Log.i("lyrics ${t.videoId}: ${found?.size ?: 0} lines")
        }
    }
}
