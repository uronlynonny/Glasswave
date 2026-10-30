// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import coil3.compose.LocalPlatformContext as LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector, val color: Color) {
    LOOK("Look", "Colours, wallpaper, fonts, visualizer, player style", Icons.Rounded.Palette, Color(0xFFAF52DE)),
    AUDIO("Player & audio", "Quality, Automix, autoplay", Icons.Rounded.MusicNote, Color(0xFFFF375F)),
    SOUND("Sound & effects", "10-band equalizer, bass, reverb", Icons.Rounded.GraphicEq, Color(0xFFFF9F0A)),
    ACCOUNTS("Accounts", "YouTube Music, Spotify and Discord", Icons.Rounded.AccountCircle, Color(0xFF0A84FF)),
    SYNC("Sync", "Bring your playlists in", Icons.Rounded.Sync, Color(0xFF30D158)),
    STORAGE("Storage & privacy", "Downloads, saved songs, history", Icons.Rounded.Storage, Color(0xFFFF9F0A)),
    ABOUT("About", "Version and credits", Icons.Rounded.Info, Color(0xFF8E8E93)),
}

/* ---------------- Building blocks ---------------- */

@Composable
private fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val clean = Settings.cleanLook
    // Frosted glass: a small grey heading over a calm frosted card (the card's look comes from glass()).
    if (title != null) {
        if (clean) GText(title.uppercase(), 12, CleanGrey, FontWeight.SemiBold, 1, Modifier.padding(start = 32.dp, top = 26.dp, bottom = 8.dp))
        else GText(title.uppercase(), 13, Faint, FontWeight.SemiBold, modifier = Modifier.padding(start = 30.dp, top = 22.dp, bottom = 8.dp))
    } else Spacer(Modifier.height(14.dp))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).glass(RoundedCornerShape(if (clean) 20.dp else 22.dp), base = 0.28f, light = 0.08f).padding(vertical = 4.dp),
        content = content,
    )
}

/** Frosted glass's grey for headings and values (see-through, so it reads over any colour). */
private val CleanGrey = Color.White.copy(alpha = 0.55f)

/** Frosted glass: a plain white icon at the start of a row, picked from its title. Nothing in liquid glass. */
@Composable
private fun RowIcon(title: String) {
    if (!Settings.cleanLook) return
    Ico(iconFor(title), Color.White, 22.dp)
    Spacer(Modifier.width(14.dp))
}

/** An icon that fits a setting, found from words in its title (Tune when nothing fits). */
private fun iconFor(title: String): ImageVector {
    val t = title.lowercase()
    fun has(vararg w: String) = w.any { it in t }
    return when {
        has("frosted glass") -> Icons.Rounded.Contrast
        has("colour", "color", "theme") -> Icons.Rounded.Palette
        has("glass", "blur") -> Icons.Rounded.BlurOn
        has("darken") -> Icons.Rounded.Brightness4
        has("animation") -> Icons.Rounded.Animation
        has("cover") -> Icons.Rounded.Album
        has("automix", "flow") -> Icons.Rounded.AutoAwesome
        has("autoplay") -> Icons.AutoMirrored.Rounded.PlaylistPlay
        has("silence") -> Icons.AutoMirrored.Rounded.VolumeOff
        has("leveling", "equalizer") -> Icons.Rounded.Equalizer
        has("lyrics") -> Icons.Rounded.Lyrics
        has("quality", "visualizer", "bass", "treble", "distortion") -> Icons.Rounded.GraphicEq
        has("download") -> Icons.Rounded.Download
        has("wi-fi", "wifi") -> Icons.Rounded.Wifi
        has("data") -> Icons.Rounded.SignalCellularAlt
        has("remember what was") -> Icons.Rounded.Restore
        has("history", "remember") -> Icons.Rounded.History
        has("cache", "storage", "space") -> Icons.Rounded.Storage
        has("report") -> Icons.Rounded.BugReport
        has("count me", "usage") -> Icons.Rounded.BarChart
        has("shuffle") -> Icons.Rounded.Shuffle
        has("privacy") -> Icons.Rounded.Lock
        has("account", "sign in") -> Icons.Rounded.AccountCircle
        has("listening") -> Icons.Rounded.Headphones
        has("sync", "devices") -> Icons.Rounded.Devices
        has("performance", "frame") -> Icons.Rounded.Speed
        has("reverb") -> Icons.Rounded.Waves
        has("stereo", "left / right") -> Icons.Rounded.SurroundSound
        has("mono") -> Icons.Rounded.Hearing
        has("language") -> Icons.Rounded.Language
        has("notification") -> Icons.Rounded.Notifications
        has("sleep") -> Icons.Rounded.Bedtime
        has("keyboard") -> Icons.Rounded.Keyboard
        has("update") -> Icons.Rounded.SystemUpdate
        has("about") -> Icons.Rounded.Info
        else -> Icons.Rounded.Tune
    }
}

/** Frosted glass: a few short choices as one dark pill, the picked part white with dark text. */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).padding(3.dp)) {
        options.forEach { o ->
            val on = o == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) Color.White else Color.Transparent, smooth(200), label = "segment")
            Box(
                Modifier.weight(1f).fillMaxSize().clip(CircleShape).background(bg).pressable(0.97f) { onPick(o) },
                contentAlignment = Alignment.Center,
            ) { GText(label(o), 14, if (on) Color.Black else Color.White, FontWeight.SemiBold, 1) }
        }
    }
}

