// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
 * Listen together ("Jam"): one person starts a Jam and shares its code; others join from a phone or
 * PC. Everyone hears the same song at the same spot (each device streams it itself), anyone can add
 * songs, and the host can remove people or end it.
 *
 * On the same Wi-Fi or hotspot the devices talk straight to each other ([JamLan]): no internet relay,
 * no daily limit. Someone further away is reached through the same relay as [Sync] (ntfy.sh, which
 * allows about 250 messages a day per internet connection), so only what they need goes there, and
 * only while they're in the Jam. The code is the secret: the key and the relay channel are worked out
 * from it, so only people with the code can read or send. No sign-in needed.
 *
 * The host's own player is the truth. It says what's playing ("st": the song, Up next, play/pause and
 * where it was at a given moment) whenever that changes. Members make their players match, and turn
 * what their own player does (buttons, notification, media keys) into requests to the host.
 * Positions are compared on a shared clock: each device measures how far off its clock is (SNTP).
 * Same file in the phone and PC apps.
 */
object Jam {
    /** What a Jam needs from the player. Set by the app (see jamPlayer). */
    interface Player {
        fun current(): Track?
        /** Playing, or about to (play pressed, still loading). */
        fun wantsToPlay(): Boolean
        /** Sound is coming out right now (not loading or buffering). */
        fun sounding(): Boolean
        fun positionMs(): Long
        fun durationMs(): Long
        fun upcoming(limit: Int): List<Track>
        fun play()
        fun pause()
        fun seekTo(ms: Long)
        fun next()
        fun previous()
        /** Plays [tracks] from [index] at [startMs]; loads it paused when [play] is false. */
        fun load(tracks: List<Track>, index: Int, startMs: Long, play: Boolean)
        /** Replaces what comes after the current song, without touching the current song. */
        fun setUpcoming(tracks: List<Track>)
        fun addToQueue(t: Track)
        /** Plays [t] now, keeping the rest of the queue. */
        fun playNow(t: Track)
        /** Normal speed: a speed preset would drift away from everyone else. */
        fun normalSpeed()
        /** Back to your own speed preset after a Jam. */
        fun ownSpeed()
    }

    enum class Role { NONE, HOST, MEMBER }

    /** Someone in the Jam. [device]: "Windows PC", "Pixel 8". */
    data class Member(
        val id: String, val name: String, val device: String, val kind: String, val photo: String?, val host: Boolean, val heardAt: Long,
        /** Connected straight across the Wi-Fi (else through the relay). */
        val lan: Boolean = false,
    )

    /** Not heard from for a while (straight across the Wi-Fi they check in every few seconds; over the relay, every 25 minutes). */
    fun isAway(m: Member, now: Long): Boolean = !m.host && now - m.heardAt > (if (m.lan) 60_000L else 35 * 60_000L)

    var role by mutableStateOf(Role.NONE)
        private set
    /** The Jam's code, as shown ("K7Q-F2X-D9P"). */
    var code by mutableStateOf<String?>(null)
        private set
    /** Everyone in the Jam, host first. */
    var members by mutableStateOf<List<Member>>(emptyList())
        private set
    /** Connected to the relay. */
    var connected by mutableStateOf(false)
        private set
    /** A line about how it's going ("Joining…", "Lost touch with Sam"), or null when all is well. */
    var status by mutableStateOf<String?>(null)
        private set
    /** Who added each queued song (by address), for Up next. */
    var addedBy by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** The Jam panel. */
    var panelOpen by mutableStateOf(false)
    /** A code from a link, waiting in the panel's Join box. */
    var joinCode by mutableStateOf("")

    val inJam: Boolean get() = role != Role.NONE
    val isHost: Boolean get() = role == Role.HOST
    /** In someone else's Jam: this player follows the host's (no Autoplay or Automix of its own). */
    val following: Boolean get() = role == Role.MEMBER
    val hostName: String get() = members.firstOrNull { it.host }?.name ?: "the host"
    fun isMe(m: Member): Boolean = m.id == myId

    var player: Player? = null

    /** The relay (automated checks can point it at a stand-in on this computer). */
    private val RELAY = System.getProperty("gw.jamRelay") ?: "https://ntfy.sh"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LEN = 9
    private lateinit var prefs: SharedPreferences
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Glasswave jam").apply { isDaemon = true } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val random = SecureRandom()

