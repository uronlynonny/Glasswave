package com.glasswave.player

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

val Accent = Color(0xFF8FB4FF)

/** The "glass" look: a dark tint, a soft light gradient, and a bright edge that catches light. */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    base: Float = 0.30f,
    light: Float = 0.16f,
): Modifier = this
    .clip(shape)
    .background(Color(0xFF0B0F18).copy(alpha = base))
    .background(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = light), Color.White.copy(alpha = light * 0.25f)),
        ),
    )
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.60f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.28f),
            ),
        ),
        shape,
    )

@Composable
fun GText(
    text: String,
    size: Int,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun DrawScope.blob(color: Color, cx: Float, cy: Float, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r),
        radius = r,
        center = Offset(cx, cy),
    )
}

/**
 * Slowly drifting colour behind everything, plus the song's artwork blurred on Android 12+.
 * Colour blobs are drawn in the draw phase only, so animating them never recomposes the screen.
 */
@Composable
fun GlassBackground(
    art: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "bg")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(modifier.fillMaxSize().background(Color(0xFF070A10))) {
        if (art != null && Build.VERSION.SDK_INT >= 31) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(70.dp).alpha(0.7f),
            )
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                val w = size.width
                val h = size.height
                blob(Color(0x884C7DFF), w * (0.15f + 0.6f * t), h * 0.15f, w * 0.95f)
                blob(Color(0x77B04CFF), w * (0.9f - 0.6f * t), h * 0.55f, w * 0.9f)
                blob(Color(0x6636D6C4), w * (0.2f + 0.4f * t), h * 0.95f, w * 0.8f)
            },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x55000000), Color(0xBB000000))),
            ),
        )
        content()
    }
}

/* ---- Icons, drawn directly so the app needs no icon library ---- */

@Composable
fun PlayIcon(color: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.28f, h * 0.15f)
        lineTo(w * 0.86f, h * 0.5f)
        lineTo(w * 0.28f, h * 0.85f)
        close()
    }
    drawPath(p, color)
}

@Composable
fun PauseIcon(color: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val w = size.width
    val h = size.height
    val r = CornerRadius(w * 0.06f)
    drawRoundRect(color, Offset(w * 0.2f, h * 0.15f), Size(w * 0.22f, h * 0.7f), r)
    drawRoundRect(color, Offset(w * 0.58f, h * 0.15f), Size(w * 0.22f, h * 0.7f), r)
}

@Composable
fun NextIcon(color: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.15f, h * 0.2f)
        lineTo(w * 0.68f, h * 0.5f)
        lineTo(w * 0.15f, h * 0.8f)
        close()
    }
    drawPath(p, color)
    drawRoundRect(color, Offset(w * 0.74f, h * 0.2f), Size(w * 0.12f, h * 0.6f), CornerRadius(w * 0.04f))
}

@Composable
fun PrevIcon(color: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.85f, h * 0.2f)
        lineTo(w * 0.32f, h * 0.5f)
        lineTo(w * 0.85f, h * 0.8f)
        close()
    }
    drawPath(p, color)
    drawRoundRect(color, Offset(w * 0.14f, h * 0.2f), Size(w * 0.12f, h * 0.6f), CornerRadius(w * 0.04f))
}

@Composable
fun SearchIcon(color: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val w = size.width
    drawCircle(color, radius = w * 0.31f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(w * 0.1f))
    drawLine(color, Offset(w * 0.66f, w * 0.66f), Offset(w * 0.88f, w * 0.88f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
}

@Composable
fun ChevronDownIcon(color: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val w = size.width
    val h = size.height
    val sw = w * 0.09f
    drawLine(color, Offset(w * 0.2f, h * 0.38f), Offset(w * 0.5f, h * 0.66f), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.5f, h * 0.66f), Offset(w * 0.8f, h * 0.38f), strokeWidth = sw, cap = StrokeCap.Round)
}

/** A round glass button holding an icon. */
@Composable
fun GlassCircle(size: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.size(size).glass(CircleShape, base = 0.25f, light = 0.2f), contentAlignment = androidx.compose.ui.Alignment.Center) {
        content()
    }
}
