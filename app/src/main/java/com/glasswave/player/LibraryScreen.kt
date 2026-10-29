// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LibraryScreen(c: ScreenCtx) {
    val vm = c.vm
    val all by vm.playlists.collectAsState()
    val pins = Library.pins.toList()
    // Pinned first (in pin order), then Liked songs, then the rest.
    val sorted = all.sortedWith(
        compareBy<PlaylistInfo>({ p -> pins.indexOf(p.id).let { if (it < 0) Int.MAX_VALUE else it } }, { if (it.id == Library.LIKED_ID) 0 else 1 }),
    )
    // Downloaded: the playlists with songs saved for offline (worked out off the main thread).
    var offlineIds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet()) }
    androidx.compose.runtime.LaunchedEffect(vm.libDownloaded, Downloads.tracks.size, Library.version) {
        if (!vm.libDownloaded) return@LaunchedEffect
        offlineIds = all.filter { p -> Library.tracks(p.id).any { Downloads.isDownloaded(it.url) } }.map { it.id }.toSet()
    }
    val shown = when {
        vm.libDownloaded -> sorted.filter { it.id in offlineIds }
        else -> vm.libFilter?.let { f -> sorted.filter { it.source == f } } ?: sorted
    }
    val spin = rememberInfiniteTransition(label = "sync")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spin")

    Column(Modifier.fillMaxSize()) {
        Header("Library") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassCircle(44.dp, c.bg, onClick = { Menus.newPlaylist() }) {
                    Ico(Icons.Rounded.Add, Color.White, 24.dp)
                }
                // Import a playlist from a link; a link already on the clipboard is filled in.
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                GlassCircle(44.dp, c.bg, onClick = {
                    val copied = runCatching { clip.getText()?.text }.getOrNull()
                    Menus.importLink(LinkImport.supportedLink(copied) ?: "") { vm.importLink(it) }
                }) {
                    Ico(Icons.Rounded.Link, Color.White, 22.dp)
                }
                if (vm.ytSignedIn || vm.spSignedIn) {
                    GlassCircle(44.dp, c.bg, onClick = { vm.syncAll() }) {
                        Ico(Icons.Rounded.Sync, Color.White, 22.dp, Modifier.graphicsLayer { rotationZ = if (vm.syncing) -angle else 0f })
                    }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("All", vm.libFilter == null && !vm.libDownloaded, c.bg) { vm.libFilter = null; vm.libDownloaded = false } }
            item { Chip("Downloaded", vm.libDownloaded, c.bg) { vm.libFilter = null; vm.libDownloaded = true } }
            item { Chip("Yours", vm.libFilter == Source.LOCAL && !vm.libDownloaded, c.bg) { vm.libFilter = Source.LOCAL; vm.libDownloaded = false } }
            item { Chip("YouTube Music", vm.libFilter == Source.YT && !vm.libDownloaded, c.bg) { vm.libFilter = Source.YT; vm.libDownloaded = false } }
            item { Chip("Spotify", vm.libFilter == Source.SPOTIFY && !vm.libDownloaded, c.bg) { vm.libFilter = Source.SPOTIFY; vm.libDownloaded = false } }
        }
        if (vm.syncing) vm.syncStatus?.let { GText(it, 13, Dim, maxLines = 2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }

        LazyVerticalGrid(
            state = vm.libGrid,
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = c.bottom),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.weight(1f),
        ) {
            // Your stats: top songs and artists this month / year, listening history.
            if (!vm.libDownloaded) item(key = "stats", span = { GridItemSpan(maxLineSpan) }) { StatsCard(c) }
            // Recently played, as a shelf.
            val recent = if (vm.libDownloaded) emptyList() else History.tracks.take(15)
            if (recent.isNotEmpty()) item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    GText("Recently played", 19, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(recent.size) { i ->
                            val t = recent[i]
                            ShapeCard(SongItem(t, t.artist), 120.dp) { c.ps.playList(recent, i) }
                        }
                    }
                }
            }
            // Downloads: always here, works without internet.
            item(key = "downloads", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(0.98f) { vm.open(Route.Offline) }
                        .glass(RoundedCornerShape(18.dp), base = 0.22f, light = 0.08f)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(Icons.Rounded.DownloadDone, CoverTint.accent, 24.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        GText("Downloads", 16, weight = FontWeight.SemiBold)
                        val busy = Downloads.progress.size
                        GText("${Downloads.tracks.size} songs · plays offline" + if (busy > 0) " · $busy downloading" else "", 13, Dim, maxLines = 1)
                    }
                    Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 24.dp)
                }
            }
            // Music files already on the phone.
            item(key = "phone", span = { GridItemSpan(maxLineSpan) }) { PhoneMusicRow(c) }
            if (all.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GText("Your playlists live here", 20, weight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        GText("Make your own, or sign in to YouTube Music or Spotify in Settings and your playlists sync in.", 15, Dim)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton("New playlist", c.bg, accent = true, icon = Icons.Rounded.Add) { Menus.newPlaylist() }
                            GlassButton("Settings", c.bg) { vm.selectTab(Tab.SETTINGS) }
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { p -> PlaylistCard(p, c.ps) { vm.open(Route.Saved(p)) } }
            if (shown.isEmpty() && all.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Box { Centered(if (vm.libDownloaded) "No playlists with downloaded songs yet. Tap ⬇ on a playlist to save it for offline." else "Nothing here yet") }
            }
        }
    }
}

