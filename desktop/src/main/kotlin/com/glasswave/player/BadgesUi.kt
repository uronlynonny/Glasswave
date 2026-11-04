// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Gold = Color(0xFFFFD166)

fun Badges.Level.icon(): ImageVector = when (this) {
    Badges.Level.FAN -> Icons.Rounded.Favorite
    Badges.Level.SUPER_FAN -> Icons.Rounded.LocalFireDepartment
    Badges.Level.TOP_FAN -> Icons.Rounded.WorkspacePremium
    Badges.Level.LEGEND -> Icons.Rounded.Diamond
}

fun Badges.Level.tint() = Color(color)

@Composable
private fun BadgeDisc(icon: ImageVector, tint: Color, size: Dp, faded: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = if (faded) 0.08f else 0.18f))
            .border(1.dp, tint.copy(alpha = if (faded) 0.15f else 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, if (faded) tint.copy(alpha = 0.4f) else tint, size * 0.56f)
    }
}

/** The small badge next to an artist's name on their page. Opens the badges page. Nothing when you have none. */
@Composable
fun ArtistBadgeIcon(c: ScreenCtx, artist: String) {
    val (_, b) = Badges.forArtist(artist) ?: return
    Box(Modifier.padding(start = 8.dp).pressable(0.9f) { c.vm.open(Route.Badges(artist)) }) {
        BadgeDisc(if (b.topListener) Icons.Rounded.EmojiEvents else b.level.icon(), if (b.topListener) Gold else b.level.tint(), 30.dp)
    }
}

/** Home's card when a month's badges have just arrived. */
@Composable
fun BadgesRevealCard(c: ScreenCtx) {
    val key = Badges.unseen ?: return
    val month = Badges.months.firstOrNull { it.key == key } ?: return
    val lead = month.artists.firstOrNull()
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .pressable(0.98f) { c.vm.open(Route.Badges(null)) }
            .clip(RoundedCornerShape(20.dp)).background(Lime.copy(alpha = 0.12f))
            .border(1.dp, Lime.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeDisc(lead?.let { if (it.topListener) Icons.Rounded.EmojiEvents else it.level.icon() } ?: Icons.Rounded.EmojiEvents, lead?.let { if (it.topListener) Gold else it.level.tint() } ?: Gold, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText("Your ${Badges.label(key).substringBefore(" ")} badges are here", 16, weight = FontWeight.Bold, maxLines = 1)
            GText(
                "${month.count} badge${if (month.count == 1) "" else "s"}" + (lead?.let { " · ${if (it.topListener) "Top listener" else it.level.title} of ${it.artist}" } ?: ""),
                13, Dim, maxLines = 1,
            )
        }
    }
}