    private val myId: String by lazy {
        prefs.getString("device", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("jam", Context.MODE_PRIVATE)
        syncPrefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        nearbyOn = prefs.getBoolean("nearby", true)
        usedOnce = prefs.getBoolean("used", false)
        JamNearby.init(prefs)
    }

    /** Sync's settings: the relay's daily messages are shared with it (see [budget]). */
    private lateinit var syncPrefs: SharedPreferences

    /** This device in Jams (also how nearby Jams tell their own announcements apart). */
    internal val deviceId: String get() = if (::prefs.isInitialized) myId else ""

    /** Hosting: people nearby can see this Jam and join (on this Wi-Fi, and in this area). */
    var nearbyOn by mutableStateOf(true)
        private set

    fun updateNearby(on: Boolean) {
        nearbyOn = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("nearby", on).apply()
        JamNearby.hosting(isHost)
    }

    /** The Jam panel has been opened on this device before (nearby Jams are only looked for after that). */
    var usedOnce by mutableStateOf(false)
        private set

    fun markUsed() {
        if (usedOnce || !::prefs.isInitialized) return
        usedOnce = true
        prefs.edit().putBoolean("used", true).apply()
    }

    internal fun myDisplayName(): String = myName()

    /** Your name in a Jam: your YouTube Music first name, else this device's name. */
    private fun myName(): String = System.getProperty("gw.jamName")
        ?: Profile.name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: SyncPlatform.deviceName()

    private fun elapsed() = SystemClock.elapsedRealtime()

    // ---------------------------------------------------------------- the Jam's channel

    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var topic: String? = null
    @Volatile private var listener: Thread? = null
    @Volatile private var lastMessageId: String? = null
    /** Bumped for each Jam, so a listener from an old one stops. */
    @Volatile private var generation = 0

    private fun useCode(c: String) {
        val k = MessageDigest.getInstance("SHA-256").digest("glasswave-jam-v1|$c".toByteArray())
        key = SecretKeySpec(k, "AES")
        topic = "gwj-" + sha("topic|" + k.joinToString("") { "%02x".format(it) }).take(32)
        lanId = sha("lan|" + k.joinToString("") { "%02x".format(it) }).take(16)
        code = c.chunked(3).joinToString("-")
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun newCode() = String(CharArray(CODE_LEN) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** The code in a link or typed text ("k7q f2x-d9p", "glasswave://jam/K7QF2XD9P"), or null. */
    fun parseCode(text: String): String? {
        val t = text.trim()
        val tail = Regex("(?i)jam[/#=]([A-Za-z0-9 -]+)").find(t)?.groupValues?.get(1) ?: t
        val c = tail.uppercase().filter { it.isLetterOrDigit() }
        return c.takeIf { it.length == CODE_LEN && it.all { ch -> ch in ALPHABET } }
    }

    /** The link and message for inviting people. */
    fun inviteLink(): String? = code?.let { "glasswave://jam/" + it.replace("-", "") }
    fun inviteText(): String? = code?.let { "Join my Jam on Glasswave and listen along. Code: $it\n${inviteLink()}" }

    // ---------------------------------------------------------------- starting, joining, leaving

    private val members0 = LinkedHashMap<String, Member>()
    private val banned = HashSet<String>()

    /** Starts a Jam with this player as the host. */
    fun start() {
        if (inJam) return
        if (!::prefs.isInitialized) return
        begin(newCode())
        role = Role.HOST
        members0.clear(); banned.clear()
        members0[myId] = me(host = true)
        publishMembers()
        hostState = null
        hostDirtyAt = 0L
        addedBy = emptyMap()
        everRemote = false
        // The relay is only listened to (free): someone further away may join through it.
        listen(backlog = null)
        lanStart()
        JamNearby.hosting(true)
        Menus.say("Jam started. Share the code so others can join")
    }

    /** Joins the Jam with this code (from [parseCode]). */
    fun join(c: String) {
        if (inJam || !::prefs.isInitialized) return
        begin(c)
        role = Role.MEMBER
        status = "Joining…"
        members0.clear()
        members = emptyList()
        joinStarted = elapsed()
        state = null; lastSeq = 0L; hostId = null; joinedOnce = false
        expTrack = null; lastTickAt = 0L; selfUntil = 0L
        snapshot = null
        pending.clear(); requestedAdds.clear()
        relayJoinSent = false; lanLostAt = 0L; lastProbeAt = 0L
        // Looks for the host on this Wi-Fi first; the relay is only written to if it isn't here.
        lanStart()
        probe()
        // The last hour: whatever the host said lately shows straight away (reading is free).
        listen(backlog = "1h")
    }

    /** Leaves the Jam (a member) or ends it for everyone (the host). */
    fun leave() {
        when (role) {
            Role.NONE -> return
            Role.HOST -> { sendLater({ JSONObject().put("ty", "end") }, essential = true, relay = anyRemote() || everRemote); finish("Jam ended") }
            Role.MEMBER -> { sendLater({ JSONObject().put("ty", "bye").put("to", hostId) }, essential = true); finish("You left the Jam") }
        }
    }

    /** The host takes someone out of the Jam. */
    fun remove(id: String) {
        if (!isHost || id == myId) return
        val m = members0.remove(id) ?: return
        banned += id
        publishMembers()
        markHostDirty()
        sendLater({ JSONObject().put("ty", "kick").put("to", id) }, essential = true, relay = links[id] == null)
        dropLink(id)
        Menus.say("${m.name} was removed from the Jam")
    }

    /** Automated checks only (pictures): pretend these people are in this Jam, and added these songs. */
    internal fun testPeople(people: List<Member>, added: Map<String, String>) {
        people.forEach { members0[it.id] = it }
        publishMembers()
        addedBy = addedBy + added
    }

    /** App closing: tell the others (blocking, a moment at most). */
    fun goodbye() {
        val k = key ?: return
        val t = topic ?: return
        val o = when (role) {
            Role.HOST -> JSONObject().put("ty", "end")
            Role.MEMBER -> JSONObject().put("ty", "bye").put("to", hostId)
            Role.NONE -> return
        }
        val relay = if (isHost) anyRemote() || everRemote else hostLink == null
        runCatching {
            val st = stamp(o)
            val body = seal(st, k)
            (links.values + listOfNotNull(hostLink)).forEach { it.sendNow(body) }
            if (relay) post(t, k, st, essential = true)
        }
    }

    private fun begin(c: String) {
        generation++
        useCode(c)
        lastMessageId = null
        seenMids.clear()
        status = null
        lastSentAt = 0L
        lastPingAt = elapsed()
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 500)
        everConnected = false
        refreshClock()
    }

    /** Asks the time servers again (its own thread: it can take a few seconds). */
    private fun refreshClock() {
        JamClock.checkedAt = elapsed()
        Thread({ JamClock.refresh() }, "Glasswave jam clock").apply { isDaemon = true; start() }
    }

    /** Back to normal: stops listening, and a member gets their own music back. */
    private fun finish(message: String?) {
        val wasMember = role == Role.MEMBER
        if (role == Role.HOST) JamNearby.hosting(false)
        lanStop()
        generation++
        listener?.interrupt()
        listener = null
        main.removeCallbacks(ticker)
        role = Role.NONE
        code = null
        members = emptyList()
        members0.clear()
        connected = false
        status = null
        addedBy = emptyMap()
        val snap = snapshot
        snapshot = null
        state = null
        player?.ownSpeed()
        if (wasMember) {
            val p = player
            if (p != null) {
                val keepPlaying = p.wantsToPlay()
                if (snap != null && snap.tracks.isNotEmpty()) p.load(snap.tracks, 0, snap.posMs, keepPlaying)
                else if (keepPlaying) p.pause()
            }
        }
        // The key stays until the goodbye has gone out.
        main.postDelayed({ if (role == Role.NONE) { key = null; topic = null } }, 5000)
        message?.let { Menus.say(it) }
    }

    private fun me(host: Boolean) = Member(myId, myName(), SyncPlatform.deviceName(), SyncPlatform.kind, Profile.photo, host, elapsed(), lan = true)

    private fun publishMembers() {
        members = members0.values.sortedWith(compareByDescending<Member> { it.host })
    }

    // ---------------------------------------------------------------- sending

    @Volatile private var lastSentAt = 0L

    /**
     * Messages sent through the relay today. It allows about 250 a day per internet connection, and
     * Sync uses it too, so Jam stops at 160 for the two together (Sync can still go on to 200).
     */
    @Synchronized
    private fun budget(essential: Boolean): Boolean {
        val day = System.currentTimeMillis() / 86_400_000
        if (prefs.getLong("budget_day", -1) != day) prefs.edit().putLong("budget_day", day).putInt("budget", 0).apply()
        val used = prefs.getInt("budget", 0)
        val sync = if (::syncPrefs.isInitialized && syncPrefs.getLong("budget_day", -1) == day) syncPrefs.getInt("budget", 0) else 0
        if (used + sync >= (if (essential) 160 else 110)) return false
        prefs.edit().putInt("budget", used + 1).apply()
        return true
    }

    internal fun seal(o: JSONObject, k: SecretKeySpec? = key): String {
        k ?: throw IllegalStateException("not in a jam")
        val zipped = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(o.toString().toByteArray()) } }.toByteArray()
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(zipped))
    }

    internal fun open(text: String, k: SecretKeySpec? = key): JSONObject? = runCatching {
        k ?: return null
        val raw = Base64.getDecoder().decode(text.trim())
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, 12)) }
        JSONObject(GZIPInputStream(ByteArrayInputStream(c.doFinal(raw, 12, raw.size - 12))).readBytes().decodeToString())
    }.getOrNull()

    private fun stamp(o: JSONObject): JSONObject {
        val m = me(isHost)
        return o.put("v", 1).put("d", myId).put("n", m.name).put("dn", m.device).put("k", m.kind).put("ph", m.photo ?: "")
            .put("ts", JamClock.now()).put("mid", java.lang.Long.toHexString(random.nextLong()))
    }

    /** The relay said "full for today": don't ask again until then (elapsed ms). */
    @Volatile private var relayFullUntil = 0L
    const val RELAY_FULL = "Can't send to the Jam right now: the free relay it uses has reached today's message limit on this internet connection."

    /** The relay's address. */
    internal val relay: String get() = RELAY

    /**
     * Blocking. Signs it with who's sending, and sends it: straight to everyone connected across the
     * Wi-Fi, and through the relay when [relay] (someone isn't connected straight).
     */
    private fun send(o: JSONObject, essential: Boolean = false, relay: Boolean = true, lan: List<JamLan.Link> = lanTargets()): Boolean {
        val t = topic ?: return false
        val k = key ?: return false
        val st = stamp(o)
        var ok = false
        if (lan.isNotEmpty()) {
            val body = seal(st, k)
            for (l in lan) if (l.send(body)) ok = true
            if (ok) lastLanAt = elapsed()
        }
        if (relay) {
            android.util.Log.i("Glasswave", "Jam: \"${o.optString("ty")}\" through the relay (${if (lan.isEmpty()) "no one connected straight" else "someone isn't"})")
            ok = post(t, k, st, essential) || ok
        }
        return ok
    }

    /** Everyone this device is connected straight to, for this Jam. */
    private fun lanTargets(): List<JamLan.Link> = if (role == Role.HOST) links.values.toList() else listOfNotNull(hostLink)

    /** Whether a message now needs the relay: someone in the Jam isn't connected straight. */
    private fun viaRelay(): Boolean = when (role) {
        Role.HOST -> anyRemote()
        Role.MEMBER -> hostLink == null
        Role.NONE -> false
    }

    /** The host: someone in the Jam is only reachable through the relay. */
    private fun anyRemote(): Boolean = members0.values.any { !it.host && links[it.id] == null }

    /** Blocking. Seals [o] with [k] and posts it to relay channel [t] (counted against the day's messages). */
    internal fun post(t: String, k: SecretKeySpec, o: JSONObject, essential: Boolean = false): Boolean {
        if (elapsed() < relayFullUntil) return false
        val body = seal(o, k)
        if (body.length > 4000) return false
        if (!budget(essential)) {
            main.post { if (inJam) status = "Jam has reached today's message limit. Try again tomorrow." }
            return false
        }
        val req = Request.Builder().url("$RELAY/$t").post(body.toRequestBody("text/plain".toMediaType())).header("Priority", "1").build()
        val ok = runCatching {
            YtRepo.http.newCall(req).execute().use {
                if (it.code == 429) {
                    // Full (for today, or for a moment): wait a couple of minutes before trying again.
                    relayFullUntil = elapsed() + 2 * 60_000
                    main.post { if (inJam) status = RELAY_FULL }
                }
                it.isSuccessful
            }
        }.getOrDefault(false)
        if (ok) {
            lastSentAt = elapsed()
            lastRelayAt = elapsed()
            main.post { if (status == RELAY_FULL) status = null }
        }
        return ok
    }

    /**
     * Sends on the Jam's thread. Who it goes to is decided now (on the main thread): leaving clears
     * the connections straight after, and the goodbye must still reach them.
     */
    private fun sendLater(
        o: () -> JSONObject?, essential: Boolean = false, relay: Boolean = viaRelay(),
        lan: List<JamLan.Link> = lanTargets(), done: ((Boolean) -> Unit)? = null,
    ) {
        if (key == null) return
        io.execute {
            val ok = o()?.let { runCatching { send(it, essential, relay, lan) }.getOrDefault(false) } ?: false
            done?.let { main.post { it(ok) } }
        }
    }

    /** Straight across the Wi-Fi only (free, so as often as needed). */
    private fun sendLan(o: JSONObject) = sendLater({ o }, relay = false)

    /** Sends on one connection only (before it's known to be the Jam's). */
    private fun sendOn(l: JamLan.Link, o: JSONObject) {
        io.execute { val k = key ?: return@execute; runCatching { l.send(seal(stamp(o), k)) } }
    }

    // Songs on the wire: [address, title, artist, seconds, cover, added by]
    private fun trackJson(t: Track, thumb: Boolean = true): JSONArray {
        // A song stored on this device can't play elsewhere: the others find it on YouTube Music instead.
        val local = JamPlatform.isLocalSong(t.url)
        val url = if (local) matchUrl(t.title, t.artist, t.seconds) else t.url
        return JSONArray().put(url).put(t.title).put(t.artist).put(t.seconds).put(if (local || !thumb) "" else t.thumb ?: "")
            .put(addedBy[t.url] ?: "")
    }

    private fun trackOf(a: JSONArray?): Track? = a?.optString(0)?.takeIf { it.isNotEmpty() }?.let {
        Track(it, a.optString(1), a.optString(2), a.optLong(3), a.optString(4).ifEmpty { null })
    }

    // ---------------------------------------------------------------- receiving

    private val seenMids = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Not seen before (the same message can come both straight and through the relay). */
    private fun fresh(o: JSONObject): Boolean {
        val mid = o.optString("mid")
        if (mid.isEmpty()) return true
        if (seenMids.size > 5_000) seenMids.clear()
        return seenMids.add(mid)
    }

    /** Keeps a connection to the Jam's channel open, reconnecting when it drops. */
    private fun listen(backlog: String?) {
        val gen = generation
        listener?.interrupt()
        listener = Thread({
            val client = YtRepo.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
            var wait = 2_000L
            var lostAt = 0L
            var lostWall = 0L
            while (!Thread.currentThread().isInterrupted && gen == generation) {
                val t = topic ?: return@Thread
                // After a drop: from the last message seen (or from just before the drop).
                val since = lastMessageId ?: (if (lostWall > 0) (lostWall / 1000 - 10).toString() else backlog)
                try {
                    val req = Request.Builder().url("$RELAY/$t/json" + (since?.let { "?since=$it" } ?: "")).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("relay ${resp.code}")
                        val away = if (lostAt > 0) elapsed() - lostAt else 0L
                        lostAt = 0L; lostWall = 0L
                        main.post { if (gen == generation) onConnected(away) }
                        wait = 2_000L
                        val src = resp.body?.source() ?: return@use
                        while (!Thread.currentThread().isInterrupted && gen == generation) {
                            val line = src.readUtf8Line() ?: break
                            val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (ev.optString("event") != "message") continue
                            lastMessageId = ev.optString("id").ifEmpty { lastMessageId }
                            val o = open(ev.optString("message")) ?: continue
                            if (!fresh(o)) continue
                            val relayAt = ev.optLong("time") * 1000
                            main.post { if (gen == generation) runCatching { handle(o, relayAt) } }
                        }
                    }
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                }
                if (lostAt == 0L) { lostAt = elapsed(); lostWall = System.currentTimeMillis() }
                main.post { if (gen == generation) connected = false }
                try { Thread.sleep(wait) } catch (e: InterruptedException) { return@Thread }
                wait = minOf(wait * 2, 30_000L)
            }
        }, "Glasswave jam listener").apply { isDaemon = true; start() }
    }

    private var everConnected = false

    private fun onConnected(awayMs: Long) {
        connected = true
        if (status?.startsWith("Reconnecting") == true) status = null
        // Back on the relay after a while, and not connected straight: say hello again (the first
        // hello waits a moment for the Wi-Fi, see memberTick).
        if (role == Role.MEMBER && hostLink == null && relayJoinSent && (awayMs > 30_000 || state == null)) {
            sendLater({ JSONObject().put("ty", "join") }, essential = true, relay = true)
        }
        everConnected = true
    }

    private fun handle(o: JSONObject, relayAt: Long, lan: Boolean = false) {
        val from = o.optString("d")
        if (from.isEmpty() || from == myId) return
        val sentAt = o.optLong("ts")
        // How long ago it was said (on the shared clock; the relay's if the sender's looks wrong).
        val age = (JamClock.now() - sentAt).let { if (sentAt <= 0 || it < -60_000) JamClock.now() - relayAt else it }.coerceAtLeast(0)
        val ty = o.optString("ty")
        when (role) {
            Role.HOST -> hostHandle(o, from, ty, age, lan)
            Role.MEMBER -> memberHandle(o, from, ty, age)
            Role.NONE -> {}
        }
    }

    // ---------------------------------------------------------------- the host

    private data class Sent(val url: String?, val playing: Boolean, val posMs: Long, val atElapsed: Long, val upKey: String, val membersKey: String)

    private var hostState: Sent? = null
    /** When the host should say how things are (0: nothing to say). */
    private var hostDirtyAt = 0L
    private var hostSending = false
    private var trackSeenAt = 0L
    private var trackSeen: String? = null
    private val acked = ArrayDeque<String>()
    /** Requests already done, so a repeat delivery isn't done twice. */
    private val doneRids = HashSet<String>()

    private fun markHostDirty(delayMs: Long = 350) {
        val at = elapsed() + delayMs
        if (hostDirtyAt == 0L || at < hostDirtyAt) hostDirtyAt = at
    }

    private fun hostHandle(o: JSONObject, from: String, ty: String, age: Long, lan: Boolean) {
        if (from in banned) {
            if (ty == "join" || ty == "rq") {
                sendLater({ JSONObject().put("ty", "kick").put("to", from) }, relay = !lan)
                dropLink(from)
            }
            return
        }
        val name = o.optString("n").ifEmpty { "Someone" }
        fun heard() {
            val known = members0[from]
            val direct = lan || links[from] != null
            members0[from] = Member(from, name, o.optString("dn"), o.optString("k"), o.optString("ph").ifEmpty { null }, false, elapsed(), direct)
            if (!direct) everRemote = true
            if (known != null && known.lan != direct) publishMembers()
            if (known == null) {
                publishMembers()
                markHostDirty(0)
                if (age < 120_000) Menus.say("$name joined the Jam")
            } else if (known.name != name) publishMembers()
        }
        when (ty) {
            "join" -> { if (age > 15 * 60_000) return; heard(); markHostDirty(0) }
            "here" -> { if (age > 15 * 60_000) return; heard() }
            // How far apart the clocks are (straight across the Wi-Fi): the answer goes straight back.
            "tq" -> links[from]?.let { l -> sendOn(l, JSONObject().put("ty", "ta").put("to", from).put("t0", o.optLong("t0")).put("t1", JamClock.now())) }
            "bye" -> {
                members0.remove(from)?.let { if (age < 120_000) Menus.say("${it.name} left the Jam") }
                dropLink(from)
                publishMembers()
                markHostDirty(0)
            }
            "rq" -> {
                val rid = o.optString("rid")
                if (age > 60_000 || rid.isEmpty() || !doneRids.add(rid)) return
                heard()
                hostDo(o, name, age)
                acked.addLast(rid)
                while (acked.size > 12) acked.removeFirst()
                markHostDirty()
            }
        }
    }

    /** Does what a member asked, on the host's player (everyone else follows it). */
    private fun hostDo(o: JSONObject, name: String, age: Long) {
        val p = player ?: return
        val ms = o.optLong("ms")
        val tracks = o.optJSONArray("tr")?.let { a -> (0 until a.length()).mapNotNull { trackOf(a.optJSONArray(it)) } }.orEmpty()
        when (o.optString("c")) {
            "play" -> {
                p.play()
                // Start from where they pressed play, as it is now.
                if (kotlin.math.abs(p.positionMs() - ms) < 5_000) p.seekTo(ms + age.coerceAtMost(3_000))
            }
            "pause" -> {
                p.pause()
                // Pause right where they paused, so everyone stops on the same word.
                if (kotlin.math.abs(p.positionMs() - ms) < 5_000) p.seekTo(ms)
            }
            "seek" -> p.seekTo(ms + if (p.wantsToPlay()) age.coerceAtMost(3_000) else 0)
            "next" -> p.next()
            "prev" -> p.previous()
            "add" -> {
                addedBy = addedBy + tracks.associate { it.url to name }
                tracks.forEach { p.addToQueue(it) }
                if (tracks.size == 1) Menus.say("$name added “${tracks[0].title}”")
                else if (tracks.size > 1) Menus.say("$name added ${tracks.size} songs")
            }
            "playnow" -> tracks.firstOrNull()?.let {
                addedBy = addedBy + (it.url to name)
                p.playNow(it)
                Menus.say("$name played “${it.title}”")
            }
        }
    }

    private fun hostTick() {
        val remote = anyRemote()
        if (!connected && everConnected && remote) status = "Reconnecting…" else if (status == "Reconnecting…") status = null
        val p = player ?: return
        val now = elapsed()
        // A direct connection that's gone quiet (they check in every 10 seconds) has dropped.
        links.values.filter { now - it.heardAt > 35_000 }.forEach { it.close() }
        // People who've gone: across the Wi-Fi, 3 minutes after their connection dropped (unless
        // they're back through the relay); through the relay, after an hour without a word.
        val gone = members0.values.filter { !it.host && links[it.id] == null && now - it.heardAt > (if (it.lan) 3 * 60_000L else 60 * 60_000L) }
        if (gone.isNotEmpty()) { gone.forEach { members0.remove(it.id) }; publishMembers(); markHostDirty(0) }
        if (members0.size < 2) { hostDirtyAt = 0L; hostState = null; return } // nobody to tell
        // Still here, for those connected straight (free).
        if (links.isNotEmpty() && now - lastLanAt > 10_000) { lastLanAt = now; sendLan(JSONObject().put("ty", "pg")) }
        p.normalSpeed()

        val t = p.current()
        val playing = p.wantsToPlay()
        val pos = p.positionMs()
        val up = p.upcoming(40)
        val upKey = up.joinToString("|") { it.url + (addedBy[it.url] ?: "") }
        val membersKey = members0.keys.sorted().joinToString("|") + "|" + members0.values.joinToString { it.name }
        if (t?.url != trackSeen) { trackSeen = t?.url; trackSeenAt = now }
        val last = hostState
        if (last == null || last.url != t?.url || last.playing != playing || last.upKey != upKey || last.membersKey != membersKey) markHostDirty()
        else if (p.sounding()) {
            // Strayed from what it said (it was buffering, or someone moved the slider): say it again.
            val said = if (last.playing) last.posMs + (now - last.atElapsed) else last.posMs
            if (kotlin.math.abs(pos - said) > 1_500) markHostDirty(0)
        }
        // Members on the relay hear from the host every 25 minutes at least, so they know it's still
        // there (each message counts against the relay's daily limit, so no more often than that).
        if (remote && now - lastRelayAt > 25 * 60_000 && now - (last?.atElapsed ?: 0) > 25 * 60_000) markHostDirty(0)

        if (hostDirtyAt == 0L || now < hostDirtyAt || hostSending) return
        // A new song: wait until it's actually playing (or 2.5 s), so the time it gives is right.
        if (t != null && playing && !p.sounding() && now - trackSeenAt < 2_500) return
        hostDirtyAt = 0L
        hostSending = true
        val anchorPos = p.positionMs()
        val anchorAt = JamClock.now()
        val sent = Sent(t?.url, playing, anchorPos, now, upKey, membersKey)
        hostState = sent
        val prevTrack = hostPrev(t)
        val msg = stateMessage(t, prevTrack, up, playing, anchorPos, anchorAt, p.durationMs())
        sendLater({ msg }, essential = true, relay = remote) { ok ->
            hostSending = false
            // Didn't go out (no internet): try again shortly.
            if (!ok && isHost) { hostState = null; markHostDirty(3_000) }
        }
    }

    /** The song before this one, so a member's Previous button works. */
    private var prevSong: Track? = null
    private var prevFor: String? = null
    private var lastCurrent: Track? = null
    private fun hostPrev(t: Track?): Track? {
        if (t?.url != lastCurrent?.url) {
            prevSong = lastCurrent
            prevFor = t?.url
            lastCurrent = t
        }
        return prevSong?.takeIf { prevFor == t?.url }
    }

    private var seq = 0L

    private fun stateMessage(t: Track?, prev: Track?, up: List<Track>, playing: Boolean, pos: Long, at: Long, dur: Long): JSONObject {
        seq = maxOf(seq + 1, JamClock.now())
        val m = JSONArray()
        members.forEach { x ->
            m.put(JSONArray().put(x.id).put(x.name).put(x.device).put(x.kind).put(x.photo ?: "").put(if (x.host) 1 else 0).put((elapsed() - x.heardAt) / 1000).put(if (x.lan) 1 else 0))
        }
        // As many songs from Up next as fit in one message (covers for the first few).
        var n = up.size
        var covers = 12
        while (true) {
            val o = JSONObject().put("ty", "st").put("seq", seq).put("p", playing).put("pos", pos).put("at", at).put("dur", dur)
                .put("m", m).put("ack", JSONArray(acked.toList()))
            t?.let { o.put("tr", trackJson(it)) }
            prev?.let { o.put("pv", trackJson(it, thumb = false)) }
            o.put("up", JSONArray().apply { up.take(n).forEachIndexed { i, x -> put(trackJson(x, thumb = i < covers)) } })
            if (n == 0 || seal(JSONObject(o.toString()).also { stamp(it) }).length <= 3900) return o
            if (covers > 3) covers /= 2 else n = n * 2 / 3
        }
    }

    // ---------------------------------------------------------------- a member

    private class State(
        val seq: Long, val track: Track?, val prev: Track?, val up: List<Track>,
        val playing: Boolean, val posMs: Long, val at: Long, val durMs: Long, val acks: Set<String>,
    ) {
        /** Where the host is in the song now (on the shared clock). */
        fun expected(now: Long = JamClock.now()): Long {
            val p = if (playing) posMs + (now - at) else posMs
            return if (durMs > 0) p.coerceIn(0, durMs) else p.coerceAtLeast(0)
        }
    }

    private class Snapshot(val tracks: List<Track>, val posMs: Long)
    private class Pending(val c: String, val at: Long)

    private var state: State? = null
    private var lastSeq = 0L
    private var hostId: String? = null
    private var hostHeardAt = 0L
    private var joinStarted = 0L
    private var lastPingAt = 0L
    /** Your own music from before joining, given back when you leave. */
    private var snapshot: Snapshot? = null
    private val pending = LinkedHashMap<String, Pending>()
    private val requestedAdds = HashSet<String>()

    // What this player was last set to by the Jam (so the user's own changes stand out).
    private var expTrack: String? = null
    private var expPlaying = false
    private var selfUntil = 0L
    private var loadedAt = 0L
    private var lastPos = 0L
    private var lastDur = 0L
    private var lastTickAt = 0L
    private var lastFix = 0L
    private var checkLagAt = 0L
    /** How far ahead to seek, learned: a seek takes a moment to be heard. */
    private var seekLead = 60L
    /** For the check: how far off this player was at the last tick (ms; + ahead, - behind). */
    @Volatile var lastDrift: Long? = null
        private set

    private fun memberHandle(o: JSONObject, from: String, ty: String, age: Long) {
        when (ty) {
            "st" -> {
                val s = o.optLong("seq")
                if (hostId != null && from != hostId) return
                // From long ago (the host has gone, or will answer our hello anyway).
                if (age > 10 * 60_000 && state == null) return
                if (s <= lastSeq) return
                lastSeq = s
                hostId = from
                hostHeardAt = elapsed()
                val up = o.optJSONArray("up")?.let { a -> (0 until a.length()).mapNotNull { trackOf(a.optJSONArray(it)) } }.orEmpty()
                val acks = o.optJSONArray("ack")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                state = State(
                    s, trackOf(o.optJSONArray("tr")), trackOf(o.optJSONArray("pv")), up,
                    o.optBoolean("p"), o.optLong("pos"), o.optLong("at"), o.optLong("dur"), acks,
                )
                acks.forEach { pending.remove(it) }
                // Who added what, for Up next.
                val by = HashMap<String, String>()
                for (i in 0 until (o.optJSONArray("up")?.length() ?: 0)) {
                    val a = o.optJSONArray("up")!!.optJSONArray(i) ?: continue
                    a.optString(5).takeIf { it.isNotEmpty() }?.let { by[a.optString(0)] = it }
                }
                o.optJSONArray("tr")?.optString(5)?.takeIf { it.isNotEmpty() }?.let { by[o.optJSONArray("tr")!!.optString(0)] = it }
                addedBy = by
                val list = o.optJSONArray("m")
                val now = elapsed()
                val ms = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val a = list!!.optJSONArray(i) ?: return@mapNotNull null
                    Member(a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4).ifEmpty { null }, a.optInt(5) == 1, now - a.optLong(6) * 1000, a.optInt(7) == 1)
                }
                if (ms.isNotEmpty() && ms.none { it.id == myId } && age < 60_000 && (everConnected || hostLink != null) && joinedOnce) {
                    // The host no longer lists us: removed while we were away.
                    return finish("You're no longer in ${hostName}'s Jam")
                }
                if (ms.any { it.id == myId }) joinedOnce = true
                members = ms
                if (status == "Joining…" || status?.startsWith("Lost touch") == true) status = null
                reconcile()
            }
            "pg" -> if (from == hostId) {
                hostHeardAt = elapsed()
                if (status?.startsWith("Lost touch") == true) status = null
            }
            // The host's clock, measured straight across the Wi-Fi (closer than the time servers get).
            "ta" -> if (o.optString("to") == myId && (hostId == null || from == hostId)) {
                val t0 = o.optLong("t0"); val t1 = o.optLong("t1"); val t3 = lineWallAt
                if (t0 > 0 && t3 >= t0) JamClock.lanSample(t3 - t0, t1 - (t0 + t3) / 2)
            }
            "end" -> if (hostId == null || from == hostId) finish(if (state == null) "That Jam has ended" else "$hostName ended the Jam")
            "kick" -> if (o.optString("to") == myId && (hostId == null || from == hostId)) finish("$hostName removed you from the Jam")
        }
    }
    private var joinedOnce = false

    /** Asks the host to do something. Done here at once too; the host's next word confirms it. */
    private fun request(c: String, ms: Long = 0, tracks: List<Track> = emptyList()) {
        val rid = java.lang.Long.toHexString(random.nextLong())
        pending[rid] = Pending(c, elapsed())
        val to = hostId
        sendLater({
            JSONObject().put("ty", "rq").put("to", to).put("c", c).put("ms", ms).put("rid", rid)
                .put("tr", JSONArray().apply { tracks.forEach { put(trackJson(it)) } })
        }, essential = true)
    }

    /** A request of these kinds is still on its way (the host hasn't shown it yet). */
    private fun waiting(vararg kinds: String): Boolean {
        val now = elapsed()
        pending.entries.removeAll { now - it.value.at > 8_000 }
        return pending.values.any { it.c in kinds && now - it.at < (if (it.c in TRACK_KINDS) 8_000 else 4_000) }
    }
    private val TRACK_KINDS = setOf("next", "prev", "playnow")

    private fun memberTick() {
        val now = elapsed()
        val link = hostLink
        if (link == null) {
            // Looking for the host on this Wi-Fi: often at first (and after losing it), then now and then.
            val lately = now - joinStarted < 30_000 || (lanLostAt > 0 && now - lanLostAt < 30_000)
            if (now - lastProbeAt > (if (lately) 2_000 else 10_000)) probe()
            // Connected, but nothing that opens came back: not the host after all.
            if (candidates.isNotEmpty() && now - candidateAt > 6_000) { candidates.forEach { it.close() }; candidates.clear() }
            // Not here: through the relay, after a few seconds of looking.
            val since = maxOf(joinStarted, lanLostAt)
            if (!relayJoinSent && connected && now - since > (if (JamLan.off) 0 else 3_000)) {
                relayJoinSent = true
                sendLater({ JSONObject().put("ty", "join") }, essential = true, relay = true)
            }
        } else {
            // Gone quiet (the host checks in every 10 seconds): the connection has dropped.
            if (now - link.heardAt > 35_000) link.close()
            if (now - lastLanAt > 10_000) { lastLanAt = now; sendLan(JSONObject().put("ty", "here")) }
            if (now - lastTimeAt > 2 * 60_000) askTime()
        }
        val s = state
        if (s == null) {
            if (now - joinStarted > 15_000) {
                finish(if (status == RELAY_FULL) RELAY_FULL else "Couldn't find that Jam. Check the code, and that the host's Glasswave is open.")
            }
            return
        }
        // The host has gone quiet: keep playing what's queued, and give up after a while. (Over the
        // relay the host only checks in every 25 minutes; straight across the Wi-Fi, every 10 seconds.)
        val quiet = now - hostHeardAt
        val lostLan = link == null && lanLostAt > 0 && hostHeardAt < lanLostAt
        if (quiet > 70 * 60_000) return finish("Lost touch with $hostName, so you left the Jam")
        if (quiet > 30 * 60_000 || (lostLan && quiet > 60_000)) status = "Lost touch with $hostName. Playing on…"
        else if (!connected && everConnected && link == null) status = "Reconnecting…"
        else if (status == "Reconnecting…") status = null
        // Still here, over the relay (every 25 minutes).
        if (link == null && now - lastPingAt > 25 * 60_000) { lastPingAt = now; sendLater({ JSONObject().put("ty", "here") }) }

        val p = player ?: return
        // Positions mean nothing until the clock is checked (a few seconds at most).
        if (!JamClock.checked && now - joinStarted < 4_000) return
        p.normalSpeed()
        watchUser(p, s)
        reconcile()
    }

    /** Turns what someone did on this player (any button, the notification, media keys) into requests. */
    private fun watchUser(p: Player, s: State) {
        val now = elapsed()
        val t = p.current()
        val wants = p.wantsToPlay()
        val pos = p.positionMs()
        val dur = p.durationMs()
        val dt = now - lastTickAt
        val nearEnd = lastDur > 0 && lastPos > lastDur - 12_000
        if (now >= selfUntil && t != null && expTrack != null) {
            if (t.url != expTrack) {
                when {
                    // The song ended and the next one came on, same as for everyone.
                    t.url == s.up.firstOrNull()?.url && nearEnd -> {}
                    t.url == s.up.firstOrNull()?.url -> request("next")
                    t.url == s.prev?.url -> request("prev")
                    else -> {
                        // Picked a song: it plays for everyone (the rest of its list isn't added).
                        request("playnow", tracks = listOf(t))
                        p.setUpcoming(s.up)
                        selfUntil = now + 1_500
                    }
                }
                expTrack = t.url
                loadedAt = now
            } else if (wants != expPlaying) {
                // Ran out at the very end isn't a pause.
                if (wants || !(lastDur > 0 && lastPos > lastDur - 3_000)) request(if (wants) "play" else "pause", ms = pos)
                expPlaying = wants
            } else if (lastTickAt > 0 && dt < 3_000) {
                val predicted = lastPos + if (wants) dt else 0
                val wrapped = lastDur > 0 && lastPos > lastDur - 4_000 && pos < 4_000
                if (kotlin.math.abs(pos - predicted) > 1_500 && !wrapped) {
                    request("seek", ms = pos)
                    lastFix = now
                }
            }
            // Songs added here ("Add to queue", "Play next") go into everyone's queue.
            val theirs = s.up.mapTo(HashSet()) { it.url }
            val added = p.upcoming(60).filter { it.url !in theirs && it.url != t.url && it.url !in requestedAdds }
            if (added.isNotEmpty()) {
                requestedAdds += added.map { it.url }
                request("add", tracks = added)
            }
        }
        lastPos = pos; lastDur = dur; lastTickAt = now
    }

    /** Makes this player match the host's: song, play/pause, spot, and Up next. */
    private fun reconcile() {
        val p = player ?: return
        val s = state ?: return
        val now = elapsed()
        // Positions mean nothing until the clock is checked (a few seconds at most).
        if (!JamClock.checked && now - joinStarted < 4_000) return
        if (snapshot == null) {
            // Your own music, kept for when you leave.
            val cur = p.current()
            snapshot = Snapshot(listOfNotNull(cur) + p.upcoming(200), p.positionMs())
        }
        val want = s.track
        val t = p.current()
        if (want == null) {
            // The host has nothing on.
            if (p.wantsToPlay() && !waiting("play")) { p.pause(); expPlaying = false; selfUntil = now + 1_000 }
            return
        }
        val expected = s.expected()
        val dur = if (s.durMs > 0) s.durMs else p.durationMs()
        if (t?.url != want.url) {
            if (waiting("next", "prev", "playnow")) return
            // Moving on together: this one is a moment ahead or behind on the change of song.
            val aheadOnNext = t != null && t.url == s.up.firstOrNull()?.url && dur > 0 && expected > dur - 12_000
            val behindOnPrev = t != null && t.url == s.prev?.url && p.durationMs() > 0 && p.positionMs() > p.durationMs() - 12_000 && expected < 12_000
            if (aheadOnNext || behindOnPrev) return
            val list = listOfNotNull(s.prev) + want + s.up
            p.load(list, if (s.prev != null) 1 else 0, expected, s.playing)
            expTrack = want.url
            expPlaying = s.playing
            requestedAdds.clear()
            selfUntil = now + 3_000
            loadedAt = now
            lastFix = now
            return
        }
        expTrack = want.url
        if (p.wantsToPlay() != s.playing && !waiting("play", "pause")) {
            val atEnd = p.durationMs() > 0 && p.positionMs() > p.durationMs() - 1_500
            if (!s.playing) p.pause()
            else if (!atEnd) {
                // Start from where the host is now, not where this one stopped.
                if (kotlin.math.abs(p.positionMs() - expected) > 150) p.seekTo(expected + seekLead)
                p.play()
            }
            expPlaying = s.playing
            selfUntil = now + 1_200
        }
        if (!waiting("seek", "play", "pause")) fixDrift(p, s, now)
        // Up next: the host's.
        if (now >= selfUntil && !waiting("add", "playnow")) {
            val mine = p.upcoming(s.up.size + 5).map { it.url }
            if (mine != s.up.map { it.url }) {
                p.setUpcoming(s.up)
                requestedAdds.retainAll(s.up.mapTo(HashSet()) { it.url })
                selfUntil = now + 600
            }
        }
    }

    /** Keeps this player within a fifth of a second of the host. */
    private fun fixDrift(p: Player, s: State, now: Long) {
        val expected = s.expected()
        val pos = p.positionMs()
        val drift = pos - expected
        lastDrift = if (s.playing && p.sounding()) drift else if (!s.playing) drift else null
        if (!s.playing) {
            if (!p.wantsToPlay() && kotlin.math.abs(drift) > 200 && now - lastFix > 1_000) {
                p.seekTo(expected); lastFix = now; selfUntil = now + 800
            }
            return
        }
        if (!p.sounding() || now - loadedAt < 1_500) return
        // After a correction: learn how long a seek takes to be heard.
        if (checkLagAt in 1..now) {
            checkLagAt = 0
            if (kotlin.math.abs(drift) < 1_500) seekLead = (seekLead - drift * 2 / 3).coerceIn(0, 1_500)
        }
        if (kotlin.math.abs(drift) > 200 && now - lastFix > 3_000) {
            p.seekTo(expected + seekLead)
            lastFix = now
            checkLagAt = now + 1_200
            selfUntil = now + 1_500
        }
    }

    // ---------------------------------------------------------------- straight across the Wi-Fi

    /** Names this Jam on the local network (worked out from the code; says nothing about it). */
    @Volatile private var lanId: String? = null
    @Volatile private var server: java.net.ServerSocket? = null
    /** The host: direct connections to members, by who they are. */
    private val links = java.util.concurrent.ConcurrentHashMap<String, JamLan.Link>()
    /** A member: the direct connection to the host. */
    @Volatile private var hostLink: JamLan.Link? = null
    /** A member: connections that haven't shown yet that they're the host's. */
    private val candidates = HashSet<JamLan.Link>()
    private var candidateAt = 0L
    private var connecting = false
    private var lastProbeAt = 0L
    @Volatile private var lastLanAt = 0L
    private var lastTimeAt = 0L
    /** When the direct connection to the host was lost (0: it wasn't). */
    private var lanLostAt = 0L
    /** A member has said hello through the relay (the host wasn't found on this Wi-Fi). */
    private var relayJoinSent = false
    /** The last message through the relay (elapsed). */
    @Volatile private var lastRelayAt = 0L
    /** The host: someone has been in this Jam through the relay (so "it's over" goes there too). */
    private var everRemote = false
    /** This device's clock when the line being handled arrived. */
    private var lineWallAt = 0L

    /** A member: connected straight to the host (nothing goes through the relay). */
    val direct: Boolean get() = hostLink != null

    private val linkEvents = object : JamLan.Events {
        override fun line(link: JamLan.Link, text: String, wallAt: Long) = onLine(link, text, wallAt)
        override fun closed(link: JamLan.Link) = onLinkClosed(link)
        override fun accepted(link: JamLan.Link) = onAccepted(link)
    }

    private fun lanStart() {
        if (JamLan.off) return
        JamLan.need("jam", true)
        val gen = generation
        JamLan.serve(linkEvents) { ss -> if (gen == generation && inJam) server = ss else runCatching { ss?.close() } }
    }

    private fun lanStop() {
        val ss = server
        server = null
        val all = links.values.toList() + listOfNotNull(hostLink) + candidates
        links.clear(); hostLink = null; candidates.clear(); connecting = false
        // On the Jam's thread, so the goodbye queued just before goes out first.
        io.execute { all.forEach { it.close() }; runCatching { ss?.close() } }
        main.postDelayed({ if (!inJam) JamLan.need("jam", false) }, 3_000)
        JamClock.lanDone()
    }

    /** The host's details for its "here I am" on the network: which Jam (not its code) and where to connect. */
    internal fun lanFields(o: JSONObject) {
        val j = lanId ?: return
        val ss = server ?: return
        if (isHost) o.put("j", j).put("p", ss.localPort)
    }

    /** A member: "is this Jam's host here?" to everyone on the network. */
    private fun probe() {
        lastProbeAt = elapsed()
        val j = lanId ?: return
        if (JamLan.off || role != Role.MEMBER) return
        JamLan.broadcast(JSONObject().put("gw", "jamq").put("v", 1).put("id", myId).put("j", j))
    }

    /** Closes the direct connection to [id], after anything already queued for it. */
    private fun dropLink(id: String) { io.execute { links.remove(id)?.close() } }

    /** Something heard on the local network (see [JamLan]). */
    internal fun heardLan(o: JSONObject, from: java.net.InetSocketAddress) {
        val j = lanId ?: return
        if (o.optString("j") != j || o.optString("id") == myId) return
        when (o.optString("gw")) {
            // The host's "here I am": connect to it.
            "jam" -> if (role == Role.MEMBER && hostLink == null && !connecting && candidates.isEmpty()) {
                val port = o.optInt("p")
                if (port > 0) connectToHost(from, port)
            }
            // A member looking for this Jam: answer it straight (that gets through a PC's firewall).
            "jamq" -> if (isHost) JamLan.sendTo(JamNearby.beacon(), from)
            // A member that couldn't reach this device: connect to it instead.
            "jamm" -> if (isHost) {
                val port = o.optInt("p")
                if (port > 0) JamLan.connect(from.address, port, linkEvents) { l -> if (l != null && !isHost) l.close() }
            }
        }
    }

    private fun connectToHost(beacon: java.net.InetSocketAddress, port: Int) {
        connecting = true
        val gen = generation
        JamLan.connect(beacon.address, port, linkEvents) { l ->
            connecting = false
            if (gen != generation || role != Role.MEMBER) { l?.close(); return@connect }
            if (l != null) {
                candidates += l
                candidateAt = elapsed()
                sendOn(l, JSONObject().put("ty", "join"))
            } else {
                // Couldn't reach it (usually a PC's firewall): ask it to connect to this device instead.
                val mine = server?.localPort ?: return@connect
                JamLan.sendTo(JSONObject().put("gw", "jamm").put("v", 1).put("id", myId).put("j", lanId).put("p", mine), beacon)
            }
        }
    }

    private fun onAccepted(l: JamLan.Link) {
        when (role) {
            // The host connecting here (see "jamm"): say hello on it.
            Role.MEMBER -> if (hostLink == null) { candidates += l; candidateAt = elapsed(); sendOn(l, JSONObject().put("ty", "join")) } else l.close()
            Role.HOST -> {} // members say who they are in their first message
            Role.NONE -> l.close()
        }
    }

    private fun onLine(l: JamLan.Link, text: String, wallAt: Long) {
        if (!inJam) { l.close(); return }
        // Only people with the code can write anything that opens.
        val o = open(text) ?: run { l.close(); return }
        val from = o.optString("d")
        if (from.isEmpty() || from == myId) return
        when (role) {
            Role.HOST -> if (l.peer != from) {
                l.peer = from
                links.put(from, l)?.takeIf { it !== l }?.close()
            }
            Role.MEMBER -> if (l !== hostLink) {
                val h = hostLink
                if ((h != null && !h.closed) || (hostId != null && from != hostId)) { candidates -= l; l.close(); return }
                useHostLink(l, from)
            }
            Role.NONE -> return
        }
        if (!fresh(o)) return
        lineWallAt = wallAt
        handle(o, System.currentTimeMillis(), lan = true)
    }

    private fun useHostLink(l: JamLan.Link, from: String) {
        hostLink = l
        l.peer = from
        candidates -= l
        candidates.forEach { it.close() }
        candidates.clear()
        lanLostAt = 0L
        relayJoinSent = false
        askTime()
    }

    private fun onLinkClosed(l: JamLan.Link) {
        candidates -= l
        if (l === hostLink) { hostLink = null; lanLostAt = elapsed(); lastProbeAt = 0L }
        l.peer?.let { p -> if (links[p] === l) links.remove(p) }
        if (isHost) publishMembers()
    }

    /** Measures how far this clock is from the host's: a few quick questions across the Wi-Fi. */
    private fun askTime() {
        lastTimeAt = elapsed()
        JamClock.lanRound()
        for (i in 0 until 5) main.postDelayed({
            hostLink?.let { l -> sendOn(l, JSONObject().put("ty", "tq").put("t0", JamClock.wall())) }
        }, i * 200L)
    }

    // ---------------------------------------------------------------- the clock

    private val ticker = object : Runnable {
        override fun run() {
            runCatching {
                when (role) {
                    Role.HOST -> hostTick()
                    Role.MEMBER -> memberTick()
                    Role.NONE -> {}
                }
            }.onFailure { android.util.Log.w("Glasswave", "Jam tick failed", it) }
            if (inJam) {
                // The clock is checked again every 10 minutes.
                if (elapsed() - JamClock.checkedAt > 10 * 60_000) refreshClock()
                main.postDelayed(this, 500)
            }
        }
    }
}