@Composable
private fun Note(text: String) {
    GText(text, 12, Faint, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
}

@Composable
private fun IconTile(icon: ImageVector, color: Color) {
    // Clear glass tile: no colour, just a faint fill and a thin bright edge.
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.07f))
            .border(
                0.8.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.2f))),
                RoundedCornerShape(9.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, Color.White, 20.dp)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(0.98f) { onChange(!on) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(title)
        Column(Modifier.weight(1f)) {
            GText(title, 16, weight = FontWeight.Medium)
            GText(detail, 13, Dim)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(on, onChange)
    }
}

/** A list of choices with an animated check mark on the picked one. */
@Composable
private fun <T> Choices(options: List<T>, selected: T, title: (T) -> String, detail: (T) -> String, onPick: (T) -> Unit) {
    // Frosted glass: a few short choices become one segmented pill, with the picked one's detail under it.
    if (Settings.cleanLook && options.size in 2..4 && options.all { title(it).length <= 12 }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Segmented(options, selected, title, onPick)
            val d = detail(selected)
            if (d.isNotEmpty()) GText(d, 13, Dim, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp))
        }
        return
    }
    options.forEachIndexed { i, o ->
        if (i > 0) Divider()
        Row(
            Modifier.fillMaxWidth().pressable(0.98f) { onPick(o) }.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                GText(title(o), 16, weight = FontWeight.Medium)
                val d = detail(o)
                if (d.isNotEmpty()) GText(d, 13, Dim)
            }
            AnimatedVisibility(selected == o, enter = scaleIn(bouncy()), exit = scaleOut(smooth(120))) {
                Ico(Icons.Rounded.Check, Accent, 24.dp)
            }
        }
    }
}

@Composable
private fun NavRow(title: String, detail: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(0.98f, onClick = onClick).padding(horizontal = if (Settings.cleanLook) 18.dp else 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Frosted glass: just the white icon, no tile.
        if (Settings.cleanLook) Ico(icon, Color.White, 22.dp) else IconTile(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GText(title, 16, weight = FontWeight.Medium)
            GText(detail, 13, Dim, maxLines = 1)
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 24.dp)
    }
}

/** Puts the colours back as they came: cover colours on, normal colour strength and glass. */
@Composable
private fun ResetColours() {
    val same = Settings.accentColor == null && Settings.colorStrength == 1f && Settings.glassOpacity == 1f
    Row(
        Modifier.fillMaxWidth().pressable(0.98f) {
            Settings.updateAccentColor(null)
            Settings.updateColorStrength(1f)
            Settings.updateGlassOpacity(1f)
            Menus.say("Colours are back to default")
        }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon("Reset colours")
        Column(Modifier.weight(1f)) {
            GText("Reset to default colours", 16, if (same) Faint else Color.White, FontWeight.Medium)
            GText("Cover colours on, normal colour strength and glass", 13, Dim)
        }
        Spacer(Modifier.width(12.dp))
        Ico(Icons.Rounded.RestartAlt, if (same) Faint else Color.White, 22.dp)
    }
}

@Composable
private fun Divider() {
    // Frosted glass: starts after the row icons, and fainter.
    if (Settings.cleanLook) Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
    else Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
}

/* ---------------- Screen ---------------- */

@Composable
fun SettingsScreen(c: ScreenCtx) {
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    // Opened from a shortcut (e.g. "Accounts & settings" on the profile sheet).
    androidx.compose.runtime.LaunchedEffect(c.vm.settingsJump) {
        val jump = c.vm.settingsJump ?: return@LaunchedEffect
        if (SettingsPage.entries.any { it.name == jump }) pageName = jump
        c.vm.settingsJump = null
    }
    val page = pageName?.let { SettingsPage.valueOf(it) }
    BackHandler(enabled = page != null) { pageName = null }

    AnimatedContent(
        page,
        transitionSpec = {
            if (targetState != null) (slideInHorizontally(bouncy()) { it } + fadeIn(smooth(200))) togetherWith (slideOutHorizontally(smooth(250)) { -it / 4 } + fadeOut(smooth(200)))
            else (slideInHorizontally(smooth(250)) { -it / 4 } + fadeIn(smooth(200))) togetherWith (slideOutHorizontally(bouncy()) { it } + fadeOut(smooth(200)))
        },
        label = "settingsPage",
    ) { p ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = c.bottom).then(if (isWide()) Modifier.padding(horizontal = 80.dp) else Modifier)) {
            if (p == null) {
                Header("Settings")
                Main(c) { pageName = it.name }
            } else {
                Row(Modifier.padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassCircle(44.dp, c.bg, onClick = { pageName = null }) { Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 22.dp) }
                }
                Header(p.title)
                when (p) {
                    SettingsPage.LOOK -> Look()
                    SettingsPage.AUDIO -> Audio(c)
                    SettingsPage.SOUND -> SoundPage(c)
                    SettingsPage.ACCOUNTS -> AccountsPage(c)
                    SettingsPage.SYNC -> SyncPage(c)
                    SettingsPage.STORAGE -> StoragePage(c)
                    SettingsPage.ABOUT -> About(c)
                }
            }
        }
    }
}

@Composable
private fun Main(c: ScreenCtx, open: (SettingsPage) -> Unit) {
    Group {
        SettingsPage.entries.forEachIndexed { i, p ->
            if (i > 0) {
                if (Settings.cleanLook) Divider()
                else Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
            }
            NavRow(p.title, p.subtitle, p.icon, p.color) { open(p) }
        }
    }
    // Account status at a glance
    Note(
        listOfNotNull(
            if (c.vm.ytSignedIn) "YouTube Music signed in" else null,
            if (c.vm.spSignedIn) "Spotify signed in" else null,
        ).joinToString(" · ").ifEmpty { "Not signed in to any service" },
    )
    // The maker's mark.
    GText(Credits.LINE, 12, Faint, FontWeight.Medium, modifier = Modifier.padding(start = 30.dp, top = 18.dp))
}

