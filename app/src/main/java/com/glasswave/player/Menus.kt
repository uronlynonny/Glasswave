// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Menus and small popups drawn above everything: the song menu (⋮ on a song), the playlist menu
 * (long-press a playlist), the name box for new / renamed playlists, and short "Added to queue" notes.
 */
object Menus {
    data class SongTarget(val track: Track, val playlistId: String?)
    class NameRequest(val title: String, val initial: String, val confirm: String, val onDone: (String) -> Unit)

    var song by mutableStateOf<SongTarget?>(null)
        private set
    var playlist by mutableStateOf<PlaylistInfo?>(null)
        private set
    var naming by mutableStateOf<NameRequest?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var toastId by mutableIntStateOf(0)
        private set

    fun openSong(t: Track, playlistId: String? = null) { song = SongTarget(t, playlistId) }
    fun closeSong() { song = null }
    fun openPlaylist(p: PlaylistInfo) { playlist = p }
    fun closePlaylist() { playlist = null }
    fun askName(r: NameRequest) { naming = r }
    fun closeName() { naming = null }
    fun say(text: String) {
        toast = text
        toastId++
    }
    internal fun clearToast() { toast = null }

    /** Creates a playlist after asking for its name, optionally with a first song in it. */
    fun newPlaylist(first: Track? = null) = askName(
        NameRequest("New playlist", "", "Create") { name ->
            Library.createPlaylist(name, first)
            say(if (first != null) "Added to $name" else "Created $name")
        },
    )

    val anyOpen get() = song != null || playlist != null || naming != null
}

/** All of [Menus], drawn over the app. [open] opens an album or artist page. */
@Composable
fun BoxScope.MenuLayer(ps: PlayerState, backdrop: Backdrop, open: (MusicItem) -> Unit) {
    val sheetOpen = Menus.song != null || Menus.playlist != null
    AnimatedVisibility(sheetOpen, enter = fadeIn(smooth(150)), exit = fadeOut(smooth(150))) {
        Box(
            Modifier.fillMaxSize().background(Color(0x88000000))
                .clickable(remember { MutableInteractionSource() }, null) { Menus.closeSong(); Menus.closePlaylist() },
        )
    }
    AnimatedVisibility(
        sheetOpen,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(smooth(220)) { it / 3 } + fadeIn(smooth(180)),
        exit = slideOutVertically(smooth(180)) { it / 3 } + fadeOut(smooth(150)),
    ) {
        // Keep the last one on screen while it slides away.
        var lastSong by remember { mutableStateOf<Menus.SongTarget?>(null) }
        var lastList by remember { mutableStateOf<PlaylistInfo?>(null) }
        Menus.song?.let { lastSong = it; lastList = null }
        Menus.playlist?.let { lastList = it; lastSong = null }
        MenuFrame(backdrop) {
            lastSong?.let { SongMenuBody(it, ps, open) }
            lastList?.let { PlaylistMenuBody(it) }
        }
    }
    Menus.naming?.let { NameDialog(it, backdrop) }
    Toast(Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun MenuFrame(backdrop: Backdrop, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .navigationBarsPadding()
            .liquidGlass(backdrop, 28.dp, Color(0xFF0E0F13).copy(alpha = 0.86f), blur = 24.dp, depth = 12.dp, bend = 14.dp)
            .clickable(remember { MutableInteractionSource() }, null) { }
            .padding(vertical = 14.dp),
    ) { content() }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, tint: Color = Color.White, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(0.98f, onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, tint, 24.dp)
        Spacer(Modifier.width(18.dp))
        GText(text, 16, Color.White, FontWeight.Medium, 1, Modifier.weight(1f))
        trailing?.invoke()
    }
}

private enum class SongPage { MAIN, PICK }

