// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import java.security.MessageDigest

/**
 * Keeps Glasswave from being passed off as someone else's app.
 *
 * Every official build is signed with nonny dev's own key. Anyone who edits the app (renames it,
 * swaps the credits, changes the code) has to sign it again with a different key, and then this
 * check fails: the app shows who really made it and where to get the original, then closes.
 * The name, package and credits are checked too.
 */
object Integrity {
    // SHA-256 of the official signing certificate.
    private val sig = intArrayOf(
        0x05, 0xE8, 0x57, 0xDA, 0x9B, 0xE9, 0xAB, 0x73, 0xE7, 0xD4, 0xF4, 0xA9, 0x42, 0x95, 0xB8, 0x1D,
        0x62, 0x1B, 0x35, 0xFC, 0x63, 0x9A, 0x47, 0x05, 0xC8, 0x8A, 0x0B, 0x0D, 0xC4, 0x03, 0xF7, 0x4B,
    ).toList()
    private const val PACKAGE = "com.glasswave.player"
    private const val REPO = "github.com/uronlynonny/Glasswave"

    @Volatile private var result: Boolean? = null

    fun ok(context: Context): Boolean = result ?: check(context.applicationContext).also { result = it }

    private fun check(c: Context): Boolean = runCatching {
        if (c.packageName != PACKAGE) return false
        if (c.getString(R.string.app_name) != "Glasswave") return false
        if (c.getString(R.string.app_author) != Credits.AUTHOR || Credits.AUTHOR != "nonny dev") return false
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return false
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return false
        val want = sig
        certs.isNotEmpty() && certs.all { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).map { it.toInt() and 0xFF } == want
        }
    }.getOrDefault(false)

    /** Shows the "not the real Glasswave" message, then closes the app. */
    fun block(a: Activity) {
        a.setContentView(
            TextView(a).apply {
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
                text = "This copy of Glasswave has been changed by someone else.\n\n" +
                    "Glasswave is made by nonny dev.\nGet the real app at\n$REPO"
            },
        )
        Handler(Looper.getMainLooper()).postDelayed({
            a.finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 6_000)
    }
}
