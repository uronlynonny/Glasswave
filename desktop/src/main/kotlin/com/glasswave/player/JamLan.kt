// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.os.SystemClock
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Glasswave devices on the same Wi-Fi or hotspot talking straight to each other: no internet, no
 * relay, no daily limit.
 *  - Finding each other: short messages to everyone on the network (UDP broadcast, port 47811).
 *  - A Jam: the host and each member keep a direct connection (TCP) for the Jam's messages.
 * Windows' firewall can stop other devices from reaching a PC, but answers to a PC's own broadcasts
 * still get through, so either side can open the connection (see [Jam]). Same file in both apps.
 */
object JamLan {
    const val PORT = 47811
    /** Automated checks only: act as if there were no local network (the relay only). */
    val off: Boolean = System.getProperty("gw.jamNoLan") != null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val out = Executors.newSingleThreadExecutor { r -> Thread(r, "Glasswave lan send").apply { isDaemon = true } }
    /** Direct connections write on their own thread: a broadcast waiting on Windows' network list mustn't hold them up. */
    private val linkOut = Executors.newSingleThreadExecutor { r -> Thread(r, "Glasswave lan link send").apply { isDaemon = true } }
    /** Who needs the network heard ("nearby", "jam"). Main thread. */
    private val users = HashSet<String>()
    /** Hears broadcasts on [PORT] (shared with other Glasswaves on this device). */
    @Volatile private var hear: DatagramSocket? = null
    /** Sends everything, and hears direct answers (its own port, so each Glasswave gets its own). */
    @Volatile private var talk: DatagramSocket? = null

    private fun elapsed() = SystemClock.elapsedRealtime()

    /** [who] needs the network heard (or no longer). The sockets stay open while anyone does. */
    fun need(who: String, on: Boolean) {
        if (off) return
        val was = users.isNotEmpty()
        if (on) users += who else users -= who
        if (users.isNotEmpty() && !was) open()
        if (users.isEmpty() && was) close()
    }

