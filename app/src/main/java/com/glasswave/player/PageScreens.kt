// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Album, artist or playlist from YouTube Music. */
@Composable
fun OnlinePage(c: ScreenCtx, item: MusicItem) {
    val vm = c.vm
    LaunchedEffect(item) { vm.ensurePage(item) }
    val st = vm.pageState(item)
    val page = (st as? Load.Done)?.value
    val list = rememberLazyListState()
    val tracks = page?.tracks ?: emptyList()
    var query by androidx.compose.runtime.remember(item) { androidx.compose.runtime.mutableStateOf("") }

    PageFrame(c, list) {
        item(key = "head") {
            PageHeader(
                list,
                thumb = page?.thumb ?: hiRes(item.thumb),
                title = page?.title ?: item.title,
                subtitle = page?.subtitle ?: item.subtitle,
                round = item is ArtistItem,
                tracks = tracks,
                c = c,
                showDownload = item !is ArtistItem,
                extra = { CollectionActions(c, item, page?.title ?: item.title, page?.thumb ?: hiRes(item.thumb), tracks) },
            )
        }
        if (item !is ArtistItem && tracks.size >= 8) item(key = "find") { FindInList(c, query) { query = it } }
        when (st) {
            is Load.Loading -> items(6) { SkeletonRow() }
            is Load.Failed -> item(key = "err") { ErrorBox(st.message, c.bg, { c.ps.playList(Downloads.tracks.toList().shuffled(), 0) }) { vm.retryPage(item) } }
            is Load.Done -> {
                if (item is ArtistItem && tracks.isNotEmpty()) item(key = "top") { SectionTitle("Top songs") }
                trackList(c, if (item is ArtistItem) tracks else found(tracks, query), numbered = item is AlbumItem && query.isBlank(), shown = st.value.shown)
                st.value.sections.forEachIndexed { i, s ->
                    item(key = "sec$i") {
                        Column(Modifier) {
                            SectionTitle(s.title)
                            SectionBody(c, s)
                        }
                    }
                }
                if (tracks.isEmpty() && st.value.sections.isEmpty()) item(key = "none") { Centered("Nothing to play here.") }
            }
        }
    }
}

/** A playlist saved in your library from YouTube Music or Spotify. */
@Composable
fun SavedPage(c: ScreenCtx, start: PlaylistInfo) {
    val list = rememberLazyListState()
    // Read the latest name and count (it may have been renamed or changed since it was opened).
    val all by c.vm.playlists.collectAsState()
    val p = all.firstOrNull { it.id == start.id } ?: start
    LaunchedEffect(p.id, Library.version) { c.vm.reloadSaved(p.id) }
    val tracks = c.vm.savedTracks
    var query by androidx.compose.runtime.remember(p.id) { androidx.compose.runtime.mutableStateOf("") }
    PageFrame(c, list) {
        item(key = "head") {
            PageHeader(
                list, if (p.id == Library.LIKED_ID) tracks.firstOrNull()?.thumb else p.thumb, p.name,
                p.source.label, false, tracks, c,
                extra = {
                    // Pin, and the playlist menu.
                    GlassCircle(48.dp, c.bg, onClick = { Library.togglePin(p.id) }) {
                        Ico(Icons.Rounded.PushPin, if (Library.isPinned(p.id)) Lime else Color.White, 20.dp)
                    }
                    GlassCircle(48.dp, c.bg, onClick = { Menus.openPlaylist(p) }) {
                        Ico(Icons.Rounded.MoreHoriz, Color.White, 22.dp)
                    }
                },
            )
        }
        when {
            !c.vm.savedLoaded -> items(6) { SkeletonRow() }
            tracks.isEmpty() -> item(key = "none") {
                Centered(if (p.source == Source.LOCAL) "No songs yet. Tap ⋮ on any song and choose Add to playlist." else "This playlist is empty.")
            }
            else -> {
                if (tracks.size >= 8) item(key = "find") { FindInList(c, query) { query = it } }
                trackList(c, found(tracks, query), numbered = false, playlistId = p.id)
            }
        }
    }
}

