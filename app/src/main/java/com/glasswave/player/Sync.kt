// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone and PC together, like Spotify, for people signed in to the same YouTube Music account:
 *  - Listening stats: each device shares the songs it played, so stats on every device count all of them.
 *  - Devices: see what your other devices are playing, control them, and move playback between them.
 *
 * There's no Glasswave server. Devices on the same account work out the same secret key from the
 * account's private YouTube id (only that account's signed-in devices can see it), and send each
 * other small encrypted messages through ntfy.sh, a public message relay that keeps messages for
 * 12 hours. Nobody else can read or send them. Messages are only sent when something happens (a new
 * song, play/pause, a command, new plays), because the relay allows 250 a day per connection.
 * Only while signed in to YouTube Music, and it can be turned off in Settings. Same file in the
 * phone and Windows apps.
 */
object Sync {
    /** Another device on this account, as last heard from. */
    data class Device(
        val id: String,
        val name: String,
        val kind: String,
        val playing: Boolean,
        val track: Track?,
        val posMs: Long,
        val durMs: Long,
        /** When it said this, on this device's clock. */
        val heardAt: Long,
    ) {
        val isPhone get() = kind == "phone"
        /** Where it probably is in the song now. */
        fun position(now: Long = System.currentTimeMillis()): Long =
            if (playing) (posMs + (now - heardAt)).coerceAtMost(if (durMs > 0) durMs else Long.MAX_VALUE) else posMs
        /** Still playing, as far as we know (it would have said if it stopped or changed song). */
        fun playingNow(now: Long = System.currentTimeMillis()): Boolean {
            val left = if (durMs > 0) durMs - posMs + 90_000 else 15 * 60_000L
            return playing && (now - heardAt) <= left
        }
    }

    /** What the player does when another device asks. Set by the app. */
    interface Remote {
        fun current(): Track?
        fun isPlaying(): Boolean
        fun positionMs(): Long
        fun durationMs(): Long
        fun upcoming(limit: Int): List<Track>
        fun toggle()
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun seekTo(ms: Long)
        fun playQueue(tracks: List<Track>, index: Int, startMs: Long)
    }

    /** The other devices heard from in the last few hours, newest first. */
    var devices by mutableStateOf<List<Device>>(emptyList())
        private set
    /** Connected to the relay and signed in. */
    var active by mutableStateOf(false)
        private set
    /** The Devices panel. */
    var panelOpen by mutableStateOf(false)
    /** Goes up when another device's plays arrive (stats refresh). */
    var playsVersion by mutableIntStateOf(0)
        private set

    var remote: Remote? = null

    private const val RELAY = "https://ntfy.sh"
    private lateinit var prefs: SharedPreferences
    private lateinit var filesDir: File
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Glasswave sync").apply { isDaemon = true } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val random = SecureRandom()

    private val myId: String by lazy {
        prefs.getString("device", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    }

    // The account's key and relay channel, once worked out.
    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var topic: String? = null
    @Volatile private var account: String? = null
    @Volatile private var listener: Thread? = null
    @Volatile private var lastMessageId: String? = null
    private val heard = java.util.concurrent.ConcurrentHashMap<String, Device>()
    private val seenPlays = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        filesDir = context.filesDir
    }

    /** Automated checks only: a made-up account, so two test copies can find each other without signing in. */
    private val testAccount: String? get() = System.getProperty("gw.syncTestAccount")
    private val signedIn get() = Accounts.ytSignedIn || testAccount != null

    private val on get() = signedIn && Settings.syncDevices && ::prefs.isInitialized

    /** Signed in to YouTube Music with syncing turned on (the Devices button shows). */
    val available: Boolean get() = signedIn && Settings.syncDevices

    /** Automated checks only: pretend a device was heard from. */
    internal fun testHeard(d: Device) { heard[d.id] = d; refresh() }

    /** Plays from this account's other devices (read by Stats), or null when not syncing. */
    fun syncedPlaysFile(): File? = account?.takeIf { signedIn && Settings.syncDevices }?.let { File(filesDir, "plays-synced-$it.jsonl") }

