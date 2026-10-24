// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.DownloadDone

/** A greeting for the time of day, picked from a few so it isn't the same every time. */
private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> listOf("Good morning", "Morning", "Rise and shine", "Hey, early bird", "Fresh start")
    in 12..16 -> listOf("Good afternoon", "Hey there", "Afternoon", "Midday mood", "Hi again")
    in 17..21 -> listOf("Good evening", "Evening", "Winding down?", "Hey you", "Golden hour")
    else -> listOf("Up late?", "Night owl", "Still up?", "Late night", "Good night")
}.random()

private val askLines = listOf(
    "What would you like to listen to?",
    "What are we playing today?",
    "What's the mood right now?",
    "Find something to press play on",
    "What should we put on?",
    "Pick a vibe, any vibe",
)

/* ---------------- Tidying the feed ---------------- */

/** Shelves that make Home feel like YouTube Music rather than a music player. */
private val dropShelves = listOf(
    "cover", "remix", "community", "live performance", "music video", "video", "podcast", "episode",
    "shorts", "listen again", "from your library",
)
private val coverWord = Regex("\\bcovers?\\b", RegexOption.IGNORE_CASE)
private val renames = mapOf(
    "quick picks" to "Picked for you",
    "mixed for you" to "Your mixes",
    "recommended albums" to "Albums to try",
    "new releases" to "Fresh out",
    "from your library" to "Yours",
)

private fun keepItem(i: MusicItem): Boolean {
    val t = i.title
    if (coverWord.containsMatchIn(t) || coverWord.containsMatchIn(i.subtitle)) return false
    // Radios go, mixes stay.
    val radio = t.contains("radio", true) || (i.subtitle.startsWith("Radio", true))
    return !(radio && !t.contains("mix", true))
}

/** Home without covers, radios and video shelves, with a few friendlier names. */
fun cleanHome(sections: List<Section>): List<Section> =
    sections.filter { s -> dropShelves.none { s.title.lowercase().contains(it) } }
        .map { s ->
            val name = renames[s.title.lowercase()] ?: s.title
            Section(name, s.items.filter(::keepItem))
        }
        .filter { it.items.size >= 2 }
        .take(10)

