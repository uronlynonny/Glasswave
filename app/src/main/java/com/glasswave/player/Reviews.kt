// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The little review bar at the bottom of your stats: tap the stars, write a few words and send.
 * It reaches nonny dev as an ntfy alert, with the device and app version but nothing about you.
 */
@Composable
fun FeedbackBar(bg: Backdrop, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    var stars by remember { mutableIntStateOf(0) }
    // 0 writing, 1 sending, 2 sent, 3 couldn't send
    var state by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val canSend = state != 1 && (text.isNotBlank() || stars > 0)
    fun send() {
        if (!canSend) return
        state = 1
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Reviews.send(ctx, text, stars) }
            state = if (ok) 2 else 3
            if (ok) { text = ""; stars = 0 }
        }
    }
    Column(
        modifier.fillMaxWidth().liquidGlass(bg, 22.dp, depth = 12.dp, bend = 16.dp).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GText("Enjoying Glasswave?", 15, Color.White, FontWeight.SemiBold, 1)
                GText("Leave a quick review for nonny dev", 12, Faint, maxLines = 1)
            }
            for (i in 1..5) {
                Box(Modifier.size(30.dp).pressable(0.8f) { stars = if (stars == i) 0 else i; if (state == 2) state = 0 }, contentAlignment = Alignment.Center) {
                    Ico(
                        if (i <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (i <= stars) readable(CoverTint.accent) else Faint, 22.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (state == 2) {
            GText("Thanks! Your review was sent.", 14, readable(CoverTint.accent), FontWeight.Medium, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(21.dp)).background(Color.White.copy(alpha = 0.07f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) GText("Write a review…", 14, Faint)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.take(1000); if (state == 3) state = 0 },
                        maxLines = 4,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(CoverTint.accent),
                        modifier = Modifier.fillMaxWidth().typing(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(if (canSend) readable(CoverTint.accent) else Color.White.copy(alpha = 0.08f))
                        .pressable(0.85f) { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state == 1) GText("…", 18, Color.Black, FontWeight.Bold)
                    else Ico(Icons.AutoMirrored.Rounded.Send, if (canSend) Color.Black else Faint, 19.dp)
                }
            }
            if (state == 3) GText("Couldn't send. Check your internet and try again.", 12, Color(0xFFFF8A80), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

/** Sends a review to nonny dev's ntfy alerts (the same ones the song problem reports go to). */
object Reviews {
    private const val TOPIC = "glasswave-nonny-re7mnsuqhuis"
    /** A few per run at most, so a stuck button can't flood the alerts. */
    private var sent = 0

    /** True once it's delivered. Call off the main thread. */
    fun send(context: Context, text: String, stars: Int): Boolean {
        if (sent >= 5) return true
        val starLine = if (stars > 0) "★".repeat(stars) + "☆".repeat(5 - stars) + " ($stars/5)" else "No stars"
        val body = starLine + (if (text.isNotBlank()) "\n\n" + text.trim().take(1000) else "") + "\n\n" + run {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, Glasswave $version"
        }
        return runCatching {
            val req = Request.Builder().url("https://ntfy.sh/$TOPIC")
                .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .header("Title", "New review" + if (stars > 0) " ($stars/5)" else "")
                .header("Tags", "speech_balloon").header("Priority", "4")
                .build()
            YtRepo.http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false).also { if (it) sent++ }
    }
}
