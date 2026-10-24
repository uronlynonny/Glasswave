package android.os

import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import javax.swing.Timer

object Build {
    object VERSION {
        /** Desktop has every graphics feature the app checks for. */
        const val SDK_INT = 34
        const val MEDIA_PERFORMANCE_CLASS = 34
    }
}

object SystemClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}

/** There's one "main thread" on desktop: the window's event thread. */
class Looper private constructor() {
    companion object {
        private val main = Looper()
        fun getMainLooper(): Looper = main
        fun myLooper(): Looper? = if (SwingUtilities.isEventDispatchThread()) main else null
    }
}

/** Runs code on the window's event thread, now or after a delay. */
class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper = Looper.getMainLooper()) {
    private val timers = ConcurrentHashMap<Runnable, MutableList<Timer>>()

    fun post(r: Runnable): Boolean = postDelayed(r, 0)

    fun postDelayed(r: Runnable, delayMs: Long): Boolean {
        if (delayMs <= 0) {
            val t = Timer(0, null)
            track(r, t)
            SwingUtilities.invokeLater {
                if (untrack(r, t)) r.run()
            }
            return true
        }
        val t = Timer(delayMs.toInt(), null).apply { isRepeats = false }
        t.addActionListener { if (untrack(r, t)) r.run() }
        track(r, t)
        t.start()
        return true
    }

    fun removeCallbacks(r: Runnable) {
        timers.remove(r)?.forEach { it.stop() }
    }

    private fun track(r: Runnable, t: Timer) {
        timers.compute(r) { _, l -> (l ?: mutableListOf()).apply { add(t) } }
    }

    /** True if [t] was still waiting (not cancelled), and takes it off the list. */
    private fun untrack(r: Runnable, t: Timer): Boolean {
        var was = false
        timers.computeIfPresent(r) { _, l -> was = l.remove(t); l.ifEmpty { null } }
        return was
    }
}