/**
 * The time everyone in a Jam agrees on: this device's clock, corrected by asking time servers
 * (SNTP) how far off it is. Phone and PC clocks can be seconds apart; this gets within a few
 * hundredths of a second.
 */
internal object JamClock {
    /** This clock is this much behind true time (ms). */
    @Volatile var offset = 0L
        private set
    @Volatile var checked = false
        private set
    @Volatile var checkedAt = 0L
    /** Automated checks only: pretend this device's clock is wrong by this much. */
    private val testSkew = System.getProperty("gw.jamClockSkew")?.toLongOrNull() ?: 0L

    fun wall(): Long = System.currentTimeMillis() + testSkew
    fun now(): Long = wall() + offset

    /** The quickest answer from the host across the Wi-Fi so far (ms there and back), while in a Jam. */
    @Volatile private var lanBest = Long.MAX_VALUE

    /**
     * One answer from the host across the Wi-Fi: [rtt] there and back, [off] how far this clock is
     * from the host's. The quickest is the most exact; it then counts instead of the time servers.
     */
    fun lanSample(rtt: Long, off: Long) {
        if (rtt < 0 || rtt > 1_000 || rtt > lanBest) return
        lanBest = rtt
        offset = off
        checked = true
    }

    /** A new round of questions: lets a slightly slower answer count, as clocks drift apart over time. */
    fun lanRound() { if (lanBest != Long.MAX_VALUE) lanBest += 15 }

