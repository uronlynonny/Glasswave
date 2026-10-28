// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/** Which computer Glasswave is running on. The same app runs on Windows and Mac. */
object Os {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = name.startsWith("mac")
    val isWindows = name.startsWith("windows")

    /** What to call this computer in the app's text: "PC" on Windows, "Mac" on a Mac. */
    val computer = if (isMac) "Mac" else "PC"
}
