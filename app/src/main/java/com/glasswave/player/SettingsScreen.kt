// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector, val color: Color) {
    LOOK("Look", "Colours, wallpaper, fonts, visualizer, player style", Icons.Rounded.Palette, Color(0xFFAF52DE)),
    AUDIO("Player & audio", "Quality, crossfade, autoplay", Icons.Rounded.MusicNote, Color(0xFFFF375F)),
    SOUND("Sound & effects", "10-band equalizer, bass, pitch, reverb", Icons.Rounded.GraphicEq, Color(0xFFFF9F0A)),
    PERFORMANCE("Performance", "Frame rate, high performance mode", Icons.Rounded.Speed, Color(0xFF64D2FF)),
    ACCOUNTS("Accounts", "YouTube Music and Spotify", Icons.Rounded.AccountCircle, Color(0xFF0A84FF)),
    SYNC("Sync", "Bring your playlists in", Icons.Rounded.Sync, Color(0xFF30D158)),
    STORAGE("Storage & privacy", "Downloads, saved songs, history", Icons.Rounded.Storage, Color(0xFFFF9F0A)),
    ABOUT("About", "Version and credits", Icons.Rounded.Info, Color(0xFF8E8E93)),
}

/* ---------------- Building blocks ---------------- */

@Composable
private fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) GText(title.uppercase(), 13, Faint, FontWeight.SemiBold, modifier = Modifier.padding(start = 30.dp, top = 22.dp, bottom = 8.dp))
    else Spacer(Modifier.height(14.dp))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).glass(RoundedCornerShape(22.dp), base = 0.28f, light = 0.08f).padding(vertical = 4.dp),
        content = content,
    )
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
        Modifier.fillMaxWidth().pressable(0.98f, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GText(title, 16, weight = FontWeight.Medium)
            GText(detail, 13, Dim, maxLines = 1)
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 24.dp)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
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
                    SettingsPage.PERFORMANCE -> Performance()
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
            if (i > 0) Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Group("Highlight colour") {
        AccentPicker()
    }
    Note("Used for buttons, the playing song, lyrics and the visualizer. \"Cover\" picks a colour from each song's cover.")
    Group("Wallpaper") {
        val pick = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                // Copied in the background (a video can be big), then shown.
                scope.launch {
                    val path = withContext(Dispatchers.IO) { WallpaperFile.save(ctx, uri) }
                    if (path != null) Settings.updateWallpaper(path) else Menus.say("Couldn't open that file")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (Settings.wallpaper == null) "Choose picture, GIF or video" else "Change", true, Modifier.weight(1f)) {
                pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            if (Settings.wallpaper != null) {
                Pill("Remove", false) {
                    Settings.updateWallpaper(null)
                    WallpaperFile.remove(ctx)
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
    Note("Shown above the progress bar in the player, in your highlight colour. It only works while it's on screen, so it doesn't use battery otherwise.")
    Group("Font") {
        FontChoices()
    }
    Note("Import any font file (.ttf or .otf) from your phone, for example one you downloaded from Google Fonts.")
    Group("Player style") {
        Choices(PlayerStyle.entries, Settings.playerStyle, { it.label }, { it.detail }) { Settings.updatePlayerStyle(it) }
    }
    Group("Play button") {
        Choices(ButtonStyle.entries, Settings.buttonStyle, { it.label }, { it.detail }) { Settings.updateButtonStyle(it) }
    }
    Group("Music videos behind the cover") {
        Choices(CoverMotion.entries, Settings.coverMotion, { it.label }, { it.detail }) { Settings.updateCoverMotion(it) }
    }
    Note("When a song has an official music video, it plays silently in place of the cover, in time with the song. On a weak connection the cover is shown instead. You can also change this from the player's ••• menu.")
    Group("Living cover") {
        SwitchRow(
            "Bring still covers to life",
            "While a song plays, the cover slowly drifts with a soft passing light, like a live photo. Uses no data.",
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
    Group("Crossfade") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 2, 4, 6, 8, 12).forEach { s ->
                val on = Settings.crossfade == s
                Box(
                    Modifier.weight(1f).pressable(0.95f) { Settings.updateCrossfade(s) }.clip(CircleShape)
                        .background(if (on) Color.White else Color.White.copy(alpha = 0.08f)).padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { GText(if (s == 0) "Off" else "${s}s", 13, if (on) Color.Black else Color.White, FontWeight.SemiBold, 1) }
            }
        }
    }
    Note("Each song fades out over its last seconds and the next fades in, so songs blend instead of cutting.")
    Group("Automix") {
        SwitchRow("Automix", "Blends each song into the next, like a DJ. Also in the player's ••• menu.", Settings.automix) { Settings.updateAutomix(it) }
        if (Settings.automix) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(4, 6, 8, 10, 12).forEach { s ->
                    val on = Settings.mixSeconds == s
                    Box(
                        Modifier.weight(1f).pressable(0.95f) { Settings.updateMixSeconds(s) }.clip(CircleShape)
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.08f)).padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { GText("${s}s", 13, if (on) Color.Black else Color.White, FontWeight.SemiBold, 1) }
                }
            }
        }
    }
    Note("The next song starts under the end of this one and they blend for the length you pick. While Automix is on, it takes over from crossfade.")
    Group("Playback") {
        SwitchRow("Autoplay", "When the queue ends, keep playing similar songs.", Settings.autoplay) { Settings.updateAutoplay(it) }
        SwitchRow("Remember what was playing", "When you open Glasswave again, the last song is waiting where you left off. Just tap play.", Settings.rememberPlaying) {
            Settings.updateRememberPlaying(it)
        }
        Divider()
        SwitchRow("Skip silence", "Skips quiet gaps at the start and end of songs.", Settings.skipSilence) { Settings.updateSkipSilence(it) }
        SwitchRow("Volume leveling", "Plays every song at the same loudness, so you don't have to keep changing the volume.", Settings.volumeLeveling) { Settings.updateVolumeLeveling(it) }
        SwitchRow("Report songs that won't play", "Sends nonny dev the song, the error, your phone model and the app version so it gets fixed. Nothing about you.", Settings.reportProblems) { Settings.updateReportProblems(it) }
        SwitchRow("Count me in usage numbers", "Once a day, tells nonny dev this copy of Glasswave was used (a random number, nothing about you), so we know how many people listen.", Settings.countMe) { Settings.updateCountMe(it) }
        Divider()
        SwitchRow("Shuffle & repeat beside controls", "They're always in the player's Up next list too.", Settings.showModeButtons) {
            Settings.updateShowModeButtons(it)
        }
    }
    Note("Equalizer, bass, pitch and reverb are under Settings > Sound & effects. Sleep timer, speed and downloads are in the player's ••• menu.")
}

