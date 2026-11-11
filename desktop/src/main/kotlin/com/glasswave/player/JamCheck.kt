// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * For automated checks only: two pretend devices in a Jam, each in its own process with its own
 * sandbox data: "host" and "guest", whose clock is set 7 seconds wrong on purpose. Their players are
 * pretend too (no sound) but behave like real ones: songs take a moment to load, seeks a moment to
 * be heard, and a song that ends moves on to the next by itself.
 *
 * Steps: start; the guest finds the Jam nearby (in its area, from the next square over, and on the
 * Wi-Fi), it disappears when the host turns that off; the QR code reads back as the invite; the guest
 * joins from nearby (no code typed); pause (guest), play (host), seek (guest), skip (host), a song
 * ending, queue add (guest), skip (guest), leave, join again and be removed, join again and the host
 * ends it. After
 * each, both must be on the same song, both playing or both paused, and within 300 ms of each other
 * for 2 seconds in a row.  ./gradlew -p desktop jamCheck   (-Plocal: stand-in relay, nothing sent out)
 *
 * By default the two find each other on this computer's network and talk straight (as on one Wi-Fi):
 * then nothing of the Jam may go through the relay. -PnoLan: as if they were in different places,
 * so everything goes through the relay (and the Wi-Fi steps are skipped).
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "device") return device(args[1])
    val base = File(System.getProperty("gw.jamDir") ?: error("gw.jamDir"))
    val relay = if (System.getProperty("gw.jamLocal") != null) LocalRelay.start() else null
    val noLan = System.getProperty("gw.jamNoLan") != null
    val java = ProcessHandle.current().info().command().get()
    val skew = mapOf("host" to 0L, "guest" to 7_000L)
    // Pretend places: the guest is one area square up and to the right of the host (about 5 km away).
    val place = mapOf("host" to "52.5200,13.4050", "guest" to "52.5640,13.4490")
    val kids = listOf("host", "guest").associateWith { role ->
        val dir = File(base, role).apply { deleteRecursively(); mkdirs() }
        val props = mutableListOf("-Dgw.noCheckIn=1", "-Dgw.jamName=${role.replaceFirstChar { it.uppercase() }}", "-Dgw.jamClockSkew=${skew[role]}", "-Dgw.jamLocation=${place[role]}")
        relay?.let { props += "-Dgw.jamRelay=$it" }
        if (noLan) props += "-Dgw.jamNoLan=1"
        ProcessBuilder(listOf(java) + props + listOf("-cp", System.getProperty("java.class.path"), "com.glasswave.player.JamCheckKt", "device", role))
            .apply { environment()["APPDATA"] = dir.absolutePath; environment()["LOCALAPPDATA"] = dir.absolutePath }
            .redirectErrorStream(true).start()
    }
    val check = Check(kids, noLan)
    var ok = runCatching { check.run() }.onFailure { println("CHECK STOPPED: $it") }.getOrDefault(false)
    if (relay != null) {
        val jam = LocalRelay.posts.filterKeys { it.startsWith("gwj-") }.values.sum()
        val near = LocalRelay.posts.filterKeys { it.startsWith("gwn-") }.values.sum()
        println("relay: $jam Jam message(s), $near nearby listing(s) went through the relay")
        if (!noLan) {
            if (jam == 0) println("PASS  on the same network nothing of the Jam went through the relay")
            else { println("FAIL  on the same network $jam Jam message(s) went through the relay"); ok = false }
        }
    }
    kids.values.forEach { runCatching { it.outputStream.write("quit\n".toByteArray()); it.outputStream.flush() } }
    Thread.sleep(1500)
    kids.values.forEach { it.destroyForcibly() }
    println(if (ok) "JAM CHECK PASSED" else "JAM CHECK FAILED")
    System.exit(if (ok) 0 else 1)
}

/** What a device last said about its player. */
private data class Seen(
    val at: Long, val track: String, val pos: Long, val wants: Boolean, val sounding: Boolean,
    val role: String, val people: Int, val up: List<String>, val loads: Int,
)

private class Check(val kids: Map<String, Process>, val noLan: Boolean) {
    val seen = ConcurrentHashMap<String, Seen>()
    val lines = ConcurrentHashMap<String, MutableList<String>>()
    @Volatile var code: String? = null
    /** Jams the guest sees nearby: "CODE/wifi" or "CODE/area". */
    @Volatile var nearby: List<String> = emptyList()
    @Volatile var qr: String? = null
    @Volatile var link: String? = null
    /** Each one's last word on whether it's connected straight to the host. */
    val direct = ConcurrentHashMap<String, Boolean>()
    var failures = 0