@Composable
private fun Look() {
    Group("Style") {
        SwitchRow("Frosted glass", "Calm frosted panels, like a classic music app. Turn off for liquid glass.", Settings.cleanLook) { Settings.updateCleanLook(it) }
        if (Settings.cleanLook) {
            Divider()
            Choices(
                listOf(false, true), Settings.darkFrost,
                { if (it) "Dark" else "Light" },
                { if (it) "Deeper, darker frosted panels." else "Lighter, see-through frosted panels." },
            ) { Settings.updateDarkFrost(it) }
        }
    }
    Group("App colours") {
        SwitchRow("Colours from the cover", "The app takes each song's cover colours", Settings.accentColor == null) { on ->
            Settings.updateAccentColor(if (on) null else Settings.lastOwnAccent() ?: AccentSwatches[0])
        }
        if (Settings.accentColor != null) AccentPicker()
        Divider()
        ResetColours()
    }
    Note("On: the background, buttons, lyrics and visualizer change with each song's cover. Off: the whole app stays one plain colour you pick (lime is Glasswave's own look).")
    Group("Wallpaper") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (Settings.wallpaper == null) "Choose picture, GIF or video" else "Change", true, Modifier.weight(1f)) {
                val path = WallpaperFile.pick()
                if (path != null) Settings.updateWallpaper(path)
            }
            if (Settings.wallpaper != null) {
                Pill("Remove", false) {
                    Settings.updateWallpaper(null)
                    WallpaperFile.remove()
                }
            }
        }
        if (Settings.wallpaper != null) {
            Divider()
            SliderRow("Blur", "${(Settings.wallpaperBlur * 100).toInt()}%", Settings.wallpaperBlur, 0f..1f) { Settings.updateWallpaperBlur(it) }
            SliderRow("Darken", "${(Settings.wallpaperDim * 100).toInt()}%", Settings.wallpaperDim, 0f..0.9f) { Settings.updateWallpaperDim(it) }
        } else {
            Divider()
            SliderRow("Colour strength", "${(Settings.colorStrength * 100).toInt()}%", Settings.colorStrength, 0.3f..1.7f) { Settings.updateColorStrength(it) }
        }
        Divider()
        SliderRow("Glass panels", glassWord(Settings.glassOpacity), Settings.glassOpacity, 0.3f..1.8f) { Settings.updateGlassOpacity(it) }
    }
    Note("Your own picture, moving GIF or video shows behind the app instead of the cover's colours. The full player still shows the song's cover.")
    Group("Visualizer") {
        Choices(VisualizerStyle.entries, Settings.visualizer, { it.label }, { it.detail }) { Settings.updateVisualizer(it) }
    }
    Note("Shown above the progress bar in the player and under the lyrics, in your highlight colour.")
    Group("Font") {
        FontChoices()
    }
    Note("Import any font file (.ttf or .otf), for example one you downloaded from Google Fonts.")
    Group("Player style") {
        Choices(listOf(PlayerStyle.CARD, PlayerStyle.PANEL), Settings.playerStyle, { it.label }, { it.detail }) { Settings.updatePlayerStyle(it) }
    }
    Group("Play button") {
        Choices(ButtonStyle.entries, Settings.buttonStyle, { it.label }, { it.detail }) { Settings.updateButtonStyle(it) }
    }
    Group("Music videos behind the cover") {
        Choices(CoverMotion.entries, Settings.coverMotion, { it.label }, { it.detail }) { Settings.updateCoverMotion(it) }
    }
    Note("When a song has an official music video, it plays silently in place of the cover, in time with the song. On a weak connection the cover is shown instead. You can also change this from the player's ••• menu.")
    Group("Animated covers") {
        SwitchRow(
            "Animated covers",
            "Plays the looping cover video some albums have (from Apple Music) in place of the still cover. Downloaded once per album.",
            AnimatedCovers.enabled,
        ) { AnimatedCovers.enabled = it }
    }
    Group("Living cover") {
        SwitchRow(
            "Bring still covers to life",
            "While a song plays, covers without a video slowly ripple and drift with a soft passing light. Uses no data.",
            Settings.livingCover,
        ) { Settings.updateLivingCover(it) }
    }
    Group("Effects") {
        SwitchRow("Animations", "Short, smooth transitions. Turn off for none at all.", Settings.animations) { Settings.updateAnimations(it) }
        Divider()
        SwitchRow(
            "Liquid glass",
            "Glass that bends what's behind it (Android 13+), and the richer animated lyrics.\n(Recommended for higher-end phones)",
            Settings.glass,
        ) { Settings.updateGlass(it) }
    }
    Note("With liquid glass off, everything stays smooth but lighter: plain frosted panels and calmer lyrics.")
}

