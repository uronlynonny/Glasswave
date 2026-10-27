// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
    CARD("Card", "Big rounded cover that shrinks a little when paused."),
    PANEL("Glass panel", "Controls sit on a glass panel tinted by the cover."),
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

/** The moving bars in the player that dance to the music. */
enum class VisualizerStyle(val label: String, val detail: String) {
    OFF("Off", "No visualizer."),
    BARS("Bars", "Bars that jump with each beat, bass on the left."),
    MIRROR("Mirror", "Bars that grow up and down from the middle."),
}

enum class CacheSize(val label: String, val bytes: Long) {
    S256("256 MB", 256L shl 20), S512("512 MB", 512L shl 20), G1("1 GB", 1L shl 30), G2("2 GB", 2L shl 30), G4("4 GB", 4L shl 30),
}

/** App settings, saved on the phone. The values are Compose state so screens update right away. */
object Settings {
    private lateinit var prefs: SharedPreferences

    var quality by mutableStateOf(Quality.HIGH); private set
    var skipSilence by mutableStateOf(false); private set
    var volumeLeveling by mutableStateOf(true); private set
    /** Automix: the end of one song plays under the start of the next, like a DJ blending them. Off by default. */
    var automix by mutableStateOf(false); private set
    /** How long two songs overlap when mixing, in seconds. */
    var mixSeconds by mutableIntStateOf(8); private set
    var animations by mutableStateOf(true); private set
    var glass by mutableStateOf(true); private set
    var playerStyle by mutableStateOf(PlayerStyle.CARD); private set
    var buttonStyle by mutableStateOf(ButtonStyle.CLASSIC); private set
    var showModeButtons by mutableStateOf(false); private set
    var cacheSize by mutableStateOf(CacheSize.G1); private set
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

    /** Your own highlight colour (ARGB), or null to take it from each song's cover. */
    var accentColor by mutableStateOf<Int?>(null); private set
    var visualizer by mutableStateOf(VisualizerStyle.BARS); private set
    /** A font file you imported (kept inside the app), or null for the standard font. */
    var fontFile by mutableStateOf<String?>(null); private set
    /** Its name, for Settings. */
    var fontName by mutableStateOf(""); private set
    /** Your own picture, GIF or video behind the app (a file inside the app), or null for the cover's colours. */
    var wallpaper by mutableStateOf<String?>(null); private set
    /** 0..1: how soft the wallpaper is. */
    var wallpaperBlur by mutableFloatStateOf(0.25f); private set
    /** 0..1: how much the wallpaper is darkened, so text stays readable. */
    var wallpaperDim by mutableFloatStateOf(0.35f); private set
    /** 0.3..1.7: how strong the colours behind everything are. */
    var colorStrength by mutableFloatStateOf(1f); private set
    /** 0.3..1.8: how see-through (low) or solid (high) the glass panels are. */
    var glassOpacity by mutableFloatStateOf(1f); private set

    fun updateAccentColor(argb: Int?) {
        accentColor = argb
        prefs.edit {
            if (argb == null) remove("accent") else putInt("accent", argb)
            // Kept, so going back to "My own colour" brings back the one you had.
            if (argb != null) putInt("accent_last", argb)
        }
    }

