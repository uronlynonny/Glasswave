// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SearchScreen(c: ScreenCtx) {
    val vm = c.vm
    LaunchedEffect(Unit) { vm.loadGenres() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Header("Search")
        // Search field
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(52.dp)
                .liquidGlass(c.bg, 26.dp, depth = 14.dp, bend = 18.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(Icons.Rounded.Search, Dim, 22.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (vm.query.isEmpty()) GText("Songs, albums, artists, videos", 17, Faint)
                BasicTextField(
                    value = vm.query,
                    onValueChange = vm::onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
                    modifier = Modifier.fillMaxWidth().typing(),
                )
            }
            if (vm.query.isNotEmpty()) {
                Box(Modifier.size(36.dp).pressable(0.85f) { vm.onQuery("") }, contentAlignment = Alignment.Center) {
                    Ico(Icons.Rounded.Cancel, Faint, 20.dp)
                }
            }
            // Voice search: sing or say some lyrics.
            Box(Modifier.size(40.dp).pressable(0.85f) { vm.voiceOpen = true }, contentAlignment = Alignment.Center) {
                Ico(androidx.compose.material.icons.Icons.Rounded.Mic, Color.White, 22.dp)
            }
        }
        // Filters
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(YtMusicApi.Filter.entries) { f -> Chip(f.label, vm.filter == f, c.bg) { vm.setSearchFilter(f) } }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val r = vm.results
            val sug = vm.suggestions
            when {
                vm.query.isBlank() -> Explore(c)
                // Results stay on screen as you keep typing; suggestions sit above them as chips.
                r is Load.Done -> Column {
                    if (vm.showSuggestions && sug != null && sug.queries.isNotEmpty()) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sug.queries.take(5)) { q ->
                                Row(
                                    Modifier.pressable(0.95f) { vm.pickRecent(q) }.clip(RoundedCornerShape(16.dp))
                                        .background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Ico(Icons.Rounded.Search, Faint, 15.dp)
                                    Spacer(Modifier.width(6.dp))
                                    GText(q, 13, maxLines = 1)
                                }
                            }
                        }
                    }
                    SearchingLine(vm.searching)
                    Results(c, r.value)
                }
                sug != null -> Suggestions(c, sug)
                r == null || r is Load.Loading -> Column { repeat(8) { SkeletonRow() } }
                r is Load.Failed -> ErrorBox(r.message, c.bg, { c.ps.playList(Downloads.tracks.toList().shuffled(), 0) }) { vm.submitSearch() }
            }
        }
    }
}

/** While typing: suggested searches (tap the arrow to keep editing one), then a few direct hits. */
@Composable
private fun Suggestions(c: ScreenCtx, s: YtMusicApi.Suggestions) {
    val vm = c.vm
    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = c.bottom)) {
        items(s.queries, key = { "q$it" }) { q ->
            Row(
                Modifier.fillMaxWidth().pressable(0.98f) { vm.pickRecent(q) }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(Icons.Rounded.Search, Faint, 20.dp)
                Spacer(Modifier.width(14.dp))
                GText(q, 16, maxLines = 1, modifier = Modifier.weight(1f))
                Box(Modifier.size(40.dp).pressable(0.85f) { vm.onQuery("$q ") }, contentAlignment = Alignment.Center) {
                    Ico(Icons.AutoMirrored.Rounded.CallMade, Faint, 18.dp, Modifier.graphicsLayer { rotationZ = -90f })
                }
            }
        }
        if (s.items.isNotEmpty()) item(key = "div") { Spacer(Modifier.height(8.dp)) }
        items(s.items.size, key = { "i$it" }) { i ->
            val item = s.items[i]
            if (item is SongItem) TrackRow(item.track, item.track.url == c.ps.currentId, c.ps.playing) { tapAndRemember(c, item, s.items) }
            else ItemRow(item) { tapAndRemember(c, item, s.items) }
        }
    }
}

