// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import okhttp3.Request
import org.json.JSONObject

/**
 * Jams nearby, so people around you can join without a code, strangers included:
 *  - On this Wi-Fi or hotspot: the host's device says so on the local network every few seconds
 *    ([JamLan]). Instant, free, and needs no location or internet.
 *  - In this area: the host's Jam is also listed for its rough area (a square about 5 km across)
 *    through the relay, every 20 minutes. Glasswave on other phones and PCs looks at its own square
 *    and the eight around it (roughly 5–10 km). The area comes from the phone's approximate location
 *    (or, without that, from its internet address); on a PC, from Windows' location, else the
 *    internet address.
 * Listed only while hosting with "Show to people nearby" on (the host still says it's there, without
 * the code, so members who have the code can find it); every open Glasswave (not in a Jam) looks.
 * What others see: the host's name and picture, the song, and that it's somewhere near. Same file in
 * both apps.
 */
object JamNearby {
    /**
     * A Jam nearby. [onWifi]: on the same Wi-Fi (else somewhere in this area). [ownTag]: set when the
     * host is signed in to YouTube Music (see [Sync.ownTag]), to tell your own devices apart.
     */
    data class Found(
        val code: String, val host: String, val kind: String, val song: String?, val hostId: String, val seenAt: Long, val onWifi: Boolean,
        val photo: String? = null, val ownTag: String? = null, val firstSeen: Long = seenAt,
    )

    /** Jams nearby, those on this Wi-Fi first, each in the order it turned up (so nothing jumps about). */
    var found by mutableStateOf<List<Found>>(emptyList())
        private set
    /** Jams waved away (by code): their prompt doesn't come back. Kept across restarts. */
    var dismissed by mutableStateOf<Set<String>>(emptySet())
        private set
    /** The area being looked at is known (else: still finding where this is, or couldn't). */
    var areaKnown by mutableStateOf(false)
        private set

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Glasswave jam nearby").apply { isDaemon = true } }
    /** "It's over" goes out on its own thread, so it never waits behind finding the area. */
    private val urgent = Executors.newSingleThreadExecutor { r -> Thread(r, "Glasswave jam nearby (goodbye)").apply { isDaemon = true } }
    private val onWifi = LinkedHashMap<String, Found>()
    private val inArea = LinkedHashMap<String, Found>()
    /** The newest message heard from each host: an older copy arriving late is ignored. */
    private val newest = HashMap<String, Long>()
    private var lastSent = 0L
    private var prefs: SharedPreferences? = null

    private fun elapsed() = SystemClock.elapsedRealtime()

    fun init(p: SharedPreferences) {
        prefs = p
        dismissed = p.getString("dismissed", null).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    }

    /** "Cancel" on the prompt: that Jam doesn't ask again. */
    fun dismiss(code: String) {
        dismissed = (dismissed.toList() + code).takeLast(60).toSet()
        prefs?.edit()?.putString("dismissed", dismissed.joinToString(","))?.apply()
    }

    /** Looks for Jams: on this Wi-Fi while [wifi], in this area while [area]. */
    fun listen(wifi: Boolean, area: Boolean) {
        if (wifi != wifiLooking) {
            wifiLooking = wifi
            JamLan.need("nearby", wifi)
            if (!wifi) { onWifi.clear(); publish() }
        }
        if (area) startArea() else stopArea()
        startPruner()
    }

    private var wifiLooking = false

    /** The host: says so on the Wi-Fi while [active], and lists it for people nearby while "Show to people nearby" is on. */
    fun hosting(active: Boolean) {
        val public = active && Jam.nearbyOn
        if (active != beaconing) {
            beaconing = active
            main.removeCallbacks(beaconer)
            if (active) main.post(beaconer)
        }
        if (public != listed) {
            val was = listed
            listed = public
            if (was) {
                // Off the list (or over): said three times straight away, as one message on a busy network can go missing.
                val m = message(public = false, active = active)
                JamLan.broadcast(m)
                main.postDelayed({ JamLan.broadcast(m) }, 400)
                main.postDelayed({ JamLan.broadcast(m) }, 1_000)
            }
        }
        announceArea(public)
    }

    private var beaconing = false
    private var listed = false

