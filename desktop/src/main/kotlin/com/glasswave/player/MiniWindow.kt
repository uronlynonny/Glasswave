// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import coil3.compose.AsyncImage
import java.awt.Cursor
import java.awt.MouseInfo

/** The mini player: a small window that floats over your other apps (button in the player bar). */
object Mini {
    var open by mutableStateOf(false)
    fun toggle() { open = !open }
}

/** Space around the glass inside the window, so its shadow has room (the window itself is see-through). */
private val Margin = 14.dp

/**
 * The mini player window. Drag it anywhere, resize it from any edge or corner, and it
 * changes layout with its size: a slim strip, or a tall card with the cover on top. How see-through,
 * how deep and how round it is, and its style, are under Settings > Look > Mini player.
 */
@Composable
fun MiniPlayerWindow(ps: PlayerState, icon: Painter) {
    if (!Mini.open) return
    val saved = remember { Settings.miniBounds?.takeIf { onSomeScreen(it[0], it[1]) } }
    val state = rememberWindowState(
        position = if (saved != null) WindowPosition(saved[0].dp, saved[1].dp) else WindowPosition(Alignment.BottomEnd),
        size = DpSize((saved?.get(2) ?: 330).dp, (saved?.get(3) ?: 118).dp),
    )
    Window(
        onCloseRequest = { Mini.open = false },
        state = state,
        title = "Glasswave mini player",
        icon = icon,
        undecorated = true,
        transparent = true,
        resizable = true,
        alwaysOnTop = Settings.miniOnTop,
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when (e.key) {
                Key.Spacebar -> { ps.toggle(); true }
                Key.Escape -> { Mini.open = false; true }
                else -> false
            }
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(220, 96) }
        // Remember where it is and how big, a moment after it stops moving.
        LaunchedEffect(state.position, state.size) {
            kotlinx.coroutines.delay(500)
            val p = state.position
            if (p is WindowPosition.Absolute) {
                Settings.updateMiniBounds(p.x.value.toInt(), p.y.value.toInt(), state.size.width.value.toInt(), state.size.height.value.toInt())
            }
        }
        MiniCard(ps, window)
    }
}

private fun onSomeScreen(x: Int, y: Int): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.contains(x + 40, y + 40) }
}.getOrDefault(false)

/** A song picture that always fills its box: YouTube's 4:3 video stills have black bars built in, so those are zoomed past them. */
@Composable
private fun MiniArt(url: String?, modifier: Modifier, corner: Dp) {
    val barred = url != null && "ytimg.com/vi" in url && ("hqdefault" in url || "sddefault" in url || "/0.jpg" in url)
    Box(modifier.clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        Ico(Icons.Rounded.MusicNote, Color.White.copy(alpha = 0.4f), 22.dp)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { if (barred) { scaleX = 1.34f; scaleY = 1.34f } },
            )
        }
    }
}

@Composable
internal fun MiniCard(ps: PlayerState, window: java.awt.Window) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val depth = Settings.miniDepth
    val radius = (4f + 26f * Settings.miniCorners).dp
    val shape = RoundedCornerShape(radius)
    val art = hiRes(ps.art)
    val colors = rememberCoverColors(art)
    Box(Modifier.fillMaxSize().padding(Margin)) {
        Box(
            Modifier
                .fillMaxSize()
                .hoverable(hover)
                .shadow((1f + 13f * depth).dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .moveWindow(window),
        ) {
            // The back: the song's colour, its blurred cover, or dark glass, as see-through as you set it.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = Settings.miniOpacity }) {
                when (Settings.miniStyle) {
                    MiniStyle.COLOUR -> Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(lerp(colors.a, Color.Black, 0.12f), lerp(colors.b, Color.Black, 0.05f))),
                        ),
                    )

                    MiniStyle.BLUR -> {
                        Box(Modifier.fillMaxSize().background(colors.base))
                        MiniArt(art, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }.blur(28.dp), 0.dp)
                        Box(Modifier.fillMaxSize().background(Color(0x73000000)))
                    }
                    MiniStyle.DARK -> Box(Modifier.fillMaxSize().background(Color(0xFF101114)))
                }
            }
            // Depth: a shine across the top and a bright rim, stronger the deeper you set it.
            if (depth > 0.02f) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f * depth), 0.45f to Color.Transparent)))
            }
            Box(
                Modifier.fillMaxSize().border(
                    (0.6f + 1.2f * depth).dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f + 0.34f * depth), Color.White.copy(alpha = 0.02f + 0.08f * depth))),
                    shape,
                ),
            )

            MiniLayout(ps, art, radius)

            // Open Glasswave and close, shown while the mouse is over it.
            AnimatedVisibility(hovered, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton(Icons.Rounded.OpenInFull, 13.dp) { SingleInstance.showMain() }
                    SmallButton(Icons.Rounded.Close, 15.dp) { Mini.open = false }
                }
            }
            // Resize from any edge or corner.
            ResizeEdges(window)
        }
    }
}

