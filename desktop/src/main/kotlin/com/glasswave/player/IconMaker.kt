// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface

/**
 * Makes the Windows icon (icons/glasswave.ico) from the same design as the phone icon: the lime
 * wave on black. Run with  ./gradlew -p desktop makeIcon  after changing the design.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "icons/glasswave.ico")
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngs = sizes.map { s ->
        val surface = Surface.makeRasterN32Premul(s, s)
        val c = surface.canvas
        val k = s / 108f
        c.drawRRect(RRect.makeXYWH(0f, 0f, s.toFloat(), s.toFloat(), 24f * k), Paint().apply { color = Color.makeRGB(0x0E, 0x0E, 0x0E); isAntiAlias = true })
        // The wave is drawn a bit bigger than in the phone's safe zone, so it reads at small sizes.
        val z = k * 1.35f
        val o = s / 2f - 54f * z
        val wave = Path.makeFromSVGString("M30,60C38,38 48,38 54,54S70,70 78,48")
        c.translate(o, o)
        c.scale(z, z)
        c.drawPath(wave, Paint().apply {
            color = Color.makeRGB(0xC6, 0xF4, 0x32)
            mode = PaintMode.STROKE
            strokeWidth = 9f
            strokeCap = PaintStrokeCap.ROUND
            isAntiAlias = true
        })
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
    // An .ico file: a small header, one entry per size, then the PNG pictures.
    val bytes = ByteArrayOutputStream()
    val d = DataOutputStream(bytes)
    fun le16(v: Int) { d.writeByte(v and 0xFF); d.writeByte((v shr 8) and 0xFF) }
    fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
    le16(0); le16(1); le16(sizes.size)
    var offset = 6 + 16 * sizes.size
    sizes.forEachIndexed { i, s ->
        d.writeByte(if (s >= 256) 0 else s); d.writeByte(if (s >= 256) 0 else s)
        d.writeByte(0); d.writeByte(0)
        le16(1); le16(32)
        le32(pngs[i].size); le32(offset)
        offset += pngs[i].size
    }
    pngs.forEach { d.write(it) }
    out.parentFile?.mkdirs()
    out.writeBytes(bytes.toByteArray())
    File(out.parentFile, "glasswave.png").writeBytes(pngs.last())
    println("Wrote ${out.absolutePath}")
}
