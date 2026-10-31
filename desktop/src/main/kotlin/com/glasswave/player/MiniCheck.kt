// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import java.io.File
import javax.swing.SwingUtilities

/**
 * For checks: draws the mini player off-screen in each style and size, over a stand-in desktop
 * (so you can see how see-through it is), and saves the pictures. ./gradlew -p desktop miniCheck -Pout=<folder>
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "mini-check").apply { mkdirs() }
    System.setProperty("gw.noCheckIn", "1")
    startUp()
    AppVolume.muteForChecks()
    // Colours from the cover (a fixed app colour would override them).
    SwingUtilities.invokeAndWait { Settings.updateAccentColor(null) }
    // A song to show: Calm Down by Rema, paused a minute in.
    val now = System.currentTimeMillis()
    File(android.content.AppDirs.data, "last_played.json").writeText(
        """{"items":[{"u":"https://music.youtube.com/watch?v=WcIcVapfqXw","t":"Calm Down","a":"Rema","s":239,"i":"https://i.ytimg.com/vi/WcIcVapfqXw/hqdefault.jpg"}],"base":[0],"shuf":[0],"cur":0,"pos":61000,"shuffle":false,"repeat":0,"at":$now,"from":""}""",
    )
    lateinit var ps: PlayerState
    SwingUtilities.invokeAndWait { ps = PlayerState().also { it.connect() } }
    Thread.sleep(4000) // the last song comes back and its cover loads
    val dummy = java.awt.Window(null as java.awt.Frame?)
    data class Case(val name: String, val w: Int, val h: Int, val style: MiniStyle, val opacity: Float, val depth: Float, val corners: Float)
    val cases = listOf(
        Case("small", 240, 96, MiniStyle.COLOUR, 0.9f, 0.5f, 0.6f),
        Case("default", 330, 118, MiniStyle.COLOUR, 0.9f, 0.5f, 0.6f),
        Case("wide", 440, 140, MiniStyle.COLOUR, 0.9f, 0.5f, 0.6f),
        Case("tall", 300, 400, MiniStyle.COLOUR, 0.9f, 0.5f, 0.6f),
        Case("blur", 330, 118, MiniStyle.BLUR, 0.9f, 0.5f, 0.6f),
        Case("dark", 330, 118, MiniStyle.DARK, 0.9f, 0.5f, 0.6f),
        Case("see-through", 330, 118, MiniStyle.COLOUR, 0.55f, 0.5f, 0.6f),
    )
    for (c in cases) {
        SwingUtilities.invokeAndWait {
            Settings.updateMiniStyle(c.style)
            Settings.updateMiniOpacity(c.opacity)
            Settings.updateMiniDepth(c.depth)
            Settings.updateMiniCorners(c.corners)
        }
        var scene: ImageComposeScene? = null
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(c.w, c.h, Density(1f)) {
                // A stand-in desktop behind the see-through window.
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B6FB6), Color(0xFFE7A15A), Color(0xFF2E8B57))))) {
                    MiniCard(ps, dummy)
                }
            }
        }
        repeat(40) { SwingUtilities.invokeAndWait { scene!!.render(System.nanoTime()) }; Thread.sleep(50) }
        SwingUtilities.invokeAndWait {
            val png = scene!!.render(System.nanoTime()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            File(out, "${c.name}.png").writeBytes(png)
            scene!!.close()
        }
        println("GW mini: ${c.name}")
    }
    // A second song with a very different cover, to see the colour follow the song.
    SwingUtilities.invokeAndWait {
        ps.playList(listOf(Track("https://music.youtube.com/watch?v=4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", 201, "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg")), 0)
        Settings.updateMiniStyle(MiniStyle.COLOUR)
        Settings.updateMiniOpacity(0.9f)
    }
    Thread.sleep(1500)
    SwingUtilities.invokeAndWait { if (ps.playing) ps.toggle() }
    var scene2: ImageComposeScene? = null
    SwingUtilities.invokeAndWait {
        scene2 = ImageComposeScene(330, 118, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B6FB6), Color(0xFFE7A15A), Color(0xFF2E8B57))))) {
                MiniCard(ps, dummy)
            }
        }
    }
    repeat(60) { SwingUtilities.invokeAndWait { scene2!!.render(System.nanoTime()) }; Thread.sleep(50) }
    SwingUtilities.invokeAndWait {
        File(out, "second-song.png").writeBytes(scene2!!.render(System.nanoTime()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
    }
    println("GW mini: '${ps.title}' by '${ps.artist}'")
    Runtime.getRuntime().halt(0)
}
