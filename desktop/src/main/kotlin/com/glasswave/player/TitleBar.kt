// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Frame

/**
 * Glasswave's own title bar. Windows' bar (solid black, or grey) is taken away and this one is drawn
 * inside the app, see-through, so the colours of the song playing (or of your theme before anything
 * plays) run right up to the top edge. Dragging, double-clicking to maximize, resizing from the top
 * edge and snapping to the sides of the screen all still work: Windows is told which part of the
 * window is the bar.
 * If any of that can't be set up, Windows' own bar simply stays.
 */
object TitleBar {
    /** How tall the bar is. */
    val Height = 32.dp

    /** Room pages leave at the top for the bar (nothing while Windows' own bar is showing). */
    var inset by mutableStateOf(0.dp)
        private set

    /** Whether the window is maximized (the middle button shows "restore" then). */
    var maximized by mutableStateOf(false)
        internal set

    /** Whether the window is the one in use (the bar's text dims when it isn't). */
    var active by mutableStateOf(true)
        internal set

    // Where the bar is, in the window's own pixels (kept up to date by the bar itself).
    @Volatile private var barPx = 0
    @Volatile private var buttonsFromPx = Int.MAX_VALUE

    private interface User32 : com.sun.jna.Library {
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Pointer): Pointer?
        fun CallWindowProcW(proc: Pointer, hwnd: Pointer, msg: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun ScreenToClient(hwnd: Pointer, point: IntArray): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun GetDpiForWindow(hwnd: Pointer): Int
        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
    }