/** Empty search box: recent searches and Moods & genres to browse. */
@Composable
private fun Explore(c: ScreenCtx) {
    val vm = c.vm
    LazyColumn(contentPadding = PaddingValues(bottom = c.bottom)) {
        if (vm.recent.isNotEmpty()) {
            item(key = "recentHead") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    GText("Recent", 19, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    GText("Clear", 15, Accent, FontWeight.Medium, modifier = Modifier.pressable { vm.clearRecent() }.padding(6.dp))
                }
            }
            items(vm.recent.take(6), key = { "r$it" }) { q ->
                Row(
                    Modifier.fillMaxWidth().pressable(0.98f) { vm.pickRecent(q) }.padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(Icons.Rounded.History, Faint, 20.dp)
                    Spacer(Modifier.width(14.dp))
                    GText(q, 16, maxLines = 1)
                }
            }
        }
        if (vm.genres.isNotEmpty()) {
            item(key = "genresHead") { SectionTitle("Moods & genres") }
            if (desktopLayout()) {
                // On a computer: colour cards, as many per row as fit, each with its cover shown whole.
                val cols = ((WindowSize.widthDp - RailSpace.value - 32f) / 230f).toInt().coerceIn(3, 7)
                vm.genres.chunked(cols).forEachIndexed { i, row ->
                    item(key = "dg$i") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { g -> GenreCard(c, g, Modifier.weight(1f)) }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                return@LazyColumn
            }
            // Two picture tiles per row; each picture comes from that mood's own playlists.
            vm.genres.chunked(2).forEachIndexed { i, row ->
                item(key = "g$i") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { g -> GenreTile(c, g, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else if (vm.recent.isEmpty()) {
            item(key = "hint") { Centered("Find any song, album, artist or video on YouTube Music.") }
        }
    }
}

/** A thin moving line while new results load (the old ones stay visible). */
@Composable
private fun SearchingLine(on: Boolean) {
    Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 20.dp)) {
        if (on) {
            val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "searching")
            val x by t.animateFloat(
                0f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900)),
                label = "x",
            )
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val w = size.width * 0.3f
                    drawRect(CoverTint.accent, androidx.compose.ui.geometry.Offset((size.width + w) * x - w, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                },
            )
        }
    }
}

@Composable
private fun GenreTile(c: ScreenCtx, g: YtMusicApi.Genre, modifier: Modifier) {
    LaunchedEffect(g.title) { c.vm.loadGenreArt(g) }
    val art = c.vm.genreArt[g.title]
    Box(
        modifier
            .height(96.dp)
            .pressable(0.97f) { c.vm.open(Route.Online(g.item)) }
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f)),
    ) {
        if (art != null) {
            coil3.compose.AsyncImage(
                model = art, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Shimmer(Modifier.fillMaxSize(), 16.dp)
        }
        // Darken the bottom so the name reads on any picture.
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xD9000000))))
        GText(g.title, 16, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

/**
 * A Moods & genres card for the computer, like Spotify's: filled with the mood's colour, its name
 * in the corner, and its cover whole (never cropped into a strip), tilted in the other corner.
 */
@Composable
private fun GenreCard(c: ScreenCtx, g: YtMusicApi.Genre, modifier: Modifier) {
    LaunchedEffect(g.title) { c.vm.loadGenreArt(g) }
    val art = c.vm.genreArt[g.title]
    val base = if (g.item.color != 0L) Color(g.item.color.toInt()).copy(alpha = 1f)
    else Color.hsv((g.title.hashCode() and 0x7fffffff) % 360f, 0.55f, 0.55f)
    // Deep enough for white text on any of YouTube's colours.
    val bg = androidx.compose.ui.graphics.lerp(base, Color.Black, 0.35f)
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val tilt by androidx.compose.animation.core.animateFloatAsState(if (hovered) 18f else 25f, androidx.compose.animation.core.tween(180), label = "tilt")
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .aspectRatio(1.55f)
            .hoverable(hover)
            .pressable(0.97f) { c.vm.open(Route.Online(g.item)) }
            .clip(RoundedCornerShape(14.dp))
            .background(bg),
    ) {
        val cover = maxHeight * 0.62f
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = cover * 0.18f, y = cover * 0.08f)
                .size(cover)
                .graphicsLayer { rotationZ = tilt }
                .shadow(10.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.25f)),
        ) {
            if (art != null) coil3.compose.AsyncImage(
                model = art, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        GText(g.title, 19, weight = FontWeight.ExtraBold, maxLines = 2, modifier = Modifier.align(Alignment.TopStart).padding(14.dp).fillMaxWidth(0.72f))
    }
}

