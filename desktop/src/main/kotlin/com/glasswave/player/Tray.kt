// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import coil3.compose.AsyncImage
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage

/**
 * Windows: Glasswave's icon in the system tray (by the clock), so it can be tucked away there and
 * keep playing without a taskbar button. A click brings Glasswave back; a right-click opens its
 * menu, in the song's colours: play/pause, next, previous, the mini player and quit.
 */
object Tray {
    /** Tucked away: the main window is hidden and only the tray icon is left. */
    var tucked by mutableStateOf(false)
    /** The tray icon is there right now (for the checks). */
    @Volatile var iconShown = false
        internal set
    /** Tray settings are on and Windows has a tray to put the icon in. */
    val wanted get() = Os.isWindows && (Settings.trayMinimize || Settings.trayClose) && runCatching { SystemTray.isSupported() }.getOrDefault(false)
    internal var icon: TrayIcon? = null

    /** Says once, the first time, where Glasswave went. */
    fun tellOnce() {
        // Test copies stay quiet, so no Windows notification pops up while checks run.
        if (Settings.trayTold || System.getProperty("gw.memScript") != null) return
        Settings.updateTrayTold()
        icon?.displayMessage("Glasswave is still here", "It's in the tray by the clock and keeps playing. Click its icon to bring it back.", TrayIcon.MessageType.NONE)
    }
}

/** The icon at every size Windows might want (16 px at 100% up to 64 px for very sharp screens). */
private fun trayImage(): java.awt.Image {
    val src = Thread.currentThread().contextClassLoader.getResourceAsStream("glasswave-icon.png")!!.use { javax.imageio.ImageIO.read(it) }
    fun scaled(size: Int): BufferedImage {
        // Halve step by step first, so small sizes stay smooth instead of jagged.
        var img: BufferedImage = src
        while (img.width / 2 >= size) img = draw(img, img.width / 2)
        return if (img.width == size) img else draw(img, size)
    }
    return BaseMultiResolutionImage(*listOf(16, 20, 24, 32, 40, 48, 64).map { scaled(it) }.toTypedArray())
}

private fun draw(from: BufferedImage, size: Int): BufferedImage {
    val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(from, 0, 0, size, size, null)
    g.dispose()
    return out
}

/** The tray icon, there while a tray setting is on. [onShow] brings the main window back. */
@Composable
fun GlasswaveTray(ps: PlayerState, onShow: () -> Unit, onQuit: () -> Unit) {
    if (!Tray.wanted) return
    val show by rememberUpdatedState(onShow)
    DisposableEffect(Unit) {
        val icon = TrayIcon(trayImage(), "Glasswave").apply {
            // A left click brings Glasswave back (Windows sends a double-click as an action too).
            // A right-click opens Glasswave's own menu (see TrayMenuWindow) instead of Windows' plain white one.
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) { TrayMenu.open = false; show() } }
                override fun mouseReleased(e: MouseEvent) { if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) TrayMenu.openAt(e.xOnScreen, e.yOnScreen) }
            })
            addActionListener { TrayMenu.open = false; show() }
        }
        val added = runCatching { SystemTray.getSystemTray().add(icon) }.isSuccess
        if (added) { Tray.icon = icon; Tray.iconShown = true }
        onDispose {
            if (added) runCatching { SystemTray.getSystemTray().remove(icon) }
            if (Tray.icon === icon) Tray.icon = null
            Tray.iconShown = false
            TrayMenu.open = false
        }
    }
    // Keep the tooltip up to date with the song.
    LaunchedEffect(Unit) {
        snapshotFlow { ps.title to ps.artist }.collect { (title, artist) ->
            val line = when {
                title.isBlank() -> "Nothing playing"
                artist.isBlank() -> title
                else -> "$title · $artist"
            }
            // Windows cuts tray tooltips off at 127 characters.
            Tray.icon?.toolTip = (if (title.isBlank()) "Glasswave" else "Glasswave · $line").take(127)
        }
    }
    TrayMenuWindow(ps, onShow = { show() }, onQuit = onQuit)
}

