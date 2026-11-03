// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
@file:OptIn(ExperimentalFoundationApi::class)

package com.glasswave.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory

/* ---------------- Look ---------------- */

private val Lime = Color(0xFFC6F432)
private val Ink = Color(0xFF0E0E10)
private val Soft = Color.White.copy(alpha = 0.62f)

private fun Modifier.glass(shape: Shape, fill: Float = 0.09f): Modifier = this
    .clip(shape)
    .background(Color.White.copy(alpha = fill))
    .border(0.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f))), shape)

@Composable
private fun Label(
    text: String,
    size: TextUnit = 15.sp,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    align: TextAlign = TextAlign.Start,
) = BasicText(
    text,
    modifier,
    style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align),
    maxLines = lines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
private fun IconButton(icon: ImageVector, size: Dp = 26.dp, tint: Color = Color.White, box: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(box).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, null, Modifier.size(size), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier, corner: Dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        Image(Icons.Rounded.MusicNote, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Soft))
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/* ---------------- The app ---------------- */

@Composable
fun App(app: AppState) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build()
    }
    val p = app.player
    val colors = rememberCoverColors(p.current?.thumb)
    Box(
        Modifier.fillMaxSize().background(Ink).background(
            Brush.verticalGradient(0f to colors.a.copy(alpha = 0.35f), 0.45f to colors.b.copy(alpha = 0.6f), 1f to Ink),
        ),
    ) {
        SearchScreen(app)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(p.notice != null, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Label(p.notice ?: "", 13.sp, modifier = Modifier.glass(RoundedCornerShape(50), 0.16f).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            AnimatedVisibility(p.current != null, enter = slideInVertically { it } + fadeIn(), exit = fadeOut()) {
                MiniBar(app)
            }
        }
        AnimatedVisibility(
            app.fullOpen && p.current != null,
            enter = slideInVertically(tween(380)) { it },
            exit = slideOutVertically(tween(320)) { it },
        ) {
            FullPlayer(app, colors)
        }
    }
}

/* ---------------- Search ---------------- */

private val quickPicks = listOf("Afrobeats", "Amapiano", "Hip-hop", "R&B", "Pop", "Gospel", "Alté", "Dancehall")

@Composable
private fun SearchScreen(app: AppState) {
    val focus = LocalFocusManager.current
    val p = app.player
    var focused by remember { mutableStateOf(false) }
    fun go(q: String) {
        focus.clearFocus()
        app.search(q)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Glasswave", 30.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Lime))
        }
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).glass(RoundedCornerShape(24.dp), 0.1f).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(Icons.Rounded.Search, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Soft))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (app.query.isEmpty()) Label("Songs, artists, lyrics", 16.sp, Soft)
                BasicTextField(
                    value = app.query,
                    onValueChange = { app.typed(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Lime),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go(app.query) }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            if (app.query.isNotEmpty()) IconButton(Icons.Rounded.Close, 18.dp, Soft, 32.dp) { app.clearSearch() }
        }
        Spacer(Modifier.height(10.dp))
        val bottomRoom = if (p.current != null) 96.dp else 24.dp
        when {
            focused && app.suggestions.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = bottomRoom)) {
                itemsIndexed(app.suggestions) { _, s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { go(s) }.padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(Icons.Rounded.Search, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Soft))
                        Spacer(Modifier.width(14.dp))
                        Label(s, 16.sp)
                    }
                }
            }
            app.searching && app.results.isEmpty() -> Message("Searching…")
            app.searchError != null && app.results.isEmpty() -> Message(app.searchError ?: "")
            app.results.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = bottomRoom)) {
                itemsIndexed(app.results, key = { _, t -> t.videoId }) { _, t ->
                    TrackRow(t, playing = p.current?.videoId == t.videoId) {
                        focus.clearFocus()
                        p.playList(listOf(t), 0)
                    }
                }
            }
            app.searched.isNotEmpty() -> Message("Nothing found for \"${app.searched}\"")
            else -> QuickPicks { go(it) }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp), contentAlignment = Alignment.Center) {
        Label(text, 15.sp, Soft, lines = 3, align = TextAlign.Center)
    }
}

