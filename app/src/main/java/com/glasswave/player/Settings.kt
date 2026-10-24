// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Audio quality. Each level lists YouTube's audio formats (itags) best-first:
 * 251 = Opus ~160 kbps, 140 = AAC 128 kbps, 250 = Opus ~70 kbps, 249 = Opus ~50 kbps, 139 = AAC 48 kbps.
 */
enum class Quality(val label: String, val detail: String, val itags: List<Int>) {
    LOW("Low", "About 50 kbps. Uses the least data.", listOf(249, 139, 250, 140, 251)),
    NORMAL("Normal", "128 kbps AAC. Good on mobile data.", listOf(140, 250, 251, 249, 139)),
    HIGH("High", "Best YouTube offers, about 160 kbps Opus.", listOf(251, 141, 140, 250, 249, 139)),
}

enum class PlayerStyle(val label: String, val detail: String) {
    IMMERSIVE("Immersive", "Cover fills the top of the screen, edge to edge."),
    CANVAS("Full screen", "Cover or video fills the whole screen behind the controls."),
    CARD("Card", "Big rounded cover that shrinks when paused."),
    PANEL("Glass panel", "Controls sit on a glass panel over the cover."),
    MINIMAL("Minimal", "Smaller cover, big title, lots of space."),
}

/** How the play / previous / next buttons look. All stay close to the original. */
enum class ButtonStyle(val label: String, val detail: String) {
    CLASSIC("Classic", "Plain white icons."),
    GLASS("Glass", "Play button in a glass bubble."),
    SOLID("Solid", "White play button with a dark icon."),
    TINTED("Tinted", "Play button in the cover's colour."),
}

enum class CoverMotion(val label: String, val detail: String) {
    OFF("Off", "Never play videos. Always show the cover."),
    WIFI("Wi-Fi only", "Videos play on Wi-Fi. On mobile data you see the cover."),
    AUTO("Wi-Fi and mobile data", "Videos play on both. Uses more data; quality follows your connection."),
}

/** Screen refresh rate. OFF lets the phone decide, but never above 90 Hz (saves battery). */
enum class FrameRate(val label: String, val detail: String, val hz: Float) {
    OFF("Off", "Phone decides, capped at 90 Hz.", 90f),
    F60("60 Hz", "Smooth, and easiest on the battery.", 60f),
    F90("90 Hz", "Smoother scrolling.", 90f),
    F120("120 Hz", "Smoothest, if your screen supports it. Uses more battery.", 120f),
}

enum class CacheSize(val label: String, val bytes: Long) {
    S256("256 MB", 256L shl 20), S512("512 MB", 512L shl 20), G1("1 GB", 1L shl 30), G2("2 GB", 2L shl 30),
}

/** App settings, saved on the phone. The values are Compose state so screens update right away. */
object Settings {
    private lateinit var prefs: SharedPreferences

