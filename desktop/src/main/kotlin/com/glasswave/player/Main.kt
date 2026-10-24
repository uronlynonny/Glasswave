// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.ui.input.pointer.pointerInput
import android.content.AppContext
import android.content.AppDirs
import androidx.activity.compose.BackDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.sun.jna.Library as JnaLibrary
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.File
import okio.Path.Companion.toOkioPath

/** The window's width, for screens that switch to a wider layout (see isWide()). */
object WindowSize {
    var widthDp by mutableFloatStateOf(1200f)
}

/** Glasswave's own volume (0..1), kept between launches. Windows' volume applies on top. */
object AppVolume {
    private val prefs by lazy { AppContext.getSharedPreferences("volume", 0) }
    var level by mutableFloatStateOf(1f)
        private set
    @Volatile private var curve = 1f
    /** Turned down while voice search listens, so the mic hears you, not the song. */
    @Volatile var duck = 1f
    /** What the audio is multiplied by: a curve, so the slider feels even to the ear. */
    val gain: Float get() = curve * duck

    fun init() {
        level = prefs.getFloat("level", 1f).coerceIn(0f, 1f)
        curve = level * level
    }

    /** Silent playback for automated checks (not saved). */
    fun muteForChecks() { curve = 0f }

    fun set(v: Float) {
        level = v.coerceIn(0f, 1f)
        curve = level * level
        prefs.edit().putFloat("level", level).apply()
    }
}

fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        .onFailure { Menus.say("Couldn't open your web browser") }
}

/** The app icon (the wave), drawn in code so the window and taskbar get it at any size. */
private val AppIcon: ImageVector = ImageVector.Builder("Glasswave", 108.dp, 108.dp, 108f, 108f)
    .addPath(PathParser().parsePathString("M18,0h72a18,18 0 0 1 18,18v72a18,18 0 0 1 -18,18h-72a18,18 0 0 1 -18,-18v-72a18,18 0 0 1 18,-18z").toNodes(), fill = SolidColor(Color(0xFF0E0E0E)))
    .addPath(
        PathParser().parsePathString("M30,60C38,38 48,38 54,54S70,70 78,48").toNodes(),
        stroke = SolidColor(Lime), strokeLineWidth = 9f, strokeLineCap = StrokeCap.Round,
    )
    .build()

/**
 * Asks Windows 11 for a see-through, frosted ("acrylic") dark title bar instead of a solid black one,
 * so the desktop behind shows through softly. Older Windows keeps its normal dark title bar.
 */
private interface Dwm : JnaLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: Pointer, size: Int): Int
}

/** [backdrop]: 2 Mica (tinted by the wallpaper), 3 Acrylic (frosted glass), 4 Mica Alt. */
internal fun glassTitleBar(w: java.awt.Window, backdrop: Int = 3) {
    runCatching {
        val dwm = Native.load("dwmapi", Dwm::class.java)
        val hwnd = Native.getWindowPointer(w)
        dwm.DwmSetWindowAttribute(hwnd, 20, IntByReference(1).pointer, 4) // dark mode
        // The backdrop material behind the title bar (Windows 11 22H2 and later).
        if (dwm.DwmSetWindowAttribute(hwnd, 38, IntByReference(backdrop).pointer, 4) != 0) {
            // Not supported: fall back to a dark caption that matches the app.
            dwm.DwmSetWindowAttribute(hwnd, 35, IntByReference(0x00080B0A).pointer, 4)
        }
    }
}

/**
 * Windows 11 slows down apps whose window is minimized or hidden ("power throttling"), which can
 * make music skip. While a song plays, Glasswave asks to keep full speed; when paused it lets
 * Windows save power again.
 */
private interface Kernel : JnaLibrary {
    fun GetCurrentProcess(): Pointer
    fun SetProcessInformation(process: Pointer, infoClass: Int, info: Pointer, size: Int): Boolean
}

object WinPower {
    private val kernel by lazy { runCatching { Native.load("kernel32", Kernel::class.java) }.getOrNull() }
    private var last: Boolean? = null

    fun fullSpeed(on: Boolean) {
        if (last == on) return
        last = on
        runCatching {
            val k = kernel ?: return
            // PROCESS_POWER_THROTTLING_STATE: version 1; we decide execution speed (1) and timer
            // accuracy (4); state 0 means "don't throttle", the same bits mean "throttle as usual".
            val mem = com.sun.jna.Memory(12)
            mem.setInt(0, 1)
            mem.setInt(4, 0x1 or 0x4)
            mem.setInt(8, if (on) 0 else 0x1 or 0x4)
            k.SetProcessInformation(k.GetCurrentProcess(), 4, mem, 12)
        }
    }
}

