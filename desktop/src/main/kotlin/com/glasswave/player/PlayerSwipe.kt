// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.event.MouseWheelEvent
import kotlin.math.abs

/**
 * Two fingers swiped down on a trackpad close the big player, like swiping it down on a phone.
 *
 * Only a quick, deliberate swipe counts: it has to travel far enough within a short time, all in
 * the one direction. A mouse wheel never does it (its whole steps are left alone), nor does a slow
 * scroll, the glide that carries on after the fingers lift, or a swipe that scrolls something inside
 * the player (Up next, the menu, the lyrics).
 */
fun Modifier.swipeDownToClose(enabled: () -> Boolean, onClose: () -> Unit): Modifier = pointerInput(Unit) {
    var start = 0L
    var last = 0L
    var travel = 0f
    var smooth = 0
    var spoiled = false
    var done = false
    awaitPointerEventScope {
        while (true) {
            // Final pass: by now anything inside the player that scrolled has taken the event.
            val e = awaitPointerEvent(PointerEventPass.Final)
            if (e.type != PointerEventType.Scroll) continue
            val c = e.changes.firstOrNull() ?: continue
            val now = System.currentTimeMillis()
            // A pause starts a new swipe; one swipe closes the player at most once.
            if (now - last > SwipeDown.GAP_MS) { start = now; travel = 0f; smooth = 0; spoiled = false; done = false }
            last = now
            if (done || spoiled) continue
            val d = c.scrollDelta
            if (c.isConsumed || !enabled() || abs(d.x) > abs(d.y)) { spoiled = true; continue }
            // Fingers moving down ("pulling" the page down), whichever way the computer scrolls.
            val down = -d.y * SwipeDown.sign
            if (down < -0.05f) { spoiled = true; continue }
            // A trackpad sends many small, uneven steps; a mouse wheel sends whole ones.
            val precise = (e.nativeEvent as? MouseWheelEvent)?.preciseWheelRotation
            if (precise != null && precise != 0.0 && precise != Math.rint(precise)) smooth++
            travel += down
            if (now - start > SwipeDown.WITHIN_MS) { spoiled = true; continue }
            if (travel >= SwipeDown.TRAVEL && smooth >= SwipeDown.MIN_STEPS) {
                done = true
                android.util.Log.i("Glasswave", "Big player closed by a swipe down")
                onClose()
            }
        }
    }
}

internal object SwipeDown {
    /** Quiet this long and the next scroll is a new swipe (the glide after a swipe has no gaps). */
    const val GAP_MS = 250L
    /** The swipe has to get there within this long from its start: slower is browsing. */
    const val WITHIN_MS = 600L
    /** How far, in scroll steps (about one mouse-wheel notch each). */
    const val TRAVEL = 2.5f
    /** At least this many trackpad steps (a mouse wheel's whole steps don't count). */
    const val MIN_STEPS = 3

    /**
     * 1 when fingers moving down scroll the page up ("natural" scrolling: the Mac's and Windows
     * touchpads' usual way), -1 when someone has turned that off on a Mac.
     */
    val sign: Float by lazy {
        if (!Os.isMac) return@lazy 1f
        val natural = runCatching {
            val p = ProcessBuilder("defaults", "read", "-g", "com.apple.swipescrolldirection").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out != "0" && out != "false"
        }.getOrDefault(true)
        if (natural) 1f else -1f
    }
}
