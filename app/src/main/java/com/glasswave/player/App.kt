// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(vm: MainViewModel, ps: PlayerState, openInBrowser: (String) -> Unit, openEqualizer: () -> Unit) {
    var full by remember { mutableStateOf(false) }
    // Back from the player: Home catches up with what you've been playing.
    LaunchedEffect(full) { if (!full && vm.tab == Tab.HOME && vm.stack.isEmpty()) vm.refreshHomeIfStale() }
    BackHandler(enabled = full) { full = false }
    BackHandler(enabled = !full && vm.showYtLogin) { vm.showYtLogin = false }
    BackHandler(enabled = !full && !vm.showYtLogin && vm.stack.isNotEmpty()) { vm.back() }
    // Settings has no tab of its own any more (it opens from Home), so Back goes home from it.
    BackHandler(enabled = !full && !vm.showYtLogin && vm.stack.isEmpty() && vm.tab == Tab.SETTINGS) { vm.selectTab(Tab.HOME) }
    BackHandler(enabled = vm.voiceOpen) { vm.voiceOpen = false }
    BackHandler(enabled = Menus.anyOpen) {
        Menus.closeName()
        Menus.closeSong()
        Menus.closePlaylist()
    }

    // Opened by tapping the song in the notification or the phone's media bar: show the full player.
    LaunchedEffect(ps.openFull, ps.hasItem) {
        if (ps.openFull && ps.hasItem) {
            full = true
            ps.openFull = false
        }
    }

    // Live users (see Active.live): in use while Glasswave is on screen or playing.
    val lifecycle = @Suppress("DEPRECATION") androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        while (true) {
            val onScreen = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            Active.live(onScreen || ps.playing, ps.playing)
            delay(20_000L)
        }
    }

    // Look for a new version when the app opens (at most every three hours), and after an update
    // say once what's new.
    LaunchedEffect(Unit) {
        Updater.checkIfDue()
        Updater.checkWhatsNew()
        // Counted as "in use today" (once a day; see Active).
        while (true) {
            Active.checkIn()
            Sync.start() // reconnects if it dropped
            delay(20 * 60_000L)
        }
    }

    // Phone and PC on the same YouTube Music account (see Sync): let them control this player,
    // and tell them when the song or play/pause changes.
    LaunchedEffect(ps) { Sync.remote = syncRemote(ps); Sync.start() }
    // Listen together (see Jam).
    LaunchedEffect(ps) { Jam.player = jamPlayer(ps) }
    // Jams nearby (see JamNearby): on this Wi-Fi and around you, whenever Glasswave is on screen
    // and you're not in a Jam yourself.
    var jamOnScreen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, _ -> jamOnScreen = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    LaunchedEffect(jamOnScreen, Jam.inJam) {
        val look = jamOnScreen && !Jam.inJam
        JamNearby.listen(wifi = look, area = look)
    }
    LaunchedEffect(ps.currentId, ps.playing) {
        kotlinx.coroutines.delay(1200)
        Sync.publishState()
    }

    // Keep the progress bars moving while a song plays.
    LaunchedEffect(ps.playing) {
        while (ps.playing) {
            ps.tick()
            delay(400)
        }
    }

    LaunchedEffect(ps.currentId) {
        if (ps.currentId == null) return@LaunchedEffect
        // Wait (a few seconds at most) for the song's length: it helps pick the right lyrics.
        repeat(20) { if (ps.durationMs > 0) return@repeat; kotlinx.coroutines.delay(250) }
        ps.currentTrack()?.let { LyricsRepo.prefetch(it.copy(seconds = ps.durationMs / 1000)) }
        if (!Settings.savingData) ps.upcoming(1).firstOrNull()?.let { LyricsRepo.prefetch(it.track) }
    }
    val colors = rememberCoverColors(hiRes(ps.art))
    val bg = rememberBackdrop()
    val page = rememberBackdrop()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Room under lists for the floating glass bar (and mini player), so the last rows scroll clear of it.
    val bottom = nav + if (ps.hasItem) 164.dp else 96.dp
    val ctx = ScreenCtx(vm, ps, bg, bottom, openInBrowser, openEqualizer)

    // While the full player covers the screen, the app underneath isn't drawn at all (its glass
    // and moving background would be wasted work). Dragging the player down shows it again.
    val fullState = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    fullState.targetState = full
    var peeking by remember { mutableStateOf(false) }
    val covered = full && fullState.currentState && fullState.isIdle && !peeking

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().drawWithContent { if (!covered) drawContent() }.backdropSource(page)) {
            GlassBackground(colors, hiRes(ps.art), Modifier.backdropSource(bg), wallpaper = true)

            val route = vm.stack.lastOrNull()
            AnimatedContent(
                targetState = Pair<Any, Int>(route ?: vm.tab, vm.stack.size),
                contentKey = { it.first },
                transitionSpec = {
                    val from = initialState.second
                    val to = targetState.second
                    when {
                        // Opening a page: a short slide in from the right.
                        to > from -> (slideInHorizontally(smooth(220)) { it / 6 } + fadeIn(smooth(200))) togetherWith
                            fadeOut(smooth(120))
                        // Going back: a short slide away to the right.
                        to < from -> fadeIn(smooth(180)) togetherWith
                            (slideOutHorizontally(smooth(200)) { it / 6 } + fadeOut(smooth(180)))
                        // Switching tabs: a quick crossfade.
                        else -> fadeIn(smooth(180)) togetherWith fadeOut(smooth(120))
                    }
                },
                label = "screens",
            ) { (key, _) ->
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    when (key) {
                        is Route.Online -> OnlinePage(ctx, key.item)
                        is Route.Saved -> SavedPage(ctx, key.info)
                        is Route.Mix -> MixPage(ctx, key.info)
                        Route.Offline -> DownloadsPage(ctx)
                        Route.Phone -> PhoneMusicPage(ctx)
                        Route.Stats -> StatsPage(ctx)
                        is Route.Badges -> BadgesPage(ctx, key.artist)
                        Tab.HOME -> HomeScreen(ctx)
                        Tab.EXPLORE -> ExploreScreen(ctx)
                        Tab.SEARCH -> SearchScreen(ctx)
                        Tab.LIBRARY -> LibraryScreen(ctx)
                        Tab.SETTINGS -> SettingsScreen(ctx)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).drawWithContent { if (!covered) drawContent() }.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 560.dp)) {
            RemoteStrip(ps.playing, Modifier.padding(bottom = 10.dp))
            AnimatedVisibility(
                visible = ps.hasItem,
                enter = slideInVertically(smooth(220)) { it } + fadeIn(smooth(200)),
                exit = slideOutVertically(smooth(250)) { it } + fadeOut(smooth(200)),
            ) {
                Column {
                    MiniPlayer(ps, page, onOpen = { full = true })
                    Spacer(Modifier.height(10.dp))
                }
            }
            TabBar(vm, page)
        }

        AnimatedVisibility(
            visibleState = fullState,
            enter = slideInVertically(smooth(250)) { it } + fadeIn(smooth(150)),
            exit = slideOutVertically(smooth(220)) { it } + fadeOut(smooth(220)),
        ) {
            FullPlayer(ps, colors, onClose = { full = false; peeking = false }, openEqualizer = openEqualizer, onPeek = { peeking = it })
        }

        // "Ada is in a Jam nearby": in the middle, over the dimmed app (clear of the "Playing on…" bar).
        NearbyJamPrompt(ps.playing)

        // Your account, from the picture on the top right of Home.
        ProfileSheet(ctx, page, vm.profileOpen) { vm.profileOpen = false }
        androidx.activity.compose.BackHandler(enabled = vm.profileOpen) { vm.profileOpen = false }

        if (vm.showYtLogin) YtLogin(onCookie = vm::onYtCookie, onClose = { vm.showYtLogin = false })

        // Your other devices on this YouTube Music account.
        DevicesPanel(ps.currentTrack(), ps.playing)
        // Listen together.
        JamPanel(ps, page)

        if (vm.voiceOpen) {
            VoiceSearch(
                onResult = { text ->
                    vm.voiceOpen = false
                    full = false
                    vm.selectTab(Tab.SEARCH)
                    vm.pickRecent(text)
                },
                onClose = { vm.voiceOpen = false },
            )
        }

        // Song menu, playlist menu, name box and short notes, above everything.
        MenuLayer(ps, page) { item ->
            full = false
            vm.open(Route.Online(item))
        }

        // "What's new" after an update: a small card at the top, once.
        WhatsNewCard(page)

        // Opening intro, once per launch (not again on rotation).
        var intro by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
        // The very first time: a short welcome, after the intro.
        if (Welcome.show) WelcomeScreen { Welcome.done() }
        if (intro) Intro { intro = false }
    }
}