/** The tray icon's right-click menu: whether it's open, and where the click was (screen units). */
object TrayMenu {
    var open by mutableStateOf(false)
    /** Made the first time it opens, then kept (hidden) so it opens instantly after that. */
    internal var made by mutableStateOf(false)
    internal var at by mutableStateOf(java.awt.Point(0, 0))
    internal var opens by mutableIntStateOf(0)

    fun openAt(x: Int, y: Int) {
        at = java.awt.Point(x, y)
        made = true
        opens++
        open = true
    }
}

private const val MENU_W = 272
private const val ROW_H = 38
private const val HEAD_H = 68
private const val CONTROLS_H = 56
private const val GAP_H = 9
private const val PAD = 6
private const val MENU_H = PAD + HEAD_H + CONTROLS_H + GAP_H + ROW_H * 2 + GAP_H + ROW_H + PAD

/** Where the menu goes: just above the click (the taskbar is usually at the bottom), kept on that screen. */
private fun menuSpot(at: java.awt.Point): java.awt.Point {
    val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    val gc = ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(at) } ?: ge.defaultScreenDevice.defaultConfiguration
    val b = gc.bounds
    val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val left = b.x + ins.left + 8
    val top = b.y + ins.top + 8
    val right = b.x + b.width - ins.right - 8
    val bottom = b.y + b.height - ins.bottom - 8
    val x = (at.x - MENU_W / 2).coerceIn(left, maxOf(left, right - MENU_W))
    var y = at.y - MENU_H - 8
    // No room above (a taskbar at the top): below the click instead.
    if (y < top) y = at.y + 8
    return java.awt.Point(x, y.coerceIn(top, maxOf(top, bottom - MENU_H)))
}

private interface TrayDwm : com.sun.jna.Library {
    fun DwmSetWindowAttribute(hwnd: com.sun.jna.Pointer, attr: Int, value: com.sun.jna.Pointer, size: Int): Int
}

/** Windows 11: rounded corners and a dark edge, like Windows' own menus. */
private fun roundCorners(w: java.awt.Window) {
    runCatching {
        val dwm = com.sun.jna.Native.load("dwmapi", TrayDwm::class.java)
        val hwnd = com.sun.jna.Native.getWindowPointer(w)
        dwm.DwmSetWindowAttribute(hwnd, 20, com.sun.jna.ptr.IntByReference(1).pointer, 4) // dark mode
        dwm.DwmSetWindowAttribute(hwnd, 33, com.sun.jna.ptr.IntByReference(2).pointer, 4) // round corners
    }
}

/**
 * The right-click menu, in the song's colours like the mini player: the song, play/pause and skip,
 * the mini player, open and quit. Closes when you click anywhere else or press Esc.
 */
@Composable
private fun TrayMenuWindow(ps: PlayerState, onShow: () -> Unit, onQuit: () -> Unit) {
    if (!TrayMenu.made) return
    val spot = remember(TrayMenu.opens) { menuSpot(TrayMenu.at) }
    val state = rememberDialogState(position = WindowPosition(spot.x.dp, spot.y.dp), size = DpSize(MENU_W.dp, MENU_H.dp))
    LaunchedEffect(spot) { state.position = WindowPosition(spot.x.dp, spot.y.dp) }
    DialogWindow(
        onCloseRequest = { TrayMenu.open = false },
        state = state,
        visible = TrayMenu.open,
        title = "Glasswave menu",
        undecorated = true,
        resizable = false,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { TrayMenu.open = false; true } else false
        },
    ) {
        LaunchedEffect(Unit) {
            window.isAlwaysOnTop = true
            window.background = java.awt.Color(0x10, 0x11, 0x14)
            if (Os.isWindows) roundCorners(window)
            window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent) { TrayMenu.open = false }
            })
        }
        // Each time it opens: to the front, with the keyboard, so Esc and clicking elsewhere close it.
        LaunchedEffect(TrayMenu.opens, TrayMenu.open) {
            if (!TrayMenu.open) return@LaunchedEffect
            window.toFront()
            window.requestFocus()
            // Windows doesn't always let a window take the keyboard from the taskbar, so a click
            // anywhere outside the menu closes it too.
            if (!Os.isWindows) return@LaunchedEffect
            val user32 = runCatching { com.sun.jna.platform.win32.User32.INSTANCE }.getOrNull() ?: return@LaunchedEffect
            kotlinx.coroutines.delay(150)
            while (TrayMenu.open) {
                val down = (user32.GetAsyncKeyState(0x01).toInt() and 0x8000) != 0 || (user32.GetAsyncKeyState(0x02).toInt() and 0x8000) != 0
                if (down) {
                    val p = java.awt.MouseInfo.getPointerInfo()?.location
                    if (p != null && !window.bounds.contains(p)) TrayMenu.open = false
                }
                kotlinx.coroutines.delay(40)
            }
        }
        TrayMenuCard(ps, onShow, onQuit)
    }
}

