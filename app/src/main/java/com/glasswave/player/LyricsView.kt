// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.OutlinedFlag
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

/*
 * Lyrics, made to stay smooth on mid-range phones:
 *  - Nothing is rebuilt while lines move. Every movement (scroll, scale, fade, blur, the letter glow)
 *    is worked out while drawing, from one shared timer, so each frame is only a redraw.
 *  - The glow is a moving mask over ready-made bright copies of the line, not re-laid-out text.
 *  - Blur levels are made once and reused.
 *
 * Staying in time:
 *  - The lyrics with the closest length to the song are chosen (the same song has many cuts).
 *  - If the chosen lyrics were timed to a slightly longer or shorter cut, their timing is stretched
 *    to fit this recording.
 *  - When the lyrics time each word, the light follows the voice word by word: it moves across a
 *    word as it's sung, waits between words, and finishes each word on time.
 *
 * Two looks, clearly different:
 *  - Liquid glass on: lines glide into place one after another like a wave with a soft overshoot,
 *    far lines shrink and blur, and the sung line lights up letter by letter.
 *  - Liquid glass off: the list glides smoothly, and the sung line lights up letter by letter too.
 *    No wave, no blur: lighter, but just as in time.
 */

@Composable
fun LyricsScreen(ps: PlayerState, onClose: () -> Unit) {
    val url = ps.currentId
    var state by remember(url) { mutableStateOf<Load<Lyrics?>>(Load.Loading) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        state = Load.Loading
        // Give the player a moment to know the song's length (it helps pick the right lyrics).
        if (ps.durationMs <= 0 && !LyricsRepo.ready(url)) delay(600)
        state = try {
            Load.Done(LyricsRepo.get(url, ps.title, ps.artist, ps.durationMs / 1000))
        } catch (e: Exception) {
            Load.Failed("Couldn't load lyrics. Check your internet.")
        }
    }
    val fancy = Settings.glass && Settings.animations
    val accent = CoverTint.accent
    val wide = isWide()

    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { }) {
        LyricsBackdrop(ps.art, fancy)
        if (wide) {
            // Tablets and landscape: the cover and controls on the left, lyrics on the right.
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Column(
                    Modifier.fillMaxHeight().width(360.dp).padding(start = 28.dp, end = 12.dp, top = 24.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(46.dp).align(Alignment.Start).pressable(0.85f, onClick = onClose), contentAlignment = Alignment.Center) {
                        Ico(Icons.Rounded.Close, Color.White, 26.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    Cover(hiRes(ps.art), Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black, spotColor = Color.Black), 18.dp)
                    Spacer(Modifier.height(18.dp))
                    GText(ps.title, 20, weight = FontWeight.Bold, maxLines = 2)
                    GText(ps.artist, 15, Color.White.copy(alpha = 0.7f), maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    MiniControls(ps)
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp)) { LyricsBody(ps, state, fancy, accent) }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Song and close
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(ps.art, Modifier.size(46.dp), 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        GText(ps.title, 16, weight = FontWeight.SemiBold, maxLines = 1)
                        GText(ps.artist, 13, Color.White.copy(alpha = 0.7f), maxLines = 1)
                    }
                    Spacer(Modifier.width(46.dp)) // room for the report flag
                    Box(Modifier.size(46.dp).pressable(0.85f, onClick = onClose), contentAlignment = Alignment.Center) {
                        Ico(Icons.Rounded.Close, Color.White, 26.dp)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { LyricsBody(ps, state, fancy, accent) }
                Column(Modifier.padding(horizontal = 24.dp)) { MiniControls(ps) }
            }
        }
        // Report the lyrics (top right; on a phone, beside the close button).
        ReportLyrics(ps, state, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = if (wide) 16.dp else 10.dp, end = if (wide) 16.dp else 54.dp))
    }
}

/**
 * The flag at the top of the lyrics: tell nonny dev the lyrics are wrong, out of sync or missing.
 * It goes to their alerts with the song, the problem and where in the song it was.
 */