    var quality by mutableStateOf(Quality.HIGH); private set
    var skipSilence by mutableStateOf(false); private set
    var volumeLeveling by mutableStateOf(true); private set
    /** Tell nonny dev when a song won't play (song, error, phone model, app version). On by default. */
    var reportProblems by mutableStateOf(true); private set
    /** Count this phone in the daily active-user numbers (a random id, nothing else). On by default. */
    var countMe by mutableStateOf(true); private set
    /** Automix: the end of one song plays under the start of the next, like a DJ blending them. Off by default. */
    var automix by mutableStateOf(false); private set
    /** How long two songs overlap when mixing, in seconds. */
    var mixSeconds by mutableIntStateOf(8); private set
    var animations by mutableStateOf(true); private set
    var glass by mutableStateOf(true); private set
    var playerStyle by mutableStateOf(PlayerStyle.IMMERSIVE); private set
    var buttonStyle by mutableStateOf(ButtonStyle.CLASSIC); private set
    var showModeButtons by mutableStateOf(false); private set
    var cacheSize by mutableStateOf(CacheSize.S512); private set
    var keepHistory by mutableStateOf(true); private set
    var coverMotion by mutableStateOf(CoverMotion.WIFI); private set
    var autoplay by mutableStateOf(true); private set
    /** Seconds the song fades out at its end and in at its start. 0 = off. */
    var crossfade by mutableIntStateOf(0); private set
    /** Quality for downloads made on Wi-Fi, and on mobile data. */
    var downloadWifi by mutableStateOf(Quality.HIGH); private set
    var downloadData by mutableStateOf(Quality.NORMAL); private set
    /** Gently animates the still cover in the full player while a song plays. */
    var livingCover by mutableStateOf(true); private set
    var frameRate by mutableStateOf(FrameRate.OFF); private set
    /** Smoother everything at the cost of battery: every-frame motion, more preloading, sustained CPU. */
    var highPerformance by mutableStateOf(false); private set

    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, null) ?: def.name) }.getOrDefault(def)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        quality = enumPref("quality", Quality.HIGH)
        skipSilence = prefs.getBoolean("skip_silence", false)
        volumeLeveling = prefs.getBoolean("volume_leveling", true)
        reportProblems = prefs.getBoolean("report_problems", true)
        countMe = prefs.getBoolean("count_me", true)
        automix = prefs.getBoolean("automix", false)
        mixSeconds = prefs.getInt("mix_seconds", 8)
        animations = prefs.getBoolean("animations", true)
        glass = prefs.getBoolean("glass", true)
        playerStyle = enumPref("player_style", PlayerStyle.IMMERSIVE)
        buttonStyle = enumPref("button_style", ButtonStyle.CLASSIC)
        showModeButtons = prefs.getBoolean("mode_buttons", false)
        cacheSize = enumPref("cache_size", CacheSize.S512)
        keepHistory = prefs.getBoolean("history", true)
        coverMotion = enumPref("cover_motion", CoverMotion.WIFI)
        autoplay = prefs.getBoolean("autoplay", true)
        crossfade = prefs.getInt("crossfade", 0)
        downloadWifi = enumPref("dl_wifi", Quality.HIGH)
        downloadData = enumPref("dl_data", Quality.NORMAL)
        livingCover = prefs.getBoolean("living_cover", true)
        frameRate = enumPref("frame_rate", FrameRate.OFF)
        highPerformance = prefs.getBoolean("high_perf", false)
    }

    fun updateFrameRate(f: FrameRate) { frameRate = f; prefs.edit { putString("frame_rate", f.name) } }
    fun updateHighPerformance(on: Boolean) { highPerformance = on; prefs.edit { putBoolean("high_perf", on) } }

    /** Raw value for the image cache, read before Settings is shown. */
    fun highPerformanceRaw(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("high_perf", false)

    fun updateDownloadWifi(q: Quality) { downloadWifi = q; prefs.edit { putString("dl_wifi", q.name) } }
    fun updateDownloadData(q: Quality) { downloadData = q; prefs.edit { putString("dl_data", q.name) } }
    fun updateLivingCover(on: Boolean) { livingCover = on; prefs.edit { putBoolean("living_cover", on) } }

    fun updateQuality(q: Quality) {
        quality = q
        prefs.edit { putString("quality", q.name) }
        YtRepo.forgetStreamUrls()
    }

    fun updateSkipSilence(on: Boolean) {
        skipSilence = on
        prefs.edit { putBoolean("skip_silence", on) }
        listeners.forEach { it() }
    }

    fun updateCountMe(on: Boolean) { countMe = on; prefs.edit { putBoolean("count_me", on) } }

    fun updateReportProblems(on: Boolean) {
        reportProblems = on
        prefs.edit { putBoolean("report_problems", on) }
        listeners.forEach { it() }
    }

    fun updateVolumeLeveling(on: Boolean) {
        volumeLeveling = on
        prefs.edit { putBoolean("volume_leveling", on) }
        listeners.forEach { it() }
    }

    fun updateAnimations(on: Boolean) { animations = on; prefs.edit { putBoolean("animations", on) } }
    fun updateGlass(on: Boolean) { glass = on; prefs.edit { putBoolean("glass", on) } }
    fun updatePlayerStyle(s: PlayerStyle) { playerStyle = s; prefs.edit { putString("player_style", s.name) } }
    fun updateButtonStyle(s: ButtonStyle) { buttonStyle = s; prefs.edit { putString("button_style", s.name) } }
    fun updateShowModeButtons(on: Boolean) { showModeButtons = on; prefs.edit { putBoolean("mode_buttons", on) } }
    fun updateCacheSize(s: CacheSize) { cacheSize = s; prefs.edit { putString("cache_size", s.name) } }
    fun updateCoverMotion(m: CoverMotion) { coverMotion = m; prefs.edit { putString("cover_motion", m.name) } }
    fun updateKeepHistory(on: Boolean) { keepHistory = on; prefs.edit { putBoolean("history", on) } }
    fun updateAutoplay(on: Boolean) {
        autoplay = on
        prefs.edit { putBoolean("autoplay", on) }
        listeners.forEach { it() } // the player adds or removes Autoplay songs
    }
    fun updateAutomix(on: Boolean) { automix = on; prefs.edit { putBoolean("automix", on) } }
    fun updateMixSeconds(sec: Int) { mixSeconds = sec.coerceIn(3, 15); prefs.edit { putInt("mix_seconds", mixSeconds) } }

    fun updateCrossfade(sec: Int) { crossfade = sec.coerceIn(0, 12); prefs.edit { putInt("crossfade", crossfade) } }

    /** Raw preferences for the cache size, read before the cache opens. */
    fun cacheBytes(context: Context): Long =
        runCatching {
            CacheSize.valueOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("cache_size", null) ?: "S512").bytes
        }.getOrDefault(CacheSize.S512.bytes)

    /** The player service listens here for playback settings. */
    val listeners = mutableListOf<() -> Unit>()
}
