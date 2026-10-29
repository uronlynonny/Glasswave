// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/* ---------------- Mini player ---------------- */

/** Floating glass bar above the tabs. Tap to open the full player; swipe left/right to change song. */
@Composable
fun MiniPlayer(ps: PlayerState, page: Backdrop, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .liquidGlass(page, 32.dp, floatTint(Color.White.copy(alpha = 0.06f)), blur = 10.dp, depth = 18.dp, bend = 22.dp)
            .clip(RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = dx.value
                        when {
                            v < -threshold -> ps.next()
                            v > threshold -> ps.previous()
                        }
                        scope.launch { dx.animateTo(0f, smooth(180)) }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f, smooth(180)) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { dx.snapTo(dx.value + amount * 0.6f) }
                    },
                )
            }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxSize().offset { IntOffset(dx.value.roundToInt(), 0) }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(ps.art, Modifier.size(48.dp), 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                GText(ps.title, 15, Color.White, FontWeight.SemiBold, 1)
                GText(if (ps.buffering) "Loading..." else ps.artist, 13, Dim, maxLines = 1)
            }
            Box(Modifier.size(46.dp).pressable(0.85f) { ps.toggle() }, contentAlignment = Alignment.Center) {
                Ico(if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Color.White, 30.dp)
            }
            Box(Modifier.size(46.dp).pressable(0.85f) { ps.next() }, contentAlignment = Alignment.Center) {
                Ico(Icons.Rounded.FastForward, Color.White, 30.dp)
            }
        }
    }
}

/* ---------------- Full player ---------------- */

private enum class Sheet { NONE, MORE, QUEUE }

@Composable
fun FullPlayer(ps: PlayerState, colors: CoverColors, onClose: () -> Unit, openEqualizer: () -> Unit, onPeek: (Boolean) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 160.dp.toPx() }
    val corner by animateDpAsState(if (dragY.value > 1f) 36.dp else 0.dp, smooth(120), label = "corner")
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var lyrics by PlayerView::lyrics
    androidx.compose.runtime.LaunchedEffect(PlayerView.queue) { if (PlayerView.queue) { sheet = Sheet.QUEUE; PlayerView.queue = false } }
    androidx.activity.compose.BackHandler(enabled = lyrics) { lyrics = false }
    val glow = rememberCursorGlow()

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .clip(RoundedCornerShape(topStart = corner, topEnd = corner))
            .pointerInput(Unit) {
                // Swipe down to close.
                detectVerticalDragGestures(
                    onDragStart = { onPeek(true) },
                    onDragEnd = {
                        if (dragY.value > closeAt) onClose()
                        else scope.launch { dragY.animateTo(0f, smooth(180)); onPeek(false) }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f, smooth(180)); onPeek(false) } },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        scope.launch { dragY.snapTo((dragY.value + amount).coerceAtLeast(0f)) }
                    },
                )
            }
            .clickable(remember { MutableInteractionSource() }, null) { }
            .trackCursorGlow(glow),
    ) {
        val fp = rememberBackdrop()
        GlassBackground(colors, hiRes(ps.art), Modifier.backdropSource(fp), artAlpha = 0.55f)
        // A soft light that follows the mouse, behind the cover and buttons.
        CursorGlowLayer(glow)

        val ui = PlayerUi(ps, fp, colors, onClose, onQueue = { sheet = Sheet.QUEUE }, onMore = { sheet = Sheet.MORE }, onLyrics = { lyrics = true })
        if (isWide()) WideStyle(ui, panel = Settings.playerStyle == PlayerStyle.PANEL) else when (Settings.playerStyle) {
            PlayerStyle.IMMERSIVE -> Immersive(ui)
            PlayerStyle.CANVAS -> CanvasStyle(ui)
            PlayerStyle.CARD -> CardStyle(ui)
            PlayerStyle.PANEL -> PanelStyle(ui)
            PlayerStyle.MINIMAL -> MinimalStyle(ui)
        }

        // Lyrics over the player: slides up and fades in with glass, a plain fade without.
        AnimatedVisibility(
            lyrics,
            enter = if (Settings.glass) slideInVertically(smooth(250)) { it / 5 } + fadeIn(smooth(220)) else fadeIn(smooth(180)),
            exit = if (Settings.glass) slideOutVertically(smooth(200)) { it / 5 } + fadeOut(smooth(180)) else fadeOut(smooth(150)),
        ) {
            LyricsScreen(ps, glow) { lyrics = false }
        }

        // Sheets: "Up next" and the ••• menu.
        // On PC they open as a see-through glass panel on the right, like Spotify's queue.
        val side = desktopLayout()
        AnimatedVisibility(sheet != Sheet.NONE, enter = fadeIn(smooth(150)), exit = fadeOut(smooth(150))) {
            Box(Modifier.fillMaxSize().background(Color(if (side) 0x22000000 else 0x77000000)).clickable(remember { MutableInteractionSource() }, null) { sheet = Sheet.NONE })
        }
        AnimatedVisibility(
            sheet != Sheet.NONE,
            modifier = Modifier.align(if (side) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (side) slideInHorizontally(smooth(220)) { it / 3 } + fadeIn(smooth(160)) else slideInVertically(smooth(220)) { it / 3 } + fadeIn(smooth(180)),
            exit = if (side) slideOutHorizontally(smooth(180)) { it / 3 } + fadeOut(smooth(150)) else slideOutVertically(smooth(180)) { it / 3 } + fadeOut(smooth(150)),
        ) {
            // Keep showing the last sheet while it slides away.
            var shown by remember { mutableStateOf(Sheet.MORE) }
            if (sheet != Sheet.NONE) shown = sheet
            if (shown == Sheet.QUEUE) QueueSheet(ps, fp, colors) else MoreSheet(ps, fp, colors, openEqualizer)
        }
    }
}