    /** What this host says on the network right now (also the answer to a member looking for it). */
    internal fun beacon(): JSONObject = message(listed, beaconing)

    private fun message(public: Boolean, active: Boolean): JSONObject {
        // Numbered (always rising), so the newest word wins whatever order copies arrive in.
        lastSent = maxOf(lastSent + 1, System.currentTimeMillis())
        val o = JSONObject().put("gw", "jam").put("v", 1).put("t", lastSent).put("id", Jam.deviceId).put("gone", !public)
        val c = Jam.code
        if (public && c != null) {
            o.put("c", c.replace("-", "")).put("n", Jam.myDisplayName()).put("k", SyncPlatform.kind)
                .put("s", Jam.player?.current()?.title ?: "").put("ph", Profile.photo ?: "").put("o", Sync.ownTag() ?: "")
        }
        if (active) Jam.lanFields(o)
        return o
    }

    /** Every 3 seconds while hosting, to everyone on this Wi-Fi. */
    private val beaconer = object : Runnable {
        override fun run() {
            if (!beaconing) return
            JamLan.broadcast(message(listed, true))
            main.postDelayed(this, 3_000)
        }
    }

    /** Heard on the local network (see [JamLan]). */
    internal fun heardLan(o: JSONObject) { if (wifiLooking) heard(o, wifi = true) }

    private fun heard(o: JSONObject, wifi: Boolean) {
        if (o.optString("gw") != "jam" || o.optInt("v") != 1) return
        val id = o.optString("id")
        if (id.isEmpty() || id == Jam.deviceId) return
        val t = o.optLong("t")
        if (t > 0) {
            if (t < (newest[id] ?: 0L)) return
            newest[id] = t
        }
        val code = if (o.optBoolean("gone")) null else Jam.parseCode(o.optString("c"))
        if (code == null) {
            // Not listed (any more): forget this host's Jams.
            val a = onWifi.values.removeAll { it.hostId == id }
            val b = inArea.values.removeAll { it.hostId == id }
            if (a || b) publish()
            return
        }
        val map = if (wifi) onWifi else inArea
        val old = map[code]
        map[code] = Found(
            code, o.optString("n").ifEmpty { "Someone" }.take(40), o.optString("k"), o.optString("s").ifEmpty { null }?.take(80), id,
            elapsed(), wifi, o.optString("ph").ifEmpty { null }?.take(500), o.optString("o").ifEmpty { null }, old?.firstSeen ?: elapsed(),
        )
        publish()
    }

    private fun publish() {
        val w = onWifi.values.sortedBy { it.firstSeen }
        found = w + inArea.values.filter { it.code !in onWifi }.sortedBy { it.firstSeen }
    }

    /** Forgets Jams not heard from lately (Wi-Fi: 10 seconds; area: 45 minutes, as they're listed every 20). */
    private val pruner = object : Runnable {
        override fun run() {
            val now = elapsed()
            val a = onWifi.values.removeAll { now - it.seenAt > 10_000 }
            val b = inArea.values.removeAll { now - it.seenAt > 45 * 60_000 }
            if (a || b) publish()
            if (wifiLooking || areaOn) main.postDelayed(this, 2_000)
        }
    }

    private fun startPruner() { main.removeCallbacks(pruner); main.postDelayed(pruner, 2_000) }

    /** Automated checks only (pictures): pretend this Jam was heard nearby. */
    internal fun testFound(f: Found) {
        (if (f.onWifi) onWifi else inArea)[f.code] = f.copy(seenAt = elapsed())
        publish()
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
            var since = "45m"
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
            val msg = message(public = false, active = false)
            urgent.execute { runCatching { val (t, k) = channel(c); Jam.post(t, k, msg) } }
        }
    }

    /** Lists the Jam for this area now, and again every 20 minutes while it lasts (each one counts against the relay's daily limit). */
    private val areaAnnouncer = object : Runnable {
        override fun run() {
            if (!areaAnnouncing) return
            val msg = message(public = true, active = false)
            io.execute {
                val here = whereAmI() ?: return@execute
                val c = Geohash.encode(here.lat, here.lon, AREA_PRECISION)
                myCell = c
                runCatching { val (t, k) = channel(c); Jam.post(t, k, msg) }
            }
            main.postDelayed(this, 20 * 60_000)
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
