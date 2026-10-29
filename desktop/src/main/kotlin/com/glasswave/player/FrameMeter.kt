// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/** For the checks: how long each frame took to come (only counted while a check script runs). */
object FrameMeter {
    private val gaps = ArrayList<Long>()

    @Synchronized fun add(ms: Long) { gaps += ms }
    @Synchronized fun reset() = gaps.clear()

    @Synchronized fun summary(): String {
        if (gaps.isEmpty()) return "no frames"
        val sorted = gaps.sorted()
        val avg = gaps.average()
        val slow = gaps.count { it > 25 }
        return "%d frames, average %.1f ms (%.0f fps), slowest %d ms, 95%% under %d ms, %d slow frames (over 25 ms)".format(
            gaps.size, avg, 1000 / avg, sorted.last(), sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)], slow,
        )
    }
}