@Composable
private fun Audio(c: ScreenCtx) {
    Group("Audio quality") {
        Choices(Quality.entries, Settings.quality, { it.label }, { it.detail }) { Settings.updateQuality(it) }
    }
    Note("Streaming quality. Applies from the next song; higher quality uses more data. Download quality is under Storage.")
    Group("Automix") {
        SwitchRow("Automix", "Mixes each song into the next like a DJ: on the beat, tempos matched. Also in the player's ••• menu.", Settings.automix) { Settings.updateAutomix(it) }
    }
    Note("Automix listens to each song first to find its tempo, beats and key. Songs with a similar tempo are matched and lined up beat for beat, and their bass is handed over halfway, so it sounds like one continuous mix. Songs that are too far apart in tempo are handed over on the beat instead. How long each blend lasts depends on the songs: faster songs get longer, DJ-style blends, slower ones shorter. Flow always mixes this way.")
    Group("Playback") {
        SwitchRow("Autoplay", "When the queue ends, keep playing similar songs.", Settings.autoplay) { Settings.updateAutoplay(it) }
        Divider()
        SwitchRow("Skip silence", "Skips quiet gaps at the start and end of songs.", Settings.skipSilence) { Settings.updateSkipSilence(it) }
        SwitchRow("Volume leveling", "Plays every song at the same loudness, so you don't have to keep changing the volume.", Settings.volumeLeveling) { Settings.updateVolumeLeveling(it) }
        SwitchRow("Report songs that won't play", "Sends nonny dev the song, the error, your Windows version and the app version so it gets fixed. Nothing about you.", Settings.reportProblems) { Settings.updateReportProblems(it) }
        SwitchRow("Count me in usage numbers", "Tells nonny dev this copy of Glasswave is being used (a random number, nothing about you): once a day, and every few minutes while it's open or playing, so we know how many people listen.", Settings.countMe) { Settings.updateCountMe(it) }
        Divider()
        SwitchRow("Shuffle & repeat beside controls", "They're always in the player's Up next list too.", Settings.showModeButtons) {
            Settings.updateShowModeButtons(it)
        }
    }
    Note("Equalizer, bass and reverb are under Settings > Sound & effects. Sleep timer and downloads are in the player's ••• menu.")
}

@Composable
private fun AccountsPage(c: ScreenCtx) {
    val vm = c.vm
    if (Discord.available) {
        Group("Discord") {
            SwitchRow(
                "Show what you're listening to",
                "Your Discord profile shows the song, artist and cover while you play, like Spotify. Needs the Discord app open on this PC.",
                Settings.discord,
            ) { Settings.updateDiscord(it) }
        }
    }
    Group("YouTube Music") {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            GText(
                if (vm.ytSignedIn) "Signed in. Your playlists and Liked Music sync to your library, and Home is made for you."
                else "Sign in with Google to sync your playlists and get a personal Home feed.",
                14, Dim,
            )
            Spacer(Modifier.height(12.dp))
            if (vm.ytSignedIn) GlassButton("Sign out", c.bg) { vm.signOutYt() }
            else GlassButton("Sign in", c.bg, accent = true) { vm.showYtLogin = true }
        }
    }
    if (vm.ytSignedIn) {
        Group("Your devices") {
            SwitchRow(
                "Sync with your phone and PC",
                "Glasswave on your other devices, signed in to this YouTube Music account, shares listening stats and can play, pause and move music between them.",
                Settings.syncDevices,
            ) { Settings.updateSyncDevices(it) }
        }
    }
    // Way 1: no account, no key. Paste a link from any music app, get the playlist.
    Group("Import from a link") {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            GText("Paste a playlist or album link from ${LinkImport.servicesText}. No sign-in needed: in the other app, tap Share → Copy link, then paste it here.", 14, Dim)
            Spacer(Modifier.height(12.dp))
            var link by remember { mutableStateOf("") }
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Box(
                Modifier.fillMaxWidth().height(48.dp).glass(RoundedCornerShape(14.dp), base = 0.35f, light = 0.05f).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (link.isEmpty()) GText("Paste a link…", 15, Faint)
                BasicTextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(CoverTint.accent),
                    modifier = Modifier.fillMaxWidth().typing(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Paste", c.bg) { link = clip.getText()?.text ?: "" }
                GlassButton(if (vm.importing) "Importing…" else "Import", c.bg, accent = true) {
                    vm.importLink(link)
                    link = ""
                }
            }
        }
        // One row per service, so it's clear where links can come from.
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        LinkImport.SERVICES.forEach { service ->
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GText(service, 16, Color.White, modifier = Modifier.weight(1f))
                Pill("Import", true) {
                    val copied = LinkImport.supportedLink(runCatching { clipboard.getText()?.text }.getOrNull())
                    Menus.importLink(copied ?: "", service) { vm.importLink(it) }
                }
            }
        }
    }
    Note("Imported playlists appear in Library under Yours. Songs play from YouTube Music. Private playlists can't be imported this way.")

    // Way 2: sync everything, with a one-time key. Each step has a button that does it for you.
    Group("Spotify · sync your whole library") {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            if (vm.spSignedIn) {
                GText("Signed in. Your playlists and Liked Songs sync to your library. Songs play from YouTube Music.", 14, Dim)
                Spacer(Modifier.height(12.dp))
                GlassButton("Sign out", c.bg) { vm.signOutSpotify() }
            } else {
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                GText("Spotify only lets apps read your library with a free key that you make once. It takes about two minutes:", 14, Dim)
                Spacer(Modifier.height(10.dp))
                SpotifyStep(1, "Open Spotify's developer page and log in with your Spotify account. Accept the terms if asked.") {
                    GlassButton("Open page", c.bg) { c.openInBrowser("https://developer.spotify.com/dashboard/create") }
                }
                SpotifyStep(2, "Fill in Create app: any name (e.g. Glasswave) and description. For Redirect URI, paste this and tap Add. Tick Web API, agree, then Save.") {
                    GlassButton("Copy redirect URI", c.bg) {
                        clip.setText(androidx.compose.ui.text.AnnotatedString("http://127.0.0.1/callback"))
                        Menus.say("Copied http://127.0.0.1/callback")
                    }
                }
                SpotifyStep(3, "On your new app's page, copy the Client ID, come back here and tap Paste.") {
                    GlassButton("Paste Client ID", c.bg) {
                        val text = clip.getText()?.text?.trim() ?: ""
                        val id = Regex("[0-9a-fA-F]{32}").find(text)?.value
                        if (id != null) {
                            vm.spClientId = id
                            Menus.say("Client ID pasted")
                        } else Menus.say("That isn't a Client ID. Copy it again.")
                    }
                }
                if (vm.spClientId.isNotBlank()) {
                    GText("Client ID: " + vm.spClientId.take(6) + "…" + vm.spClientId.takeLast(4), 13, CoverTint.accent, FontWeight.SemiBold, modifier = Modifier.padding(start = 36.dp, bottom = 6.dp))
                }
                SpotifyStep(4, "Sign in. Spotify opens in your browser, then sends you back here and your playlists sync.") {
                    GlassButton("Sign in to Spotify", c.bg, accent = true) { vm.startSpotifyLogin(c.openInBrowser) }
                }
                Spacer(Modifier.height(4.dp))
                GText("Spotify asks that the account making the key has Premium. Without Premium, use quick import above.", 12, Faint)
            }
        }
    }
}

