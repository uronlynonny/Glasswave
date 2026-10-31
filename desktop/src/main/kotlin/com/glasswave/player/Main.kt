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
import androidx.compose.ui.input.key.isMetaPressed
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
internal val AppIcon: ImageVector = ImageVector.Builder("Glasswave", 108.dp, 108.dp, 108f, 108f)
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

/**
 * While the window is minimized, Glasswave hands the memory it isn't using back to Windows (for
 * games and other apps). The music keeps playing; anything needed again comes back by itself.
 */
private interface Psapi : JnaLibrary {
    fun GetCurrentProcess(): Pointer
    fun K32EmptyWorkingSet(process: Pointer): Boolean
}

object WinMemory {
    private val kernel by lazy { runCatching { Native.load("kernel32", Psapi::class.java) }.getOrNull() }

    /**
     * Java keeps the room it grew into after a busy moment (a big search, a playlist import) until a
     * full clean-up, which may not come for a long time. Every half minute, if it holds more than
     * twice what it uses, it is cleaned up and shrinks back (a few hundredths of a second, unheard:
     * the speaker has more than half a second of sound queued).
     */
    fun startGuard() {
        Thread({
            while (true) {
                Thread.sleep(30_000)
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                if (rt.totalMemory() > 48L * 1024 * 1024 && rt.totalMemory() > used * 2) System.gc()
            }
        }, "memory-guard").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun giveBack() {
        runCatching {
            System.gc()
            // Pictures that were let go are freed once the clean-up above has run.
            Thread.sleep(300)
            val k = kernel ?: return
            k.K32EmptyWorkingSet(k.GetCurrentProcess())
        }
    }
}

/**
 * The graphics card side of drawing keeps old pictures and scratch space around "in case", up to
 * 256 MB, and never lets go of them: opening the lyrics once was enough to fill it. This keeps that
 * store small. (The drawing library doesn't offer it directly, so it's found by name, and nothing
 * happens if it isn't there.)
 */
object GpuMemory {
    /** What the graphics store may keep between frames. */
    private const val LIMIT = 32L * 1024 * 1024
    private var done: Any? = null

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            c = c.superclass
        }
        return null
    }

    fun findLayer(c: java.awt.Component): org.jetbrains.skiko.SkiaLayer? {
        if (c is org.jetbrains.skiko.SkiaLayer) return c
        if (c is java.awt.Container) for (k in c.components) findLayer(k)?.let { return it }
        return null
    }

    /** Call now and then: the store is made with the first frame, and again if the card resets. */
    fun limit(window: java.awt.Window) {
        // Not on a Mac: there it draws with Metal on a thread of its own, and trimming the store from
        // here, while that thread is drawing, crashes the app.
        if (Os.isMac) return
        runCatching {
            val layer = findLayer(window) ?: return
            val redrawer = layer.javaClass.methods.firstOrNull { it.name.startsWith("getRedrawer") && it.parameterCount == 0 }
                ?.invoke(layer) ?: return
            val handler = field(redrawer, "contextHandler") ?: return
            val ctx = field(handler, "context") as? org.jetbrains.skia.DirectContext ?: return
            if (done === ctx) return
            ctx.resourceCacheLimit = LIMIT
            android.util.Log.i("Glasswave", "Graphics memory limited to ${LIMIT shr 20} MB")
            done = ctx
        }.onFailure { android.util.Log.w("Glasswave", "Couldn't limit graphics memory", it) }
    }
}

/** Everything the phone app set up when it started. */
/** Starting up: with -Dgw.bootTimes=1, how long each step takes goes to the log. */
private val bootTimes = System.getProperty("gw.bootTimes") == "1"
private val bootStart = System.nanoTime()
internal inline fun boot(name: String, f: () -> Unit) {
    val t = System.nanoTime()
    f()
    if (bootTimes) System.err.println("GW boot $name ${(System.nanoTime() - t) / 1_000_000} ms (at ${(System.nanoTime() - bootStart) / 1_000_000} ms)")
}

