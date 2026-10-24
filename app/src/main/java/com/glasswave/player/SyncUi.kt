// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The clock, ticking every second while something on screen needs it (positions, "playing now"). */
@Composable
private fun rememberNow(everyMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(everyMs) { while (true) { delay(everyMs); now = System.currentTimeMillis() } }
    return now
}

/** True when the Devices button should show: signed in to YouTube Music with syncing on. */
val syncAvailable: Boolean get() = Sync.available

/** The Devices button for the player: lit up when another device is playing. */
@Composable
fun DevicesButton(size: androidx.compose.ui.unit.Dp = 44.dp, iconSize: androidx.compose.ui.unit.Dp = 22.dp, idle: Color = Color.White.copy(alpha = 0.85f)) {
    if (!syncAvailable) return
    val now = rememberNow(15_000)
    val lit = Sync.devices.any { it.playingNow(now) }
    Box(Modifier.size(size).pressable(0.85f) { Sync.panelOpen = true }, contentAlignment = Alignment.Center) {
        Ico(Icons.Rounded.Devices, if (lit) CoverTint.accent else idle, iconSize)
    }
}

/**
 * A slim bar for when another device is playing and this one isn't: "Playing on Windows PC", the
 * song, and play/pause. Tapping it opens Devices.
 */
@Composable
fun RemoteStrip(localPlaying: Boolean, modifier: Modifier = Modifier) {
    if (!syncAvailable || localPlaying) return
    val now = rememberNow()
    val d = Sync.devices.firstOrNull { it.playingNow(now) && it.track != null } ?: return
    val t = d.track ?: return
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF121519))
            .pressable(0.99f) { Sync.panelOpen = true }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t.thumb, Modifier.size(34.dp), 6.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            GText("Playing on ${d.name}", 11, CoverTint.accent, FontWeight.SemiBold, 1)
            GText(listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" · "), 13, Color.White, maxLines = 1)
        }
        Box(Modifier.size(38.dp).pressable(0.88f) { Sync.command(d, "toggle") }, contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.Pause, Color.White, 24.dp)
        }
        Box(Modifier.size(38.dp).pressable(0.88f) { Sync.command(d, "next") }, contentAlignment = Alignment.Center) {
            Ico(Icons.Rounded.SkipNext, Color.White, 24.dp)
        }
    }
}

/** Your devices on this YouTube Music account: what each is playing, with controls. */
@Composable
fun DevicesPanel(hereTrack: Track?, herePlaying: Boolean) {
    if (!Sync.panelOpen) return
    LaunchedEffect(Unit) { Sync.start(); Sync.hello() }
    val now = rememberNow()
    BackHandlerCompat { Sync.panelOpen = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pressable(1f) { Sync.panelOpen = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 620.dp)
                .clip(RoundedCornerShape(26.dp)).background(Color(0xFF101318))
                .pressable(1f) { } // taps inside don't close it
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText("Devices", 22, Color.White, FontWeight.Bold, 1, Modifier.weight(1f))
                Box(Modifier.size(36.dp).pressable(0.85f) { Sync.panelOpen = false }, contentAlignment = Alignment.Center) {
                    Ico(Icons.Rounded.Close, Dim, 22.dp)
                }
            }
            GText("Phone and PC signed in to the same YouTube Music account. Listening stats count songs from all of them.", 13, Faint)
            Spacer(Modifier.height(16.dp))

            DeviceRow(
                SyncPlatform.kind == "phone", SyncPlatform.deviceName() + " (this device)",
                when {
                    hereTrack == null -> "Nothing playing"
                    herePlaying -> "Playing · ${hereTrack.title}"
                    else -> "Paused · ${hereTrack.title}"
                },
                herePlaying,
            ) {}

            if (!syncAvailable) {
                Spacer(Modifier.height(14.dp))
                GText(
                    if (!Accounts.ytSignedIn) "Sign in to YouTube Music to see your other devices." else "Turned off in Settings → Accounts.",
                    14, Dim,
                )
            } else if (Sync.devices.isEmpty()) {
                Spacer(Modifier.height(14.dp))
                GText(
                    if (Sync.active) "Looking for your other devices… Open Glasswave on your phone or PC, signed in to the same YouTube Music account."
                    else "Connecting…",
                    14, Dim,
                )
            }

            for (d in Sync.devices) {
                Spacer(Modifier.height(10.dp))
                val t = d.track
                val playing = d.playingNow(now)
                DeviceRow(
                    d.isPhone, d.name,
                    when {
                        t == null -> "Available"
                        playing -> "Playing · ${t.title}" + (if (t.artist.isNotBlank()) " — ${t.artist}" else "")
                        else -> "Paused · ${t.title}"
                    },
                    playing,
                ) {
                    if (t != null) {
                        // Where it is in the song.
                        if (d.durMs > 0) {
                            Spacer(Modifier.height(8.dp))
                            val f = (d.position(now).toFloat() / d.durMs).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                                Box(Modifier.fillMaxWidth(f).height(3.dp).clip(CircleShape).background(if (playing) CoverTint.accent else Dim))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RoundIcon(Icons.Rounded.SkipPrevious) { Sync.command(d, "prev") }
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color.White).pressable(0.9f) { Sync.command(d, "toggle") },
                                contentAlignment = Alignment.Center,
                            ) { Ico(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Color(0xFF111111), 24.dp) }
                            Spacer(Modifier.width(6.dp))
                            RoundIcon(Icons.Rounded.SkipNext) { Sync.command(d, "next") }
                            Spacer(Modifier.weight(1f))
                            SmallPill("Play here") { Sync.playHere(d); Sync.panelOpen = false }
                        }
                    }
                    if (hereTrack != null) {
                        Spacer(Modifier.height(8.dp))
                        SmallPill("Play “${hereTrack.title.take(28)}” on ${d.name.take(20)}", Modifier.fillMaxWidth()) { Sync.playOn(d); Sync.panelOpen = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(phone: Boolean, name: String, status: String, playing: Boolean, extra: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.05f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (playing) CoverTint.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) { Ico(if (phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, if (playing) CoverTint.accent else Dim, 20.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                GText(name, 15, Color.White, FontWeight.SemiBold, 1)
                GText(status, 13, if (playing) CoverTint.accent else Dim, maxLines = 1)
            }
        }
        extra()
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).pressable(0.9f, onClick = onClick), contentAlignment = Alignment.Center) {
        Ico(icon, Color.White, 22.dp)
    }
}

@Composable
private fun SmallPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.1f)).pressable(0.96f, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { GText(text, 13, Color.White, FontWeight.SemiBold, 1) }
}

@Composable
private fun BackHandlerCompat(onBack: () -> Unit) = androidx.activity.compose.BackHandler(onBack = onBack)

/** Lets other devices on the account control this player (see Sync). */
fun syncRemote(ps: PlayerState): Sync.Remote = object : Sync.Remote {
    override fun current() = ps.currentTrack()
    override fun isPlaying() = ps.playing
    override fun positionMs() = ps.livePosition()
    override fun durationMs() = ps.durationMs
    override fun upcoming(limit: Int) = ps.upcoming(limit).map { it.track }
    override fun toggle() = ps.toggle()
    override fun play() = ps.play()
    override fun pause() = ps.pause()
    override fun next() = ps.next()
    override fun previous() = ps.previous()
    override fun seekTo(ms: Long) = ps.seekTo(ms)
    override fun playQueue(tracks: List<Track>, index: Int, startMs: Long) = ps.playList(tracks, index, startMs = startMs)
}
