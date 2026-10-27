// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.nio.ByteOrder

/**
 * Reads parts of songs for Automix to listen to. It goes through the same cache as the player
 * (and downloads), so what the player has already fetched costs nothing, and what Automix fetches
 * is there for the player afterwards.
 */
@OptIn(UnstableApi::class)
class MixAudio(private val sources: DataSource.Factory) : MixDecoder {

    override fun decode(url: String, part: MixPart): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val src = CachedMedia(sources, Uri.parse(url))
        try {
            ex.setDataSource(src)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val songMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            if (songMs < 30_000) return null
            val (a, z) = when (part) {
                MixPart.HEAD -> 0L to minOf(MixLibrary.HEAD_MS, songMs * 2 / 5)
                MixPart.TAIL -> maxOf(songMs - MixLibrary.TAIL_MS, songMs * 2 / 5) to songMs
            }
            // A little before the start, so the decoder has settled by then.
            if (a > 0) ex.seekTo(maxOf(0L, (a - 200) * 1000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            // Halve the rate (averaging pairs), which is plenty for finding beats and keys.
            var out = FloatArray(((z - a) * rate / 2000 + rate).toInt())
            var n = 0
            var pair = 0f
            var half = false
            var startMs = -1.0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            while (!done) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || ex.sampleTime > z * 1000 + 500_000) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        val need = ((z - a) * rate / 2000 + rate).toInt()
                        if (need > out.size) out = out.copyOf(need)
                    }
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytesPer = if (float) 4 else 2
                        val frames = info.size / (bytesPer * channels)
                        val t0 = info.presentationTimeUs
                        for (f in 0 until frames) {
                            var s = 0f
                            for (ch in 0 until channels) s += if (float) buf.float else buf.short / 32768f
                            s /= channels
                            val tUs = t0 + f * 1_000_000L / rate
                            if (tUs < a * 1000) continue
                            if (tUs >= z * 1000) { done = true; break }
                            if (!half) { pair = s; half = true; if (startMs < 0) startMs = tUs / 1000.0 }
                            else {
                                if (n < out.size) out[n++] = (pair + s) / 2
                                half = false
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
            if (n < rate / 2 * 5 || startMs < 0) return null
            return Pcm(out.copyOf(n), rate / 2, startMs, songMs)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
            runCatching { src.close() }
        }
    }
}

/** Lets Android's extractor read a song through the player's cache, jumping around as it needs. */
@OptIn(UnstableApi::class)
private class CachedMedia(private val factory: DataSource.Factory, private val uri: Uri) : MediaDataSource() {
    private var ds: DataSource? = null
    private var pos = -1L
    private var length = -1L
    private val skip = ByteArray(16 * 1024)

    private fun openAt(at: Long) {
        runCatching { ds?.close() }
        val d = factory.createDataSource()
        val got = d.open(DataSpec.Builder().setUri(uri).setPosition(at).build())
        if (length < 0 && got != C.LENGTH_UNSET.toLong()) length = at + got
        ds = d
        pos = at
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length in 0..position) return -1
        // A short jump forward is cheaper to read through than to start a new request for.
        if (ds != null && position > pos && position - pos <= 256 * 1024) {
            while (pos < position) {
                val r = ds!!.read(skip, 0, minOf(skip.size.toLong(), position - pos).toInt())
                if (r == C.RESULT_END_OF_INPUT) { length = pos; return -1 }
                pos += r
            }
        }
        if (ds == null || pos != position) openAt(position)
        var r = ds!!.read(buffer, offset, size)
        if (r == C.RESULT_END_OF_INPUT) { length = pos; return -1 }
        pos += r
        // Fill the request as far as the source gives it, so the extractor sees whole reads.
        while (r < size) {
            val more = ds!!.read(buffer, offset + r, size - r)
            if (more == C.RESULT_END_OF_INPUT) break
            r += more
            pos += more
        }
        return r
    }

    @Synchronized
    override fun getSize(): Long {
        if (length < 0 && ds == null) runCatching { openAt(0) }
        return length
    }

    @Synchronized
    override fun close() {
        runCatching { ds?.close() }
        ds = null
    }
}
