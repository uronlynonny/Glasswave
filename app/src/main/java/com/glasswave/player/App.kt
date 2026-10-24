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
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
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
    BackHandler(enabled = full) { full = false }
    BackHandler(enabled = !full && vm.showYtLogin) { vm.showYtLogin = false }
    BackHandler(enabled = !full && !vm.showYtLogin && vm.stack.isNotEmpty()) { vm.back() }
    BackHandler(enabled = vm.voiceOpen) { vm.voiceOpen = false }
    BackHandler(enabled = Menus.anyOpen) {
        Menus.closeName()
        Menus.closeSong()
        Menus.closePlaylist()
    }

    // Look for a new version when the app opens (at most every three hours), and after an update
    // say once what's new.
    LaunchedEffect(Unit) {
        Updater.checkIfDue()
        Updater.checkWhatsNew()
    }

    // Keep the progress bars moving while a song plays.
    LaunchedEffect(ps.playing) {
        while (ps.playing) {
            ps.tick()
            delay(400)
        }
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
            GlassBackground(colors, hiRes(ps.art), Modifier.backdropSource(bg))

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
                        Route.Offline -> DownloadsPage(ctx)
                        Route.Stats -> StatsPage(ctx)
                        Tab.HOME -> HomeScreen(ctx)
                        Tab.SEARCH -> SearchScreen(ctx)
                        Tab.LIBRARY -> LibraryScreen(ctx)
                        Tab.SETTINGS -> SettingsScreen(ctx)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).drawWithContent { if (!covered) drawContent() }.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 560.dp)) {
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

        // Your account, from the picture on the top right of Home.
        ProfileSheet(ctx, page, vm.profileOpen) { vm.profileOpen = false }
        androidx.activity.compose.BackHandler(enabled = vm.profileOpen) { vm.profileOpen = false }

        if (vm.showYtLogin) YtLogin(onCookie = vm::onYtCookie, onClose = { vm.showYtLogin = false })

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
        if (intro) Intro { intro = false }
    }
}

/**
 * The opening: a lime glow wakes up, the wave draws itself with a bright tip and ripples out,
 * the name rises in letter by letter, then everything opens up into the app. About two seconds;
 * tap to skip.
 */
@Composable
private fun Intro(onDone: () -> Unit) {
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    val draw = remember { androidx.compose.animation.core.Animatable(0f) }
    val ripple = remember { androidx.compose.animation.core.Animatable(0f) }
    val letters = remember { androidx.compose.animation.core.Animatable(0f) }
    val out = remember { androidx.compose.animation.core.Animatable(0f) }
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    val ease = androidx.compose.animation.core.FastOutSlowInEasing
    fun tw(ms: Int, delayMs: Int = 0) = androidx.compose.animation.core.tween<Float>(ms, delayMs, ease)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            out.animateTo(1f, tw(if (Settings.animations) 450 else 1))
            done()
        }
    }
    LaunchedEffect(Unit) {
        if (!Settings.animations) {
            glow.snapTo(1f); draw.snapTo(1f); letters.snapTo(1f)
            delay(500)
        } else {
            launch { glow.animateTo(1f, tw(700)) }
            delay(150)
            draw.animateTo(1f, tw(900))
            launch { ripple.animateTo(1f, tw(900)) }
            launch { letters.animateTo(1f, tw(600)) }
            delay(350)
            delay(350)
            delay(450)
        }
        leave()
    }
    val name = "Glasswave"
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - out.value
                val s = 1f + 0.12f * out.value
                scaleX = s
                scaleY = s
            }
            .background(Color(0xFF0A0B08))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { leave() },
        contentAlignment = Alignment.Center,
    ) {
        // Soft lime light behind the logo, breathing in.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2 - 40.dp.toPx())
            val r = size.minDimension * (0.35f + 0.25f * glow.value)
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Lime.copy(alpha = 0.22f * glow.value), Lime.copy(alpha = 0.05f * glow.value), Color.Transparent),
                    center = c, radius = r,
                ),
                r, c,
            )
            // A ring that spreads out as the wave finishes.
            if (ripple.value in 0.001f..0.999f) {
                drawCircle(
                    Lime.copy(alpha = 0.35f * (1f - ripple.value)),
                    radius = 60.dp.toPx() + size.minDimension * 0.45f * ripple.value,
                    center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx() * (1f - ripple.value) + 0.5f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-20).dp)) {
            androidx.compose.foundation.Canvas(Modifier.size(160.dp)) {
                // Same wave as the app icon: M30,60 C38,38 48,38 54,54 S70,70 78,48 (in a 108 box).
                val k = size.minDimension / 56f
                fun x(v: Float) = size.width / 2 + (v - 54f) * k
                fun y(v: Float) = size.height / 2 + (v - 54f) * k
                val wave = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x(30f), y(60f))
                    cubicTo(x(38f), y(38f), x(48f), y(38f), x(54f), y(54f))
                    cubicTo(x(60f), y(70f), x(70f), y(70f), x(78f), y(48f))
                }
                val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(wave, false) }
                val len = measure.length * draw.value
                val part = androidx.compose.ui.graphics.Path()
                measure.getSegment(0f, len, part, true)
                val round = androidx.compose.ui.graphics.StrokeCap.Round
                // Glow under the line, then the line itself.
                drawPath(part, Lime.copy(alpha = 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(26f * k, cap = round))
                drawPath(part, Lime.copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(15f * k, cap = round))
                drawPath(part, Lime, style = androidx.compose.ui.graphics.drawscope.Stroke(9f * k, cap = round))
                // A bright spark at the drawing tip.
                if (draw.value in 0.01f..0.99f) {
                    val tip = measure.getPosition(len)
                    drawCircle(Color.White.copy(alpha = 0.5f), 9f * k, tip)
                    drawCircle(Color.White, 4.5f * k, tip)
                }
            }
            Row {
                name.forEachIndexed { i, ch ->
                    val f = (letters.value * (name.length + 3) - i).coerceIn(0f, 3f) / 3f
                    GText(
                        ch.toString(), 30, Color.White.copy(alpha = f), FontWeight.Bold,
                        modifier = Modifier.graphicsLayer { translationY = (1f - f) * 14.dp.toPx() },
                    )
                }
            }
        }
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
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(64.dp).liquidGlass(page, 32.dp, blur = 6.dp, depth = 20.dp, bend = 26.dp).padding(5.dp),
    ) {
        val tabs = Tab.entries
        val w = maxWidth / tabs.size
        // The highlight pill slides to the selected tab.
        val x by animateDpAsState(w * vm.tab.ordinal, smooth(200), label = "pill")
        val pill by animateColorAsState(tabShade(CoverTint.accent).copy(alpha = 0.17f), tween(450), label = "pillColor")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(pill),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { t -> TabItem(t, vm.tab == t) { vm.selectTab(t) } }
        }
    }
}

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
    val tint = tabShade(CoverTint.accent)
    val color by animateColorAsState(
        if (selected) tint else androidx.compose.ui.graphics.lerp(Color.White, tint, 0.25f).copy(alpha = 0.72f),
        tween(450), label = "tabColor",
    )
   val icon = when (tab) {
        Tab.HOME -> if (selected) Icons.Rounded.Home else Icons.Outlined.Home
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
