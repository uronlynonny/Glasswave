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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
    LOOK("Look", "Player style, buttons, music videos, glass", Icons.Rounded.Palette, Color(0xFFAF52DE)),
    AUDIO("Player & audio", "Quality, crossfade, autoplay, equalizer", Icons.Rounded.MusicNote, Color(0xFFFF375F)),
    ACCOUNTS("Accounts", "YouTube Music, Spotify and Discord", Icons.Rounded.AccountCircle, Color(0xFF0A84FF)),
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
        Divider()
        SwitchRow("Skip silence", "Skips quiet gaps at the start and end of songs.", Settings.skipSilence) { Settings.updateSkipSilence(it) }
        SwitchRow("Volume leveling", "Plays every song at the same loudness, so you don't have to keep changing the volume.", Settings.volumeLeveling) { Settings.updateVolumeLeveling(it) }
        SwitchRow("Report songs that won't play", "Sends nonny dev the song, the error, your Windows version and the app version so it gets fixed. Nothing about you.", Settings.reportProblems) { Settings.updateReportProblems(it) }
        SwitchRow("Count me in usage numbers", "Once a day, tells nonny dev this copy of Glasswave was used (a random number, nothing about you), so we know how many people listen.", Settings.countMe) { Settings.updateCountMe(it) }
        Divider()
        SwitchRow("Shuffle & repeat beside controls", "They're always in the player's Up next list too.", Settings.showModeButtons) {
            Settings.updateShowModeButtons(it)
        }
    }
    Group("Sound") {
        NavRow("Equalizer", "Opens your phone's equalizer for Glasswave", Icons.Rounded.Equalizer, Color.Transparent, c.openEqualizer)
    }
    Note("Sleep timer, speed and downloads are in the player's ••• menu.")
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
    // Way 1: no account, no key. Paste a link, get the playlist.
    Group("Spotify · quick import") {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            GText("No sign-in needed. In Spotify, open a playlist or album, tap Share → Copy link, then paste it here.", 14, Dim)
            Spacer(Modifier.height(12.dp))
            var link by remember { mutableStateOf("") }
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Box(
                Modifier.fillMaxWidth().height(48.dp).glass(RoundedCornerShape(14.dp), base = 0.35f, light = 0.05f).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (link.isEmpty()) GText("open.spotify.com/playlist/…", 15, Faint)
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
                    vm.importSpotifyLink(link)
                    link = ""
                }
            }
        }
    }
    Note("Imported playlists appear in Library under Yours. Private playlists can't be imported this way.")

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
