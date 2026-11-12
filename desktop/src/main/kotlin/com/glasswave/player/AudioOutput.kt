// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * Where the music comes out on a PC: the system's default (follows Windows), or a speaker,
 * headset or screen you pick, like Spotify's or Discord's output setting. Picking another one while
 * a song plays moves it there straight away, from the same moment (see [AudioDeck]).
 */
object AudioOutput {
    /** A speaker or headset the PC can play through ([name] as Windows calls it). */
    class Device(val name: String, internal val info: Mixer.Info)

    /** What this PC can play through right now (not counting the system default). */
    var devices by mutableStateOf<List<Device>>(emptyList())
        private set
    /** The picked output, by name, or null for the system default. Kept when it's unplugged, and used again when it's back. */
    var chosen by mutableStateOf<String?>(null)
        private set
    /** Goes up whenever the output changes, so the decks know to move. */
    @Volatile var generation = 0
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("audio_output", Context.MODE_PRIVATE)
        chosen = prefs?.getString("device", null)?.takeIf { it.isNotBlank() }
    }

    /** Plays through [name] from now on (null: the system default). */
    fun pick(name: String?) {
        if (name == chosen) return
        chosen = name
        prefs?.edit()?.putString("device", name ?: "")?.apply()
        generation++
    }

    /** The picked output is plugged in (or the system default is in use). */
    val chosenPresent: Boolean get() = chosen == null || devices.any { it.name == chosen }

    /** Looks again at what's plugged in (blocking, but quick). */
    fun refresh() {
        val found = runCatching { list() }.getOrDefault(emptyList())
        // Something came or went: if it was the picked one, the decks move (back) to it.
        val before = devices.any { it.name == chosen }
        devices = found
        if (chosen != null && before != found.any { it.name == chosen }) generation++
    }

    private val wanted = DataLine.Info(SourceDataLine::class.java, AudioFormat(48_000f, 16, 2, true, false))

    private fun list(): List<Device> = AudioSystem.getMixerInfo().filter { info ->
        val n = info.name
        // "Primary Sound Driver" is the system default (offered on its own), "Port ..." are volume controls.
        !n.startsWith("Port ") && !n.contains("Primary Sound Driver") &&
            runCatching { AudioSystem.getMixer(info).isLineSupported(wanted) }.getOrDefault(false)
    }.map { Device(it.name.trim(), it) }.distinctBy { it.name }

    /**
     * Opens a sound line on the picked output (or the system default when none is picked, or the
     * picked one isn't plugged in).
     */
    fun openLine(format: AudioFormat, bufferBytes: Int): SourceDataLine {
        val name = chosen
        if (name != null) {
            val info = runCatching { list() }.getOrDefault(emptyList()).firstOrNull { it.name == name }?.info
            if (info != null) {
                runCatching {
                    val l = AudioSystem.getMixer(info).getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                    l.open(format, bufferBytes)
                    return l
                }.onFailure { android.util.Log.w("Glasswave", "Couldn't play through $name: using the system default", it) }
            }
        }
        val l = AudioSystem.getSourceDataLine(format)
        l.open(format, bufferBytes)
        return l
    }
}