@Composable
private fun SongMenuBody(target: Menus.SongTarget, ps: PlayerState, open: (MusicItem) -> Unit) {
    val t = target.track
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember(target) { mutableStateOf(SongPage.MAIN) }
    var looking by remember(target) { mutableStateOf<String?>(null) }

    fun goTo(kind: String) {
        if (looking != null) return
        looking = kind
        scope.launch {
            val links = try {
                val id = withContext(Dispatchers.IO) { YtRepo.videoIdFor(t.url) }
                id?.let { YtMusicApi.songLinks(it) }
            } catch (e: Exception) {
                null
            }
            looking = null
            val item: MusicItem? = if (kind == "album") links?.album else links?.artists?.firstOrNull()
            if (item == null) {
                Menus.say(if (kind == "album") "Couldn't find the album" else "Couldn't find the artist")
            } else {
                Menus.closeSong()
                open(item)
            }
        }
    }

    Column {
        // The song itself
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.thumb, Modifier.size(52.dp), 8.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                GText(t.title, 16, weight = FontWeight.SemiBold, maxLines = 1)
                GText(t.artist, 13, Dim, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
        AnimatedContent(page, transitionSpec = { fadeIn(smooth(150)) togetherWith fadeOut(smooth(100)) }, label = "songMenu") { p ->
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                when (p) {
                    SongPage.MAIN -> {
                        val liked = Library.isLiked(t.url)
                        MenuRow(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (liked) "Remove from Liked songs" else "Add to Liked songs",
                            if (liked) Lime else Color.White,
                        ) {
                            Library.toggleLike(t)
                            Menus.say(if (liked) "Removed from Liked songs" else "Added to Liked songs")
                            Menus.closeSong()
                        }
                        MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { page = SongPage.PICK }
                        val pid = target.playlistId
                        if (pid != null && Library.info(pid)?.source == Source.LOCAL) {
                            MenuRow(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist") {
                                Library.remove(pid, t.url)
                                Menus.say("Removed from ${Library.info(pid)?.name ?: "playlist"}")
                                Menus.closeSong()
                            }
                        }
                        MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") {
                            ps.addToQueue(t)
                            Menus.say("Added to queue")
                            Menus.closeSong()
                        }
                        MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") {
                            ps.playNextTrack(t)
                            Menus.say("Plays next")
                            Menus.closeSong()
                        }
                        val pct = Downloads.progress[t.url]
                        val saved = Downloads.isDownloaded(t.url)
                        MenuRow(
                            if (saved) Icons.Rounded.Delete else Icons.Rounded.Download,
                            when {
                                saved -> "Remove download"
                                pct != null -> "Downloading ${(pct * 100).toInt()}%"
                                else -> "Download"
                            },
                            trailing = if (pct != null) ({ DownloadRing(pct, 22.dp, CoverTint.accent, 2.5.dp) }) else null,
                        ) {
                            when {
                                saved -> { Downloads.remove(t.url); Menus.say("Download removed") }
                                pct == null -> { Downloads.download(t); Menus.say("Downloading") }
                            }
                        }
                        MenuRow(
                            Icons.Rounded.Album, "Go to album",
                            trailing = if (looking == "album") ({ Spinner() }) else null,
                        ) { goTo("album") }
                        MenuRow(
                            Icons.Rounded.Person, "Go to artist",
                            trailing = if (looking == "artist") ({ Spinner() }) else null,
                        ) { goTo("artist") }
                        MenuRow(Icons.Rounded.Share, "Share") {
                            val link = if (t.url.startsWith("gwmatch")) {
                                "https://music.youtube.com/search?q=" + android.net.Uri.encode("${t.title} ${t.artist}")
                            } else t.url
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${t.title} · ${t.artist}\n$link")
                            ctx.startActivity(Intent.createChooser(send, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            Menus.closeSong()
                        }
                    }
                    SongPage.PICK -> {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).pressable(0.85f) { page = SongPage.MAIN }, contentAlignment = Alignment.Center) {
                                Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 22.dp)
                            }
                            GText("Add to playlist", 17, weight = FontWeight.SemiBold)
                        }
                        MenuRow(Icons.Rounded.Add, "New playlist", Lime) {
                            Menus.closeSong()
                            Menus.newPlaylist(t)
                        }
                        Library.ownPlaylists().filter { it.id != Library.LIKED_ID }.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().pressable(0.98f) {
                                    val added = Library.add(p.id, t)
                                    Menus.say(if (added) "Added to ${p.name}" else "Already in ${p.name}")
                                    Menus.closeSong()
                                }.padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(p.thumb, Modifier.size(44.dp), 8.dp, fallback = Icons.AutoMirrored.Rounded.QueueMusic)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    GText(p.name, 15, weight = FontWeight.Medium, maxLines = 1)
                                    GText("${p.count} songs", 12, Dim)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Long-press a playlist in the Library: pin it, and rename or delete your own. */
@Composable
private fun PlaylistMenuBody(p: PlaylistInfo) {
    Column {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(p.thumb, Modifier.size(52.dp), 8.dp, fallback = Icons.AutoMirrored.Rounded.QueueMusic)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                GText(p.name, 16, weight = FontWeight.SemiBold, maxLines = 1)
                GText("${p.source.label} · ${p.count} songs", 13, Dim, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
        val pinned = Library.isPinned(p.id)
        MenuRow(Icons.Rounded.PushPin, if (pinned) "Unpin" else "Pin to top", if (pinned) Lime else Color.White) {
            Library.togglePin(p.id)
            Menus.say(if (pinned) "Unpinned" else "Pinned")
            Menus.closePlaylist()
        }
        if (p.source == Source.LOCAL && p.id != Library.LIKED_ID) {
            MenuRow(Icons.Rounded.Edit, "Rename") {
                Menus.closePlaylist()
                Menus.askName(Menus.NameRequest("Rename playlist", p.name, "Save") { Library.rename(p.id, it) })
            }
        }
        if (p.source == Source.LOCAL) {
            MenuRow(Icons.Rounded.Delete, "Delete playlist", Color(0xFFFF8A8A)) {
                Library.delete(p.id)
                Menus.say("Deleted ${p.name}")
                Menus.closePlaylist()
            }
        }
        MenuRow(Icons.Rounded.Download, "Download all") {
            Menus.closePlaylist()
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { Downloads.downloadAll(Library.tracks(p.id)) }
            Menus.say("Downloading ${p.name}")
        }
    }
}

@Composable
private fun Spinner() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing)), label = "a")
    DownloadRing(0.3f, 20.dp, Color.White, 2.dp, Modifier.graphicsLayer { rotationZ = a })
}