@Composable
fun HomeScreen(c: ScreenCtx) {
    val vm = c.vm
    PullRefresh(vm.refreshing, vm::refreshHome, c.bg) {
        LazyColumn(state = vm.homeList, contentPadding = PaddingValues(bottom = c.bottom)) {
            item(key = "header") {
                // Picked once per visit, not on every redraw.
                val hello = androidx.compose.runtime.remember { greeting() }
                val ask = androidx.compose.runtime.remember { askLines.random() }
                Row(Modifier.padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        GText(hello, 34, weight = FontWeight.Bold, maxLines = 1)
                        GText(ask, 15, Dim, maxLines = 1)
                    }
                    // Top right: updates, then your YouTube Music picture.
                    UpdateButton()
                    Spacer(Modifier.width(8.dp))
                    ProfileButton { vm.profileOpen = true }
                }
            }
            // A new version is ready: say so under the header too, with the download progress.
            Updater.available?.let { r ->
                item(key = "update") {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val p = Updater.progress
                    GText(
                        when {
                            p != null -> "Downloading Glasswave ${r.version}… ${(p * 100).toInt()}%"
                            Updater.ready != null -> "Glasswave ${r.version} is downloaded · tap to install"
                            else -> "Glasswave ${r.version} is out · getting it ready…"
                        },
                        13, CoverTint.accent, FontWeight.SemiBold, 1,
                        Modifier.padding(start = 20.dp, end = 16.dp, bottom = 6.dp).pressable(0.98f) { Updater.install(ctx) },
                    )
                }
            }


            // A few mood chips
            if (vm.homeChips.isNotEmpty()) {
                item(key = "chips") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(vm.homeChips.take(6)) { chip -> Chip(chip.title, vm.homeChip == chip, c.bg) { vm.pickChip(chip) } }
                    }
                }
            }

            when (val h = vm.home) {
                is Load.Loading -> item(key = "loading") { HomeSkeleton() }
                is Load.Failed -> item(key = "error") { ErrorBox(h.message, c.bg) { vm.loadHome() } }
                is Load.Done -> {
                    if (h.value.isEmpty()) item(key = "empty") { Centered("Nothing here right now. Pull down to refresh.") }
                    // The first section of albums or playlists becomes a big swipeable showcase.
                    val heroIndex = h.value.indexOfFirst { s -> s.items.size >= 3 && s.items.count { it !is SongItem } * 2 > s.items.size }
                    val recent = History.tracks.take(15)
                    h.value.forEachIndexed { i, s ->
                        item(key = "s$i-${s.title}") {
                            Column(Modifier) {
                                SectionTitle(s.title.ifEmpty { "For you" })
                                if (i == heroIndex) Hero(c, s) else SectionBody(c, s)
                            }
                        }
                        // After the first shelf: Made for you (Flow and your mixes).
                        if (i == 0 && vm.homeChip == null) {
                            item(key = "madeForYou") {
                                val seeds = h.value.flatMap { it.items }.filterIsInstance<SongItem>().map { it.track }
                                MadeForYou(c, seeds)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Big cards you swipe through; the ones to the side shrink and dim. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(c: ScreenCtx, s: Section) {
    val items = s.items.take(10)
    val pager = rememberPagerState { items.size }
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 44.dp),
        pageSpacing = 12.dp,
    ) { page ->
        val item = items[page]
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                    val sc = 1f - 0.12f * off
                    scaleX = sc
                    scaleY = sc
                    alpha = 1f - 0.4f * off
                }
                .shadow(20.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(22.dp))
                .pressable(0.97f) { c.tap(item, items) },
        ) {
            Cover(hiRes(item.thumb), Modifier.fillMaxSize(), 0.dp, circle = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GText(item.title, 20, weight = FontWeight.Bold, maxLines = 2)
                    if (item.subtitle.isNotEmpty()) GText(item.subtitle, 13, Color.White.copy(alpha = 0.75f), maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                GlassCircle(46.dp, c.bg, tint = Accent.copy(alpha = 0.55f), onClick = { c.tap(item, items) }) {
                    Ico(Icons.Rounded.PlayArrow, Color.White, 26.dp)
                }
            }
        }
    }
}

/** Songs show as columns of four rows you swipe through; everything else as cards. */
@Composable
fun SectionBody(c: ScreenCtx, s: Section) {
    val songs = s.items.count { it is SongItem }
    if (songs * 2 > s.items.size && s.items.size > 4) {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.fillMaxWidth().height(256.dp),
        ) {
            itemsIndexed(s.items, key = { i, x -> "$i${x.title}" }) { _, item ->
                val song = item as? SongItem
                Row(Modifier.width(310.dp)) {
                    if (song != null) {
                        TrackRow(song.track, song.track.url == c.ps.currentId, c.ps.playing) { c.tap(item, s.items) }
                    } else {
                        ItemRow(item) { c.tap(item, s.items) }
                    }
                }
            }
        }
    } else {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(s.items) { item ->
                ShapeCard(item, if (item is AlbumItem) 168.dp else 150.dp) { c.tap(item, s.items) }
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column {
        Shimmer(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 12.dp).size(width = 160.dp, height = 22.dp), 6.dp)
        Shimmer(Modifier.padding(horizontal = 44.dp).fillMaxWidth().aspectRatio(1f), 22.dp)
        repeat(2) {
            Shimmer(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 12.dp).size(width = 160.dp, height = 22.dp), 6.dp)
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(3) {
                    Column {
                        Shimmer(Modifier.size(150.dp))
                        Spacer(Modifier.height(8.dp))
                        Shimmer(Modifier.size(width = 110.dp, height = 14.dp), 4.dp)
                    }
                }
            }
        }
    }
}

/** Songs you played lately, as a shelf of cards. */
@Composable
private fun RecentShelf(c: ScreenCtx, recent: List<Track>) {
    Column {
        SectionTitle("Recently played")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(recent.size) { i ->
                val t = recent[i]
                ShapeCard(SongItem(t, t.artist), 132.dp) { c.ps.playList(recent, i) }
            }
        }
    }
}

