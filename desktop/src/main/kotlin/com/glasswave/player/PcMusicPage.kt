// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class PcView(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), FOLDERS("Folders") }
private enum class PcSort(val label: String) { TITLE("A–Z"), ARTIST("Artist"), RECENT("Recently added") }

/** An album, artist or folder on this PC, with its songs. */
private class PcGroup(val view: PcView, val key: String, val name: String, val songs: List<PcSong>) {
    val thumb get() = songs.firstNotNullOfOrNull { it.track.thumb }
    val subtitle: String
        get() {
            val n = if (songs.size == 1) "1 song" else "${songs.size} songs"
            if (view != PcView.ALBUMS) return n
            val artists = songs.map { it.track.artist }.distinct()
            return (if (artists.size == 1) artists[0] else "Various artists") + " · " + n
        }
}

private fun keyOf(s: PcSong, view: PcView) = when (view) {
    PcView.ALBUMS -> s.albumKey
    PcView.ARTISTS -> s.track.artist.lowercase()
    else -> s.folder.lowercase()
}

private fun nameOf(s: PcSong, view: PcView) = when (view) {
    PcView.ALBUMS -> s.album
    PcView.ARTISTS -> s.track.artist
    else -> PcMusic.folderName(s.folder)
}

/** Asks for a folder and adds it. */
private fun addFolder() {
    val f = PcMusic.pickFolder() ?: return
    PcMusic.addFolder(f)
    Menus.say("Looking for songs in ${f.name.ifEmpty { f.path }}")
}

/** "On this PC" in the Library: a way in, with how many songs there are. */
@Composable
fun PcMusicRow(c: ScreenCtx) {
    LaunchedEffect(Unit) { if (!PcMusic.loaded) PcMusic.refresh() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(0.98f) { c.vm.open(Route.Pc) }
            .glass(RoundedCornerShape(18.dp), base = 0.22f, light = 0.08f)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(Icons.Rounded.Computer, CoverTint.accent, 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText("On this PC", 16, weight = FontWeight.SemiBold)
            val n = PcMusic.songs.size
            GText(if (PcMusic.loaded) "${if (n == 1) "1 song" else "$n songs"} · your own music files" else "Play the music files saved on this PC", 13, Dim, maxLines = 1)
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 24.dp)
    }
}

/** Music files on this PC: songs, albums, artists and folders. */
@Composable
fun PcMusicPage(c: ScreenCtx) {
    // Look again every time the page opens, so new files show up.
    LaunchedEffect(Unit) { PcMusic.refresh() }

    var view by rememberSaveable { mutableStateOf(PcView.SONGS) }
    var sort by rememberSaveable { mutableStateOf(PcSort.TITLE) }
    var query by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

    val all = PcMusic.songs
    val q = query.trim()
    val found = remember(all, q) {
        if (q.isEmpty()) all else all.filter {
            it.track.title.contains(q, true) || it.track.artist.contains(q, true) ||
                it.album.contains(q, true) || PcMusic.folderName(it.folder).contains(q, true)
        }
    }
    val groups = remember(found, view) {
        if (view == PcView.SONGS) emptyList()
        else found.groupBy { keyOf(it, view) }.map { (k, list) -> PcGroup(view, k, nameOf(list[0], view), list) }.sortedBy { it.name.lowercase() }
    }
    // An album, artist or folder that's been opened.
    val open = openKey?.let { k ->
        all.filter { keyOf(it, view) == k }.takeIf { it.isNotEmpty() }?.let { list -> PcGroup(view, k, nameOf(list[0], view), list) }
    }
    BackHandler(enabled = open != null && !Menus.anyOpen) { openKey = null }

    val songs: List<PcSong> = when {
        open != null -> when (view) {
            PcView.ALBUMS -> open.songs.sortedWith(compareBy({ it.trackNo }, { it.track.title.lowercase() }))
            PcView.ARTISTS -> open.songs.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNo }, { it.track.title.lowercase() }))
            else -> open.songs.sortedBy { it.track.title.lowercase() }
        }
        view == PcView.SONGS -> when (sort) {
            PcSort.TITLE -> found
            PcSort.ARTIST -> found.sortedWith(compareBy({ it.track.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
            PcSort.RECENT -> found.sortedByDescending { it.added }
        }
        else -> emptyList()
    }
    val tracks = songs.map { it.track }

    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = c.bottom)) {
            item(key = "head") {
                val title = open?.name ?: "On this PC"
                val sub = when {
                    open != null -> open.subtitle
                    !PcMusic.loaded -> "Looking for music…"
                    PcMusic.scanning -> "${all.size} songs · looking for new ones…"
                    else -> "${all.size} songs · plays without internet"
                }
                val icon = when (open?.view) {
                    PcView.ARTISTS -> Icons.Rounded.Person
                    PcView.FOLDERS -> Icons.Rounded.Folder
                    PcView.ALBUMS -> Icons.Rounded.Album
                    else -> Icons.Rounded.Computer
                }
                PcHeader(open?.thumb, title, sub, icon, open?.view == PcView.ARTISTS, tracks, c)
            }
            when {
                !PcMusic.loaded -> items(6) { SkeletonRow() }
                all.isEmpty() -> item(key = "none") {
                    Column(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GText(if (PcMusic.scanning) "Looking for music…" else "No music files found yet", 20, weight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        GText(
                            "Songs in your Music folder (${PcMusic.musicFolder.path}) play right here, even without internet. " +
                                "Keep your music somewhere else? Add that folder. Nothing leaves this PC.",
                            15, Dim,
                        )
                        Spacer(Modifier.height(16.dp))
                        GlassButton("Add a folder", c.bg, accent = true, icon = Icons.Rounded.CreateNewFolder) { addFolder() }
                        if (PcMusic.folders.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            FolderList(c)
                        }
                    }
                }
                open != null -> pcSongs(c, tracks, numbered = view == PcView.ALBUMS)
                else -> {
                    item(key = "tools") {
                        Column {
                            PcSearch(query, c) { query = it }
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(PcView.entries) { v -> Chip(v.label, view == v, c.bg) { view = v } }
                            }
                            if (view == PcView.SONGS) {
                                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(PcSort.entries) { s -> Chip(s.label, sort == s, c.bg) { sort = s } }
                                }
                            }
                        }
                    }
                    if (view == PcView.FOLDERS) item(key = "where") {
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), base = 0.22f, light = 0.08f).padding(14.dp)) {
                            GText("Where Glasswave looks", 15, weight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            FolderList(c)
                            Spacer(Modifier.height(10.dp))
                            GlassButton("Add a folder", c.bg, icon = Icons.Rounded.CreateNewFolder) { addFolder() }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (view == PcView.SONGS) {
                        if (songs.isEmpty()) item(key = "nomatch") { Centered("Nothing here matches \"$q\"") }
                        pcSongs(c, tracks, numbered = false)
                    } else {
                        if (groups.isEmpty()) item(key = "nomatch") { Centered("Nothing here matches \"$q\"") }
                        items(groups, key = { "g|${it.view}|${it.key}" }) { g -> GroupRow(g) { openKey = g.key } }
                    }
                }
            }
        }
        GlassCircle(44.dp, c.bg, Modifier.padding(start = 14.dp, top = 6.dp), onClick = { if (open != null) openKey = null else c.vm.back() }) {
            Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 22.dp)
        }
        // Add a folder, from any view.
        if (open == null) {
            GlassCircle(44.dp, c.bg, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 6.dp), onClick = { addFolder() }) {
                Ico(Icons.Rounded.CreateNewFolder, Color.White, 22.dp)
            }
        }
    }
}

