// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.graphics.toAwtImage
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext as LocalContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * Sharing a song: a card like Spotify's, with the cover on a background in the cover's own colour
 * (whatever the app's colour setting is), the song's name and artist, and Glasswave's mark. It can
 * be sent as a picture with the link, or just the link.
 */
object ShareCard {
    var track by mutableStateOf<Track?>(null)

    /** The link that opens the song for anyone (YouTube Music). */
    fun link(t: Track): String = if (t.url.startsWith("gwmatch") || !t.url.startsWith("http")) {
        "https://music.youtube.com/search?q=" + android.net.Uri.encode("${t.title} ${t.artist}")
    } else t.url

    fun text(t: Track) = "${t.title} · ${t.artist}\n${link(t)}"
}

@Composable
fun BoxScope.ShareCardLayer() {
    var last by remember { mutableStateOf<Track?>(null) }
    ShareCard.track?.let { last = it }
    val open = ShareCard.track != null
    AnimatedVisibility(open, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000))
                .clickable(remember { MutableInteractionSource() }, null) { ShareCard.track = null },
        )
    }
    AnimatedVisibility(
        open,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(tween(160)) + scaleIn(tween(220, easing = Motion.ease), 0.92f),
        exit = fadeOut(tween(120)),
    ) {
        last?.let { ShareSheet(it) }
    }
}

@Composable
private fun ShareSheet(t: Track) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val art = hiRes(t.thumb)
    val color = coverColor(art)
    val actions = shareActions(t)
    Column(
        Modifier.padding(24.dp).width(300.dp).clickable(remember { MutableInteractionSource() }, null) { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
        ) { CardFace(t, art, color) }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (a in actions) {
                ShareButton(a.icon, a.label, a.primary) {
                    if (busy) return@ShareButton
                    busy = true
                    scope.launch {
                        try {
                            val pic = if (a.needsPicture) layer.toImageBitmap() else null
                            a.run(pic)
                        } catch (e: Exception) {
                            Menus.say("Couldn't share that")
                        } finally {
                            busy = false
                        }
                    }
                }
            }
            ShareButton(Icons.Rounded.Close, "Close", false) { ShareCard.track = null }
        }
    }
}

/** The card itself: what the picture looks like. */
@Composable
private fun CardFace(t: Track, art: String?, color: Color) {
    val top = lerp(color, Color.White, 0.06f)
    val bottom = lerp(color, Color.Black, 0.55f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(22.dp),
    ) {
        Cover(art, Modifier.fillMaxWidth().aspectRatio(1f).shadow(18.dp, RoundedCornerShape(14.dp)), 14.dp)
        Spacer(Modifier.height(18.dp))
        GText(t.title, 22, Color.White, FontWeight.Bold, 2)
        Spacer(Modifier.height(2.dp))
        GText(t.artist, 15, Color.White.copy(alpha = 0.75f), FontWeight.Medium, 1)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaveDot(Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            GText("Glasswave", 13, Color.White.copy(alpha = 0.85f), FontWeight.SemiBold, 1)
        }
    }
}

/** Glasswave's mark: the wave on a lime dot. */
@Composable
private fun WaveDot(modifier: Modifier) {
    Canvas(modifier) {
        drawCircle(Lime)
        val s = size.width / 25f
        val p = Path().apply {
            moveTo(5.5f * s, 13.5f * s)
            cubicTo(8.1f * s, 8.3f * s, 10.7f * s, 8.3f * s, 13f * s, 13.5f * s)
            cubicTo(15.3f * s, 18.7f * s, 17.9f * s, 18.7f * s, 19.5f * s, 13.5f * s)
        }
        drawPath(p, Color(0xFF0B0D09), style = Stroke(2.6f * s, cap = StrokeCap.Round))
    }
}

@Composable
private fun ShareButton(icon: ImageVector, label: String, primary: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable(0.92f, onClick = onClick)) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(if (primary) Color.White else Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, if (primary) Color.Black else Color.White, 24.dp) }
        Spacer(Modifier.height(6.dp))
        GText(label, 12, Color.White.copy(alpha = 0.85f), FontWeight.Medium, 1)
    }
}

private class ShareAction(val icon: ImageVector, val label: String, val primary: Boolean, val needsPicture: Boolean, val run: suspend (ImageBitmap?) -> Unit)

/** The cover's main colour, made deep enough for white writing (dark grey until it's read). */
@Composable
private fun coverColor(art: String?): Color {
    var c by remember(art) { mutableStateOf(Color(0xFF2A2D33)) }
    val ctx = LocalContext.current
    LaunchedEffect(art) {
        if (art == null) return@LaunchedEffect
        val bmp = (SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(art).size(96).build()) as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val p = withContext(Dispatchers.Default) { Palette.from(bmp).maximumColorCount(16).generate() }
        val rgb = listOfNotNull(p.vibrantSwatch, p.darkVibrantSwatch, p.dominantSwatch, p.mutedSwatch).firstOrNull()?.rgb ?: return@LaunchedEffect
        var x = Color(rgb)
        repeat(6) { if (x.luminance() > 0.32f) x = lerp(x, Color.Black, 0.18f) }
        c = x
    }
    return c
}

/** PC: copy the card as a picture (paste it in WhatsApp, Discord...), save it, or copy the link. */
@Composable
private fun shareActions(t: Track): List<ShareAction> = remember(t) {
    listOf(
        ShareAction(Icons.Rounded.ContentCopy, "Copy picture", true, true) { pic ->
            val img = pic!!.toAwtImage()
            val data = object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.imageFlavor
                override fun getTransferData(f: DataFlavor): Any = img
            }
            Toolkit.getDefaultToolkit().systemClipboard.setContents(data, null)
            Menus.say("Picture copied. Paste it anywhere")
        },
        ShareAction(Icons.Rounded.Download, "Save", false, true) { pic ->
            val home = java.io.File(System.getProperty("user.home"))
            val dir = java.io.File(home, "Pictures").takeIf { it.isDirectory } ?: home
            val name = (t.title + " - " + t.artist).replace(Regex("""[\\/:*?"<>|]"""), "").take(80).trim()
            val file = java.io.File(dir, "$name.png")
            withContext(Dispatchers.IO) { javax.imageio.ImageIO.write(pic!!.toAwtImage(), "png", file) }
            Menus.say("Saved to ${dir.name}")
        },
        ShareAction(Icons.Rounded.Link, "Copy link", false, false) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ShareCard.text(t)), null)
            Menus.say("Link copied")
        },
    )
}
