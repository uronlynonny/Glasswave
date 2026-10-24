// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/*
 * Listening stats, designed for the computer: a spotlight on your #1 song, your minutes in big
 * numbers, a chart of when you listened, and your top artists and songs side by side with bars
 * showing how far ahead each one is.
 */

private fun fmt(n: Long) = "%,d".format(n)

@Composable
fun DeskStatsPage(c: ScreenCtx) {
    var period by rememberSaveable { mutableStateOf(Stats.Period.MONTH) }
    var data by remember { mutableStateOf<Stats.Summary?>(null) }
    LaunchedEffect(period, Stats.version) { data = Stats.summary(period) }
    val accent = readable(CoverTint.accent)
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 64.dp, bottom = c.bottom + 24.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        GText("YOUR LISTENING", 12, accent, FontWeight.Bold)
                        GText("Stats", 44, weight = FontWeight.ExtraBold)
                    }
                    PeriodSwitch(period) { period = it }
                }
            }
            val d = data
            if (d == null) {
                item(key = "wait") { Box(Modifier.fillMaxWidth().height(320.dp).glass(RoundedCornerShape(28.dp), base = 0.2f, light = 0.06f)) }
                return@LazyColumn
            }
            if (d.plays == 0) {
                item(key = "none") { Centered("Nothing here yet. Songs you listen to for 30 seconds or more are counted here.") }
                return@LazyColumn
            }
            // Spotlight and the big numbers, side by side.
            item(key = "hero") {
                Row(Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    d.topSongs.firstOrNull()?.let { top -> Spotlight(c, d, top, accent, Modifier.weight(1.35f).fillMaxHeight()) }
                    Numbers(d, accent, Modifier.weight(1f).fillMaxHeight())
                }
            }
            // When you listened.
            if (d.activity.size > 1) item(key = "chart") {
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().glass(RoundedCornerShape(26.dp), base = 0.22f, light = 0.06f).padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GText("When you listened", 20, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        val best = d.activity.maxBy { it.minutes }
                        if (best.minutes > 0) GText(
                            "Busiest: ${if (period == Stats.Period.MONTH) "the ${best.label}${suffix(best.label)}" else best.label} · ${fmt(best.minutes)} min",
                            13, Dim,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    ActivityChart(d.activity, accent, Modifier.fillMaxWidth().height(170.dp))
                }
            }
            // Top artists and most played, side by side.
            item(key = "tops") {
                Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Ranking("Top artists", Modifier.weight(1f)) {
                        val max = d.topArtists.firstOrNull()?.plays ?: 1
                        d.topArtists.take(8).forEachIndexed { i, a ->
                            RankRow(i, a.thumb, true, a.name, "${fmt(a.plays.toLong())} plays", a.plays.toFloat() / max, accent) {
                                c.vm.selectTab(Tab.SEARCH)
                                c.vm.pickRecent(a.name)
                            }
                        }
                    }
                    Ranking("Most played", Modifier.weight(1f)) {
                        val max = d.topSongs.firstOrNull()?.plays ?: 1
                        val songs = d.topSongs.map { it.track }
                        d.topSongs.take(8).forEachIndexed { i, r ->
                            RankRow(i, r.track.thumb, false, r.track.title, r.track.artist, r.plays.toFloat() / max, accent, "${r.plays}×") {
                                c.ps.playList(songs, i)
                            }
                        }
                    }
                }
            }
            // Recently played, newest first.
            item(key = "recentTitle") { GText("Recently played", 20, weight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 6.dp, start = 4.dp)) }
            val timeFmt = java.text.SimpleDateFormat("EEE d MMM · HH:mm", java.util.Locale.getDefault())
            d.history.take(60).forEachIndexed { i, p ->
                item(key = "h$i${p.at}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { TrackRow(p.track, p.track.url == c.ps.currentId, c.ps.playing) { c.ps.playList(listOf(p.track), 0) } }
                        GText(timeFmt.format(java.util.Date(p.at)), 12, Faint, modifier = Modifier.padding(start = 8.dp, end = 6.dp))
                    }
                }
            }
        }
        GlassCircle(44.dp, c.bg, Modifier.padding(start = 14.dp, top = 6.dp), onClick = { c.vm.back() }) {
            Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 22.dp)
        }
    }
}

