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

    /* ---------------- The recogniser runs in a helper process ----------------
     * Whisper is native code built for processors with AVX2, FMA and F16C (most PCs since about 2013).
     * On an older processor, or without Microsoft's C++ runtime, it doesn't throw an error: it takes
     * the whole program down with it. So it runs in a second copy of Glasswave started just for this
     * ("Glasswave.exe --voice-worker <folder>"): if that one fails, Glasswave stays open and says why. */

    /** Voice search can't run on this PC; the message says why. */
    class Unavailable(message: String) : IOException(message)

    private class Job(val dir: File, val process: Process)
    private var pending: Job? = null

    /** Remembered once the recogniser has failed for a reason that won't go away (this PC's processor). */
    private val unsupportedFile get() = File(dir, "unsupported.txt")

    /** Why voice search can't run here, if it's known that it can't. */
    val unsupportedReason: String? get() = unsupportedFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    private fun workerCommand(jobDir: File): List<String> {
        val self = ProcessHandle.current().info().command().orElse("")
        // Installed: Glasswave.exe itself. Running from the build (checks): Java with the same class path.
        return if (self.isNotEmpty() && !File(self).name.lowercase().startsWith("java")) {
            listOf(self, "--voice-worker", jobDir.path)
        } else {
            val java = File(System.getProperty("java.home"), "bin/" + if (Os.isWindows) "java.exe" else "java").path
            listOfNotNull(java, "-Xmx128m", System.getProperty("gw.voiceModel")?.let { "-Dgw.voiceModel=$it" }, System.getProperty("gw.voiceWorkerFail")?.let { "-Dgw.voiceWorkerFail=$it" }, "-cp", System.getProperty("java.class.path"), "com.glasswave.player.MainKt", "--voice-worker", jobDir.path)
        }
    }

    @Synchronized
    private fun startJob(): Job {
        pending?.let { if (it.process.isAlive) return it }
        val jobDir = File(dir, "job-" + System.nanoTime()).apply { mkdirs() }
        File(jobDir, "parent.txt").writeText(ProcessHandle.current().pid().toString())
        val p = ProcessBuilder(workerCommand(jobDir))
            .directory(jobDir)
            .redirectErrorStream(true)
            .redirectOutput(File(jobDir, "log.txt"))
            .start()
        return Job(jobDir, p).also { pending = it }
    }

    @Synchronized
    private fun takeJob(): Job = startJob().also { pending = null }

    /** Starts the recogniser in the background while you sing, so the answer comes quicker. */
    fun warmUp() {
        if (ready && unsupportedReason == null) runCatching { startJob() }
    }

    /** Stops a recogniser that was started but isn't needed (nothing was heard, or the window closed). */
    @Synchronized
    fun cancel() {
        pending?.let { it.process.destroy(); it.dir.deleteRecursively() }
        pending = null
    }

    /** Turns recorded audio (16 kHz mono) into text, in the helper process. Blank if nothing was understood. */
    fun transcribe(samples: FloatArray): String {
        unsupportedReason?.let { throw Unavailable(it) }
        val job = takeJob()
        try {
            val buf = java.nio.ByteBuffer.allocate(samples.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(samples)
            File(job.dir, "in.part").writeBytes(buf.array())
            File(job.dir, "in.part").renameTo(File(job.dir, "in.raw"))
            if (!job.process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
                job.process.destroyForcibly()
                throw IOException("Voice search took too long")
            }
            val out = File(job.dir, "out.txt")
            if (job.process.exitValue() == 0 && out.isFile) return out.readText()
            throw failure(job)
        } finally {
            job.dir.deleteRecursively()
        }
    }

    /** Why the helper stopped without an answer. */
    private fun failure(job: Job): IOException {
        val said = (job.dir.listFiles().orEmpty().filter { it.name.startsWith("hs_err") || it.name == "err.txt" || it.name == "log.txt" })
            .joinToString("\n") { runCatching { it.readText().take(20_000) }.getOrDefault("") }
        android.util.Log.w("Glasswave", "Voice recogniser stopped (exit ${job.process.exitValue()}): ${said.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(300)}")
        return when {
            // The processor doesn't have the instructions it was built for. That won't change, so remember it.
            "ILLEGAL_INSTRUCTION" in said || job.process.exitValue() == 0xC000001D.toInt() -> {
                val why = "Voice search needs a newer processor than this PC has (one with AVX2). Everything else in Glasswave works as normal."
                runCatching { unsupportedFile.writeText(why) }
                Unavailable(why)
            }
            "VCOMP140" in said.uppercase() || "MSVCP140" in said.uppercase() || "UnsatisfiedLinkError" in said ->
                Unavailable("Voice search needs Microsoft's Visual C++ Redistributable. Install it from Microsoft's website (search \"Visual C++ Redistributable x64\"), then try again.")
            else -> IOException("Voice recogniser stopped (exit ${job.process.exitValue()})")
        }
    }

    /**
     * The helper process: loads the recogniser, waits for the recording ("in.raw" in [jobDir]),
     * writes what it heard to "out.txt" and quits. Gives up if Glasswave closes or nothing comes.
     */
    fun worker(jobDir: File) {
        try {
            val parent = File(jobDir, "parent.txt").readText().trim().toLongOrNull()?.let { ProcessHandle.of(it).orElse(null) }
            load()
            // Checks only: stop the way a processor without AVX2 does.
            if (System.getProperty("gw.voiceWorkerFail") == "cpu") Runtime.getRuntime().halt(0xC000001D.toInt())
            val input = File(jobDir, "in.raw")
            val giveUp = System.currentTimeMillis() + 120_000
            while (!input.isFile) {
                if (System.currentTimeMillis() > giveUp || (parent != null && !parent.isAlive)) kotlin.system.exitProcess(2)
                Thread.sleep(40)
            }
            val bytes = input.readBytes()
            val samples = FloatArray(bytes.size / 4)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            val text = transcribeHere(samples)
            File(jobDir, "out.part").writeText(text)
            File(jobDir, "out.part").renameTo(File(jobDir, "out.txt"))
            kotlin.system.exitProcess(0)
        } catch (t: Throwable) {
            runCatching { File(jobDir, "err.txt").writeText(t.toString() + "\n" + t.stackTraceToString().take(4000)) }
            kotlin.system.exitProcess(1)
        }
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

    /** Turns recorded audio (16 kHz mono) into text, in this process (the helper). Blank if nothing was understood. */
    @Synchronized
    internal fun transcribeHere(samples: FloatArray): String {
        val (j, c) = load()
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
