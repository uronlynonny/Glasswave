// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.activity.compose.BackDispatcher
import com.sun.jna.Library as JnaLibrary
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import javax.swing.SwingUtilities

/**
 * For automated checks only: with -Dgw.memScript=<file>, the real app runs the steps in that file
 * and writes down how much memory it uses as it goes. (./gradlew -p desktop memCheck -Pgwscript=...)
 *
 *   wait 10            seconds
 *   play daft punk     searches and plays the first song
 *   next               next song
 *   full / back        opens the full player / goes back
 *   lyrics on|off      the lyrics view in the full player
 *   vis BARS|OFF       visualizer style
 *   wallpaper <path>   wallpaper (none: no wallpaper)
 *   tab LIBRARY        switches tab
 *   search <words>     searches (shows results with pictures)
 *   mem <label>        writes down the memory now
 *   watch 60 5         writes down the memory every 5 s for 60 s
 *   quit
 */
object MemScript {
    private interface Psapi : JnaLibrary {
        fun GetCurrentProcess(): Pointer
        fun K32GetProcessMemoryInfo(process: Pointer, counters: Pointer, size: Int): Boolean
        fun GetProcessHeaps(count: Int, heaps: Pointer?): Int
        fun HeapSummary(heap: Pointer, flags: Int, summary: Pointer): Boolean
        fun VirtualQuery(address: Pointer?, info: Pointer, size: Long): Long
        fun K32GetMappedFileNameW(process: Pointer, address: Pointer, name: CharArray, size: Int): Int
    }

    /** Where the memory outside Java sits: each Windows heap, and committed memory by kind and owner. */
    fun regions(label: String) {
        val n = k.GetProcessHeaps(0, null)
        val hs = Memory(8L * n)
        k.GetProcessHeaps(n, hs)
        val heaps = (0 until n).map { i ->
            val h = hs.getPointer(8L * i)
            val sm = Memory(40).apply { clear(); setInt(0, 40) }
            k.HeapSummary(h, 0, sm)
            Pointer.nativeValue(h) to sm.getLong(16) // cbCommitted
        }.sortedByDescending { it.second }
        System.err.println("GW heaps [$label] " + heaps.take(8).joinToString { "${java.lang.Long.toHexString(it.first)}=${it.second shr 20}MB" })
        // Walk the address space.
        val mbi = Memory(48)
        var addr = 0L
        val byKind = HashMap<String, Long>()
        val byBase = HashMap<Long, Long>()
        val proc = k.GetCurrentProcess()
        while (addr < 0x7FFFFFFF0000L) {
            if (k.VirtualQuery(Pointer(addr), mbi, 48) == 0L) break
            val base = mbi.getLong(8) // AllocationBase
            val size = mbi.getLong(24)
            val state = mbi.getInt(32)
            val protect = mbi.getInt(36)
            val type = mbi.getInt(40)
            if (state == 0x1000) { // committed
                val writable = protect and (0x04 or 0x08 or 0x40 or 0x80) != 0
                val kind = when (type) { 0x20000 -> "private"; 0x40000 -> if (writable) "mapped-rw" else "mapped-ro"; else -> if (writable) "image-rw" else "image-ro" }
                byKind.merge(kind, size, Long::plus)
                if (type == 0x20000) byBase.merge(base, size, Long::plus)
                if (type != 0x20000 && writable) {
                    val name = CharArray(260)
                    val len = k.K32GetMappedFileNameW(proc, Pointer(addr), name, 260)
                    if (len > 0) byKind.merge("rw:" + String(name, 0, len).substringAfterLast('\\'), size, Long::plus)
                }
            }
            addr += size.coerceAtLeast(4096)
        }
        System.err.println("GW kinds [$label] " + byKind.filterKeys { !it.startsWith("rw:") }.entries.joinToString { "${it.key}=${it.value shr 20}MB" })
        System.err.println("GW rw-files [$label] " + byKind.filterKeys { it.startsWith("rw:") }.entries.sortedByDescending { it.value }.take(10).joinToString { "${it.key.drop(3)}=${it.value shr 10}KB" })
        System.err.println("GW private-blocks [$label] " + byBase.entries.sortedByDescending { it.value }.take(14).joinToString { "${java.lang.Long.toHexString(it.key)}=${it.value shr 20}MB" })
    }
    private val k by lazy { Native.load("kernel32", Psapi::class.java) }