@Composable
private fun TrayMenuCard(ps: PlayerState, onShow: () -> Unit, onQuit: () -> Unit) {
    val colors = rememberCoverColors(hiRes(ps.art))
    val close = { TrayMenu.open = false }
    Box(
        Modifier
            .fillMaxSize()
            // The song's colours, darkened enough for white text, with a soft shine across the top.
            .background(Brush.linearGradient(listOf(lerp(colors.a, Color.Black, 0.38f), lerp(colors.b, Color.Black, 0.3f))))
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f)))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.fillMaxSize().padding(PAD.dp)) {
            // The song.
            Row(Modifier.fillMaxWidth().height(HEAD_H.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    if (ps.art != null) {
                        AsyncImage(ps.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Image(AppIconPainter, null, Modifier.size(34.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GText(ps.title.ifBlank { "Nothing playing" }, 14, Color.White, FontWeight.SemiBold, 1)
                    GText(if (ps.title.isBlank()) "Glasswave" else ps.artist, 12, Color.White.copy(alpha = 0.72f), maxLines = 1)
                }
            }
            // Previous, play/pause, next.
            val hasSong = ps.title.isNotBlank()
            Row(Modifier.fillMaxWidth().height(CONTROLS_H.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.SkipPrevious, hasSong, big = false) { ps.previous() }
                Spacer(Modifier.width(18.dp))
                RoundButton(if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, hasSong, big = true) { ps.toggle() }
                Spacer(Modifier.width(18.dp))
                RoundButton(Icons.Rounded.SkipNext, hasSong, big = false) { ps.next() }
            }
            Line()
            MenuRow(Icons.Rounded.PictureInPictureAlt, "Mini player", on = Mini.enabled) { Mini.setEnabled(!Mini.enabled); close() }
            MenuRow(Icons.Rounded.OpenInNew, "Open Glasswave") { close(); onShow() }
            Line()
            MenuRow(Icons.Rounded.PowerSettingsNew, "Quit Glasswave") { close(); onQuit() }
        }
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(GAP_H.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, enabled: Boolean, big: Boolean, onClick: () -> Unit) {
    val size = if (big) 42.dp else 34.dp
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (big) Color.White.copy(alpha = if (enabled) 1f else 0.4f) else Color.White.copy(alpha = 0.12f))
            .pressable(0.9f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, if (big) Color(0xFF111111) else Color.White.copy(alpha = if (enabled) 1f else 0.4f), size * 0.58f)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, on: Boolean? = null, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_H.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .hoverable(hover)
            .pressable(0.98f, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, Color.White.copy(alpha = 0.85f), 18.dp)
        Spacer(Modifier.width(12.dp))
        GText(label, 13, Color.White, modifier = Modifier.weight(1f))
        if (on != null) Toggle(on)
    }
}

/** A small on/off switch. */
@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(30.dp, 17.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.22f)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(2.dp).size(13.dp).clip(CircleShape).background(if (on) Color(0xFF111111) else Color.White))
    }
}
