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
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Usb
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private fun iconFor(kind: PhoneOutput.Kind?): ImageVector = when (kind) {
    null -> Icons.Rounded.Speaker
    PhoneOutput.Kind.SPEAKER -> Icons.Rounded.PhoneAndroid
    PhoneOutput.Kind.WIRED -> Icons.Rounded.Headphones
    PhoneOutput.Kind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    PhoneOutput.Kind.USB -> Icons.Rounded.Usb
    PhoneOutput.Kind.HEARING -> Icons.Rounded.Hearing
    PhoneOutput.Kind.OTHER -> Icons.Rounded.SettingsInputComponent
}

private fun kindOfChosen(): PhoneOutput.Kind? = PhoneOutput.outputs.firstOrNull { it.key == PhoneOutput.chosen }?.kind

/**
 * The full player's output button: which speaker or headset plays the music (like the PC's). Lit
 * when one is picked. Opens a short list; "More devices" opens Android's own switcher (casting too).
 */
@Composable
fun PhoneOutputButton(size: Dp = 44.dp, iconSize: Dp = 22.dp, idle: Color = Color.White.copy(alpha = 0.85f)) {
    var open by remember { mutableStateOf(false) }
    val picked = PhoneOutput.chosen != null && !PhoneOutput.chosenAway
    Box {
        Box(Modifier.size(size).pressable(0.85f) { open = !open }, contentAlignment = Alignment.Center) {
            Ico(iconFor(kindOfChosen().takeIf { picked }), if (picked) CoverTint.accent else idle, iconSize)
        }
        if (open) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, -140),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) { PhoneOutputList { open = false } }
        }
    }
}

@Composable
private fun PhoneOutputList(done: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { PhoneOutput.refresh() }
    Column(Modifier.width(290.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF5121519)).padding(vertical = 10.dp)) {
        GText("Play through", 13, Faint, FontWeight.SemiBold, 1, Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
        OutRow(null, null, "Automatic", "Headphones when they're connected, else this phone", PhoneOutput.chosen == null) { PhoneOutput.pick(null); done() }
        for (o in PhoneOutput.outputs) OutRow(o.key, o.kind, o.name, null, PhoneOutput.chosen == o.key) { PhoneOutput.pick(o.key); done() }
        if (PhoneOutput.chosenAway) {
            OutRow(PhoneOutput.chosen, null, "Picked one isn't connected", "Playing through the automatic choice for now", true) { PhoneOutput.pick(null); done() }
        }
        OutRow(null, null, "More devices", "Cast or pick from Android's own list", false, icon = Icons.Rounded.Cast) {
            done()
            if (!PhoneOutput.openSystemSwitcher(ctx)) Menus.say("This phone doesn't offer that list. Use the quick settings panel instead.")
        }
    }
}

@Composable
private fun OutRow(@Suppress("UNUSED_PARAMETER") key: String?, kind: PhoneOutput.Kind?, title: String, detail: String?, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(0.98f, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon ?: iconFor(kind), if (selected) CoverTint.accent else Dim, 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GText(title, 15, if (selected) CoverTint.accent else Color.White, FontWeight.Medium, 1)
            if (detail != null) GText(detail, 12, Dim, maxLines = 2)
        }
        if (selected) Ico(Icons.Rounded.Check, CoverTint.accent, 18.dp)
    }
}

/** Settings > Playback: the same choice as the player's output button. */
@Composable
fun PhoneOutputSettings(choices: @Composable (options: List<String?>, selected: String?, title: (String?) -> String, detail: (String?) -> String, onPick: (String?) -> Unit) -> Unit) {
    LaunchedEffect(Unit) { PhoneOutput.refresh() }
    val outs = PhoneOutput.outputs
    val chosen = PhoneOutput.chosen
    val options = listOf<String?>(null) + outs.map { it.key } + listOfNotNull(chosen?.takeIf { c -> outs.none { it.key == c } })
    choices(
        options, chosen,
        { k -> if (k == null) "Automatic" else outs.firstOrNull { it.key == k }?.name ?: k.substringAfter(':').ifEmpty { "Picked device" } },
        { k ->
            when {
                k == null -> "Headphones when they're connected, else this phone"
                outs.none { it.key == k } -> "Not connected · playing through the automatic choice for now"
                else -> ""
            }
        },
    ) { PhoneOutput.pick(it) }
}