@Composable
private fun ReportLyrics(ps: PlayerState, state: Load<Lyrics?>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.End) {
        Box(Modifier.size(46.dp).pressable(0.85f) { open = !open }, contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.OutlinedFlag, Color.White.copy(alpha = if (open) 1f else 0.8f), 23.dp)
        }
        if (open) {
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.padding(top = 4.dp).width(230.dp).clip(shape).background(Color(0xF0171920))
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape).padding(vertical = 6.dp),
            ) {
                GText("Report these lyrics", 12, Color.White.copy(alpha = 0.55f), FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
                listOf("Wrong lyrics", "Out of sync", "No lyrics").forEach { kind ->
                    GText(
                        kind, 15, Color.White, FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().pressable(0.97f) {
                            open = false
                            val url = ps.currentId ?: return@pressable
                            val title = ps.title
                            val artist = ps.artist
                            val at = ps.positionMs
                            val ly = (state as? Load.Done)?.value
                            val from = ly?.let { it.source + if (it.synced) " (synced)" else " (not synced)" }
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Reviews.lyricsProblem(ctx, title, artist, url, kind, at, from) }
                                Menus.say(if (ok) "Thanks! nonny dev will look at this song's lyrics" else "Couldn't send. Check your internet and try again.")
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Small controls, so you never have to leave the lyrics. */
@Composable
private fun MiniControls(ps: PlayerState) {
    Column(Modifier.fillMaxWidth()) {
        // Bars that dance under the lyrics (Settings > Look).
        if (Settings.visualizer != VisualizerStyle.OFF) {
            Visualizer(ps.playing, CoverTint.accent, Modifier.fillMaxWidth().height(28.dp))
            Spacer(Modifier.height(6.dp))
        }
        AppleSeekBar(ps)
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).pressable(0.85f) { ps.previous() }, contentAlignment = Alignment.Center) { Ico(Icons.Rounded.FastRewind, Color.White, 34.dp) }
            Box(Modifier.size(64.dp).pressable(0.88f) { ps.toggle() }, contentAlignment = Alignment.Center) {
                Ico(if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Color.White, 46.dp)
            }
            Box(Modifier.size(52.dp).pressable(0.85f) { ps.next() }, contentAlignment = Alignment.Center) { Ico(Icons.Rounded.FastForward, Color.White, 34.dp) }
        }
    }
}

@Composable
private fun LyricsBody(ps: PlayerState, state: Load<Lyrics?>, fancy: Boolean, accent: Color) {
    when (val s = state) {
        is Load.Loading -> Column(Modifier.padding(24.dp)) {
            repeat(6) {
                Shimmer(Modifier.fillMaxWidth(if (it % 2 == 0) 0.8f else 0.6f).height(26.dp), 8.dp)
                Spacer(Modifier.height(18.dp))
            }
        }
        is Load.Failed -> Centered(s.message)
        is Load.Done -> {
            val ly = s.value
            when {
                ly == null -> Centered("No lyrics found for this song.")
                ly.synced -> SyncedLyrics(ps, ly, fancy, accent)
                else -> PlainLyrics(ly)
            }
        }
    }
}

/**
 * The song's cover, blown up and blurred behind the lyrics (like Spotify and BitChord), darkened so
 * the words read. The cover is loaded tiny, which makes it soft by itself; the blur is drawn once and
 * kept, and only the slow drift moves (with liquid glass on).
 */
@Composable
private fun LyricsBackdrop(art: String?, moving: Boolean) {
    val ctx = LocalContext.current
    val req = remember(art) { art?.let { ImageRequest.Builder(ctx).data(it).size(64).build() } }
    val drift by rememberDrift(36_000)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        if (req != null) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val t = if (moving) drift else 0.8f
                    val s = 1.45f + 0.1f * sin(t)
                    scaleX = s
                    scaleY = s
                    rotationZ = if (moving) 8f * sin(t * 0.5f) else 0f
                },
            ) {
                AsyncImage(
                    model = req, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x8C000000), Color(0x66000000), Color(0xA6000000)))))
    }
}