/**
 * The song and its buttons, laid out for the window's size. Short: the cover on the left, the song,
 * and play (plus previous and next when there's room). Tall: the cover fills the top edge to edge.
 */
@Composable
private fun MiniLayout(ps: PlayerState, art: String?, radius: Dp) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tall = maxHeight >= 220.dp && maxWidth >= 180.dp
        val skips = maxWidth >= 330.dp
        val tallSkips = maxWidth >= 220.dp
        val title = ps.title.ifBlank { "Nothing playing" }
        val artist = if (ps.buffering) "Loading…" else ps.artist
        if (tall) {
            Column(Modifier.fillMaxSize()) {
                // The cover, edge to edge across the top.
                MiniArt(art, Modifier.fillMaxWidth().weight(1f), 0.dp)
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            GText(title, 16, Color.White, FontWeight.Bold, 1)
                            GText(artist, 13, Color.White.copy(alpha = 0.72f), maxLines = 1)
                        }
                        LikeButton(ps)
                    }
                    if (Settings.miniProgress) Progress(ps, Modifier.fillMaxWidth().padding(top = 2.dp, end = 4.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Controls(ps, size = 44.dp, skips = tallSkips) }
                }
            }
        } else {
            // Like the small one: cover, the song, and play. It grows with the window.
            val pad = (maxHeight * 0.1f).coerceIn(6.dp, 14.dp)
            val artSide = maxHeight - pad * 2
            val play = (maxHeight * 0.42f).coerceIn(32.dp, 52.dp)
            val big = maxHeight >= 120.dp
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                MiniArt(art, Modifier.size(artSide), (radius - pad).coerceAtLeast(6.dp))
                Spacer(Modifier.width(pad + 4.dp))
                Column(Modifier.weight(1f)) {
                    GText(title, if (big) 16 else 14, Color.White, FontWeight.Bold, 1)
                    GText(artist, if (big) 13 else 12, Color.White.copy(alpha = 0.72f), maxLines = 1)
                    if (Settings.miniProgress) Progress(ps, Modifier.fillMaxWidth().padding(end = 6.dp))
                }
                Spacer(Modifier.width(6.dp))
                Controls(ps, size = play, skips = skips)
            }
        }
    }
}

@Composable
private fun Controls(ps: PlayerState, size: Dp, skips: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (skips) MiniButton(Icons.Rounded.SkipPrevious, size * 0.55f) { ps.previous() }
        Box(
            Modifier.size(size).clip(RoundedCornerShape(50)).background(Color.White).pressable(0.9f) { ps.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Ico(if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Color(0xFF111111), size * 0.6f)
        }
        if (skips) MiniButton(Icons.Rounded.SkipNext, size * 0.55f) { ps.next() }
    }
}

@Composable
private fun LikeButton(ps: PlayerState) {
    val t = ps.currentTrack() ?: return
    val liked = Library.isLiked(t.url)
    MiniButton(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, 20.dp, if (liked) Color.White else Color.White.copy(alpha = 0.8f)) {
        Library.toggleLike(t)
    }
}