/** What every screen needs, bundled so screens take one argument. */
class ScreenCtx(
    val vm: MainViewModel,
    val ps: PlayerState,
    val bg: Backdrop,
    val bottom: Dp,
    val openInBrowser: (String) -> Unit,
    val openEqualizer: () -> Unit,
) {
    /** Tapping something: songs play (with their neighbours as the queue), everything else opens. */
    fun tap(item: MusicItem, neighbours: List<MusicItem>) {
        if (item is SongItem) {
            // A song picked from a Home shelf or search plays on its own, and Autoplay follows it
            // with songs like it. With Autoplay off, the rest of the shelf plays after it instead.
            if (Settings.autoplay) {
                ps.playList(listOf(item.track), 0)
            } else {
                val songs = neighbours.filterIsInstance<SongItem>()
                ps.playList(songs.map { it.track }, songs.indexOf(item).coerceAtLeast(0))
            }
        } else {
            vm.open(Route.Online(item))
        }
    }
}

/* ---------------- Floating tab bar ---------------- */

@Composable
private fun TabBar(vm: MainViewModel, page: Backdrop) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().liquidGlass(page, 32.dp, floatTint(Color.White.copy(alpha = 0.06f)), blur = 6.dp, depth = 20.dp, bend = 26.dp).padding(5.dp),
        ) {
            val tabs = BarTabs
            val w = maxWidth / tabs.size
            val i = tabs.indexOf(vm.tab)
            // The highlight pill slides to the selected tab (and fades out on Search and Settings).
            val x by animateDpAsState(w * maxOf(i, 0), smooth(200), label = "pill")
            val shown by animateFloatAsState(if (i >= 0) 1f else 0f, smooth(180), label = "pillShown")
            // Frosted glass: a soft lighter highlight. Liquid glass: a hint of the cover's colour.
            val pill by animateColorAsState(
                if (Settings.cleanLook) Color.White.copy(alpha = 0.14f) else tabShade(CoverTint.accent).copy(alpha = 0.17f),
                tween(450), label = "pillColor",
            )
            Box(
                Modifier
                    .offset(x = x)
                    .width(w)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = shown }
                    .clip(CircleShape)
                    .background(pill),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { t -> TabItem(t, vm.tab == t) { vm.selectTab(t) } }
            }
        }
        Spacer(Modifier.width(10.dp))
        // Search: its own round button beside the bar. It opens with the keyboard up.
        val searching = vm.tab == Tab.SEARCH
        GlassCircle(
            64.dp, page,
            tint = if (searching) Color.White.copy(alpha = 0.14f) else floatTint(Color.White.copy(alpha = 0.06f)),
            onClick = {
                vm.focusSearch = true
                vm.selectTab(Tab.SEARCH)
            },
        ) { Ico(Icons.Rounded.Search, if (searching || Settings.cleanLook) Color.White else Color.White.copy(alpha = 0.8f), 26.dp) }
    }
}