private fun suffix(day: String): String {
    val n = day.toIntOrNull() ?: return ""
    if (n in 11..13) return "th"
    return when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
}

/** This month / This year / All time, as one pill with a sliding highlight. */
@Composable
private fun PeriodSwitch(period: Stats.Period, onPick: (Stats.Period) -> Unit) {
    val items = Stats.Period.entries
    val w = 112.dp
    val x by animateFloatAsState(items.indexOf(period).toFloat(), tween(220, easing = Motion.ease), label = "period")
    Box(
        Modifier.height(40.dp).width(w * items.size + 8.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f)).border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape).padding(4.dp),
    ) {
        Box(Modifier.offset(x = w * x).width(w).fillMaxHeight().clip(CircleShape).background(Color.White.copy(alpha = 0.92f)))
        Row(Modifier.fillMaxSize()) {
            items.forEach { p ->
                Box(Modifier.width(w).fillMaxHeight().pressable(0.97f) { onPick(p) }, contentAlignment = Alignment.Center) {
                    GText(p.label, 13, if (p == period) Color.Black else Color.White.copy(alpha = 0.8f), FontWeight.SemiBold, 1)
                }
            }
        }
    }
}

/** Your #1 song, big, with its cover glowing behind it. Click to play your top songs. */
@Composable
private fun Spotlight(c: ScreenCtx, d: Stats.Summary, top: Stats.Ranked, accent: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF0C0D10)).pressable(0.99f) { c.ps.playList(d.topSongs.map { it.track }, 0) }) {
        AsyncImage(
            model = hiRes(top.track.thumb), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.55f; scaleX = 1.2f; scaleY = 1.2f },
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color(0x33000000)))))
        Row(Modifier.fillMaxSize().padding(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(hiRes(top.track.thumb), Modifier.size(220.dp), 18.dp)
            Spacer(Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                GText("YOUR #1 SONG", 12, accent, FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                GText(top.track.title, 30, weight = FontWeight.ExtraBold, maxLines = 2)
                GText(top.track.artist, 16, Color.White.copy(alpha = 0.7f), maxLines = 1)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                        Ico(Icons.Rounded.PlayArrow, Color.Black, 26.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    GText("${fmt(top.plays.toLong())} plays · ${fmt(top.ms / 60_000)} min", 14, Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

/** Minutes in big type, then plays, songs and artists. */
@Composable
private fun Numbers(d: Stats.Summary, accent: Color, modifier: Modifier) {
    Column(modifier.glass(RoundedCornerShape(28.dp), base = 0.22f, light = 0.07f).padding(26.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            GText(fmt(d.minutes), 64, accent, FontWeight.ExtraBold, 1)
            GText("minutes listened", 15, Dim)
            val hours = d.minutes / 60
            if (hours >= 1) GText("That's about ${fmt(hours)} hour${if (hours == 1L) "" else "s"} of music.", 13, Faint)
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Plays" to d.plays.toLong(), "Songs" to d.songs.toLong(), "Artists" to d.artists.toLong()).forEach { (label, n) ->
                Column(Modifier.weight(1f)) {
                    GText(fmt(n), 26, weight = FontWeight.Bold, maxLines = 1)
                    GText(label, 12, Dim)
                }
            }
        }
    }
}

/** Rounded bars for each day or month, the busiest one lit up. */
@Composable
private fun ActivityChart(bars: List<Stats.Bar>, accent: Color, modifier: Modifier) {
    val grow by animateFloatAsState(1f, tween(500, easing = Motion.ease), label = "grow")
    val max = (bars.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
    val best = bars.indexOfFirst { it.minutes == max }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = bars.size
            val slot = size.width / n
            val bw = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            bars.forEachIndexed { i, b ->
                val x = slot * i + (slot - bw) / 2
                // Empty days still show a faint stub, so the rhythm of the month is visible.
                drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                val h = (size.height * b.minutes / max * grow).coerceAtLeast(if (b.minutes > 0) bw else 0f)
                if (h > 0f) drawRoundRect(
                    if (i == best) Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.7f)), size.height - h, size.height)
                    else Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.25f)), size.height - h, size.height),
                    Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // A few labels along the bottom, so it stays readable with 31 days.
        Row(Modifier.fillMaxWidth()) {
            val every = if (bars.size > 14) 5 else 1
            bars.forEachIndexed { i, b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % every == 0 || i == bars.lastIndex) GText(b.label, 11, Faint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Ranking(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.glass(RoundedCornerShape(26.dp), base = 0.22f, light = 0.06f).padding(horizontal = 14.dp, vertical = 18.dp)) {
        GText(title, 20, weight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        content()
    }
}

/** One ranked line: number, picture, name, and a bar showing how it compares with #1. */
@Composable
private fun RankRow(i: Int, thumb: String?, circle: Boolean, title: String, sub: String, share: Float, accent: Color, tail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(0.98f, onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GText("${i + 1}", if (i < 3) 22 else 16, if (i < 3) accent else Faint, FontWeight.ExtraBold, 1, Modifier.width(34.dp))
        Cover(thumb, Modifier.size(46.dp), 8.dp, circle = circle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(title, 15, weight = FontWeight.SemiBold, maxLines = 1)
            GText(sub, 12, Dim, maxLines = 1)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0.04f, 1f)).fillMaxHeight().clip(CircleShape).background(accent.copy(alpha = if (i < 3) 0.9f else 0.5f)))
            }
        }
        if (tail != null) GText(tail, 12, Faint, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * The way into your stats from the Library: this month's minutes in big type, a little chart of
 * the month so far, and your top songs' covers fanned out.
 */
@Composable
fun StatsBanner(c: ScreenCtx) {
    var month by remember { mutableStateOf<Stats.Summary?>(null) }
    LaunchedEffect(Stats.version) { month = Stats.summary(Stats.Period.MONTH) }
    val accent = readable(CoverTint.accent)
    val m = month
    Row(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(24.dp))
            .pressable(0.99f) { c.vm.open(Route.Stats) }
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), Color(0x22000000), Color.White.copy(alpha = 0.03f))))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(220.dp)) {
            GText("YOUR STATS · THIS MONTH", 11, accent, FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            GText(if (m == null) "…" else fmt(m.minutes), 44, weight = FontWeight.ExtraBold, maxLines = 1)
            GText(if (m == null) "" else "minutes · ${fmt(m.plays.toLong())} plays", 13, Dim, maxLines = 1)
        }
        // The month so far, as a little chart.
        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp)) {
            val bars = m?.activity.orEmpty()
            if (bars.size > 1) Canvas(Modifier.fillMaxSize()) {
                val max = (bars.maxOf { it.minutes }).coerceAtLeast(1L)
                val slot = size.width / bars.size
                val bw = (slot * 0.55f).coerceAtMost(10.dp.toPx())
                bars.forEachIndexed { i, b ->
                    val h = (size.height * b.minutes / max).coerceAtLeast(if (b.minutes > 0) bw else 2.dp.toPx())
                    drawRoundRect(
                        if (b.minutes > 0) accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.1f),
                        Offset(slot * i + (slot - bw) / 2, size.height - h), Size(bw, h), CornerRadius(bw / 2),
                    )
                }
            }
        }
        // Top three covers, fanned.
        val tops = m?.topSongs?.take(3).orEmpty()
        if (tops.isNotEmpty()) {
            Box(Modifier.width(150.dp).height(96.dp)) {
                tops.reversed().forEachIndexed { k, r ->
                    val i = tops.size - 1 - k
                    Cover(
                        r.track.thumb,
                        Modifier.offset(x = (i * 27).dp).align(Alignment.CenterStart).size(96.dp - (i * 10).dp)
                            .graphicsLayer { rotationZ = i * 6f },
                        14.dp,
                    )
                }
            }
        }
        Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, Faint, 26.dp)
    }
}
