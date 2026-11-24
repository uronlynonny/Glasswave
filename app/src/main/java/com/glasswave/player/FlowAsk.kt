// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasswave.player.JamPlatform.jamTyping

/** A few ideas under the box, so it's clear what you can type. One set picked at random each time. */
private val flowIdeas = listOf(
    listOf("Chill afrobeats", "Gym hype", "Late night R&B", "2000s throwbacks"),
    listOf("Amapiano for the weekend", "Sad songs", "Focus, no lyrics", "Road trip"),
    listOf("Feel-good pop", "Old school hip hop", "Rainy day", "Party starters"),
)

/**
 * Under Flow on Home: type what you want to hear and Flow plays it ("chill afrobeats for the rain",
 * "gym hype", "songs like Burna Boy"). The player opens, where Flow answers. Ask again any time to change it.
 */
@Composable
fun FlowAsk(ps: PlayerState) {
    val onAsked = { FlowTalk.openPlayer = true }
    val ideas = remember { flowIdeas.random() }
    Column(
        Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 2.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
    ) {
        FlowAskField(ps, onAsked = onAsked)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (idea in ideas) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                        .pressable(0.95f) { if (!Flow.building) { Flow.request(ps, idea); onAsked() } }.padding(horizontal = 12.dp, vertical = 7.dp),
                ) { GText(idea, 13, Color.White.copy(alpha = 0.85f), FontWeight.Medium, 1) }
            }
        }
    }
}

/** The box you type a request to Flow in. Enter or the arrow sends it. */
@Composable
fun FlowAskField(ps: PlayerState, modifier: Modifier = Modifier, onAsked: () -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val accent = CoverTint.accent
    fun go(q: String = text) {
        if (q.isBlank() || Flow.building) return
        focus.clearFocus()
        Flow.request(ps, q)
        text = ""
        onAsked()
    }
    run {
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier.fillMaxWidth().height(48.dp).clip(shape).background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(Icons.Rounded.AutoAwesome, accent, 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    GText(
                        when {
                            Flow.building -> "Mixing your set…"
                            Flow.asked != null -> "Playing “${Flow.asked}” · ask for something else"
                            else -> "Tell Flow what you want to hear"
                        },
                        14, Dim, maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(120) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.fillMaxWidth().jamTyping().onPreviewKeyEvent {
                        if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { go(); true } else false
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (text.isBlank()) Color.White.copy(alpha = 0.08f) else accent)
                    .pressable(0.85f) { go() },
                contentAlignment = Alignment.Center,
            ) {
                if (Flow.building) Ico(Icons.Rounded.AutoAwesome, Dim, 16.dp)
                else Ico(Icons.Rounded.ArrowUpward, if (text.isBlank()) Dim else Color.Black, 18.dp)
            }
        }
    }
}
