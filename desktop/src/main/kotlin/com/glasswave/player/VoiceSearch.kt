// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities

/**
 * Voice search, like on the phone: sing or say a bit of the lyrics (or the song's name) and
 * Glasswave searches YouTube Music for it, which matches songs by their lyrics.
 * Click the orb to stop early or to listen again. Esc closes.
 */
@Composable
fun VoiceSearch(onResult: (String) -> Unit, onClose: () -> Unit) {
    var ready by remember { mutableStateOf(Voice.ready) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    var heard by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var listening by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val stop = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun ui(f: () -> Unit) = SwingUtilities.invokeLater(f)

    fun setUp() {
        if (downloading != null) return
        downloading = 0f
        status = "Getting voice search ready…"
        Thread {
            try {
                Voice.download { p -> ui { downloading = p } }
                ui { downloading = null; ready = true; attempt++ }
            } catch (e: Exception) {
                ui { downloading = null; status = "Couldn't download voice search. Check your internet and try again." }
            }
        }.apply { isDaemon = true }.start()
    }

    if (ready) {
        DisposableEffect(attempt) {
            stop.set(false)
            heard = ""
            listening = true
            busy = true
            status = "Listening… sing or say some lyrics"
            val t = Thread {
                try {
                    Voice.warmUp()
                    AppVolume.duck = 0.12f
                    val rec = try {
                        Voice.record(level = { l -> ui { level = l } }, stop = { stop.get() })
                    } finally {
                        AppVolume.duck = 1f
                    }
                    ui { listening = false; level = 0f; status = "Finding it…" }
                    if (!rec.heardSomething) {
                        ui { busy = false; status = "Didn't hear anything. Check your microphone, then click the mic to try again." }
                        return@Thread
                    }
                    val text = Voice.transcribe(rec.samples)
                    ui {
                        busy = false
                        if (text.length < 2) status = "Didn't catch that. Click the mic to try again."
                        else { heard = text; onResult(text) }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Glasswave", "Voice search failed", e)
                    ui {
                        listening = false
                        busy = false
                        level = 0f
                        status = if (e is IllegalArgumentException || e is javax.sound.sampled.LineUnavailableException)
                            "No microphone found. Plug one in, or allow apps to use it in Windows Settings › Privacy › Microphone."
                        else "Something went wrong. Click the mic to try again."
                    }
                }
            }.apply { isDaemon = true }
            t.start()
            onDispose { stop.set(true) }
        }
    }

    // Smoothly follow the voice level for the orb.
    val shown by androidx.compose.animation.core.animateFloatAsState(level, androidx.compose.animation.core.tween(120), label = "lvl")
    val drift by rememberDrift(6_000, running = listening)
    val accent = CoverTint.accent

    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0B08)).clickable(remember { MutableInteractionSource() }, null) { },
    ) {
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(48.dp).pressable(0.85f, onClick = onClose), contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.Close, Color.White, 26.dp)
        }
        Column(Modifier.align(Alignment.Center).width(560.dp).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // A glowing orb that swells with your voice. Click it to stop, or to listen again.
            Box(
                Modifier.size(220.dp).pressable(0.95f) {
                    when {
                        !ready -> setUp()
                        listening -> stop.set(true)
                        !busy -> attempt++
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val base = size.minDimension * 0.26f
                    val r = base * (1f + 0.45f * shown)
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
                    for (k in 0..2) {
                        val a = drift + k * 2.1f
                        val o = Offset(c.x + kotlin.math.cos(a) * base * 0.18f, c.y + kotlin.math.sin(a) * base * 0.18f)
                        drawCircle(accent.copy(alpha = 0.22f), r * (0.92f + 0.06f * k), o, style = Stroke(2.dp.toPx()))
                    }
                    drawCircle(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.55f)), c, r), r * 0.78f, c)
                    downloading?.let { p ->
                        drawArc(Color.White.copy(alpha = 0.9f), -90f, 360f * p, false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(4.dp.toPx()))
                    }
                }
                Ico(if (ready) Icons.Rounded.Mic else Icons.Rounded.Download, Color.Black.copy(alpha = 0.8f), 46.dp)
            }
            Spacer(Modifier.height(24.dp))
            if (!ready) {
                GText("Search by singing", 24, Color.White, FontWeight.Bold, 2)
                Spacer(Modifier.height(8.dp))
                GText(
                    downloading?.let { "Downloading… ${(it * 100).toInt()}%" } ?: status.ifEmpty {
                        "Voice search runs on this PC, so nothing you say leaves your computer. Click the orb to download it once (about 60 MB)."
                    },
                    14, Dim, maxLines = 3,
                )
            } else {
                GText(heard.ifEmpty { "…" }, 24, Color.White, FontWeight.Bold, 3)
                Spacer(Modifier.height(8.dp))
                GText(status, 14, Dim, maxLines = 2)
            }
        }
    }
}
