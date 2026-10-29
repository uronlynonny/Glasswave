// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drag to reorder rows in a list (the queue, your playlists): hold a row's handle and drag it up or
 * down. The list scrolls by itself near its top and bottom. While you drag, the screen shows its own
 * copy of the list ([onStart] makes it, [onMove] moves one place); [onDone] says where the row
 * started and where it ended once you let go.
 */
class Reorder internal constructor(internal val list: LazyListState) {
    /** The key of the row being dragged (null when nothing is). */
    var dragKey by mutableStateOf<Any?>(null)
        private set
    /** How far the dragged row sits from its place in the list, in pixels. */
    var offset by mutableFloatStateOf(0f)
        internal set

    internal var keys: () -> List<Any> = { emptyList() }
    internal var onStart: () -> Unit = {}
    internal var onMove: (Int, Int) -> Unit = { _, _ -> }
    internal var onDone: (Int, Int) -> Unit = { _, _ -> }
    private var from = -1
    /** The dragged row's place on screen before its last move: no second move until the screen catches up. */
    private var staleIndex = -1

    fun start(key: Any) {
        onStart()
        val k = keys()
        from = k.indexOf(key)
        if (from < 0) return
        staleIndex = -1
        offset = 0f
        dragKey = key
    }

    fun drag(dy: Float) {
        if (dragKey == null) return
        offset += dy
        settle()
    }

    fun end() {
        val key = dragKey ?: return
        val to = keys().indexOf(key)
        dragKey = null
        offset = 0f
        if (from >= 0 && to >= 0 && to != from) onDone(from, to)
        from = -1
    }

    /** Swaps with a neighbour once the dragged row's middle passes the neighbour's middle. */
    internal fun settle() {
        val key = dragKey ?: return
        val k = keys()
        val info = list.layoutInfo.visibleItemsInfo
        val me = info.firstOrNull { it.key == key } ?: return
        if (me.index == staleIndex) return
        staleIndex = -1
        val mid = me.offset + offset + me.size / 2f
        val i = k.indexOf(key)
        if (i < 0) return
        if (i < k.lastIndex) {
            val n = info.firstOrNull { it.key == k[i + 1] }
            if (n != null && mid > n.offset + n.size / 2f) {
                staleIndex = me.index
                onMove(i, i + 1)
                offset -= (n.offset + n.size) - (me.offset + me.size)
                return
            }
        }
        if (i > 0) {
            val n = info.firstOrNull { it.key == k[i - 1] }
            if (n != null && mid < n.offset + n.size / 2f) {
                staleIndex = me.index
                onMove(i, i - 1)
                offset += me.offset - n.offset
            }
        }
    }
}

@Composable
fun rememberReorder(
    list: LazyListState,
    keys: () -> List<Any>,
    onStart: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onDone: (Int, Int) -> Unit,
): Reorder {
    val r = remember(list) { Reorder(list) }
    r.keys = keys
    r.onStart = onStart
    r.onMove = onMove
    r.onDone = onDone
    // Near the top or bottom of the list while dragging: scroll, faster the closer you are.
    LaunchedEffect(r, r.dragKey) {
        val key = r.dragKey ?: return@LaunchedEffect
        while (r.dragKey == key) {
            withFrameNanos { }
            val layout = list.layoutInfo
            val me = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
            val top = me.offset + r.offset
            val bottom = top + me.size
            val edge = 140f
            val speed = when {
                top < layout.viewportStartOffset + edge -> -(1f - ((top - layout.viewportStartOffset) / edge).coerceIn(0f, 1f)) * 22f
                bottom > layout.viewportEndOffset - edge -> (1f - ((layout.viewportEndOffset - bottom) / edge).coerceIn(0f, 1f)) * 22f
                else -> 0f
            }
            if (speed != 0f) {
                val used = list.scrollBy(speed)
                if (used != 0f) {
                    r.offset += used
                    r.settle()
                }
            }
        }
    }
    return r
}

/** The row being dragged floats above the others, a touch bigger, and follows your finger. */
fun Modifier.reorderRow(r: Reorder, key: Any): Modifier = composed {
    val dragging = r.dragKey == key
    this
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer {
            translationY = if (dragging) r.offset else 0f
            val s = if (dragging) 1.02f else 1f
            scaleX = s
            scaleY = s
        }
        .background(if (dragging) Color(0xF01A1D25) else Color.Transparent, RoundedCornerShape(12.dp))
}

/** The ≡ handle: hold it and drag to move the row. */
@Composable
fun ReorderHandle(r: Reorder, key: Any, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).pointerInput(key) {
            detectDragGestures(
                onDragStart = { r.start(key) },
                onDragEnd = { r.end() },
                onDragCancel = { r.end() },
            ) { change, d ->
                change.consume()
                r.drag(d.y)
            }
        },
        contentAlignment = Alignment.Center,
    ) { Ico(Icons.Rounded.DragHandle, if (r.dragKey == key) Color.White else Faint, 22.dp) }
}
