package com.glasswave.player

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import javax.swing.SwingUtilities

/**
 * Shortcuts that work even while Glasswave is behind other windows (Windows only):
 * Ctrl+Alt+→ next song, Ctrl+Alt+← previous song, Ctrl+Alt+Space play/pause
 * (Ctrl+Alt+P instead when another app already uses Ctrl+Alt+Space).
 * The keyboard's media keys (⏭ ⏮ ⏯) work too, but only while a song is loaded,
 * so they still reach other apps when Glasswave has nothing to play.
 */
object GlobalKeys {
    private const val MOD_ALT = 0x1
    private const val MOD_CONTROL = 0x2
    private const val MOD_NOREPEAT = 0x4000
    private const val VK_SPACE = 0x20
    private const val VK_P = 0x50
    private const val VK_LEFT = 0x25
    private const val VK_RIGHT = 0x27
    private const val VK_MEDIA_NEXT = 0xB0
    private const val VK_MEDIA_PREV = 0xB1
    private const val VK_MEDIA_PLAY_PAUSE = 0xB3
    private const val WM_HOTKEY = 0x0312
    private const val WM_APP = 0x8000

    private const val NEXT = 1
    private const val PREV = 2
    private const val TOGGLE = 3
    private const val MEDIA_NEXT = 4
    private const val MEDIA_PREV = 5
    private const val MEDIA_TOGGLE = 6

    @Volatile private var threadId = 0
    @Volatile private var wantMedia = false
    private var mediaOn = false // only touched on the keys thread

    fun start(ps: PlayerState) {
        if (!Os.isWindows || threadId != 0) return
        Thread({
            runCatching {
                val u = User32.INSTANCE
                fun reg(id: Int, mods: Int, vk: Int) = u.RegisterHotKey(null, id, mods or MOD_NOREPEAT, vk)
                val ok = listOf(reg(NEXT, MOD_CONTROL or MOD_ALT, VK_RIGHT), reg(PREV, MOD_CONTROL or MOD_ALT, VK_LEFT), reg(TOGGLE, MOD_CONTROL or MOD_ALT, VK_SPACE) || reg(TOGGLE, MOD_CONTROL or MOD_ALT, VK_P))
                android.util.Log.i("Glasswave", "Shortcuts from any window: " + if (ok.all { it }) "on" else "some taken by another app $ok")
                threadId = Kernel32.INSTANCE.GetCurrentThreadId()
                syncMedia()
                val msg = WinUser.MSG()
                while (u.GetMessage(msg, null, 0, 0) > 0) {
                    when (msg.message) {
                        WM_HOTKEY -> {
                            val id = msg.wParam.toInt()
                            SwingUtilities.invokeLater {
                                when (id) {
                                    NEXT, MEDIA_NEXT -> ps.next()
                                    PREV, MEDIA_PREV -> ps.previous()
                                    TOGGLE, MEDIA_TOGGLE -> ps.toggle()
                                }
                            }
                        }
                        WM_APP -> syncMedia()
                    }
                }
            }.onFailure { android.util.Log.w("Glasswave", "Shortcuts outside the window are off ($it)") }
        }, "global-keys").apply { isDaemon = true }.start()
    }

    /** Media keys belong to Glasswave only while it has a song loaded. */
    fun mediaKeys(on: Boolean) {
        wantMedia = on
        val t = threadId
        if (t != 0) runCatching { User32.INSTANCE.PostThreadMessage(t, WM_APP, WinDef.WPARAM(0), WinDef.LPARAM(0)) }
    }

    private fun syncMedia() {
        val u = User32.INSTANCE
        if (wantMedia == mediaOn) return
        if (wantMedia) {
            // Another app may already own them; then they just keep working there.
            u.RegisterHotKey(null, MEDIA_NEXT, MOD_NOREPEAT, VK_MEDIA_NEXT)
            u.RegisterHotKey(null, MEDIA_PREV, MOD_NOREPEAT, VK_MEDIA_PREV)
            u.RegisterHotKey(null, MEDIA_TOGGLE, MOD_NOREPEAT, VK_MEDIA_PLAY_PAUSE)
        } else {
            u.UnregisterHotKey(null, MEDIA_NEXT)
            u.UnregisterHotKey(null, MEDIA_PREV)
            u.UnregisterHotKey(null, MEDIA_TOGGLE)
        }
        mediaOn = wantMedia
    }
}
