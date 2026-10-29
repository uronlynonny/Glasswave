// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.scale

/**
 * Click that shrinks the element a touch while pressed.
 * Built as a lightweight "indication" node: no extra state or recomposition per row, and the press
 * animation only redraws. This matters in long lists, where every row has one.
 */
fun Modifier.pressable(scale: Float = 0.94f, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = PressScale(maxOf(scale, 0.95f)), enabled = enabled, onClick = onClick)

private data class PressScale(val scale: Float) : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        PressScaleNode(interactionSource, scale)
}

private class PressScaleNode(
    private val source: androidx.compose.foundation.interaction.InteractionSource,
    private val target: Float,
) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
    private val s = androidx.compose.animation.core.Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press ->
                        launch { if (Settings.animations) s.animateTo(target, tween(110)) else s.snapTo(target) }
                    is androidx.compose.foundation.interaction.PressInteraction.Release,
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel ->
                        launch { if (Settings.animations) s.animateTo(1f, tween(160)) else s.snapTo(1f) }
                }
            }
        }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val v = s.value
        if (v == 1f) {
            drawContent()
        } else {
            scale(v) { this@draw.drawContent() }
        }
    }
}

@Composable
fun Centered(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.text.BasicText(
            text,
            style = TextStyle(color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center),
        )
    }
}

@Composable
fun ErrorBox(message: String, backdrop: Backdrop, onPlaySaved: (() -> Unit)? = null, onRetry: () -> Unit) {
    if (message.startsWith("No internet")) return OfflineBox(backdrop, onPlaySaved, onRetry)
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GText(message, 15, Dim)
        Spacer(Modifier.height(14.dp))
        GlassButton("Try again", backdrop, onClick = onRetry)
    }
}

@Composable
fun Header(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GText(title, 34, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    GText(text, 21, weight = FontWeight.Bold, maxLines = 1, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp))
}

/** A round liquid glass button. */
@Composable
fun GlassCircle(
    size: Dp,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.06f),
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .pressable(0.88f, onClick = onClick)
            .size(size)
            .liquidGlass(backdrop, size / 2, tint, depth = size / 4, bend = size / 4),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A pill-shaped liquid glass button with text. */
