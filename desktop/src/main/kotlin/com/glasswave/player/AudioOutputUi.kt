// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An icon for an output, from the name Windows gives it. Windows puts what it is first
 * ("Speakers (Realtek…)", "Headphones (…)", "Speakers/Headphones (…)" for a shared jack), so that
 * part decides first: speakers stay speakers even when the jack also takes headphones.
 */
private fun iconFor(name: String?): ImageVector {
    if (name == null) return Icons.Rounded.Speaker
    val kind = name.substringBefore('(').lowercase()
    val all = name.lowercase()
    return when {
        "speaker" in kind -> Icons.Rounded.Speaker
        Regex("head|ear|buds|airpods|hands-free|handsfree").containsMatchIn(kind) -> Icons.Rounded.Headphones
        Regex("""\btv\b|monitor|display|hdmi|displayport""").containsMatchIn(all) ||
            Regex("""nvidia|amd high definition|intel\(r\) display""").containsMatchIn(all) -> Icons.Rounded.Tv
        Regex("""headphone|headset|earbuds|airpods|arctis|hyperx|\bwh-|\bwf-|buds""").containsMatchIn(all) -> Icons.Rounded.Headphones
        "bluetooth" in all -> Icons.Rounded.BluetoothAudio
        else -> Icons.Rounded.Speaker
    }
}

/** What "System default" follows, in this computer's words. */
private val followsSystem = when {
    Os.isWindows -> "Follows Windows' sound settings"
    Os.isMac -> "Follows the Mac's sound settings"
    else -> "Follows the system's sound settings"
}

/**
 * The player bar's output button: which speaker or headset plays the music. Lit up when it isn't the
 * system default. Opens a short list to pick from.
 */
@Composable
fun OutputButton(size: Dp = 36.dp, iconSize: Dp = 20.dp, idle: Color = Dim) {
    var open by remember { mutableStateOf(false) }
    val picked = AudioOutput.chosen != null && AudioOutput.chosenPresent
    Box {
        Box(Modifier.size(size).pressable(0.85f) { open = !open }, contentAlignment = Alignment.Center) {
            Ico(iconFor(AudioOutput.chosen.takeIf { picked }), if (picked) CoverTint.accent else idle, iconSize)
        }
        if (open) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -52),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) { OutputList { open = false } }
        }
    }
}

@Composable
private fun OutputList(done: () -> Unit) {
    // What's plugged in, looked at again each time the list opens.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AudioOutput.refresh() } }
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF5121519)).padding(vertical = 10.dp),
    ) {
        GText("Play through", 13, Faint, FontWeight.SemiBold, 1, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        OutputRow(null, "System default", followsSystem, AudioOutput.chosen == null, done)
        for (d in AudioOutput.devices) OutputRow(d.name, d.name, null, AudioOutput.chosen == d.name, done)
        // Picked before, but not plugged in now: shown, so it's clear why the default is playing.
        val missing = AudioOutput.chosen?.takeIf { c -> AudioOutput.devices.none { it.name == c } }
        if (missing != null) OutputRow(missing, missing, "Not plugged in · playing through the default", true, done)
    }
}

@Composable
private fun OutputRow(name: String?, title: String, detail: String?, selected: Boolean, done: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(0.98f) { AudioOutput.pick(name); done() }.padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(iconFor(name), if (selected) CoverTint.accent else Dim, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(title, 14, if (selected) CoverTint.accent else Color.White, FontWeight.Medium, 1)
            if (detail != null) GText(detail, 12, Dim, maxLines = 1)
        }
        if (selected) Ico(Icons.Rounded.Check, CoverTint.accent, 18.dp)
    }
}

/** Settings > Playback: the same choice as the player's output button. */
@Composable
fun OutputSettings(choices: @Composable (options: List<String?>, selected: String?, title: (String?) -> String, detail: (String?) -> String, onPick: (String?) -> Unit) -> Unit) {
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AudioOutput.refresh() } }
    val names = AudioOutput.devices.map { it.name }
    val chosen = AudioOutput.chosen
    val options = listOf<String?>(null) + names + listOfNotNull(chosen?.takeIf { it !in names })
    choices(
        options, chosen,
        { it ?: "System default" },
        { n ->
            when {
                n == null -> followsSystem
                n !in names -> "Not plugged in · playing through the default for now"
                else -> ""
            }
        },
    ) { AudioOutput.pick(it) }
}
