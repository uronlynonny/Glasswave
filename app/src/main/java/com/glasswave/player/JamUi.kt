// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasswave.player.JamPlatform.jamTyping
import kotlinx.coroutines.delay
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The Jam button for the player: lit up in a Jam, with how many are in it. */
@Composable
fun JamButton(size: Dp = 44.dp, iconSize: Dp = 22.dp, idle: Color = Color.White.copy(alpha = 0.85f)) {
    Box(Modifier.size(size).pressable(0.85f) { Jam.panelOpen = true }, contentAlignment = Alignment.Center) {
        Ico(Icons.Rounded.Groups, if (Jam.inJam) CoverTint.accent else idle, iconSize)
        val n = Jam.members.size
        if (Jam.inJam && n > 1) {
            // How many are in it, on the icon's top corner.
            Box(
                Modifier.align(Alignment.Center).offset(x = iconSize * 0.55f, y = -iconSize * 0.42f).size(iconSize * 0.62f)
                    .clip(CircleShape).background(CoverTint.accent),
                contentAlignment = Alignment.Center,
            ) { GText(if (n > 9) "9+" else "$n", if (iconSize < 22.dp) 8 else 9, Color.Black, FontWeight.Bold, 1) }
        }
    }
}

/**
 * Listen together: start a Jam or join one with a code; in a Jam, the code to share, who's in it,
 * what's coming up (and who added it), and Leave or End.
 */
