// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One setting that "Search any settings" can find: its name, the settings page it's on, the heading
 * it sits under (so the page can scroll to it), and other words people might type for it.
 */
internal data class SettingEntry(val title: String, val page: String, val group: String? = null, val words: String = "")

/** Where a search result wants to go: the heading to scroll to on the page that just opened. */
internal class SettingsJump(val group: String?, val scroll: ScrollState, val done: () -> Unit)

internal val LocalSettingsJump = staticCompositionLocalOf<SettingsJump?> { null }

/** Every setting, for "Search any settings". Page names are SettingsPage names. */
internal object SettingsIndex {
    private val all = listOf(
        // Look
        SettingEntry("Frosted glass", "LOOK", "Style", "liquid glass look theme style light dark frost"),
        SettingEntry("Home songs: list or covers", "LOOK", "Layout", "home layout grid big covers list view"),
        SettingEntry("Library: grid or list", "LOOK", "Layout", "library layout playlists grid list view"),
        SettingEntry("Colours from the cover", "LOOK", "App colours", "accent colour color theme tint"),
        SettingEntry("Reset to default colours", "LOOK", "App colours", "colour color reset default"),
        SettingEntry("Wallpaper", "LOOK", "Wallpaper", "background picture image photo"),
        SettingEntry("Wallpaper blur", "LOOK", "Wallpaper", "blur background"),
        SettingEntry("Darken wallpaper", "LOOK", "Wallpaper", "darken dim background"),
        SettingEntry("Colour strength", "LOOK", "Wallpaper", "colour color strength saturation vivid"),
        SettingEntry("Glass panels", "LOOK", "Wallpaper", "glass opacity clear frosted panels"),
        SettingEntry("Visualizer", "LOOK", "Visualizer", "bars mirror wave line dots glow peaks spectrum visualiser"),
        SettingEntry("Visualizer size", "LOOK", "Visualizer look", "small medium large big tall visualiser"),
        SettingEntry("Visualizer colour", "LOOK", "Visualizer look", "rainbow white song color visualiser"),
        SettingEntry("Visualizer feel", "LOOK", "Visualizer look", "smooth snappy speed visualiser"),
        SettingEntry("Font", "LOOK", "Font", "text typeface letters"),
        SettingEntry("Player style", "LOOK", "Player style", "now playing player screen layout"),
        SettingEntry("Play button", "LOOK", "Play button", "play pause button shape"),
        SettingEntry("Music videos behind the cover", "LOOK", "Music videos behind the cover", "video canvas background clip"),
        SettingEntry("Animated covers", "LOOK", "Animated covers", "motion moving cover art apple"),
        SettingEntry("Living cover", "LOOK", "Living cover", "cover moving alive"),
        SettingEntry("Mini player", "LOOK", "Mini player", "small window floating on top pip"),
        SettingEntry("Mini player transparency", "LOOK", "Mini player", "see-through clear opacity"),
        SettingEntry("Mini player depth", "LOOK", "Mini player", "shadow depth"),
        SettingEntry("Mini player corners", "LOOK", "Mini player", "rounded corners square"),
        SettingEntry("Mini player: keep on top", "LOOK", "Mini player", "always on top"),
        SettingEntry("Mini player progress bar", "LOOK", "Mini player", "progress time bar"),
        SettingEntry("Animations", "LOOK", "Effects", "motion reduce effects smooth"),
        // Player & audio
        SettingEntry("Audio quality", "AUDIO", "Audio quality", "bitrate high low normal sound quality streaming"),
        SettingEntry("Automix", "AUDIO", "Automix", "crossfade dj transitions mix blend fade"),
        SettingEntry("Autoplay", "AUDIO", "Playback", "keep playing similar songs radio"),
        SettingEntry("Skip silence", "AUDIO", "Playback", "silence quiet gaps"),
        SettingEntry("Volume leveling", "SOUND", "Volume leveling", "loudness normalize normalise even out volume replaygain"),
        SettingEntry("Report songs that won't play", "GENERAL", "Help make Glasswave better", "problems errors report"),
        SettingEntry("Count me in usage numbers", "GENERAL", "Help make Glasswave better", "stats usage anonymous count"),
        SettingEntry("Shuffle & repeat beside controls", "AUDIO", "Playback", "shuffle repeat buttons player"),
        // Sound & effects
        SettingEntry("Equalizer", "SOUND", "Equalizer", "eq equaliser bass treble graph curve bands presets audio lab"),
        SettingEntry("Save your own equalizer sound", "SOUND", "Equalizer", "custom preset save eq"),
        SettingEntry("Playback speed", "SOUND", "Playback speed", "speed tempo faster slower 1x"),
        SettingEntry("Slowed", "SOUND", "Playback speed", "slow slowed down tiktok"),
        SettingEntry("Sped up", "SOUND", "Playback speed", "fast faster sped up nightcore tiktok"),
        SettingEntry("Slowed + reverb", "SOUND", "Playback speed", "slowed reverb tiktok edit"),
        SettingEntry("Spatial audio", "SOUND", "Spatial audio", "surround 3d headphones around you dolby atmos immersive virtual"),
        SettingEntry("Bass boost", "SOUND", "Tone", "bass low end boom"),
        SettingEntry("Treble boost", "SOUND", "Tone", "treble highs bright clear"),
        SettingEntry("Stereo width", "SOUND", "Tone", "stereo wide width"),
        SettingEntry("Reverb", "SOUND", "Effects", "room hall arena echo"),
        SettingEntry("Distortion", "SOUND", "Effects", "drive fuzz warm"),
        SettingEntry("Left / right balance", "SOUND", "Balance", "balance left right ear"),
        SettingEntry("Mono", "SOUND", "Balance", "one earbud mono"),
        SettingEntry("Reset all sound settings", "SOUND", "Balance", "reset default sound"),
        // Accounts
        SettingEntry("Discord", "ACCOUNTS", "Discord", "discord status profile rich presence playing"),
        SettingEntry("YouTube Music account", "ACCOUNTS", "YouTube Music", "sign in log in google account log out youtube"),
        SettingEntry("Your devices", "ACCOUNTS", "Your devices", "sync phone pc computer devices"),
        SettingEntry("Import from a link", "ACCOUNTS", "Import from a link", "playlist link paste import"),
        SettingEntry("Spotify", "ACCOUNTS", "Spotify · sync your whole library", "spotify sync import liked songs library playlists"),
        SettingEntry("Sync now", "ACCOUNTS", "Sync", "sync playlists bring in import refresh"),
        // Storage & privacy
        SettingEntry("Downloads", "STORAGE", "Downloads", "offline downloaded delete space"),
        SettingEntry("Download quality on Wi-Fi", "STORAGE", "Download quality on Wi-Fi", "wifi download quality"),
        SettingEntry("Download quality on mobile data", "STORAGE", "Download quality on mobile data", "mobile data cellular download quality"),
        SettingEntry("Played songs (temporary)", "STORAGE", "Played songs (temporary)", "cache temporary saved"),
        SettingEntry("Space for played songs", "STORAGE", "Space for played songs", "cache size space storage"),
        SettingEntry("Remember what was playing", "STORAGE", "Privacy", "resume continue last song"),
        SettingEntry("Remember what I play", "STORAGE", "Privacy", "history privacy listening"),
        SettingEntry("Clear listening history", "STORAGE", "Privacy", "history delete clear"),
        SettingEntry("Clear search history", "STORAGE", "Privacy", "search history delete clear"),
        SettingEntry("Re-match Spotify songs", "STORAGE", "Privacy", "spotify match songs wrong"),
        // About
        SettingEntry("Updates", "GENERAL", "Updates", "update version new check"),
        SettingEntry("About Glasswave", "ABOUT", null, "version credits licence nonny dev"),
    )

    /** Settings matching every word of [query], best matches first. */
    fun search(query: String, pageTitle: (String) -> String): List<SettingEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.mapNotNull { e ->
            val title = e.title.lowercase()
            val hay = listOf(title, e.group.orEmpty(), pageTitle(e.page), e.words).joinToString(" ").lowercase()
            if (!words.all { it in hay }) return@mapNotNull null
            val score = when {
                title.startsWith(words.first()) -> 0
                words.all { it in title } -> 1
                words.any { it in title } -> 2
                else -> 3
            }
            score to e
        }.sortedBy { it.first }.map { it.second }
    }
}