/** Everything a player style needs. */
private class PlayerUi(
    val ps: PlayerState,
    val fp: Backdrop,
    val colors: CoverColors,
    val onClose: () -> Unit,
    val onQueue: () -> Unit,
    val onMore: () -> Unit,
    val onLyrics: () -> Unit,
)

@Composable
private fun Grabber(onClose: () -> Unit, modifier: Modifier = Modifier) {
    // On a computer: a real button to close (there's no swipe down). Esc works too.
    if (desktopLayout()) {
        Box(modifier.padding(top = 8.dp).size(44.dp).pressable(0.9f, onClick = onClose), contentAlignment = Alignment.Center) {
            Ico(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, Color.White.copy(alpha = 0.85f), 30.dp)
        }
        return
    }
    Box(
        modifier
            .padding(top = 10.dp)
            .size(width = 40.dp, height = 5.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.5f))
            .clickable(onClick = onClose),
    )
}

/** The cover, with the song's animated cover (music video) on top when there is one and the connection allows. */
@Composable
private fun CoverArt(ps: PlayerState, modifier: Modifier) {
    Box(modifier.background(Color.White.copy(alpha = 0.05f))) {
        // The still cover gently comes alive while the song plays (and no music video is showing).
        val alive = Settings.livingCover && Settings.animations && ps.playing && !VideoState.showing
        // The image loader fades each new cover in by itself.
        val u = hiRes(ps.art)
        // The small cover (already loaded for the player bar) shows at once underneath, so the
        // cover is never blank while the sharp one loads.
        if (ps.art != null && ps.art != u) {
            AsyncImage(
                model = ps.art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (u != null) {
            AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // While it plays, the cover gently ripples and drifts on top.
            if (Settings.livingCover && Settings.animations) LivingArt(u, alive, Modifier.fillMaxSize())
        }
        MusicVideoLayer(ps, Modifier.fillMaxSize())
    }
}

/** Cover edge to edge at the top, fading into the controls. */
@Composable
private fun Immersive(ui: PlayerUi) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CoverArt(
                ui.ps,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // Fade the bottom of the cover (or video) into the background.
                        drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
            )
            Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))))
            Grabber(ui.onClose, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 26.dp)) { Details(ui) }
    }
}

/** Cover (or video) fills the whole screen; controls sit on a dark fade at the bottom. */
@Composable
private fun CanvasStyle(ui: PlayerUi) {
    Box(Modifier.fillMaxSize()) {
        CoverArt(ui.ps, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x77000000), 0.18f to Color.Transparent, 0.45f to Color.Transparent, 0.75f to Color(0xCC000000), 1f to Color(0xEE000000),
                ),
            ),
        )
        Grabber(ui.onClose, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 26.dp)) { Details(ui) }
    }
}