/** Asks for a playlist name. */
@Composable
private fun NameDialog(r: Menus.NameRequest, backdrop: Backdrop) {
    var text by remember(r) { mutableStateOf(r.initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(r) { runCatching { focus.requestFocus() } }
    fun done() {
        if (text.isBlank()) return
        r.onDone(text.trim())
        Menus.closeName()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) { Menus.closeName() }.imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth()
                .liquidGlass(backdrop, 26.dp, Color(0xFF0E0F13).copy(alpha = 0.9f), blur = 24.dp, depth = 12.dp, bend = 14.dp)
                .clickable(remember { MutableInteractionSource() }, null) { }
                .padding(20.dp),
        ) {
            GText(r.title, 20, weight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) GText("Playlist name", 16, Faint)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(60) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Lime),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Cancel", backdrop, Modifier.weight(1f)) { Menus.closeName() }
                GlassButton(r.confirm, backdrop, Modifier.weight(1f), accent = true) { done() }
            }
        }
    }
}

/** A short note near the bottom, e.g. "Added to queue". */
@Composable
private fun Toast(modifier: Modifier) {
    val text = Menus.toast
    LaunchedEffect(Menus.toastId) {
        if (Menus.toast != null) {
            // Longer notes stay up longer, so there's time to read them.
            delay((1800L + (Menus.toast?.length ?: 0) * 35L).coerceAtMost(6000L))
            Menus.clearToast()
        }
    }
    AnimatedVisibility(
        text != null,
        modifier = modifier.navigationBarsPadding().padding(bottom = 170.dp),
        enter = fadeIn(smooth(150)) + slideInVertically(smooth(200)) { it / 2 },
        exit = fadeOut(smooth(200)),
    ) {
        var last by remember { mutableStateOf("") }
        if (text != null) last = text
        Box(
            Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xEE1B1C20))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) { GText(last, 14, Color.White, FontWeight.Medium, 3) }
    }
}
