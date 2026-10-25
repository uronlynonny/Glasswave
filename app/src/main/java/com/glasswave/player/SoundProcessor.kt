// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs inside the player, on the song's sound before it reaches the speaker: the effects from
 * Settings > Sound & effects (see [SoundFx]). When the visualizer is on screen it also measures
 * how loud each part of the sound is (see [Spectrum]).
 */
@OptIn(UnstableApi::class)
class SoundProcessor(private val measure: Boolean) : BaseAudioProcessor() {
    private var rate = 44100
    private var channels = 2
    private var fx: SoundFx? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) return AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        fx = null
        return inputAudioFormat
    }

    override fun onFlush() { fx?.clear() }

    override fun onReset() { fx = null }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val effects = fx ?: SoundFx(rate).also { fx = it }
        effects.update()
        val size = inputBuffer.remaining()
        if (size == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size)
        val watch = measure && Spectrum.wanted
        if (!effects.active && !watch) {
            out.put(input)
            out.flip()
            return
        }
        val pos = input.position()
        val frames = size / (2 * channels)
        for (f in 0 until frames) {
            val at = pos + f * 2 * channels
            val l = input.getShort(at) / 32768f
            val r = if (channels == 2) input.getShort(at + 2) / 32768f else l
            effects.process(l, r)
            val ol = effects.outL
            val orr = effects.outR
            if (watch) Spectrum.feed((ol + orr) * 0.5f, rate)
            out.putShort((ol * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            if (channels == 2) out.putShort((orr * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
