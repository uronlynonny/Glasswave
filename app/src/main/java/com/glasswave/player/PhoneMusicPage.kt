// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

private enum class PhoneView(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), FOLDERS("Folders") }
private enum class PhoneSort(val label: String) { TITLE("A–Z"), ARTIST("Artist"), RECENT("Recently added") }

/** An album, artist or folder on the phone, with its songs. */
private class PhoneGroup(val view: PhoneView, val key: String, val name: String, val songs: List<PhoneSong>) {
    val thumb get() = songs.firstNotNullOfOrNull { it.track.thumb }
    val subtitle: String
        get() {
            val n = if (songs.size == 1) "1 song" else "${songs.size} songs"
            if (view != PhoneView.ALBUMS) return n
            val artists = songs.map { it.track.artist }.distinct()
            return (if (artists.size == 1) artists[0] else "Various artists") + " · " + n
        }
}

private fun keyOf(s: PhoneSong, view: PhoneView) = when (view) {
    PhoneView.ALBUMS -> s.albumId.toString()
    PhoneView.ARTISTS -> s.track.artist.lowercase()
    else -> s.folder
}

/** "On this phone" in the Library: a way in, with how many songs there are. */
@Composable
fun PhoneMusicRow(c: ScreenCtx) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (!PhoneMusic.loaded && PhoneMusic.allowed(ctx)) PhoneMusic.scan(ctx) }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(0.98f) { c.vm.open(Route.Phone) }
            .glass(RoundedCornerShape(18.dp), base = 0.22f, light = 0.08f)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(Icons.Rounded.PhoneAndroid, CoverTint.accent, 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText("On this phone", 16, weight = FontWeight.SemiBold)
            val n = PhoneMusic.songs.size
            GText(if (PhoneMusic.loaded) "$n songs · your own music files" else "Play the music files saved on your phone", 13, Dim, maxLines = 1)
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 24.dp)
    }
}