    init {
        for ((name, p) in kids) Thread {
            p.inputStream.bufferedReader().forEachLine { l ->
                if (l.startsWith("POS\t")) {
                    val f = l.split('\t').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
                    seen[name] = Seen(
                        f["real"]!!.toLong(), f["tr"]!!, f["pos"]!!.toLong(), f["want"] == "true", f["snd"] == "true",
                        f["role"]!!, f["n"]!!.toInt(), f["up"]!!.split('|').filter { it.isNotEmpty() }, f["loads"]!!.toInt(),
                    )
                } else {
                    if (l.startsWith("CODE ")) code = l.substringAfter("CODE ").trim()
                    if (l.startsWith("NEARBY ") && name == "guest") nearby = l.substringAfter("NEARBY ").split(',').filter { it.contains('/') }
                    if (l.startsWith("QR ")) qr = l.substringAfter("QR ")
                    if (l.startsWith("LINK ")) link = l.substringAfter("LINK ")
                    if (l.startsWith("DIRECT ")) direct[name] = l.substringAfter("DIRECT ").trim() == "true"
                    println("  [$name] $l")
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun tell(who: String, cmd: String) {
        println("> $who: $cmd")
        kids[who]!!.outputStream.apply { write("$cmd\n".toByteArray()); flush() }
    }

    /** Where each would be right now (from what it last said). */
    fun nowOf(s: Seen, t: Long) = if (s.wants && s.sounding) s.pos + (t - s.at) else s.pos

    /** Both on the same song, both playing or both paused, close together: the gap in ms, else null. */
    fun gap(): Long? {
        val h = seen["host"] ?: return null
        val g = seen["guest"] ?: return null
        val t = System.currentTimeMillis()
        if (t - h.at > 700 || t - g.at > 700) return null
        if (h.track != g.track || h.wants != g.wants || h.role == "NONE" || g.role == "NONE") return null
        if (h.wants && (!h.sounding || !g.sounding)) return null
        return nowOf(g, t) - nowOf(h, t)
    }

    fun state(): String = listOf("host", "guest").joinToString("  |  ") { n ->
        seen[n]?.let { s -> "$n ${s.role} ${s.track} @${s.pos / 100 / 10.0}s ${if (s.wants) "playing" else "paused"}${if (s.wants && !s.sounding) " (loading)" else ""} people=${s.people} up=${s.up.take(3)}" } ?: "$n ?"
    }

    /** Waits until both are together (and [also] holds) for 2 s straight. */
    fun together(label: String, seconds: Int, also: () -> Boolean = { true }): Boolean {
        val start = System.currentTimeMillis()
        var since = 0L
        var worst = 0L
        while (System.currentTimeMillis() - start < seconds * 1000L) {
            val g = gap()
            if (g != null && kotlin.math.abs(g) <= 300 && also()) {
                if (since == 0L) { since = System.currentTimeMillis(); worst = 0 }
                worst = maxOf(worst, kotlin.math.abs(g))
                if (System.currentTimeMillis() - since >= 2_000) {
                    println("PASS  $label: together after ${(since - start) / 100 / 10.0}s, at most ${worst} ms apart. ${state()}")
                    return true
                }
            } else since = 0L
            Thread.sleep(100)
        }
        failures++
        println("FAIL  $label: not together within ${seconds}s (gap ${gap()} ms). ${state()}")
        return false
    }

    fun waitFor(label: String, seconds: Int, cond: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < seconds * 1000L) {
            if (cond()) { println("PASS  $label (${(System.currentTimeMillis() - start) / 100 / 10.0}s). ${state()}"); return true }
            Thread.sleep(100)
        }
        failures++
        println("FAIL  $label: not within ${seconds}s. ${state()}")
        return false
    }

    fun h() = seen["host"]!!
    fun g() = seen["guest"]!!

    fun run(): Boolean {
        waitFor("both devices up", 30) { seen["host"] != null && seen["guest"] != null }
        // The guest is listening to its own music before joining.
        tell("guest", "own")
        tell("host", "start")
        waitFor("host has a code", 10) { code != null }
        tell("host", "queue")
        val raw = code!!.replace("-", "")

        // Nearby: first only by area (the host is in the next square), then on the Wi-Fi too.
        tell("guest", "look area")
        waitFor("nearby: the guest finds the Jam in its area (the host is one square over)", 40) { "$raw/area" in nearby }
        if (!noLan) {
            tell("guest", "look both")
            waitFor("nearby: and on this Wi-Fi", 20) { "$raw/wifi" in nearby }
        }
        tell("host", "nearby off")
        waitFor("host turns off Show to people nearby: the Jam disappears for the guest", 20) { nearby.none { it.startsWith(raw) } }
        tell("host", "nearby on")
        waitFor("host turns it back on: it shows again", 30) { nearby.any { it.startsWith(raw) } }

        // The QR code reads back as the invite, and the code in it is the Jam's.
        tell("host", "qr")
        waitFor("the QR code reads back as the invite link, with this Jam's code", 10) {
            qr != null && qr == link && Jam.parseCode(qr ?: "") == raw
        }

        // Join from nearby: no code typed.
        tell("guest", "joinnearby $raw")
        together("join from nearby: the guest hears the host's song at the host's spot", 30) { h().people == 2 && g().people == 2 }
        if (!noLan) waitFor("the guest is connected straight to the host (no relay)", 10) { direct["guest"] == true }
        tell("host", "clock")
        tell("guest", "clock")

        // For its first 3 seconds after joining, the guest's player takes any change as its own loading.
        Thread.sleep(3_000)
        tell("guest", "pause")
        together("pause (guest)", 12) { !h().wants && !g().wants }
        Thread.sleep(1_500)
        tell("host", "play")
        together("play (host)", 12) { h().wants && g().wants }

        tell("guest", "seek 120")
        together("seek to 2:00 (guest)", 12) { h().pos in 118_000..140_000 }

        tell("host", "next")
        together("skip (host)", 15) { h().track == "Second" }
        val loadsBefore = g().loads
        tell("host", "seek 15")
        together("seek near the end of a short song (host)", 12) { h().track == "Second" && h().pos > 14_000 }
        together("the song ends and the next starts, for both", 30) { h().track == "Third" && h().pos > 1_000 }
        println("      (the guest's player loaded ${g().loads - loadsBefore} new song(s) for that: 0 means it moved on by itself, like the host)")

        tell("guest", "add Added-By-Guest")
        together("queue add (guest): in the host's Up next, and the guest's matches", 15) {
            "Added-By-Guest" in h().up && h().up == g().up
        }
        tell("guest", "next")
        together("skip (guest)", 15) { h().track == "Added-By-Guest" }

        tell("guest", "leave")
        waitFor("leave: the host sees one person, the guest is back on its own music", 15) {
            h().people == 1 && g().role == "NONE" && g().track == "Guests-Own-Song"
        }

        tell("guest", "join $code")
        together("join again", 30) { h().people == 2 }
        tell("host", "remove")
        waitFor("host removes the guest: the guest is back on its own music", 15) {
            h().people == 1 && g().role == "NONE" && g().track == "Guests-Own-Song"
        }

        // Removed means removed: the same code doesn't let them back in.
        tell("guest", "join $code")
        Thread.sleep(4_000)
        waitFor("a removed guest can't come back with the same code", 20) { h().people == 1 && g().role == "NONE" }

        tell("host", "end")
        waitFor("host ends that Jam", 10) { h().role == "NONE" }
        code = null
        tell("host", "start")
        waitFor("host starts a new Jam", 10) { code != null }
        tell("guest", "join $code")
        together("guest joins the new Jam", 30) { h().people == 2 }
        tell("host", "end")
        waitFor("host ends the Jam: both out, the guest on its own music", 15) {
            h().role == "NONE" && g().role == "NONE" && g().track == "Guests-Own-Song"
        }
        val last = code!!.replace("-", "")
        waitFor("an ended Jam disappears from nearby", 20) { nearby.none { it.startsWith(last) } }
        return failures == 0
    }
}

/** A pretend player: no sound, but loading, seeking and songs ending behave like a real one's. */
private class JamFakePlayer : Jam.Player {
    private var list = mutableListOf<Track>()
    private var idx = -1
    private var wants = false
    private var anchorPos = 0L
    private var anchorAt = 0L
    private var readyAt = 0L
    var loads = 0
    private val rnd = java.util.Random()

    private fun now() = System.nanoTime() / 1_000_000
    private fun cur() = list.getOrNull(idx)
    private fun dur() = (cur()?.seconds ?: 0) * 1000

    private fun pos(): Long {
        if (!wants) return anchorPos
        val start = maxOf(anchorAt, readyAt)
        val t = now()
        return if (t < start) anchorPos else minOf(anchorPos + (t - start), dur())
    }

    private fun place(p: Long, delay: Long) { anchorPos = p; anchorAt = now(); readyAt = anchorAt + delay }

    /** Called often: a song that's finished moves on to the next (already loaded, no gap). */
    fun step() {
        if (!wants || cur() == null || now() < readyAt) return
        val start = maxOf(anchorAt, readyAt)
        val over = anchorPos + (now() - start) - dur()
        if (over < 0) return
        if (idx + 1 < list.size) { idx++; anchorPos = over; anchorAt = now(); readyAt = anchorAt }
        else { anchorPos = dur(); anchorAt = now(); wants = false }
    }

    override fun current() = cur()
    override fun wantsToPlay() = wants
    override fun sounding() = wants && cur() != null && now() >= readyAt
    override fun positionMs() = pos()
    override fun durationMs() = dur()
    override fun upcoming(limit: Int) = list.drop(idx + 1).take(limit)
    override fun play() { if (!wants && cur() != null) { place(pos(), 40); wants = true } }
    override fun pause() { if (wants) { place(pos(), 0); wants = false } }
    override fun seekTo(ms: Long) { place(ms.coerceIn(0, dur()), if (wants) 90 else 0) }
    override fun next() { if (idx + 1 < list.size) { idx++; place(0, 200L + rnd.nextInt(500)) } }
    override fun previous() {
        if (pos() > 3000 || idx == 0) seekTo(0) else { idx--; place(0, 200L + rnd.nextInt(500)) }
    }
    override fun load(tracks: List<Track>, index: Int, startMs: Long, play: Boolean) {
        list = tracks.toMutableList(); idx = index; wants = play; loads++
        place(startMs, 250L + rnd.nextInt(700)) // a new song takes a moment to load
    }
    override fun setUpcoming(tracks: List<Track>) { list = (list.take(idx + 1) + tracks).toMutableList() }
    override fun addToQueue(t: Track) { if (cur() == null) load(listOf(t), 0, 0, true) else list.add(t) }
    override fun playNow(t: Track) { list.add(idx + 1, t); next() }
    override fun normalSpeed() {}
    override fun ownSpeed() {}
}

/**
 * Reads a QR code back, the way a scanner would: drawn big with a white border, found and decoded.
 * Says how it was read ("found" by looking for it like a camera, or read square by square).
 */
private fun readQr(m: com.google.zxing.common.BitMatrix): String {
    val scale = 10
    val quiet = 4
    val w = (m.width + 2 * quiet) * scale
    val px = IntArray(w * w) { 0xFFFFFFFF.toInt() }
    for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
        for (dy in 0 until scale) for (dx in 0 until scale) px[((y + quiet) * scale + dy) * w + (x + quiet) * scale + dx] = 0xFF000000.toInt()
    }
    fun bitmap() = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(w, w, px)))
    val hard = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)
    runCatching { return com.google.zxing.qrcode.QRCodeReader().decode(bitmap(), hard).text.also { System.err.println("qr: found like a camera would") } }
        .onFailure { println("qr: not found like a camera would ($it), reading it square by square") }
    val pure = mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)
    return com.google.zxing.qrcode.QRCodeReader().decode(bitmap(), pure).text
}