/** The tabs in the bottom bar. */
private val BarTabs = listOf(Tab.HOME, Tab.EXPLORE, Tab.LIBRARY)

/** The cover colour, adjusted for the tab bar: lighter if it's dark, a touch darker if it's glaring. */
private fun tabShade(c: Color): Color {
    val l = c.luminance()
    return when {
        l < 0.35f -> readable(c)
        l > 0.8f -> androidx.compose.ui.graphics.lerp(c, Color.Black, 0.2f)
        else -> c
    }
}

@Composable
private fun RowScope.TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    // The selected tab takes the cover's colour: lifted when the cover is dark, deepened when it's
    // very bright, so it always reads on the glass. The others get a faint hint of it.
    // Frosted glass keeps it white: full white when selected, softer otherwise.
    val tint = if (Settings.cleanLook) Color.White else tabShade(CoverTint.accent)
    val color by animateColorAsState(
        when {
            selected -> tint
            Settings.cleanLook -> Color.White.copy(alpha = 0.62f)
            else -> androidx.compose.ui.graphics.lerp(Color.White, tint, 0.25f).copy(alpha = 0.72f)
        },
        tween(450), label = "tabColor",
    )
    val icon = when (tab) {
        Tab.HOME -> if (selected) Icons.Rounded.Home else Icons.Outlined.Home
        Tab.EXPLORE -> if (selected) Icons.Rounded.GridView else Icons.Outlined.GridView
        Tab.SEARCH -> Icons.Rounded.Search
        Tab.LIBRARY -> if (selected) Icons.Rounded.LibraryMusic else Icons.Outlined.LibraryMusic
        Tab.SETTINGS -> if (selected) Icons.Rounded.Settings else Icons.Outlined.Settings
    }
    Column(
        Modifier.weight(1f).fillMaxHeight().pressable(0.9f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Ico(icon, color, 24.dp)
        GText(tab.label, 10, color, if (selected) FontWeight.SemiBold else FontWeight.Medium, 1)
    }
}