    private fun interface WndProc : Callback {
        fun callback(hwnd: Pointer, msg: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
    }

    /** Kept here so the callback is never thrown away while Windows still calls it. */
    private var proc: WndProc? = null
    private var old: Pointer? = null

    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val HTCLIENT = 1L
    private const val HTCAPTION = 2L
    private const val HTTOP = 12L
    private const val HTTOPLEFT = 13L
    private const val HTTOPRIGHT = 14L

    /** Takes over Windows' title bar for [window]. Call once, after the window is showing. */
    fun install(window: java.awt.Window) {
        if (proc != null || System.getProperty("gw.nativeTitleBar") != null) return
        runCatching {
            val u = Native.load("user32", User32::class.java)
            val hwnd = Native.getWindowPointer(window) ?: return
            fun frame(): Int {
                val dpi = runCatching { u.GetDpiForWindow(hwnd) }.getOrDefault(96).takeIf { it > 0 } ?: 96
                // SM_CYSIZEFRAME + SM_CXPADDEDBORDER: the resize border's thickness.
                return u.GetSystemMetricsForDpi(33, dpi) + u.GetSystemMetricsForDpi(92, dpi)
            }
            val p = WndProc { h, msg, wParam, lParam ->
                when {
                    // The client area takes the whole window except the side and bottom borders.
                    msg == WM_NCCALCSIZE && wParam != null && Pointer.nativeValue(wParam) != 0L && lParam != null -> {
                        val top = lParam.getInt(4)
                        val r = u.CallWindowProcW(old!!, h, msg, wParam, lParam)
                        // Maximized windows hang over the screen edge by the border's size: keep that bit off-screen.
                        lParam.setInt(4, if (u.IsZoomed(h)) top + frame() else top)
                        r
                    }
                    msg == WM_NCHITTEST -> {
                        val r = u.CallWindowProcW(old!!, h, msg, wParam, lParam)
                        if (r == null || Pointer.nativeValue(r) != HTCLIENT) return@WndProc r
                        val lp = Pointer.nativeValue(lParam)
                        val pt = intArrayOf((lp and 0xFFFF).toShort().toInt(), ((lp shr 16) and 0xFFFF).toShort().toInt())
                        u.ScreenToClient(h, pt)
                        val (x, y) = pt[0] to pt[1]
                        val zoomed = u.IsZoomed(h)
                        val edge = frame()
                        val code = when {
                            // The top edge still resizes (the side borders do this by themselves).
                            !zoomed && y < edge && x < edge * 2 -> HTTOPLEFT
                            !zoomed && y < edge && x > window.width * scale(window) - edge * 2 -> HTTOPRIGHT
                            !zoomed && y < edge -> HTTOP
                            y < barPx && x < buttonsFromPx -> HTCAPTION
                            else -> HTCLIENT
                        }
                        Pointer(code)
                    }
                    else -> u.CallWindowProcW(old!!, h, msg, wParam, lParam)
                }
            }
            proc = p
            old = u.SetWindowLongPtrW(hwnd, -4, CallbackReference.getFunctionPointer(p))
            if (old == null) { proc = null; return }
            // Ask Windows to measure the window again with the new rules (SWP_FRAMECHANGED and keep the rest).
            u.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0020 or 0x0001 or 0x0002 or 0x0004 or 0x0010)
            inset = Height
            maximized = (window as? Frame)?.let { it.extendedState and Frame.MAXIMIZED_BOTH != 0 } ?: false
            window.addWindowStateListener { maximized = it.newState and Frame.MAXIMIZED_BOTH != 0 }
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { active = true }
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) { active = false }
            })
        }.onFailure {
            proc = null
            inset = 0.dp
            android.util.Log.w("Glasswave", "Couldn't set up the title bar", it)
        }
    }

    private fun scale(w: java.awt.Window) = w.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    /** The bar itself: the app's name on the left, the three window buttons on the right. */
    @Composable
    fun Bar(window: java.awt.Window, modifier: Modifier = Modifier) {
        if (inset == 0.dp) return
        val text = Color.White.copy(alpha = if (active) 0.85f else 0.45f)
        Row(
            modifier.fillMaxWidth().height(Height).onGloballyPositioned { barPx = it.size.height },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            WaveMark(Modifier.size(16.dp), text)
            Spacer(Modifier.width(8.dp))
            BasicText("Glasswave", style = TextStyle(color = text, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxHeight().onGloballyPositioned { buttonsFromPx = it.positionInWindow().x.toInt() }) {
                WindowButton(text, onClick = { (window as? Frame)?.extendedState = Frame.ICONIFIED }) { c ->
                    drawLine(c, Offset(size.width * 0.5f - 5.dp.toPx(), center.y), Offset(size.width * 0.5f + 5.dp.toPx(), center.y), 1.dp.toPx())
                }
                WindowButton(text, onClick = {
                    (window as? Frame)?.let { it.extendedState = if (maximized) Frame.NORMAL else Frame.MAXIMIZED_BOTH }
                }) { c ->
                    val s = 9.dp.toPx()
                    val w = 1.dp.toPx()
                    val tl = Offset(center.x - s / 2, center.y - s / 2)
                    if (maximized) {
                        // Two overlapping squares: "restore".
                        val k = 2.dp.toPx()
                        drawRect(c, Offset(tl.x, tl.y + k), androidx.compose.ui.geometry.Size(s - k, s - k), style = Stroke(w))
                        drawLine(c, Offset(tl.x + k, tl.y), Offset(tl.x + s, tl.y), w)
                        drawLine(c, Offset(tl.x + s, tl.y), Offset(tl.x + s, tl.y + s - k), w)
                    } else {
                        drawRect(c, tl, androidx.compose.ui.geometry.Size(s, s), style = Stroke(w))
                    }
                }
                WindowButton(text, hoverColor = Color(0xFFC42B1C), onClick = {
                    window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING))
                }) { c ->
                    val s = 5.dp.toPx()
                    drawLine(c, Offset(center.x - s, center.y - s), Offset(center.x + s, center.y + s), 1.dp.toPx(), StrokeCap.Round)
                    drawLine(c, Offset(center.x - s, center.y + s), Offset(center.x + s, center.y - s), 1.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }

    @Composable
    private fun WindowButton(
        color: Color,
        hoverColor: Color = Color.White.copy(alpha = 0.12f),
        width: Dp = 46.dp,
        onClick: () -> Unit,
        icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
    ) {
        val hover = remember { MutableInteractionSource() }
        val over by hover.collectIsHoveredAsState()
        var down by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxHeight()
                .width(width)
                .background(if (over) hoverColor.copy(alpha = hoverColor.alpha * (if (down) 0.7f else 1f)) else Color.Transparent)
                .hoverable(hover)
                .pointerInput(onClick) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> down = true
                                androidx.compose.ui.input.pointer.PointerEventType.Release -> {
                                    // Only when let go over the button, like Windows' own.
                                    if (down && e.changes.any { it.position.x in 0f..size.width.toFloat() && it.position.y in 0f..size.height.toFloat() }) onClick()
                                    down = false
                                }
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> down = false
                                else -> {}
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) { icon(if (over && hoverColor.red > 0.5f && hoverColor.green < 0.4f) Color.White else color) }
        }
    }
}

/** Room for the title bar at the top of a page (Windows has no status bar, so this is all it is). */
fun Modifier.statusBarsPadding(): Modifier = this.padding(top = TitleBar.inset)

/** The same, for screens that also keep clear of the bottom on phones (nothing at the bottom here). */
fun Modifier.systemBarsPadding(): Modifier = this.padding(top = TitleBar.inset)
