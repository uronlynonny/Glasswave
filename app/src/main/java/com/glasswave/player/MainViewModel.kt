package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        loading = true
        error = null
        job = viewModelScope.launch {
            try {
                val found = YtRepo.search(q)
                results = found
                error = if (found.isEmpty()) "Nothing found for \"$q\"" else null
                loading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Search failed. Check your internet. If it keeps happening, YouTube may have changed and the app needs an update."
                loading = false
            }
        }
    }
}
