// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.AppDirs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import javax.swing.SwingUtilities

/**
 * Only one Glasswave at a time. Java apps take a few seconds to appear, so clicking the shortcut
 * again while waiting used to open a second (and third) copy. Now a second launch just brings the
 * open window to the front and quits.
 */
object SingleInstance {
    private var lock: FileLock? = null

    /** Goes up by one each time another launch asks this copy to show itself. */
    var showRequests by mutableIntStateOf(0)
        private set

    /** True if this is the only copy running. If one is already open, asks it to come forward. */
    fun claim(): Boolean {
        val lockFile = File(AppDirs.data, "running.lock")
        val portFile = File(AppDirs.data, "running.port")
        val l = runCatching {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).tryLock()
        }.getOrNull()
        if (l == null) {
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), portFile.readText().trim().toInt()).use {
                    it.soTimeout = 2000
                    it.getOutputStream().write("show\n".toByteArray())
                }
            }
            return false
        }
        lock = l
        // A tiny listener on this PC only (not the network) for "show yourself" from later launches.
        runCatching {
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            Thread {
                while (true) {
                    runCatching {
                        server.accept().use { s ->
                            s.soTimeout = 2000
                            if (s.getInputStream().bufferedReader().readLine() == "show") SwingUtilities.invokeLater { showRequests++ }
                        }
                    }
                }
            }.apply { isDaemon = true; name = "Glasswave single instance" }.start()
        }
        return true
    }
}