/** One numbered step of the Spotify setup, with the button that does it. */
@Composable
private fun SpotifyStep(n: Int, text: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(CoverTint.accent.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) { GText("$n", 13, Color.Black, FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            GText(text, 14, Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}
@Composable
private fun SyncPage(c: ScreenCtx) {
    val vm = c.vm
    Group {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            val last = Accounts.lastSync
            GText(
                vm.syncStatus ?: if (last > 0) "Last synced " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
                else "Not synced yet.",
                14, Dim,
            )
            Spacer(Modifier.height(12.dp))
            GlassButton(if (vm.syncing) "Syncing..." else "Sync now", c.bg, accent = true, icon = Icons.Rounded.Sync) { vm.syncAll() }
        }
    }
    Note("Glasswave also syncs by itself when you open it, if the last sync was over 12 hours ago.")
}

@Composable
private fun StoragePage(c: ScreenCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { AudioCache.sizeBytes(ctx) } }

    var dlBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Downloads.tracks.size) { dlBytes = withContext(Dispatchers.IO) { Downloads.totalBytes() } }
    Group("Downloads") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GText("Downloaded songs", 16, weight = FontWeight.Medium)
                GText("${Downloads.tracks.size} songs" + if (dlBytes >= 0) " · %.0f MB".format(dlBytes / 1_048_576.0) else "", 13, Dim)
            }
            if (Downloads.tracks.isNotEmpty()) GlassButton("Remove all", c.bg) { Downloads.removeAll() }
        }
    }
    Note("Find them in Library → Downloads. They play without internet.")
    Group("Download quality on Wi-Fi") {
        Choices(Quality.entries, Settings.downloadWifi, { it.label }, { it.detail }) { Settings.updateDownloadWifi(it) }
    }
    Group("Download quality on mobile data") {
        Choices(Quality.entries, Settings.downloadData, { it.label }, { it.detail }) { Settings.updateDownloadData(it) }
    }
    Note("Each download uses the quality for the connection you're on when it starts. Songs already downloaded keep their quality.")
    Group("Played songs (temporary)") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GText("In use", 16, weight = FontWeight.Medium)
                GText(if (cacheBytes < 0) "Checking..." else "%.0f MB".format(cacheBytes / 1_048_576.0), 13, Dim)
            }
            GlassButton("Clear", c.bg) {
                scope.launch {
                    withContext(Dispatchers.IO) { AudioCache.clear(ctx) }
                    cacheBytes = withContext(Dispatchers.IO) { AudioCache.sizeBytes(ctx) }
                }
            }
        }
    }
    Group("Space for played songs") {
        Choices(CacheSize.entries, Settings.cacheSize, { it.label }, { "" }) { Settings.updateCacheSize(it) }
    }
    Note("Songs you listen to are kept here, so they play again instantly and still play with no internet. When it's full, the ones played longest ago make room.")
    Group("Privacy") {
        SwitchRow("Remember what was playing", "When you open Glasswave again, the last song and its queue are waiting where you left off. Just press play.", Settings.rememberPlaying) {
            Settings.updateRememberPlaying(it)
        }
        Divider()
        SwitchRow("Remember what I play", "Powers \"Recently played\" on Home. Stays on this phone.", Settings.keepHistory) { Settings.updateKeepHistory(it) }
        Divider()
        NavRow("Clear listening history", "${History.tracks.size} songs", Icons.Rounded.Storage, Color(0xFFFF9F0A)) { History.clear() }
        Divider()
        NavRow("Clear search history", "${c.vm.recent.size} searches", Icons.Rounded.Storage, Color(0xFFFF9F0A)) { c.vm.clearRecent() }
        Divider()
        NavRow("Re-match Spotify songs", "Forget which YouTube song each Spotify song played as", Icons.Rounded.Sync, Color(0xFF30D158)) { YtRepo.clearMatches() }
    }
}