@Composable
fun JamPanel(ps: PlayerState, backdrop: Backdrop) {
    if (!Jam.panelOpen) return
    androidx.activity.compose.BackHandler { Jam.panelOpen = false }
    // From now on this device looks for Jams nearby; a phone asks for approximate location (once).
    LaunchedEffect(Unit) { Jam.markUsed(); JamPlatform.askLocationOnce() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pressable(1f) { Jam.panelOpen = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 680.dp)
                .liquidGlass(backdrop, 26.dp, Color(0xFF0E0F13).copy(alpha = 0.9f), blur = 24.dp, depth = 12.dp, bend = 14.dp)
                .clickable(remember { MutableInteractionSource() }, null) { } // taps inside don't close it
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(CoverTint.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Ico(Icons.Rounded.Groups, CoverTint.accent, 22.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GText("Jam", 22, Color.White, FontWeight.Bold, 1)
                    GText(
                        when (Jam.role) {
                            Jam.Role.NONE -> "Listen together"
                            Jam.Role.HOST -> "You're the host"
                            Jam.Role.MEMBER -> if (Jam.members.isEmpty()) "Joining…" else "With ${Jam.hostName}"
                        },
                        13, Dim, maxLines = 1,
                    )
                }
                Box(Modifier.size(36.dp).pressable(0.85f) { Jam.panelOpen = false }, contentAlignment = Alignment.Center) {
                    Ico(Icons.Rounded.Close, Dim, 22.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (Jam.inJam) InJam(ps, backdrop) else StartOrJoin(backdrop)
        }
    }
}

@Composable
private fun StartOrJoin(backdrop: Backdrop) {
    GText("Everyone hears the same song at the same moment, each on their own phone or PC, and anyone can add songs to the queue.", 14, Dim)
    Spacer(Modifier.height(16.dp))
    GlassButton("Start a Jam", backdrop, Modifier.fillMaxWidth(), accent = true, icon = Icons.Rounded.Groups) { Jam.start() }
    Spacer(Modifier.height(20.dp))
    GText("Or join one", 13, Faint, FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    var text by remember { mutableStateOf(Jam.joinCode) }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(Jam.joinCode) { if (Jam.joinCode.isNotEmpty()) text = Jam.joinCode }
    fun go() {
        val c = Jam.parseCode(text)
        if (c == null) { wrong = true; return }
        wrong = false
        Jam.joinCode = ""
        Jam.join(c)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) GText("Code, like K7Q-F2X-D9P", 15, Faint, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(80); wrong = false },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp, letterSpacing = 1.sp),
                cursorBrush = SolidColor(CoverTint.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth().jamTyping().onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { go(); true } else false
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        GlassButton("Join", backdrop) { go() }
    }
    if (wrong) {
        Spacer(Modifier.height(6.dp))
        GText("That doesn't look like a Jam code. It's 9 letters and numbers, or paste the whole invite.", 13, Color(0xFFFFA8A8))
    }
    if (JamPlatform.canScan) {
        Spacer(Modifier.height(10.dp))
        GlassButton("Scan QR code", backdrop, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner) {
            JamPlatform.scanQr { t ->
                val c = Jam.parseCode(t)
                if (c == null) Menus.say("That QR code isn't a Glasswave Jam") else Jam.join(c)
            }
        }
    }

    // Jams nearby: tap to join, no code needed.
    Spacer(Modifier.height(20.dp))
    GText("Jams nearby", 13, Faint, FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (JamNearby.found.isEmpty()) {
        GText(
            if (JamNearby.areaKnown) "None around you right now. When someone near you starts one, it shows up here."
            else "Looking around you…",
            13, Dim,
        )
    }
    for (f in JamNearby.found) {
        NearbyRow(f) { Jam.join(f.code) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NearbyRow(f: JamNearby.Found, onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HostPicture(f, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText("${f.host}'s Jam", 15, Color.White, FontWeight.SemiBold, 1)
            GText(listOfNotNull(f.song, if (f.onWifi) "on this Wi-Fi" else "nearby").joinToString(" · "), 12, Dim, maxLines = 1)
        }
        Box(
            Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Color.White).pressable(0.94f, onClick = onJoin).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { GText("Join", 13, Color(0xFF111111), FontWeight.Bold, 1) }
    }
}

/** The host's YouTube Music picture (or their initial), with a Wi-Fi or "near you" mark in the corner. */
@Composable
private fun HostPicture(f: JamNearby.Found, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (f.photo != null) Cover(f.photo, Modifier.size(size), size / 2, circle = true)
        else Box(Modifier.size(size).clip(CircleShape).background(CoverTint.accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            GText(f.host.take(1).uppercase(), (size.value * 0.42f).toInt(), Color.White, FontWeight.Bold, 1)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(size * 0.44f).clip(CircleShape).background(Color(0xFF1A1D23)),
            contentAlignment = Alignment.Center,
        ) { Ico(if (f.onWifi) Icons.Rounded.Wifi else Icons.Rounded.NearMe, CoverTint.accent, size * 0.26f) }
    }
}

/** The Jam nearby that's shown on its own: not in a Jam, not waved away, and not already on the "Playing on…" bar. */
private fun promptFor(localPlaying: Boolean, now: Long): JamNearby.Found? {
    if (Jam.inJam || Jam.panelOpen) return null
    val strip = remoteStripTag(localPlaying, now)
    return JamNearby.found.firstOrNull { it.code !in JamNearby.dismissed && !(strip != null && it.ownTag == strip) }
}

/**
 * "Ada is in a Jam nearby": a card that drops in at the top when someone near you (on this Wi-Fi, or in
 * this area) is hosting a Jam and you're not in one. Their picture, Join or Cancel. Cancel means that
 * Jam doesn't ask again. When the Jam is your own other device's and the "Playing on…" bar already shows
 * it, that bar gets the Join button instead (see [RemoteStrip]), so the two never stack up.
 */
@Composable
fun NearbyJamPrompt(localPlaying: Boolean, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); now = System.currentTimeMillis() } }
    val f = promptFor(localPlaying, now)
    // Keeps the last one while it slides away.
    val last = remember { arrayOfNulls<JamNearby.Found>(1) }
    if (f != null) last[0] = f
    AnimatedVisibility(
        visible = f != null,
        modifier = modifier,
        enter = slideInVertically(smooth(260)) { -it } + fadeIn(smooth(200)),
        exit = slideOutVertically(smooth(220)) { -it } + fadeOut(smooth(180)),
    ) {
        val x = f ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xF2121519))
                .pressable(1f) { } // taps on it don't reach the page underneath
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HostPicture(x, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    GText("${x.host} is in a Jam nearby", 16, Color.White, FontWeight.Bold, 2)
                    GText(
                        listOfNotNull(x.song?.let { "Playing $it" }, if (x.onWifi) "on your Wi-Fi" else "near you").joinToString(" · "),
                        13, CoverTint.accent, maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp)).background(Color.White.copy(alpha = 0.08f))
                        .pressable(0.95f) { JamNearby.dismiss(x.code) },
                    contentAlignment = Alignment.Center,
                ) { GText("Cancel", 14, Color.White, FontWeight.SemiBold, 1) }
                Box(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp)).background(Color.White)
                        .pressable(0.95f) { Jam.join(x.code); Jam.panelOpen = true },
                    contentAlignment = Alignment.Center,
                ) { GText("Join", 14, Color(0xFF111111), FontWeight.Bold, 1) }
            }
        }
    }
}