@Composable
private fun Performance() {
    Group("Frame rate") {
        Choices(FrameRate.entries, Settings.frameRate, { it.label }, { it.detail }) { Settings.updateFrameRate(it) }
    }
    Note("Your screen may not offer every rate; Glasswave picks the closest one it has, never above your choice.")
    Group {
        SwitchRow(
            "High performance mode",
            "Smoothest motion: backgrounds and covers move every frame, more covers stay loaded, and the phone keeps its speed steady. Uses more battery.",
            Settings.highPerformance,
        ) { Settings.updateHighPerformance(it) }
    }
    Note("Keeping more covers loaded takes effect the next time Glasswave starts.")
}

@Composable
private fun AccountsPage(c: ScreenCtx) {
    val vm = c.vm
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
                    modifier = Modifier.fillMaxWidth(),
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
    Note("A new size takes effect the next time Glasswave starts.")
    Group("Privacy") {
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
 * Signing in to YouTube Music. First a choice: "Continue with Google" opens Android's own account
 * picker with the Google accounts already on the phone; the picked account is filled into Google's
 * sign-in, which then usually just asks to confirm ("Tap Yes on your phone") or offers the saved
 * password. "Use another account" opens Google's sign-in empty.
 */
@Composable
fun YtLogin(onCookie: (String) -> Unit, onClose: () -> Unit) {
    // null: still choosing; "": sign in by typing; an email: that Google account.
    var account by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val email = res.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        // Picked an account: fill it in. Anything else (no accounts, picker closed by the phone):
        // carry on to Google's normal sign-in instead of doing nothing.
        account = if (res.resultCode == android.app.Activity.RESULT_OK && !email.isNullOrBlank()) email else ""
    }
    BackHandler(enabled = account != null) { account = null }
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0B10)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).pressable(0.85f) { if (account != null) account = null else onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 24.dp)
            }
            GText("Sign in to YouTube Music", 17, weight = FontWeight.SemiBold)
        }
        val a = account
        if (a == null) {
            SignInChoice(
                onGoogle = {
                    val intent = android.accounts.AccountManager.newChooseAccountIntent(
                        null, null, arrayOf("com.google"), null, null, null, null,
                    )
                    runCatching { picker.launch(intent) }.onFailure { account = "" }
                },
                onOther = { account = "" },
            )
        } else {
            YtLoginWeb(a, onCookie)
        }
    }
}

