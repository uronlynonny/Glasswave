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
    // ./gradlew -p desktop fxCheck -Pfile=spatial : numbers for spatial sound and the speed presets.
    if (args.firstOrNull() == "spatial") return spatialCheck()
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

/**
 * Spatial sound by numbers, since a check can't listen: how much louder or quieter each preset is
 * (for music that's mostly in the middle, wide music, and one side only), the same summed to mono,
 * how flat the middle of the sound stays, clicks when switching, and that the speed presets leave
 * your own reverb alone.
 */
private fun spatialCheck() {
    Sound.init(android.content.Context())
    Sound.resetAll()
    for (rate in listOf(44_100, 48_000)) {
        println("--- $rate Hz")
        val n = rate * 8
        val rnd = java.util.Random(7)
        // Pinkish noise (the loudness music has across the bands), three of them.
        fun pink(): FloatArray {
            val out = FloatArray(n)
            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
            for (i in 0 until n) {
                val w = rnd.nextGaussian()
                b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913
                out[i] = ((b0 + b1 + b2 + w * 0.1848) * 0.05).toFloat()
            }
            return out
        }
        val c = pink(); val a = pink(); val b = pink()
        val kinds = listOf(
            "centre" to { i: Int -> c[i] to c[i] },
            "music" to { i: Int -> (0.8f * c[i] + 0.45f * a[i]) to (0.8f * c[i] + 0.45f * b[i]) },
            "wide" to { i: Int -> a[i] to b[i] },
            "left only" to { i: Int -> c[i] to 0f },
        )
        for ((kind, src) in kinds) {
            var base = 0.0; var baseMono = 0.0
            for (i in 0 until n) { val (l, r) = src(i); base += l * l + r * r; baseMono += (l + r) * (l + r) }
            val line = StringBuilder("%-10s".format(kind))
            for (s in Spatial.entries) {
                if (s == Spatial.OFF) continue
                Sound.updateSpatial(s)
                val fx = SoundFx(rate); fx.update()
                var e = 0.0; var mono = 0.0; var peak = 0f; var bad = false; var lr = 0.0; var ll = 0.0; var rr = 0.0
                for (i in 0 until n) {
                    val (l, r) = src(i)
                    fx.process(l, r)
                    val ol = fx.outL; val or = fx.outR
                    if (ol.isNaN() || or.isNaN() || ol.isInfinite() || or.isInfinite()) bad = true
                    if (i < rate / 2) continue // let it fade in
                    e += ol * ol + or * or; mono += (ol + or) * (ol + or)
                    lr += ol * or; ll += ol * ol; rr += or * or
                    peak = maxOf(peak, kotlin.math.abs(ol), kotlin.math.abs(or))
                }
                val part = (n - rate / 2).toDouble() / n
                val db = 10 * kotlin.math.log10(e / (base * part))
                val monoDb = 10 * kotlin.math.log10(mono / (baseMono * part) + 1e-12)
                val corr = lr / kotlin.math.sqrt(ll * rr + 1e-12)
                line.append("  ${s.label}: %+.2f dB (mono %+.2f, corr %.2f, peak %.2f)%s".format(db, monoDb, corr, peak, if (bad) " NaN!" else ""))
            }
            println(line)
        }
        // How flat the middle stays: the left ear's response to a click in the centre, in 1/3 octaves.
        for (s in Spatial.entries) {
            if (s == Spatial.OFF) continue
            Sound.updateSpatial(s)
            val fx = SoundFx(rate); fx.update()
            // Let the settings glide in on silence first.
            repeat(rate) { fx.process(0f, 0f) }
            val len = rate
            val h = FloatArray(len); val hs = FloatArray(len)
            for (i in 0 until len) { val x = if (i == 0) 0.25f else 0f; fx.process(x, x); h[i] = fx.outL * 4; hs[i] = (fx.outL + fx.outR) * 2 }
            fun bands(ir: FloatArray): List<Double> {
                val out = ArrayList<Double>()
                var f = 100.0
                while (f < 10_000) {
                    var p = 0.0; var k = 0
                    var g = f / Math.pow(2.0, 1.0 / 6)
                    while (g < f * Math.pow(2.0, 1.0 / 6)) {
                        var re = 0.0; var im = 0.0
                        val w = 2 * Math.PI * g / rate
                        for (t in 0 until 4096) { re += ir[t] * Math.cos(w * t); im -= ir[t] * Math.sin(w * t) }
                        p += re * re + im * im; k++
                        g *= Math.pow(2.0, 1.0 / 48)
                    }
                    out += 10 * kotlin.math.log10(p / k)
                    f *= Math.pow(2.0, 1.0 / 3)
                }
                return out
            }
            val ear = bands(h); val sum = bands(hs)
            println("${s.label}: centre sound, left ear %+.1f..%+.1f dB, mono %+.1f..%+.1f dB (1/3 octaves, 100 Hz-10 kHz)".format(ear.min(), ear.max(), sum.min(), sum.max()))
        }
        // Switching presets (and on and off) on a steady tone: the biggest step from one sample to the next.
        run {
            Sound.updateSpatial(Spatial.OFF)
            val fx = SoundFx(rate); fx.update()
            var maxStep = 0f; var last = 0f; var offAt = -1
            val order = listOf(Spatial.SUBTLE, Spatial.WIDE, Spatial.NATURAL, Spatial.OFF)
            for (i in 0 until rate * 5) {
                if (i % rate == rate / 2) { order.getOrNull(i / rate)?.let { Sound.updateSpatial(it); fx.update() } }
                val x = 0.5f * kotlin.math.sin(2 * Math.PI * 220 * i / rate).toFloat()
                fx.process(x, x * 0.6f)
                if (i > 0) maxStep = maxOf(maxStep, kotlin.math.abs(fx.outL - last))
                last = fx.outL
                if (offAt < 0 && i > rate * 3.5 && !fx.active) offAt = i
            }
            val plain = 0.5f * 2 * Math.PI.toFloat() * 220 / rate
            println("switching: biggest step %.4f (the tone alone: %.4f), off and passed straight through %.2f s after switching off".format(maxStep, plain, (offAt - rate * 3.5) / rate))
        }
    }
    Sound.updateSpatial(Spatial.OFF)
    // Speed presets: your own reverb stays as it was.
    Sound.updateReverb(0.2f); Sound.updateRoom(Room.SMALL)
    Sound.updateSpeedPreset(SpeedPreset.SLOWED_REVERB)
    val during = "reverb ${Sound.reverb}, room ${Sound.room}, tempo ${Sound.tempo()}, pitch ${Sound.playPitch()}"
    Sound.updateSpeedPreset(SpeedPreset.NORMAL)
    println("speed preset: while on $during; back to Normal: reverb ${Sound.reverb}, room ${Sound.room}, tempo ${Sound.tempo()}, pitch ${Sound.playPitch()}")
    Sound.resetAll()
}