/**
 * Made for you: Flow (the nonstop mix that plays like a DJ set) leads, followed by quick mixes made
 * from your likes, downloads and history. Square tiles, like the album shelves around them.
 */
@Composable
private fun MadeForYou(c: ScreenCtx, seeds: List<Track>) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val liked = Library.liked.size
    val offline = Downloads.tracks.size
    val history = History.tracks.size
    Column {
        SectionTitle("Made for you")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "flow") { FlowTile(c, seeds) }
            if (liked >= 3) item(key = "liked") {
                MixTile("Liked mix", " songs, shuffled", androidx.compose.material.icons.Icons.Rounded.Favorite, Lime) {
                    scope.launch { c.ps.playList(Library.tracks(Library.LIKED_ID), 0, shuffle = true) }
                }
            }
            if (offline >= 3) item(key = "offline") {
                MixTile("Offline mix", "Plays without internet", androidx.compose.material.icons.Icons.Rounded.DownloadDone, Color(0xFF64D2FF)) {
                    c.ps.playList(Downloads.tracks.toList(), 0, shuffle = true)
                }
            }
        }
    }
}

private val tileSize = 150.dp

/** Flow's tile: a slowly turning glow in the cover's colours. Shows the part playing while it's on. */
@Composable
private fun FlowTile(c: ScreenCtx, seeds: List<Track>) {
    val accent = CoverTint.accent
    val spin by rememberDrift(12_000)
    val active = Flow.active
    Column(Modifier.width(tileSize).pressable { if (active) Flow.switchUp(c.ps) else Flow.start(c.ps, seeds) }) {
        Box(
            Modifier
                .size(tileSize)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0C0D0A))
                .drawBehind {
                    val s = size.minDimension
                    val ctr = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                    for (k in 0..2) {
                        val a = spin + k * 2.09f
                        val r = s * (0.62f - k * 0.08f)
                        val o = androidx.compose.ui.geometry.Offset(ctr.x + kotlin.math.cos(a) * s * 0.17f, ctr.y + kotlin.math.sin(a) * s * 0.17f)
                        val col = if (k == 1) Color.White else accent
                        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = if (k == 1) 0.22f else 0.55f), Color.Transparent), o, r), r, o)
                    }
                }
                .border(0.8.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(12.dp)),
        ) {
            GText("Flow", 26, Color.White, FontWeight.ExtraBold, 1, Modifier.align(Alignment.BottomStart).padding(12.dp))
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Ico(
                    if (active) androidx.compose.material.icons.Icons.Rounded.AutoAwesome else Icons.Rounded.PlayArrow,
                    Color.Black, if (active) 17.dp else 22.dp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val seg = Flow.now
        GText(
            when {
                Flow.building -> "Mixing your set…"
                active && seg != null -> "Now: " + seg.title
                else -> "Your nonstop mix"
            },
            14, Color.White, FontWeight.Medium, 1,
        )
        GText(if (active) "Tap to switch it up" else "Like a DJ, no talking", 12, Dim, maxLines = 1)
    }
}

/** A quick mix tile: a coloured gradient with an icon. */
@Composable
private fun MixTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Column(Modifier.width(tileSize).pressable(onClick = onClick)) {
        Box(
            Modifier.size(tileSize).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.85f), lerpDark(tint)))),
        ) {
            Ico(icon, Color.Black.copy(alpha = 0.75f), 40.dp, Modifier.align(Alignment.TopStart).padding(12.dp))
            GText(title, 20, Color.Black.copy(alpha = 0.85f), FontWeight.ExtraBold, 2, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        Spacer(Modifier.height(8.dp))
        GText(title, 14, Color.White, FontWeight.Medium, 1)
        GText(subtitle, 12, Dim, maxLines = 1)
    }
}

private fun lerpDark(c: Color) = androidx.compose.ui.graphics.lerp(c, Color.Black, 0.65f)