    /** The last colour you picked yourself (even while the colours follow the song). */
    fun lastOwnAccent(): Int? = if (prefs.contains("accent_last")) prefs.getInt("accent_last", 0) else null
    fun updateVisualizer(v: VisualizerStyle) { visualizer = v; prefs.edit { putString("visualizer", v.name) } }
    fun updateFont(path: String?, name: String) {
        fontFile = path
        fontName = name
        prefs.edit {
            if (path == null) remove("font_file") else putString("font_file", path)
            putString("font_name", name)
        }
    }
    fun updateWallpaper(path: String?) {
        wallpaper = path
        prefs.edit { if (path == null) remove("wallpaper") else putString("wallpaper", path) }
    }
    fun updateWallpaperBlur(v: Float) { wallpaperBlur = v.coerceIn(0f, 1f); prefs.edit { putFloat("wallpaper_blur", wallpaperBlur) } }
    fun updateWallpaperDim(v: Float) { wallpaperDim = v.coerceIn(0f, 0.9f); prefs.edit { putFloat("wallpaper_dim", wallpaperDim) } }
    fun updateColorStrength(v: Float) { colorStrength = v.coerceIn(0.3f, 1.7f); prefs.edit { putFloat("color_strength", colorStrength) } }
    fun updateGlassOpacity(v: Float) { glassOpacity = v.coerceIn(0.3f, 1.8f); prefs.edit { putFloat("glass_opacity", glassOpacity) } }
    /** Tell nonny dev when a song won't play (song, error, Windows version, app version). On by default. */
    var reportProblems by mutableStateOf(true); private set
    /** Phone and PC on the same YouTube Music account share listening stats and can control each other. On by default. */
    var syncDevices by mutableStateOf(true); private set
    fun updateSyncDevices(on: Boolean) {
        syncDevices = on
        prefs.edit { putBoolean("sync_devices", on) }
        if (on) Sync.start() else Sync.stop()
    }

    fun updateReportProblems(on: Boolean) { reportProblems = on; prefs.edit { putBoolean("report_problems", on) } }
    /** Count this PC in Glasswave's anonymous active-user numbers (a random id once a day). On by default. */
    var countMe by mutableStateOf(true); private set
    fun updateCountMe(on: Boolean) { countMe = on; prefs.edit { putBoolean("count_me", on) } }
    /** Show what's playing on your Discord profile. */
    var discord by mutableStateOf(true); private set
    fun updateDiscord(on: Boolean) { discord = on; prefs.edit { putBoolean("discord", on) } }

    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, null) ?: def.name) }.getOrDefault(def)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        quality = enumPref("quality", Quality.HIGH)
        skipSilence = prefs.getBoolean("skip_silence", false)
        volumeLeveling = prefs.getBoolean("volume_leveling", true)
        automix = prefs.getBoolean("automix", false)
        mixSeconds = prefs.getInt("mix_seconds", 8)
        animations = prefs.getBoolean("animations", true)
        glass = prefs.getBoolean("glass", true)
        // The PC has two player styles: Card and Glass panel. Anything else saved becomes Card.
        playerStyle = enumPref("player_style", PlayerStyle.CARD).let { if (it == PlayerStyle.PANEL) it else PlayerStyle.CARD }
        buttonStyle = enumPref("button_style", ButtonStyle.CLASSIC)
        showModeButtons = prefs.getBoolean("mode_buttons", false)
        cacheSize = enumPref("cache_size", CacheSize.G1)
        keepHistory = prefs.getBoolean("history", true)
        coverMotion = enumPref("cover_motion", CoverMotion.WIFI)
        autoplay = prefs.getBoolean("autoplay", true)
        crossfade = prefs.getInt("crossfade", 0)
        downloadWifi = enumPref("dl_wifi", Quality.HIGH)
        downloadData = enumPref("dl_data", Quality.NORMAL)
        livingCover = prefs.getBoolean("living_cover", true)
        frameRate = enumPref("frame_rate", FrameRate.OFF)
        // No performance mode on PC: it always runs the efficient way.
        highPerformance = false
        discord = prefs.getBoolean("discord", true)
        reportProblems = prefs.getBoolean("report_problems", true)
        syncDevices = prefs.getBoolean("sync_devices", true)
        countMe = prefs.getBoolean("count_me", true)
        accentColor = if (prefs.contains("accent")) prefs.getInt("accent", 0) else null
        visualizer = enumPref("visualizer", VisualizerStyle.BARS)
        fontFile = prefs.getString("font_file", null)?.takeIf { java.io.File(it).exists() }
        fontName = prefs.getString("font_name", "") ?: ""
        wallpaper = prefs.getString("wallpaper", null)?.takeIf { java.io.File(it).exists() }
        wallpaperBlur = prefs.getFloat("wallpaper_blur", 0.25f)
        wallpaperDim = prefs.getFloat("wallpaper_dim", 0.35f)
        colorStrength = prefs.getFloat("color_strength", 1f)
        glassOpacity = prefs.getFloat("glass_opacity", 1f)
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
