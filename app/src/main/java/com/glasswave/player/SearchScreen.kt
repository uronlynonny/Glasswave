// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
fun SearchScreen(c: ScreenCtx) {
    val vm = c.vm
    LaunchedEffect(Unit) { vm.loadGenres() }
    // Opened from the round Search button: straight into the box, keyboard up.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(vm.focusSearch) {
        if (!vm.focusSearch) return@LaunchedEffect
        vm.focusSearch = false
        runCatching { focus.requestFocus(); keyboard?.show() }
    }
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (vm.query.isNotEmpty()) {
                Box(Modifier.size(36.dp).pressable(0.85f) { vm.onQuery("") }, contentAlignment = Alignment.Center) {
                    Ico(Icons.Rounded.Cancel, Faint, 20.dp)
                }
            }
            // Voice search: sing or say lyrics.
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
                    YourUsual(c)
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
                sug != null -> Column { YourUsual(c); Suggestions(c, sug) }
                r == null || r is Load.Loading -> Column { repeat(8) { SkeletonRow() } }
                r is Load.Failed -> ErrorBox(r.message, c.bg, { c.ps.playList(Downloads.tracks.toList().shuffled(), 0) }) { vm.submitSearch() }
            }
        }
    }
}

/** The song(s) you usually play when you search something starting like this: right at the top. */
@Composable
private fun YourUsual(c: ScreenCtx) {
    val q = c.vm.query
    val usual = androidx.compose.runtime.remember(q) { SearchPicks.match(q) }
    if (usual.isEmpty()) return
    Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp)) {
        GText("You usually play", 13, Faint, FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp))
        usual.forEach { t ->
            TrackRow(t, t.url == c.ps.currentId, c.ps.playing) {
                SearchPicks.record(q, t)
                c.vm.rememberQuery()
                c.ps.playList(listOf(t), 0)
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

/**
 * The Explore tab: ready-to-play mixes made from what you listen to (like Spotify's Daily Mix and
 * artist mixes), then a row of mood mixes. Tap a mix and it plays straight away.
 */
@Composable
fun ExploreScreen(c: ScreenCtx) {
    LaunchedEffect(Unit) {
        c.vm.loadGenres()
        Mixes.refresh(c.vm.viewModelScope)
    }
    val mixes = Mixes.list
    val moods = c.vm.genres.filter { it.mood }
    val cols = if (isWide()) 4 else 2
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = c.bottom)) {
        item(key = "head") {
            Column {
                Header("Explore")
                GText("Mixes made from what you play", 15, Dim, maxLines = 1, modifier = Modifier.padding(start = 20.dp, end = 20.dp).padding(bottom = 6.dp))
            }
        }
        when {
            // Reading your stats: placeholder cards for a moment.
            mixes == null -> item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(cols) { Shimmer(Modifier.weight(1f).aspectRatio(0.8f), 18.dp) }
                }
            }
            mixes.isEmpty() -> item(key = "empty") { NoMixesYet() }
            else -> {
                item(key = "mixesTitle") { SectionTitle("Your mixes") }
                mixes.chunked(cols).forEachIndexed { i, row ->
                    item(key = "mixRow$i") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { m -> MixCard(c, m, Modifier.weight(1f)) }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        // Mood mixes: a playlist for the mood, shuffled. Only when YouTube Music sent its moods.
        if (moods.isNotEmpty()) {
            item(key = "moodsTitle") { SectionTitle(if (mixes.isNullOrEmpty()) "Start with a mood" else "Mood mixes") }
            item(key = "moods") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(moods, key = { it.title }) { g -> MoodTile(c, g) }
                }
            }
        } else if (mixes?.isEmpty() == true) {
            item(key = "offline") { Centered("Mood mixes show here when you're online.") }
        }
    }
}

