// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/** How this PC shows up in the Devices list on your phone (see Sync). */
object SyncPlatform {
    const val kind = "pc"

    fun deviceName(): String {
        val pc = System.getenv("COMPUTERNAME").orEmpty().trim()
        // Windows' made-up names (DESKTOP-4F7Q2K1) mean nothing to people.
        return if (pc.isEmpty() || Regex("^(DESKTOP|LAPTOP|WIN)-[A-Z0-9]+$", RegexOption.IGNORE_CASE).matches(pc)) "Windows PC"
        else pc.lowercase().replaceFirstChar { it.uppercase() }
    }
}
