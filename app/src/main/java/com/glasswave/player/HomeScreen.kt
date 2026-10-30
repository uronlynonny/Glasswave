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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
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
                        // The app's name, small, over a big greeting.
                        GText("GLASSWAVE", 12, Faint, FontWeight.Bold, 1)
                        GText(hello, 34, weight = FontWeight.Bold, maxLines = 1)
                        GText(ask, 15, Dim, maxLines = 1)
                    }
                    // Top right: updates, Settings (it has no tab of its own), then your YouTube Music picture.
                    UpdateButton()
                    Spacer(Modifier.width(8.dp))
                    GlassCircle(40.dp, c.bg, onClick = { vm.selectTab(Tab.SETTINGS) }) {
                        Ico(Icons.Rounded.SettingsIcon, Color.White, 22.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    ProfileButton { vm.profileOpen = true }
                }
            }
            // A new version is ready: say so under the header too, with the download progress.
            Updater.available?.let { r ->
                item(key = "update") {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val p = Updater.progress
                    // The whole area (both lines and the space around them) installs: no fiddly target,
                    // and tapping again while it downloads just waits for it.
                    Column(
                        Modifier.fillMaxWidth().pressable(0.99f) { Updater.install(ctx) }
                            .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    ) {
                        GText(
                            when {
                                p != null && Updater.installWhenReady -> "Downloading Glasswave ${r.version}… ${(p * 100).toInt()}% · installs when done"
                                p != null -> "Downloading Glasswave ${r.version}… ${(p * 100).toInt()}% · tap to install when done"
                                Updater.ready != null -> "Glasswave ${r.version} is downloaded · tap to install"
                                else -> "Glasswave ${r.version} is out · tap to get it"
                            },
                            13, CoverTint.accent, FontWeight.SemiBold, 1,
                        )
                        // A peek at what's in it: the first two lines of its notes.
                        val peek = Updater.highlights(r.notes, 2)
                        if (peek.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            GText(peek.joinToString(" · "), 12, Dim, maxLines = 2)
                        }
                        Updater.status?.let { Spacer(Modifier.height(2.dp)); GText(it, 12, Color(0xFFFFB4A8), maxLines = 2) }
                    }
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
                is Load.Failed -> item(key = "error") { ErrorBox(h.message, c.bg, { c.ps.playList(Downloads.tracks.toList().shuffled(), 0) }) { vm.loadHome() } }
                is Load.Done -> {
                    // Songs you marked "Not interested" stay off Home.
                    val sections = NotInterested.hide(h.value)
                    if (sections.isEmpty()) item(key = "empty") { Centered("Nothing here right now. Pull down to refresh.") }
                    // The first section of albums or playlists becomes a big swipeable showcase.
                    val heroIndex = sections.indexOfFirst { s -> s.items.size >= 3 && s.items.count { it !is SongItem } * 2 > s.items.size }
                    val recent = History.tracks.take(15)
                    // Flow first: the nonstop mix, right under the mood buttons.
                    if (vm.homeChip == null) item(key = "flow") {
                        FlowStrip(c, sections.flatMap { it.items }.filterIsInstance<SongItem>().map { it.track })
                    }
                    sections.forEachIndexed { i, s ->
                        item(key = "s$i-${s.title}") {
                            Column(Modifier) {
                                SectionTitle(s.title.ifEmpty { "For you" })
                                if (i == heroIndex) Hero(c, s) else SectionBody(c, s)
                            }
                        }
                        // After the first shelf: Made for you (your mixes), when there are any.
                        if (i == 0 && vm.homeChip == null && (Library.liked.size >= 3 || Downloads.tracks.size >= 3)) {
                            item(key = "madeForYou") {
                                val seeds = sections.flatMap { it.items }.filterIsInstance<SongItem>().map { it.track }
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
    // Big covers instead (Settings > Look > Layout): song shelves become rows of cards too.
    if (!Settings.homeCovers && songs * 2 > s.items.size && s.items.size > 4) {
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

/** Made for you: quick mixes from your likes and downloads. Square tiles, like the album shelves around them. */
@Composable
private fun MadeForYou(c: ScreenCtx, seeds: List<Track>) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val liked = Library.liked.size
    val offline = Downloads.tracks.size
    val history = History.tracks.size
    Column {
        SectionTitle("Made for you")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (liked >= 3) item(key = "liked") {
                MixTile("Liked mix", "$liked songs, shuffled", androidx.compose.material.icons.Icons.Rounded.Favorite, Lime) {
                    scope.launch { c.ps.playList(Library.tracks(Library.LIKED_ID), 0, shuffle = true, from = Library.LIKED_ID) }
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

/** The short line under Flow when it isn't playing: one picked at random each time Home opens. */
private val flowLines = listOf(
    "Press play and let it run",
    "Your favourites and a few new ones",
    "Music that keeps going",
    "Made from what you play",
    "One tap, hours of music",
    "Old favourites, new finds",
    "No skipping needed",
)

/** Flow at the top of Home: a wide bar with the sound-levels icon. Tap to start; tap again to switch it up. */
@Composable
private fun FlowStrip(c: ScreenCtx, seeds: List<Track>) {
    val accent = CoverTint.accent
    val active = Flow.active
    val line = androidx.compose.runtime.remember { flowLines.random() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 2.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.2f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.04f))))
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .pressable(0.98f) { if (active) Flow.switchUp(c.ps) else Flow.start(c.ps, seeds) }
            .padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowLevels(accent, active && c.ps.playing, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GText("Flow", 19, Color.White, FontWeight.ExtraBold, 1)
            val seg = Flow.now
            GText(
                when {
                    Flow.building -> "Mixing your set…"
                    active && seg != null -> "Now: " + seg.title
                    active -> "Tap to switch it up"
                    else -> line
                },
                13, Dim, maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Ico(
                if (active) androidx.compose.material.icons.Icons.Rounded.AutoAwesome else Icons.Rounded.PlayArrow,
                Color.Black, if (active) 18.dp else 22.dp,
            )
        }
    }
}

/** Flow's icon: five sound-level bars in a circle of the highlight colour. They bounce while Flow plays. */
@Composable
private fun FlowLevels(accent: Color, moving: Boolean, modifier: Modifier) {
    // Only animates while moving, so a still icon costs nothing.
    val phase = if (moving) levelsPhase() else 0f
    val rest = floatArrayOf(0.29f, 0.71f, 1f, 0.59f, 0.24f)
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension
        drawCircle(accent)
        val w = s * 0.07f
        val gap = s * 0.117f
        for (i in 0..4) {
            val k = if (moving) 0.55f + 0.45f * ((kotlin.math.sin(phase * (1f + (i % 3) * 0.35f) + i * 1.3f) + 1f) / 2f) else 1f
            val h = maxOf(w, s * 0.53f * rest[i] * k)
            drawRoundRect(
                Color.Black.copy(alpha = 0.88f),
                topLeft = androidx.compose.ui.geometry.Offset(center.x + (i - 2) * gap - w / 2, center.y - h / 2),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
    }
}

@Composable
private fun levelsPhase(): Float {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "levels")
    val p by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "phase",
    )
    return p
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