/** Empty search box: recent searches and Moods & genres to browse. */
@Composable
private fun Explore(c: ScreenCtx, showRecent: Boolean = true) {
    val vm = c.vm
    LazyColumn(contentPadding = PaddingValues(bottom = c.bottom)) {
        if (showRecent && vm.recent.isNotEmpty()) {
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
            // Two picture tiles per row; each picture comes from that mood's own playlists.
            vm.genres.chunked(2).forEachIndexed { i, row ->
                item(key = "g$i") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { g -> GenreTile(c, g, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else if (!showRecent) {
            item(key = "hint") { Centered("Moods & genres show here when you're online.") }
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
            coil.compose.AsyncImage(
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
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
            // The song's menu, like every other song: add to a playlist, play next, go to the artist...
            Box(Modifier.size(40.dp).pressable(0.85f) { Menus.openSong(item.track) }, contentAlignment = Alignment.Center) {
                Ico(Icons.Rounded.MoreVert, Dim, 22.dp)
            }
        }
    }
}


/** Opening a result also saves the search in Recent (results appear while typing, without pressing search). */
private fun tapAndRemember(c: ScreenCtx, item: MusicItem, all: List<MusicItem>) {
    if (item is SongItem) SearchPicks.record(c.vm.query, item.track)
    c.vm.rememberQuery()
    c.tap(item, all)
}

/* ---------------- Explore: your mixes ---------------- */

/** One ready-to-play mix on Explore. [key] stays the same between visits, so a mix made once is reused for a while. */
private class Mix(
    val key: String,
    /** Small line on the cover: "ARTIST MIX", "MADE FOR YOU"... */
    val label: String,
    /** The big name on the cover. */
    val cover: String,
    /** The name under the cover. */
    val name: String,
    /** The line under the name until the mix is made (then it says who's in it). */
    val blurb: String,
    /** Artist mixes: whose photo goes on the cover. */
    val artist: String?,
    /** Pictures for the cover: one, or four for a collage (empty: the mix's own songs once it's made). */
    val pictures: List<String>,
    val color: Color,
    val build: suspend () -> List<Track>,
)

/** Bright colours for the mix covers, one after another. */
private val mixColors = listOf(
    Color(0xFFFF6B6B), Color(0xFF4ECDC4), Color(0xFFFFC857), Color(0xFF9B5DE5), Color(0xFF00BBF9),
    Color(0xFFF15BB5), Color(0xFF8AC926), Color(0xFFFF924C), Color(0xFF5E7BFF), Color(0xFF2EC4B6),
)

/** A song's main artist, counted the same way as in your stats ("Wizkid, Tems" -> "Wizkid"). */
private fun leadArtist(a: String) = a.substringBefore(",").substringBefore(" & ").trim()

/** "Burna Boy, Rema, Wizkid and more". */
private fun artistsLine(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size <= 3 -> names.joinToString(", ")
    else -> names.take(3).joinToString(", ") + " and more"
}

/** Who's in a made mix, most songs first ([first] leads, for an artist's own mix). */
private fun whoIsIn(songs: List<Track>, first: String?): String {
    val counts = songs.map { leadArtist(it.artist) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
    val names = counts.entries.sortedByDescending { it.value }.map { it.key }
    val ordered = if (first == null) names else listOf(first) + names.filterNot { it.equals(first, true) }
    return artistsLine(ordered)
}

/** Songs like these (YouTube Music's radio for each), all together. Nothing for a seed that fails. */
private suspend fun radios(seeds: List<Track>): List<Track> = coroutineScope {
    seeds.map { s ->
        async(Dispatchers.IO) {
            try {
                YtRepo.videoIdFor(s.url)?.let { NotInterested.thin(YtMusicApi.radio(it)) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }.awaitAll().flatten()
}

/** One of each song, minus [skip], in a mixed-up order. */
private fun blend(pool: List<Track>, skip: Set<String> = emptySet(), max: Int = 50): List<Track> =
    pool.filter { it.url !in skip }.distinctBy { YtRepo.songKey(it.title, it.artist) }.shuffled().take(max)

/**
 * Explore's mixes, made from your stats: a Daily Mix, one mix per artist you keep coming back to
 * (more artists, more mixes, up to six), Discover, On Repeat and Throwback. Each is made ahead of
 * time in the background, so tapping one plays at once.
 */
private object Mixes {
    /** The mixes to show; null until your stats have been read the first time. */
    var list by mutableStateOf<List<Mix>?>(null)
        private set
    /** Mixes already made, ready to play. */
    val made = mutableStateMapOf<String, List<Track>>()
    /** Mixes you tapped that are still being made (their card shows a spinner). */
    val busy = mutableStateMapOf<String, Boolean>()
    private val madeAt = HashMap<String, Long>()
    private val jobs = HashMap<String, Deferred<List<Track>>>()
    private val moods = HashMap<String, Mix>()
    private var listAt = 0L
    private var listPlays = -1
    private const val FRESH = 30 * 60_000L

    /** Reads your stats again once you've played a few songs (or it's been a while), then makes the mixes. */
    fun refresh(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        if (list != null && History.played - listPlays < 3 && now - listAt < FRESH) return
        listAt = now
        listPlays = History.played
        scope.launch {
            val fresh = try { plan() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            list = fresh
            // Two at a time, quietly; one that fails is simply made again when tapped.
            val gate = Semaphore(2)
            fresh.forEach { m ->
                launch {
                    gate.withPermit {
                        try { tracks(scope, m).await() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
                    }
                }
            }
        }
    }

    private fun isFresh(key: String) = made[key] != null && System.currentTimeMillis() - (madeAt[key] ?: 0L) < FRESH

    /** The mix's songs: already made, being made, or made now. */
    private fun tracks(scope: CoroutineScope, m: Mix): Deferred<List<Track>> {
        if (isFresh(m.key)) return CompletableDeferred(made.getValue(m.key))
        jobs[m.key]?.takeIf { it.isActive }?.let { return it }
        return scope.async {
            val t = m.build()
            if (t.isNotEmpty()) {
                made[m.key] = t
                madeAt[m.key] = System.currentTimeMillis()
            }
            t
        }.also { jobs[m.key] = it }
    }

    /** Plays a mix in a new order each time (making it first if it isn't ready yet). */
    fun play(c: ScreenCtx, m: Mix) {
        if (busy[m.key] == true) return
        val scope = c.vm.viewModelScope
        busy[m.key] = true
        scope.launch {
            val songs = try {
                tracks(scope, m).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            } finally {
                busy.remove(m.key)
            }
            if (songs.isEmpty()) Menus.say("Couldn't make ${m.name} right now. Check your internet and try again.")
            else c.ps.playList(songs.shuffled(), 0, from = "mix:${m.key}")
        }
    }

    /** A mood mix: one of that mood's own playlists (from its top few), shuffled. */
    fun mood(g: YtMusicApi.Genre): Mix = moods.getOrPut(g.title) {
        Mix("mood:${g.title.lowercase()}", "MOOD", g.title, "${g.title} mix", "", null, emptyList(), Color(g.item.color)) {
            val lists = YtMusicApi.page(g.item).sections.flatMap { it.items }.filterIsInstance<PlaylistItem>().take(6)
            val pick = lists.randomOrNull()
            if (pick == null) emptyList() else NotInterested.thin(YtMusicApi.page(pick).tracks).shuffled().take(80)
        }
    }

    /** Which mixes you get, from your stats and recent plays (no internet needed for this part). */
    private suspend fun plan(): List<Mix> {
        val month = Stats.summary(Stats.Period.MONTH)
        val all = Stats.summary(Stats.Period.ALL)
        val recent = History.tracks.toList()
        val ranked = (month.topSongs + all.topSongs).map { it.track }.distinctBy { it.url }
        val favourites = ranked.ifEmpty { recent }.take(40)
        if (favourites.size < 3) return emptyList()
        val everySong = (ranked + recent).distinctBy { it.url }
        val justHeard = recent.take(8).map { it.url }.toSet()
        val known = (all.topArtists.map { it.name } + recent.map { leadArtist(it.artist) }).map { it.lowercase() }.toSet()
        val out = mutableListOf<Mix>()
        fun color() = mixColors[out.size % mixColors.size]
        fun pictures(songs: List<Track>) = songs.mapNotNull { it.thumb }.distinct().take(4)

        // Daily Mix: your favourites with songs like them.
        val dailyNames = favourites.map { leadArtist(it.artist) }.filter { it.isNotBlank() }.distinct()
        out += Mix("daily", "MADE FOR YOU", "Daily Mix", "Daily Mix", artistsLine(dailyNames), null, pictures(favourites), color()) {
            blend(favourites.shuffled().take(15) + radios(favourites.shuffled().take(2)), justHeard)
        }

        // One mix per artist you keep coming back to: their songs and artists like them.
        val plays = all.topArtists.associate { it.name.lowercase() to it.plays }
        val artists = (month.topArtists + all.topArtists).distinctBy { it.name.lowercase() }
            .filter { (plays[it.name.lowercase()] ?: it.plays) >= 3 }
            .take(6)
        for (a in artists) {
            val own = everySong.filter { leadArtist(it.artist).equals(a.name, true) }
            if (own.isEmpty()) continue
            out += Mix(
                "artist:${a.name.lowercase()}", "ARTIST MIX", a.name, "${a.name} Mix", "${a.name} and artists like them",
                a.name, listOfNotNull(a.thumb ?: own.first().thumb), color(),
            ) {
                val similar = radios(own.take(3).shuffled().take(1))
                // Offline: their songs you've played, if there are enough to make a mix.
                if (similar.isEmpty() && own.size < 3) emptyList() else blend(own.shuffled().take(12) + similar)
            }
        }

        // Discover: artists you haven't played, from the world of your favourites.
        out += Mix("discover", "NEW TO YOU", "Discover", "Discover Mix", "New artists like the ones you love", null, emptyList(), color()) {
            blend(radios(favourites.shuffled().take(3)).filter { leadArtist(it.artist).lowercase() !in known }, justHeard)
                .let { if (it.size < 5) emptyList() else it }
        }

        // On Repeat: what you've played most this month.
        val onRepeat = month.topSongs.take(30).map { it.track }
        if (onRepeat.size >= 5) out += Mix("repeat", "YOUR MOST PLAYED", "On Repeat", "On Repeat", "The songs you can't stop playing", null, pictures(onRepeat), color()) { onRepeat }

        // Throwback: old favourites you haven't played lately.
        val lately = month.topSongs.map { it.track.url }.toSet() + recent.map { it.url }
        val throwback = all.topSongs.map { it.track }.filter { it.url !in lately }.take(30)
        if (throwback.size >= 5) out += Mix("throwback", "OLD FAVOURITES", "Throwback", "Throwback Mix", "Songs you loved and haven't played lately", null, pictures(throwback), color()) { throwback }

        return out
    }
}

/** A mix on Explore: a square cover with the mix's colour and name, and who's in it underneath. */
@Composable
private fun MixCard(c: ScreenCtx, m: Mix, modifier: Modifier) {
    val made = Mixes.made[m.key]
    val busy = Mixes.busy[m.key] == true
    // Lit up while this mix is what's playing (like a playlist started with Play).
    val live = PlayingFrom.id == "mix:${m.key}" && c.ps.hasItem
    val box = RoundedCornerShape(18.dp)
    Column(
        modifier.glass(box)
            .then(if (live) Modifier.border(1.5.dp, readable(CoverTint.accent).copy(alpha = 0.85f), box) else Modifier)
            .pressable(0.97f) { Mixes.play(c, m) }
            .padding(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(m.color, lerp(m.color, Color.Black, 0.7f)))),
        ) {
            val pics = m.pictures.ifEmpty { made.orEmpty().mapNotNull { it.thumb }.distinct().take(4) }
            when {
                m.artist != null -> Cover(artistPhoto(m.artist, pics.firstOrNull()), Modifier.fillMaxSize(), 0.dp)
                // Four covers from the mix, side by side.
                pics.size >= 4 -> Column(Modifier.fillMaxSize()) {
                    for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (k in 0..1) Cover(pics[r * 2 + k], Modifier.weight(1f).fillMaxHeight(), 0.dp)
                    }
                }
                pics.isNotEmpty() -> Cover(pics.first(), Modifier.fillMaxSize(), 0.dp)
                else -> Ico(Icons.Rounded.AutoAwesome, Color.Black.copy(alpha = 0.55f), 44.dp, Modifier.align(Alignment.Center))
            }
            // The mix's colour rises from the bottom, so its name reads on any picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to lerp(m.color, Color.Black, 0.5f).copy(alpha = 0.95f))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 13.dp)) {
                GText(m.label, 10, Color.White.copy(alpha = 0.85f), FontWeight.Bold, 1)
                GText(m.cover, 19, Color.White, FontWeight.ExtraBold, 2)
            }
            // A band of the mix's colour along the bottom edge, like Spotify's mix covers.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(m.color))
            if (live) PlayingBadge(c.ps.playing, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (busy) Spinner(Modifier.align(Alignment.Center), 46.dp)
        }
        Spacer(Modifier.height(8.dp))
        GText(m.name, 14, if (live) readable(CoverTint.accent) else Color.White, FontWeight.Medium, 1, Modifier.padding(horizontal = 2.dp))
        // Two lines' room, so cards side by side stay the same height.
        Box(Modifier.height(32.dp).padding(horizontal = 2.dp)) {
            GText(made?.let { whoIsIn(it, m.artist) }?.ifEmpty { null } ?: m.blurb, 12, Dim, maxLines = 2)
        }
    }
}

/** A mood mix: a colourful tile in the mood's own colour. Tap to play. */
@Composable
private fun MoodTile(c: ScreenCtx, g: YtMusicApi.Genre) {
    val m = Mixes.mood(g)
    val busy = Mixes.busy[m.key] == true
    val live = PlayingFrom.id == "mix:${m.key}" && c.ps.hasItem
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.size(width = 140.dp, height = 80.dp)
            .pressable(0.96f) { Mixes.play(c, m) }
            .clip(shape)
            .background(Brush.linearGradient(listOf(m.color.copy(alpha = 0.95f), lerp(m.color, Color.Black, 0.6f))))
            .background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0x8C000000)))
            .border(if (live) 1.5.dp else 0.5.dp, if (live) readable(CoverTint.accent).copy(alpha = 0.85f) else CleanEdge, shape),
    ) {
        GText(g.title, 16, Color.White, FontWeight.ExtraBold, 1, Modifier.align(Alignment.BottomStart).padding(12.dp))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            when {
                busy -> Spinner(Modifier, 28.dp)
                live -> PlayingBadge(c.ps.playing, size = 28.dp)
                else -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Ico(Icons.Rounded.PlayArrow, Color.White, 18.dp)
                }
            }
        }
    }
}

/** No mixes yet (hardly anything played): what will show up here. */
@Composable
private fun NoMixesYet() {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), base = 0.2f, light = 0.1f).padding(20.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(CoverTint.accent), contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.AutoAwesome, Color.Black, 24.dp)
        }
        Spacer(Modifier.height(12.dp))
        GText("Your mixes show up here", 19, weight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        GText("Play some songs and Glasswave makes mixes from what you listen to: a Daily Mix, one for each artist you love, and new finds.", 14, Dim)
    }
}

/** A small turning ring on a dark disc, while a mix is being made. */
@Composable
private fun Spinner(modifier: Modifier, size: androidx.compose.ui.unit.Dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "a",
    )
    Box(modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size * 0.5f)) {
            drawArc(
                Color.White, a, 270f, false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}