/** Big rounded cover that shrinks a little when paused. */
@Composable
private fun CardStyle(ui: PlayerUi) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Grabber(ui.onClose)
        Spacer(Modifier.weight(1f))
        val scale by animateFloatAsState(if (ui.ps.playing) 1f else 0.88f, smooth(220), label = "cover")
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(24.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(16.dp)),
        ) { CoverArt(ui.ps, Modifier.fillMaxSize()) }
        Spacer(Modifier.weight(1f))
        Details(ui, Modifier.padding(horizontal = 6.dp))
    }
}

/** Cover on top, controls on a glass panel tinted by the cover. */
@Composable
private fun PanelStyle(ui: PlayerUi) {
    Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Grabber(ui.onClose)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .aspectRatio(1f)
                .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(22.dp)),
        ) { CoverArt(ui.ps, Modifier.fillMaxSize()) }
        Spacer(Modifier.weight(1f))
        // A slim panel: tighter spacing and smaller buttons than the other styles.
        Column(
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .liquidGlass(ui.fp, 28.dp, sheetTint(ui.colors, 0.55f), blur = 18.dp, depth = 12.dp, bend = 14.dp, rim = ui.colors.a)
                .padding(start = 18.dp, end = 18.dp, top = 12.dp),
        ) { Details(ui, compact = true) }
    }
}

/**
 * Computers, tablets and landscape: the cover on the left, everything else on the right.
 * Card: a big rounded cover that settles back a little when paused.
 * Glass panel: the controls sit on a sheet of glass tinted by the cover, like on the phone.
 */
@Composable
private fun WideStyle(ui: PlayerUi, panel: Boolean) {
    Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Grabber(ui.onClose)
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.45f), contentAlignment = Alignment.Center) {
                val scale by animateFloatAsState(if (panel || ui.ps.playing) 1f else 0.9f, smooth(220), label = "cover")
                val corner = if (panel) 22.dp else 20.dp
                Box(
                    Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .shadow(24.dp, RoundedCornerShape(corner), ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(RoundedCornerShape(corner)),
                ) { CoverArt(ui.ps, Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(56.dp))
            // The controls sit in the middle of their half, not pressed up against the cover.
            Box(Modifier.weight(0.55f), contentAlignment = Alignment.Center) {
                if (panel) {
                    Column(
                        Modifier
                            .widthIn(max = 600.dp)
                            .liquidGlass(ui.fp, 28.dp, sheetTint(ui.colors, 0.55f), blur = 18.dp, depth = 12.dp, bend = 14.dp, rim = ui.colors.a)
                            .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 6.dp),
                    ) { Details(ui) }
                } else {
                    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.Center) { Details(ui) }
                }
            }
        }
    }
}

/** A smaller cover and a big title, with lots of breathing room. */
@Composable
private fun MinimalStyle(ui: PlayerUi) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Grabber(ui.onClose)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f).clip(RoundedCornerShape(14.dp))) { CoverArt(ui.ps, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GText(ui.ps.title, 28, Color.White, FontWeight.Bold, 2)
                ArtistName(ui.ps, 18, 0.6f)
            }
            Spacer(Modifier.width(10.dp))
            LikeButton(ui.ps, CoverTint.accent)
            Spacer(Modifier.width(4.dp))
            LyricsButton(ui.ps.playing, ui.colors.a, ui.onLyrics)
        }
        Spacer(Modifier.weight(1f))
        Details(ui, showTitle = false)
    }
}

/** The artist under the song's title. Tap it to open the artist's page. */
@Composable
private fun ArtistName(ps: PlayerState, size: Int, alpha: Float) {
    val canTap = !PcMusic.isPcSong(ps.currentTrack()?.url ?: "")
    GText(
        ps.artist, size, Color.White.copy(alpha = alpha), maxLines = 1,
        modifier = Modifier.pressable(0.97f, enabled = canTap) { ps.currentTrack()?.let { Menus.artistOf = it } },
    )
}