@Composable
private fun SignInChoice(onGoogle: () -> Unit, onOther: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        GText("Sign in with your Google account", 24, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        GText("Your playlists, liked songs and mixes come over from YouTube Music.", 15, Dim, modifier = Modifier.padding(bottom = 30.dp))
        // Google's own colours on the button, so it's recognisable at a glance.
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).pressable(0.97f, onClick = onGoogle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(22.dp)) { drawGoogleG() }
            Spacer(Modifier.width(12.dp))
            GText("Continue with Google", 16, Color(0xFF1F1F1F), FontWeight.SemiBold, 1)
        }
        GText("Pick one of the Google accounts on this phone. No typing your email.", 13, Faint, modifier = Modifier.padding(top = 10.dp, bottom = 22.dp))
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.08f)).pressable(0.97f, onClick = onOther),
            contentAlignment = Alignment.Center,
        ) { GText("Use another account", 15, weight = FontWeight.SemiBold, maxLines = 1) }
    }
}

/** Google's four-colour "G", drawn so no image file is needed. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGoogleG() {
    val s = size.minDimension
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.2f)
    val inset = s * 0.1f
    val tl = androidx.compose.ui.geometry.Offset(inset, inset)
    val sz = androidx.compose.ui.geometry.Size(s - 2 * inset, s - 2 * inset)
    drawArc(Color(0xFFEA4335), -150f, 105f, false, tl, sz, style = stroke) // red, top
    drawArc(Color(0xFFFBBC05), 150f, 60f, false, tl, sz, style = stroke) // yellow, left
    drawArc(Color(0xFF34A853), 45f, 105f, false, tl, sz, style = stroke) // green, bottom
    drawArc(Color(0xFF4285F4), -10f, 55f, false, tl, sz, style = stroke) // blue, right
    drawRect(Color(0xFF4285F4), androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.42f), androidx.compose.ui.geometry.Size(s * 0.42f, s * 0.18f))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YtLoginWeb(email: String, onCookie: (String) -> Unit) {
    // Google turned the sign-in away ("This browser or app may not be secure").
    var blocked by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val startUrl = if (email.isBlank()) YT_LOGIN_URL
    else YT_LOGIN_URL + "&Email=" + java.net.URLEncoder.encode(email, "UTF-8") + "&login_hint=" + java.net.URLEncoder.encode(email, "UTF-8")
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    web = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Look exactly like Chrome on this phone. Google refuses sign-ins from pages it can
                    // tell are inside an app: by the "wv" mark in the browser name, and by a hidden
                    // "X-Requested-With: <app>" header that web views send. Both are removed here.
                    val chrome = Regex("Chrome/([\\d.]+)").find(settings.userAgentString)?.groupValues?.get(1) ?: "130.0.0.0"
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Mobile Safari/537.36"
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                        androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                    }
                    // Let Android offer saved passwords (Google Password Manager) on the sign-in page.
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        var done = false
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            if (url != null && ("/signin/rejected" in url || "disallowed_useragent" in url)) blocked = true
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url != null && ("/signin/rejected" in url || "disallowed_useragent" in url)) blocked = true
                            if (done || url == null || !url.startsWith("https://music.youtube.com")) return
                            val c = cm.getCookie("https://music.youtube.com") ?: return
                            if ("SAPISID=" in c) {
                                done = true
                                cm.flush()
                                onCookie(c)
                            }
                        }
                    }
                    // The picked account goes straight in, so Google skips the "Email or phone" step.
                    loadUrl(startUrl)
                }
            },
            onRelease = { it.destroy(); web = null },
        )
        if (blocked) {
            // Explain it plainly instead of leaving Google's scary page up.
            Column(
                Modifier.fillMaxSize().background(Color(0xFF0A0B10)).padding(28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                GText("Google didn't accept this sign-in", 22, weight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                GText(
                    "Sometimes Google is extra careful with sign-in screens inside apps. Your account is safe: " +
                        "Glasswave only keeps the sign-in on this phone to load your music. Trying again usually works, " +
                        "especially after a minute or on Wi-Fi.",
                    15, Dim,
                )
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White)
                        .pressable(0.97f) {
                            blocked = false
                            CookieManager.getInstance().removeAllCookies { web?.loadUrl(startUrl) }
                        },
                    contentAlignment = Alignment.Center,
                ) { GText("Try again", 16, Color(0xFF1F1F1F), FontWeight.SemiBold, 1) }
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
    Group("Speed & pitch") {
        SliderRow("Speed", "${"%.2f".format(c.ps.speed)}x", c.ps.speed, 0.5f..2f, center = 1f, snap = 0.05f) { c.ps.changeSpeed(it) }
        SliderRow("Pitch", semis(Sound.pitch), Sound.pitch, -12f..12f, center = 0f, snap = 1f) { Sound.updatePitch(it) }
    }
    Note("Pitch makes songs higher or lower without changing their speed. Speed resets when Glasswave closes; pitch is kept.")
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
        NavRow("Phone's own equalizer", "Opens your phone's sound settings for Glasswave, if it has them", Icons.Rounded.Equalizer, Color.Transparent, c.openEqualizer)
    }
    Note("Everything here works inside Glasswave, on every phone, and during Automix too.")
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
            GText(title, 16, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            GText(value, 14, Dim)
        }
        Spacer(Modifier.height(6.dp))
        GlassSlider(v, range, center, snap, onChange = onChange)
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

/** "Cover" (from each song) or one of the ready colours, plus a rainbow slider for any colour. */
@Composable
private fun AccentPicker() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val on = Settings.accentColor == null
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(38.dp).pressable(0.9f) { Settings.updateAccentColor(null) }.clip(CircleShape)
                            .background(Brush.sweepGradient(listOf(Color(0xFFFF5C7A), Color(0xFFF6C744), Color(0xFF68D391), Color(0xFF7AA2FF), Color(0xFFB794F6), Color(0xFFFF5C7A))))
                            .border(if (on) 3.dp else 0.dp, Color.White, CircleShape),
                    )
                    GText("Cover", 11, if (on) Color.White else Faint, modifier = Modifier.padding(top = 4.dp))
                }
            }
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
        val hue = Settings.accentColor?.let { c -> FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }[0] } ?: 0f
        GlassSlider(
            hue, 0f..360f,
            brush = Brush.horizontalGradient((0..6).map { Color(accentFromHue(it * 60f)) }),
        ) { Settings.updateAccentColor(accentFromHue(it)) }
    }
}

