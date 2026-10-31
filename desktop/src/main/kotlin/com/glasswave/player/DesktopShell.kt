// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/** The PC layout (sidebar and bottom player bar) is used from this window width up. */
fun desktopLayout(): Boolean = WindowSize.widthDp >= 900f

/** What the full player should show when it opens from the player bar. */
object PlayerView {
    var lyrics by mutableStateOf(false)
    /** Set by the queue button: the full player opens on "Up next". */
    var queue by mutableStateOf(false)
    /** Opens the full player from outside the app's screens (checks). */
    var openFull by mutableStateOf(false)
}

/* ---------------- Sidebar: an icon rail that opens on hover ---------------- */

/** Width of the rail when it's just icons, and when it's open. */
val RailWidth = 76.dp
private val RailOpen = 252.dp
/** Room the pages leave on the left for the rail (its width plus margins). */
val RailSpace = RailWidth + 24.dp

/**
 * The PC sidebar: a slim glass rail of icons floating over the page. Rest the mouse on it and it
 * slides open over the page with the names; move away and it folds back. Refresh sits in its
 * bottom-left corner and Update in its bottom-right.
 */
@Composable
fun Sidebar(vm: MainViewModel, ps: PlayerState, page: Backdrop, modifier: Modifier = Modifier) {
    val playlists by vm.playlists.collectAsState()
    // Watched before the buttons inside see the pointer, so moving over them keeps it open.
    var hovered by remember { mutableStateOf(false) }
    // A short pause before opening (built into the animation), so sweeping the mouse past doesn't pop it open.
    val w by animateDpAsState(if (hovered) RailOpen else RailWidth, tween(220, delayMillis = if (hovered) 110 else 160, easing = Motion.ease), label = "rail")
    val k = ((w - RailWidth) / (RailOpen - RailWidth)).coerceIn(0f, 1f)
    Column(
        modifier
            .fillMaxHeight()
            .padding(start = 12.dp, top = 12.dp + TitleBar.inset, bottom = 12.dp)
            .width(w)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        when (e.type) {
                            androidx.compose.ui.input.pointer.PointerEventType.Enter, androidx.compose.ui.input.pointer.PointerEventType.Move -> hovered = true
                            androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                        }
                    }
                }
            }
            .shadow((18 * k).dp, RoundedCornerShape(24.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .liquidGlass(page, 24.dp, tint = Color.Black.copy(alpha = 0.16f + 0.26f * k), blur = 16.dp, depth = 12.dp, bend = 14.dp, rim = CoverTint.accent)
            .clipToBounds()
            .padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        // The wave, and the name when open.
        Row(Modifier.height(44.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { WaveMark(Modifier.size(30.dp), readable(CoverTint.accent)) }
            RailLabel("Glasswave", k, 19, FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        RailItem(Icons.Rounded.Home, Icons.Outlined.Home, "Home", vm.tab == Tab.HOME && vm.stack.isEmpty(), k) { vm.selectTab(Tab.HOME) }
        RailItem(Icons.Rounded.Search, Icons.Rounded.Search, "Search", vm.tab == Tab.SEARCH && vm.stack.isEmpty(), k) { vm.selectTab(Tab.SEARCH) }
        RailItem(Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, "Library", vm.tab == Tab.LIBRARY && vm.stack.isEmpty(), k) { vm.selectTab(Tab.LIBRARY) }
        RailItem(Icons.Rounded.BarChart, Icons.Rounded.BarChart, "Your stats", vm.stack.lastOrNull() == Route.Stats, k) {
            if (vm.stack.lastOrNull() != Route.Stats) vm.open(Route.Stats)
        }
        RailItem(Icons.Rounded.Settings, Icons.Outlined.Settings, "Settings", vm.tab == Tab.SETTINGS && vm.stack.isEmpty(), k) { vm.selectTab(Tab.SETTINGS) }

        // Your playlists: covers in the rail, with names when open.
        Box(Modifier.padding(start = 10.dp, top = 14.dp, bottom = 8.dp).height(1.dp).width(36.dp + (w - RailWidth) * 0.85f).background(Color.White.copy(alpha = 0.14f)))
        RailLabel("PLAYLISTS", k, 11, FontWeight.SemiBold, Faint, Modifier.padding(start = 12.dp, bottom = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(playlists, key = { it.id }) { p ->
                val opened = (vm.stack.lastOrNull() as? Route.Saved)?.info?.id == p.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (opened) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .pressable(0.97f) { if (!opened) vm.open(Route.Saved(p)) }
                        .onRightClick { Menus.openPlaylist(p) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val live = PlayingFrom.id == p.id && ps.hasItem
                    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        val frame = RoundedCornerShape(11.dp)
                        Box(
                            Modifier.size(44.dp).glass(frame)
                                .then(if (live) Modifier.border(1.2.dp, readable(CoverTint.accent).copy(alpha = 0.85f), frame) else Modifier)
                                .padding(3.dp),
                        ) {
                            Cover(p.thumb, Modifier.fillMaxSize(), 8.dp)
                            if (live) Box(
                                Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) { EqualizerBars(readable(CoverTint.accent), ps.playing, Modifier.size(16.dp)) }
                        }
                    }
                    Column(Modifier.graphicsLayer { alpha = k }) {
                        GText(p.name, 13, if (live) readable(CoverTint.accent) else Color.White, FontWeight.Medium, 1, Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                        GText("${p.count} songs · ${p.source.label}", 11, Faint, maxLines = 1, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                    }
                }
            }
        }

        // Bottom corners: Refresh on the left, Update on the right (stacked while it's just icons).
        if (k > 0.6f) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp).graphicsLayer { alpha = (k - 0.6f) / 0.4f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val news = Updater.available
                // While an update downloads or waits, its status needs the room: Refresh keeps just its icon.
                val updating = Updater.progress != null || Updater.ready != null || news != null
                RefreshButton(vm)
                if (!updating) GText(if (vm.refreshing) "Refreshing…" else "Refresh", 13, Dim, FontWeight.Medium, 1, Modifier.padding(start = 8.dp))
                val label = when {
                    Updater.progress != null -> "Downloading ${((Updater.progress ?: 0f) * 100).toInt()}%"
                    Updater.ready != null -> "Restart to update"
                    news != null -> "${news.version} is out"
                    else -> "Updates"
                }
                // Takes only the space that's left, so it can never push the button out of the sidebar.
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    GText(label, 13, if (updating) readable(CoverTint.accent) else Dim, FontWeight.Medium, 1)
                }
                UpdateButton(40.dp)
            }
        } else {
            Column(
                Modifier.width(56.dp).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RefreshButton(vm)
                UpdateButton(40.dp)
            }
        }
    }
}

/** A label that fades in as the rail opens, without squashing while it's narrow. */
@Composable
private fun RailLabel(text: String, k: Float, size: Int, weight: FontWeight, color: Color = Color.White, modifier: Modifier = Modifier) {
    GText(text, size, color, weight, 1, modifier.graphicsLayer { alpha = k }.wrapContentWidth(Alignment.Start, unbounded = true))
}

@Composable
private fun RailItem(on: ImageVector, off: ImageVector, label: String, selected: Boolean, k: Float, onClick: () -> Unit) {
    val tint = readable(CoverTint.accent)
    val color by animateColorAsState(if (selected) tint else Color.White.copy(alpha = 0.78f), tween(160), label = "nav")
    val pill by animateColorAsState(if (selected) tint.copy(alpha = 0.18f) else Color.Transparent, tween(160), label = "navPill")
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(pill)
            .pressable(0.97f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { Ico(if (selected) on else off, color, 23.dp) }
        RailLabel(label, k, 14, if (selected) FontWeight.SemiBold else FontWeight.Medium, color)
    }
}

/** Reloads the Home feed (a computer has no pull-to-refresh). Spins while it loads. */
@Composable
private fun RefreshButton(vm: MainViewModel) {
    val spin = if (vm.refreshing) {
        val t = rememberInfiniteTransition(label = "refresh")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spin").value
    } else 0f
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .pressable(0.88f) {
                vm.selectTab(Tab.HOME)
                vm.refreshHome()
            },
        contentAlignment = Alignment.Center,
    ) {
        Ico(Icons.Rounded.Refresh, Color.White.copy(alpha = 0.85f), 20.dp, Modifier.graphicsLayer { rotationZ = spin })
    }
}

/** The app's wave mark, drawn in the cover's colour. */
@Composable
fun WaveMark(modifier: Modifier = Modifier, color: Color = Lime) {
    Canvas(modifier) {
        val k = size.minDimension / 56f
        fun x(v: Float) = size.width / 2 + (v - 54f) * k
        fun y(v: Float) = size.height / 2 + (v - 54f) * k
        val wave = Path().apply {
            moveTo(x(30f), y(60f))
            cubicTo(x(38f), y(38f), x(48f), y(38f), x(54f), y(54f))
            cubicTo(x(60f), y(70f), x(70f), y(70f), x(78f), y(48f))
        }
        drawPath(wave, color, style = Stroke(9f * k, cap = StrokeCap.Round))
    }
}

/* ---------------- Player bar ---------------- */

/** Height of the player bar plus its margin, so lists can scroll clear of it. */
val PlayerBarSpace = 104.dp

/**
 * Now playing, along the bottom of the window: the song on the left, the controls and the seek
 * bar in the middle, lyrics, queue and volume on the right.
 */
@Composable
fun PlayerBar(ps: PlayerState, page: Backdrop, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val accent = readable(CoverTint.accent)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .height(80.dp)
            .blockPointer()
            .liquidGlass(page, 22.dp, tint = floatTint(Color.Black.copy(alpha = 0.22f)), blur = 22.dp, depth = 14.dp, bend = 16.dp, rim = CoverTint.accent)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The song
        Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).pressable(0.97f, onClick = onOpen)) {
                Cover(ps.art, Modifier.fillMaxSize(), 8.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false).pressable(0.99f, onClick = onOpen)) {
                GText(ps.title.ifBlank { "Nothing playing" }, 14, Color.White, FontWeight.SemiBold, 1)
                GText(if (ps.buffering) "Loading…" else ps.artist, 12, Dim, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            ps.currentTrack()?.let { t ->
                val liked = Library.isLiked(t.url)
                BarButton(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) accent else Dim, 20.dp) { Library.toggleLike(t) }
            }
        }

        // Controls and seek bar
        Column(Modifier.weight(0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarButton(Icons.Rounded.Shuffle, if (ps.shuffle) accent else Dim, 19.dp) { ps.toggleShuffle() }
                Spacer(Modifier.width(10.dp))
                BarButton(Icons.Rounded.SkipPrevious, Color.White, 26.dp, if (Os.isMac) "Previous  ⌘←" else "Previous  Ctrl+←") { ps.previous() }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White).pressable(0.92f) { ps.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Ico(if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Color(0xFF111111), 24.dp)
                }
                Spacer(Modifier.width(10.dp))
                BarButton(Icons.Rounded.SkipNext, Color.White, 26.dp, if (Os.isMac) "Next  ⌘→" else "Next  Ctrl+→") { ps.next() }
                Spacer(Modifier.width(10.dp))
                BarButton(
                    if (ps.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    if (ps.repeat != Player.REPEAT_MODE_OFF) accent else Dim, 19.dp,
                ) { ps.cycleRepeat() }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GText(fmtTime(ps.positionMs / 1000), 11, Faint, modifier = Modifier.width(40.dp))
                SeekLine(
                    fraction = if (ps.durationMs > 0) ps.positionMs.toFloat() / ps.durationMs else 0f,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                ) { f -> if (ps.durationMs > 0) ps.seekTo((f * ps.durationMs).toLong()) }
                GText(fmtTime(ps.durationMs / 1000), 11, Faint, modifier = Modifier.width(40.dp).padding(start = 8.dp))
            }
        }

        // Lyrics, queue, volume, full screen
        Row(Modifier.weight(0.3f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            DevicesButton(36.dp, 20.dp, Dim)
            BarButton(Icons.Rounded.Lyrics, if (PlayerView.lyrics) accent else Dim, 20.dp) {
                PlayerView.lyrics = true
                onOpen()
            }
            BarButton(Icons.AutoMirrored.Rounded.QueueMusic, Dim, 21.dp) {
                PlayerView.queue = true
                onOpen()
            }
            Spacer(Modifier.width(6.dp))
            BarButton(if (AppVolume.level <= 0.001f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, Dim, 20.dp) {
                AppVolume.set(if (AppVolume.level > 0.001f) 0f else 0.7f)
            }
            SeekLine(AppVolume.level, Color.White, Modifier.width(100.dp)) { AppVolume.set(it) }
            Spacer(Modifier.width(6.dp))
            BarButton(Icons.Rounded.PictureInPictureAlt, if (Mini.enabled) accent else Dim, 19.dp, "Mini player") { Mini.toggle() }
            BarButton(Icons.Rounded.OpenInFull, Dim, 18.dp, onClick = onOpen)
        }
    }
}