@Composable
private fun QuickPicks(pick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Label("Start with", 13.sp, Soft, FontWeight.Medium, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp))
        quickPicks.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { g ->
                    Box(
                        Modifier.weight(1f).height(64.dp).glass(RoundedCornerShape(16.dp), 0.08f).clickable { pick("$g hits") }.padding(14.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Label(g, 16.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(t: Track, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t.thumb, Modifier.size(54.dp), 8.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Label(t.title, 16.sp, if (playing) Lime else Color.White, FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.explicit) {
                    Box(Modifier.size(15.dp).clip(RoundedCornerShape(3.dp)).background(Soft), contentAlignment = Alignment.Center) {
                        Label("E", 10.sp, Ink, FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Label(listOf(t.artist, if (t.seconds > 0) fmtTime(t.seconds) else "").filter { it.isNotEmpty() }.joinToString(" • "), 13.sp, Soft)
            }
        }
    }
}

/* ---------------- Mini player ---------------- */

@Composable
private fun MiniBar(app: AppState) {
    val p = app.player
    val t = p.current ?: return
    Box(Modifier.navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(20.dp)).glass(RoundedCornerShape(20.dp), 0.14f)
                .background(Ink.copy(alpha = 0.55f))
                .clickable { app.fullOpen = true }
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -12) app.fullOpen = true } },
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(t.thumb, Modifier.size(46.dp), 10.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Label(t.title, 15.sp, weight = FontWeight.SemiBold)
                    Label(if (p.loading) "Loading…" else t.artist, 13.sp, Soft)
                }
                IconButton(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 30.dp) { p.toggle() }
                IconButton(Icons.Rounded.SkipNext, 28.dp) { p.next() }
            }
            val frac = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/* ---------------- Full player ---------------- */

@Composable
private fun FullPlayer(app: AppState, colors: CoverColors) {
    val p = app.player
    val t = p.current ?: return
    var drag by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(t.videoId, app.showLyrics) { if (app.showLyrics) app.loadLyrics(t) }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = drag.coerceAtLeast(0f) }
            .background(Brush.verticalGradient(0f to colors.a, 0.55f to colors.b, 1f to Ink))
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag > 160f) app.fullOpen = false
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, dy -> drag += dy }
            },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.Rounded.KeyboardArrowDown, 32.dp) { app.fullOpen = false }
                Label(
                    when {
                        app.showLyrics -> "Lyrics"
                        app.showQueue -> "Up next"
                        else -> "Now playing"
                    },
                    14.sp, Soft, FontWeight.Medium, Modifier.weight(1f), align = TextAlign.Center,
                )
                Spacer(Modifier.width(44.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                when {
                    app.showLyrics -> LyricsPanel(app)
                    app.showQueue -> QueuePanel(app)
                    else -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, maxHeight)
                        Cover(hiRes(t.thumb), Modifier.size(side).shadow(30.dp, RoundedCornerShape(18.dp)), 18.dp)
                    }
                }
            }
            Label(t.title, 24.sp, weight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().basicMarquee())
            Spacer(Modifier.height(4.dp))
            Label(t.artist, 17.sp, Soft)
            Spacer(Modifier.height(18.dp))
            SeekBar(p)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.Rounded.SkipPrevious, 40.dp, box = 64.dp) { p.previous() }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable { p.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(42.dp), colorFilter = ColorFilter.tint(Ink))
                }
                IconButton(Icons.Rounded.SkipNext, 40.dp, box = 64.dp) { p.next() }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(Icons.Rounded.Lyrics, 24.dp, if (app.showLyrics) Lime else Soft) {
                    app.showLyrics = !app.showLyrics
                    app.showQueue = false
                }
                IconButton(Icons.AutoMirrored.Rounded.QueueMusic, 24.dp, if (app.showQueue) Lime else Soft) {
                    app.showQueue = !app.showQueue
                    app.showLyrics = false
                }
            }
        }
    }
}

@Composable
private fun SeekBar(p: Player) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = p.durationMs.coerceAtLeast(1)
    val frac = dragging ?: (p.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Column {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(dur) {
                    detectTapGestures { o -> p.seekTo((o.x / size.width * dur).toLong()) }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragging?.let { p.seekTo((it * dur).toLong()) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = if (dragging != null) 8.dp else 4.dp
            Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Label(fmtTime((frac * dur / 1000).toLong()), 12.sp, Soft)
            Spacer(Modifier.weight(1f))
            Label(fmtTime(dur / 1000), 12.sp, Soft)
        }
    }
}

@Composable
private fun LyricsPanel(app: AppState) {
    val p = app.player
    val lines = app.lyrics
    if (lines == null) {
        Label(if (app.lyricsFor == p.current?.videoId) "No timed lyrics for this song" else "Looking for lyrics…", 16.sp, Soft, align = TextAlign.Center)
        return
    }
    val now = lines.indexOfLast { it.ms <= p.positionMs + 250 }.coerceAtLeast(0)
    val list = rememberLazyListState()
    LaunchedEffect(now) { list.animateScrollToItem((now - 2).coerceAtLeast(0)) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 80.dp)) {
        itemsIndexed(lines) { i, line ->
            BasicText(
                line.text.ifEmpty { "♪" },
                Modifier.fillMaxWidth().clickable { p.seekTo(line.ms) }.padding(vertical = 9.dp),
                style = TextStyle(
                    color = if (i == now) Color.White else Color.White.copy(alpha = if (i < now) 0.38f else 0.5f),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 32.sp,
                ),
            )
        }
    }
}

@Composable
private fun QueuePanel(app: AppState) {
    val p = app.player
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(p.index.coerceAtLeast(0)) }
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        itemsIndexed(p.queue, key = { i, t -> "$i-${t.videoId}" }) { i, t ->
            Box(Modifier.fillMaxWidth().graphicsLayer { alpha = if (i < p.index) 0.45f else 1f }) {
                TrackRow(t, playing = i == p.index) { p.jumpTo(i) }
            }
        }
    }
}
