// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import javax.swing.SwingUtilities
import okhttp3.Request
import okhttp3.Response
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber

/** Where a song's audio comes from: a downloaded file, or an address on YouTube's servers. */
sealed interface AudioSource {
    data class LocalFile(val file: File) : AudioSource
    data class Remote(val url: String) : AudioSource
}

/**
 * Reads a song from the internet in a way FFmpeg can jump around in: every jump opens a fresh
 * request starting at that byte. Nothing is downloaded before it's needed.
 */
internal class HttpSeekStream(private val url: String) : InputStream() {
    private var pos = 0L
    private var body: InputStream? = null
    private var response: Response? = null
    private var bodyPos = -1L
    var length = -1L
        private set

    init { open(0) }

    /**
     * YouTube slows a song to playing speed when it's fetched in one long request (and sometimes
     * cuts it off partway), so its audio servers are read in 2 MB pieces instead: a whole song
     * arrives in seconds. Other servers get one request as before.
     */
    private val pieces = runCatching { java.net.URI(url).host?.endsWith("googlevideo.com") == true }.getOrDefault(false)
    /** Where the open piece ends (exclusive). */
    private var pieceEnd = Long.MAX_VALUE

    private fun open(at: Long) {
        closeBody()
        if (length < 0) {
            // YouTube puts the song's size in its address ("clen").
            length = Regex("""[?&]clen=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1
        }
        val end = if (pieces) (if (length > 0) minOf(at + PIECE, length) else at + PIECE) else Long.MAX_VALUE
        val req = Request.Builder().url(url)
            .header("User-Agent", YtDownloader.USER_AGENT)
            .header("Range", if (end == Long.MAX_VALUE) "bytes=$at-" else "bytes=$at-${end - 1}")
            .build()
        pieceEnd = end
        val r = YtRepo.http.newCall(req).execute()
        if (!r.isSuccessful) {
            r.close()
            throw HttpStatus(r.code)
        }
        if (length < 0) {
            length = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it >= 0 && !pieces }?.plus(if (r.code == 206) at else 0)
                ?: -1
        }
        response = r
        val stream = r.body!!.byteStream()
        // The server ignored the range: skip ahead by hand.
        if (r.code == 200 && at > 0) {
            var left = at
            while (left > 0) {
                val n = stream.skip(left)
                if (n <= 0) break
                left -= n
            }
        }
        body = stream
        bodyPos = at
    }

    private fun closeBody() {
        runCatching { response?.close() }
        response = null
        body = null
        bodyPos = -1
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (length in 0..pos) return -1
        if (body == null || bodyPos != pos || pos >= pieceEnd) open(pos)
        var n = try {
            body!!.read(b, off, len)
        } catch (e: IOException) {
            // A dropped connection: pick up where it stopped, once.
            open(pos)
            body!!.read(b, off, len)
        }
        // A piece ended (or came up short): carry on with the next one.
        if (n < 0 && pieces && (length < 0 || pos < length)) {
            open(pos)
            n = body!!.read(b, off, len)
        }
        if (n > 0) {
            pos += n
            bodyPos += n
        }
        return n
    }

    // FFmpeg jumps by going back to the start and skipping forward; neither reads anything.
    override fun markSupported() = true
    override fun mark(readlimit: Int) {}
    override fun reset() { pos = 0 }
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        // FFmpeg skips by Long.MAX_VALUE to find the end: cap the step first so nothing overflows.
        val moved = if (length >= 0) minOf(n, (length - pos).coerceAtLeast(0)) else 0
        pos += moved
        return moved
    }
    override fun available() = 0
    override fun close() = closeBody()
}

private const val PIECE = 2L * 1024 * 1024

class HttpStatus(val code: Int) : IOException("The server answered $code")

/**
 * One audio player: decodes a song with FFmpeg and plays it through the sound card. Two of these
 * let one song fade into the next. All the listener calls arrive on the window's thread.
 */
class AudioDeck(private val name: String) {
    interface Listener {
        fun onReady(deck: AudioDeck)
        fun onEnded(deck: AudioDeck)
        fun onError(deck: AudioDeck, e: Exception)
    }

    var listener: Listener? = null

    /** Software volume (0..2; above 1 lifts quiet songs). Changes glide over one buffer, so there are no clicks. */
    @Volatile var volume = 1f
    @Volatile var playWhenReady = false
        private set
    @Volatile var ready = false
        private set
    @Volatile var ended = false
        private set
    @Volatile var durationMs = 0L
        private set

    /** What this deck was last asked to play, so the queue can tell which song it holds. */
    @Volatile var tag: Any? = null

    @Volatile private var generation = 0
    @Volatile private var seekUs = -1L
    @Volatile private var playedUs = 0L
    @Volatile private var line: SourceDataLine? = null
    /** The speed of the sound waiting at the speaker. */
    @Volatile private var playSpeed = 1f
    private val lock = Object()

    /** Where the song is right now, counting only what has actually come out of the speakers. */
    val positionMs: Long
        get() {
            val s = seekUs
            if (s >= 0) return s / 1000
            val l = line ?: return playedUs / 1000
            val queued = (l.bufferSize - l.available()).coerceAtLeast(0)
            // At a different speed, each second of sound waiting at the speaker covers more (or less) of the song.
            return ((playedUs / 1000) - (queued / BYTES_PER_MS * playSpeed).toLong()).coerceAtLeast(0)
        }

    fun load(source: AudioSource, startMs: Long, play: Boolean, tag: Any?) {
        stop()
        this.tag = tag
        playWhenReady = play
        ready = false
        ended = false
        durationMs = 0
        playedUs = startMs * 1000
        seekUs = -1
        val gen = generation
        Thread({ run(gen, source, startMs) }, "deck-$name").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun play() {
        playWhenReady = true
        // If the decoder is waiting on a full, stopped speaker buffer, get the speaker going again.
        if (seekUs < 0) runCatching { line?.start() }
        synchronized(lock) { lock.notifyAll() }
    }

    fun pause() {
        playWhenReady = false
        runCatching { line?.stop() }
    }

    fun seekTo(ms: Long) {
        seekUs = ms.coerceAtLeast(0) * 1000
        ended = false
        runCatching { line?.flush() }
        synchronized(lock) { lock.notifyAll() }
    }

    /** Stops and forgets the song. */
    fun stop() {
        generation++
        playWhenReady = false
        ready = false
        tag = null
        // Closing the speaker line also frees a decoder waiting to write to it.
        line?.let { l -> runCatching { l.stop(); l.flush(); l.close() } }
        line = null
        synchronized(lock) { lock.notifyAll() }
    }

    private fun post(gen: Int, f: () -> Unit) = SwingUtilities.invokeLater { if (gen == generation) f() }

    private fun run(gen: Int, source: AudioSource, startMs: Long) {
        var grabber: FFmpegFrameGrabber? = null
        var out: SourceDataLine? = null
        // Speed and pitch go through FFmpeg's own tempo filter (only while they're not normal).
        var chain: FFmpegFrameFilter? = null
        var chainFor = 1f to 1f
        fun closeChain() {
            runCatching { chain?.stop(); chain?.release() }
            chain = null
        }
        try {
            grabber = when (source) {
                is AudioSource.LocalFile -> FFmpegFrameGrabber(source.file)
                is AudioSource.Remote -> FFmpegFrameGrabber(HttpSeekStream(source.url), Int.MAX_VALUE - 8)
            }
            // Decode at full precision; everything is worked on in floating point and only turned
            // into 16-bit (what Windows' sound output takes from Java) at the very end.
            grabber.sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            grabber.sampleRate = RATE
            grabber.audioChannels = 2
            grabber.start()
            if (gen != generation) return
            if (startMs > 0) grabber.setTimestamp(startMs * 1000)
            durationMs = (grabber.lengthInTime / 1000).coerceAtLeast(0)

            val l = AudioSystem.getSourceDataLine(FORMAT)
            l.open(FORMAT, BUFFER_BYTES)
            out = l
            line = l
            ready = true
            post(gen) { listener?.onReady(this) }

            var lastVol = volume
            var bytes = ByteArray(0)
            // Your volume is applied at the speaker when it can be, so the slider still reacts at
            // once even with a deeper queue of sound ahead. The deck's own level (crossfades,
            // Automix) stays in the samples, where both decks line up.
            val master = runCatching { l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl }.getOrNull()
            var lastApp = -1f
            // A smooth limiter: when volume leveling lifts a quiet song, loud peaks are eased
            // under the ceiling instead of being clipped (which sounds harsh and crunchy).
            var env = 1f
            val release = (1.0 - Math.exp(-1.0 / (0.15 * RATE))).toFloat()
            val rng = java.util.SplittableRandom()
            // Settings > Sound & effects.
            val fx = SoundFx(RATE)

            /** Sends one piece of decoded sound to the speaker. [mediaUs]: where it starts in the song, or -1 to carry on. */
            fun emit(frame: org.bytedeco.javacv.Frame, mediaUs: Long, stretch: Float): Boolean {
                val bufs = frame.samples ?: return true
                val raw = bufs.firstOrNull() ?: return true
                val fbuf = raw as? java.nio.FloatBuffer
                val sbuf = raw as? java.nio.ShortBuffer
                if (fbuf == null && sbuf == null) return true
                // Some filters hand back left and right separately; most give them interleaved.
                val planar = bufs.size >= 2
                val right = if (planar) bufs[1] else null
                val count = fbuf?.remaining() ?: sbuf!!.remaining()
                val n = if (planar) count * 2 else count
                if (n == 0) return true
                if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                fun sample(i: Int): Float {
                    if (planar) {
                        val k = i / 2
                        val b = if (i % 2 == 0) raw else right!!
                        return when (b) {
                            is java.nio.FloatBuffer -> b.get(b.position() + k)
                            is java.nio.ShortBuffer -> b.get(b.position() + k) / 32768f
                            else -> 0f
                        }
                    }
                    return fbuf?.get(fbuf.position() + i) ?: (sbuf!!.get(sbuf.position() + i) / 32768f)
                }
                // Volume, gliding from the last value to the new one across this piece of audio.
                val app = AppVolume.gain
                if (master != null && app != lastApp) {
                    lastApp = app
                    runCatching {
                        master.value = if (app <= 0.0001f) master.minimum
                        else (20f * kotlin.math.log10(app)).coerceIn(master.minimum, master.maximum)
                    }
                }
                val target = volume * (if (master != null) 1f else app)
                fx.update()
                val watch = Spectrum.wanted && current === this
                var i = 0
                while (i + 1 < n) {
                    fx.process(sample(i), sample(i + 1))
                    if (watch) Spectrum.feed((fx.outL + fx.outR) * 0.5f, RATE)
                    // One stereo frame at a time, so both sides are limited together.
                    val g = lastVol + (target - lastVol) * i / n
                    var a = fx.outL * g
                    var b = fx.outR * g
                    val peak = maxOf(kotlin.math.abs(a), kotlin.math.abs(b)) * env
                    if (peak > CEILING) env *= CEILING / peak else env += (1f - env) * release
                    a *= env
                    b *= env
                    // A whisper of dither (TPDF) so quiet parts and fades stay smooth at 16-bit.
                    val va = (a * 32767f + (rng.nextDouble() - rng.nextDouble()).toFloat()).toInt().coerceIn(-32768, 32767)
                    val vb = (b * 32767f + (rng.nextDouble() - rng.nextDouble()).toFloat()).toInt().coerceIn(-32768, 32767)
                    bytes[i * 2] = va.toByte()
                    bytes[i * 2 + 1] = (va shr 8).toByte()
                    bytes[i * 2 + 2] = vb.toByte()
                    bytes[i * 2 + 3] = (vb shr 8).toByte()
                    i += 2
                }
                lastVol = target
                if (seekUs >= 0) return false // a jump came in while decoding: drop this piece
                // Automated checks only: pretend the connection dropped once, 5 s in.
                if (testDrop && playedUs > 5_000_000) { testDrop = false; throw IOException("Test: connection dropped") }
                l.write(bytes, 0, n * 2)
                val start = if (mediaUs > 0) mediaUs else playedUs
                playedUs = start + ((n / 2) * 1_000_000L / RATE * stretch).toLong()
                playSpeed = stretch
                return true
            }

            while (gen == generation) {
                if (!playWhenReady) {
                    if (l.isRunning) l.stop()
                    synchronized(lock) { if (!playWhenReady && gen == generation) lock.wait(100) }
                    continue
                }
                val s = seekUs
                if (s >= 0) {
                    l.flush()
                    grabber.setTimestamp(s)
                    playedUs = s
                    seekUs = -1
                    fx.clear()
                    closeChain()
                }
                // Speed or pitch changed: start the tempo filter afresh.
                val want = speed to pitch
                if (want != chainFor || (chain == null && want != (1f to 1f))) {
                    closeChain()
                    chainFor = want
                    if (want != (1f to 1f)) chain = tempoFilter(want.first, want.second)
                }
                if (!l.isRunning) l.start()
                val frame = grabber.grabSamples()
                if (frame == null) {
                    // The end of the song: let the last bit play out, then say so.
                    if (seekUs >= 0) continue
                    l.drain()
                    if (gen == generation && seekUs < 0) {
                        ended = true
                        playWhenReady = false
                        post(gen) { listener?.onEnded(this) }
                        // Wait in case it's asked to seek back into the song.
                        while (gen == generation && seekUs < 0) synchronized(lock) { lock.wait(200) }
                        if (gen == generation) { ended = false; continue }
                    }
                    break
                }
                val c = chain
                if (c == null) {
                    emit(frame, frame.timestamp, 1f)
                } else {
                    c.push(frame)
                    // The filter may hand back none, one or several pieces for each one going in.
                    while (gen == generation && seekUs < 0) {
                        val f = c.pull() ?: break
                        if (!emit(f, -1, chainFor.first)) break
                    }
                }
            }
        } catch (e: Exception) {
            if (gen == generation) {
                ready = false
                post(gen) { listener?.onError(this, e) }
            }
        } finally {
            closeChain()
            if (line === out) line = null
            out?.let { runCatching { it.stop(); it.close() } }
            runCatching { grabber?.stop(); grabber?.release() }
        }
    }

    companion object {
        private const val RATE = 48_000

        /** Playing speed (1 = normal) and pitch (1 = normal, 2 = an octave up), for every deck. */
        @Volatile var speed = 1f
        @Volatile var pitch = 1f

        /** The deck playing the current song: the visualizer listens to this one. */
        @Volatile var current: AudioDeck? = null

        /**
         * FFmpeg's filters for a different speed and pitch: the pitch is moved by playing faster or
         * slower, and the tempo is then put back (to [speed]) without touching the pitch.
         */
        internal fun tempoFilter(speed: Float, pitch: Float): FFmpegFrameFilter? = runCatching {
            val parts = mutableListOf<String>()
            if (pitch != 1f) {
                parts += "asetrate=${(RATE * pitch).toInt()}"
                parts += "aresample=$RATE"
            }
            // atempo takes 0.5..2 at a time, so bigger changes are done in steps.
            var t = speed / pitch
            while (t < 0.5f) { parts += "atempo=0.5"; t /= 0.5f }
            while (t > 2f) { parts += "atempo=2.0"; t /= 2f }
            if (kotlin.math.abs(t - 1f) > 0.001f) parts += "atempo=${"%.5f".format(java.util.Locale.ROOT, t)}"
            FFmpegFrameFilter(parts.joinToString(","), 2).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_FLT
                sampleRate = RATE
                start()
            }
        }.onFailure { android.util.Log.w("Glasswave", "Couldn't set up speed and pitch", it) }.getOrNull()
        @Volatile private var testDrop = System.getProperty("gw.testDrop") != null
        /** Where the limiter holds peaks: just under full scale (about -0.3 dB). */
        private const val CEILING = 0.966f
        private const val BYTES_PER_MS = RATE * 4 / 1000
        /**
         * About 0.6 s of sound queued up ahead: enough to ride out a busy moment (like the window
         * coming back from the taskbar) without a skip, while pausing and seeking stay instant.
         */
        private const val BUFFER_BYTES = RATE * 4 * 3 / 5
        private val FORMAT = AudioFormat(RATE.toFloat(), 16, 2, true, false)

        init {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }

        /** Loads the audio libraries ahead of time (the first load is slow). */
        fun warmUp() {
            runCatching { AudioSystem.getSourceDataLine(FORMAT) }
        }
    }
}
