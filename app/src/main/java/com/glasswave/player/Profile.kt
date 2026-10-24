// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Your YouTube Music account (name and picture), remembered so it shows straight away. */
object Profile {
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var name by mutableStateOf<String?>(null); private set
    var handle by mutableStateOf<String?>(null); private set
    var photo by mutableStateOf<String?>(null); private set
    private var fetching = false

    fun init(context: Context) {
        prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
        name = prefs.getString("name", null)
        handle = prefs.getString("handle", null)
        photo = prefs.getString("photo", null)
    }

    /** Fetches the account again (on launch and after signing in). */
    fun refresh() {
        if (fetching || !Accounts.ytSignedIn) return
        fetching = true
        scope.launch {
            try {
                YtMusicApi.account()?.let { a ->
                    name = a.name
                    handle = a.handle
                    photo = a.photo
                    prefs.edit().putString("name", a.name).putString("handle", a.handle).putString("photo", a.photo).apply()
                }
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Account lookup failed", e)
            } finally {
                fetching = false
            }
        }
    }

    fun clear() {
        name = null; handle = null; photo = null
        prefs.edit().clear().apply()
    }
}

/* ---------------- Home: the two round buttons on the top right ---------------- */

/**
 * The update button: glows and shows a dot when a new version is out (tap to download and
 * install it), shows a filling ring while downloading, and otherwise checks for one when tapped.
 */
@Composable
fun UpdateButton(size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val ready = Updater.available
    val p = Updater.progress
    // Says the result of a check the person asked for (not on every visit to Home).
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(Updater.checking) {
        if (asked && !Updater.checking) {
            asked = false
            val msg = if (Updater.available != null) "Glasswave ${Updater.available?.version} is ready. Tap again to install." else Updater.status
            msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
        }
    }
    val pulse = if (ready != null && p == null) {
        val t = rememberInfiniteTransition(label = "update")
        t.animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .pressable(0.88f) {
                when {
                    p != null -> Unit
                    ready != null -> Updater.install(ctx)
                    else -> { asked = true; Updater.check(tell = true); Toast.makeText(ctx, "Checking for updates…", Toast.LENGTH_SHORT).show() }
                }
            }
            .clip(CircleShape)
            .background(if (ready != null) CoverTint.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.09f))
            .border(1.dp, if (ready != null) CoverTint.accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (p != null) DownloadRing(p, size * 0.55f, CoverTint.accent, 2.5.dp)
        else Ico(Icons.Rounded.SystemUpdate, if (ready != null) CoverTint.accent else Color.White.copy(alpha = 0.85f), size * 0.5f)
        if (ready != null && p == null) {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(9.dp).clip(CircleShape).background(CoverTint.accent))
        }
    }
}

/** Your YouTube Music picture (or a plain person icon when signed out). */
@Composable
fun ProfileButton(size: Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .pressable(0.88f, onClick = onClick)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.5.dp, CoverTint.accent.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Ico(Icons.Rounded.Person, Color.White.copy(alpha = 0.8f), size * 0.55f)
        Profile.photo?.let { AsyncImage(it, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop) }
    }
}

/* ---------------- The profile sheet ---------------- */

/**
 * Tapping your picture: who you're signed in as, your month in music at a glance, and quick ways
 * to your liked songs, stats, a fresh sync, and account settings.
 */
@Composable
fun ProfileSheet(c: ScreenCtx, page: Backdrop, open: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(smooth(150)), exit = fadeOut(smooth(150))) {
        Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            open,
            enter = slideInVertically(bouncy()) { it / 2 } + fadeIn(smooth(180)),
            exit = slideOutVertically(smooth(180)) { it / 2 } + fadeOut(smooth(150)),
        ) {
            ProfileBody(c, page, onClose)
        }
    }
}