/** The standard font, or one you imported (shown written in itself). */
@Composable
private fun FontChoices() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val got = withContext(Dispatchers.IO) { FontFile.save(ctx, uri) }
            if (got != null) Settings.updateFont(got.first, got.second) else Menus.say("That isn't a font file Glasswave can use")
        }
    }
    fun import() = pick.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"))
    val own = Settings.fontFile
    Row(
        Modifier.fillMaxWidth().pressable(0.98f) { Settings.updateFont(null, Settings.fontName) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            androidx.compose.foundation.text.BasicText("Default", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            GText("Glasswave's own clean font.", 13, Dim)
        }
        AnimatedVisibility(own == null, enter = scaleIn(bouncy()), exit = scaleOut(smooth(120))) { Ico(Icons.Rounded.Check, Accent, 24.dp) }
    }
    Divider()
    val saved = FontFile.saved(ctx)
    Row(
        Modifier.fillMaxWidth().pressable(0.98f) {
            if (saved != null && own == null) Settings.updateFont(saved.path, Settings.fontName) else import()
        }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val family = remember(saved?.path) { saved?.let { runCatching { androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.createFromFile(it)) }.getOrNull() } }
            androidx.compose.foundation.text.BasicText(
                if (saved != null) Settings.fontName.ifBlank { "Your font" } else "Import a font",
                style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = family),
                maxLines = 1,
            )
            GText(if (saved != null) "Your own font. Tap Change to pick another." else "Use any font file from your phone.", 13, Dim)
        }
        if (saved != null) {
            Pill("Change", false) { import() }
            Spacer(Modifier.width(8.dp))
        }
        AnimatedVisibility(own != null, enter = scaleIn(bouncy()), exit = scaleOut(smooth(120))) { Ico(Icons.Rounded.Check, Accent, 24.dp) }
    }
}