    private fun open() {
        JamPlatform.holdWifi(true)
        Thread({
            val h = runCatching {
                DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1_500; bind(InetSocketAddress(PORT)) }
            }.getOrNull()
            val t = runCatching { DatagramSocket(0).apply { broadcast = true; soTimeout = 1_500 } }.getOrNull()
            // Where broadcasts go, worked out now (it can take Windows a few seconds) rather than on the first one.
            runCatching { broadcastTargets() }
            main.post {
                if (users.isEmpty()) { h?.close(); t?.close(); return@post }
                hear = h; talk = t
                h?.let { hearOn(it) }
                t?.let { hearOn(it) }
            }
        }, "Glasswave lan open").apply { isDaemon = true; start() }
    }

    private fun close() {
        val h = hear; val t = talk
        hear = null; talk = null
        runCatching { h?.close() }
        runCatching { t?.close() }
        JamPlatform.holdWifi(false)
    }

    private fun hearOn(s: DatagramSocket) {
        Thread({
            val buf = ByteArray(4096)
            while (hear === s || talk === s) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val o = runCatching { JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    val from = p.socketAddress as? InetSocketAddress ?: continue
                    main.post { dispatch(o, from) }
                } catch (e: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (hear !== s && talk !== s) break
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                }
            }
        }, "Glasswave lan hear").apply { isDaemon = true; start() }
    }

    private fun dispatch(o: JSONObject, from: InetSocketAddress) {
        when (o.optString("gw")) {
            "jam" -> { JamNearby.heardLan(o); Jam.heardLan(o, from) }
            "jamq", "jamm" -> Jam.heardLan(o, from)
        }
    }

    /** To everyone on every Wi-Fi/Ethernet network this device is on. */
    fun broadcast(o: JSONObject) {
        if (off) return
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        out.execute {
            val s = talk ?: return@execute
            for (t in broadcastTargets()) runCatching { s.send(DatagramPacket(bytes, bytes.size, t, PORT)) }
        }
    }

    /** Straight to one device (an answer to something it sent). */
    fun sendTo(o: JSONObject, to: InetSocketAddress) {
        if (off) return
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        out.execute { runCatching { talk?.send(DatagramPacket(bytes, bytes.size, to)) } }
    }

    @Volatile private var targets: List<InetAddress> = emptyList()
    @Volatile private var targetsAt = 0L

    /**
     * Each network's broadcast address. Asking Windows for its networks can take seconds, so the
     * answer is kept for a minute (else messages pile up behind it).
     */
    @Synchronized
    private fun broadcastTargets(): List<InetAddress> {
        if (targets.isNotEmpty() && elapsed() - targetsAt < 60_000) return targets
        val found = LinkedHashSet<InetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                ni.interfaceAddresses.mapNotNullTo(found) { it.broadcast }
            }
        }
        runCatching { found += InetAddress.getByName("255.255.255.255") }
        targets = found.toList()
        targetsAt = elapsed()
        return targets
    }

    // ---------------------------------------------------------------- direct connections

    /** What happens on a connection (called on the main thread). */
    interface Events {
        /** A line arrived ([wallAt]: this device's clock when it did). */
        fun line(link: Link, text: String, wallAt: Long)
        fun closed(link: Link)
        /** Someone connected to this device's server. */
        fun accepted(link: Link) {}
    }

    /** One direct connection: lines of text each way. */
    class Link internal constructor(private val sock: Socket, val outgoing: Boolean) {
        /** Who's at the other end, once they've said. */
        @Volatile var peer: String? = null
        @Volatile var heardAt: Long = SystemClock.elapsedRealtime()
        @Volatile var closed = false
            private set
        val address: InetAddress? get() = sock.inetAddress
        private val stream = sock.getOutputStream()

        /** Queues a line to send; false once closed. */
        fun send(text: String): Boolean {
            if (closed) return false
            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            linkOut.execute { write(bytes) }
            return true
        }

        /** Sends a line now, waiting for it (the app closing). */
        fun sendNow(text: String) { write((text + "\n").toByteArray(Charsets.UTF_8)) }

        @Synchronized
        private fun write(bytes: ByteArray) {
            if (closed) return
            try { stream.write(bytes); stream.flush() } catch (e: Exception) { shut() }
        }

        /** Closes it after what's already queued has gone. */
        fun close() { linkOut.execute { shut() } }

        internal fun shut() {
            if (closed) return
            closed = true
            runCatching { sock.close() }
        }
    }

    /** Starts reading [sock]; [first] runs (main thread) before any line is handed over. */
    private fun start(sock: Socket, outgoing: Boolean, ev: Events, first: (Link) -> Unit) {
        sock.tcpNoDelay = true
        sock.keepAlive = true
        val link = Link(sock, outgoing)
        main.post { first(link) }
        Thread({
            val input = sock.getInputStream()
            try {
                while (!link.closed) {
                    val text = readLine(input) ?: break
                    val at = System.currentTimeMillis()
                    link.heardAt = SystemClock.elapsedRealtime()
                    main.post { ev.line(link, text, at) }
                }
            } catch (e: Exception) {
            }
            link.shut()
            main.post { ev.closed(link) }
        }, "Glasswave lan link").apply { isDaemon = true; start() }
    }

    /** One line (up to 256 KB, else the connection's dropped). */
    private fun readLine(input: InputStream): String? {
        val b = java.io.ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return b.toString("UTF-8")
            b.write(c)
            if (b.size() > 256 * 1024) throw java.io.IOException("too long")
        }
    }

    /** Waits for connections, on a port of its own: [ready] gets the server (null if it couldn't). */
    fun serve(ev: Events, ready: (ServerSocket?) -> Unit) {
        if (off) { ready(null); return }
        Thread({
            val ss = runCatching { ServerSocket(0) }.getOrNull()
            main.post { ready(ss) }
            ss ?: return@Thread
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                runCatching { start(s, outgoing = false, ev) { ev.accepted(it) } }
            }
        }, "Glasswave lan serve").apply { isDaemon = true; start() }
    }

    /** Connects to [addr]:[port] (a couple of seconds at most): [done] gets the link, or null. */
    fun connect(addr: InetAddress, port: Int, ev: Events, done: (Link?) -> Unit) {
        Thread({
            val s = Socket()
            val ok = runCatching { s.connect(InetSocketAddress(addr, port), 2_500); start(s, outgoing = true, ev, done) }.isSuccess
            if (!ok) { runCatching { s.close() }; main.post { done(null) } }
        }, "Glasswave lan connect").apply { isDaemon = true; start() }
    }
}