    /** Out of the Jam: the time servers count again. */
    fun lanDone() { lanBest = Long.MAX_VALUE }

    /** Blocking: the best of a few answers (the quickest round trip is the most exact). */
    fun refresh() {
        checkedAt = SystemClock.elapsedRealtime()
        var best: Pair<Long, Long>? = null
        for (host in listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")) {
            repeat(2) { sample(host)?.let { s -> if (best == null || s.first < best!!.first) best = s } }
            if ((best?.first ?: Long.MAX_VALUE) < 60) break
        }
        // Measured against the host across the Wi-Fi: that's closer, keep it.
        if (lanBest != Long.MAX_VALUE) return
        best?.let { offset = it.second; checked = true }
    }

    /** One SNTP question: (round trip, offset), in ms. */
    private fun sample(host: String): Pair<Long, Long>? = runCatching {
        DatagramSocket().use { sock ->
            sock.soTimeout = 1_500
            val out = ByteArray(48).also { it[0] = 0x1B } // version 3, client
            val t0 = wall()
            putTime(out, 40, t0)
            sock.send(DatagramPacket(out, out.size, InetAddress.getByName(host), 123))
            val back = DatagramPacket(ByteArray(48), 48)
            sock.receive(back)
            val t3 = wall()
            val d = back.data
            val stratum = d[1].toInt() and 0xff
            if (stratum == 0 || stratum > 15 || (d[0].toInt() and 0xC0) == 0xC0) return null
            val t1 = getTime(d, 32)
            val t2 = getTime(d, 40)
            ((t3 - t0) - (t2 - t1)) to (((t1 - t0) + (t2 - t3)) / 2)
        }
    }.getOrNull()

    private const val FROM_1900 = 2_208_988_800L

    private fun getTime(b: ByteArray, at: Int): Long {
        var sec = 0L; var frac = 0L
        for (i in 0..3) sec = (sec shl 8) or (b[at + i].toLong() and 0xff)
        for (i in 4..7) frac = (frac shl 8) or (b[at + i].toLong() and 0xff)
        return (sec - FROM_1900) * 1000 + frac * 1000 / 0x1_0000_0000L
    }

    private fun putTime(b: ByteArray, at: Int, ms: Long) {
        val sec = ms / 1000 + FROM_1900
        val frac = (ms % 1000) * 0x1_0000_0000L / 1000
        for (i in 0..3) b[at + i] = (sec shr (24 - 8 * i)).toByte()
        for (i in 0..3) b[at + 4 + i] = (frac shr (24 - 8 * i)).toByte()
    }
}
