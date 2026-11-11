// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import okhttp3.Request
import org.json.JSONObject

/**
 * Jams nearby, so people around you can join without a code, strangers included:
 *  - In this area: the host's Jam is listed for its rough area (a square about 5 km across), through
 *    the same relay as everything else. Glasswave on other phones and PCs looks at its own square and
 *    the eight around it (roughly 5–10 km), and offers to join. The area comes from the phone's
 *    approximate location (or, without that, from its internet address); on a PC, from Windows'
 *    location, else the internet address.
 *  - On this Wi-Fi: the host's device also says so on the local network every few seconds, which is
 *    instant and works without any location.
 * Announced only while hosting with "Show to people nearby" on; every open Glasswave (not in a Jam)
 * looks. What others see: the host's name, the song, and that it's somewhere near. Same file in both apps.
 */
object JamNearby {
    /** A Jam nearby. [onWifi]: on the same Wi-Fi (else somewhere in this area). */
    data class Found(val code: String, val host: String, val kind: String, val song: String?, val hostId: String, val seenAt: Long, val onWifi: Boolean)

    /** Jams nearby, those on this Wi-Fi first. */
    var found by mutableStateOf<List<Found>>(emptyList())
        private set
    /** Jams waved away (by code), so their prompt doesn't come back. */
    var dismissed by mutableStateOf<Set<String>>(emptySet())
    /** The area being looked at is known (else: still finding where this is, or couldn't). */
    var areaKnown by mutableStateOf(false)
        private set

    private const val PORT = 47811
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Glasswave jam nearby").apply { isDaemon = true } }
    private val onWifi = LinkedHashMap<String, Found>()
    private val inArea = LinkedHashMap<String, Found>()

    private fun elapsed() = SystemClock.elapsedRealtime()

    /** Looks for Jams: on this Wi-Fi while [wifi], in this area while [area]. */
    fun listen(wifi: Boolean, area: Boolean) {
        if (wifi) startWifi() else stopWifi()
        if (area) startArea() else stopArea()
    }

    /** The host: tells people nearby about this Jam, or stops (and says it's over). */
    fun hosting(on: Boolean) {
        announceWifi(on)
        announceArea(on)
    }

    private fun heard(o: JSONObject, wifi: Boolean) {
        if (o.optString("gw") != "jam" || o.optInt("v") != 1) return
        val id = o.optString("id")
        if (id.isEmpty() || id == Jam.deviceId) return
        val code = Jam.parseCode(o.optString("c")) ?: return
        val map = if (wifi) onWifi else inArea
        if (o.optBoolean("gone")) { onWifi.remove(code); inArea.remove(code) }
        else map[code] = Found(code, o.optString("n").ifEmpty { "Someone" }.take(40), o.optString("k"), o.optString("s").ifEmpty { null }?.take(80), id, elapsed(), wifi)
        publish()
    }

    private fun publish() {
        val w = onWifi.values.sortedByDescending { it.seenAt }
        found = w + inArea.values.filter { it.code !in onWifi }.sortedByDescending { it.seenAt }
    }

    private fun message(gone: Boolean): JSONObject? {
        val c = Jam.code ?: return null
        return JSONObject().put("gw", "jam").put("v", 1).put("id", Jam.deviceId).put("c", c.replace("-", ""))
            .put("n", Jam.myDisplayName()).put("k", SyncPlatform.kind).put("s", Jam.player?.current()?.title ?: "").put("gone", gone)
    }

    /** Forgets Jams not heard from lately (Wi-Fi: 10 seconds; area: 25 minutes, as they're said every 10). */
    private val pruner = object : Runnable {
        override fun run() {
            val now = elapsed()
            val a = onWifi.values.removeAll { now - it.seenAt > 10_000 }
            val b = inArea.values.removeAll { now - it.seenAt > 25 * 60_000 }
            if (a || b) publish()
            if (wifiSocket != null || areaOn) main.postDelayed(this, 2_000)
        }
    }

    private fun startPruner() { main.removeCallbacks(pruner); main.postDelayed(pruner, 2_000) }

    // ---------------------------------------------------------------- on this Wi-Fi