/** The QR code for [text] (an invite link): which squares are dark. */
fun jamQrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))

/** A QR code on a white card (scanners need the light border). */
@Composable
private fun JamQr(text: String, size: Dp) {
    val m = remember(text) { runCatching { jamQrMatrix(text) }.getOrNull() } ?: return
    Box(Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(size * 0.07f)) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / m.width
            for (y in 0 until m.height) for (x in 0 until m.width) {
                if (m[x, y]) drawRect(Color(0xFF0B0D10), androidx.compose.ui.geometry.Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun InJam(ps: PlayerState, backdrop: Backdrop) {
    // Ticks so "away" and the queue stay current.
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); now = android.os.SystemClock.elapsedRealtime() } }

    Jam.status?.let {
        GText(it, 14, CoverTint.accent, FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
    }

    // The code, to share.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.05f)).padding(16.dp)) {
        GText("Jam code", 12, Faint, FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.text.BasicText(
            Jam.code ?: "",
            style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontFamily = AppFontFamily),
            maxLines = 1,
        )
        // Or scan it: a phone's Jam panel has Scan QR code.
        Jam.inviteLink()?.let { link ->
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                JamQr(link, 168.dp)
                Spacer(Modifier.height(6.dp))
                GText("Scan in Glasswave: Jam, then Scan QR code", 12, Faint, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(JamPlatform.shareLabel, backdrop, Modifier.weight(1f), accent = true, icon = Icons.Rounded.IosShare) {
                Jam.inviteText()?.let { JamPlatform.share(it) }
            }
            GlassButton("Copy code", backdrop, Modifier.weight(1f), icon = Icons.Rounded.ContentCopy) {
                Jam.code?.let { JamPlatform.copy(it) }
            }
        }
    }

    // Strangers nearby can find it too, unless the host says no.
    if (Jam.isHost) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                GText("Show to people nearby", 15, Color.White, FontWeight.SemiBold, 1)
                GText("Anyone near you with Glasswave can see your name and song, and join.", 12, Dim)
            }
            Spacer(Modifier.width(10.dp))
            Toggle(Jam.nearbyOn) { Jam.updateNearby(it) }
        }
    }

    Spacer(Modifier.height(18.dp))
    GText(if (Jam.members.size == 1) "Just you so far" else "In this Jam · ${Jam.members.size}", 13, Faint, FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    for (m in Jam.members) {
        val away = Jam.isAway(m, now)
        PersonRow(m, isMe = Jam.isMe(m), away = away, canRemove = Jam.isHost && !m.host)
        Spacer(Modifier.height(8.dp))
    }
    if (Jam.members.size <= 1 && Jam.isHost) {
        GText("Share the code or let them scan it. People nearby can also join from their Jam panel.", 13, Dim)
        Spacer(Modifier.height(8.dp))
    }

    // What's coming up, and who added it.
    val up = ps.upcoming(5)
    if (up.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        GText("Up next", 13, Faint, FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        for (e in up) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Cover(e.track.thumb, Modifier.size(36.dp), 7.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    GText(e.track.title, 14, Color.White, FontWeight.SemiBold, 1)
                    val by = Jam.addedBy[e.track.url]
                    GText(listOfNotNull(e.track.artist.takeIf { it.isNotBlank() }, by?.let { "Added by $it" }).joinToString(" · "), 12, if (by != null) CoverTint.accent else Dim, maxLines = 1)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    GText(
        if (Jam.isHost) "Anyone in the Jam can play, pause, skip and add songs. Ending the Jam sends everyone back to their own music."
        else "Anything you play or add goes into everyone's queue. Leaving takes you back to your own music.",
        12, Faint,
    )
    Spacer(Modifier.height(14.dp))
    GlassButton(if (Jam.isHost) "End Jam for everyone" else "Leave Jam", backdrop, Modifier.fillMaxWidth()) {
        Jam.leave()
        Jam.panelOpen = false
    }
}

@Composable
private fun PersonRow(m: Jam.Member, isMe: Boolean, away: Boolean, canRemove: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (m.photo != null) Cover(m.photo, Modifier.size(36.dp), 18.dp, circle = true)
            else Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                GText(m.name.take(1).uppercase(), 15, Color.White, FontWeight.Bold, 1)
            }
            // Phone or PC, in the corner.
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(16.dp).clip(CircleShape).background(Color(0xFF1A1D23)),
                contentAlignment = Alignment.Center,
            ) { Ico(if (m.kind == "phone") Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, Dim, 10.dp) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GText(m.name + (if (isMe) " (you)" else ""), 15, if (away) Dim else Color.White, FontWeight.SemiBold, 1)
            GText(
                listOfNotNull(
                    if (m.host) "Host" else null, m.device.takeIf { it.isNotBlank() },
                    if (!m.host && m.lan && !away) "on this Wi-Fi" else null, if (away) "away" else null,
                ).joinToString(" · "),
                12, if (m.host) CoverTint.accent else Dim, maxLines = 1,
            )
        }
        if (canRemove) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f)).pressable(0.88f) { Jam.remove(m.id) }, contentAlignment = Alignment.Center) {
                Ico(Icons.Rounded.Close, Dim, 18.dp)
            }
        }
    }
}

