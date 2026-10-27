// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File

/**
 * For checks only, plays nothing out loud: runs an audio file through the speed/pitch filter and the
 * sound effects, and prints how long the result is and what note it's at.
 *   ./gradlew -p desktop fxCheck -Pfile=tone.wav
 */
fun main(args: Array<String>) {
    val file = File(args.firstOrNull() ?: error("Give an audio file"))
    val rate = 48_000
    for ((speed, semis) in listOf(1f to 0f, 1.25f to 0f, 1f to 2f, 0.75f to -3f, 1.5f to 12f)) {
        val g = FFmpegFrameGrabber(file)
        g.sampleFormat = avutil.AV_SAMPLE_FMT_FLT
        g.sampleRate = rate
        g.audioChannels = 2
        g.start()
        val pitch = Math.pow(2.0, semis / 12.0).toFloat()
        val filter = if (speed == 1f && pitch == 1f) null else AudioDeck.tempoFilter(speed, pitch) ?: error("No filter")
        // Every effect on, to make sure nothing blows up (silence, NaN). Checks keep their own settings.
        Sound.init(android.content.Context())
        Sound.applyPreset(EqPreset.ROCK)
        Sound.updateBass(0.6f); Sound.updateTreble(0.4f); Sound.updateWidth(0.5f)
        Sound.updateReverb(0.5f); Sound.updateDrive(0.3f); Sound.updateBalance(-0.2f)
        val fx = SoundFx(rate)
        fx.update()
        var frames = 0L
        var crossings = 0
        var last = 0f
        var peak = 0f
        var nan = false
        fun take(f: org.bytedeco.javacv.Frame) {
            val bufs = f.samples ?: return
            val raw = bufs[0]
            if (bufs.size >= 2) error("planar output: ${bufs.size} buffers")
            val fb = raw as java.nio.FloatBuffer
            val n = fb.remaining()
            var i = 0
            while (i + 1 < n) {
                val l = fb.get(fb.position() + i)
                if (l > 0 && last <= 0) crossings++
                last = l
                fx.process(l, fb.get(fb.position() + i + 1))
                if (fx.outL.isNaN()) nan = true
                peak = maxOf(peak, kotlin.math.abs(fx.outL))
                frames++
                i += 2
            }
        }
        while (true) {
            val f = g.grabSamples() ?: break
            if (filter == null) take(f) else {
                filter.push(f)
                while (true) take(filter.pull() ?: break)
            }
        }
        filter?.stop(); filter?.release()
        g.stop(); g.release()
        val secs = frames.toDouble() / rate
        println("speed ${speed}x pitch ${semis.toInt()}: %.2f s, about %.0f Hz, loudest %.2f%s".format(secs, crossings / secs, peak, if (nan) ", NaN!" else ""))
    }
}
