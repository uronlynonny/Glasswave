// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The computer's song cache: songs you listen to are kept on disk, so they play again instantly
 * and still play with no internet. Nothing extra is downloaded to fill it: the player already
 * fetches each song whole ahead of the music ([AheadStream]), and that file is kept instead of
 * thrown away. Capped by the cache size in Settings, dropping the songs played longest ago first.
 */
object SongCache {
    private lateinit var dir: File
    private lateinit var app: Context
    private val index get() = File(dir, "index.json")

    private class Entry(val track: Track, val file: File, @Volatile var used: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val lock = Object()

    fun init(context: Context) {
        app = context.applicationContext
        dir = File(context.cacheDir, "songs").apply { mkdirs() }
        runCatching {
            val arr = org.json.JSONArray(index.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(dir, o.getString("f"))
                if (!f.exists()) continue
                val t = Track(o.getString("u"), o.optString("t"), o.optString("a"), o.optLong("s"), o.optString("i").ifEmpty { null })
                entries[t.url] = Entry(t, f, o.optLong("used"))
            }
        }
        // Songs that were still being saved when the app closed.
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private val ready get() = this::dir.isInitialized

    /** The saved file for a song, if it's in the cache. */
    fun fileFor(url: String): File? {
        if (!ready) return null
        val e = entries[url] ?: return null
        if (!e.file.exists()) { entries.remove(url); return null }
        e.used = System.currentTimeMillis()
        return e.file
    }

    /** Every cached song, most recently played first. */
    val tracks: List<Track> get() = entries.values.sortedByDescending { it.used }.map { it.track }

    fun sizeBytes(): Long = if (!ready) 0 else entries.values.sumOf { it.file.length() }

    fun clear() {
        if (!ready) return
        synchronized(lock) {
            entries.values.forEach { it.file.delete() }
            entries.clear()
            save()
        }
    }

    /** Keeps a song that has fully arrived ([file] is moved into the cache). False if it isn't wanted. */
    fun keep(t: Track, file: File): Boolean {
        if (!ready || t.url.isEmpty() || t.title.isBlank()) return false
        if (entries.containsKey(t.url) || Downloads.fileFor(t.url) != null) return false
        val size = file.length()
        if (size <= 0 || Settings.cacheBytes(app) < size * 4) return false
        synchronized(lock) {
            val out = File(dir, "c" + Integer.toHexString(t.url.hashCode()) + "_" + System.currentTimeMillis() + ".audio")
            if (!file.renameTo(out)) return false
            entries[t.url] = Entry(t, out, System.currentTimeMillis())
            trim()
            save()
        }
        return true
    }

    /** Drops the songs played longest ago until the cache fits its size. */
    private fun trim() {
        val limit = Settings.cacheBytes(app)
        var total = entries.values.sumOf { it.file.length() }
        for (e in entries.values.sortedBy { it.used }) {
            if (total <= limit) break
            total -= e.file.length()
            e.file.delete()
            entries.remove(e.track.url)
        }
    }

    private fun save() {
        val arr = org.json.JSONArray()
        for (e in entries.values) {
            arr.put(
                org.json.JSONObject().put("u", e.track.url).put("t", e.track.title).put("a", e.track.artist).put("s", e.track.seconds)
                    .put("i", e.track.thumb ?: "").put("f", e.file.name).put("used", e.used),
            )
        }
        runCatching { index.writeText(arr.toString()) }
    }

    /** Saves when songs were last played (now and then, and when the app closes). */
    fun flush() {
        if (ready) synchronized(lock) { save() }
    }
}