/** A player bar button. With [hint], resting the mouse on it shows the hint (its keyboard shortcut). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BarButton(icon: ImageVector, color: Color, iconSize: Dp, hint: String? = null, onClick: () -> Unit) {
    val button = @Composable {
        Box(Modifier.size(36.dp).pressable(0.9f, onClick = onClick), contentAlignment = Alignment.Center) {
            Ico(icon, color, iconSize)
        }
    }
    if (hint == null) return button()
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xEE1C1D22)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                GText(hint, 12, Color.White)
            }
        },
        delayMillis = 500,
    ) { button() }
}

/**
 * A thin line you can click or drag along (seek bar and volume). It thickens and shows its knob
 * while the mouse is over it, like Apple Music.
 */
@Composable
fun SeekLine(fraction: Float, color: Color, modifier: Modifier = Modifier, onSet: (Float) -> Unit) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    val active = hovered || drag != null
    val thick by animateDpAsState(if (active) 6.dp else 4.dp, tween(120), label = "seekThick")
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    Canvas(
        modifier
            .height(18.dp)
            .hoverable(hoverSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { o -> onSet((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(onSet); drag = null },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        val h = thick.toPx()
        val y = (size.height - h) / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(if (active) color else color.copy(alpha = 0.85f), Offset(0f, y), Size(size.width * shown, h), r)
        if (active) drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * shown, size.height / 2))
    }
}

/**
 * Makes this area solid to the mouse: clicks, hovers and wheel scrolls that land on it (and that its
 * own buttons don't use) stop here instead of reaching whatever is underneath.
 */
fun Modifier.blockPointer(): Modifier = this.pointerInput(Unit) {
    // Just listening makes this area the mouse's target, so nothing underneath gets it. It doesn't
    // consume anything, so the bar's own buttons and sliders work as usual.
    awaitPointerEventScope {
        while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
    }
}