@Composable
private fun BoxScope.ProfileBody(c: ScreenCtx, page: Backdrop, onClose: () -> Unit) {
    val vm = c.vm
    val month by produceState<Stats.Summary?>(null, Stats.version) { value = runCatching { Stats.summary(Stats.Period.MONTH) }.getOrNull() }
    fun go(action: () -> Unit) { onClose(); action() }
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(10.dp)
            .navigationBarsPadding()
            .liquidGlass(page, 30.dp, Color(0xE00E1110), blur = 24.dp, depth = 12.dp, bend = 14.dp, rim = CoverTint.accent)
            .clickable(remember { MutableInteractionSource() }, null) { }
            .padding(20.dp),
    ) {
        // Who you are
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileButton(64.dp) { }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (vm.ytSignedIn) {
                    GText(Profile.name ?: "YouTube Music", 22, weight = FontWeight.Bold, maxLines = 1)
                    GText(Profile.handle ?: "Signed in to YouTube Music", 14, Dim, maxLines = 1)
                } else {
                    GText("Not signed in", 22, weight = FontWeight.Bold, maxLines = 1)
                    GText("Sign in to get your mixes and playlists", 14, Dim, maxLines = 1)
                }
            }
        }

        // Your month, in one line
        month?.let { m ->
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().pressable(0.98f) { go { vm.open(Route.Stats) } }
                    .clip(RoundedCornerShape(18.dp)).background(CoverTint.accent.copy(alpha = 0.14f)).padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MiniStat("${m.minutes}", "minutes")
                MiniStat("${m.plays}", "plays")
                MiniStat(m.topSongs.firstOrNull()?.track?.title ?: "—", "top song", wide = true)
            }
        }

        Spacer(Modifier.height(10.dp))
        if (!vm.ytSignedIn) {
            ProfileRow(Icons.Rounded.Person, "Sign in to YouTube Music", accent = true) { go { vm.showYtLogin = true } }
        }
        ProfileRow(Icons.Rounded.Favorite, "Liked songs") {
            go { vm.open(Route.Saved(PlaylistInfo(Library.LIKED_ID, "Liked songs", Source.LOCAL, null, 0))) }
        }
        ProfileRow(Icons.Rounded.BarChart, "Listening stats") { go { vm.open(Route.Stats) } }
        val syncNote = if (vm.syncing) "Syncing…" else Accounts.lastSync.takeIf { it > 0 }?.let { "Last synced ${ago(it)}" }
        ProfileRow(Icons.Rounded.Sync, "Sync playlists now", syncNote) { if (!vm.syncing) vm.syncAll() }
        ProfileRow(Icons.Rounded.Settings, "Accounts & settings") { go { vm.openSettings("ACCOUNTS") } }
        Spacer(Modifier.height(6.dp))
        GText("Glasswave ${Updater.currentVersion()} · ${Credits.LINE}", 12, Faint, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MiniStat(value: String, label: String, wide: Boolean = false) {
    Column(if (wide) Modifier.weight(1f).padding(start = 12.dp) else Modifier.padding(end = 6.dp)) {
        GText(value, if (wide) 15 else 20, weight = FontWeight.Bold, maxLines = 1)
        GText(label, 12, Dim, maxLines = 1)
    }
}

@Composable
private fun ProfileRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, note: String? = null, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(0.98f, onClick = onClick).clip(RoundedCornerShape(14.dp)).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                .background(if (accent) CoverTint.accent else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, if (accent) Color.Black else Color.White, 20.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GText(title, 16, weight = FontWeight.SemiBold, maxLines = 1)
            note?.let { GText(it, 12, Dim, maxLines = 1) }
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 22.dp)
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

/* ---------------- Player: the lyrics button ---------------- */

/**
 * The lyrics button, drawn just for Glasswave: three lyric lines in a round button, with the top
 * line filling up in the cover's colour while the song plays, like a line being sung.
 */
@Composable
fun LyricsButton(playing: Boolean, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill = if (playing && Settings.animations) {
        val t = rememberInfiniteTransition(label = "lyricsBtn")
        // 0..1 fills the line, 1..1.2 fades the filled part away, so it never snaps back to empty.
        t.animateFloat(0f, 1.2f, infiniteRepeatable(tween(2200, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "fill")
    } else null
    Box(
        modifier
            .size(44.dp)
            .pressable(0.86f, onClick = onClick)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.2f))
            .border(1.dp, accent.copy(alpha = 0.65f), CircleShape)
            .drawBehind {
                val w = size.width
                val h = size.height
                val stroke = h * 0.075f
                val left = w * 0.27f
                val lines = listOf(0.36f to 0.46f, 0.5f to 0.36f, 0.64f to 0.26f) // (y, length) as parts of the button
                lines.forEachIndexed { i, (y, len) ->
                    val start = androidx.compose.ui.geometry.Offset(left, h * y)
                    val end = androidx.compose.ui.geometry.Offset(left + w * len, h * y)
                    drawLine(Color.White.copy(alpha = if (i == 0) 0.45f else 0.8f), start, end, stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                    if (i == 0) {
                        val v = fill?.value ?: 1f
                        // Ease the fill so it starts and lands softly.
                        val k = v.coerceAtMost(1f).let { it * it * (3 - 2 * it) }
                        val fade = if (v > 1f) 1f - (v - 1f) / 0.2f else 1f
                        if (k > 0.01f && fade > 0f) {
                            drawLine(accent.copy(alpha = accent.alpha * fade), start, androidx.compose.ui.geometry.Offset(left + w * len * k, h * y), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                }
            },
    )
}

/* ---------------- What's new ---------------- */

/**
 * After an update: a small glass card slides down from the top with what changed, then slides
 * away on its own after a few seconds (or straight away when tapped). It's shown once per version.
 */
@Composable
fun BoxScope.WhatsNewCard(page: Backdrop) {
    val news = Updater.whatsNew
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(news) {
        if (news == null) return@LaunchedEffect
        kotlinx.coroutines.delay(2600) // after the opening animation
        shown = true
        kotlinx.coroutines.delay(9000)
        shown = false
        kotlinx.coroutines.delay(400)
        Updater.dismissWhatsNew()
    }
    AnimatedVisibility(
        shown && news != null,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = slideInVertically(bouncy()) { -it } + fadeIn(smooth(200)),
        exit = slideOutVertically(smooth(220)) { -it } + fadeOut(smooth(180)),
    ) {
        var last by remember { mutableStateOf(news) }
        if (news != null) last = news
        val n = last ?: return@AnimatedVisibility
        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .pressable(0.98f) { shown = false }
                .liquidGlass(page, 22.dp, Color(0xD90E1110), blur = 18.dp, depth = 10.dp, bend = 10.dp, rim = CoverTint.accent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(CoverTint.accent))
                Spacer(Modifier.width(8.dp))
                GText("What's new in ${n.version}", 15, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                GText("Got it", 13, CoverTint.accent, FontWeight.SemiBold, 1)
            }
            Spacer(Modifier.height(6.dp))
            n.lines.forEach { line ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    GText("•", 13, Dim, maxLines = 1, modifier = Modifier.padding(end = 6.dp))
                    GText(line, 13, Dim, maxLines = 2)
                }
            }
        }
    }
}

/* ---------------- Player: the like button ---------------- */

/**
 * The heart next to the song title: adds the song to Liked songs (and likes it on YouTube Music
 * when you're signed in). It pops when you tap it and fills in the cover's colour.
 */
@Composable
fun LikeButton(ps: PlayerState, accent: Color) {
    val id = ps.currentId
    val liked = id != null && Library.isLiked(id)
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .pressable(0.86f) {
                val t = ps.currentTrack() ?: return@pressable
                val nowLiked = !Library.isLiked(t.url)
                Library.toggleLike(t)
                Menus.say(if (nowLiked) "Added to Liked songs" else "Removed from Liked songs")
                if (nowLiked) scope.launch {
                    pop.snapTo(0.7f)
                    pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Ico(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) accent else Color.White.copy(alpha = 0.85f),
            26.dp,
        )
    }
}