private fun song(title: String, seconds: Long) = Track("https://music.youtube.com/watch?v=jam$title", title, "Test Artist", seconds, null)

private fun device(role: String) {
    val ctx = android.content.AppContext
    Settings.init(ctx); Accounts.init(ctx); Jam.init(ctx)
    val player = JamFakePlayer()
    fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    ui {
        Jam.player = player
        javax.swing.Timer(20) { player.step() }.start()
        // Say what the player is doing, five times a second.
        javax.swing.Timer(200) {
            val t = player.current()
            println(
                listOf(
                    "POS", "real=${System.currentTimeMillis()}", "tr=${t?.title ?: "-"}", "pos=${player.positionMs()}",
                    "want=${player.wantsToPlay()}", "snd=${player.sounding()}", "role=${Jam.role}", "n=${Jam.members.size}",
                    "up=${player.upcoming(5).joinToString("|") { it.title }}", "loads=${player.loads}",
                ).joinToString("\t"),
            )
        }.start()
        // The Jam's status line, when it changes.
        var lastStatus: String? = null
        javax.swing.Timer(100) {
            if (Jam.status != lastStatus) { lastStatus = Jam.status; println("status: ${Jam.status ?: "(none)"}") }
        }.start()
        // Connected straight to the host, when that changes.
        var lastDirect: Boolean? = null
        javax.swing.Timer(100) {
            if (Jam.direct != lastDirect) { lastDirect = Jam.direct; println("DIRECT ${Jam.direct}") }
        }.start()
        // Jams nearby, when the list changes.
        var lastNearby = ""
        javax.swing.Timer(100) {
            val n = JamNearby.found.joinToString(",") { it.code + if (it.onWifi) "/wifi" else "/area" }
            if (n != lastNearby) { lastNearby = n; println("NEARBY ${n.ifEmpty { "-" }}") }
        }.start()
        // Toasts, as they'd show on screen.
        var lastToast = 0
        javax.swing.Timer(100) {
            if (Menus.toastId != lastToast) { lastToast = Menus.toastId; Menus.toast?.let { println("says: $it") } }
        }.start()
    }

    val input = System.`in`.bufferedReader()
    while (true) {
        val line = input.readLine() ?: break
        val cmd = line.substringBefore(' ')
        val arg = line.substringAfter(' ', "")
        if (cmd == "quit") break
        ui {
            when (cmd) {
                "own" -> player.load(listOf(song("Guests-Own-Song", 240), song("Guests-Next-Song", 200)), 0, 30_000, true)
                "queue" -> player.load(listOf(song("First", 200), song("Second", 20), song("Third", 200)), 0, 0, true)
                "start" -> { Jam.start(); println("CODE ${Jam.code}") }
                "join" -> Jam.parseCode(arg)?.let { Jam.join(it) } ?: println("bad code $arg")
                "pause" -> player.pause()
                "play" -> player.play()
                "seek" -> player.seekTo(arg.toLong() * 1000)
                "next" -> player.next()
                "prev" -> player.previous()
                "add" -> player.addToQueue(song(arg, 180))
                "leave", "end" -> Jam.leave()
                "remove" -> Jam.members.firstOrNull { !it.host }?.let { Jam.remove(it.id) }
                "look" -> JamNearby.listen(wifi = arg == "wifi" || arg == "both", area = arg == "area" || arg == "both")
                "nearby" -> Jam.updateNearby(arg == "on")
                // This check's own Jam (others may be live on the same network).
                "joinnearby" -> JamNearby.found.firstOrNull { it.code == arg }?.let { Jam.join(it.code) } ?: println("not nearby: $arg")
                "clock" -> println("clock: ${if (JamClock.checked) "checked, this one is ${-JamClock.offset} ms off" else "not checked (no time server answered)"}")
                "qr" -> Jam.inviteLink()?.let { l ->
                    println("LINK $l")
                    println("QR " + runCatching { readQr(jamQrMatrix(l)) }.getOrElse { "unreadable: $it" })
                }
            }
        }
    }
    ui { Jam.goodbye() }
    System.exit(0)
}

