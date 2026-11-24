// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import com.sun.jna.Library as JnaLibrary
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Opening the app faster. Most of the wait at launch is Java reading the app's code from its files,
 * one piece at a time, on the thread that draws the window. Two helpers read the pieces the first
 * screen needs (listed in boot-classes.txt) ahead of time, on the computer's other cores, while the
 * main thread loads your library and settings; by the time the window opens, most of it is ready.
 *
 * They only read the code, they don't run any of it, so nothing happens twice or out of order.
 * A name that's no longer in the app (the list is from an older build) is just skipped.
 *
 * The list is Java's own record of what it loaded on the way to the first window. To make it again,
 * start the app with -XX:DumpLoadedClassList=list.txt, wait for Home, close it, and keep the first
 * word of each line that doesn't start with # or @ ("/" made ".", without $$Lambda, $Proxy and
 * LambdaForm$ names).
 */
object Preload {
    fun start() {
        val names = runCatching {
            Preload::class.java.getResourceAsStream("/boot-classes.txt")?.bufferedReader()?.use { it.readLines() }
        }.getOrNull()?.filter { it.isNotBlank() } ?: return
        val loader = Preload::class.java.classLoader
        // The second helper starts halfway down the list (what the window needs later on), so the
        // two don't race each other for the same pieces.
        val half = names.size / 2
        for ((i, part) in listOf(names.subList(0, half), names.subList(half, names.size)).withIndex()) {
            Thread({
                for (n in part) {
                    try { Class.forName(n, false, loader) } catch (_: Throwable) {}
                }
            }, "gw-preload-$i").apply { isDaemon = true; start() }
        }
    }
}

/**
 * While Glasswave opens, Windows gives it a little more of the processor than the other apps, so a
 * busy computer (a game, a big download, a build) doesn't leave you waiting on a blank screen.
 * Back to normal a few seconds after the window shows, so it never takes from your other apps
 * while it plays.
 */
object StartPriority {
    private interface Kernel : JnaLibrary {
        fun GetCurrentProcess(): Pointer
        fun SetPriorityClass(process: Pointer, priorityClass: Int): Boolean
    }

    private const val NORMAL = 0x20
    private const val ABOVE_NORMAL = 0x8000

    private val kernel by lazy { if (!Os.isWindows) null else runCatching { Native.load("kernel32", Kernel::class.java) }.getOrNull() }

    private fun set(priority: Int) {
        runCatching { kernel?.let { k -> k.SetPriorityClass(k.GetCurrentProcess(), priority) } }
    }

    fun boost() {
        if (!Os.isWindows) return
        Thread({ set(ABOVE_NORMAL) }, "gw-priority").apply { isDaemon = true; start() }
    }

    /** The window is up: give the processor back (after a short while, for the first screen's pictures). */
    fun settle() {
        if (!Os.isWindows) return
        Thread({ runCatching { Thread.sleep(8_000) }; set(NORMAL) }, "gw-priority").apply { isDaemon = true; start() }
    }
}