/** Music files already on the phone: songs, albums, artists and folders. */
@Composable
fun PhoneMusicPage(c: ScreenCtx) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(PhoneMusic.allowed(ctx)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        asked = true
    }
    // Coming back from the phone's settings: check again.
    LifecycleResumeEffect(Unit) {
        allowed = PhoneMusic.allowed(ctx)
        onPauseOrDispose { }
    }
    // Look again every time the page opens, so new files show up.
    LaunchedEffect(allowed) { if (allowed) PhoneMusic.scan(ctx) }

    var view by rememberSaveable { mutableStateOf(PhoneView.SONGS) }
    var sort by rememberSaveable { mutableStateOf(PhoneSort.TITLE) }
    var query by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

    val all = PhoneMusic.songs
    val q = query.trim()
    val found = remember(all, q) {
        if (q.isEmpty()) all else all.filter {
            it.track.title.contains(q, true) || it.track.artist.contains(q, true) ||
                it.album.contains(q, true) || PhoneMusic.folderName(it.folder).contains(q, true)
        }
    }
    val groups = remember(found, view) {
        if (view == PhoneView.SONGS) emptyList()
        else found.groupBy { keyOf(it, view) }.map { (k, list) ->
            val name = when (view) {
                PhoneView.ALBUMS -> list[0].album
                PhoneView.ARTISTS -> list[0].track.artist
                else -> PhoneMusic.folderName(list[0].folder)
            }
            PhoneGroup(view, k, name, list)
        }.sortedBy { it.name.lowercase() }
    }
    // An album, artist or folder that's been opened.
    val open = openKey?.let { k ->
        all.filter { keyOf(it, view) == k }.takeIf { it.isNotEmpty() }?.let { list ->
            PhoneGroup(view, k, groups.firstOrNull { it.key == k }?.name ?: when (view) {
                PhoneView.ALBUMS -> list[0].album
                PhoneView.ARTISTS -> list[0].track.artist
                else -> PhoneMusic.folderName(list[0].folder)
            }, list)
        }
    }
    BackHandler(enabled = open != null && !Menus.anyOpen) { openKey = null }

    val songs: List<PhoneSong> = when {
        open != null -> when (view) {
            PhoneView.ALBUMS -> open.songs.sortedWith(compareBy({ it.trackNo }, { it.track.title.lowercase() }))
            PhoneView.ARTISTS -> open.songs.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNo }, { it.track.title.lowercase() }))
            else -> open.songs.sortedBy { it.track.title.lowercase() }
        }
        view == PhoneView.SONGS -> when (sort) {
            PhoneSort.TITLE -> found
            PhoneSort.ARTIST -> found.sortedWith(compareBy({ it.track.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
            PhoneSort.RECENT -> found.sortedByDescending { it.added }
        }
        else -> emptyList()
    }
    val tracks = songs.map { it.track }

    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = c.bottom)) {
            item(key = "head") {
                val title = open?.name ?: "On this phone"
                val sub = when {
                    open != null -> open.subtitle
                    !allowed -> "Your own music files"
                    !PhoneMusic.loaded -> "Looking for music…"
                    else -> "${all.size} songs · plays without internet"
                }
                val icon = when (open?.view) {
                    PhoneView.ARTISTS -> Icons.Rounded.Person
                    PhoneView.FOLDERS -> Icons.Rounded.Folder
                    PhoneView.ALBUMS -> Icons.Rounded.Album
                    else -> Icons.Rounded.PhoneAndroid
                }
                PhoneHeader(open?.thumb, title, sub, icon, open?.view == PhoneView.ARTISTS, tracks, c)
            }
            when {
                !allowed -> item(key = "ask") {
                    Column(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GText("Play the music on your phone", 20, weight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        GText(
                            "Songs you've copied to your phone, bought or recorded can play right here, even without internet. " +
                                "To find them, Glasswave needs your OK to see the music and audio files on this phone. It only reads them, and nothing leaves your phone.",
                            15, Dim,
                        )
                        if (asked) {
                            Spacer(Modifier.height(8.dp))
                            GText("If no question pops up, open Settings, tap Permissions, and allow Music and audio (or Files).", 14, Faint)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton("Allow", c.bg, accent = true, icon = Icons.Rounded.PhoneAndroid) { ask.launch(PhoneMusic.permission) }
                            if (asked) GlassButton("Settings", c.bg) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        }
                    }
                }
                !PhoneMusic.loaded -> items(6) { SkeletonRow() }
                all.isEmpty() -> item(key = "none") {
                    Centered("No music files found on this phone yet. Songs you copy into its Music folder will show up here.")
                }
                open != null -> numberedSongs(c, tracks, numbered = view == PhoneView.ALBUMS)
                else -> {
                    item(key = "tools") {
                        Column {
                            PhoneSearch(query, c) { query = it }
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(PhoneView.entries) { v -> Chip(v.label, view == v, c.bg) { view = v } }
                            }
                            if (view == PhoneView.SONGS) {
                                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(PhoneSort.entries) { s -> Chip(s.label, sort == s, c.bg) { sort = s } }
                                }
                            }
                        }
                    }
                    if (view == PhoneView.SONGS) {
                        if (songs.isEmpty()) item(key = "nomatch") { Centered("Nothing here matches \"$q\"") }
                        numberedSongs(c, tracks, numbered = false)
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
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.numberedSongs(c: ScreenCtx, tracks: List<Track>, numbered: Boolean) {
    itemsIndexed(tracks, key = { _, t -> "ph|${t.url}" }) { i, t ->
        Box(Modifier.padding(horizontal = 8.dp)) {
            TrackRow(t, t.url == c.ps.currentId, c.ps.playing, if (numbered) i + 1 else null) { c.ps.playList(tracks, i) }
        }
    }
}

@Composable
private fun PhoneHeader(thumb: String?, title: String, subtitle: String, icon: ImageVector, round: Boolean, tracks: List<Track>, c: ScreenCtx) {
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

/** Find a song, artist, album or folder on the phone. */
@Composable
private fun PhoneSearch(query: String, c: ScreenCtx, onChange: (String) -> Unit) {
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
            if (query.isEmpty()) GText("Find on this phone", 16, Faint)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
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
private fun GroupRow(g: PhoneGroup, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .pressable(0.97f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (g.view) {
            PhoneView.ARTISTS -> Icons.Rounded.Person
            PhoneView.FOLDERS -> Icons.Rounded.Folder
            else -> Icons.Rounded.Album
        }
        // Folders get a folder, not the first song's cover.
        Cover(if (g.view == PhoneView.FOLDERS) null else g.thumb, Modifier.size(54.dp), 8.dp, circle = g.view == PhoneView.ARTISTS, fallback = icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(g.name, 16, weight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            GText(g.subtitle, 13, Dim, maxLines = 1)
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 22.dp)
    }
}