/** Lyrics without timing: they rise in one after another, then you just read. */
@Composable
private fun PlainLyrics(ly: Lyrics) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(ly) { enter.animateTo(1f, tween(1200, easing = LinearEasing)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        ly.lines.forEachIndexed { i, l ->
            val start = minOf(i, 14) * 0.05f
            Box(
                Modifier.graphicsLayer {
                    val f = ((enter.value - start) / 0.35f).coerceIn(0f, 1f)
                    val e = 1f - (1f - f) * (1f - f) * (1f - f)
                    alpha = e
                    translationY = (1f - e) * 24.dp.toPx()
                },
            ) {
                if (l.text.isBlank()) Spacer(Modifier.height(14.dp))
                else GText(l.text, 22, Color.White.copy(alpha = 0.9f), FontWeight.SemiBold, modifier = Modifier.padding(vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        GText("These lyrics aren't timed · ${ly.source}", 12, Faint)
    }
}

/* ---------------- Synced lyrics ---------------- */

private fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

/** Ease out with a small overshoot, like a soft spring. */
private fun easeOutBack(t: Float): Float {
    val c1 = 0.9f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

private class LineLook(val scale: Float, val alpha: Float, val blur: Float)

/** How a line looks at signed distance [d] from the sung line (negative = already sung). */
private fun look(d: Int, fancy: Boolean): LineLook = when {
    d == 0 -> LineLook(1f, 1f, 0f)
    fancy -> LineLook(0.93f, if (d < 0) 0.42f else (0.62f - abs(d) * 0.08f).coerceAtLeast(0.2f), minOf(abs(d), 4) * 1.5f)
    else -> LineLook(0.95f, if (d < 0) 0.45f else 0.38f, 0f)
}

@Composable
private fun SyncedLyrics(ps: PlayerState, ly: Lyrics, fancy: Boolean, accent: Color) {
    val lines = ly.lines
    val n = lines.size
    val url = ps.currentId ?: ""

    // No stretching: lyric sites often list the wrong length for correctly timed lyrics, and
    // stretching to that length pushed lines seconds off by the end of the song.
    val stretch = 1f
    val nudge = 0L

    // The song position (on the lyrics' clock), read every frame while this is open (cheap: it only feeds drawing).
    val pos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(stretch, nudge) {
        while (true) {
            withFrameNanos { }
            pos.longValue = (ps.livePosition() * stretch).toLong() + nudge
        }
    }
    // A touch early, so the highlight lands as the line is sung rather than after.
    val active by remember(lines) { derivedStateOf { lines.indexOfLast { it.ms <= pos.longValue + 150 } } }

    // Where each line sits in the list (measured once), and where each one is drawn right now.
    val ys = remember(lines) { FloatArray(n) }
    val hs = remember(lines) { FloatArray(n) }
    val from = remember(lines) { FloatArray(n) }
    val cur = remember(lines) { FloatArray(n) }
    var measured by remember(lines) { mutableIntStateOf(0) }

    // One timer drives the whole move; each line follows a little after the one before it.
    val stagger = if (fancy) 38f else 0f
    val dur = if (fancy) 760f else 520f
    val total = dur + stagger * 8
    val progress = remember { Animatable(1f) }
    var toTarget by remember { mutableFloatStateOf(0f) }
    var toActive by remember { mutableIntStateOf(-1) }
    var fromActive by remember { mutableIntStateOf(-1) }

    var manual by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var lastDrag by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastDrag) {
        if (lastDrag == 0L) return@LaunchedEffect
        delay(2500)
        if (!dragging) manual = null
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; lastDrag = System.currentTimeMillis() },
                        onDragCancel = { dragging = false; lastDrag = System.currentTimeMillis() },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            manual = (manual ?: toTarget) - dy
                        },
                    )
                },
        ) {
            val viewH = with(LocalDensity.current) { maxHeight.toPx() }
            val anchor = viewH * 0.34f
            fun targetFor(a: Int) = (if (a in 0 until n) ys[a] + hs[a] / 2 else 0f) - anchor

            // A new line (or the end of a drag): glide from wherever each line is now.
            LaunchedEffect(active, measured, manual == null) {
                if (manual != null || measured == 0) return@LaunchedEffect
                System.arraycopy(cur, 0, from, 0, n)
                fromActive = toActive
                toActive = active
                toTarget = targetFor(active)
                progress.snapTo(0f)
                progress.animateTo(1f, tween(total.toInt(), easing = LinearEasing))
            }

            val blurs = remember { HashMap<Int, RenderEffect>() }
            val density = LocalDensity.current.density
            Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)) {
                lines.forEachIndexed { i, line ->
                    val next = lines.getOrNull(i + 1)?.ms ?: (line.ms + 5000)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                val y = it.positionInParent().y
                                val h = it.size.height.toFloat()
                                if (ys[i] != y || hs[i] != h) {
                                    ys[i] = y
                                    hs[i] = h
                                    if (i == n - 1) measured++
                                }
                            }
                            .graphicsLayer {
                                val p = progress.value * total
                                val delayMs = minOf(abs(i - toActive), 8) * stagger
                                val local = ((p - delayMs) / dur).coerceIn(0f, 1f)
                                val m = manual
                                val y = if (m != null) m else from[i] + (toTarget - from[i]) * (if (fancy) easeOutBack(local) else easeOutCubic(local))
                                cur[i] = y
                                translationY = -y

                                val e = easeOutCubic(local)
                                val a = look(if (fromActive < 0) i + 1 else i - fromActive, fancy)
                                val b = look(if (toActive < 0) i + 1 else i - toActive, fancy)
                                val s = a.scale + (b.scale - a.scale) * e
                                scaleX = s
                                scaleY = s
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                // Fade out near the top and bottom edges instead of cutting off.
                                val top = ys[i] - y
                                val edge = (minOf(top + hs[i], viewH - top) / (viewH * 0.14f)).coerceIn(0f, 1f)
                                alpha = (a.alpha + (b.alpha - a.alpha) * e) * edge
                                if (fancy && Build.VERSION.SDK_INT >= 31) {
                                    val r = ((a.blur + (b.blur - a.blur) * e) * density).toInt()
                                    renderEffect = if (r <= 0) null else blurs.getOrPut(r) { BlurEffect(r.toFloat(), r.toFloat(), TileMode.Decal) }
                                }
                            }
                            // Hold a line to jump there. (A plain tap used to fire by accident when
                            // swiping to close, sending the song back to an older line.)
                            .pointerInput(line.ms, stretch) {
                                detectTapGestures(onLongPress = { ps.seekTo(((line.ms - nudge) / stretch).toLong().coerceAtLeast(0)) })
                            },
                    ) {
                        LyricText(line, next, i, { active }, { pos.longValue }, fancy, accent)
                    }
                }
                Spacer(Modifier.height(420.dp))
                GText(
                    ly.source + (if (ly.wordTimed) " · word by word" else "") + (if (stretch != 1f) " · timing fitted to this cut" else ""),
                    12, Faint, modifier = Modifier.padding(horizontal = 26.dp),
                )
            }
        }

    }
}

