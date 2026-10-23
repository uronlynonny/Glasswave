// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Voice search. Sing or say a bit of the lyrics (or the song's name) and Glasswave searches
 * YouTube Music for it, which matches songs by their lyrics. For humming without words, it opens
 * Google's "hum to search", since no free service can recognise a hummed tune.
 */
@Composable
fun VoiceSearch(onResult: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        if (!ok) {
            Menus.say("Voice search needs the microphone")
            onClose()
        }
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.RECORD_AUDIO) }

    var heard by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Listening… sing or say some lyrics") }
    var level by remember { mutableFloatStateOf(0f) }
    var listening by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    if (allowed) {
        DisposableEffect(attempt) {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                status = "This phone has no speech recognizer. Try Hum instead."
                return@DisposableEffect onDispose { }
            }
            val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "Listening… sing or say some lyrics" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; status = "Finding it…" }
                override fun onError(error: Int) {
                    listening = false
                    level = 0f
                    status = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic to try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet. Tap the mic to try again."
                        else -> "Something went wrong. Tap the mic to try again."
                    }
                }
                override fun onResults(results: Bundle?) {
                    val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (best.isNullOrBlank()) status = "Didn't catch that. Tap the mic to try again."
                    else onResult(best)
                }
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) heard = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Singing has pauses: wait a little longer before deciding you've finished.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            heard = ""
            sr.startListening(intent)
            onDispose {
                sr.cancel()
                sr.destroy()
            }
        }
    }

    // Smoothly follow the voice level for the orb.
    val shown by androidx.compose.animation.core.animateFloatAsState(level, androidx.compose.animation.core.tween(120), label = "lvl")
    val drift by rememberDrift(6_000)
    val accent = CoverTint.accent

    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0B08)).clickable(remember { MutableInteractionSource() }, null) { }
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp).pressable(0.85f, onClick = onClose), contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.Close, Color.White, 26.dp)
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // A glowing orb that swells with your voice; tap it to listen again.
            Box(
                Modifier.size(220.dp).pressable(0.95f) { if (!listening) attempt++ },
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
                }
                Ico(Icons.Rounded.Mic, Color.Black.copy(alpha = 0.8f), 46.dp)
            }
            Spacer(Modifier.height(24.dp))
            GText(heard.ifEmpty { "…" }, 24, Color.White, FontWeight.Bold, 3)
            Spacer(Modifier.height(8.dp))
            GText(status, 14, Dim, maxLines = 2)
        }
        // Humming: hand over to Google's hum to search.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                Modifier.pressable(0.95f) {
                    try {
                        ctx.startActivity(Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onClose()
                    } catch (e: ActivityNotFoundException) {
                        status = "Humming needs the Google app (it has \"hum to search\")."
                    }
                }.background(Color.White.copy(alpha = 0.08f), androidx.compose.foundation.shape.CircleShape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(Icons.Rounded.GraphicEq, accent, 22.dp)
                Spacer(Modifier.size(10.dp))
                Column {
                    GText("Only humming?", 15, weight = FontWeight.SemiBold)
                    GText("Opens Google's hum to search", 12, Dim)
                }
            }
        }
    }
}
