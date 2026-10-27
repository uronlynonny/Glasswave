// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The mix EQ ([MixEq]) as a step in the player's sound path: bass level and the leaving song's high-pass. */
@OptIn(UnstableApi::class)
class MixFilter : BaseAudioProcessor() {
    @Volatile var bass = 1f
    @Volatile var sweepHz = 0f

    private var eq: MixEq? = null
    private var buf = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        eq = MixEq(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val e = eq
        val format = inputAudioFormat
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (e == null) { out.put(input); out.flip(); return }
        val floatPcm = format.encoding == C.ENCODING_PCM_FLOAT
        val samples = size / (if (floatPcm) 4 else 2)
        if (buf.size < samples) buf = FloatArray(samples)
        for (i in 0 until samples) buf[i] = if (floatPcm) input.float else input.short / 32768f
        e.bass = bass
        e.sweepHz = sweepHz
        e.process(buf, 0, samples / format.channelCount)
        for (i in 0 until samples) {
            if (floatPcm) out.putFloat(buf[i]) else out.putShort((buf[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onFlush() {
        eq?.reset()
    }

    override fun onReset() {
        eq = null
    }
}
