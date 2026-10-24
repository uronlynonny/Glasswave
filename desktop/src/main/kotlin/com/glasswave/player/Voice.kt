// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt
import okhttp3.Request

/**
 * Voice search on the computer. Records you singing or saying some lyrics (or a song's name), then
 * turns it into text with Whisper, an open speech recogniser that runs entirely on this PC: nothing
 * you say leaves the computer. The recogniser (about 60 MB) downloads once, the first time.
 */
object Voice {
    private val MODEL = System.getProperty("gw.voiceModel") ?: "ggml-base-q5_1.bin"
    private val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL"
    private const val RATE = 16_000f

    private val dir by lazy { File(android.content.AppDirs.cache, "voice").apply { mkdirs() } }
    val modelFile get() = File(dir, MODEL)
    val ready get() = modelFile.isFile && modelFile.length() > 1_000_000

    private var jni: WhisperJNI? = null
    private var ctx: WhisperContext? = null

    /** Downloads the recogniser once. [progress] gets 0..1. */
    fun download(progress: (Float) -> Unit) {
        if (ready) return
        val part = File(dir, "$MODEL.part")
        val req = Request.Builder().url(MODEL_URL).header("User-Agent", "Glasswave").build()
        YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        modelFile.delete()
        if (!part.renameTo(modelFile)) throw IOException("Couldn't save the recogniser")
    }

    @Synchronized
    private fun load(): Pair<WhisperJNI, WhisperContext> {
        val j = jni ?: run {
            WhisperJNI.loadLibrary()
            WhisperJNI.setLibraryLogger(null)
            WhisperJNI().also { jni = it }
        }
        val c = ctx ?: j.init(modelFile.toPath()).also { ctx = it }
        return j to c
    }

    private val idle = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-idle").apply { isDaemon = true } }
    private var unload: java.util.concurrent.ScheduledFuture<*>? = null

    /** Frees the recogniser's memory a few minutes after the last voice search. */
    @Synchronized
    private fun unloadLater() {
        unload?.cancel(false)
        unload = idle.schedule({
            synchronized(this) {
                ctx?.let { c -> runCatching { jni?.free(c) } }
                ctx = null
            }
        }, 3, java.util.concurrent.TimeUnit.MINUTES)
    }

    /** Loads the recogniser in the background, so the first search is quicker. */
    fun warmUp() {
        if (ready) Thread { runCatching { load() } }.apply { isDaemon = true }.start()
    }

    class Recording(val samples: FloatArray, val heardSomething: Boolean)

    /**
     * Records from the default microphone until you've been quiet for a moment after singing, until
     * [stop] says so, or for at most 12 seconds. [level] gets the loudness (0..1) as you go.
     */
    fun record(level: (Float) -> Unit, stop: () -> Boolean): Recording {
        // Automated checks only: a sound file stands in for the microphone.
        System.getProperty("gw.voiceTestWav")?.let { f ->
            val target = AudioFormat(RATE, 16, 1, true, false)
            val bytes = AudioSystem.getAudioInputStream(target, AudioSystem.getAudioInputStream(File(f))).readAllBytes()
            level(0.6f)
            Thread.sleep(1500)
            return Recording(FloatArray(bytes.size / 2) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768f }, true)
        }
        val format = AudioFormat(RATE, 16, 1, true, false)
        val line = AudioSystem.getTargetDataLine(format) as TargetDataLine
        line.open(format, 3200)
        line.start()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1600) // 50 ms
        var spoke = false
        var quietMs = 0
        var elapsed = 0
        try {
            while (!stop() && elapsed < 12_000) {
                val n = line.read(buf, 0, buf.size)
                if (n <= 0) continue
                out.write(buf, 0, n)
                elapsed += 50
                var sum = 0.0
                for (i in 0 until n / 2) {
                    val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort() / 32768.0
                    sum += s * s
                }
                val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
                level((rms * 9).toFloat().coerceIn(0f, 1f))
                if (rms > 0.02) { spoke = true; quietMs = 0 } else quietMs += 50
                // Singing has pauses: wait a couple of seconds of quiet before deciding you've finished.
                if (spoke && quietMs >= 2200) break
                if (!spoke && elapsed >= 7000) break
            }
        } finally {
            line.stop()
            line.close()
        }
        val bytes = out.toByteArray()
        val samples = FloatArray(bytes.size / 2) { i ->
            ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768f
        }
        return Recording(samples, spoke)
    }

    /** Turns recorded audio (16 kHz mono) into text. Blank if nothing was understood. */
    @Synchronized
    fun transcribe(samples: FloatArray): String {
        val (j, c) = load()
        unloadLater()
        // Whisper wants at least a second of audio.
        val audio = if (samples.size < 16_000) samples.copyOf(16_000) else samples
        val p = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
            language = "auto"
            translate = false
            noTimestamps = true
            singleSegment = false
            printProgress = false
            printRealtime = false
            printSpecial = false
            printTimestamps = false
            suppressBlank = true
            suppressNonSpeechTokens = true
            nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            // Only look at as much audio as was recorded (Whisper otherwise always works through 30 s),
            // and take its first guess: several times faster for a short search.
            audioCtx = (audio.size / 16_000f * 50f).toInt().plus(64).coerceIn(256, 1500)
            greedyBestOf = 1
            temperatureInc = 0f
        }
        val r = j.full(c, p, audio, audio.size)
        if (r != 0) throw IOException("Couldn't understand the recording")
        val text = (0 until j.fullNSegments(c)).joinToString(" ") { j.fullGetSegmentText(c, it) }
        return clean(text)
    }

    /** Drops music notes, bracketed sounds ("[Music]", "(singing)") and stray punctuation. */
    fun clean(raw: String): String = raw
        .replace(Regex("""\[[^\]]*]|\([^)]*\)|[♪♫*]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('.', ',', '-', ' ')
}