/** A line's word timings, ready for drawing: [letters] are the timed letters' places in the text. */
private class WordFill(val words: List<LyricWord>, val first: IntArray, val count: IntArray, val letters: IntArray) {
    /**
     * How far the singing is at [p], counted in [letters] (2.5 = halfway through the third). Inside a
     * word the fill moves across its letters in time; between words it waits where the last one ended.
     */
    fun progress(p: Long): Float {
        for (i in words.indices) {
            val w = words[i]
            if (p < w.ms) return first[i].toFloat()
            if (p < w.endMs) return first[i] + count[i] * (p - w.ms).toFloat() / (w.endMs - w.ms)
        }
        return letters.size.toFloat()
    }
}

/**
 * One line: a dim copy, with a bright copy on top revealed by a moving mask, letter by letter in
 * time with the singing (both looks). The mask is worked out while drawing, so it moves every frame
 * without rebuilding the text, and there's no glow to blur: one cheap layer, only on the sung line.
 */
@Composable
private fun LyricText(line: LyricLine, nextMs: Long, index: Int, active: () -> Int, pos: () -> Long, fancy: Boolean, accent: Color) {
    val text = line.text.ifBlank { "●  ●  ●" }
    val isActive = active() == index
    val passed = active() > index
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val fontSize = if (fancy) 30.sp else 27.sp
    val base = TextStyle(fontSize = fontSize, fontWeight = FontWeight.ExtraBold, fontFamily = AppFontFamily, lineHeight = if (fancy) 37.sp else 33.sp)
    // Letters that count for timing (spaces are skipped, so the fill doesn't pause between words).
    val letterAt = remember(text) { text.indices.filter { !text[it].isWhitespace() } }
    // With word timing: each timed word's letters in order, where each word's letters start in that
    // list, and how many it has. Times are on the lyrics' clock, the same one pos() uses.
    val timed = remember(line) {
        val words = if (line.text.isBlank()) emptyList() else line.words.filter { it.from >= 0 && it.to <= text.length }
        val first = IntArray(words.size)
        val count = IntArray(words.size)
        val letters = ArrayList<Int>()
        words.forEachIndexed { i, w ->
            first[i] = letters.size
            for (c in w.from until w.to) if (!text[c].isWhitespace()) letters += c
            count[i] = letters.size - first[i]
        }
        if (letters.isEmpty()) null else WordFill(words, first, count, letters.toIntArray())
    }

    Box(Modifier.padding(horizontal = 24.dp, vertical = if (fancy) 12.dp else 10.dp)) {
        BasicText(text, style = base.copy(color = Color.White.copy(alpha = 0.4f)), onTextLayout = { layout[0] = it })
        if (passed) {
            BasicText(text, style = base.copy(color = Color.White))
        } else if (isActive) {
            BasicText(
                text,
                style = base.copy(color = if (fancy) Color.White else Color.White.copy(alpha = 0.95f)),
                modifier = Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        val tl = layout[0] ?: return@drawWithContent drawContent()
                        val c: Int
                        val x: Float
                        if (timed != null) {
                            // Real word timing: follow the word being sung; between words, wait at
                            // the end of the last one.
                            val exact = timed.progress(pos())
                            if (exact >= timed.letters.size) return@drawWithContent drawContent()
                            val li = if (exact <= 0f) 0 else kotlin.math.ceil(exact).toInt() - 1
                            c = timed.letters[li]
                            val box = tl.getBoundingBox(c)
                            x = box.left + (box.right - box.left) * (exact - li).coerceIn(0f, 1f)
                        } else {
                            // How far through the line the singing is, as a letter (with the part of the next letter).
                            val span = ((nextMs - line.ms) * 0.92f).coerceIn(600f, 12_000f)
                            val f = ((pos() - line.ms) / span).coerceIn(0f, 1f)
                            val exact = f * letterAt.size
                            val li = exact.toInt()
                            if (li >= letterAt.size) return@drawWithContent drawContent()
                            c = letterAt[li]
                            val x0 = tl.getHorizontalPosition(c, true)
                            val x1 = tl.getHorizontalPosition(c + 1, true)
                            x = x0 + (x1 - x0) * (exact - li)
                        }
                        val vline = tl.getLineForOffset(c)
                        val lineTop = tl.getLineTop(vline)
                        val lineBottom = tl.getLineBottom(vline)
                        val feather = 14.dp.toPx()
                        drawContent()
                        // Keep lines above, the sung part of this line (soft edge), clear the rest.
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = x - feather / 2, endX = x + feather / 2),
                            Offset(0f, lineTop), Size(size.width, lineBottom - lineTop), blendMode = BlendMode.DstIn,
                        )
                        if (lineBottom < size.height) {
                            drawRect(Color.Transparent, Offset(0f, lineBottom), Size(size.width, size.height - lineBottom), blendMode = BlendMode.DstIn)
                        }
                    },
            )
        }
    }
}