/** A playlist in the grid. Tap to open, hold for Pin / Rename / Delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(p: PlaylistInfo, ps: PlayerState, onOpen: () -> Unit) {
    // Lit up while this playlist was started as a whole (not when one of its songs plays on its own).
    val live = PlayingFrom.id == p.id && ps.hasItem
    val box = RoundedCornerShape(18.dp)
    Column(
        Modifier.glass(box)
            .then(if (live) Modifier.border(1.5.dp, readable(CoverTint.accent).copy(alpha = 0.85f), box) else Modifier)
            .then(Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onLongClick = { Menus.openPlaylist(p) },
            onClick = onOpen,
        ))
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (p.id == Library.LIKED_ID) {
                // Liked songs: a lime gradient with a heart, like a little badge of its own.
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Lime, Color(0xFF3F6A12), Color(0xFF0E1A06)))),
                    contentAlignment = Alignment.Center,
                ) { Ico(Icons.Rounded.Favorite, Color.Black.copy(alpha = 0.85f), 56.dp) }
            } else {
                Cover(p.thumb, Modifier.fillMaxSize(), 12.dp, fallback = Icons.AutoMirrored.Rounded.QueueMusic)
            }
            if (Library.isPinned(p.id)) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center,
                ) { Ico(Icons.Rounded.PushPin, Lime, 15.dp) }
            }
            if (live) PlayingBadge(ps.playing, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        GText(p.name, 14, if (live) readable(CoverTint.accent) else Color.White, FontWeight.Medium, 1, Modifier.padding(horizontal = 2.dp))
        GText("${p.source.label} · ${p.count}", 12, Dim, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** The way into your stats: this month's minutes and your top song so far. */
@Composable
private fun StatsCard(c: ScreenCtx) {
    var month by remember { androidx.compose.runtime.mutableStateOf<Stats.Summary?>(null) }
    androidx.compose.runtime.LaunchedEffect(Stats.version) { month = Stats.summary(Stats.Period.MONTH) }
    val accent = CoverTint.accent
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(0.98f) { c.vm.open(Route.Stats) }
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f))))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GText("YOUR STATS · THIS MONTH", 11, accent, FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            val m = month
            GText(if (m == null) "…" else "${m.minutes} min · ${m.plays} plays", 22, weight = FontWeight.ExtraBold, maxLines = 1)
            GText(
                m?.topSongs?.firstOrNull()?.let { "Top song: ${it.track.title}" } ?: "Top songs, top artists and your history",
                13, Dim, maxLines = 1,
            )
        }
        val top = month?.topSongs?.firstOrNull()
        if (top != null) {
            Cover(top.track.thumb, Modifier.size(56.dp), 10.dp)
            Spacer(Modifier.width(8.dp))
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 24.dp)
    }
}