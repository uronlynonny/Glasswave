// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Path
import org.jetbrains.skia.Point
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/**
 * Makes the website's link preview picture (docs/og.png, 1200 × 630): what shows up when someone
 * shares the link. Run with  ./gradlew -p desktop makeOg  after changing the wording.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "../docs/og.png")
    val w = 1200
    val h = 630
    val surface = Surface.makeRasterN32Premul(w, h)
    val c = surface.canvas
    c.clear(Color.makeRGB(0x04, 0x07, 0x05))
    fun glow(x: Float, y: Float, r: Float, argb: Int) = c.drawCircle(x, y, r, Paint().apply {
        shader = Shader.makeRadialGradient(Point(x, y), r, intArrayOf(argb, argb and 0x00FFFFFF), null)
    })
    glow(470f, 180f, 520f, 0x8C3F6418.toInt())
    glow(1090f, 560f, 260f, 0x400E5A2E)

    // The app icon: the lime wave on black.
    val ix = 90f
    val iy = 150f
    val s = 150f
    c.drawRRect(RRect.makeXYWH(ix, iy, s, s, 24f), Paint().apply { color = Color.makeRGB(0x0E, 0x0E, 0x0E); isAntiAlias = true })
    c.drawRRect(RRect.makeXYWH(ix + 1f, iy + 1f, s - 2f, s - 2f, 23f), Paint().apply {
        color = Color.makeARGB(0x50, 0xFF, 0xFF, 0xFF); mode = PaintMode.STROKE; strokeWidth = 2f; isAntiAlias = true
    })
    val z = s / 108f * 1.35f
    c.save()
    c.translate(ix + s / 2 - 54f * z, iy + s / 2 - 54f * z)
    c.scale(z, z)
    c.drawPath(Path.makeFromSVGString("M30,60C38,38 48,38 54,54S70,70 78,48"), Paint().apply {
        color = Color.makeRGB(0xC6, 0xF4, 0x32); mode = PaintMode.STROKE; strokeWidth = 9f; strokeCap = PaintStrokeCap.ROUND; isAntiAlias = true
    })
    c.restore()

    val fonts = FontMgr.default
    fun face(bold: Boolean) = fonts.matchFamilyStyle("Segoe UI", if (bold) FontStyle.BOLD else FontStyle(600, 5, org.jetbrains.skia.FontSlant.UPRIGHT))
    fun text(t: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) =
        c.drawString(t, x, y, Font(face(bold), size), Paint().apply { this.color = color; isAntiAlias = true })

    text("Glasswave", 284f, 262f, 128f, Color.makeRGB(0xF1, 0xF6, 0xEC), true)
    text("A free music player for Android and Windows", 288f, 306f, 36f, Color.makeRGB(0xC6, 0xF4, 0x32), false)
    text("Free  ·  YouTube Music  ·  Spotify playlist sync  ·  Synced lyrics  ·  Automix", 98f, 452f, 26f, Color.makeRGB(0xA6, 0xAD, 0xA3), false)
    text("by nonny dev  ·  uronlynonny.github.io/Glasswave", 98f, 502f, 25f, Color.makeRGB(0xB9, 0xC0, 0xB6), true)

    out.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("Wrote ${out.absolutePath}")
}