/** Lets a Jam drive and watch this player (see Jam). */
fun jamPlayer(ps: PlayerState): Jam.Player = object : Jam.Player {
    override fun current() = ps.currentTrack()
    override fun wantsToPlay() = ps.wantsToPlay
    override fun sounding() = ps.sounding
    override fun positionMs() = ps.livePosition()
    override fun durationMs() = ps.durationMs
    override fun upcoming(limit: Int) = ps.upcoming(limit).map { it.track }
    override fun play() = ps.play()
    override fun pause() = ps.pause()
    override fun seekTo(ms: Long) = ps.seekTo(ms)
    override fun next() = ps.next()
    override fun previous() = ps.previous()
    override fun load(tracks: List<Track>, index: Int, startMs: Long, play: Boolean) {
        ps.playList(tracks, index, startMs = startMs)
        if (!play) ps.pause()
    }
    override fun setUpcoming(tracks: List<Track>) = ps.setUpcoming(tracks)
    override fun addToQueue(t: Track) = ps.addToQueue(t)
    override fun playNow(t: Track) { ps.playNextTrack(t); ps.next() }
    override fun normalSpeed() { if (ps.speed != 1f) ps.changeSpeed(1f) }
    override fun ownSpeed() { if (ps.speed != Sound.tempo()) ps.changeSpeed(Sound.tempo()) }
}