/** Songs saved on the phone. Plays without internet. */
@Composable
fun DownloadsPage(c: ScreenCtx) {
    val list = rememberLazyListState()
    val tracks = Downloads.tracks.toList()
    val busy = Downloads.progress.toMap()
    PageFrame(c, list) {
        item(key = "head") {
            PageHeader(list, tracks.firstOrNull()?.thumb, "Downloads", "${tracks.size} songs · plays offline", false, tracks, c, showDownload = false, icon = Icons.Rounded.Download)
        }
        if (busy.isNotEmpty()) {
            item(key = "busyTitle") { SectionTitle("Downloading") }
            busy.forEach { (url, p) ->
                item(key = "busy$url") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        DownloadRing(p, 22.dp, CoverTint.accent, 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        GText(Downloads.pendingTitle(url) ?: "Song", 15, maxLines = 1, modifier = Modifier.weight(1f))
                        GText("${(p * 100).toInt()}%", 13, Dim)
                    }
                }
            }
        }
        if (tracks.isEmpty() && busy.isEmpty()) {
            item(key = "none") { Centered("No downloads yet. Tap the download button on an album or playlist, or in the player's ••• menu.") }
        }
        itemsIndexed(tracks, key = { _, t -> "dl|${t.url}" }) { i, t ->
            // "Remove download" is in each song's ⋮ menu.
            Box(Modifier.padding(horizontal = 8.dp)) { TrackRow(t, t.url == c.ps.currentId, c.ps.playing) { c.ps.playList(tracks, i) } }
        }
    }
}

/**
 * Your listening stats, like BitChord's: minutes and plays, your most played songs and artists
 * ranked for this month, this year or all time, and your listening history day by day.
 */
