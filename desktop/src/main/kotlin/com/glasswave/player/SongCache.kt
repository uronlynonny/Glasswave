// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request

/**
 * The computer's song cache: songs you listen to are kept on disk, so they play again instantly
 * and still play with no internet. Nothing extra is downloaded to fill it: the sound the player
 * already fetched is saved as it plays, and only the small bits it skipped (like the last seconds
 * Automix mixed out of) are fetched afterwards. Capped by the cache size in Settings, dropping the
 * songs played longest ago first.
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

    /** Starts saving a song as it streams from [stream] ([length] bytes). Null when it's already kept, or the cache is off. */
    fun recorder(t: Track, stream: String, length: Long): Recorder? {
        if (!ready || length <= 0 || t.url.isEmpty() || t.title.isBlank()) return null
        if (entries.containsKey(t.url) || Downloads.fileFor(t.url) != null) return null
        if (Settings.cacheBytes(app) < length * 4) return null
        return runCatching { Recorder(t, stream, length) }.getOrNull()
    }

    class Recorder internal constructor(private val track: Track, private val stream: String, private val length: Long) {
        private val part = File(dir, "c" + Integer.toHexString(track.url.hashCode()) + "_" + System.nanoTime() + ".part")
        private val file = RandomAccessFile(part, "rw").apply { setLength(length) }
        /** Byte ranges saved so far: start -> end (exclusive), never overlapping. */
        private val have = TreeMap<Long, Long>()
        private var closed = false

        @Synchronized
        fun write(pos: Long, b: ByteArray, off: Int, n: Int) {
            if (closed || n <= 0 || pos < 0 || pos + n > length) return
            runCatching {
                file.seek(pos)
                file.write(b, off, n)
                add(pos, pos + n)
            }
        }

        private fun add(a0: Long, z0: Long) {
            var a = a0
            var z = z0
            have.floorEntry(a)?.let { if (it.value >= a) { a = it.key; z = maxOf(z, it.value) } }
            while (true) {
                val next = have.ceilingEntry(a) ?: break
                if (next.key > z) break
                z = maxOf(z, next.value)
                have.remove(next.key)
            }
            have[a] = z
        }

        /** The song stopped (or ended): keep it if most of it was heard, fetching whatever bits are missing. */
        @Synchronized
        fun finish() {
            if (closed) return
            closed = true
            val saved = have.entries.sumOf { it.value - it.key }
            if (saved < length * 6 / 10) { runCatching { file.close() }; part.delete(); return }
            val gaps = ArrayList<Pair<Long, Long>>()
            var at = 0L
            for ((a, z) in have) { if (a > at) gaps += at to a; at = z }
            if (at < length) gaps += at to length
            Thread({
                try {
                    for ((a, z) in gaps) fetch(a, z)
                    file.close()
                    commit()
                } catch (e: Exception) {
                    android.util.Log.w("Glasswave", "Couldn't keep ${track.title} in the cache", e)
                    runCatching { file.close() }
                    part.delete()
                }
            }, "song-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        }

        private fun fetch(a: Long, z: Long) {
            var p = a
            while (p < z) {
                val end = minOf(z, p + (1L shl 20)) - 1
                val req = Request.Builder().url(stream).header("User-Agent", YtDownloader.USER_AGENT).header("Range", "bytes=$p-$end").build()
                YtRepo.http.newCall(req).execute().use { r ->
                    if (r.code != 206) throw java.io.IOException("The server answered ${r.code}")
                    val bytes = r.body?.bytes() ?: throw java.io.IOException("Empty piece")
                    file.seek(p)
                    file.write(bytes, 0, minOf(bytes.size.toLong(), end - p + 1).toInt())
                    p += bytes.size
                }
            }
        }

        private fun commit() {
            synchronized(lock) {
                if (entries.containsKey(track.url)) { part.delete(); return }
                val out = File(dir, part.name.removeSuffix(".part") + ".audio")
                if (!part.renameTo(out)) { part.delete(); return }
                entries[track.url] = Entry(track.copy(seconds = track.seconds), out, System.currentTimeMillis())
                trim()
                save()
            }
        }
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