@Composable
fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Shimmer(Modifier.size(50.dp), 8.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Shimmer(Modifier.size(width = 180.dp, height = 14.dp), 4.dp)
            Spacer(Modifier.height(6.dp))
            Shimmer(Modifier.size(width = 110.dp, height = 12.dp), 4.dp)
        }
    }
}

@Composable
private fun Results(c: ScreenCtx, sections: List<Section>) {
    if (sections.isEmpty()) {
        Centered("Nothing found for \"${c.vm.query}\"")
        return
    }
    val list = rememberLazyListState()
    // Near the bottom of a single-type search: fetch the next page.
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, c.vm.canLoadMore) { if (nearEnd && c.vm.canLoadMore) c.vm.loadMoreResults() }

    val allSongs = sections.filter { it.title == "Songs" || it.title == "More songs" }.flatMap { it.items }
    LazyColumn(state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = c.bottom)) {
        sections.forEachIndexed { si, s ->
            if (s.title == "Top result" && s.items.isNotEmpty()) {
                item(key = "top$si") { TopResult(c, s.items.first(), s.items) }
                return@forEachIndexed
            }
            if (s.title.isNotEmpty()) item(key = "t$si") { SectionTitle(s.title, Modifier.padding(start = 0.dp)) }
            // Albums, artists and playlists in the mixed results: a row of cards you swipe.
            if (c.vm.filter == YtMusicApi.Filter.ALL && s.items.none { it is SongItem }) {
                item(key = "row$si") {
                    DeskLazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(s.items) { entry -> ShapeCard(entry, 140.dp) { tapAndRemember(c, entry, s.items) } }
                    }
                }
                return@forEachIndexed
            }
            // Songs and More songs play as one long queue.
            val queue = if (s.title == "Songs" || s.title == "More songs") allSongs else s.items
            s.items.forEachIndexed { i, entry ->
                item(key = "r$si-$i") {
                    if (entry is SongItem) {
                        TrackRow(entry.track, entry.track.url == c.ps.currentId, c.ps.playing) { tapAndRemember(c, entry, queue) }
                    } else {
                        ItemRow(entry) { tapAndRemember(c, entry, s.items) }
                    }
                }
            }
        }
        if (c.vm.loadingMore) item(key = "more") { SkeletonRow() }
    }
}

/** The big first card: tap to open, or the play button to play straight away. */
@Composable
private fun TopResult(c: ScreenCtx, item: MusicItem, all: List<MusicItem>) {
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .pressable(0.97f) { tapAndRemember(c, item, all) }
            .glass(RoundedCornerShape(22.dp), base = 0.2f, light = 0.1f)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.thumb, Modifier.size(92.dp), 12.dp, circle = item is ArtistItem)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GText(item.title, 20, weight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            GText(item.subtitle, 14, Dim, maxLines = 2)
        }
        if (item is SongItem) {
            GlassCircle(48.dp, c.bg, tint = Accent.copy(alpha = 0.5f), onClick = { tapAndRemember(c, item, all) }) {
                Ico(Icons.Rounded.PlayArrow, Color.White, 28.dp)
            }
        }
    }
}


/** Opening a result also saves the search in Recent (results appear while typing, without pressing search). */
private fun tapAndRemember(c: ScreenCtx, item: MusicItem, all: List<MusicItem>) {
    c.vm.rememberQuery()
    c.tap(item, all)
}