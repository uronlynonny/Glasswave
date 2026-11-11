// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The phone side of a Jam (see Jam): sharing the invite, where the phone is, scanning, and songs only this phone has. */
object JamPlatform {
    private var app: Context? = null
    /** The open screen, for the permission question and the scanner. Set by MainActivity. */
    var activity: WeakReference<Activity>? = null

    fun init(context: Context) { app = context.applicationContext }

    fun isLocalSong(url: String): Boolean = PhoneMusic.isPhoneSong(url)

    const val shareLabel = "Share invite"

    /** The phone's share sheet (WhatsApp, Messages...). */
    fun share(text: String) {
        val c = app ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { c.startActivity(Intent.createChooser(send, "Invite to your Jam").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copy(text: String) {
        val c = app ?: return
        runCatching { (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jam code", text)) }
        Menus.say("Code copied")
    }

    /** (On a PC, typing a code mustn't set off keyboard shortcuts. Nothing to do on a phone.) */
    fun Modifier.jamTyping(): Modifier = this

    private var wifiLock: WifiManager.MulticastLock? = null

    /** Many phones drop network-wide messages to save power; this keeps them coming while Glasswave looks for Jams. */
    fun holdWifi(on: Boolean) {
        val c = app ?: return
        runCatching {
            if (on) {
                if (wifiLock?.isHeld == true) return
                val wm = c.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createMulticastLock("glasswave-jam").apply { setReferenceCounted(false); acquire() }
            } else {
                wifiLock?.takeIf { it.isHeld }?.release()
                wifiLock = null
            }
        }
    }

    private fun hasLocation(): Boolean =
        app?.let { ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED } == true

    /** The first time Jam opens: asks for approximate location, so Jams around you show up. Once. */
    fun askLocationOnce() {
        val c = app ?: return
        val a = activity?.get() ?: return
        val prefs = c.getSharedPreferences("jam", Context.MODE_PRIVATE)
        if (hasLocation() || prefs.getBoolean("asked_location", false)) return
        prefs.edit().putBoolean("asked_location", true).apply()
        runCatching { a.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST) }
    }

    const val LOCATION_REQUEST = 4711

    /** The answer came back (MainActivity): look again with the better location. */
    fun locationAnswered() = JamNearby.relocate()

    /** The phone's approximate location (blocking, ten seconds at most), or null without permission. */
    fun location(): Pair<Double, Double>? {
        val c = app ?: return null
        if (!hasLocation()) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val recent = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 2 * 3600_000L) return recent.latitude to recent.longitude
        if (Build.VERSION.SDK_INT < 30) return recent?.let { it.latitude to it.longitude }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return recent?.let { it.latitude to it.longitude }
        val latch = CountDownLatch(1)
        var got: Location? = null
        val asked = runCatching { lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(c)) { got = it; latch.countDown() } }.isSuccess
        if (asked) latch.await(10, TimeUnit.SECONDS)
        return (got ?: recent)?.let { it.latitude to it.longitude }
    }

    /** Phones can scan a Jam's QR code (Google's scanner: no camera permission needed). */
    const val canScan = true

    fun scanQr(onResult: (String) -> Unit) {
        val a = activity?.get() ?: return
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        runCatching {
            GmsBarcodeScanning.getClient(a, options).startScan()
                .addOnSuccessListener { b -> b.rawValue?.let(onResult) }
                .addOnFailureListener { Menus.say("Couldn't open the scanner. Type the code instead.") }
        }.onFailure { Menus.say("Couldn't open the scanner. Type the code instead.") }
    }
}