@Composable
fun StatsPage(c: ScreenCtx) {
    val list = rememberLazyListState()
    var period by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(Stats.Period.WEEK) }
    var data by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Stats.Summary?>(null) }
    LaunchedEffect(period, Stats.version) { data = Stats.summary(period) }
    val accent = CoverTint.accent
    PageFrame(c, list) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                GText("Your stats", 32, weight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stats.Period.entries.forEach { p -> Chip(p.label, period == p, c.bg) { period = p } }
                }
            }
        }
        val d = data
        if (d == null) {
            items(6) { SkeletonRow() }
            return@PageFrame
        }
        if (d.plays == 0) {
            item(key = "none") { Centered("Nothing here yet. Songs you listen to for 30 seconds or more are counted here.") }
            return@PageFrame
        }
        // The big numbers
        item(key = "nums") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Minutes" to d.minutes.toString(), "Plays" to d.plays.toString(), "Songs" to d.songs.toString()).forEach { (label, value) ->
                    Column(Modifier.weight(1f).glass(RoundedCornerShape(18.dp), base = 0.22f, light = 0.08f).padding(14.dp)) {
                        GText(value, 24, accent, FontWeight.ExtraBold, 1)
                        GText(label, 12, Dim)
                    }
                }
            }
            // This week: how it compares with last week.
            Stats.vsLastWeek(d)?.let { GText(it, 14, accent, FontWeight.SemiBold, 1, Modifier.padding(start = 20.dp, bottom = 6.dp)) }
        }
        // Number one, big
        d.topSongs.firstOrNull()?.let { top ->
            item(key = "one") {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                        .pressable(0.98f) { c.ps.playList(d.topSongs.map { it.track }, 0) }
                        .glass(RoundedCornerShape(22.dp), base = 0.2f, light = 0.1f).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(hiRes(top.track.thumb), Modifier.size(96.dp), 14.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        GText("#1 SONG", 11, accent, FontWeight.Bold)
                        GText(top.track.title, 20, weight = FontWeight.Bold, maxLines = 2)
                        GText(top.track.artist, 14, Dim, maxLines = 1)
                        GText("${top.plays} plays · ${top.ms / 60_000} min", 12, Faint)
                    }
                }
            }
        }
        // Top artists
        if (d.topArtists.isNotEmpty()) {
            item(key = "artistsTitle") { SectionTitle("Top artists") }
            item(key = "artists") {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(d.topArtists.size) { i ->
                        val a = d.topArtists[i]
                        Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                Cover(artistPhoto(a.name, a.thumb), Modifier.size(104.dp), 0.dp, circle = true)
                                Box(
                                    Modifier.align(Alignment.BottomStart).size(24.dp).clip(CircleShape).background(Color(0xCC000000)),
                                    contentAlignment = Alignment.Center,
                                ) { GText("${i + 1}", 11, accent, FontWeight.Medium) }
                            }
                            Spacer(Modifier.height(8.dp))
                            GText(a.name, 14, weight = FontWeight.SemiBold, maxLines = 1)
                            GText("${a.plays} plays", 12, Dim, maxLines = 1)
                        }
                    }
                }
            }
        }
        // Most played songs, ranked
        item(key = "songsTitle") { SectionTitle("Most played") }
        val top5 = d.topSongs.take(5)
        val songs = top5.map { it.track }
        itemsIndexed(top5, key = { i, r -> "top$i|${r.track.url}" }) { i, r ->
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GText("${i + 1}", 14, if (i < 3) accent else Faint, FontWeight.Medium, modifier = Modifier.width(26.dp))
                Box(Modifier.weight(1f)) { TrackRow(r.track, r.track.url == c.ps.currentId, c.ps.playing) { c.ps.playList(songs, i) } }
                GText("${r.plays} plays", 12, Faint, modifier = Modifier.padding(end = 6.dp))
            }
        }
        // Listening history, day by day
        item(key = "historyTitle") { SectionTitle("Listening history") }
        val fmt = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
        val today = java.util.Calendar.getInstance()
        fun dayLabel(at: Long): String {
            val c2 = java.util.Calendar.getInstance().apply { timeInMillis = at }
            val sameYear = c2.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
            val diff = today.get(java.util.Calendar.DAY_OF_YEAR) - c2.get(java.util.Calendar.DAY_OF_YEAR)
            return when {
                sameYear && diff == 0 -> "Today"
                sameYear && diff == 1 -> "Yesterday"
                else -> fmt.format(java.util.Date(at))
            }
        }
        val timeFmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        d.history.groupBy { dayLabel(it.at) }.forEach { (day, plays) ->
            item(key = "day$day") { GText(day.uppercase(), 12, Faint, FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)) }
            plays.forEachIndexed { i, p ->
                item(key = "h$day$i${p.at}") {
                    Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { TrackRow(p.track, p.track.url == c.ps.currentId, c.ps.playing) { c.ps.playList(listOf(p.track), 0) } }
                        GText(timeFmt.format(java.util.Date(p.at)), 12, Faint, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.trackList(c: ScreenCtx, tracks: List<Track>, numbered: Boolean, playlistId: String? = null, shown: Int = Int.MAX_VALUE) {
    // Only the first [shown] are listed, but tapping one plays on through all of [tracks].
    itemsIndexed(tracks.take(shown), key = { i, t -> "$i|${t.url}" }) { i, t ->
        Box(Modifier.padding(horizontal = 8.dp)) {
            TrackRow(t, t.url == c.ps.currentId, c.ps.playing, if (numbered) i + 1 else null, playlistId) { c.ps.playList(tracks, i) }
        }
    }
}

/** "12 songs · 48 min" (or "1 hr 5 min"), like Spotify shows under the title. */
private fun listLength(tracks: List<Track>): String {
    val n = tracks.size
    val songs = if (n == 1) "1 song" else "$n songs"
    val secs = tracks.sumOf { it.seconds }
    if (secs <= 0) return songs
    val mins = (secs + 30) / 60
    val time = if (mins >= 60) "${mins / 60} hr ${mins % 60} min" else "$mins min"
    return "$songs · $time"
}

/** The songs whose name or artist has [query] in it (all of them when it's empty). */
private fun found(tracks: List<Track>, query: String): List<Track> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return tracks
    return tracks.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
}

/** An album or playlist from YouTube Music: save it to your Library (or take it out), and its menu. */
@Composable
private fun CollectionActions(c: ScreenCtx, item: MusicItem, title: String, cover: String?, tracks: List<Track>) {
    val browseId = when (item) {
        is AlbumItem -> item.browseId
        is PlaylistItem -> item.browseId
        else -> return
    }
    if (tracks.isEmpty()) return
    val all by c.vm.playlists.collectAsState()
    val id = Library.savedId(browseId)
    val saved = all.any { it.id == id }
    GlassCircle(
        48.dp, c.bg,
        tint = if (saved) CoverTint.accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.06f),
        onClick = {
            if (saved) {
                Library.delete(id)
                Menus.say("Removed from Library")
            } else {
                Library.saveCollection(browseId, title, cover, tracks)
                Menus.say("Saved to Library")
            }
        },
    ) {
        Ico(if (saved) Icons.Rounded.Check else Icons.Rounded.Add, if (saved) Color.Black else Color.White, 22.dp)
    }
    GlassCircle(48.dp, c.bg, onClick = {
        Menus.openPlaylist(all.firstOrNull { it.id == id } ?: PlaylistInfo(id, title, Source.YT, cover, tracks.size), tracks)
    }) {
        Ico(Icons.Rounded.MoreHoriz, Color.White, 22.dp)
    }
}

/** "Find in playlist": narrows the songs down as you type. */
@Composable
private fun FindInList(c: ScreenCtx, query: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(44.dp)
            .liquidGlass(c.bg, 22.dp, depth = 12.dp, bend = 16.dp)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(Icons.Rounded.Search, Dim, 19.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) GText("Find in this list", 15, Faint)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CoverTint.accent),
                modifier = Modifier.fillMaxWidth().typing(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(34.dp).pressable(0.85f) { onChange("") }, contentAlignment = Alignment.Center) {
                Ico(Icons.Rounded.Close, Faint, 18.dp)
            }
        }
    }
}