@Composable
private fun About(c: ScreenCtx) {
    val ctx = LocalContext.current
    Group("Updates") {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            val r = Updater.available
            val p = Updater.progress
            when {
                r != null -> {
                    GText("Glasswave ${r.version} is out", 16, weight = FontWeight.SemiBold)
                    if (r.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        GText(r.notes.trim(), 13, Dim, maxLines = 8)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (p != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DownloadRing(p, 26.dp, CoverTint.accent, 3.dp)
                            Spacer(Modifier.width(12.dp))
                            GText("Downloading… ${(p * 100).toInt()}%", 14, Dim)
                        }
                    } else {
                        GlassButton("Update now", c.bg, accent = true) { Updater.install(ctx) }
                    }
                }
                else -> {
                    GText("Glasswave ${Updater.currentVersion()}", 16, weight = FontWeight.SemiBold)
                    GText("Glasswave checks for new versions when it opens.", 13, Dim)
                    Spacer(Modifier.height(12.dp))
                    GlassButton(if (Updater.checking) "Checking…" else "Check for updates", c.bg) { Updater.check(tell = true) }
                }
            }
            Updater.status?.let {
                Spacer(Modifier.height(10.dp))
                GText(it, 13, CoverTint.accent)
            }
        }
    }
    Note("Updates come straight from the Glasswave releases page on GitHub. Android asks you to confirm each install; your songs and settings stay.")
    Group {
        Column(Modifier.padding(18.dp)) {
            GText("Glasswave " + Updater.currentVersion(), 18, weight = FontWeight.Bold)
            GText("Made by " + Credits.AUTHOR, 14, CoverTint.accent, FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            GText(
                "A light YouTube Music player with liquid glass. Songs stream from YouTube Music; Spotify playlists are matched to YouTube Music.",
                14, Dim,
            )
            Spacer(Modifier.height(10.dp))
            GText(
                "Ideas borrowed with thanks from ArchiveTune, BitChord, ViTune, Metrolist and Apple Music. " +
                    "Uses NewPipe Extractor, Media3, Jetpack Compose and Coil.",
                13, Faint,
            )
        }
    }
}

/* ---------------- YouTube Music sign-in ---------------- */

private const val YT_LOGIN_URL =
    // The same address YouTube Music's own "Sign in" button opens: Google signs in, YouTube
    // finishes its side, then it lands on YouTube Music.
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3&passive=true&hl=en" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

/**
 * Signing in to YouTube Music. Google's sign-in opens in its own small window (see YtSignIn); this
 * panel waits behind it and says what's happening. The window closes by itself once you're in.
 */
@Composable
fun YtLogin(onCookie: (String) -> Unit, onClose: () -> Unit) {
    var result by remember { mutableStateOf<YtSignIn.Result?>(null) }
    LaunchedEffect(Unit) {
        // gw.signInUrl: automated checks start somewhere that needs no account.
        val r = withContext(Dispatchers.IO) { YtSignIn.run(System.getProperty("gw.signInUrl") ?: YT_LOGIN_URL) }
        when (r) {
            is YtSignIn.Result.Cookie -> onCookie(r.value)
            YtSignIn.Result.Closed -> onClose()
            else -> result = r
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { YtSignIn.cancel() } }

    val (title, body) = when (result) {
        null -> "Sign in to YouTube Music" to
            "Google's sign-in is open in its own window. Sign in there and it closes by itself, then your playlists, " +
            "Liked Music and a Home made for you come over.\n\nGlasswave never sees your password: you type it into Google's own page."
        YtSignIn.Result.Blocked -> "Google didn't allow this sign-in" to
            "Google sometimes refuses sign-ins from windows inside apps. Try again in a little while, or keep using " +
            "Glasswave without an account: search, Home, albums, artists and your Glasswave playlists all work."
        YtSignIn.Result.NoRuntime -> "Sign-in needs Microsoft Edge WebView2" to
            "It comes with Windows 11 and most Windows 10 PCs, but it's missing here. Install it (free, from Microsoft), then try again."
        else -> "Sign-in isn't available in this copy" to
            "Update Glasswave or reinstall it from the website, then try again."
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0B10)), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 520.dp).padding(28.dp)) {
        GText(title, 22, weight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        GText(body, 15, Dim)
        Spacer(Modifier.height(22.dp))
        if (result == YtSignIn.Result.NoRuntime) {
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White)
                    .pressable(0.97f) { openInBrowser("https://developer.microsoft.com/microsoft-edge/webview2/") },
                contentAlignment = Alignment.Center,
            ) { GText("Get WebView2", 16, Color(0xFF1F1F1F), FontWeight.SemiBold, 1) }
            Spacer(Modifier.height(10.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(if (result == null || result == YtSignIn.Result.NoRuntime) Color.White.copy(alpha = 0.1f) else Color.White)
                .pressable(0.97f) { YtSignIn.cancel(); onClose() },
            contentAlignment = Alignment.Center,
        ) {
            GText(
                if (result == null) "Cancel" else "OK", 16,
                if (result == null || result == YtSignIn.Result.NoRuntime) Color.White else Color(0xFF1F1F1F), FontWeight.SemiBold, 1,
            )
        }
    }
    }
}

/* ---------------- Sound & effects ---------------- */