/** Title, progress bar, controls and the bottom row. Shared by every style. */
@Composable
private fun Details(ui: PlayerUi, modifier: Modifier = Modifier, showTitle: Boolean = true, compact: Boolean = false) {
    val ps = ui.ps
    Column(modifier.fillMaxWidth()) {
        if (Flow.active) FlowBar(ps)
        if (showTitle) {
            // Title on the left, the lyrics button on the top right, in every player style.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GText(ps.title, if (compact) 20 else 22, Color.White, FontWeight.Bold, 1)
                    ArtistName(ps, if (compact) 16 else 18, 0.62f)
                }
                Spacer(Modifier.width(10.dp))
                LikeButton(ps, CoverTint.accent)
                Spacer(Modifier.width(4.dp))
                LyricsButton(ps.playing, ui.colors.a, ui.onLyrics)
            }
            Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
        }
        // Bars that dance to the music (Settings > Look).
        if (Settings.visualizer != VisualizerStyle.OFF) {
            Visualizer(ps.playing, CoverTint.accent, Modifier.fillMaxWidth().height(if (compact) 24.dp else 34.dp))
            Spacer(Modifier.height(6.dp))
        }
        AppleSeekBar(ps)
        if (ps.error != null) {
            Spacer(Modifier.height(4.dp))
            GText(ps.error ?: "", 13, Color(0xFFFFA8A8), maxLines = 2)
        }
        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
        Controls(ui, compact)
        Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        VolumeBar()
        // Bottom row: queue on the left, status in the middle, the ••• menu on the right.
        Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp, bottom = if (compact) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).pressable(0.85f, onClick = ui.onQueue), contentAlignment = Alignment.Center) {
                Ico(Icons.AutoMirrored.Rounded.QueueMusic, Color.White.copy(alpha = 0.85f), 24.dp)
            }
            DevicesButton()

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Badge(Settings.quality.label.uppercase())
                ps.currentId?.let { id ->
                    // On the phone: a small tick in the cover's colour. Downloading: a filling ring.
                    val pct = Downloads.progress[id]
                    if (pct != null) {
                        Spacer(Modifier.width(6.dp))
                        DownloadRing(pct, 16.dp, CoverTint.accent, 2.dp)
                    } else if (Downloads.isDownloaded(id)) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(18.dp).clip(CircleShape).background(CoverTint.accent), contentAlignment = Alignment.Center) {
                            Ico(Icons.Rounded.DownloadDone, Color.Black, 13.dp)
                        }
                    }
                }
                if (SleepTimer.active) { Spacer(Modifier.width(6.dp)); Badge("SLEEP") }
                if (ps.speed != 1f) { Spacer(Modifier.width(6.dp)); Badge("${ps.speed}x") }
            }
            Box(Modifier.size(44.dp).pressable(0.85f, onClick = ui.onMore), contentAlignment = Alignment.Center) {
                Ico(Icons.Rounded.MoreHoriz, Color.White.copy(alpha = 0.85f), 26.dp)
            }
        }
    }
}

/** Previous / play / next, in the look picked in Settings, with optional shuffle and repeat. */
@Composable
private fun Controls(ui: PlayerUi, compact: Boolean = false) {
    val ps = ui.ps
    val side = if (compact) 56.dp else 64.dp
    val sideIcon = if (compact) 36.dp else 42.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (Settings.showModeButtons) ShuffleToggle(ps)
        Box(Modifier.size(side).pressable(0.85f) { ps.previous() }, contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.FastRewind, Color.White, sideIcon)
        }
        PlayButton(ui, if (compact) 0.84f else 1f)
        Box(Modifier.size(side).pressable(0.85f) { ps.next() }, contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.FastForward, Color.White, sideIcon)
        }
        if (Settings.showModeButtons) RepeatToggle(ps)
    }
}

@Composable
private fun PlayButton(ui: PlayerUi, k: Float = 1f) {
    val ps = ui.ps
    val icon = if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
    when (Settings.buttonStyle) {
        ButtonStyle.CLASSIC -> Box(Modifier.size(84.dp * k).pressable(0.88f) { ps.toggle() }, contentAlignment = Alignment.Center) {
            Ico(icon, Color.White, 64.dp * k)
        }
        ButtonStyle.GLASS -> GlassCircle(78.dp * k, ui.fp, tint = Color.White.copy(alpha = 0.10f), onClick = { ps.toggle() }) {
            Ico(icon, Color.White, 42.dp * k)
        }
        ButtonStyle.SOLID -> Box(
            Modifier.pressable(0.9f) { ps.toggle() }.size(76.dp * k).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, Color(0xFF111111), 42.dp * k) }
        ButtonStyle.TINTED -> Box(
            Modifier.pressable(0.9f) { ps.toggle() }.size(76.dp * k).clip(CircleShape).background(lerp(ui.colors.a, Color.Black, 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, Color.White, 42.dp * k) }
    }
}

