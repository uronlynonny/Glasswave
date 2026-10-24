// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import javax.swing.SwingUtilities

/**
 * For automated checks only: acts as an older copy, downloads the live Windows update with the
 * connection dropping partway (see build.gradle.kts updateCheck), and prints how it went.
 * Nothing is installed.
 */
fun main() {
    android.content.AppDirs.cache.mkdirs()
    SwingUtilities.invokeAndWait { Updater.init(android.content.AppContext); Updater.check(tell = true) }
    val start = System.currentTimeMillis()
    var lastPct = -1
    while (System.currentTimeMillis() - start < 15 * 60_000) {
        Thread.sleep(500)
        var done = false
        SwingUtilities.invokeAndWait {
            val p = Updater.progress?.let { (it * 100).toInt() }
            if (p != null && p / 10 != lastPct / 10) { println("GW progress $p%"); lastPct = p }
            if (Updater.ready != null || (Updater.progress == null && !Updater.checking && Updater.status != null)) done = true
        }
        if (done) break
    }
    SwingUtilities.invokeAndWait {
        val f = Updater.ready
        println("GW result: ready=${f?.name} size=${f?.length()} status=${Updater.status} after ${(System.currentTimeMillis() - start) / 1000}s")
        if (f != null) {
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            println("GW sha256 ${sha.take(20)}")
        }
    }
    System.exit(0)
}
