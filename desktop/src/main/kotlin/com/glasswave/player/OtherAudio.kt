// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import javax.swing.SwingUtilities

/**
 * Pause for other apps (Settings.pauseForOthers), on Windows: when a video in the browser, or any
 * other app, plays sound for a few seconds while Glasswave is playing, Glasswave pauses; when that
 * sound stops for a few seconds, it carries on. A short ping (a message, a notification) never
 * pauses it, and if you press play yourself while the other sound goes on, it's left alone until
 * that sound stops.
 *
 * How: Windows keeps a volume meter for every app's sound (the ones in the Volume mixer). Twice a
 * second this reads the meters of the default speakers' apps, leaving out Glasswave and Windows'
 * own system sounds. Read through Windows' Core Audio interfaces with plain JNA calls.
 */
object OtherAudio {
    /** Some other app has been making sound for a few seconds now. */
    @Volatile var otherPlaying = false
        private set

    private const val LOUD = 0.004f
    private const val START_MS = 3_000L
    private const val STOP_MS = 3_000L
    private const val GAP_MS = 1_200L

    fun start(ps: PlayerState) {
        // (Test copies run it too, so it can be tried; GitHub's machines don't.)
        if (!Os.isWindows || System.getenv("CI") != null) return
        Thread({ watch(ps) }, "gw-other-audio").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /* ---------------- What Glasswave does about it ---------------- */

    private var pausedAt = 0L
    private var leaveAlone = false

    private fun react(ps: PlayerState, other: Boolean) {
        if (!Settings.pauseForOthers || Jam.inJam) { pausedAt = 0L; leaveAlone = false; return }
        val now = System.currentTimeMillis()
        if (other) {
            when {
                // You pressed play while it's still going: it's your call until that sound stops.
                pausedAt != 0L && ps.playing -> { pausedAt = 0L; leaveAlone = true }
                !leaveAlone && pausedAt == 0L && ps.playing -> { ps.pause(); pausedAt = now }
            }
        } else {
            leaveAlone = false
            if (pausedAt != 0L) {
                // Carry on, unless it's been over an hour or something was played meanwhile.
                if (!ps.playing && ps.hasItem && now - pausedAt < 60 * 60_000L) ps.play()
                pausedAt = 0L
            }
        }
    }

    /* ---------------- Reading Windows' meters ---------------- */

    private val CLSID_MMDeviceEnumerator = Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    private val IID_IMMDeviceEnumerator = Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    private val IID_IAudioSessionManager2 = Guid.IID("{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}")
    private val IID_IAudioSessionControl2 = Guid.IID("{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}")
    private val IID_IAudioMeterInformation = Guid.IID("{C02216F6-8C67-4B5B-9D00-D008E73E0064}")

    /** Calls method [index] of the COM object [obj] (its vtable), returning the HRESULT. */
    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf(obj, *args))
    }

    private fun release(obj: Pointer?) { if (obj != null) runCatching { call(obj, 2) } }

    private fun query(obj: Pointer, iid: Guid.IID): Pointer? {
        val out = PointerByReference()
        return if (call(obj, 0, iid, out) == 0) out.value else null
    }

    private val myPid = ProcessHandle.current().pid().toInt()
    private val ignored = HashMap<Int, Boolean>()

    /** Glasswave itself (any copy or helper) doesn't count. */
    private fun isGlasswave(pid: Int): Boolean = ignored.getOrPut(pid) {
        pid == myPid || ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }.orElse("").substringAfterLast('\\').startsWith("Glasswave", true)
    }

    /** The loudest other app's meter right now (0 when nothing else is making sound). */
    private fun loudestOther(manager: Pointer): Float {
        val en = PointerByReference()
        if (call(manager, 5, en) != 0) return 0f
        val sessions = en.value
        var loudest = 0f
        try {
            val count = IntByReference()
            if (call(sessions, 3, count) != 0) return 0f
            for (i in 0 until count.value) {
                val ctl = PointerByReference()
                if (call(sessions, 4, i, ctl) != 0) continue
                val c = ctl.value
                var c2: Pointer? = null
                var meter: Pointer? = null
                try {
                    c2 = query(c, IID_IAudioSessionControl2) ?: continue
                    if (call(c2, 15) == 0) continue // Windows' own system sounds
                    val pid = IntByReference()
                    if (call(c2, 14, pid) != 0 && pid.value == 0) continue
                    if (isGlasswave(pid.value)) continue
                    meter = query(c, IID_IAudioMeterInformation) ?: continue
                    val peak = Memory(4)
                    if (call(meter, 3, peak) == 0) loudest = maxOf(loudest, peak.getFloat(0))
                } finally {
                    release(meter); release(c2); release(c)
                }
            }
        } finally {
            release(sessions)
        }
        return loudest
    }

    private fun manager(): Pointer? {
        val enumerator = PointerByReference()
        if (Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, 23, IID_IMMDeviceEnumerator, enumerator).toInt() != 0) return null
        try {
            val device = PointerByReference()
            if (call(enumerator.value, 4, 0, 1, device) != 0) return null // default speakers, for music
            try {
                val mgr = PointerByReference()
                return if (call(device.value, 3, IID_IAudioSessionManager2, 23, null, mgr) == 0) mgr.value else null
            } finally {
                release(device.value)
            }
        } finally {
            release(enumerator.value)
        }
    }

    private fun watch(ps: PlayerState) {
        Ole32.INSTANCE.CoInitializeEx(null, 0) // multithreaded
        var mgr: Pointer? = null
        var mgrAt = 0L
        var loudSince = 0L
        var lastLoud = 0L
        var quietSince = 0L
        while (true) {
            try {
                Thread.sleep(500)
                if (!Settings.pauseForOthers) { if (otherPlaying) { otherPlaying = false; SwingUtilities.invokeLater { react(ps, false) } }; continue }
                val now = System.currentTimeMillis()
                // The default speakers can change: look them up again now and then.
                if (mgr == null || now - mgrAt > 20_000) { release(mgr); mgr = manager(); mgrAt = now }
                val m = mgr ?: continue
                val loud = loudestOther(m) > LOUD
                if (loud) {
                    if (loudSince == 0L || now - lastLoud > GAP_MS) loudSince = now
                    lastLoud = now
                    quietSince = 0L
                } else if (quietSince == 0L) quietSince = now
                val was = otherPlaying
                otherPlaying = when {
                    !was -> loud && now - loudSince >= START_MS
                    else -> !(quietSince != 0L && now - quietSince >= STOP_MS)
                }
                if (otherPlaying != was || otherPlaying) {
                    val o = otherPlaying
                    SwingUtilities.invokeLater { react(ps, o) }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                android.util.Log.w("Glasswave", "Other-app sound check failed", e)
                release(mgr); mgr = null
                runCatching { Thread.sleep(5_000) }
            }
        }
    }
}
