// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private lateinit var ps: PlayerState

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Integrity.ok(this)) {
            Integrity.block(this)
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        ps = PlayerState(applicationContext)
        ps.connect()
        if (savedInstanceState == null) handleOpen(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val vm: MainViewModel = viewModel()
            androidx.compose.runtime.LaunchedEffect(Settings.frameRate, Settings.highPerformance) { applyPerformance() }
            App(vm, ps, openInBrowser = ::openInBrowser, openEqualizer = ::openEqualizer)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpen(intent)
    }

    /** Opened by tapping the song in the notification or the phone's media bar: show the full player. */
    private fun handleOpen(i: Intent?) {
        if (i?.action == OPEN_PLAYER && ::ps.isInitialized) ps.openFull = true
    }

    companion object {
        const val OPEN_PLAYER = "com.glasswave.player.OPEN_PLAYER"
    }

    /**
     * Picks the screen refresh rate from Settings (the fastest mode at or under the chosen rate,
     * same resolution), and in high performance mode asks for steady CPU speed where supported.
     */
    private fun applyPerformance() {
        try {
            @Suppress("DEPRECATION")
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            if (d != null) {
                val cur = d.mode
                val same = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                val cap = Settings.frameRate.hz + 1f
                val pick = same.filter { it.refreshRate <= cap }.maxByOrNull { it.refreshRate } ?: same.minByOrNull { it.refreshRate }
                if (pick != null) window.attributes = window.attributes.also { it.preferredDisplayModeId = pick.modeId }
            }
        } catch (e: Exception) {
            android.util.Log.w("Glasswave", "Couldn't set the refresh rate", e)
        }
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isSustainedPerformanceModeSupported == true) window.setSustainedPerformanceMode(Settings.highPerformance)
    }

    /** Opens the phone's own equalizer, attached to Glasswave's audio. */
    private fun openEqualizer() {
        val i = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, PlaybackService.audioSessionId)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 0)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "This phone has no built-in equalizer", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No web browser found", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        ps.release()
        super.onDestroy()
    }
}
