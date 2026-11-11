// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.ui.Modifier
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

/** The PC side of a Jam (see Jam): sharing the invite, where this PC is, and songs only this PC has. */
object JamPlatform {
    fun isLocalSong(url: String): Boolean = PcMusic.isPcSong(url)

    const val shareLabel = "Copy invite"

    /** On a PC the invite goes on the clipboard, ready to paste into a chat. */
    fun share(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        Menus.say("Invite copied. Paste it into a chat")
    }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        Menus.say("Code copied")
    }

    /** Typing a code doesn't set off the keyboard shortcuts. */
    fun Modifier.jamTyping(): Modifier = this.typing()

    /** (Phones keep Wi-Fi listening for nearby Jams. Nothing to do on a PC.) */
    fun holdWifi(@Suppress("UNUSED_PARAMETER") on: Boolean) {}

    /** (Phones ask for location the first time Jam opens. A PC uses the Windows setting as it is.) */
    fun askLocationOnce() {}

    /** Windows' own location, if it's turned on for apps (blocking, about ten seconds at most). Null elsewhere. */
    fun location(): Pair<Double, Double>? {
        if (!Os.isWindows) return null
        val script = "Add-Type -AssemblyName System.Device; " +
            "\$w = New-Object System.Device.Location.GeoCoordinateWatcher; " +
            "\$null = \$w.TryStart(\$false, [TimeSpan]::FromSeconds(8)); " +
            "\$c = \$w.Position.Location; if (\$c.IsUnknown) { 'unknown' } else { \"\$(\$c.Latitude),\$(\$c.Longitude)\" }"
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        if (!p.waitFor(12, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        val out = p.inputStream.bufferedReader().readText().trim().lines().lastOrNull().orEmpty()
        val parts = out.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        return if (parts.size == 2 && !(parts[0] == 0.0 && parts[1] == 0.0)) parts[0] to parts[1] else null
    }

    /** No camera scanning on a PC: phones scan the QR code a PC shows. */
    const val canScan = false
    fun scanQr(@Suppress("UNUSED_PARAMETER") onResult: (String) -> Unit) {}
}