    /** Starts (or restarts) syncing if signed in. Safe to call often. */
    fun start() {
        if (!on) { stop(); return }
        if (listener?.isAlive == true) return
        io.execute {
            if (!on || listener?.isAlive == true) return@execute
            val id = runCatching { accountId() }.getOrNull() ?: return@execute
            setAccount(id)
            listener = Thread({ listen() }, "Glasswave sync listener").apply { isDaemon = true; start() }
        }
    }

    /** On sign-out or when turned off. */
    fun stop() {
        listener?.interrupt()
        listener = null
        key = null; topic = null; account = null
        heard.clear()
        main.post { devices = emptyList(); active = false; playsVersion++ }
        Stats.bump()
    }

    /** Says goodbye (app closing), so other devices stop showing this one as playing. */
    fun goodbye() {
        if (key == null) return
        runCatching { send(JSONObject().put("ty", "bye"), essential = true) }
    }

    // ---------------------------------------------------------------- account key

    /**
     * The account's private id ("DATASYNC_ID" in YouTube Music's page settings). It's the same on
     * every device signed in to the account and isn't shown publicly. Remembered per sign-in.
     */
    private fun accountId(): String? {
        testAccount?.let { return it }
        val cookie = Accounts.ytCookie ?: return null
        val cookieTag = sha("cookie|$cookie").take(16)
        prefs.getString("acct_$cookieTag", null)?.let { return it }
        val req = Request.Builder().url("https://music.youtube.com/")
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .build()
        val html = YtRepo.http.newCall(req).execute().use { it.body?.string().orEmpty() }
        val id = Regex("\"DATASYNC_ID\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.substringBefore("||")?.takeIf { it.length >= 6 }
            ?: return null
        prefs.edit().putString("acct_$cookieTag", id).apply()
        return id
    }

    private fun setAccount(id: String) {
        val k = MessageDigest.getInstance("SHA-256").digest("glasswave-sync-v1|$id".toByteArray())
        key = SecretKeySpec(k, "AES")
        topic = "gws-" + sha("topic|" + k.joinToString("") { "%02x".format(it) }).take(32)
        account = sha("account|$id").take(12)
        // Which plays we already have, so nothing is counted twice.
        seenPlays.clear()
        syncedPlaysFile()?.takeIf { it.exists() }?.forEachLine { l ->
            runCatching { JSONObject(l) }.getOrNull()?.let { seenPlays += it.optString("d") + "|" + it.optLong("at") + "|" + it.optString("u") }
        }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- sending

    /** Messages sent today (the relay allows 250 a day per internet connection). */
    private fun budget(essential: Boolean): Boolean {
        val day = System.currentTimeMillis() / 86_400_000
        if (prefs.getLong("budget_day", -1) != day) prefs.edit().putLong("budget_day", day).putInt("budget", 0).apply()
        val used = prefs.getInt("budget", 0)
        // Keep room for the ones that matter (commands, moving playback) when it's been a busy day.
        if (used >= (if (essential) 200 else 140)) return false
        prefs.edit().putInt("budget", used + 1).apply()
        return true
    }

    private fun seal(o: JSONObject): String {
        val k = key ?: throw IllegalStateException("not syncing")
        val zipped = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(o.toString().toByteArray()) } }.toByteArray()
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(zipped))
    }

    private fun open(text: String): JSONObject? = runCatching {
        val k = key ?: return null
        val raw = Base64.getDecoder().decode(text.trim())
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, 12)) }
        val plain = c.doFinal(raw, 12, raw.size - 12)
        JSONObject(GZIPInputStream(ByteArrayInputStream(plain)).readBytes().decodeToString())
    }.getOrNull()

    /** Blocking. Adds who's sending, and posts it to the account's channel. */
    private fun send(o: JSONObject, essential: Boolean = false): Boolean {
        val t = topic ?: return false
        o.put("v", 1).put("d", myId).put("n", SyncPlatform.deviceName()).put("k", SyncPlatform.kind).put("ts", System.currentTimeMillis())
        val body = seal(o)
        if (body.length > 4000) return false
        if (!budget(essential)) return false
        val req = Request.Builder().url("$RELAY/$t").post(body.toRequestBody("text/plain".toMediaType())).header("Priority", "1").build()
        return runCatching { YtRepo.http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private fun sendLater(o: () -> JSONObject?, essential: Boolean = false) {
        if (key == null) return
        io.execute { o()?.let { runCatching { send(it, essential) } } }
    }

    private fun trackJson(t: Track): JSONObject {
        // A song stored on this phone can't play elsewhere: the other device finds it on YouTube Music instead.
        val phone = PhoneMusic.isPhoneSong(t.url)
        val url = if (phone) matchUrl(t.title, t.artist, t.seconds) else t.url
        return JSONObject().put("u", url).put("t", t.title).put("a", t.artist).put("s", t.seconds).put("i", if (phone) "" else t.thumb ?: "")
    }
    private fun trackOf(o: JSONObject?) = o?.optString("u")?.takeIf { it.isNotEmpty() }?.let {
        Track(it, o.optString("t"), o.optString("a"), o.optLong("s"), o.optString("i").ifEmpty { null })
    }

    // What we last told the others, so the same thing isn't sent twice.
    @Volatile private var lastState: String? = null
    @Volatile private var lastStateAt = 0L

    /** Tells the other devices what's playing here. Called when the song or play/pause changes. */
    fun publishState(force: Boolean = false) {
        val r = remote ?: return
        if (key == null) return
        val t = r.current()
        val playing = r.isPlaying()
        val sig = "${t?.url}|$playing"
        val now = System.currentTimeMillis()
        // Nothing new to say (and paused with nothing loaded isn't worth a message at start-up).
        if (!force && (sig == lastState || (lastState == null && t == null))) return
        if (!force && now - lastStateAt < 2500 && sig.substringBefore('|') == lastState?.substringBefore('|') && t != null) return
        lastState = sig
        lastStateAt = now
        val pos = r.positionMs()
        val dur = r.durationMs()
        sendLater({ JSONObject().put("ty", "st").put("p", playing).put("pos", pos).put("dur", dur).apply { t?.let { put("tr", trackJson(it)) } } })
    }

    /** Asks the other devices to say what they're playing (and send any plays we're missing). */
    @Volatile private var lastHello = 0L
    fun hello(force: Boolean = false) {
        if (key == null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastHello < 60_000) return
        lastHello = now
        sendLater({ JSONObject().put("ty", "hi").put("since", JSONObject(sinceMap())) })
    }

    /** A remote control button for another device: toggle, play, pause, next, prev or seek. */
    fun command(to: Device, c: String, ms: Long = 0) {
        // Show it straight away; the device confirms with its own update.
        if (c == "toggle" || c == "play" || c == "pause") {
            val playing = when (c) { "play" -> true; "pause" -> false; else -> !to.playing }
            heard[to.id] = to.copy(playing = playing, posMs = to.position(), heardAt = System.currentTimeMillis())
            refresh()
        }
        sendLater({ JSONObject().put("ty", "cmd").put("to", to.id).put("c", c).put("ms", ms) }, essential = true)
    }

    /** Moves what's playing here to [to]: it carries on from the same spot, and this device pauses. */
    fun playOn(to: Device) {
        val r = remote ?: return
        val t = r.current() ?: return
        val queue = listOf(t) + r.upcoming(24)
        val pos = r.positionMs()
        r.pause()
        sendLater({
            JSONObject().put("ty", "xfer").put("to", to.id).put("pos", pos).put("i", 0)
                .put("q", JSONArray().apply { queue.forEach { put(trackJson(it)) } })
        }, essential = true)
    }

    /** Takes over what [from] is playing: it plays here from the same spot, and that device pauses. */
    fun playHere(from: Device) {
        val r = remote ?: return
        val t = from.track ?: return
        r.playQueue(listOf(t), 0, from.position())
        command(from, "pause")
    }

    // ---------------------------------------------------------------- plays (listening stats)

    /** Newest play time we have from each other device, sent with "hi" so they only send what's new. */
    private fun sinceMap(): Map<String, Long> =
        runCatching { JSONObject(prefs.getString("since_$account", "{}")!!) }.getOrNull()?.let { o -> o.keys().asSequence().associateWith { o.optLong(it) } } ?: emptyMap()

    @Volatile private var pushQueued = false

    /** Called after a play is logged: shares new plays a little later, a few at a time. */
    fun onPlayRecorded() {
        if (key == null || pushQueued) return
        pushQueued = true
        main.postDelayed({ pushQueued = false; io.execute { runCatching { pushPlays(prefs.getLong("pushed_$account", 0L), limitMessages = 4) } } }, 90_000)
    }

    /** Sends this device's own plays after [after] (oldest first), in as few messages as fit. */
    private fun pushPlays(after: Long, limitMessages: Int) {
        val own = Stats.ownPlaysAfter(after - 1)
        if (own.isEmpty()) return
        var i = 0
        var sent = 0
        var newest = after
        while (i < own.size && sent < limitMessages) {
            // As many plays as fit in one message.
            var n = minOf(own.size - i, 60)
            var msg: JSONObject
            while (true) {
                val chunk = own.subList(i, i + n)
                msg = JSONObject().put("ty", "pl").put("plays", JSONArray().apply {
                    chunk.forEach { p -> put(JSONArray().put(p.at).put(p.ms).put(p.track.url).put(p.track.title).put(p.track.artist).put(p.track.thumb ?: "")) }
                })
                if (n == 1 || seal(JSONObject(msg.toString()).put("v", 1).put("d", myId).put("n", SyncPlatform.deviceName()).put("k", SyncPlatform.kind).put("ts", 0L)).length <= 3900) break
                n = maxOf(1, n * 2 / 3)
            }
            if (!send(msg)) break
            newest = maxOf(newest, own[i + n - 1].at)
            i += n
            sent++
        }
        if (newest > prefs.getLong("pushed_$account", 0L)) prefs.edit().putLong("pushed_$account", newest).apply()
    }

    private fun takePlays(from: String, name: String, arr: JSONArray?) {
        val file = syncedPlaysFile() ?: return
        arr ?: return
        val lines = StringBuilder()
        var newest = 0L
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            val at = p.optLong(0)
            if (at <= 0 || !seenPlays.add("$from|$at|${p.optString(2)}")) continue
            newest = maxOf(newest, at)
            lines.append(
                JSONObject().put("u", p.optString(2)).put("t", p.optString(3)).put("a", p.optString(4)).put("i", p.optString(5))
                    .put("at", at).put("ms", p.optLong(1)).put("d", from).put("dn", name).toString(),
            ).append('\n')
        }
        if (lines.isEmpty()) return
        file.appendText(lines.toString())
        val since = JSONObject(prefs.getString("since_$account", "{}")!!)
        if (newest > since.optLong(from)) prefs.edit().putString("since_$account", since.put(from, newest).toString()).apply()
        main.post { playsVersion++ }
        Stats.bump()
    }

    // ---------------------------------------------------------------- receiving

    /** Keeps a connection to the account's channel open, reconnecting when it drops. */
    private fun listen() {
        val client = YtRepo.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
        var wait = 2_000L
        var first = true
        while (!Thread.currentThread().isInterrupted && on) {
            val t = topic ?: return
            // First time: the last 12 hours (whatever the relay still has). After a drop: from the last message seen.
            val since = lastMessageId ?: "12h"
            try {
                val req = Request.Builder().url("$RELAY/$t/json?since=$since").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("relay ${resp.code}")
                    main.post { active = true }
                    wait = 2_000L
                    if (first) {
                        first = false
                        // Say hello, share anything not yet shared, and say what's playing here.
                        hello(force = true)
                        io.execute { runCatching { pushPlays(prefs.getLong("pushed_$account", 0L), limitMessages = 6) } }
                        main.postDelayed({ publishState(force = remote?.current() != null) }, 1500)
                    }
                    val src = resp.body?.source() ?: return@use
                    while (!Thread.currentThread().isInterrupted) {
                        val line = src.readUtf8Line() ?: break
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (ev.optString("event") != "message") continue
                        lastMessageId = ev.optString("id").ifEmpty { lastMessageId }
                        val o = open(ev.optString("message")) ?: continue
                        runCatching { handle(o, ev.optLong("time") * 1000) }
                    }
                }
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) return
            }
            main.post { active = false }
            try { Thread.sleep(wait) } catch (e: InterruptedException) { return }
            wait = minOf(wait * 2, 60_000L)
        }
    }

    private fun handle(o: JSONObject, relayTime: Long) {
        val from = o.optString("d")
        if (from.isEmpty() || from == myId) return
        val now = System.currentTimeMillis()
        val sentAt = o.optLong("ts").takeIf { it > 0 } ?: relayTime
        // How long ago it was said (by the relay's clock, which both sides share closely enough).
        val age = (now - relayTime).coerceAtLeast(0)
        val name = o.optString("n").ifEmpty { "Another device" }
        val kind = o.optString("k")
        // Anything from a device counts as hearing from it.
        val known = heard[from]
        when (o.optString("ty")) {
            "st" -> {
                if (known != null && known.heardAt > now - age) { /* older than what we have */ }
                else heard[from] = Device(from, name, kind, o.optBoolean("p"), trackOf(o.optJSONObject("tr")), o.optLong("pos"), o.optLong("dur"), now - age)
                refresh()
            }
            "bye" -> { heard[from]?.let { heard[from] = it.copy(playing = false, heardAt = now - age) }; refresh() }
            "hi" -> {
                if (known == null) heard[from] = Device(from, name, kind, false, null, 0, 0, now - age)
                refresh()
                if (age > 60_000) return
                // Answer: what's playing here, and the plays it doesn't have yet.
                main.post { publishState(force = true) }
                val after = o.optJSONObject("since")?.optLong(myId) ?: 0L
                io.execute { runCatching { pushPlays(after, limitMessages = 12) } }
            }
            "cmd" -> {
                if (o.optString("to") != myId || age > 60_000) return
                val r = remote ?: return
                main.post {
                    when (o.optString("c")) {
                        "toggle" -> r.toggle()
                        "play" -> r.play()
                        "pause" -> r.pause()
                        "next" -> r.next()
                        "prev" -> r.previous()
                        "seek" -> r.seekTo(o.optLong("ms"))
                    }
                    main.postDelayed({ publishState(force = true) }, 800)
                }
            }
            "xfer" -> {
                if (o.optString("to") != myId || age > 120_000) return
                val q = o.optJSONArray("q") ?: return
                val tracks = (0 until q.length()).mapNotNull { trackOf(q.optJSONObject(it)) }
                if (tracks.isEmpty()) return
                // It was playing for the time the message took, so start a touch later.
                val pos = o.optLong("pos") + age.coerceAtMost(10_000)
                main.post { remote?.playQueue(tracks, o.optInt("i"), pos) }
            }
            "pl" -> takePlays(from, name, o.optJSONArray("plays"))
        }
        if (sentAt > 0 && o.optString("ty") != "st" && heard[from] == null) {
            heard[from] = Device(from, name, kind, false, null, 0, 0, now - age)
            refresh()
        }
    }

    /** Devices heard from in the last 6 hours, playing ones first. */
    private fun refresh() {
        val now = System.currentTimeMillis()
        val list = heard.values.filter { now - it.heardAt < 6 * 3600_000L }
            .sortedWith(compareByDescending<Device> { it.playingNow(now) }.thenByDescending { it.heardAt })
        main.post { devices = list }
    }
}