@Composable
private fun SoundPage(c: ScreenCtx) {
    Group("Equalizer") {
        SwitchRow("Equalizer", "Shape the sound with 10 bands, from deep bass to sparkle.", Sound.eqOn) { Sound.updateEqOn(it) }
        Box(Modifier.padding(horizontal = 6.dp)) { EqBands() }
        LazyPills(EqPreset.entries.map { it.label }, Sound.preset?.label ?: "Custom") { label ->
            EqPreset.entries.firstOrNull { it.label == label }?.let { Sound.applyPreset(it) }
        }
        Spacer(Modifier.height(10.dp))
    }
    Note("Drag a band up or down, or pick a ready-made sound. Moving a band turns the equalizer on.")
    Group("Tone") {
        SliderRow("Bass boost", pct(Sound.bass), Sound.bass, 0f..1f) { Sound.updateBass(it) }
        SliderRow("Treble boost", pct(Sound.treble), Sound.treble, 0f..1f) { Sound.updateTreble(it) }
        SliderRow("Stereo width", pct(Sound.width), Sound.width, 0f..1f) { Sound.updateWidth(it) }
    }
    Group("Effects") {
        SliderRow("Reverb", pct(Sound.reverb), Sound.reverb, 0f..1f) { Sound.updateReverb(it) }
        if (Sound.reverb > 0f) LazyPills(Room.entries.map { it.label }, Sound.room.label) { l -> Room.entries.firstOrNull { it.label == l }?.let { Sound.updateRoom(it) } }
        SliderRow("Distortion", pct(Sound.drive), Sound.drive, 0f..1f) { Sound.updateDrive(it) }
    }
    Note("Reverb makes it sound like a room, a concert hall or an arena. Distortion goes from warm to fuzzy.")
    Group("Balance") {
        SliderRow("Left / right", balanceWord(Sound.balance), Sound.balance, -1f..1f, center = 0f, snap = 0.05f) { Sound.updateBalance(it) }
        SwitchRow("Mono", "Both ears hear the same thing. Handy with one earbud.", Sound.mono) { Sound.updateMono(it) }
    }
    Group {
        NavRow("Reset all sound settings", "Back to how the song was recorded", Icons.Rounded.Sync, Color.Transparent) { Sound.resetAll(); c.ps.changeSpeed(1f) }
    }
    Note("Everything here works inside Glasswave, also during Automix and crossfades.")
}

private fun pct(v: Float) = if (v <= 0f) "Off" else "${(v * 100).toInt()}%"
private fun semis(v: Float) = when { v == 0f -> "Normal"; v > 0 -> "+${v.toInt()}"; else -> "${v.toInt()}" }
private fun balanceWord(v: Float) = when {
    kotlin.math.abs(v) < 0.025f -> "Centre"
    v < 0 -> "${(-v * 100).toInt()}% left"
    else -> "${(v * 100).toInt()}% right"
}
private fun glassWord(v: Float) = when { v < 0.7f -> "Clear"; v < 1.25f -> "Normal"; else -> "Frosted" }

/** A title, the value on the right, and a slider underneath. [center] fills from that point (for things like balance). */
@Composable
private fun SliderRow(
    title: String,
    value: String,
    v: Float,
    range: ClosedFloatingPointRange<Float>,
    center: Float? = null,
    snap: Float? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(title)
            GText(title, 16, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            GText(value, 14, if (Settings.cleanLook) CleanGrey else Dim)
        }
        Spacer(Modifier.height(6.dp))
        // Frosted glass: the slider lines up with the title, after the icon.
        Box(Modifier.padding(start = if (Settings.cleanLook) 36.dp else 0.dp)) { GlassSlider(v, range, center, snap, onChange = onChange) }
    }
}

@Composable
private fun GlassSlider(
    v: Float,
    range: ClosedFloatingPointRange<Float>,
    center: Float? = null,
    snap: Float? = null,
    brush: Brush? = null,
    onChange: (Float) -> Unit,
) {
    val span = range.endInclusive - range.start
    fun valueAt(x: Float, w: Float): Float {
        var out = range.start + (x / w).coerceIn(0f, 1f) * span
        if (snap != null) out = Math.round(out / snap) * snap
        // A little pull towards the middle point, so it's easy to get back to "normal".
        if (center != null && kotlin.math.abs(out - center) < span * 0.03f) out = center
        return out.coerceIn(range.start, range.endInclusive)
    }
    var dragging by remember { mutableStateOf(false) }
    val accent = Accent
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(range) { detectTapGestures { o -> change(valueAt(o.x, size.width.toFloat())) } }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; change(valueAt(o.x, size.width.toFloat())) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { ch, _ -> ch.consume(); change(valueAt(ch.position.x, size.width.toFloat())) },
                )
            }
            .drawBehind {
                val h = (if (dragging) 8.dp else 5.dp).toPx()
                val top = (size.height - h) / 2
                val f = ((v - range.start) / span).coerceIn(0f, 1f)
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                if (brush != null) {
                    drawRoundRect(brush, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h), r)
                } else {
                    drawRoundRect(Color.White.copy(alpha = 0.14f), androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h), r)
                    val from = if (center != null) ((center - range.start) / span) else 0f
                    val a = minOf(from, f) * size.width
                    val b = maxOf(from, f) * size.width
                    drawRoundRect(accent, androidx.compose.ui.geometry.Offset(a, top), androidx.compose.ui.geometry.Size(b - a, h), r)
                }
                val kr = (if (dragging) 11.dp else 9.dp).toPx()
                drawCircle(Color.White, kr, androidx.compose.ui.geometry.Offset((f * size.width).coerceIn(kr, size.width - kr), size.height / 2))
            },
    )
}

/** Round buttons in a row that scroll sideways; the picked one is white. */
@Composable
private fun LazyPills(options: List<String>, selected: String, onPick: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { i ->
            val o = options[i]
            Pill(o, o == selected) { onPick(o) }
        }
    }
}

