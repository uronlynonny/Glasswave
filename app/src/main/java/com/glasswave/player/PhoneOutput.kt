// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which speaker or headset plays the music on the phone, like the PC's sound output picker:
 * this phone's speaker, wired headphones, Bluetooth, USB. "Automatic" lets Android choose (the
 * newest headset, else the speaker). The choice is kept by a steady name, so a Bluetooth headset
 * that comes back is used again; while it's away, Android's own choice plays.
 */
object PhoneOutput {
    enum class Kind { SPEAKER, WIRED, BLUETOOTH, USB, HEARING, OTHER }

    class Output(val key: String, val name: String, val kind: Kind)

    /** What can play right now. */
    var outputs by mutableStateOf<List<Output>>(emptyList())
        private set

    /** The picked output's key, or null for Automatic. */
    var chosen by mutableStateOf<String?>(null)
        private set

    /** Told when the device to use changes (the player service moves the sound there). */
    val listeners = mutableListOf<(AudioDeviceInfo?) -> Unit>()

    private var am: AudioManager? = null
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        if (am != null) return
        appContext = context.applicationContext
        am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = context.getSharedPreferences("phone_output", Context.MODE_PRIVATE)
        chosen = prefs?.getString("chosen", null)
        refresh()
        am?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { refresh(); tell() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { refresh(); tell() }
        }, Handler(Looper.getMainLooper()))
    }

    private fun kindOf(d: AudioDeviceInfo): Kind? = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> Kind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.USB
        else -> when {
            Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> Kind.BLUETOOTH
            Build.VERSION.SDK_INT >= 28 && d.type == AudioDeviceInfo.TYPE_HEARING_AID -> Kind.HEARING
            else -> null // the earpiece, call audio and the like aren't for music
        }
    }

    private fun keyOf(d: AudioDeviceInfo, kind: Kind) = when (kind) {
        Kind.SPEAKER -> "speaker"
        Kind.WIRED -> "wired"
        else -> "${kind.name.lowercase()}:${d.productName}"
    }

    private fun nameOf(d: AudioDeviceInfo, kind: Kind): String = when (kind) {
        Kind.SPEAKER -> "This phone"
        Kind.WIRED -> "Wired headphones"
        else -> d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.equals(Build.MODEL, true) }
            ?: when (kind) { Kind.BLUETOOTH -> "Bluetooth"; Kind.USB -> "USB audio"; Kind.HEARING -> "Hearing aid"; else -> "Other" }
    }

    /** Looks at what's connected now. */
    fun refresh() {
        val m = am ?: return
        val seen = LinkedHashMap<String, Output>()
        for (d in m.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val k = kindOf(d) ?: continue
            val key = keyOf(d, k)
            if (key !in seen) seen[key] = Output(key, nameOf(d, k), k)
        }
        // The phone first, then the rest as Android lists them.
        outputs = seen.values.sortedBy { if (it.kind == Kind.SPEAKER) 0 else 1 }
    }

    /** The device to play through now, or null for Android's own choice (Automatic, or the picked one isn't here). */
    fun preferred(): AudioDeviceInfo? {
        val want = chosen ?: return null
        val m = am ?: return null
        return m.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { d -> kindOf(d)?.let { keyOf(d, it) } == want }
    }

    fun pick(key: String?) {
        chosen = key
        prefs?.edit()?.putString("chosen", key)?.apply()
        tell()
    }

    /** The picked output isn't connected right now. */
    val chosenAway: Boolean get() = chosen != null && outputs.none { it.key == chosen }

    private fun tell() {
        val d = preferred()
        listeners.toList().forEach { runCatching { it(d) } }
    }

    /**
     * Android's own output switcher (casting, nearby speakers, everything the phone knows), where it
     * has one. False if this phone doesn't offer it.
     */
    fun openSystemSwitcher(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            if (runCatching { android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return true
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui")
                .putExtra("package_name", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.sendBroadcast(i); true }.getOrDefault(false)) return true
        }
        return false
    }
}
