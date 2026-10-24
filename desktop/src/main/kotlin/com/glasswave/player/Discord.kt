// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shows what you're playing on your Discord profile ("Listening to Glasswave"), like Spotify does.
 * It talks to the Discord app running on this computer through its local pipe: no sign-in, nothing
 * sent anywhere else. If Discord isn't open, it quietly tries again later. Paused: the status clears.
 */
object Discord {
    /**
     * The Discord application this shows as. Its name ("Glasswave") and icon come from
     * discord.com/developers. Blank: the feature stays off.
     */
    private const val CLIENT_ID = "1563448661147517028"

    val available get() = CLIENT_ID.isNotBlank()

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "discord").apply { isDaemon = true } }
    private var pipe: RandomAccessFile? = null
    private var lastTry = 0L

    /** What should show right now, or null for nothing. */
    data class Now(val title: String, val artist: String, val art: String?, val startMs: Long, val endMs: Long)

    @Volatile private var wanted: Now? = null
    private var shown: Now? = null
    private var shownAnything = false

    init {
        // Keeps the status in step every few seconds (and reconnects when Discord opens).
        worker.scheduleWithFixedDelay({ runCatching { sync() } }, 3, 4, TimeUnit.SECONDS)
    }

    /** Called from the app with the player's current state. */
    fun update(ps: PlayerState) {
        val on = available && Settings.discord && ps.playing && ps.title.isNotBlank()
        wanted = if (!on) null else {
            val now = System.currentTimeMillis()
            val start = now - ps.livePosition()
            Now(ps.title, ps.artist, hiRes(ps.art), start, if (ps.durationMs > 0) start + ps.durationMs else 0L)
        }
    }

    /** Clears the status as the app closes. */
    fun shutdown() {
        wanted = null
        runCatching { worker.submit { runCatching { sync() }; close() }.get(1, TimeUnit.SECONDS) }
    }

    private fun sync() {
        val w = wanted
        val s = shown
        // Same song and the clock still lines up (within 3 s): nothing to send.
        val same = if (w == null || s == null) w == s && shownAnything == (w != null)
        else w.title == s.title && w.artist == s.artist && w.art == s.art && kotlin.math.abs(w.startMs - s.startMs) < 3000
        if (same && pipe != null) return
        if (w == null && !shownAnything) return
        val p = connect() ?: return
        try {
            val args = JSONObject().put("pid", ProcessHandle.current().pid())
            if (w != null) args.put("activity", activity(w))
            send(p, 1, JSONObject().put("cmd", "SET_ACTIVITY").put("args", args).put("nonce", UUID.randomUUID().toString()))
            val reply = read(p)
            if (reply.optString("evt") == "ERROR") android.util.Log.w("Discord", reply.optJSONObject("data")?.optString("message") ?: "error")
            else android.util.Log.d("Discord", if (w != null) "Showing: ${w.title}" else "Cleared")
            shown = w
            shownAnything = w != null
        } catch (e: IOException) {
            close()
        }
    }

    private fun activity(n: Now): JSONObject {
        fun fit(s: String) = s.trim().let { if (it.length < 2) "$it  " else it }.take(128)
        val a = JSONObject()
            .put("type", 2) // "Listening to"
            .put("details", fit(n.title))
            .put("state", fit("by ${n.artist.ifBlank { "Unknown artist" }}"))
        val t = JSONObject().put("start", n.startMs)
        if (n.endMs > n.startMs) t.put("end", n.endMs)
        a.put("timestamps", t)
        val assets = JSONObject().put("large_text", fit(n.title))
        if (n.art != null && n.art.startsWith("https://") && n.art.length <= 256) assets.put("large_image", n.art)
        a.put("assets", assets)
        a.put("buttons", JSONArray().put(JSONObject().put("label", "Get Glasswave").put("url", "https://uronlynonny.github.io/Glasswave/")))
        return a
    }

    private fun connect(): RandomAccessFile? {
        pipe?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastTry < 20_000) return null
        lastTry = now
        for (i in 0..9) {
            val p = try { RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw") } catch (e: Exception) { continue }
            try {
                send(p, 0, JSONObject().put("v", 1).put("client_id", CLIENT_ID))
                val ready = read(p)
                if (ready.optString("evt") == "READY") {
                    pipe = p
                    return p
                }
            } catch (e: IOException) { }
            runCatching { p.close() }
        }
        return null
    }

    private fun close() {
        runCatching { pipe?.close() }
        pipe = null
        shown = null
        shownAnything = false
    }

    // Discord's pipe messages: a 4-byte kind, a 4-byte length (both little-endian), then JSON.
    private fun send(p: RandomAccessFile, op: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(op).putInt(bytes.size).put(bytes)
        p.write(buf.array())
    }

    private fun read(p: RandomAccessFile): JSONObject {
        val head = ByteArray(8)
        p.readFully(head)
        val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val op = hb.int
        val len = hb.int
        if (len < 0 || len > 1 shl 20) throw IOException("Bad message")
        val body = ByteArray(len)
        p.readFully(body)
        if (op == 2) throw IOException("Discord closed the connection")
        return runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }
}