private fun LazyListScope.pcSongs(c: ScreenCtx, tracks: List<Track>, numbered: Boolean) {
    itemsIndexed(tracks, key = { _, t -> "pc|${t.url}" }) { i, t ->
        Box(Modifier.padding(horizontal = 8.dp)) {
            TrackRow(t, t.url == c.ps.currentId, c.ps.playing, if (numbered) i + 1 else null) { c.ps.playList(tracks, i) }
        }
    }
}

/** The folders we look in. Ones you added can be taken off again. */
@Composable
private fun FolderList(c: ScreenCtx) {
    Column(Modifier.fillMaxWidth()) {
        FolderLine(PcMusic.musicFolder.path, "Music folder", null)
        PcMusic.folders.forEach { path -> FolderLine(path, null) { PcMusic.removeFolder(path); Menus.say("Folder taken off") } }
    }
}

@Composable
private fun FolderLine(path: String, note: String?, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(Icons.Rounded.Folder, Dim, 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            GText(PcMusic.folderName(path), 14, weight = FontWeight.Medium, maxLines = 1)
            GText(note?.let { "$it · $path" } ?: path, 12, Faint, maxLines = 1)
        }
        if (onRemove != null) {
            Box(Modifier.size(36.dp).pressable(0.85f, onClick = onRemove), contentAlignment = Alignment.Center) {
                Ico(Icons.Rounded.Close, Dim, 20.dp)
            }
        }
    }
}

@Composable
private fun PcHeader(thumb: String?, title: String, subtitle: String, icon: ImageVector, round: Boolean, tracks: List<Track>, c: ScreenCtx) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(thumb, Modifier.size(180.dp), 14.dp, circle = round, fallback = icon)
        Spacer(Modifier.height(16.dp))
        GText(title, 26, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        GText(subtitle, 15, Dim, maxLines = 2)
        if (tracks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Play", c.bg, Modifier.weight(1f), accent = true, icon = Icons.Rounded.PlayArrow) { c.ps.playList(tracks, 0) }
                GlassButton("Shuffle", c.bg, Modifier.weight(1f), icon = Icons.Rounded.Shuffle) { c.ps.playList(tracks, 0, shuffle = true) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Find a song, artist, album or folder on this PC. */
@Composable
private fun PcSearch(query: String, c: ScreenCtx, onChange: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .liquidGlass(c.bg, 24.dp, depth = 12.dp, bend = 16.dp)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(Icons.Rounded.Search, Dim, 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) GText("Find on this PC", 16, Faint)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().typing(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(36.dp).pressable(0.85f) { onChange("") }, contentAlignment = Alignment.Center) {
                Ico(Icons.Rounded.Cancel, Faint, 20.dp)
            }
        }
    }
}

/** An album, artist or folder in the list. */
@Composable
private fun GroupRow(g: PcGroup, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .pressable(0.97f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (g.view) {
            PcView.ARTISTS -> Icons.Rounded.Person
            PcView.FOLDERS -> Icons.Rounded.Folder
            else -> Icons.Rounded.Album
        }
        // Folders get a folder, not the first song's cover.
        Cover(if (g.view == PcView.FOLDERS) null else g.thumb, Modifier.size(54.dp), 8.dp, circle = g.view == PcView.ARTISTS, fallback = icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(g.name, 16, weight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            GText(g.subtitle, 13, Dim, maxLines = 1)
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 22.dp)
    }
}
