// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A song from the internet, downloaded ahead of where it's playing into a small temporary file.
 * The sound card only holds about half a second of sound, so reading straight from the internet
 * meant any hiccup in the connection longer than that was a gap you could hear. Now a separate
 * download runs up to [AHEAD] bytes (several minutes of music) in front of the music, and the
 * music plays from what's already here. Jumps (seeking) start the download again from there.
 * The temporary file is deleted when the song closes, unless the whole song arrived: then it's
 * kept in the [SongCache] (for [track]), so it plays again instantly and with no internet.
 */
internal class AheadStream(url: String, private val track: Track? = null) : InputStream() {
    private val net = HttpSeekStream(url)
    private val file: File = File.createTempFile("song-", ".part", dir()).apply { deleteOnExit() }
    private val raf = RandomAccessFile(file, "rw")
    private val lock = Object()

    // All below are guarded by [lock].
    /** The bytes held in the file: [start, end). */
    private var start = 0L
    private var end = 0L
    /** Where the music is reading. */
    private var pos = 0L
    /** A jump asked the download to start again from [want]. */
    private var restart = false
    private var want = 0L
    /** The download reached the end of the song. */
    private var done = false
    private var failure: IOException? = null
    private var closed = false

    /** The song's size in bytes, once the server has said (-1 until then). */
    val length: Long get() = net.length
    /** How long the server last took to answer. */
    val lastOpenMs: Long get() = net.lastOpenMs

    private val worker = Thread(::download, "song-download").apply { isDaemon = true; start() }

    private fun download() {
        val buf = ByteArray(64 * 1024)
        var at = 0L
        while (true) {
            // Wait while far enough ahead, finished or failed, unless a jump came in.
            var go = false
            synchronized(lock) {
                while (!closed && !restart && (done || failure != null || end - pos > AHEAD)) lock.wait(500)
                if (closed) return
                if (restart) {
                    restart = false
                    at = want
                    start = want
                    end = want
                    done = false
                    failure = null
                }
                go = true
            }
            if (!go) continue
            val n = try {
                net.seek(at)
                net.read(buf, 0, buf.size)
            } catch (e: IOException) {
                synchronized(lock) {
                    if (!restart && !closed) failure = e
                    lock.notifyAll()
                }
                continue
            }
            synchronized(lock) {
                if (closed) return
                if (!restart) {
                    if (n < 0) {
                        done = true
                    } else if (n > 0) {
                        raf.seek(at)
                        raf.write(buf, 0, n)
                        at += n
                        end = at
                    }
                }
                lock.notifyAll()
            }
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            while (true) {
                if (closed) return -1
                val size = net.length
                if (size in 0..pos) return -1
                when {
                    // Somewhere the download isn't heading: start it again from here.
                    !restart && (pos < start || pos > end + JUMP) -> {
                        want = pos
                        restart = true
                        lock.notifyAll()
                    }
                    !restart && pos < end -> {
                        val n = minOf(len.toLong(), end - pos).toInt()
                        raf.seek(pos)
                        raf.readFully(b, off, n)
                        pos += n
                        lock.notifyAll() // there may be room to download more now
                        return n
                    }
                    !restart && done -> return -1
                }
                failure?.let { e ->
                    if (!restart) {
                        failure = null
                        throw e
                    }
                }
                lock.wait(200)
            }
        }
    }

    // FFmpeg jumps by going back to the start and skipping forward; neither reads anything.
    override fun markSupported() = true
    override fun mark(readlimit: Int) {}
    override fun reset() { synchronized(lock) { pos = 0 } }
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        synchronized(lock) {
            // FFmpeg skips by Long.MAX_VALUE to find the end: cap the step first so nothing overflows.
            val size = net.length
            val moved = if (size >= 0) minOf(n, (size - pos).coerceAtLeast(0)) else 0
            pos += moved
            return moved
        }
    }
    override fun available() = 0

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lock.notifyAll()
        }
        runCatching { net.close() }
        runCatching { worker.join(2000) }
        val whole = synchronized(lock) {
            runCatching { raf.close() }
            start == 0L && done && net.length > 0 && end >= net.length
        }
        val keepFor = track
        if (whole && keepFor != null && runCatching { SongCache.keep(keepFor, file) }.getOrDefault(false)) return
        file.delete()
    }

    companion object {
        /** How far ahead to download: 8 MB, about 7 minutes of music at the best quality. */
        // Enough for a whole song, so a dropped connection doesn't cut it short.
        private const val AHEAD = 24L * 1024 * 1024
        /** A jump this close past what's downloaded just waits for the download to get there. */
        private const val JUMP = 512L * 1024

        @Volatile private var cleaned = false

        /** The folder for the temporary files; left-overs from a crash are cleared once. */
        private fun dir(): File {
            val d = File(android.content.AppDirs.cache, "playing").apply { mkdirs() }
            if (!cleaned) {
                cleaned = true
                d.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000L) it.delete() }
            }
            return d
        }
    }
}
