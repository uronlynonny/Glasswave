// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/** Which computer Glasswave is running on. The same app runs on Windows, Mac and Linux. */
object Os {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = name.startsWith("mac")
    val isWindows = name.startsWith("windows")
    val isLinux = name.startsWith("linux")

    /**
     * A check, not a person: running on GitHub's machines (CI) or as a test copy. Problem alerts,
     * song reports and usage counts stay quiet, so checks never reach nonny dev's alerts or numbers.
     */
    val isTestRun: Boolean = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null ||
        System.getProperty("gw.noUpdate") != null

    /** What to call this computer in the app's text: "PC" on Windows and Linux, "Mac" on a Mac. */
    val computer = if (isMac) "Mac" else "PC"

    /** The system's name in the app's text and reports: "Windows", "Mac" or "Linux". */
    val system = when {
        isMac -> "Mac"
        isLinux -> "Linux"
        else -> "Windows"
    }
}