/** Everything the phone app set up when it started. */
internal fun startUp() {
    val ctx = AppContext
    Settings.init(ctx)
    Accounts.init(ctx)
    Library.init(ctx)
    History.init(ctx)
    Downloads.init(ctx)
    GenreArt.init(ctx)
    LyricsRepo.init(ctx)
    Loudness.init(ctx)
    GlassTier.init(ctx)
    Stats.init(ctx)
    Updater.init(ctx)
    Profile.init(ctx)
    Profile.refresh()
    YtRepo.initMatches(ctx)
    AppVolume.init()
    val lastPlayed = History.tracks.firstOrNull()?.url
    // Load FFmpeg now (it takes a few seconds), so the first song you pick starts quickly.
    Thread { runCatching { org.bytedeco.javacv.FFmpegFrameGrabber.tryLoad(); AudioDeck.warmUp() } }.apply { isDaemon = true }.start()
    Thread {
        YtRepo.init()
        History.repairOnce(ctx)
        // Warm up: the first YouTube lookup is slow; doing it now makes your first click play sooner.
        lastPlayed?.let { YtRepo.prefetch(android.net.Uri.parse(it)) }
    }.apply { isDaemon = true }.start()

    SingletonImageLoader.setSafe { pc ->
        ImageLoader.Builder(pc)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { YtRepo.http })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(40L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(File(AppDirs.cache, "art").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
}

/** 1280 × 840, or less if the screen's free area (without the taskbar) is smaller. */
private fun startSize(): DpSize {
    val area = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    return DpSize(minOf(1280, area.width - 40).dp, minOf(840, area.height - 40).dp)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Already open: that window comes to the front instead of a second copy starting.
    if (!SingleInstance.claim()) return
    startUp()
    application {
        val ps = remember { PlayerState().also { it.connect() } }
        val vm = remember { MainViewModel() }
        val quit = ::exitApplication
        remember { Updater.exitApp = { Discord.shutdown(); ps.release(); quit() } }
        // Open centred, and small enough to sit above the taskbar on any screen.
        val state = rememberWindowState(size = startSize(), position = WindowPosition(androidx.compose.ui.Alignment.Center))
        Window(
            onCloseRequest = {
                // A downloaded update installs quietly as the app closes.
                Discord.shutdown()
                Updater.installOnExit()
                ps.release()
                exitApplication()
            },
            title = "Glasswave",
            icon = rememberVectorPainter(AppIcon),
            state = state,
            onPreviewKeyEvent = { e ->
                // Caught before any button sees them, and left alone while you type in a text box:
                // Space plays/pauses, Ctrl+arrows skip, Esc goes back.
                if (e.type != KeyEventType.KeyDown || Typing.active) return@Window false
                when {
                    e.key == Key.Spacebar -> { ps.toggle(); true }
                    e.isCtrlPressed && e.key == Key.DirectionRight -> { ps.next(); true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft -> { ps.previous(); true }
                    e.key == Key.Escape -> BackDispatcher.back()
                    e.key == Key.MediaPlayPause -> { ps.toggle(); true }
                    e.key == Key.MediaNext -> { ps.next(); true }
                    e.key == Key.MediaPrevious -> { ps.previous(); true }
                    else -> false
                }
            },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(420, 640)
                glassTitleBar(window)
            }
            // Someone opened Glasswave again while it was already running: show this window.
            LaunchedEffect(SingleInstance.showRequests) {
                if (SingleInstance.showRequests == 0) return@LaunchedEffect
                state.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
            val density = LocalDensity.current.density
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { WindowSize.widthDp = it.width / density }
                    // A click anywhere lets go of the search box (a click on a text box takes it right back).
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (ev.type == PointerEventType.Press && Typing.active) focusManager.clearFocus()
                            }
                        }
                    }
                    // The mouse's back button goes back too.
                    .onPointerEvent(PointerEventType.Press) { if (it.button == PointerButton.Back) BackDispatcher.back() },
            ) {
                App(vm, ps, openInBrowser = ::openInBrowser, openEqualizer = { Menus.say("Use Windows' sound settings for an equalizer") })
            }
        }
    }
}
