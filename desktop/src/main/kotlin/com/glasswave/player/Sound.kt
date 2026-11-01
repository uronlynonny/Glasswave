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
 * Spatial sound for headphones: the music seems to come from speakers out in front of you instead
 * of from inside your head (see [SoundFx]). [cross]: how much each ear hears of the other side,
 * [itdMs]: how much later it gets there, [side]: extra stereo width, [early] and [tail]: how much of
 * the room you hear, [trim]: keeps it about as loud as with it off.
 */
enum class Spatial(
    val label: String, val about: String,
    val cross: Float, val itdMs: Float, val side: Float, val early: Float, val tail: Float, val trim: Float,
) {
    OFF("Off", "As recorded", 0f, 0f, 1f, 0f, 0f, 1f),
    SUBTLE("Subtle", "A little more room, close to the original", 0.35f, 0.26f, 1.0f, 0.24f, 0.14f, 1f),
    NATURAL("Natural", "Like good speakers in front of you", 0.50f, 0.30f, 1.06f, 0.36f, 0.22f, 0.98f),
    WIDE("Wide", "A big, open stage around you", 0.45f, 0.34f, 1.14f, 0.46f, 0.30f, 0.955f),
}

/**
 * A ready-made playing speed for every song. [pitchFollows]: the pitch moves with the speed, like a
 * record played slower or faster. [reverb] (0..1, in [room]) is added on top of your own reverb
 * while it's on; your own setting stays as it is.
 */
enum class SpeedPreset(val label: String, val about: String, val speed: Float, val pitchFollows: Boolean, val reverb: Float, val room: Room) {
    NORMAL("Normal", "As recorded", 1f, false, 0f, Room.HALL),
    SLOWED("Slowed", "Slower and deeper, like a slowed-down record", 0.85f, true, 0f, Room.HALL),
    SPED_UP("Sped up", "Faster and higher, nightcore style", 1.25f, true, 0f, Room.HALL),
    SLOWED_REVERB("Slowed + reverb", "Slowed down, in a big echoing hall", 0.85f, true, 0.5f, Room.HALL),
}

/**
 * Glasswave's own sound settings: a 10-band equalizer, bass boost, pitch, reverb, distortion,
 * balance, mono, spatial sound and speed presets. They're applied inside the player (see [SoundProcessor]), so they work the
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
    /** Spatial sound for headphones. */
    var spatial by mutableStateOf(Spatial.OFF); private set
    /** The playing speed for every song (Normal, Slowed, Sped up...). */
    var speedPreset by mutableStateOf(SpeedPreset.NORMAL); private set

    /** Goes up on every change, so the audio thread knows to pick up new settings. */
    @Volatile
    var version = 0
        private set

    /** The player service listens here (for speed and pitch). */
    val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
        eqOn = prefs.getBoolean("eq_on", false)
        gains = prefs.getString("gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == BANDS.size }?.toFloatArray() ?: FloatArray(BANDS.size)
        preset = prefs.getString("preset", EqPreset.FLAT.name)?.let { n -> EqPreset.entries.firstOrNull { it.name == n } }
        bass = prefs.getFloat("bass", 0f)
        treble = prefs.getFloat("treble", 0f)
        // Pitch and speed were taken out of the settings: songs always play as recorded.
        pitch = 0f
        prefs.edit { remove("pitch") }
        reverb = prefs.getFloat("reverb", 0f)
        room = runCatching { Room.valueOf(prefs.getString("room", null) ?: "HALL") }.getOrDefault(Room.HALL)
        drive = prefs.getFloat("drive", 0f)
        balance = prefs.getFloat("balance", 0f)
        mono = prefs.getBoolean("mono", false)
        width = prefs.getFloat("width", 0f)
        spatial = runCatching { Spatial.valueOf(prefs.getString("spatial", null) ?: "OFF") }.getOrDefault(Spatial.OFF)
        speedPreset = runCatching { SpeedPreset.valueOf(prefs.getString("speed_preset", null) ?: "NORMAL") }.getOrDefault(SpeedPreset.NORMAL)
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
    fun updateSpatial(s: Spatial) { spatial = s; prefs.edit { putString("spatial", s.name) }; changed() }
    fun updateSpeedPreset(p: SpeedPreset) { speedPreset = p; prefs.edit { putString("speed_preset", p.name) }; changed() }

    /** Back to how the song was recorded. */
    fun resetAll() {
        eqOn = false; gains = FloatArray(BANDS.size); preset = EqPreset.FLAT
        bass = 0f; treble = 0f; pitch = 0f; reverb = 0f; drive = 0f; balance = 0f; mono = false; width = 0f
        spatial = Spatial.OFF; speedPreset = SpeedPreset.NORMAL
        prefs.edit { clear() }
        changed()
    }

    /** Your own pitch setting: 1 = unchanged, 2 = an octave up. */
    fun pitchFactor(): Float = Math.pow(2.0, pitch / 12.0).toFloat()

    /** The speed the player should play at (from [speedPreset]): 1 = as recorded. */
    fun tempo(): Float = speedPreset.speed

    /** The pitch the player should play at: your own pitch, moved with the speed when the speed preset says so. */
    fun playPitch(): Float = pitchFactor() * (if (speedPreset.pitchFollows) speedPreset.speed else 1f)

    /** True when anything here changes the sound. */
    val anyOn: Boolean
        get() = (eqOn && gains.any { it != 0f }) || bass > 0f || treble > 0f || pitch != 0f || reverb > 0f ||
            drive > 0f || balance != 0f || mono || width > 0f || spatial != Spatial.OFF || speedPreset != SpeedPreset.NORMAL
}