@Composable
fun GlassButton(
    text: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .pressable(onClick = onClick)
            .height(48.dp)
            .liquidGlass(
                backdrop, 24.dp,
                tint = if (accent) Accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f),
                depth = 12.dp, bend = 14.dp,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Ico(icon, Color.White, 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        GText(text, 15, Color.White, FontWeight.SemiBold, 1)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, backdrop: Backdrop, onClick: () -> Unit) {
    val tint by animateColorAsState(
        // Frosted glass: unselected chips are just the frosted pill.
        if (selected) Color.White.copy(alpha = 0.92f) else if (Settings.cleanLook) Color.Transparent else Color.White.copy(alpha = 0.05f),
        smooth(220), label = "chip",
    )
    val textColor by animateColorAsState(if (selected) Color.Black else Color.White, smooth(220), label = "chipText")
    Box(
        Modifier
            .pressable(onClick = onClick)
            .height(36.dp)
            .liquidGlass(backdrop, 18.dp, tint = tint, depth = 8.dp, bend = 8.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        GText(text, 14, textColor, FontWeight.SemiBold, 1)
    }
}

@Composable
fun Cover(url: String?, modifier: Modifier, corner: Dp, circle: Boolean = false, fallback: ImageVector = Icons.Rounded.MusicNote) {
    val shape: Shape = if (circle) CircleShape else RoundedCornerShape(corner)
    Box(modifier.clip(shape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        Ico(fallback, Faint, 26.dp)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Three bouncing bars, shown next to the song that's playing. */
@Composable
fun EqualizerBars(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 180, 360).map { delay ->
        t.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(520, delayMillis = delay, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$delay",
        )
    }
    Box(
        modifier.size(18.dp).drawBehind {
            val bw = size.width / 5
            phases.forEachIndexed { i, p ->
                val f = if (playing && Settings.animations) p.value else listOf(0.5f, 0.8f, 0.4f)[i]
                val h = size.height * f
                drawRoundRect(color, Offset(bw * (i * 2), size.height - h), Size(bw, h), CornerRadius(bw / 2))
            }
        },
    )
}

/** On a playlist's cover while that playlist plays: bouncing bars on a small dark disc (they rest while paused). */
@Composable
fun PlayingBadge(playing: Boolean, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { EqualizerBars(readable(CoverTint.accent), playing, Modifier.size(size * 0.5f)) }
}

/** A ring that fills as a download goes, smoothly. */
@Composable
fun DownloadRing(progress: Float, size: Dp, color: Color = Color.White, stroke: Dp = 2.dp, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress, tween(350), label = "ring")
    Box(
        modifier.size(size).drawBehind {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(color.copy(alpha = 0.22f), 0f, 360f, false, Offset(inset, inset), arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawArc(
                color, -90f, 360f * p.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        },
    )
}

/** Small status next to a song: a filling ring while it downloads, a tick once it's on the phone. */
@Composable
fun DownloadMark(url: String) {
    val p = Downloads.progress[url]
    when {
        p != null -> {
            DownloadRing(p, 13.dp, CoverTint.accent, 2.dp)
            Spacer(Modifier.width(5.dp))
        }
        Downloads.isDownloaded(url) -> {
            Box(Modifier.size(14.dp).clip(CircleShape).background(CoverTint.accent), contentAlignment = Alignment.Center) {
                Ico(androidx.compose.material.icons.Icons.Rounded.Check, Color.Black, 11.dp)
            }
            Spacer(Modifier.width(5.dp))
        }
    }
}

/**
 * A song in a list. The ⋮ on the right opens the song menu (like Spotify's).
 * [playlistId] is set when the list is one of your playlists, so the menu can remove the song from it.
 */
@Composable
fun TrackRow(
    t: Track,
    current: Boolean,
    playing: Boolean,
    number: Int? = null,
    playlistId: String? = null,
    menu: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressable(0.97f, onClick = onClick)
            .background(if (current) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .padding(start = 10.dp, end = if (menu) 0.dp else 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                if (current) EqualizerBars(Accent, playing) else GText("$number", 15, Faint)
            }
        } else {
            Cover(t.thumb, Modifier.size(50.dp), 8.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            GText(t.title, 16, if (current) Accent else Color.White, FontWeight.Medium, 1)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadMark(t.url)
                GText(t.artist, 13, Dim, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            current && number == null -> EqualizerBars(Accent, playing)
            t.seconds > 0 -> GText(fmtTime(t.seconds), 12, Faint)
        }
        if (menu) {
            Box(Modifier.size(40.dp).pressable(0.85f) { Menus.openSong(t, playlistId) }, contentAlignment = Alignment.Center) {
                Ico(androidx.compose.material.icons.Icons.Rounded.MoreVert, Dim, 22.dp)
            }
        }
    }
}

/**
 * Cards with album shapes instead of plain squares: albums are a sleeve with the record peeking
 * out, playlists and mixes a little stack of sleeves, artists a circle.
 */
@Composable
fun ShapeCard(item: MusicItem, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).pressable(onClick = onClick)) {
        when (item) {
            is AlbumItem -> Box(Modifier.fillMaxWidth().aspectRatio(1.12f)) {
                val sleeve = width / 1.12f
                // The record, sliding out of the right side of the sleeve.
                Box(
                    Modifier.align(Alignment.CenterEnd).size(sleeve * 0.94f).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFF2A2A2A), Color(0xFF0B0B0B))))
                        .drawBehind {
                            val r = size.minDimension / 2
                            for (k in 1..5) drawCircle(Color.White.copy(alpha = 0.05f), r * (0.45f + k * 0.1f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Cover(item.thumb, Modifier.size(sleeve * 0.3f), 0.dp, circle = true, fallback = item.fallbackIcon())
                }
                Cover(item.thumb, Modifier.align(Alignment.CenterStart).size(sleeve), 10.dp, fallback = item.fallbackIcon())
            }
            is PlaylistItem -> Box(Modifier.fillMaxWidth().aspectRatio(0.92f)) {
                // Two sleeves peeking out behind the cover.
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth(0.78f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 6.dp).fillMaxWidth(0.89f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.14f)),
                )
                Cover(item.thumb, Modifier.align(Alignment.BottomCenter).fillMaxWidth().aspectRatio(1f), 12.dp, fallback = item.fallbackIcon())
            }
            else -> Cover(item.thumb, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp, circle = item is ArtistItem, fallback = item.fallbackIcon())
        }
        Spacer(Modifier.height(8.dp))
        GText(item.title, 14, Color.White, FontWeight.Medium, 1, if (item is ArtistItem) Modifier.align(Alignment.CenterHorizontally) else Modifier)
        if (item !is ArtistItem) GText(item.subtitle, 12, Dim, maxLines = 1)
    }
}

private fun MusicItem.kindLabel() = when (this) {
    is SongItem -> "Song"
    is AlbumItem -> "Album"
    is ArtistItem -> "Artist"
    is PlaylistItem -> "Playlist"
    is CategoryItem -> "Mood & genre"
}

private fun MusicItem.fallbackIcon() = when (this) {
    is SongItem -> Icons.Rounded.MusicNote
    is AlbumItem -> Icons.Rounded.Album
    is ArtistItem -> Icons.Rounded.Person
    is PlaylistItem -> Icons.Rounded.QueueMusic
    is CategoryItem -> Icons.Rounded.MusicNote
}

/** A list row for an album, artist or playlist. */
@Composable
fun ItemRow(item: MusicItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressable(0.97f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.thumb, Modifier.size(56.dp), 8.dp, circle = item is ArtistItem, fallback = item.fallbackIcon())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(item.title, 16, Color.White, FontWeight.Medium, 1)
            Spacer(Modifier.height(2.dp))
            GText(item.subtitle.ifEmpty { item.kindLabel() }, 13, Dim, maxLines = 1)
        }
    }
}

