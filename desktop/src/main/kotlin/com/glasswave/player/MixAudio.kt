// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber

/** Reads parts of songs for Automix to listen to, with FFmpeg (the same decoder the player uses). */
object MixAudio : MixDecoder {
    const val RATE = 22_050

    /** Where a song's audio is: a download, or its address on YouTube. Blocking. */
    fun source(url: String): AudioSource {
        Downloads.fileFor(url)?.let { return AudioSource.LocalFile(it) }
        SongCache.fileFor(url)?.let { return AudioSource.LocalFile(it) }
        val uri = android.net.Uri.parse(url)
        val page = if (uri.scheme == "gwmatch") {
            YtRepo.match(uri.getQueryParameter("q") ?: throw java.io.IOException("Empty song"), uri.getQueryParameter("d")?.toLongOrNull() ?: 0)
        } else url
        return AudioSource.Remote(YtRepo.streamUrl(page))
    }

    override fun decode(url: String, part: MixPart): Pcm? = read(source(url), part, RATE, 1)

    /**
     * Decodes one end of a song at [rate] with [channels] (interleaved). The head is the first
     * minute (at most 40% of the song), the tail the last minute and a half (at most 60%).
     */
    fun read(src: AudioSource, part: MixPart, rate: Int, channels: Int): Pcm? {
        val g = grabber(src, rate, channels)
        try {
            g.start()
            val song = (g.lengthInTime / 1000).coerceAtLeast(0)
            if (song < 30_000) return null
            val (a, z) = when (part) {
                MixPart.HEAD -> 0L to minOf(MixLibrary.HEAD_MS, song * 2 / 5)
                MixPart.TAIL -> maxOf(song - MixLibrary.TAIL_MS, song * 2 / 5) to song
            }
            return collect(g, a, z, rate, channels, song)
        } finally {
            runCatching { g.stop(); g.release() }
        }
    }

    /** Decodes [fromMs]..[toMs] of a song (for the checks, which render whole transitions). */
    fun readRange(src: AudioSource, fromMs: Long, toMs: Long, rate: Int, channels: Int): Pcm? {
        val g = grabber(src, rate, channels)
        try {
            g.start()
            return collect(g, fromMs, toMs, rate, channels, (g.lengthInTime / 1000).coerceAtLeast(0))
        } finally {
            runCatching { g.stop(); g.release() }
        }
    }

    private fun grabber(src: AudioSource, rate: Int, channels: Int): FFmpegFrameGrabber {
        val g = when (src) {
            is AudioSource.LocalFile -> FFmpegFrameGrabber(src.file)
            is AudioSource.Remote -> FFmpegFrameGrabber(HttpSeekStream(src.url), Int.MAX_VALUE - 8)
        }
        g.sampleFormat = avutil.AV_SAMPLE_FMT_FLT
        g.sampleRate = rate
        g.audioChannels = channels
        return g
    }

    private fun collect(g: FFmpegFrameGrabber, fromMs: Long, toMs: Long, rate: Int, channels: Int, song: Long): Pcm? {
        if (fromMs > 0) g.setTimestamp(fromMs * 1000)
        val want = ((toMs - fromMs) * rate / 1000 * channels).toInt().coerceAtLeast(0)
        val out = FloatArray(want)
        var n = 0
        var startMs = -1.0
        while (n < want) {
            val f = g.grabSamples() ?: break
            val b = f.samples?.firstOrNull() ?: continue
            if (startMs < 0) startMs = f.timestamp / 1000.0
            val fb = b as? java.nio.FloatBuffer
            val sb = b as? java.nio.ShortBuffer
            val len = fb?.remaining() ?: sb?.remaining() ?: continue
            val p = fb?.position() ?: sb!!.position()
            for (i in 0 until len) {
                if (n >= want) break
                out[n++] = fb?.get(p + i) ?: (sb!!.get(p + i) / 32768f)
            }
        }
        if (n < rate * channels * 5 || startMs < 0) return null
        return Pcm(if (n == want) out else out.copyOf(n), rate, startMs, song)
    }
}
