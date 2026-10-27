// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** A ready-made equalizer setting. [gains] are in dB, one per band (see [Sound.BANDS]). */
enum class EqPreset(val label: String, val gains: FloatArray) {
    FLAT("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS("Bass boost", floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
    BASS_CUT("Less bass", floatArrayOf(-6f, -5f, -4f, -2f, -1f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE("Treble boost", floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
    VOCAL("Vocals", floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    POP("Pop", floatArrayOf(-1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f, -1f, -1f)),
    ROCK("Rock", floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    HIPHOP("Hip-hop", floatArrayOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f)),
    ELECTRONIC("Electronic", floatArrayOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f)),
    DANCE("Dance", floatArrayOf(4f, 6f, 4f, 0f, 0f, -2f, -2f, -1f, 2f, 3f)),
    RNB("R&B", floatArrayOf(3f, 6f, 5f, 1f, -2f, -1f, 2f, 2f, 3f, 3f)),
    JAZZ("Jazz", floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
    CLASSICAL("Classical", floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    ACOUSTIC("Acoustic", floatArrayOf(4f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 3f, 2f)),
    LOUD("Loudness", floatArrayOf(5f, 4f, 1f, 0f, -2f, 0f, -1f, -2f, 4f, 2f)),
    SMALL("Small speakers", floatArrayOf(-4f, -3f, -1f, 1f, 2f, 3f, 3f, 2f, 1f, 0f)),
    NIGHT("Late night", floatArrayOf(-3f, -2f, -1f, 0f, 1f, 2f, 1f, 0f, -2f, -3f)),
}

/** A room for the reverb effect: how big it sounds and how long it rings. */
enum class Room(val label: String, val size: Float, val damp: Float) {
    SMALL("Small room", 0.70f, 0.45f),
    HALL("Concert hall", 0.84f, 0.25f),
    ARENA("Arena", 0.92f, 0.18f),
}

/**
 * Glasswave's own sound settings: a 10-band equalizer, bass boost, pitch, reverb, distortion,
 * balance and mono. They're applied inside the player (see [SoundProcessor]), so they work the
 * same on every phone and need no extra apps.
 */
object Sound {
    /** The equalizer's bands, in Hz. */
    val BANDS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    const val MAX_DB = 12f

    private lateinit var prefs: SharedPreferences

    var eqOn by mutableStateOf(false); private set
    /** Band gains in dB. Changing it replaces the whole array, so screens update. */
    var gains by mutableStateOf(FloatArray(BANDS.size)); private set
    /** The preset the bands came from, or null once you move a band yourself. */
    var preset by mutableStateOf<EqPreset?>(EqPreset.FLAT); private set
    /** 0..1: extra low end on top of the equalizer. */
    var bass by mutableFloatStateOf(0f); private set
    /** 0..1: brighter, clearer highs. */
    var treble by mutableFloatStateOf(0f); private set
    /** Semitones up or down, without changing the speed. */
    var pitch by mutableFloatStateOf(0f); private set
    /** 0..1: how much of the room you hear. 0 = off. */
    var reverb by mutableFloatStateOf(0f); private set
    var room by mutableStateOf(Room.HALL); private set
    /** 0..1: warm overdrive up to fuzzy distortion. 0 = off. */
    var drive by mutableFloatStateOf(0f); private set
    /** -1 (left) .. 1 (right). */
    var balance by mutableFloatStateOf(0f); private set
    var mono by mutableStateOf(false); private set
    /** 0..1: wider stereo (0 = as recorded). */
    var width by mutableFloatStateOf(0f); private set

    /** Goes up on every change, so the audio thread knows to pick up new settings. */
    @Volatile
    var version = 0
        private set

    /** The player service listens here (for pitch). */
    val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
        eqOn = prefs.getBoolean("eq_on", false)
        gains = prefs.getString("gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == BANDS.size }?.toFloatArray() ?: FloatArray(BANDS.size)
        preset = prefs.getString("preset", EqPreset.FLAT.name)?.let { n -> EqPreset.entries.firstOrNull { it.name == n } }
        bass = prefs.getFloat("bass", 0f)
        treble = prefs.getFloat("treble", 0f)
        pitch = prefs.getFloat("pitch", 0f)
        reverb = prefs.getFloat("reverb", 0f)
        room = runCatching { Room.valueOf(prefs.getString("room", null) ?: "HALL") }.getOrDefault(Room.HALL)
        drive = prefs.getFloat("drive", 0f)
        balance = prefs.getFloat("balance", 0f)
        mono = prefs.getBoolean("mono", false)
        width = prefs.getFloat("width", 0f)
        version++
    }

    private fun changed() {
        version++
        listeners.forEach { it() }
    }

    fun updateEqOn(on: Boolean) { eqOn = on; prefs.edit { putBoolean("eq_on", on) }; changed() }

    fun applyPreset(p: EqPreset) {
        gains = p.gains.copyOf()
        preset = p
        if (!eqOn) eqOn = true
        prefs.edit {
            putBoolean("eq_on", true)
            putString("gains", gains.joinToString(","))
            putString("preset", p.name)
        }
        changed()
    }

    fun setBand(i: Int, db: Float) {
        val g = gains.copyOf()
        g[i] = db.coerceIn(-MAX_DB, MAX_DB)
        gains = g
        preset = EqPreset.entries.firstOrNull { it.gains.contentEquals(g) }
        if (!eqOn) eqOn = true
        prefs.edit {
            putBoolean("eq_on", true)
            putString("gains", g.joinToString(","))
            putString("preset", preset?.name ?: "")
        }
        changed()
    }

    fun updateBass(v: Float) { bass = v.coerceIn(0f, 1f); prefs.edit { putFloat("bass", bass) }; changed() }
    fun updateTreble(v: Float) { treble = v.coerceIn(0f, 1f); prefs.edit { putFloat("treble", treble) }; changed() }
    fun updatePitch(semis: Float) { pitch = semis.coerceIn(-12f, 12f); prefs.edit { putFloat("pitch", pitch) }; changed() }
    fun updateReverb(v: Float) { reverb = v.coerceIn(0f, 1f); prefs.edit { putFloat("reverb", reverb) }; changed() }
    fun updateRoom(r: Room) { room = r; prefs.edit { putString("room", r.name) }; changed() }
    fun updateDrive(v: Float) { drive = v.coerceIn(0f, 1f); prefs.edit { putFloat("drive", drive) }; changed() }
    fun updateBalance(v: Float) { balance = v.coerceIn(-1f, 1f); prefs.edit { putFloat("balance", balance) }; changed() }
    fun updateMono(on: Boolean) { mono = on; prefs.edit { putBoolean("mono", on) }; changed() }
    fun updateWidth(v: Float) { width = v.coerceIn(0f, 1f); prefs.edit { putFloat("width", width) }; changed() }

    /** Back to how the song was recorded. */
    fun resetAll() {
        eqOn = false; gains = FloatArray(BANDS.size); preset = EqPreset.FLAT
        bass = 0f; treble = 0f; pitch = 0f; reverb = 0f; drive = 0f; balance = 0f; mono = false; width = 0f
        prefs.edit { clear() }
        changed()
    }

    /** Pitch as the player wants it: 1 = unchanged, 2 = an octave up. */
    fun pitchFactor(): Float = Math.pow(2.0, pitch / 12.0).toFloat()

    /** True when anything here changes the sound. */
    val anyOn: Boolean
        get() = (eqOn && gains.any { it != 0f }) || bass > 0f || treble > 0f || pitch != 0f || reverb > 0f ||
            drive > 0f || balance != 0f || mono || width > 0f
}
