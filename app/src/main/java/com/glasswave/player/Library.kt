// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved playlists, kept as small JSON files in the app's private storage.
 * The list of playlists stays in memory; each playlist's songs are only read from disk when opened,
 * so a big library doesn't sit in RAM.
 *
 * Playlists come from YouTube Music and Spotify (synced, read-only here) or are made in the app
 * ([Source.LOCAL]), including "Liked songs". Any playlist can be pinned to the top of the Library.
 */
object Library {
    const val LIKED_ID = "local_liked"

    private lateinit var dir: File
    private val _playlists = MutableStateFlow<List<PlaylistInfo>>(emptyList())
    val playlists: StateFlow<List<PlaylistInfo>> = _playlists

    /** Goes up whenever any playlist's songs change, so open pages reload. */
    var version by mutableIntStateOf(0)
        private set

    /** Pinned playlist ids, in the order they were pinned. */
    val pins = mutableStateListOf<String>()

    /** Addresses of liked songs, for the heart in menus. */
    var liked by mutableStateOf<Set<String>>(emptySet())
        private set

    fun init(context: Context) {
        dir = File(context.filesDir, "library").apply { mkdirs() }
        _playlists.value = readIndex()
        runCatching {
            val arr = JSONArray(pinsFile().readText())
            for (i in 0 until arr.length()) pins += arr.getString(i)
        }
        liked = readTracks(LIKED_ID).map { it.url }.toSet()
    }

    private fun indexFile() = File(dir, "index.json")
    private fun pinsFile() = File(dir, "pins.json")
    private fun tracksFile(id: String) = File(dir, "pl_" + id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun readIndex(): List<PlaylistInfo> = try {
        val f = indexFile()
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val src = runCatching { Source.valueOf(o.getString("source")) }.getOrNull() ?: return@mapNotNull null
                PlaylistInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    source = src,
                    thumb = o.optString("thumb").ifEmpty { null },
                    count = o.optInt("count"),
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("Glasswave", "Couldn't read library", e)
        emptyList()
    }

    private fun writeIndex(list: List<PlaylistInfo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("source", it.source.name)
                    .put("thumb", it.thumb ?: "")
                    .put("count", it.count),
            )
        }
        writeAtomic(indexFile(), arr.toString())
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun readTracks(id: String): List<Track> = try {
        val f = tracksFile(id)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Track(
                    url = o.getString("u"),
                    title = o.optString("t"),
                    artist = o.optString("a"),
                    seconds = o.optLong("s"),
                    thumb = o.optString("i").ifEmpty { null },
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("Glasswave", "Couldn't read playlist $id", e)
        emptyList()
    }

    private fun writeTracks(id: String, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach {
            arr.put(
                JSONObject()
                    .put("u", it.url)
                    .put("t", it.title)
                    .put("a", it.artist)
                    .put("s", it.seconds)
                    .put("i", it.thumb ?: ""),
            )
        }
        writeAtomic(tracksFile(id), arr.toString())
    }

    suspend fun tracks(id: String): List<Track> = withContext(Dispatchers.IO) { readTracks(id) }

    fun info(id: String): PlaylistInfo? = _playlists.value.firstOrNull { it.id == id }

    /**
     * Replaces every playlist from [source] with [fresh]. Playlists that were removed on the
     * service are removed here too. Other sources are left alone.
     */
    @Synchronized
    fun replaceSource(source: Source, fresh: List<Pair<PlaylistInfo, List<Track>>>) {
        val keep = _playlists.value.filter { it.source != source }
        val old = _playlists.value.filter { it.source == source }
        fresh.forEach { (info, tracks) -> writeTracks(info.id, tracks) }
        val freshIds = fresh.map { it.first.id }.toSet()
        old.filter { it.id !in freshIds }.forEach { tracksFile(it.id).delete() }
        val all = keep + fresh.map { it.first }
        writeIndex(all)
        _playlists.value = all
        version++
    }

    @Synchronized
    fun clearSource(source: Source) = replaceSource(source, emptyList())

    /* ---------------- Playlists made in the app ---------------- */

    /** Your own playlists (not the synced ones), Liked songs first. */
    fun ownPlaylists(): List<PlaylistInfo> =
        _playlists.value.filter { it.source == Source.LOCAL }.sortedBy { if (it.id == LIKED_ID) 0 else 1 }

    @Synchronized
    fun createPlaylist(name: String, first: Track? = null): PlaylistInfo {
        val p = PlaylistInfo("local_" + System.currentTimeMillis(), name.trim().ifEmpty { "My playlist" }, Source.LOCAL, first?.thumb, 0)
        writeTracks(p.id, emptyList())
        val all = listOf(p) + _playlists.value
        writeIndex(all)
        _playlists.value = all
        if (first != null) add(p.id, first)
        return info(p.id) ?: p
    }

    /** A new playlist of yours with songs already in it (e.g. imported from a Spotify link). */
    @Synchronized
    fun createWith(name: String, cover: String?, tracks: List<Track>): PlaylistInfo {
        val p = PlaylistInfo("local_" + System.currentTimeMillis(), name.trim().ifEmpty { "Imported" }, Source.LOCAL, cover ?: tracks.firstOrNull()?.thumb, tracks.size)
        writeTracks(p.id, tracks)
        val all = listOf(p) + _playlists.value
        writeIndex(all)
        _playlists.value = all
        version++
        return p
    }

    /** Adds a song to one of your playlists (no duplicates). Returns false if it was already there. */
    @Synchronized
    fun add(id: String, t: Track): Boolean {
        if (id == LIKED_ID && info(LIKED_ID) == null) {
            val p = PlaylistInfo(LIKED_ID, "Liked songs", Source.LOCAL, null, 0)
            writeIndex(listOf(p) + _playlists.value)
            _playlists.value = listOf(p) + _playlists.value
        }
        val list = readTracks(id)
        if (list.any { it.url == t.url }) return false
        // Newest first, like Spotify's Liked Songs.
        val now = listOf(t) + list
        writeTracks(id, now)
        updateInfo(id, now)
        return true
    }

    @Synchronized
    fun remove(id: String, url: String) {
        val now = readTracks(id).filter { it.url != url }
        writeTracks(id, now)
        updateInfo(id, now)
    }

    private fun updateInfo(id: String, tracks: List<Track>) {
        val all = _playlists.value.map { if (it.id == id) it.copy(count = tracks.size, thumb = tracks.firstOrNull()?.thumb) else it }
        writeIndex(all)
        _playlists.value = all
        if (id == LIKED_ID) liked = tracks.map { it.url }.toSet()
        version++
    }

    fun isLiked(url: String) = url in liked

    fun toggleLike(t: Track) {
        if (isLiked(t.url)) remove(LIKED_ID, t.url) else add(LIKED_ID, t)
    }

    @Synchronized
    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        val all = _playlists.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        writeIndex(all)
        _playlists.value = all
    }

    @Synchronized
    fun delete(id: String) {
        tracksFile(id).delete()
        val all = _playlists.value.filter { it.id != id }
        writeIndex(all)
        _playlists.value = all
        if (id == LIKED_ID) liked = emptySet()
        if (pins.remove(id)) savePins()
        version++
    }

    /* ---------------- Pins ---------------- */

    fun isPinned(id: String) = id in pins

    fun togglePin(id: String) {
        if (!pins.remove(id)) pins.add(id)
        savePins()
    }

    private fun savePins() {
        runCatching { writeAtomic(pinsFile(), JSONArray(pins.toList()).toString()) }
    }
}