/** The phone's media volume, as a thin bar like the progress bar. Follows the volume buttons too. */
@Composable
private fun VolumeBar() {
    // On desktop this is Glasswave's own volume (Windows' volume still applies on top).
    val max = 100
    val level = (AppVolume.level * max).roundToInt()
    var drag by remember { mutableStateOf<Float?>(null) }
    fun set(f: Float) {
        drag = f
        AppVolume.set(f)
    }
    val thick by animateDpAsState(if (drag != null) 6.dp else 3.dp, smooth(120), label = "volThick")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Ico(Icons.AutoMirrored.Rounded.VolumeMute, Color.White.copy(alpha = 0.45f), 14.dp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(22.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o -> set((o.x / size.width).coerceIn(0f, 1f)); drag = null }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> set((o.x / size.width).coerceIn(0f, 1f)) },
                        onDragEnd = { drag = null },
                        onDragCancel = { drag = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            set((change.position.x / size.width).coerceIn(0f, 1f))
                        },
                    )
                }
                .drawBehind {
                    val frac = drag ?: (level.toFloat() / max)
                    val h = thick.toPx()
                    val top = (size.height - h) / 2
                    drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                    drawRoundRect(Color.White.copy(alpha = if (drag != null) 0.95f else 0.6f), Offset(0f, top), Size(size.width * frac, h), CornerRadius(h / 2))
                },
        )
        Spacer(Modifier.width(8.dp))
        Ico(Icons.AutoMirrored.Rounded.VolumeUp, Color.White.copy(alpha = 0.45f), 14.dp)
    }
}

@Composable
private fun Badge(text: String) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        GText(text, 10, Color.White.copy(alpha = 0.75f), FontWeight.Bold)
    }
}

/* ---------------- Shuffle and repeat ---------------- */

/** An icon that lights up in the accent colour with a dot underneath when on. */
@Composable
private fun ModeToggle(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (on) Accent else Color.White.copy(alpha = 0.6f), smooth(150), label = "mode")
    Column(Modifier.size(44.dp).pressable(0.85f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Ico(icon, color, 22.dp)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(4.dp).clip(CircleShape).background(if (on) Accent else Color.Transparent))
    }
}

@Composable
private fun ShuffleToggle(ps: PlayerState) = ModeToggle(Icons.Rounded.Shuffle, ps.shuffle) { ps.toggleShuffle() }

