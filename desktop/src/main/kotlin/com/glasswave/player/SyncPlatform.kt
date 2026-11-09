// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/** How this PC shows up in the Devices list on your phone (see Sync). */
object SyncPlatform {
    const val kind = "pc"

    fun deviceName(): String {
        // A Mac's own name, as set in System Settings › General › About ("Sam's MacBook Air").
        if (Os.isMac) return runCatching {
            val p = ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
        }.getOrNull() ?: "Mac"
        // On Linux: the computer's host name ("thinkpad"), unless it's a made-up or default one.
        if (Os.isLinux) return runCatching {
            (System.getenv("HOSTNAME") ?: java.io.File("/etc/hostname").takeIf { it.isFile }?.readText())?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() && it != "localhost" && !it.contains('.') }
            ?.replaceFirstChar { it.uppercase() } ?: "Linux PC"
        val pc = System.getenv("COMPUTERNAME").orEmpty().trim()
        // Windows' made-up names (DESKTOP-4F7Q2K1) mean nothing to people.
        return if (pc.isEmpty() || Regex("^(DESKTOP|LAPTOP|WIN)-[A-Z0-9]+$", RegexOption.IGNORE_CASE).matches(pc)) "Windows PC"
        else pc.lowercase().replaceFirstChar { it.uppercase() }
    }
}