/**
 * A stand-in for the relay on this computer (-Plocal), so the check can run as often as needed
 * without using up the real relay's daily messages. Speaks just enough of ntfy's protocol.
 */
private object LocalRelay {
    private class Msg(val id: String, val time: Long, val topic: String, val text: String)
    private val all = java.util.concurrent.CopyOnWriteArrayList<Msg>()
    private val lock = Object()
    private var n = 0L
    /** Messages posted, by channel. */
    val posts = ConcurrentHashMap<String, Int>()

    fun start(): String {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.trim('/')
            if (ex.requestMethod == "POST") {
                val text = ex.requestBody.readBytes().decodeToString()
                // A little delay, like the real thing.
                Thread.sleep(40L + (Math.random() * 120).toLong())
                synchronized(lock) { all += Msg("m${++n}", System.currentTimeMillis() / 1000, path, text); lock.notifyAll() }
                posts.merge(path, 1, Int::plus)
                ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write("{}".toByteArray()) }
                return@createContext
            }
            val topics = path.removeSuffix("/json").split(',').toSet()
            val since = ex.requestURI.query?.split('&')?.firstOrNull { it.startsWith("since=") }?.substringAfter('=')
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            var sent = synchronized(lock) {
                when {
                    since == null -> all.size
                    since.startsWith("m") -> all.indexOfFirst { it.id == since }.let { if (it < 0) 0 else it + 1 }
                    else -> 0 // "1h": everything (the check is shorter than that)
                }
            }
            try {
                out.write("{\"event\":\"open\"}\n".toByteArray()); out.flush()
                while (true) {
                    val batch = synchronized(lock) {
                        if (sent >= all.size) lock.wait(20_000)
                        all.subList(sent, all.size).toList().also { sent = all.size }
                    }
                    if (batch.isEmpty()) out.write("{\"event\":\"keepalive\"}\n".toByteArray())
                    for (m in batch) if (m.topic in topics) {
                        out.write(org.json.JSONObject().put("id", m.id).put("time", m.time).put("event", "message").put("topic", m.topic).put("message", m.text).toString().toByteArray())
                        out.write("\n".toByteArray())
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                runCatching { out.close() }
            }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }
}
