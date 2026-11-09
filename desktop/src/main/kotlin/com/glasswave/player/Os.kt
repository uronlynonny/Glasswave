// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/** Which computer Glasswave is running on. The same app runs on Windows, Mac and Linux. */
object Os {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = name.startsWith("mac")
    val isWindows = name.startsWith("windows")
    val isLinux = name.startsWith("linux")

    /** What to call this computer in the app's text: "PC" on Windows and Linux, "Mac" on a Mac. */
    val computer = if (isMac) "Mac" else "PC"

    /** The system's name in the app's text and reports: "Windows", "Mac" or "Linux". */
    val system = when {
        isMac -> "Mac"
        isLinux -> "Linux"
        else -> "Windows"
    }
}