    /** Working set and private memory, in MB. */
    fun usage(): Pair<Long, Long> {
        val m = Memory(80)
        m.setInt(0, 80)
        k.K32GetProcessMemoryInfo(k.GetCurrentProcess(), m, 80)
        return m.getLong(16) / (1 shl 20) to m.getLong(72) / (1 shl 20)
    }

    private var lastAt = 0L
    private var lastCpu = 0L

    fun line(label: String): String {
        val (ws, priv) = usage()
        val rt = Runtime.getRuntime()
        val heapUsed = (rt.totalMemory() - rt.freeMemory()) shr 20
        val threads = Thread.activeCount()
        // Processor use since the last reading, as a share of one core.
        val now = System.nanoTime()
        val cpu = ProcessHandle.current().info().totalCpuDuration().map { it.toNanos() }.orElse(0L)
        val share = if (lastAt == 0L) 0 else ((cpu - lastCpu) * 100 / (now - lastAt).coerceAtLeast(1)).toInt()
        lastAt = now; lastCpu = cpu
        return "GW mem [$label] working=$ws MB private=$priv MB heap=$heapUsed/${rt.totalMemory() shr 20} MB threads=$threads cpu=$share%"
    }

    fun start(ps: PlayerState, vm: MainViewModel, file: File) {
        AppVolume.muteForChecks()
        Thread({
            fun onUi(f: () -> Unit) = SwingUtilities.invokeAndWait(f)
            for (raw in file.readLines()) {
                val l = raw.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                val parts = l.split(Regex("\\s+"), limit = 2)
                val arg = parts.getOrElse(1) { "" }
                System.err.println("GW step: $l")
                runCatching {
                    when (parts[0]) {
                        "wait" -> Thread.sleep((arg.toDouble() * 1000).toLong())
                        "play" -> {
                            val hits = kotlinx.coroutines.runBlocking { YtRepo.search(arg) }
                            onUi { ps.playList(hits.take(10), 0) }
                        }
                        "next" -> onUi { ps.next() }
                        // animcover: looks up the playing song's animated cover (Apple Music) and prints its address.
                        // "animcover off" / "animcover on" turn animated covers off and on; "animcover cpu"
                        // prints how much of one core its decoding thread used over 5 s.
                        "animcover" -> if (arg == "cpu") {
                            val mx = java.lang.management.ManagementFactory.getThreadMXBean()
                            fun used() = Thread.getAllStackTraces().keys.filter { it.name == "animated-cover" }.sumOf { mx.getThreadCpuTime(it.id).coerceAtLeast(0) }
                            val a = used()
                            Thread.sleep(5000)
                            System.err.println("GW animcover cpu=${(used() - a) / 50_000_000}% of one core")
                        } else if (arg.isNotEmpty()) onUi { AnimatedCovers.enabled = arg == "on" } else {
                            var t: Track? = null
                            onUi { t = ps.currentTrack() }
                            val song = t
                            System.err.println("GW animcover ${song?.title} / ${song?.artist}: " + (song?.let { kotlinx.coroutines.runBlocking { AnimatedCovers.videoFor(it) } }))
                        }
                        "toggle" -> onUi { ps.toggle() }
                        // mini on|off: the mini player window. miniinfo: how that window is set up. minisize w h: resizes it.
                        "mini" -> onUi { Mini.open = arg == "on" }
                        "miniinfo" -> onUi {
                            val m = java.awt.Window.getWindows().firstOrNull { (it as? java.awt.Frame)?.title == "Glasswave mini player" && it.isDisplayable } as? java.awt.Frame
                            System.err.println(if (m == null) "GW miniinfo: no mini window" else "GW miniinfo: at ${m.x},${m.y} size ${m.width}x${m.height} onTop=${m.isAlwaysOnTop} undecorated=${m.isUndecorated} seeThrough=${(m as? androidx.compose.ui.awt.ComposeWindow)?.isTransparent} bg=${m.background} opaque=${m.isOpaque} saved=${Settings.miniBounds}")
                        }
                        // minishot file: puts the mini player over this test copy's own window and saves a picture of just that spot.
                        "minishot" -> {
                            val main = java.awt.Window.getWindows().first { (it as? java.awt.Frame)?.title == "Glasswave" && it.isDisplayable }
                            val m = java.awt.Window.getWindows().first { (it as? java.awt.Frame)?.title == "Glasswave mini player" && it.isDisplayable }
                            onUi { (main as java.awt.Frame).isAlwaysOnTop = true; main.toFront(); m.setLocation(main.x + main.width / 2 - m.width / 2, main.y + main.height / 3); m.toFront() }
                            Thread.sleep(1200)
                            val img = java.awt.Robot().createScreenCapture(m.bounds)
                            javax.imageio.ImageIO.write(img, "png", File(arg))
                            onUi { (main as java.awt.Frame).isAlwaysOnTop = false }
                        }
                        "minisize" -> onUi {
                            val (w, h) = arg.split(" ").map { it.toInt() }
                            java.awt.Window.getWindows().firstOrNull { (it as? java.awt.Frame)?.title == "Glasswave mini player" && it.isDisplayable }?.setSize(w, h)
                        }
                        // fillpl N: adds the N most recent songs to the open playlist.
                        "fillpl" -> onUi { (vm.stack.lastOrNull() as? Route.Saved)?.info?.let { p -> History.tracks.take(arg.toInt()).forEach { Library.add(p.id, it) } } }
                        // fps N: how smoothly the window draws over the next N seconds.
                        "fps" -> { FrameMeter.reset(); Thread.sleep(arg.toLong() * 1000); System.err.println("GW fps: " + FrameMeter.summary()) }
                        // mixpl: turns Mix on for the open playlist (8 s fades, starting 20 s early) and opens its Mix page; playpl plays it.
                        "mixpl" -> onUi {
                            val p = (vm.stack.lastOrNull() as? Route.Saved)?.info
                            if (p != null) {
                                val urls = kotlinx.coroutines.runBlocking { Library.tracks(p.id) }.map { it.url }.toSet()
                                PlaylistMix.save(p.id, PlaylistMix.Mix(true, PlaylistMix.Transition(PlaylistMix.Style.FADE, 8, 20), emptyMap(), urls))
                                vm.open(Route.Mix(p))
                            }
                        }
                        "playpl" -> onUi { val p = (vm.stack.lastOrNull() as? Route.Mix)?.info; if (p != null) ps.playList(kotlinx.coroutines.runBlocking { Library.tracks(p.id) }, 0) }
                        "libdl" -> onUi { vm.selectTab(Tab.LIBRARY); vm.libDownloaded = arg == "on" }
                        // scrolltest: scrolls down and up for a few seconds, then says how smooth the frames were.
                        "scrolltest" -> {
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            val r = java.awt.Robot()
                            r.mouseMove(w.x + w.width / 2, w.y + w.height / 2 + 100)
                            FrameMeter.reset()
                            repeat(30) { r.mouseWheel(2); Thread.sleep(60) }
                            repeat(30) { r.mouseWheel(-2); Thread.sleep(60) }
                            System.err.println("GW scrolltest $arg: " + FrameMeter.summary())
                        }
                        // uncensorcur: which video the playing song's audio really comes from.
                        "uncensorcur" -> ps.currentTrack()?.let { System.err.println("GW uncensor '${it.title}' ${it.url} -> " + YtRepo.uncensored(it.url)) }
                        // pick query: as if the playing song was played from a search for [query]; typing: types into search without pressing enter.
                        "pick" -> ps.currentTrack()?.let { SearchPicks.record(arg, it) }
                        "typing" -> onUi { vm.selectTab(Tab.SEARCH); vm.onQuery(arg) }
                        // newpl name: a new playlist of yours with the playing song in it, opened.
                        "newpl" -> onUi { val p = Library.createPlaylist(arg, ps.currentTrack()); vm.open(Route.Saved(p)) }
                        // postrace: how smoothly the song position moves (sampled every 10 ms for 4 s).
                        "postrace" -> {
                            val t0 = System.nanoTime(); var last = -1L; var jumps = 0; var maxStep = 0L; var stalls = 0
                            val p0 = ps.debugPosition(); val f0 = ps.debugFrames()
                            repeat(400) {
                                val p = ps.debugPosition()
                                if (last >= 0) { val step = p - last; if (step > maxStep) maxStep = step; if (step == 0L) stalls++; if (step > 30) jumps++ }
                                last = p; Thread.sleep(10)
                            }
                            val wall = (System.nanoTime() - t0) / 1_000_000
                            val f1 = ps.debugFrames()
                            System.err.println("GW postrace: wall ${wall}ms, position moved ${last - p0}ms, biggest step ${maxStep}ms, ${stalls} samples stuck, ${jumps} jumps; device frames moved ${(f1.first - f0.first) / 48}ms, device behind written by ${(f1.second - f1.first) / 48}ms")
                        }
                        // plmenu: opens the first playlist's menu (as a right-click in the side menu does).
                        // hotkey: presses Ctrl+Alt+→ like a person would (the shortcut that works from any window).
                        "hotkey" -> java.awt.Robot().run {
                            keyPress(java.awt.event.KeyEvent.VK_CONTROL); keyPress(java.awt.event.KeyEvent.VK_ALT); keyPress(java.awt.event.KeyEvent.VK_RIGHT)
                            delay(60)
                            keyRelease(java.awt.event.KeyEvent.VK_RIGHT); keyRelease(java.awt.event.KeyEvent.VK_ALT); keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
                        }
                        // startpl N: plays the Library's Nth playlist with its Play button (so its card shows it playing).
                        "startpl" -> onUi {
                            (arg.toIntOrNull()?.let { Library.playlists.value.getOrNull(it) } ?: Library.playlists.value.firstOrNull { it.name == arg })?.let { p ->
                                System.err.println("GW startpl ${p.name}")
                                ps.playList(kotlinx.coroutines.runBlocking { Library.tracks(p.id) }, 0, from = p.id)
                            }
                        }
                        // resize WxH: sets the window's size (for screenshots).
                        "resize" -> onUi {
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            val (a, b) = arg.split("x").map { it.toInt() }
                            w.setLocation(0, 0)
                            w.setSize(a, b)
                        }
                        "plmenu" -> onUi { Library.playlists.value.firstOrNull()?.let { Menus.openPlaylist(it) } }
                        // state: what's loaded right now (song, spot, playing, what's next).
                        "state" -> onUi { System.err.println("GW state '${ps.title}' at ${ps.livePosition() / 1000}s playing=${ps.playing} next: " + ps.upcoming(3).joinToString(" | ") { it.track.title }) }
                        // upnext: what's coming up after the song playing (Autoplay's picks).
                        "upnext" -> onUi { System.err.println("GW upnext after '${ps.title}' by ${ps.artist}: " + ps.upcoming(8).joinToString(" | ") { "${it.track.title} - ${it.track.artist}" }) }
                        // tap: plays one song from a search on its own, like tapping it on Home (Autoplay follows).
                        "tap" -> {
                            val hits = kotlinx.coroutines.runBlocking { YtRepo.search(arg) }
                            onUi { ps.playList(hits.take(1), 0) }
                        }
                        // size w h: resizes the window (in pixels), e.g. to phone size to see the phone layout.
                        "size" -> onUi {
                            val (w, h) = arg.split(" ").map { it.toInt() }
                            java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }.apply { minimumSize = java.awt.Dimension(300, 400); setSize(w, h) }
                        }
                        // stats: opens your listening stats.
                        "stats" -> onUi { vm.open(Route.Stats) }
                        // quality LOW|NORMAL|HIGH: changes the quality setting. artistnamed Name: opens an artist like the stats page does.
                        "quality" -> onUi { Settings.updateQuality(Quality.valueOf(arg)) }
                        "artistnamed" -> onUi { Menus.artistNamed = arg }
                        // fakeplays N: records the song playing as played N times in a row (to see Recently played group them).
                        "fakeplays" -> { var t: Track? = null; onUi { t = ps.currentTrack() }; t?.let { tr -> repeat(arg.toInt()) { Stats.record(tr, 60_000); Thread.sleep(30) } } }
                        // pcfolder path: adds a music folder on this PC. pcplay title: plays one of its songs.
                        "pcfolder" -> {
                            onUi { PcMusic.addFolder(File(arg)); PcMusic.refresh() }
                            // Wait (up to 30 s) for the songs to be found.
                            var n = 0
                            while (PcMusic.songs.isEmpty() && n++ < 60) Thread.sleep(500)
                            System.err.println("GW pcfolder: ${PcMusic.songs.size} songs")
                        }
                        "pcplay" -> onUi {
                            val all = PcMusic.songs.map { it.track }
                            val i = all.indexOfFirst { it.title.equals(arg, true) }
                            if (i >= 0) ps.playList(all, i) else System.err.println("GW pcplay: no song called $arg (${all.size} songs)")
                        }
                        // wheel n: scrolls the mouse wheel n notches over the middle of the window.
                        "wheel" -> {
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            val r = java.awt.Robot()
                            onUi { (w as? java.awt.Frame)?.isAlwaysOnTop = true; w.toFront() }
                            Thread.sleep(300)
                            r.mouseMove(w.x + w.width / 2, w.y + w.height / 2)
                            repeat(arg.toInt()) { r.mouseWheel(1); Thread.sleep(25) }
                            onUi { (w as? java.awt.Frame)?.isAlwaysOnTop = false }
                        }
                        // seedlyrics title|file: gives a song on this PC lyrics from a saved-lyrics file (for recordings).
                        "seedlyrics" -> {
                            val (title, path) = arg.split("|")
                            val t = PcMusic.songs.map { it.track }.firstOrNull { it.title.equals(title, true) }
                            if (t == null) System.err.println("GW seedlyrics: no song called $title") else {
                                val sha = java.security.MessageDigest.getInstance("SHA-1").digest(t.url.toByteArray()).joinToString("") { "%02x".format(it) }
                                File(File(android.content.AppDirs.data, "lyrics").apply { mkdirs() }, "$sha.json").writeText(File(path).readText())
                            }
                        }
                        // rec seconds file: records the window (as it shows on screen, mouse included unless "nomouse") with ffmpeg.
                        "rec" -> {
                            val parts = arg.split(" ")
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            onUi { w.isAlwaysOnTop = true; w.toFront() }
                            Thread.sleep(400)
                            val at = w.locationOnScreen
                            val ff = System.getProperty("gw.ffmpeg") ?: "ffmpeg"
                            ProcessBuilder(
                                ff, "-y", "-loglevel", "error", "-f", "gdigrab", "-framerate", "30",
                                "-offset_x", "${at.x}", "-offset_y", "${at.y}", "-video_size", "${w.width / 2 * 2}x${w.height / 2 * 2}",
                                "-draw_mouse", if ("nomouse" in parts) "0" else "1", "-t", parts[0], "-i", "desktop",
                                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16", parts[1],
                            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
                        }
                        // glide x y ms: moves the mouse smoothly to a spot in the window. click: clicks there.
                        "glide" -> {
                            val (x, y, ms) = arg.split(" ").map { it.toInt() }
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            val at = w.locationOnScreen
                            val r = java.awt.Robot()
                            val from = java.awt.MouseInfo.getPointerInfo().location
                            val steps = maxOf(1, ms / 16)
                            for (k in 1..steps) {
                                val f = k / steps.toDouble()
                                val e = if (f < .5) 4 * f * f * f else 1 - Math.pow(-2 * f + 2, 3.0) / 2
                                r.mouseMove((from.x + (at.x + x - from.x) * e).toInt(), (from.y + (at.y + y - from.y) * e).toInt())
                                Thread.sleep(16)
                            }
                        }
                        // drag x1 y1 x2 y2: holds the mouse at one spot in the window and drags it to another.
                        "drag" -> {
                            val (x1, y1, x2, y2) = arg.split(" ").map { it.toInt() }
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            onUi { (w as? java.awt.Frame)?.isAlwaysOnTop = true; w.toFront() }
                            Thread.sleep(300)
                            val at = w.locationOnScreen
                            val r = java.awt.Robot()
                            r.mouseMove(at.x + x1, at.y + y1); Thread.sleep(120)
                            r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(120)
                            for (k in 1..30) { r.mouseMove(at.x + x1 + (x2 - x1) * k / 30, at.y + y1 + (y2 - y1) * k / 30); Thread.sleep(25) }
                            Thread.sleep(250)
                            r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                            onUi { (w as? java.awt.Frame)?.isAlwaysOnTop = false }
                        }
                        // openpl N: opens the Library's Nth playlist. plorder: prints the open playlist's songs in order.
                        "openpl" -> onUi { Library.playlists.value.firstOrNull { it.name == arg }?.let { vm.open(Route.Saved(it)) } }
                        "plorder" -> {
                            val p = (vm.stack.lastOrNull() as? Route.Saved)?.info
                            if (p != null) System.err.println("GW order: " + kotlinx.coroutines.runBlocking { Library.tracks(p.id) }.joinToString(" | ") { it.title })
                        }
                        "click" -> {
                            val r = java.awt.Robot()
                            r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(70); r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                        }
                        // mouse x y: puts the mouse at a spot in the window straight away.
                        "mouse" -> {
                            val (x, y) = arg.split(" ").map { it.toInt() }
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            val at = w.locationOnScreen
                            java.awt.Robot().mouseMove(at.x + x, at.y + y)
                        }
                        // flow: starts Flow, like tapping its bar on Home. seek ms: jumps to a time in the song.
                        "flow" -> onUi { Flow.start(ps, History.tracks.take(20)) }
                        // mixlog: prints how each blend is planned; seekend N: jumps to N seconds before the end.
                        "mixlog" -> onUi { ps.mixLog = { System.err.println("GW mix: $it") } }
                        "seekend" -> onUi { ps.seekTo((ps.durationMs - arg.toLong() * 1000).coerceAtLeast(0)) }
                        "seek" -> onUi { ps.seekTo(arg.toLong()) }
                        "full" -> onUi { PlayerView.openFull = true }
                        // artisttap: like tapping the artist's name in the full player.
                        "artisttap" -> onUi { ps.currentTrack()?.let { Menus.artistOf = it } }
                        // spimport link: reads a Spotify link without saving it, and says how many different song pictures it got.
                        // covers: what the one-time picture repair would fix, then runs it now and counts again.
                        "covers" -> {
                            fun count() = Library.ownPlaylists().joinToString { p -> val t = kotlinx.coroutines.runBlocking { Library.tracks(p.id) }; "${p.name.take(20)}: ${t.size} songs/${t.mapNotNull { it.thumb }.distinct().size} pictures" }
                            System.err.println("GW covers dir=${android.content.AppDirs.data} lib=${java.io.File(android.content.AppDirs.data, "library").list()?.size} before: ${count()} | needs fixing: ${Library.songsWithPlaylistCover().mapValues { it.value.size }}")
                            History.fixCoversNow()
                            System.err.println("GW covers after: ${count()}")
                        }
                        "spimport" -> {
                            val got = kotlinx.coroutines.runBlocking { SpotifyApi.importLink(arg) }
                            System.err.println("GW spimport ${got.name}: ${got.tracks.size} songs, ${got.tracks.mapNotNull { it.thumb }.distinct().size} pictures, ${got.tracks.count { it.thumb == got.cover }} with the playlist cover")
                        }
                        "back" -> onUi { BackDispatcher.back() }
                        "lyrics" -> onUi { PlayerView.lyrics = arg == "on" }
                        "vis" -> onUi { Settings.updateVisualizer(VisualizerStyle.valueOf(arg)) }
                        "wallpaper" -> onUi { Settings.updateWallpaper(arg.takeIf { it != "none" }) }
                        "tab" -> onUi { vm.selectTab(Tab.valueOf(arg)) }
                        "settings" -> onUi { vm.openSettings(arg) }
                        "accent" -> onUi { Settings.updateAccentColor(if (arg == "song") null else arg.toLong(16).toInt()) }
                        "search" -> onUi { vm.selectTab(Tab.SEARCH); vm.onQuery(arg); vm.submitSearch() }
                        // rank query: what the All search shows, in order, with play counts.
                        "rank" -> {
                            val page = kotlinx.coroutines.runBlocking { YtMusicApi.searchAll(arg, History.tracks.map { it.url }.toSet()) }
                            for (s in page.sections) {
                                System.err.println("GW rank [$arg] == ${s.title}")
                                for (i in s.items.take(14)) System.err.println("GW rank [$arg]    " + when (i) {
                                    is SongItem -> "${i.title} - ${i.track.artist} (${i.plays})" + if (History.tracks.any { h -> h.url == i.track.url }) " [played]" else ""
                                    else -> "${i::class.simpleName}: ${i.title}"
                                })
                            }
                        }
                        "offline" -> onUi { vm.homeOfflineForCheck() }
                        // lyricsfor query: the first song a search finds, and where its lyrics come from.
                        "lyricsfor" -> {
                            val s = kotlinx.coroutines.runBlocking { YtMusicApi.search(arg, YtMusicApi.Filter.SONGS) }.sections.flatMap { it.items }.filterIsInstance<SongItem>().firstOrNull()
                            val t = s?.track
                            val ly = t?.let { kotlinx.coroutines.runBlocking { LyricsRepo.get(it.url, it.title, it.artist, it.seconds) } }
                            System.err.println("GW lyricsfor [$arg] ${t?.title} - ${t?.artist} (${t?.seconds}s): source=${ly?.source} synced=${ly?.synced} lines=${ly?.lines?.size} first: " + ly?.lines?.filter { it.text.isNotBlank() }?.take(3)?.joinToString(" | ") { "${it.ms}: ${it.text}" })
                        }
                        // explicitcheck query: every song a search finds, whether it's explicit, and what plays.
                        "explicitcheck" -> {
                            val seen = HashSet<String>()
                            for (f in listOf(YtMusicApi.Filter.SONGS, null)) {
                                val page = kotlinx.coroutines.runBlocking { if (f == null) YtMusicApi.searchAll(arg, emptySet()) else YtMusicApi.search(arg, f) }
                                for (i in page.sections.flatMap { it.items }.filterIsInstance<SongItem>()) {
                                    if (!seen.add(i.track.url)) continue
                                    val plays = if (i.explicit) "same" else YtRepo.uncensored(i.track.url).let { if (it == i.track.url) "CLEAN STAYS" else "-> $it" }
                                    System.err.println("GW explicit [${if (i.explicit) "E" else " "}] ${i.title} - ${i.track.artist} ${i.track.seconds}s ${i.track.url.substringAfter("v=")} $plays")
                                }
                            }
                        }
                        // uncensor id: which version plays for that song (the explicit one when it's clean).
                        "uncensor" -> System.err.println("GW uncensor $arg -> " + YtRepo.uncensored(ytWatchUrl(arg)))
                        // artistpage name: what an artist's page holds (and opens it).
                        // albumpage query: opens the first album found; savealbum saves the open one to the Library.
                        "albumpage" -> {
                            val a = kotlinx.coroutines.runBlocking { YtMusicApi.search(arg, YtMusicApi.Filter.ALBUMS) }.sections.flatMap { it.items }.filterIsInstance<AlbumItem>().first()
                            onUi { vm.open(Route.Online(a)) }
                        }
                        "savealbum" -> {
                            val a = (vm.stack.lastOrNull() as? Route.Online)?.item as? AlbumItem
                            val p = a?.let { kotlinx.coroutines.runBlocking { YtMusicApi.page(it) } }
                            if (a != null && p != null) onUi { Library.saveCollection(a.browseId, p.title, p.thumb, p.tracks) }
                            System.err.println("GW saved: " + Library.playlists.value.filter { it.id.startsWith(Library.SAVED_PREFIX) }.joinToString { "${it.name} (${it.count})" })
                        }
                        "artistpage" -> { val t0 = System.currentTimeMillis()
                            val a = kotlinx.coroutines.runBlocking { YtMusicApi.search(arg, YtMusicApi.Filter.ARTISTS) }.sections.flatMap { it.items }.filterIsInstance<ArtistItem>().first()
                            val p = kotlinx.coroutines.runBlocking { YtMusicApi.page(a) }
                            System.err.println("GW artistpage ${a.title}: ${p.tracks.size} songs to play, ${p.shown} listed, ${System.currentTimeMillis() - t0} ms; rows: " + p.sections.joinToString(" | ") { "${it.title} (${it.items.size})" })
                            onUi { vm.open(Route.Online(a)) }
                        }
                        // lyrtime: how long the playing song's lyrics take to come (0 when fetched ahead).
                        "lyrtime" -> {
                            val t = ps.currentTrack()
                            if (t != null) {
                                val was = LyricsRepo.ready(t.url)
                                val t0 = System.currentTimeMillis()
                                val ly = kotlinx.coroutines.runBlocking { LyricsRepo.get(t.url, t.title, t.artist, t.seconds) }
                                System.err.println("GW lyrtime ${t.title}: ${System.currentTimeMillis() - t0} ms, ready before: $was, ${ly?.lines?.size ?: 0} lines")
                                System.err.println("GW lyrics for ${t.title} - ${t.artist} (${t.seconds}s): source=${ly?.source} synced=${ly?.synced} words=${ly?.wordTimed} dur=${ly?.durationSec} first: " + ly?.lines?.take(6)?.joinToString(" | ") { "${it.ms}: ${it.text}" })
                            }
                        }
                        // streamtest id: can that song's audio be found (18+ songs play another upload)?
                        "streamtest" -> System.err.println("GW streamtest $arg -> " + runCatching { YtRepo.streamUrl(ytWatchUrl(arg)).take(60) }.getOrElse { "FAILED: $it" })
                        "share" -> onUi { ShareCard.track = if (arg == "off") null else ps.currentTrack() }
                        "mem" -> System.err.println(line(arg))
                        "regions" -> regions(arg)
                        // wshot name: the whole window as Windows shows it, frame and all.
                        "wshot" -> {
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            val u = com.sun.jna.platform.win32.User32.INSTANCE
                            val g = com.sun.jna.platform.win32.GDI32.INSTANCE
                            val hwnd = com.sun.jna.platform.win32.WinDef.HWND(Native.getWindowPointer(w))
                            val r = com.sun.jna.platform.win32.WinDef.RECT()
                            u.GetWindowRect(hwnd, r)
                            val ww = r.right - r.left
                            val hh = r.bottom - r.top
                            val dc = u.GetDC(hwnd)
                            val mem = g.CreateCompatibleDC(dc)
                            val bmp = g.CreateCompatibleBitmap(dc, ww, hh)
                            val prev = g.SelectObject(mem, bmp)
                            u.PrintWindow(hwnd, mem, 2)
                            val bi = com.sun.jna.platform.win32.WinGDI.BITMAPINFO()
                            bi.bmiHeader.biWidth = ww
                            bi.bmiHeader.biHeight = -hh
                            bi.bmiHeader.biPlanes = 1
                            bi.bmiHeader.biBitCount = 32
                            bi.bmiHeader.biCompression = 0
                            val buf = Memory(ww.toLong() * hh * 4)
                            g.GetDIBits(dc, bmp, 0, hh, buf, bi, 0)
                            g.SelectObject(mem, prev); g.DeleteObject(bmp); g.DeleteDC(mem); u.ReleaseDC(hwnd, dc)
                            val img = java.awt.image.BufferedImage(ww, hh, java.awt.image.BufferedImage.TYPE_INT_RGB)
                            img.setRGB(0, 0, ww, hh, buf.getIntArray(0, ww * hh), 0, ww)
                            javax.imageio.ImageIO.write(img, "png", File(file.parentFile, "$arg.png"))
                        }
                        // hittest x y: what Windows thinks is at that spot of the window (window pixels, from its top-left).
                        "hittest" -> {
                            val (x, y) = arg.split(" ").map { it.toInt() }
                            val w = java.awt.Window.getWindows().first { it is java.awt.Frame && it.isDisplayable }
                            val u = com.sun.jna.platform.win32.User32.INSTANCE
                            val hwnd = com.sun.jna.platform.win32.WinDef.HWND(Native.getWindowPointer(w))
                            val r = com.sun.jna.platform.win32.WinDef.RECT()
                            u.GetWindowRect(hwnd, r)
                            val sx = r.left + (if (x < 0) r.right - r.left + x else x)
                            val sy = r.top + y
                            val lp = ((sy and 0xFFFF).toLong() shl 16) or (sx and 0xFFFF).toLong()
                            val res = u.SendMessage(hwnd, 0x0084, com.sun.jna.platform.win32.WinDef.WPARAM(0), com.sun.jna.platform.win32.WinDef.LPARAM(lp))
                            val names = mapOf(1L to "client", 2L to "caption(drag)", 12L to "top-edge", 13L to "top-left", 14L to "top-right", 10L to "left-edge", 11L to "right-edge")
                            System.err.println("GW hittest $arg -> ${names[res.toLong()] ?: res.toLong()}")
                        }
                        "maximize" -> onUi {
                            (java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame && it.isDisplayable } as? java.awt.Frame)?.extendedState = java.awt.Frame.MAXIMIZED_BOTH
                        }
                        "minimize", "restore" -> onUi {
                            (java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame && it.isDisplayable } as? java.awt.Frame)
                                ?.extendedState = if (parts[0] == "minimize") java.awt.Frame.ICONIFIED else java.awt.Frame.NORMAL
                        }
                        // shot name: a picture of the window, saved next to the script.
                        "shot" -> {
                            var bmp: org.jetbrains.skia.Bitmap? = null
                            onUi {
                                val w = java.awt.Window.getWindows().firstOrNull { it.isShowing }
                                bmp = w?.let { GpuMemory.findLayer(it) }?.screenshot()
                            }
                            val png = org.jetbrains.skia.Image.makeFromBitmap(bmp!!).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
                            File(file.parentFile, "$arg.png").writeBytes(png)
                        }
                        // Java's own breakdown (needs -XX:NativeMemoryTracking=summary).
                        "nmt" -> {
                            val out = java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
                                javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                                "vmNativeMemory", arrayOf<Any>(arrayOf("summary")), arrayOf(Array<String>::class.java.name),
                            ) as String
                            out.lines().filter { it.contains("committed=") && it.trim().startsWith("-") || it.startsWith("Total") }
                                .forEach { System.err.println("GW nmt " + it.trim()) }
                        }
                        "watch" -> {
                            val (total, every) = arg.split(" ").map { it.toDouble() }
                            var t = 0.0
                            while (t < total) {
                                Thread.sleep((every * 1000).toLong()); t += every
                                System.err.println(line("${t.toInt()}s '${ps.title}' playing=${ps.playing} gaps=${AudioDeck.underruns}"))
                            }
                        }
                        "quit" -> { onUi { ps.release() }; System.exit(0) }
                    }
                }.onFailure { System.err.println("GW step failed: $l ($it)") }
            }
        }, "mem-script").apply { isDaemon = true; start() }
    }
}
