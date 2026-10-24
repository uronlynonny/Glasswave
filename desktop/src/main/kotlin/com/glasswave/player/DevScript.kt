// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * For automated checks only: draws the whole app off-screen (no window, nothing on your screen,
 * no real mouse or keyboard) and runs the steps in a script file against it, saving pictures.
 * Run with  ./gradlew -p desktop screenCheck -Pscript=path/to/script.txt
 *
 *   wait 3            seconds
 *   shot home         saves home.png next to the script
 *   click 200 300     clicks at a point
 *   scroll 400 500 3  wheel ticks at a point (negative scrolls up)
 *   search daft punk  opens Search and searches
 *   tab LIBRARY       switches tab
 *   key ESCAPE        Esc (goes back)
 *   size 1280 800     resizes the drawing
 *   log               prints the player's state
 *   quit
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val file = File(args.firstOrNull() ?: error("Give the script file"))
    startUp()
    AppVolume.muteForChecks()
    var w = 1280
    var h = 810
    lateinit var scene: ImageComposeScene
    lateinit var ps: PlayerState
    lateinit var vm: MainViewModel
    fun make() {
        scene = ImageComposeScene(w, h, Density(1f)) {
            WindowSize.widthDp = w.toFloat()
            App(vm, ps, openInBrowser = {}, openEqualizer = {})
        }
    }
    SwingUtilities.invokeAndWait {
        ps = PlayerState().also { it.connect() }
        vm = MainViewModel()
        make()
    }
    // Keep drawing frames, like a screen would, so animations and loading move along.
    val frames = Timer(16) { scene.render(System.nanoTime()) }
    SwingUtilities.invokeAndWait { frames.start() }

    fun onUi(f: () -> Unit) = SwingUtilities.invokeAndWait(f)
    for (raw in file.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split(Regex("\\s+"), limit = 2)
        val arg = parts.getOrElse(1) { "" }
        System.err.println("GW step: $line")
        try {
            when (parts[0]) {
                "wait" -> Thread.sleep((arg.toDouble() * 1000).toLong())
                "shot" -> onUi {
                    val img = scene.render(System.nanoTime())
                    val png = img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
                    File(file.parentFile, "$arg.png").writeBytes(png)
                }
                "click" -> {
                    val (x, y) = arg.split(" ").map { it.toFloat() }
                    onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)) }
                    onUi { scene.sendPointerEvent(PointerEventType.Press, Offset(x, y)) }
                    Thread.sleep(60)
                    onUi { scene.sendPointerEvent(PointerEventType.Release, Offset(x, y)) }
                }
                "hover" -> {
                    val (x, y) = arg.split(" ").map { it.toFloat() }
                    onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)) }
                }
                "scroll" -> {
                    val (x, y, n) = arg.split(" ").map { it.toFloat() }
                    onUi {
                        scene.sendPointerEvent(
                            PointerEventType.Scroll, Offset(x, y),
                            scrollDelta = Offset(0f, n),
                        )
                    }
                }
                "search" -> onUi { vm.selectTab(Tab.SEARCH); vm.onQuery(arg); vm.submitSearch() }
                "tab" -> onUi { vm.selectTab(Tab.valueOf(arg)) }
                // dl: downloads the song that's playing. lyrics: shows what lyrics the app finds for it.
                "dl" -> onUi { ps.currentTrack()?.let { Downloads.download(it) } }
                "lyrics" -> {
                    val t = Downloads.tracks.firstOrNull() ?: ps.currentTrack()
                    val ly = t?.let { kotlinx.coroutines.runBlocking { LyricsRepo.get(it.url, it.title, it.artist, it.seconds) } }
                    System.err.println("GW lyrics for '${t?.title}' (downloaded=${t?.let { Downloads.isDownloaded(it.url) }}): " +
                        (ly?.let { "${it.lines.size} lines, synced=${it.synced}, from ${it.source}; first: ${it.lines.firstOrNull { l -> l.text.isNotBlank() }?.text}" } ?: "none"))
                }
                "style" -> onUi { Settings.updatePlayerStyle(PlayerStyle.valueOf(arg)) }
                "key" -> onUi { if (arg == "ESCAPE") androidx.activity.compose.BackDispatcher.back() }
                "size" -> {
                    val (a, b) = arg.split(" ").map { it.toInt() }
                    w = a; h = b
                    onUi { scene.close(); make() }
                }
                "log" -> onUi {
                    println("GW: '${ps.title}' by '${ps.artist}' playing=${ps.playing} buffering=${ps.buffering} pos=${ps.livePosition()} dur=${ps.durationMs} error=${ps.error} upcoming=${ps.upcoming(3).map { it.track.title }} menu=${Menus.song?.track?.title} queue=${PlayerView.queue} lyrics=${PlayerView.lyrics}")
                }
                // idle 120: how many of the next frames had something to redraw with nobody touching anything.
                "idle" -> {
                    var busy = 0
                    val n = arg.toInt()
                    // Which bits of state keep changing while nobody touches anything.
                    val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()
                    val reg = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { changed, _ ->
                        for (c in changed) {
                            val k = c.toString().replace(Regex("@[0-9a-f]+"), "").replace(Regex("value=[-0-9.E]+"), "value=#").take(140)
                            seen.merge(k, 1, Int::plus)
                        }
                    }
                    val stacks = java.util.concurrent.ConcurrentHashMap<String, Int>()
                    val wreg = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver { _ ->
                        val st = Thread.currentThread().stackTrace
                            .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                            .filter { !it.startsWith("Thread.") && !it.contains("Snapshot") && !it.contains("DevScriptKt") }
                            .take(9).joinToString(" < ")
                        stacks.merge(st, 1, Int::plus)
                    }
                    repeat(n) {
                        Thread.sleep(16)
                        onUi { if (scene.hasInvalidations()) busy++ }
                    }
                    reg.dispose()
                    wreg.dispose()
                    stacks.entries.sortedByDescending { it.value }.take(8).forEach { System.err.println("GW W ${it.value}x ${it.key}") }
                    System.err.println("GW idle: $busy of $n frames redrew")
                    seen.entries.sortedByDescending { it.value }.take(12).forEach { System.err.println("GW   ${it.value}x ${it.key}") }
                }
                "quit" -> break
            }
        } catch (e: Exception) {
            println("GW: step failed: $line ($e)")
        }
    }
    onUi { frames.stop(); ps.release() }
    System.exit(0)
}
