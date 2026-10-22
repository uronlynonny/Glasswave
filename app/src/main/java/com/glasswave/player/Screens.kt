package com.glasswave.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun App(vm: MainViewModel, ps: PlayerState) {
    var full by remember { mutableStateOf(false) }
    BackHandler(enabled = full) { full = false }

    // Keep the progress bar moving while a song plays.
    LaunchedEffect(ps.playing) {
        while (ps.playing) {
            ps.tick()
            delay(500)
        }
    }

    GlassBackground(art = ps.art) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(14.dp))
                GText("Glasswave", 30, weight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                GlassSearch(vm.query, { vm.query = it }, { vm.search() })
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vm.loading -> Centered("Searching...")
                    vm.error != null -> Centered(vm.error ?: "")
                    vm.results.isEmpty() -> Centered("Search for any song")
                    else -> LazyColumn(
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 4.dp,
                            bottom = if (ps.hasItem) 104.dp else 16.dp,
                        ),
                    ) {
                        itemsIndexed(vm.results, key = { _, t -> t.url }) { i, t ->
                            TrackRow(t, current = t.url == ps.currentId) {
                                ps.playList(vm.results, i)
                            }
                        }
                    }
                }
            }
        }

        if (ps.hasItem) {
            MiniPlayer(
                ps,
                onOpen = { full = true },
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(12.dp),
            )
        }

        AnimatedVisibility(
            visible = full,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            FullPlayer(ps, onClose = { full = false })
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        BasicText2(text)
    }
}

@Composable
private fun BasicText2(text: String) {
    androidx.compose.foundation.text.BasicText(
        text,
        style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, textAlign = TextAlign.Center),
    )
}

@Composable
fun GlassSearch(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(Color.White.copy(alpha = 0.8f), 22.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) GText("Search songs", 17, Color.White.copy(alpha = 0.5f))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun TrackRow(t: Track, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = t.thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(t.title, 16, if (current) Accent else Color.White, FontWeight.Medium, 1)
            Spacer(Modifier.height(2.dp))
            GText(t.artist, 13, Color.White.copy(alpha = 0.6f), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        GText(fmtTime(t.seconds), 12, Color.White.copy(alpha = 0.5f))
    }
}

@Composable
fun MiniPlayer(ps: PlayerState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), base = 0.62f, light = 0.14f)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ps.art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(ps.title, 15, Color.White, FontWeight.Medium, 1)
            GText(
                if (ps.buffering) "Loading..." else ps.artist,
                12,
                Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { ps.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (ps.playing) PauseIcon(Color.White, 26.dp) else PlayIcon(Color.White, 26.dp)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { ps.next() },
            contentAlignment = Alignment.Center,
        ) {
            NextIcon(Color.White, 24.dp)
        }
    }
}

@Composable
fun FullPlayer(ps: PlayerState, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD9070A10))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Start) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    ChevronDownIcon(Color.White, 30.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            AsyncImage(
                model = ps.art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .glass(RoundedCornerShape(36.dp), base = 0.15f, light = 0.1f),
            )
            Spacer(Modifier.height(28.dp))
            GText(ps.title, 22, Color.White, FontWeight.Bold, 2, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            GText(ps.artist, 16, Color.White.copy(alpha = 0.65f), maxLines = 1, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            SeekBar(ps.positionMs, ps.durationMs) { ps.seekTo(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GText(fmtTime(ps.positionMs / 1000), 12, Color.White.copy(alpha = 0.55f))
                GText(fmtTime(ps.durationMs / 1000), 12, Color.White.copy(alpha = 0.55f))
            }
            if (ps.error != null) {
                Spacer(Modifier.height(10.dp))
                GText(ps.error ?: "", 13, Color(0xFFFFA8A8))
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).clickable { ps.previous() }, contentAlignment = Alignment.Center) {
                    PrevIcon(Color.White, 30.dp)
                }
                Box(Modifier.clip(CircleShape).clickable { ps.toggle() }) {
                    GlassCircle(84.dp) {
                        if (ps.playing) PauseIcon(Color.White, 38.dp) else PlayIcon(Color.White, 38.dp)
                    }
                }
                Box(Modifier.size(56.dp).clip(CircleShape).clickable { ps.next() }, contentAlignment = Alignment.Center) {
                    NextIcon(Color.White, 30.dp)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(durationMs) {
                detectTapGestures { o ->
                    if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
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
                val cy = size.height / 2
                val tr = 3.dp.toPx()
                drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, cy - tr), Size(size.width, tr * 2), CornerRadius(tr))
                drawRoundRect(Color.White, Offset(0f, cy - tr), Size(size.width * frac, tr * 2), CornerRadius(tr))
                drawCircle(Color.White, 8.dp.toPx(), Offset(size.width * frac, cy))
            },
    )
}
