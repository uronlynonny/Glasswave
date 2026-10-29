// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A playlist's Mix, like Spotify's: Mix on or off, how every song blends into the next, and each
 * change between two songs on its own (style, length, how early it starts). ▶ plays the last
 * seconds of a song into the next, so you hear the change straight away.
 */
@Composable
fun MixPage(c: ScreenCtx, p: PlaylistInfo) {
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(p.id, Library.version) { tracks = Library.tracks(p.id) }
    @Suppress("UNUSED_VARIABLE") val v = PlaylistMix.version // redraw on changes
    val mix = PlaylistMix.get(p.id)
    val list = tracks.orEmpty()
    var open by remember { mutableIntStateOf(-1) }

    fun store(on: Boolean = mix.on, all: PlaylistMix.Transition = mix.all, pairs: Map<String, PlaylistMix.Transition> = mix.pairs) =
        PlaylistMix.save(p.id, PlaylistMix.Mix(on, all, pairs, list.map { it.url }.toSet()))

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(top = 56.dp, bottom = c.bottom)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    GText("Mix", 30, weight = FontWeight.ExtraBold)
                    GText(p.name, 15, Dim, maxLines = 1)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), base = 0.22f, light = 0.08f)
                            .pressable(0.98f) { store(on = !mix.on) }.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            GText("Mix this playlist", 16, weight = FontWeight.SemiBold)
                            GText("When you play it, each song blends into the next like a DJ set.", 13, Dim)
                        }
                        Spacer(Modifier.width(12.dp))
                        Toggle(mix.on) { store(on = it) }
                    }
                    Spacer(Modifier.height(20.dp))
                    GText("Every transition", 18, weight = FontWeight.Bold)
                    GText("How songs blend, unless you change one below.", 13, Faint)
                    Spacer(Modifier.height(8.dp))
                    TransitionEditor(c, mix.all) { store(all = it) }
                    Spacer(Modifier.height(22.dp))
                    GText("Transitions", 18, weight = FontWeight.Bold)
                    GText(if (list.size < 2) "Add at least two songs to mix them." else "Tap one to change just that blend. ▶ plays it.", 13, Faint)
                    Spacer(Modifier.height(6.dp))
                }
            }
            itemsIndexed(list.zipWithNext(), key = { i, _ -> "t$i" }) { i, (a, b) ->
                val key = PlaylistMix.pairKey(a.url, b.url)
                val own = mix.pairs[key]
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp), base = 0.18f, light = 0.06f)) {
                    Row(
                        Modifier.fillMaxWidth().pressable(0.99f) { open = if (open == i) -1 else i }.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(a.thumb, Modifier.size(38.dp), 6.dp)
                        Spacer(Modifier.width(8.dp))
                        GText("→", 16, Faint)
                        Spacer(Modifier.width(8.dp))
                        Cover(b.thumb, Modifier.size(38.dp), 6.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            GText("${a.title}  →  ${b.title}", 14, weight = FontWeight.Medium, maxLines = 1)
                            GText(if (own != null) own.summary else "Same as every transition", 12, if (own != null) CoverTint.accent else Dim, maxLines = 1)
                        }
                        // Hear it: the end of the first song blending into the second.
                        Box(Modifier.size(40.dp).pressable(0.85f) { preview(c, list, i, own ?: mix.all) }, contentAlignment = Alignment.Center) {
                            Ico(Icons.Rounded.PlayArrow, Color.White, 22.dp)
                        }
                    }
                    if (open == i) {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                            TransitionEditor(c, own ?: mix.all) { t -> store(on = true, pairs = mix.pairs + (key to t)) }
                            if (own != null) {
                                GText("Use the same as every transition", 13, CoverTint.accent, FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 10.dp).pressable { store(pairs = mix.pairs - key) })
                            }
                        }
                    }
                }
            }
        }
        GlassCircle(44.dp, c.bg, Modifier.padding(start = 14.dp, top = 6.dp), onClick = { c.vm.back() }) {
            Ico(Icons.AutoMirrored.Rounded.ArrowBack, Color.White, 22.dp)
        }
    }
}

/** Plays song [i] from just before its blend, on through the playlist, so the change can be heard. */
private fun preview(c: ScreenCtx, list: List<Track>, i: Int, t: PlaylistMix.Transition) {
    val secs = list[i].seconds
    val lead = (t.earlySec + (if (t.seconds > 0) t.seconds else 10) + 8) * 1000L
    val start = if (secs > 0) (secs * 1000 - lead).coerceAtLeast(0) else 0L
    c.ps.playList(list, i, startMs = start)
    Menus.say("Playing the blend")
}

/** Style, length and start of one transition. */
@Composable
private fun TransitionEditor(c: ScreenCtx, t: PlaylistMix.Transition, onChange: (PlaylistMix.Transition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GText("Style", 13, Dim, FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaylistMix.Style.entries.forEach { s -> item { Chip(s.label, t.style == s, c.bg) { onChange(t.copy(style = s)) } } }
        }
        GText(t.style.detail, 12, Faint)
        GText("Length of the blend", 13, Dim, FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 3, 6, 9, 12, 16).forEach { s -> item { Chip(if (s == 0) "Auto (by tempo)" else "${s}s", t.seconds == s, c.bg) { onChange(t.copy(seconds = s)) } } }
        }
        GText("When it starts", 13, Dim, FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 10, 20, 30, 45).forEach { s -> item { Chip(if (s == 0) "At the end" else "${s}s early", t.earlySec == s, c.bg) { onChange(t.copy(earlySec = s)) } } }
        }
    }
}