@Composable
private fun RepeatToggle(ps: PlayerState) = ModeToggle(
    if (ps.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
    ps.repeat != Player.REPEAT_MODE_OFF,
) { ps.cycleRepeat() }

/* ---------------- Sheets ---------------- */

/** Dark glass with a hint of the cover's colour. */
private fun sheetTint(colors: CoverColors, alpha: Float = 0.82f) = lerp(Color(0xFF0E0F13), colors.b, 0.22f).copy(alpha = alpha)

@Composable
private fun SheetFrame(fp: Backdrop, colors: CoverColors, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val side = desktopLayout()
    Box(
        modifier
            .then(if (side) Modifier.width(400.dp).padding(16.dp) else Modifier.fillMaxWidth().padding(10.dp).navigationBarsPadding())
            // On PC: clear liquid glass, so the player shows through.
            .liquidGlass(fp, if (side) 24.dp else 28.dp, if (side) sheetTint(colors, 0.14f) else sheetTint(colors), blur = if (side) 14.dp else 24.dp, depth = 12.dp, bend = 14.dp, rim = colors.a)
            .clickable(remember { MutableInteractionSource() }, null) { }
            .padding(18.dp),
    ) { content() }
}

/** "Up next": what plays after this song, with Autoplay, shuffle and repeat. */
@Composable
private fun QueueSheet(ps: PlayerState, fp: Backdrop, colors: CoverColors) {
    BoxWithConstraints {
        // PC: the panel runs the full height of the window.
        val maxH = if (desktopLayout()) maxHeight - 68.dp else maxHeight * 0.72f
        SheetFrame(fp, colors) {
            Column(Modifier.height(maxH)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GText("Up next", 22, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    ShuffleToggle(ps)
                    RepeatToggle(ps)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        GText("Autoplay", 15, weight = FontWeight.SemiBold)
                        GText("Keeps playing similar songs when the queue ends", 12, Dim, maxLines = 2)
                    }
                    Toggle(Settings.autoplay) { Settings.updateAutoplay(it) }
                }
                val upcoming = ps.upcoming()
                if (upcoming.isEmpty()) {
                    Centered(if (Settings.autoplay) "Nothing queued. Similar songs will be added when this one ends." else "Nothing queued after this song.")
                } else {
                    // Where Autoplay's songs start, so they get their own heading.
                    val firstAuto = upcoming.indexOfFirst { it.track.url in Autoplay.added }
                    LazyColumn(Modifier.weight(1f)) {
                        upcoming.forEachIndexed { pos, e ->
                            if (pos == firstAuto) item(key = "autoHead") {
                                Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(CoverTint.accent))
                                    Spacer(Modifier.width(8.dp))
                                    GText("AUTOPLAYING SIMILAR SONGS", 11, Faint, FontWeight.SemiBold)
                                }
                            }
                            item(key = "${e.index}|${e.track.url}") {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(0.98f) { ps.playAt(e.index) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(e.track.thumb, Modifier.size(44.dp), 8.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    GText(e.track.title, 15, maxLines = 1, weight = FontWeight.Medium)
                                    GText(e.track.artist, 12, Dim, maxLines = 1)
                                }
                                Box(Modifier.size(40.dp).pressable(0.85f) { ps.removeAt(e.index) }, contentAlignment = Alignment.Center) {
                                    Ico(Icons.Rounded.Close, Faint, 20.dp)
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The ••• menu: download, sleep timer, equalizer, speed and quality. */
@Composable
private fun MoreSheet(ps: PlayerState, fp: Backdrop, colors: CoverColors, openEqualizer: () -> Unit) {
    var showSleep by remember { mutableStateOf(false) }
    SheetFrame(fp, colors) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DownloadTile(ps)
                SheetTile(Icons.Rounded.Bedtime, "Sleep", SleepTimer.active) { showSleep = !showSleep }
                SheetTile(Icons.Rounded.Equalizer, "Equalizer", false, openEqualizer)
                // Automix: blend each song into the next. Off until you switch it on.
                SheetTile(Icons.Rounded.MergeType, "Automix", Settings.automix) {
                    Settings.updateAutomix(!Settings.automix)
                    Menus.say(if (Settings.automix) "Automix on: songs mix on the beat, like a DJ" else "Automix off")
                }
            }
            AnimatedVisibility(showSleep) {
                Column {
                    SheetLabel("Sleep timer")
                    ChoiceRow(
                        listOf("Off", "15 min", "30 min", "45 min", "1 hour", "End of song"),
                        when {
                            SleepTimer.endOfSong -> "End of song"
                            SleepTimer.endsAt > 0 -> ""
                            else -> "Off"
                        },
                    ) { pick ->
                        when (pick) {
                            "Off" -> SleepTimer.cancel()
                            "End of song" -> SleepTimer.startEndOfSong()
                            "1 hour" -> SleepTimer.start(60)
                            else -> SleepTimer.start(pick.substringBefore(' ').toInt())
                        }
                    }
                }
            }
        }
    }
}

private enum class DlState { IDLE, BUSY, DONE }

/** Download button for the song that's playing: a ring fills while it downloads, then a tick in the cover's colour pops in. */
@Composable
private fun RowScope.DownloadTile(ps: PlayerState) {
    val id = ps.currentId
    val pct = id?.let { Downloads.progress[it] }
    val state = when {
        pct != null -> DlState.BUSY
        id != null && Downloads.isDownloaded(id) -> DlState.DONE
        else -> DlState.IDLE
    }
    val bg by animateColorAsState(if (state == DlState.DONE) CoverTint.accent else Color.White.copy(alpha = 0.08f), smooth(250), label = "dlBg")
    val fg = if (state == DlState.DONE) Color.Black else Color.White
    Column(
        Modifier.weight(1f).pressable(0.95f) {
            val t = ps.currentTrack() ?: return@pressable
            when (state) {
                DlState.IDLE -> Downloads.download(t)
                DlState.DONE -> Downloads.remove(t.url)
                DlState.BUSY -> Unit
            }
        }.clip(RoundedCornerShape(18.dp)).background(bg).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            state,
            transitionSpec = { (fadeIn(smooth(180)) + androidx.compose.animation.scaleIn(bouncy(), 0.6f)) togetherWith fadeOut(smooth(120)) },
            label = "dlIcon",
            modifier = Modifier.size(26.dp),
        ) { s ->
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                when (s) {
                    DlState.IDLE -> Ico(Icons.Rounded.Download, fg, 24.dp)
                    DlState.BUSY -> {
                        DownloadRing(pct ?: 0f, 26.dp, CoverTint.accent, 2.5.dp)
                        Ico(Icons.Rounded.Download, Color.White.copy(alpha = 0.8f), 14.dp)
                    }
                    DlState.DONE -> Ico(Icons.Rounded.DownloadDone, Color.Black, 24.dp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        GText(
            when (state) {
                DlState.IDLE -> "Download"
                DlState.BUSY -> "${((pct ?: 0f) * 100).toInt()}%"
                DlState.DONE -> "Downloaded"
            },
            11, fg, FontWeight.SemiBold, 1,
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    GText(text.uppercase(), 12, Faint, FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun RowScope.SheetTile(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f), smooth(150), label = "tile")
    val fg by animateColorAsState(if (on) Color.Black else Color.White, smooth(150), label = "tileFg")
    Column(
        Modifier.weight(1f).pressable(0.95f, onClick = onClick).clip(RoundedCornerShape(18.dp)).background(bg).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ico(icon, fg, 24.dp)
        Spacer(Modifier.height(6.dp))
        GText(label, 11, fg, FontWeight.SemiBold, 1)
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selected: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o ->
            val on = o == selected
            Box(
                Modifier.pressable(0.95f) { onPick(o) }.clip(CircleShape)
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { GText(o, 13, if (on) Color.Black else Color.White, FontWeight.SemiBold, 1) }
        }
    }
}

/* ---------------- Progress bar ---------------- */

/** Apple-style progress bar: thin, no knob, gets thicker while you drag it. */
@Composable
fun AppleSeekBar(ps: PlayerState) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = ps.durationMs
    val thick by animateDpAsState(if (drag != null) 12.dp else 7.dp, smooth(120), label = "thick")
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(dur) {
                    detectTapGestures { o -> if (dur > 0) ps.seekTo((o.x / size.width * dur).toLong()) }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag?.let { if (dur > 0) ps.seekTo((it * dur).toLong()) }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            drag = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                }
                .drawBehind {
                    val frac = drag ?: if (dur > 0) (ps.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val h = thick.toPx()
                    val top = (size.height - h) / 2
                    drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                    drawRoundRect(
                        Color.White.copy(alpha = if (drag != null) 1f else 0.85f),
                        Offset(0f, top), Size(size.width * frac, h), CornerRadius(h / 2),
                    )
                },
        )
        Times(ps, drag, dur)
    }
}

@Composable
private fun Times(ps: PlayerState, drag: Float?, dur: Long) {
    val shownMs = drag?.let { (it * dur).toLong() } ?: ps.positionMs
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        GText(fmtTime(shownMs / 1000), 12, Color.White.copy(alpha = 0.55f), FontWeight.Medium)
        GText("-" + fmtTime((dur - shownMs).coerceAtLeast(0) / 1000), 12, Color.White.copy(alpha = 0.55f), FontWeight.Medium)
    }
}


/** While Flow is on: which segment is playing, and "Switch it up" to jump to the next one. */
@Composable
private fun FlowBar(ps: PlayerState) {
    val seg = Flow.now ?: return
    val accent = CoverTint.accent
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                GText("FLOW · " + seg.title.uppercase(), 11, accent, FontWeight.Bold, 1)
            }
            GText(seg.blurb, 12, Color.White.copy(alpha = 0.6f), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.pressable(0.92f) { Flow.switchUp(ps) }.clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(androidx.compose.material.icons.Icons.Rounded.AutoAwesome, accent, 15.dp)
            Spacer(Modifier.width(6.dp))
            GText("Switch it up", 12, Color.White, FontWeight.SemiBold, 1)
        }
    }
}