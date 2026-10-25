// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Data saver (Settings > Player & audio): when it cuts back on data. */
enum class DataSaver(val label: String, val detail: String) {
    OFF("Off", "Your choices above, on every connection."),
    MOBILE("On mobile data", "Saves data away from Wi-Fi. On Wi-Fi everything is as normal."),
    ALWAYS("Always", "Saves data on every connection, Wi-Fi too."),
}

/** Whether the phone is on a connection that costs data (mobile data or a hotspot), kept up to date. */
object Net {
    var metered by mutableStateOf(false)
        private set
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        runCatching {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            metered = cm.getNetworkCapabilities(cm.activeNetwork)?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: false
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
                override fun onLost(network: Network) { metered = false }
            })
        }
    }
}