/** Your badges: what each one means, the one you have for [artist] (when opened from their page), and every month's. */
@Composable
fun BadgesPage(c: ScreenCtx, artist: String?) {
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { Badges.markSeen() }
    // How this month is going for the artist, toward the badge on the 1st.
    val soFar by produceState(0 to 0L, artist, Stats.version) {
        value = artist?.let { runCatching { Badges.thisMonth(it) }.getOrNull() } ?: (0 to 0L)
    }
    val mine = artist?.let { Badges.forArtist(it) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = c.bottom)) {
            item(key = "title") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    GText("Your badges", 32, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    GText("Given out on the 1st of every month, from what you listened to that month.", 14, Dim)
                }
            // The artist you came from, in the same block as the title so it always opens at the top.
            if (artist != null) {
                Column(Modifier.padding(16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), base = 0.22f, light = 0.08f).padding(18.dp)) {
                    if (mine != null) {
                        val (month, b) = mine
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BadgeDisc(b.level.icon(), b.level.tint(), 64.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                GText("You're a ${b.level.title.lowercase()}", 22, weight = FontWeight.Bold, maxLines = 1)
                                GText("of ${b.artist} · ${Badges.label(month)}", 14, Dim, maxLines = 1)
                                GText("${b.plays} plays · ${hm(b.minutes)}", 13, Faint, maxLines = 1)
                            }
                        }
                        if (b.topListener) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BadgeDisc(Icons.Rounded.EmojiEvents, Gold, 30.dp)
                                Spacer(Modifier.width(10.dp))
                                GText("And your top listener badge: ${b.artist} was your most played artist that month", 13, Dim)
                            }
                        }
                    } else {
                        GText("No badge for ${Badges.artistOf(artist)} yet", 20, weight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        GText("Badges for this month arrive on ${Badges.nextAwardDay()}.", 14, Dim)
                    }
                    // This month so far, and what's next.
                    val (plays, minutes) = soFar
                    val level = Badges.Level.of(plays, minutes)
                    val next = Badges.Level.entries.firstOrNull { level == null || it.ordinal > level.ordinal }
                    Spacer(Modifier.height(16.dp))
                    GText("This month so far", 13, Faint, FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    GText(
                        "$plays plays · ${hm(minutes)}" + (level?.let { " · on track for ${it.title}" } ?: ""),
                        15, maxLines = 1,
                    )
                    if (next != null) {
                        val f = maxOf(plays.toFloat() / next.plays, minutes.toFloat() / next.minutes).coerceIn(0f, 1f)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.12f))) {
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(next.tint()))
                        }
                        Spacer(Modifier.height(4.dp))
                        GText("Next: ${next.title} (${next.about.substringBefore(" of ")})", 12, Faint, maxLines = 1)
                    }
                }
            }
            }
            item(key = "levels") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SectionTitle("What they mean", Modifier.padding(start = 4.dp))
                    Badges.Level.entries.reversed().forEach { l ->
                        LevelRow(l.icon(), l.tint(), l.title, l.about.replaceFirstChar { it.uppercase() }, you = mine?.second?.level == l)
                    }
                    LevelRow(Icons.Rounded.EmojiEvents, Gold, "Top listener", "Your most played artist of the month", you = mine?.second?.topListener == true)
                    LevelRow(Icons.Rounded.CalendarMonth, Color.White, "Streak", "Listening ${Badges.STREAKS.joinToString(", ")} or more days in a row", you = false)
                    LevelRow(Icons.Rounded.Headphones, Color.White, "Hours", "${Badges.HOURS.joinToString(", ")} hours of music in a month", you = false)
                }
            }
            if (Badges.months.isEmpty()) item(key = "none") {
                Centered("Your first badges arrive on ${Badges.nextAwardDay()}. Keep listening.")
            }
            Badges.months.forEach { m ->
                item(key = "m-${m.key}") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SectionTitle(Badges.label(m.key), Modifier.padding(start = 4.dp))
                        val tiles = buildList<@Composable () -> Unit> {
                            m.artists.firstOrNull { it.topListener }?.let { b -> add { Tile(Icons.Rounded.EmojiEvents, Gold, "Top listener", b.artist) } }
                            m.artists.forEach { b -> add { Tile(b.level.icon(), b.level.tint(), b.level.title, b.artist) } }
                            if (m.streak > 0) add { Tile(Icons.Rounded.CalendarMonth, Color.White, "${m.streak}-day streak", "Days in a row") }
                            if (m.hours > 0) add { Tile(Icons.Rounded.Headphones, Color.White, "${m.hours} hours", "Listened") }
                        }
                        tiles.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { t -> Box(Modifier.weight(1f)) { t() } }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item(key = "note") {
                GText(
                    "Badges come from your own listening on Glasswave (and your other devices when you're signed in). They don't compare you with other people.",
                    12, Faint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 520.dp),
                )
            }
        }
        GlassCircle(44.dp, c.bg, Modifier.padding(start = 14.dp, top = 6.dp), onClick = { c.vm.back() }) {
            Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 22.dp)
        }
    }
}

@Composable
private fun LevelRow(icon: ImageVector, tint: Color, title: String, about: String, you: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (you) tint.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (you) tint.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeDisc(icon, tint, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(title, 16, weight = FontWeight.SemiBold, maxLines = 1)
            GText(about, 13, Dim, maxLines = 2)
        }
        if (you) GText("You", 12, tint, FontWeight.Bold, 1, Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.18f)).padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
private fun Tile(icon: ImageVector, tint: Color, title: String, sub: String) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), base = 0.2f, light = 0.06f).padding(12.dp)) {
        BadgeDisc(icon, tint, 36.dp)
        Spacer(Modifier.height(8.dp))
        GText(title, 15, weight = FontWeight.SemiBold, maxLines = 1)
        GText(sub, 13, Dim, maxLines = 1)
    }
}

private fun hm(minutes: Long): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