    @Volatile private var wifiSocket: DatagramSocket? = null

    private fun startWifi() {
        if (wifiSocket != null) return
        val s = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1_500; bind(InetSocketAddress(PORT)) }
        }.getOrNull() ?: return
        wifiSocket = s
        JamPlatform.holdWifi(true)
        Thread({
            val buf = ByteArray(2048)
            while (wifiSocket === s) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val o = runCatching { JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    main.post { heard(o, wifi = true) }
                } catch (e: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (wifiSocket !== s) break
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                }
            }
        }, "Glasswave jam nearby (Wi-Fi)").apply { isDaemon = true; start() }
        startPruner()
    }

    private fun stopWifi() {
        val s = wifiSocket ?: return
        wifiSocket = null
        runCatching { s.close() }
        JamPlatform.holdWifi(false)
        onWifi.clear()
        publish()
    }

    private var wifiAnnouncing = false

    private fun announceWifi(on: Boolean) {
        if (on == wifiAnnouncing) return
        wifiAnnouncing = on
        main.removeCallbacks(wifiAnnouncer)
        if (on) main.post(wifiAnnouncer) else message(gone = true)?.let { sendWifi(it.toString()) }
    }

    private val wifiAnnouncer = object : Runnable {
        override fun run() {
            if (!wifiAnnouncing) return
            message(gone = false)?.let { sendWifi(it.toString()) }
            main.postDelayed(this, 3_000)
        }
    }

    /** To every Wi-Fi/Ethernet network this device is on (each one's broadcast address). */
    private fun sendWifi(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        io.execute {
            val targets = LinkedHashSet<InetAddress>()
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                    ni.interfaceAddresses.mapNotNullTo(targets) { it.broadcast }
                }
            }
            runCatching { targets += InetAddress.getByName("255.255.255.255") }
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    for (t in targets) runCatching { s.send(DatagramPacket(bytes, bytes.size, t, PORT)) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- in this area

    private class Spot(val lat: Double, val lon: Double, val at: Long)
    @Volatile private var spot: Spot? = null
    @Volatile private var areaOn = false
    @Volatile private var areaGen = 0

    /** Where this device roughly is (blocking): remembered for half an hour. */
    private fun whereAmI(): Spot? {
        System.getProperty("gw.jamLocation")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }
            ?.let { return Spot(it[0], it[1], elapsed()) }
        spot?.takeIf { elapsed() - it.at < 30 * 60_000 }?.let { return it }
        val p = runCatching { JamPlatform.location() }.getOrNull() ?: fromInternetAddress()
        return p?.let { Spot(it.first, it.second, elapsed()) }?.also { spot = it }
    }

    /** A rough place from this connection's internet address (city level), when there's no location. */
    private fun fromInternetAddress(): Pair<Double, Double>? {
        for (url in listOf("https://ipapi.co/json/", "https://ipwho.is/")) {
            val o = runCatching {
                YtRepo.http.newCall(Request.Builder().url(url).header("User-Agent", "Glasswave").build()).execute().use { r ->
                    if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null
                }
            }.getOrNull() ?: continue
            val lat = o.optDouble("latitude", Double.NaN)
            val lon = o.optDouble("longitude", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) return lat to lon
        }
        return null
    }

    /** The relay channel and key for one area square. */
    private fun channel(cell: String): Pair<String, SecretKeySpec> {
        val k = MessageDigest.getInstance("SHA-256").digest("glasswave-near-v1|$cell".toByteArray())
        val hex = MessageDigest.getInstance("SHA-256").digest("near|$cell".toByteArray()).joinToString("") { "%02x".format(it) }
        return "gwn-" + hex.take(28) to SecretKeySpec(k, "AES")
    }

    private fun startArea() {
        if (areaOn) return
        areaOn = true
        val gen = ++areaGen
        startPruner()
        Thread({
            val here = whereAmI()
            if (here == null || gen != areaGen) return@Thread
            main.post { areaKnown = true }
            // This square and the eight around it.
            val step = Geohash.cellDegrees(AREA_PRECISION)
            val cells = LinkedHashSet<String>()
            for (dy in -1..1) for (dx in -1..1) cells += Geohash.encode(here.lat + dy * step.first, here.lon + dx * step.second, AREA_PRECISION)
            val channels = cells.associate { c -> channel(c).let { it.first to it.second } }
            val client = YtRepo.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
            var since = "25m"
            var wait = 5_000L
            while (gen == areaGen) {
                try {
                    val req = Request.Builder().url("${Jam.relay}/${channels.keys.joinToString(",")}/json?since=$since").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("relay ${resp.code}")
                        wait = 5_000L
                        val src = resp.body?.source() ?: return@use
                        while (gen == areaGen) {
                            val line = src.readUtf8Line() ?: break
                            val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (ev.optString("event") != "message") continue
                            ev.optString("id").takeIf { it.isNotEmpty() }?.let { since = it }
                            val key = channels[ev.optString("topic")] ?: continue
                            val o = Jam.open(ev.optString("message"), key) ?: continue
                            main.post { heard(o, wifi = false) }
                        }
                    }
                } catch (e: Exception) {
                }
                if (gen != areaGen) break
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
                wait = minOf(wait * 2, 120_000L)
            }
        }, "Glasswave jam nearby (area)").apply { isDaemon = true; start() }
    }

    /** Automated checks only (pictures): pretend this Jam was heard nearby. */
    internal fun testFound(f: Found) {
        (if (f.onWifi) onWifi else inArea)[f.code] = f.copy(seenAt = elapsed())
        publish()
    }

    /** Location permission was answered: find where this is again, and look around there. */
    fun relocate() {
        spot = null
        if (areaOn) { stopArea(); startArea() }
        if (areaAnnouncing) { main.removeCallbacks(areaAnnouncer); main.post(areaAnnouncer) }
    }

    private fun stopArea() {
        if (!areaOn) return
        areaOn = false
        areaGen++
        inArea.clear()
        publish()
    }

    private var areaAnnouncing = false
    @Volatile private var myCell: String? = null

    private fun announceArea(on: Boolean) {
        if (on == areaAnnouncing) return
        areaAnnouncing = on
        main.removeCallbacks(areaAnnouncer)
        if (on) main.post(areaAnnouncer)
        else {
            val c = myCell ?: return
            val msg = message(gone = true) ?: return
            io.execute { runCatching { val (t, k) = channel(c); Jam.post(t, k, msg) } }
        }
    }

    /** Lists the Jam for this area now, and again every 10 minutes while it lasts. */
    private val areaAnnouncer = object : Runnable {
        override fun run() {
            if (!areaAnnouncing) return
            val msg = message(gone = false)
            if (msg != null) io.execute {
                val here = whereAmI() ?: return@execute
                val c = Geohash.encode(here.lat, here.lon, AREA_PRECISION)
                myCell = c
                runCatching { val (t, k) = channel(c); Jam.post(t, k, msg) }
            }
            main.postDelayed(this, 10 * 60_000)
        }
    }

    /** Squares about 5 km across (geohash precision 5). */
    private const val AREA_PRECISION = 5
}

/** Geohash: a short name for a square on the map (longer = smaller square). */
internal object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int): String {
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        val la = lat.coerceIn(-90.0, 90.0)
        val lo = ((lon + 540.0) % 360.0) - 180.0 // wraps across the date line
        val out = StringBuilder()
        var bit = 0; var ch = 0; var even = true
        while (out.length < precision) {
            if (even) {
                val mid = (lonLo + lonHi) / 2
                if (lo >= mid) { ch = ch or (1 shl (4 - bit)); lonLo = mid } else lonHi = mid
            } else {
                val mid = (latLo + latHi) / 2
                if (la >= mid) { ch = ch or (1 shl (4 - bit)); latLo = mid } else latHi = mid
            }
            even = !even
            if (++bit == 5) { out.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return out.toString()
    }

    /** How tall and wide a square is, in degrees (latitude, longitude). */
    fun cellDegrees(precision: Int): Pair<Double, Double> {
        val bits = precision * 5
        val lonBits = (bits + 1) / 2
        val latBits = bits / 2
        return 180.0 / (1 shl latBits) to 360.0 / (1 shl lonBits)
    }
}
