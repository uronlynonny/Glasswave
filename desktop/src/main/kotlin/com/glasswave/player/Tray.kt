// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.awt.CheckboxMenuItem
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage

/**
 * Windows: Glasswave's icon in the system tray (by the clock), so it can be tucked away there and
 * keep playing without a taskbar button. A click brings Glasswave back; a right-click has the
 * controls: play/pause, next, previous, the mini player and quit.
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
    val quit by rememberUpdatedState(onQuit)
    val nowPlaying = remember { MenuItem("Nothing playing").apply { isEnabled = false } }
    val playPause = remember { MenuItem("Play") }
    val next = remember { MenuItem("Next") }
    val previous = remember { MenuItem("Previous") }
    val mini = remember { CheckboxMenuItem("Mini player") }
    DisposableEffect(Unit) {
        val menu = PopupMenu().apply {
            add(nowPlaying)
            addSeparator()
            add(playPause.apply { addActionListener { ps.toggle() } })
            add(next.apply { addActionListener { ps.next() } })
            add(previous.apply { addActionListener { ps.previous() } })
            addSeparator()
            add(mini.apply { addItemListener { Mini.setEnabled(state) } })
            add(MenuItem("Open Glasswave").apply { addActionListener { show() } })
            addSeparator()
            add(MenuItem("Quit Glasswave").apply { addActionListener { quit() } })
        }
        val icon = TrayIcon(trayImage(), "Glasswave", menu).apply {
            // A left click brings Glasswave back (Windows sends a double-click as an action too).
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) show() }
            })
            addActionListener { show() }
        }
        val added = runCatching { SystemTray.getSystemTray().add(icon) }.isSuccess
        if (added) { Tray.icon = icon; Tray.iconShown = true }
        onDispose {
            if (added) runCatching { SystemTray.getSystemTray().remove(icon) }
            if (Tray.icon === icon) Tray.icon = null
            Tray.iconShown = false
        }
    }
    // Keep the menu and the tooltip up to date with the song.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(ps.title to ps.artist, ps.playing, Mini.enabled) }.collect { (song, playing, miniOn) ->
            val (title, artist) = song
            val line = when {
                title.isBlank() -> "Nothing playing"
                artist.isBlank() -> title
                else -> "$title · $artist"
            }
            nowPlaying.label = line.take(60)
            playPause.label = if (playing) "Pause" else "Play"
            val hasSong = title.isNotBlank()
            playPause.isEnabled = hasSong
            next.isEnabled = hasSong
            previous.isEnabled = hasSong
            mini.state = miniOn
            // Windows cuts tray tooltips off at 127 characters.
            Tray.icon?.toolTip = (if (title.isBlank()) "Glasswave" else "Glasswave · $line").take(127)
        }
    }
}