@Composable
private fun PageFrame(c: ScreenCtx, list: LazyListState, content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = c.bottom), content = content)
        GlassCircle(44.dp, c.bg, Modifier.padding(start = 14.dp, top = 6.dp), onClick = { c.vm.back() }) {
            Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 22.dp)
        }
    }
}

@Composable
private fun PageHeader(
    list: LazyListState,
    thumb: String?,
    title: String,
    subtitle: String,
    round: Boolean,
    tracks: List<Track>,
    c: ScreenCtx,
    showDownload: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.MusicNote,
    extra: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The cover shrinks and fades as you scroll down.
        Box(
            Modifier.graphicsLayer {
                val scrolled = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 1000f
                val f = (1f - scrolled / 900f).coerceIn(0.6f, 1f)
                scaleX = f
                scaleY = f
                alpha = ((f - 0.6f) / 0.4f).coerceIn(0f, 1f)
                translationY = scrolled * 0.35f
            },
        ) {
            Cover(
                thumb,
                Modifier.size(if (round) 200.dp else 240.dp)
                    .shadow(24.dp, if (round) CircleShape else RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black),
                14.dp,
                circle = round,
                fallback = icon,
            )
        }
        Spacer(Modifier.height(18.dp))
        GText(title, 26, weight = FontWeight.Bold, maxLines = 2)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            GText(subtitle, 15, Dim, maxLines = 2)
        }
        // Like Spotify: how many songs and how long they last altogether.
        if (!round && tracks.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            GText(listLength(tracks), 13, Faint, maxLines = 1)
        }
        Spacer(Modifier.height(18.dp))
        // Small round actions (download, pin, menu) on their own row, so Play and Shuffle below
        // always have room for their names.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tracks.isNotEmpty() && showDownload) {
                // Download every song here: a ring fills as they come in, then a tick.
                val saved = tracks.count { Downloads.isDownloaded(it.url) }
                val partial = tracks.sumOf { (Downloads.progress[it.url] ?: 0f).toDouble() }.toFloat()
                val all = saved == tracks.size
                val busy = tracks.any { Downloads.isDownloading(it.url) }
                GlassCircle(48.dp, c.bg, tint = if (all) CoverTint.accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.06f), onClick = { if (!all) Downloads.downloadAll(tracks) }) {
                    when {
                        all -> Ico(Icons.Rounded.DownloadDone, Color.Black, 22.dp)
                        busy -> Box(contentAlignment = Alignment.Center) {
                            DownloadRing((saved + partial) / tracks.size, 30.dp, CoverTint.accent, 2.5.dp)
                            Ico(Icons.Rounded.Download, Color.White, 15.dp)
                        }
                        else -> Ico(Icons.Rounded.Download, Color.White, 22.dp)
                    }
                }
            }
            extra()
        }
        if (tracks.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Play", c.bg, Modifier.weight(1f), accent = true, icon = Icons.Rounded.PlayArrow) { c.ps.playList(tracks, 0) }
                GlassButton("Shuffle", c.bg, Modifier.weight(1f), icon = Icons.Rounded.Shuffle) { c.ps.playList(tracks, 0, shuffle = true) }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
