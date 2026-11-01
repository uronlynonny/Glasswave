// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The welcome screen, shown once: the first time someone opens Glasswave. */
object Welcome {
    private var prefs: SharedPreferences? = null

    /** True until the welcome screen has been seen (or skipped). */
    var show by mutableStateOf(false); private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("welcome", Context.MODE_PRIVATE)
        val seen = prefs?.getBoolean("seen", false) == true
        // People updating from an older version already know the app: no welcome for them.
        val used = History.tracks.isNotEmpty() || Library.playlists.value.size > 1
        if (!seen && used) done()
        show = !seen && !used
    }

    fun done() {
        show = false
        prefs?.edit()?.putBoolean("seen", true)?.apply()
    }
}

private class WelcomePage(val title: String, val body: String, val points: List<String>)

private val pages = listOf(
    WelcomePage(
        "Welcome to Glasswave",
        "A free music player made of liquid glass. No ads, no account needed.",
        emptyList(),
    ),
    WelcomePage(
        "All your music",
        "Everything on YouTube Music, ready to play.",
        listOf(
            "Bring in your Spotify and YouTube Music playlists",
            "Lyrics that scroll with the song",
            "Download songs to listen offline",
        ),
    ),
    WelcomePage(
        "Make it sound yours",
        "Glasswave's own sound tools work on every phone.",
        listOf(
            "Draw your own equalizer curve",
            "Spatial audio, speed presets and reverb",
            "Automix blends one song into the next",
        ),
    ),
)

/** Three short pages, then "Get started". Skip leaves straight away. */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler { if (page > 0) page-- else onDone() }
    val accent = readable(CoverTint.accent)
    Box(
        Modifier.fillMaxSize().background(Color(0xFF05070A))
            // Taps don't fall through to the app underneath.
            .pressable(1f) {},
    ) {
        GlassOrb(accent, Modifier.align(Alignment.TopCenter).padding(top = 90.dp).size(260.dp))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (page < pages.size - 1) {
                    Box(Modifier.clip(CircleShape).pressable(0.9f, onClick = onDone).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        GText("Skip", 15, Dim, FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                page,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(320)) { it / 3 * dir } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 3 * dir } + fadeOut(tween(200)))
                },
                label = "welcome",
            ) { i ->
                val p = pages[i]
                Column {
                    GText(p.title, 32, Color.White, FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    GText(p.body, 16, Dim)
                    p.points.forEach { line ->
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                            Spacer(Modifier.width(12.dp))
                            GText(line, 15, Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            // Which page you're on.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pages.indices.forEach { i ->
                    val w by androidx.compose.animation.core.animateDpAsState(if (i == page) 22.dp else 7.dp, tween(250), label = "dot")
                    Box(Modifier.height(7.dp).width(w).clip(CircleShape).background(if (i == page) Color.White else Color.White.copy(alpha = 0.25f)))
                }
            }
            Spacer(Modifier.height(18.dp))
            val last = page == pages.size - 1
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(Color.White)
                    .pressable(0.97f) { if (last) onDone() else page++ },
                contentAlignment = Alignment.Center,
            ) { GText(if (last) "Get started" else "Next", 17, Color.Black, FontWeight.SemiBold) }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** Soft colour lights circling slowly behind a glass ring, with the Glasswave mark in the middle. */
@Composable
private fun GlassOrb(accent: Color, modifier: Modifier) {
    val spin = rememberInfiniteTransition(label = "orb")
    val t by spin.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val breathe by spin.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breathe")
    val lights = listOf(accent, Color(0xFF64D2FF), Color(0xFFAF52DE))
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().blur(46.dp)) {
            val r = size.minDimension * 0.22f
            lights.forEachIndexed { i, c ->
                val a = 2 * PI * (t + i / 3.0)
                val o = Offset(center.x + (cos(a) * r).toFloat(), center.y + (sin(a) * r * 0.8).toFloat())
                drawCircle(c.copy(alpha = 0.75f), size.minDimension * 0.26f * breathe, o)
            }
        }
        Canvas(Modifier.size(170.dp)) {
            // A glass ring: bright on top, fading underneath.
            drawCircle(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.25f))),
                size.minDimension / 2 - 2.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
            drawCircle(Color.White.copy(alpha = 0.06f), size.minDimension / 2 - 3.dp.toPx())
        }
        Image(painterResource(R.drawable.ic_foreground), null, Modifier.size(150.dp))
    }
}
