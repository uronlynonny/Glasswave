// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.border
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The card's fill: a colour from the cover, kept dark enough for white words. */
private fun cardColor(colors: CoverColors): Color {
    var c = lerp(colors.a, colors.b, 0.5f)
    repeat(6) { if (c.luminance() > 0.16f) c = lerp(c, Color.Black, 0.18f) }
    return c.copy(alpha = 1f)
}

/**
 * The lyrics card under the full player (scroll down to see it, like Spotify's): the song's lines
 * on a colour from the cover, the sung one bright, moving along as the song plays. Tap a line to
 * jump there; the corner button (or the header) opens the full lyrics.
 * Songs without lyrics show no card at all, so the player doesn't scroll for them.
 */
@Composable
fun LyricsCard(
    ps: PlayerState,
    colors: CoverColors,
    height: Dp,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onExpand: () -> Unit,
) {
    val state = rememberLyrics(ps)
    val ly = (state as? Load.Done)?.value
    val show = state is Load.Loading || (ly != null && ly.lines.any { it.text.isNotBlank() })
    // Where the card is on screen, so its background lines up with the full lyrics page's.
    var at by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val root = androidx.compose.ui.platform.LocalView.current.rootView
    // Growing or shrinking smoothly also slides the page back up when a song has no lyrics.
    Box(Modifier.fillMaxWidth().animateContentSize(smooth(300)), contentAlignment = Alignment.TopCenter) {
        if (!show) return@Box
        Box(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)
                .height(height)
                // Where it sits, so the full lyrics can grow out of it (and shrink back into it).
                .onGloballyPositioned { onBounds(it.boundsInRoot()); at = it.positionInRoot() }
                .clip(RoundedCornerShape(22.dp))
                .border(
                    0.8.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.14f))),
                    RoundedCornerShape(22.dp),
                ),
        ) {
            // The same moving cover-art background as the full lyrics page, lined up with it, so the
            // card looks like a window onto that page and opening it is one smooth blend.
            androidx.compose.ui.layout.Layout(
                content = { LyricsBackdrop(ps.art, Settings.glass && Settings.animations) },
                modifier = Modifier.matchParentSize(),
            ) { measurables, constraints ->
                val w = root.width.takeIf { it > 0 } ?: constraints.maxWidth
                val h = root.height.takeIf { it > 0 } ?: constraints.maxHeight
                val page = measurables.first().measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) { page.place(-at.x.toInt(), -at.y.toInt()) }
            }
            Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null, onClick = onExpand)
                    .padding(start = 20.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GText("Lyrics", 16, Color.White, FontWeight.Bold, 1, Modifier.weight(1f))
                Box(Modifier.size(44.dp).pressable(0.85f, onClick = onExpand), contentAlignment = Alignment.Center) {
                    Ico(Icons.Rounded.OpenInFull, Color.White.copy(alpha = 0.9f), 20.dp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().fadedEdges()) {
                when {
                    ly == null -> Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        repeat(4) {
                            Shimmer(Modifier.fillMaxWidth(if (it % 2 == 0) 0.85f else 0.6f).height(22.dp), 8.dp)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    ly.synced -> CardLines(ps, ly)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = false).padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(8.dp))
                        ly.lines.forEach { l ->
                            if (l.text.isBlank()) Spacer(Modifier.height(12.dp))
                            else GText(l.text, 20, Color.White.copy(alpha = 0.9f), FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
            }
        }
    }
}

/** Soft fades at the top and bottom of the lines, instead of a hard cut. */
private fun Modifier.fadedEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/** Synced lines: the sung one is kept about a third of the way down, the rest dimmer. */
@Composable
private fun CardLines(ps: PlayerState, ly: Lyrics) {
    val lines = ly.lines
    val active by rememberActiveLine(ps, lines)
    val scroll = rememberScrollState()
    // Where each line sits in the list (measured once).
    val ys = remember(lines) { FloatArray(lines.size) }
    var measured by remember(lines) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewH = constraints.maxHeight.toFloat()
        val room = maxHeight * 0.6f
        LaunchedEffect(active, measured) {
            if (measured == 0) return@LaunchedEffect
            val target = if (active < 0) 0 else (ys[active] - viewH * 0.3f).toInt().coerceIn(0, scroll.maxValue)
            scroll.animateScrollTo(target, smooth(450))
        }
        // Not draggable itself: swipes move the player page instead.
        Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = false).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            lines.forEachIndexed { i, line ->
                val alpha by animateFloatAsState(if (i == active) 1f else 0.42f, smooth(250), label = "line")
                GText(
                    line.text.ifBlank { "♪" }, 22, Color.White, FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            val y = it.positionInParent().y
                            if (ys[i] != y) {
                                ys[i] = y
                                if (i == lines.size - 1) measured++
                            }
                        }
                        .graphicsLayer { this.alpha = alpha }
                        .clickable(remember { MutableInteractionSource() }, null) { if (line.ms >= 0) ps.seekTo(line.ms) }
                        .padding(vertical = 6.dp),
                )
            }
            // Room so the last lines can still move up to the highlight's place.
            Spacer(Modifier.height(room))
        }
    }
}
