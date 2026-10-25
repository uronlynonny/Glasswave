// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Songs saved on the phone for offline listening. Files live in the app's private storage, so
 * they're removed if the app is uninstalled. The player reads a downloaded song from its file
 * instead of the internet.
 *
 * Each song is fetched in 1 MB pieces, four at a time. YouTube slows down one long download but
 * not short pieces, so this is several times faster than reading the file in one go.
 */
object Downloads {
    private lateinit var dir: File
    private lateinit var app: Context
    private val index get() = File(dir, "index.json")

    /** Finished downloads, newest first. */
    val tracks = mutableStateListOf<Track>()

    /** Downloads in progress: song address -> 0..1. */
    val progress = mutableStateMapOf<String, Float>()

    /** Songs that just finished, for a short "done" animation. */
    val justDone = mutableStateMapOf<String, Long>()

    private val files = ConcurrentHashMap<String, File>()
    private val pending = ConcurrentHashMap<String, Track>()

    /** Title of a song that's still downloading. */
    fun pendingTitle(url: String): String? = pending[url]?.title
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(3)

    private const val PIECE = 1L shl 20
    private const val PIECES_AT_ONCE = 4

    fun init(context: Context) {
        app = context.applicationContext
        dir = File(context.filesDir, "downloads").apply { mkdirs() }
        runCatching {
            val arr = org.json.JSONArray(index.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(dir, o.getString("f"))
                if (!f.exists()) continue
                val t = Track(o.getString("u"), o.optString("t"), o.optString("a"), o.optLong("s"), o.optString("i").ifEmpty { null })
                files[t.url] = f
                tracks += t
            }
        }
        // Leftover pieces from downloads the app was closed during.
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun fileFor(url: String): File? = files[url]?.takeIf { it.exists() }
    fun isDownloaded(url: String) = files.containsKey(url)
    fun isDownloading(url: String) = progress.containsKey(url)

    /** Wi-Fi (or another unmetered network) right now? */
    private fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** The download quality for the current connection, from Settings. */
    fun qualityNow(): Quality = if (onWifi()) Settings.downloadWifi else Settings.downloadData

    fun download(t: Track) {
        // Songs already stored on the phone don't need downloading.
        if (PhoneMusic.isPhoneSong(t.url) || isDownloaded(t.url) || isDownloading(t.url)) return
        progress[t.url] = 0f
        pending[t.url] = t
        scope.launch {
            slots.withPermit {
                try {
                    val f = fetch(t)
                    withContext(Dispatchers.Main) {
                        files[t.url] = f
                        tracks.removeAll { it.url == t.url }
                        tracks.add(0, t)
                        justDone[t.url] = System.currentTimeMillis()
                        save()
                    }
                    // Its lyrics come along, so they show offline too.
                    runCatching { LyricsRepo.keepFor(t) }
                } catch (e: Exception) {
                    android.util.Log.w("Glasswave", "Download failed: ${t.title}", e)
                } finally {
                    pending.remove(t.url)
                    withContext(Dispatchers.Main) { progress.remove(t.url) }
                }
            }
        }
    }

    fun downloadAll(list: List<Track>) = list.forEach(::download)

    fun remove(url: String) {
        files.remove(url)?.delete()
        LyricsRepo.forget(url)
        tracks.removeAll { it.url == url }
        justDone.remove(url)
        save()
    }

    fun removeAll() {
        files.values.forEach { it.delete() }
        files.clear()
        LyricsRepo.forgetAll()
        tracks.clear()
        justDone.clear()
        save()
    }

    fun totalBytes(): Long = files.values.sumOf { it.length() }

    private fun show(url: String, p: Float) {
        scope.launch(Dispatchers.Main) { if (progress.containsKey(url)) progress[url] = p }
    }

    private suspend fun fetch(t: Track): File {
        val uri = android.net.Uri.parse(t.url)
        val page = if (uri.scheme == "gwmatch") {
            YtRepo.match(uri.getQueryParameter("q") ?: throw IOException("Empty song"), uri.getQueryParameter("d")?.toLongOrNull() ?: 0)
        } else t.url
        val stream = YtRepo.streamUrl(page, qualityNow())
        show(t.url, 0.02f)
        val name = "s" + Integer.toHexString(t.url.hashCode()) + "_" + System.currentTimeMillis() + ".audio"
        val out = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            val total = sizeOf(stream)
            if (total != null && total > 0) inPieces(stream, part, total, t.url) else inOneGo(stream, part, t.url)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (!part.renameTo(out)) throw IOException("Couldn't save the download")
        return out
    }

    /** The file size, from a one-byte request. Null if the server doesn't say. */
    private fun sizeOf(stream: String): Long? {
        val req = Request.Builder().url(stream).header("User-Agent", YtDownloader.USER_AGENT).header("Range", "bytes=0-0").build()
        return YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
        }
    }

    private suspend fun inPieces(stream: String, part: File, total: Long, url: String) {
        RandomAccessFile(part, "rw").use { it.setLength(total) }
        val done = AtomicLong(0)
        var lastShown = 0f
        val gate = Semaphore(PIECES_AT_ONCE)
        coroutineScope {
            (0 until total step PIECE).map { start ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val end = minOf(start + PIECE, total) - 1
                        piece(stream, part, start, end)
                        val p = done.addAndGet(end - start + 1).toFloat() / total
                        synchronized(done) {
                            if (p - lastShown >= 0.01f || p >= 1f) {
                                lastShown = p
                                show(url, 0.02f + p * 0.98f)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** One piece, tried up to three times. */
    private fun piece(stream: String, part: File, start: Long, end: Long) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val req = Request.Builder().url(stream)
                    .header("User-Agent", YtDownloader.USER_AGENT)
                    .header("Range", "bytes=$start-$end")
                    .build()
                YtRepo.http.newCall(req).execute().use { r ->
                    if (r.code != 206 && r.code != 200) throw IOException("Download failed (${r.code})")
                    val bytes = r.body?.bytes() ?: throw IOException("Empty piece")
                    RandomAccessFile(part, "rw").use { f ->
                        f.seek(start)
                        f.write(bytes, 0, minOf(bytes.size.toLong(), end - start + 1).toInt())
                    }
                }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(300L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }

    /** Fallback when the size isn't known: read the whole file in one request. */
    private fun inOneGo(stream: String, part: File, url: String) {
        val req = Request.Builder().url(stream).header("User-Agent", YtDownloader.USER_AGENT).build()
        YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            var lastShown = 0f
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total != null) {
                            val p = done.toFloat() / total
                            if (p - lastShown >= 0.01f) {
                                lastShown = p
                                show(url, p)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun save() {
        val arr = org.json.JSONArray()
        for (t in tracks) {
            val f = files[t.url] ?: continue
            arr.put(
                org.json.JSONObject().put("u", t.url).put("t", t.title).put("a", t.artist).put("s", t.seconds)
                    .put("i", t.thumb ?: "").put("f", f.name),
            )
        }
        runCatching { index.writeText(arr.toString()) }
    }
}
