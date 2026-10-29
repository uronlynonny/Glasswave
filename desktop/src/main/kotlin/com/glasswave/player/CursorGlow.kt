// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/*
 * The soft light that follows the mouse on the playing screen and the lyrics, like the one on
 * the website. One screen watches the mouse ([trackCursorGlow]); a layer drawn just above the
 * background ([CursorGlowLayer]) paints the light. It only animates while the light is moving.
 */

@Stable
class CursorGlow internal constructor(private val scope: CoroutineScope) {
    /** Where the light is now and where the mouse is, in window pixels. */
    internal var pos by mutableStateOf(Offset.Zero)
    private var target = Offset.Zero
    /** 0 = hidden, 1 = fully shown. */
    internal var shown by mutableFloatStateOf(0f)
    private var wantShown = 0f
    private var job: Job? = null

    internal fun moveTo(p: Offset) {
        target = p
        // Coming back after hiding: start right under the mouse instead of flying in.
        if (shown < 0.02f) pos = p
        wantShown = 1f
        wake()
    }

    internal fun hide() { wantShown = 0f; wake() }

    private fun wake() {
        if (!Settings.animations) { pos = target; shown = wantShown; return }
        if (job?.isActive == true) return
        job = scope.launch {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                // Steps scaled to the time since the last frame, so it feels the same at any frame rate.
                val frames = if (last == 0L) 1f else ((now - last) / 16_666_667f).coerceIn(0.1f, 4f)
                last = now
                val follow = 1f - 0.45f.pow(frames)
                val fade = 1f - 0.9f.pow(frames)
                val d = target - pos
                pos = if (d.getDistance() < 0.5f) target else pos + d * follow
                shown = if (abs(wantShown - shown) < 0.01f) wantShown else shown + (wantShown - shown) * fade
                if (pos == target && shown == wantShown) break
            }
        }
    }
}

@Composable
fun rememberCursorGlow(): CursorGlow {
    val scope = rememberCoroutineScope()
    return remember { CursorGlow(scope) }
}

/** Watches the mouse over this screen. Only looks at the events, so clicks, scrolling and hover still work. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trackCursorGlow(glow: CursorGlow): Modifier {
    var origin = Offset.Zero
    return this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .onPointerEvent(PointerEventType.Move) { e -> e.changes.firstOrNull()?.let { glow.moveTo(origin + it.position) } }
        .onPointerEvent(PointerEventType.Enter) { e -> e.changes.firstOrNull()?.let { glow.moveTo(origin + it.position) } }
        .onPointerEvent(PointerEventType.Exit) { glow.hide() }
}

/** Paints the light. Put it right after the background, before the screen's content. */
@Composable
fun CursorGlowLayer(glow: CursorGlow, color: Color = CoverTint.accent) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Spacer(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val a = glow.shown
                if (a <= 0f) return@drawBehind
                val radius = 240.dp.toPx()
                val center = glow.pos - origin
                drawCircle(
                    Brush.radialGradient(
                        0f to color.copy(alpha = 0.16f * a),
                        0.6f to Color.Transparent,
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            },
    )
}
