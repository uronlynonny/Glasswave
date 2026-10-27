// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shown instead of a page when there's no internet: a signal whose waves light up one after
 * another while it looks for a connection, what still works, and a button for downloaded songs.
 * It checks every few seconds and loads the page by itself once the internet is back.
 */
@Composable
fun OfflineBox(backdrop: Backdrop, onPlaySaved: (() -> Unit)?, onRetry: () -> Unit) {
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (withContext(Dispatchers.IO) { online() }) {
                retry()
                break
            }
        }
    }
    val accent = CoverTint.accent
    val t = rememberInfiniteTransition(label = "offline")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sweep")
    val breathe by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(132.dp)) {
            val c = Offset(size.width / 2, size.height * 0.72f)
            val unit = size.width / 132f
            // A soft ring that grows from the dot and fades, like a ping going out.
            val ring = sweep
            drawCircle(accent.copy(alpha = (1f - ring) * 0.22f), radius = (10f + ring * 70f) * unit, center = c, style = Stroke(2f * unit))
            // Three waves, each lighting up in turn, then all dimming together.
            for (i in 0 until 3) {
                val r = (26f + i * 20f) * unit
                val on = ((sweep * 4f - i).coerceIn(0f, 1f)) * (1f - ((sweep - 0.8f) * 5f).coerceIn(0f, 1f))
                drawArc(
                    color = lerpWhite(accent, on),
                    startAngle = 225f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(7f * unit, cap = StrokeCap.Round),
                )
            }
            drawCircle(accent, radius = 7.5f * unit * breathe, center = c)
        }
        Spacer(Modifier.height(10.dp))
        GText("You're offline", 22, Color.White, FontWeight.Bold, 1)
        Spacer(Modifier.height(6.dp))
        GText(
            "Glasswave keeps looking and loads this the moment you're back. Downloaded songs, and the ones it saved ahead, still play.",
            14, Dim, modifier = Modifier.widthIn(max = 340.dp), align = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onPlaySaved != null && Downloads.tracks.isNotEmpty()) GlassButton("Play downloads", backdrop, onClick = onPlaySaved)
            GlassButton("Try again", backdrop, onClick = onRetry)
        }
    }
}

/** A wave that's off is a faint grey; on, it's the highlight colour. */
private fun lerpWhite(accent: Color, on: Float): Color =
    androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.14f), accent, on)

/** Can the music server be reached? Blocking. */
private fun online(): Boolean = runCatching {
    java.net.Socket().use { it.connect(java.net.InetSocketAddress("music.youtube.com", 443), 3_000) }
    true
}.getOrDefault(false)
