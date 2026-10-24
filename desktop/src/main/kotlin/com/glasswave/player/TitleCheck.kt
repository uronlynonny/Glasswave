// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * For automated checks only: opens a small window with the app's dark background for each
 * title-bar material, takes a picture of just that window's area of the screen, and closes it.
 * No mouse or keyboard is used.  ./gradlew -p desktop titleCheck -Pout=folder
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: ".")
    out.mkdirs()
    for ((name, kind) in listOf("acrylic" to 3, "mica" to 2, "micaalt" to 4)) {
        lateinit var f: JFrame
        SwingUtilities.invokeAndWait {
            f = JFrame("Glasswave")
            f.contentPane = object : JPanel() {
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g as java.awt.Graphics2D
                    g2.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x10, 0x1A, 0x0C), 0f, height.toFloat(), java.awt.Color(0x04, 0x06, 0x04))
                    g2.fillRect(0, 0, width, height)
                }
            }
            f.setSize(720, 260)
            f.setLocation(200, 200)
            f.isAlwaysOnTop = true
            f.isVisible = true
            glassTitleBar(f, kind)
            f.toFront()
            f.requestFocus()
        }
        Thread.sleep(1500)
        val img = java.awt.Robot().createScreenCapture(f.bounds)
        ImageIO.write(img, "png", File(out, "title-$name.png"))
        System.err.println("GW title $name active=${f.isActive}")
        SwingUtilities.invokeAndWait { f.dispose() }
    }
    System.exit(0)
}