/** A square card for home and artist carousels. Artists get a round picture. */
@Composable
fun ItemCard(item: MusicItem, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).pressable(onClick = onClick)) {
        Cover(item.thumb, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp, circle = item is ArtistItem, fallback = item.fallbackIcon())
        Spacer(Modifier.height(8.dp))
        GText(item.title, 14, Color.White, FontWeight.Medium, 1, if (item is ArtistItem) Modifier.align(Alignment.CenterHorizontally) else Modifier)
        if (item !is ArtistItem) GText(item.subtitle, 12, Dim, maxLines = 1)
    }
}

/** A grey block with a light sweep moving across it, shown while things load. */
@Composable
fun Shimmer(modifier: Modifier, corner: Dp = 12.dp) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    Box(
        modifier.clip(RoundedCornerShape(corner)).drawBehind {
            drawRect(Color.White.copy(alpha = 0.07f))
            val sx = if (Settings.animations) x else 0.5f
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                    start = Offset(size.width * sx - size.width * 0.5f, 0f),
                    end = Offset(size.width * sx + size.width * 0.5f, size.height),
                ),
            )
        },
    )
}

/**
 * Pull down past the top to refresh. A glass bubble with a turning arrow follows your finger,
 * and spins while refreshing.
 */
@Composable
fun PullRefresh(refreshing: Boolean, onRefresh: () -> Unit, backdrop: Backdrop, content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val trigger = with(density) { 96.dp.toPx() }
    var pull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val refresh by androidx.compose.runtime.rememberUpdatedState(onRefresh)
    val conn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (available.y < 0 && pull > 0) {
                    val used = maxOf(available.y, -pull)
                    pull += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0) {
                    pull = (pull + available.y * 0.5f).coerceAtMost(trigger * 1.6f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val was = pull
                if (was >= trigger) refresh()
                pull = 0f
                return if (was > 0) available else androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    val target = if (refreshing) trigger * 0.75f else pull
    val y by animateFloatAsState(target, if (pull > 0 && !refreshing) androidx.compose.animation.core.snap() else bouncy(), label = "pull")
    val spin = rememberInfiniteTransition(label = "pullSpin")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn")
    Box(Modifier.fillMaxSize().nestedScroll(conn)) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = y * 0.45f }) { content() }
        if (y > 1f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = y - 44.dp.toPx()
                        val s = (y / trigger).coerceIn(0.3f, 1f)
                        scaleX = s
                        scaleY = s
                        alpha = (y / trigger).coerceIn(0f, 1f)
                    }
                    .size(44.dp)
                    .liquidGlass(backdrop, 22.dp, depth = 10.dp, bend = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Ico(
                    androidx.compose.material.icons.Icons.Rounded.Refresh, Color.White, 22.dp,
                    Modifier.graphicsLayer { rotationZ = if (refreshing) turn else (y / trigger) * 270f },
                )
            }
        }
    }
}

/** A glass on/off switch with a sliding knob. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val knob by animateDpAsState(if (on) 22.dp else 2.dp, bouncy(Spring.StiffnessMedium), label = "knob")
    val clean = Settings.cleanLook
    // Frosted glass is black and white: a white track with a dark knob when on, see-through grey when off.
    val track by animateColorAsState(
        when {
            clean -> if (on) Color.White else Color.White.copy(alpha = 0.18f)
            on -> Color(0xFF34C759)
            else -> Color.White.copy(alpha = 0.16f)
        },
        smooth(220), label = "track",
    )
    val knobColor = if (clean) Color(0xFF111113) else Color.White
    Box(
        Modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(remember { MutableInteractionSource() }, null) { onChange(!on) },
    ) {
        Box(Modifier.offset(x = knob, y = 2.dp).size(26.dp).clip(CircleShape).background(knobColor))
    }
}

/** On PC this keeps keyboard shortcuts out of a text box; the phone has none, so it does nothing. */
fun androidx.compose.ui.Modifier.typing(): androidx.compose.ui.Modifier = this
