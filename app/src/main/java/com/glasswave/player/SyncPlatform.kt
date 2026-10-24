// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.os.Build

/** How this phone shows up in the Devices list on your PC (see Sync). */
object SyncPlatform {
    const val kind = "phone"
    private var name: String? = null

    fun init(context: Context) {
        // The name you gave the phone in Android settings ("Nonny's S23"), else its model.
        name = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun deviceName(): String = name ?: run {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }
}