internal fun startUp() {
    val ctx = AppContext
    boot("Settings.init") { Settings.init(ctx) }
    boot("Sound.init") { Sound.init(ctx) }
    // Sound waits about 1.2 s at the speaker (see AudioDeck), so the visualizer shows readings from then.
    Spectrum.latencyMs = 1220
    boot("Accounts.init") { Accounts.init(ctx) }
    boot("Library.init") { Library.init(ctx) }
    boot("SearchPicks.init") { SearchPicks.init(ctx) }
    boot("PlaylistMix.init") { PlaylistMix.init(ctx) }
    boot("History.init") { History.init(ctx) }
    boot("Downloads.init") { Downloads.init(ctx) }
    boot("SongCache.init") { SongCache.init(ctx) }
    boot("NotInterested.init") { NotInterested.init(ctx) }
    boot("GenreArt.init") { GenreArt.init(ctx) }
    boot("ArtistArt.init") { ArtistArt.init(ctx) }
    boot("LyricsRepo.init") { LyricsRepo.init(ctx) }
    boot("Loudness.init") { Loudness.init(ctx) }
    boot("AnimatedCovers.init") { AnimatedCovers.init(ctx) }
    boot("MixLibrary.init") { MixLibrary.init(ctx) }
    MixLibrary.decoder = MixAudio
    boot("GlassTier.init") { GlassTier.init(ctx) }
    boot("Stats.init") { Stats.init(ctx) }
    boot("Sync.init") { Sync.init(ctx) }
    boot("Updater.init") { Updater.init(ctx) }
    boot("Profile.init") { Profile.init(ctx) }
    boot("Profile.refresh") { Profile.refresh() }
    // Saved song matches: read in the background (about 0.15 s), so the window opens sooner.
    Thread { YtRepo.initMatches(ctx) }.apply { isDaemon = true; name = "matches"; start() }
    boot("AppVolume.init") { AppVolume.init() }
    boot("WinMemory.startGuard") { WinMemory.startGuard() }
    val lastPlayed = History.tracks.firstOrNull()?.url
    // Load FFmpeg now (it takes a few seconds), so the first song you pick starts quickly.
    Thread { runCatching { org.bytedeco.javacv.FFmpegFrameGrabber.tryLoad(); AudioDeck.warmUp() } }.apply { isDaemon = true }.start()
    Thread {
        YtRepo.init()
        History.repairOnce(ctx)
        // Warm up: the first YouTube lookup is slow; doing it now makes your first click play sooner.
        lastPlayed?.let { YtRepo.prefetch(android.net.Uri.parse(it)) }
        // Give songs imported from Spotify playlists their own pictures (runs once).
        History.fixCoversOnce()
    }.apply { isDaemon = true }.start()

    SingletonImageLoader.setSafe { pc ->
        ImageLoader.Builder(pc)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { YtRepo.http })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(File(AppDirs.cache, "art").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
}

/** 1280 × 840, or less if the screen's free area (without the taskbar) is smaller. */
private fun startSize(): DpSize {
    // For screenshots: -Dgw.size=420x860 starts the window at that size.
    System.getProperty("gw.size")?.split("x")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { return DpSize(it[0].dp, it[1].dp) }
    val area = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    return DpSize(minOf(1280, area.width - 40).dp, minOf(840, area.height - 40).dp)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Draw with OpenGL: on Windows' default (Direct3D 12) the graphics driver takes memory in
    // 64 MB blocks and keeps them, which alone pushed the app well past 300 MB. If OpenGL doesn't
    // work on a PC, the drawing library moves on to another way by itself.
    // (Macs keep the drawing library's own choice, Apple's Metal.)
    if (Os.isWindows && System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "OPENGL")
    if (Os.isMac) {
        // The window's buttons and any system menus in dark mode, to match the app; and the app's
        // name in the menu bar (rather than the launcher's).
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
        System.setProperty("apple.awt.application.name", "Glasswave")
    }
    if (bootTimes) System.err.println("GW boot main() reached ${System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().startTime} ms after the JVM started")
    // Already open: that window comes to the front instead of a second copy starting.
    if (!SingleInstance.claim()) return
    startUp()
    application {
        val ps = remember { PlayerState().also { p -> boot("player") { p.connect(); GlobalKeys.start(p) } } }
        // The keyboard's media keys go to Glasswave while it has a song loaded.
        LaunchedEffect(ps.hasItem) { GlobalKeys.mediaKeys(ps.hasItem) }
        val vm = remember { MainViewModel() }
        remember { System.getProperty("gw.memScript")?.let { MemScript.start(ps, vm, File(it)) } }
        val quit = ::exitApplication
        remember { Updater.exitApp = { Discord.shutdown(); ps.release(); quit() } }
        // Closing for good: tell your phone, let go of the audio, then quit.
        val quitAll = {
            // Tell your phone this computer stopped playing (a second at most).
            Thread { Sync.goodbye() }.apply { isDaemon = true; start() }.join(1500)
            Active.liveBye()
            // A downloaded update installs quietly as the app closes.
            Discord.shutdown()
            Updater.installOnExit()
            ps.release()
            exitApplication()
        }
        // On a Mac, the red button only hides the window and the music keeps playing, like Apple
        // Music: clicking Glasswave in the Dock brings it back, and ⌘Q (or Quit in the Dock) quits.
        var shown by remember { mutableStateOf(true) }
        remember {
            if (Os.isMac) runCatching {
                val d = java.awt.Desktop.getDesktop()
                d.setQuitHandler { _, r -> r.cancelQuit(); javax.swing.SwingUtilities.invokeLater { quitAll() } }
                d.addAppEventListener(java.awt.desktop.AppReopenedListener { shown = true })
            }
        }
        // Open centred, and small enough to sit above the taskbar on any screen.
        val state = rememberWindowState(size = startSize(), position = WindowPosition(androidx.compose.ui.Alignment.Center))
        remember { if (bootTimes) System.err.println("GW boot opening the window at ${(System.nanoTime() - bootStart) / 1_000_000} ms") }
        Window(
            onCloseRequest = { if (Os.isMac) shown = false else quitAll() },
            visible = shown,
            title = "Glasswave",
            icon = rememberVectorPainter(AppIcon),
            state = state,
            onPreviewKeyEvent = { e ->
                // Caught before any button sees them, and left alone while you type in a text box:
                // Space plays/pauses, Ctrl+arrows (⌘+arrows on a Mac) skip, Esc goes back.
                // (Ctrl+Alt+arrows and the media keys also work from any other window: see GlobalKeys.)
                if (e.type != KeyEventType.KeyDown || Typing.active) return@Window false
                val skip = if (Os.isMac) e.isMetaPressed else e.isCtrlPressed
                when {
                    e.key == Key.Spacebar -> { ps.toggle(); true }
                    skip && e.key == Key.DirectionRight -> { ps.next(); true }
                    skip && e.key == Key.DirectionLeft -> { ps.previous(); true }
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
                if (Os.isWindows) glassTitleBar(window)
                TitleBar.install(window)
            }
            LaunchedEffect(Unit) {
                while (true) {
                    GpuMemory.limit(window)
                    kotlinx.coroutines.delay(3_000)
                }
            }
            // Minimized: give memory back now, and again now and then while it stays down.
            // For the checks: counts drawn frames and the longest gap between two (smoothness).
            if (System.getProperty("gw.memScript") != null) LaunchedEffect(Unit) {
                var last = 0L
                while (true) {
                    androidx.compose.runtime.withFrameNanos { now ->
                        if (last > 0) FrameMeter.add((now - last) / 1_000_000)
                        last = now
                    }
                }
            }
            LaunchedEffect(state.isMinimized) {
                if (!state.isMinimized) return@LaunchedEffect
                kotlinx.coroutines.delay(2_000)
                while (true) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WinMemory.giveBack() }
                    kotlinx.coroutines.delay(60_000)
                }
            }
            LaunchedEffect(Unit) { if (bootTimes) System.err.println("GW boot window drawn ${(System.nanoTime() - bootStart) / 1_000_000} ms after startUp began") }
            // Someone opened Glasswave again while it was already running: show this window.
            LaunchedEffect(SingleInstance.showRequests) {
                if (SingleInstance.showRequests == 0) return@LaunchedEffect
                shown = true
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
                App(vm, ps, openInBrowser = ::openInBrowser, openEqualizer = { Menus.say(if (Os.isMac) "An equalizer isn't on the Mac version yet" else "Use Windows' sound settings for an equalizer") })
                // Over everything, see-through: the app's colours show behind it.
                TitleBar.Bar(window, Modifier.align(androidx.compose.ui.Alignment.TopStart))
            }
        }
        // The mini player: its own small window over your other apps.
        MiniPlayerWindow(ps, rememberVectorPainter(AppIcon))
    }
}