@Composable
private fun Progress(ps: PlayerState, modifier: Modifier) {
    SeekLine(
        fraction = if (ps.durationMs > 0) ps.positionMs.toFloat() / ps.durationMs else 0f,
        color = Color.White,
        modifier = modifier,
    ) { f -> if (ps.durationMs > 0) ps.seekTo((f * ps.durationMs).toLong()) }
}

@Composable
private fun MiniButton(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp, color: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(size + 12.dp).pressable(0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Ico(icon, color, size)
    }
}

@Composable
private fun SmallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(Color(0x73000000)).pressable(0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Ico(icon, Color.White, size) }
}

/** Drag the glass to move the window (buttons and the progress bar keep their own drags). */
private fun Modifier.moveWindow(window: java.awt.Window): Modifier = pointerInput(window) {
    var grab = java.awt.Point()
    var from = java.awt.Point()
    detectDragGestures(
        onDragStart = {
            grab = MouseInfo.getPointerInfo().location
            from = window.location
        },
        onDrag = { change, _ ->
            change.consume()
            val now = MouseInfo.getPointerInfo().location
            window.setLocation(from.x + now.x - grab.x, from.y + now.y - grab.y)
        },
    )
}

/** Thin strips along every edge and corner you can drag to resize, with the matching arrow cursor. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ResizeEdges(window: java.awt.Window) {
    val e = 7.dp
    val c = 16.dp
    Edge(window, Modifier.align(Alignment.CenterStart).width(e).fillMaxHeight(), -1, 0, Cursor.W_RESIZE_CURSOR)
    Edge(window, Modifier.align(Alignment.CenterEnd).width(e).fillMaxHeight(), 1, 0, Cursor.E_RESIZE_CURSOR)
    Edge(window, Modifier.align(Alignment.TopCenter).height(e).fillMaxWidth(), 0, -1, Cursor.N_RESIZE_CURSOR)
    Edge(window, Modifier.align(Alignment.BottomCenter).height(e).fillMaxWidth(), 0, 1, Cursor.S_RESIZE_CURSOR)
    Edge(window, Modifier.align(Alignment.TopStart).size(c), -1, -1, Cursor.NW_RESIZE_CURSOR)
    Edge(window, Modifier.align(Alignment.TopEnd).size(c), 1, -1, Cursor.NE_RESIZE_CURSOR)
    Edge(window, Modifier.align(Alignment.BottomStart).size(c), -1, 1, Cursor.SW_RESIZE_CURSOR)
    Edge(window, Modifier.align(Alignment.BottomEnd).size(c), 1, 1, Cursor.SE_RESIZE_CURSOR)
}

/** One resize strip: [dx]/[dy] say which edges it moves (-1 left/top, 1 right/bottom, 0 neither). */
@Composable
private fun Edge(window: java.awt.Window, modifier: Modifier, dx: Int, dy: Int, cursor: Int) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(window) {
                var grab = java.awt.Point()
                var from = java.awt.Rectangle()
                detectDragGestures(
                    onDragStart = {
                        grab = MouseInfo.getPointerInfo().location
                        from = window.bounds
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val now = MouseInfo.getPointerInfo().location
                        val mx = now.x - grab.x
                        val my = now.y - grab.y
                        val min = window.minimumSize
                        var x = from.x
                        var y = from.y
                        var w = from.width
                        var h = from.height
                        if (dx > 0) w = (from.width + mx).coerceIn(min.width, 1000)
                        if (dx < 0) { w = (from.width - mx).coerceIn(min.width, 1000); x = from.x + from.width - w }
                        if (dy > 0) h = (from.height + my).coerceIn(min.height, 1000)
                        if (dy < 0) { h = (from.height - my).coerceIn(min.height, 1000); y = from.y + from.height - h }
                        window.setBounds(x, y, w, h)
                    },
                )
            },
    )
}
