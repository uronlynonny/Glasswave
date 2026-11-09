// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import javax.swing.SwingUtilities

/**
 * Windows: when the screen turns off or the PC sleeps, some graphics drivers (Intel's especially)
 * quietly drop a window's drawing surface, and the window stays white until it's hidden and shown
 * again. This rebuilds the surface instead: when the PC wakes up, and when you come back to a window
 * you left alone for a while.
 */
object GpuReset {
    /** Left alone this long, a window gets a fresh surface when you come back to it. */
    const val AWAY_MS = 3 * 60_000L

    fun start() {
        if (!Os.isWindows) return
        Thread({
            var last = System.currentTimeMillis()
            while (true) {
                Thread.sleep(2_000)
                val now = System.currentTimeMillis()
                // Much more than 2 s went by: the PC was asleep (Windows freezes apps then). The driver
                // may still be waking up, so once now and once a few seconds later.
                if (now - last > 15_000) {
                    SwingUtilities.invokeLater { rebuildAll("the PC woke up") }
                    Thread.sleep(5_000)
                    SwingUtilities.invokeLater { rebuildAll("the PC woke up, again") }
                }
                last = System.currentTimeMillis()
            }
        }, "gw-wake-watch").apply { isDaemon = true; start() }
    }

    /** Every Glasswave window on screen (the main one and the mini player). Call on the window thread. */
    fun rebuildAll(why: String) {
        java.awt.Window.getWindows().filter { it.isShowing }.forEach { rebuild(it, why) }
    }

    /** A fresh drawing surface for [w], the same way the drawing library makes its first one. Call on the window thread. */
    fun rebuild(w: java.awt.Window, why: String) {
        if (!Os.isWindows) return
        runCatching {
            val layer = GpuMemory.findLayer(w) ?: return
            // The window's layer is the app framework's own kind of SkiaLayer, so the field is on a parent class.
            val manager = generateSequence<Class<*>>(layer.javaClass) { it.superclass }
                .firstNotNullOfOrNull { c -> runCatching { c.getDeclaredField("redrawerManager") }.getOrNull() }
                ?.apply { isAccessible = true }?.get(layer) ?: error("no redrawerManager")
            manager.javaClass.getMethod("findNextWorkingRenderApi", Boolean::class.javaPrimitiveType).invoke(manager, true)
            layer.needRedraw()
            Rebuilds.count++
            android.util.Log.i("Glasswave", "Fresh drawing surface for ${w.javaClass.simpleName} ($why)")
        }.onFailure { android.util.Log.w("Glasswave", "Couldn't refresh the drawing surface", it) }
    }

    /** Rebuilds [w]'s surface when it comes back to the front after [AWAY_MS] in the background. */
    fun watch(w: java.awt.Window) {
        if (!Os.isWindows) return
        var leftAt = 0L
        w.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowDeactivated(e: java.awt.event.WindowEvent) { leftAt = System.currentTimeMillis() }
            override fun windowActivated(e: java.awt.event.WindowEvent) {
                if (leftAt > 0 && System.currentTimeMillis() - leftAt >= AWAY_MS) rebuild(w, "back after a while")
            }
        })
    }
}

/** For checks: how many times a surface was rebuilt. */
internal object Rebuilds {
    @Volatile var count = 0
}