@Composable
private fun Pill(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.pressable(0.95f, onClick = onClick).clip(CircleShape)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { GText(text, 13, if (on) Color.Black else Color.White, FontWeight.SemiBold, 1) }
}

/** The 10 equalizer bands as upright sliders, joined by a line showing the shape of the sound. */
@Composable
private fun EqBands() {
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val accent = if (Sound.eqOn) Accent else Color.White.copy(alpha = 0.45f)
    var active by remember { mutableStateOf(-1) }
    val g = Sound.gains
    val max = Sound.MAX_DB
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(Unit) {
                    fun band(x: Float) = (x / size.width * 10).toInt().coerceIn(0, 9)
                    fun db(y: Float) = (Math.round((1f - (y / size.height).coerceIn(0f, 1f)) * 2 * max - max)).toFloat()
                    detectDragGestures(
                        onDragStart = { o -> active = band(o.x); Sound.setBand(active, db(o.y)) },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onDrag = { ch, _ -> ch.consume(); if (active >= 0) Sound.setBand(active, db(ch.position.y)) },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val i = (o.x / size.width * 10).toInt().coerceIn(0, 9)
                        Sound.setBand(i, (Math.round((1f - (o.y / size.height).coerceIn(0f, 1f)) * 2 * max - max)).toFloat())
                    }
                }
                .drawBehind {
                    val col = size.width / 10
                    fun y(db: Float) = (1f - (db + max) / (2 * max)) * size.height
                    // Middle line (no change).
                    drawLine(Color.White.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(0f, y(0f)), androidx.compose.ui.geometry.Offset(size.width, y(0f)), 1.dp.toPx())
                    for (i in 0 until 10) {
                        val x = col * i + col / 2
                        drawLine(Color.White.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(accent, androidx.compose.ui.geometry.Offset(x, y(0f)), androidx.compose.ui.geometry.Offset(x, y(g[i])), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    // The shape of the sound, as a smooth line through the knobs.
                    val path = androidx.compose.ui.graphics.Path()
                    for (i in 0 until 10) {
                        val x = col * i + col / 2
                        if (i == 0) path.moveTo(x, y(g[i])) else {
                            val px = col * (i - 1) + col / 2
                            path.cubicTo((px + x) / 2, y(g[i - 1]), (px + x) / 2, y(g[i]), x, y(g[i]))
                        }
                    }
                    drawPath(path, accent.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    for (i in 0 until 10) {
                        val x = col * i + col / 2
                        drawCircle(Color.White, (if (i == active) 10.dp else 7.dp).toPx(), androidx.compose.ui.geometry.Offset(x, y(g[i])))
                    }
                },
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    GText(if (i == active) "${if (g[i] > 0) "+" else ""}${g[i].toInt()}" else l, 11, if (i == active) Color.White else Faint, FontWeight.Medium, 1)
                }
            }
        }
    }
}

/**
 * Two ways: the colours follow each song's cover (buttons, the side menu, the playing song...), or
 * they stay one colour you pick, from the ready ones or the rainbow slider.
 */
@Composable
private fun AccentPicker() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (Settings.accentColor == null) return@Column
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(AccentSwatches.size) { i ->
                val c = AccentSwatches[i]
                val on = Settings.accentColor == c
                Box(
                    Modifier.size(38.dp).pressable(0.9f) { Settings.updateAccentColor(c) }.clip(CircleShape).background(Color(c))
                        .border(if (on) 3.dp else 0.dp, Color.White, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GText("Any colour", 13, Dim)
        Spacer(Modifier.height(4.dp))
        val hue = Settings.accentColor?.let { hueOf(it) } ?: 0f
        GlassSlider(
            hue, 0f..360f,
            brush = Brush.horizontalGradient((0..6).map { Color(accentFromHue(it * 60f)) }),
        ) { Settings.updateAccentColor(accentFromHue(it)) }
    }
}

/** The standard font, or one you imported (shown written in itself). */
@Composable
private fun FontChoices() {
    val own = Settings.fontFile
    val saved = FontFile.saved()
    fun import() {
        val got = FontFile.pick() ?: return
        Settings.updateFont(got.first, got.second)
    }
    Row(
        Modifier.fillMaxWidth().pressable(0.98f) { Settings.updateFont(null, Settings.fontName) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            androidx.compose.foundation.text.BasicText("Default", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = TextFont))
            GText("Windows' own clean font.", 13, Dim)
        }
        AnimatedVisibility(own == null, enter = scaleIn(bouncy()), exit = scaleOut(smooth(120))) { Ico(Icons.Rounded.Check, Accent, 24.dp) }
    }
    Divider()
    Row(
        Modifier.fillMaxWidth().pressable(0.98f) {
            if (saved != null && own == null) Settings.updateFont(saved.path, Settings.fontName) else import()
        }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val family = saved?.let { fontFamilyFromFile(it.path) }
            androidx.compose.foundation.text.BasicText(
                if (saved != null) Settings.fontName.ifBlank { "Your font" } else "Import a font",
                style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = family ?: TextFont),
                maxLines = 1,
            )
            GText(if (saved != null) "Your own font. Click Change to pick another." else "Use any font file on this PC.", 13, Dim)
        }
        if (saved != null) {
            Pill("Change", false) { import() }
            Spacer(Modifier.width(8.dp))
        }
        AnimatedVisibility(own != null, enter = scaleIn(bouncy()), exit = scaleOut(smooth(120))) { Ico(Icons.Rounded.Check, Accent, 24.dp) }
    }
}
