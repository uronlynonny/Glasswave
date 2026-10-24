// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** For checks only: downloads voice search's recogniser if needed and transcribes a WAV file. */
fun main(args: Array<String>) {
    if (!Voice.ready) {
        var last = -1
        Voice.download { p -> val n = (p * 10).toInt(); if (n != last) { last = n; println("GW download ${n * 10}%") } }
    }
    val target = AudioFormat(16_000f, 16, 1, true, false)
    val stream = AudioSystem.getAudioInputStream(target, AudioSystem.getAudioInputStream(File(args[0])))
    val bytes = stream.readAllBytes()
    val samples = FloatArray(bytes.size / 2) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768f }
    var t = System.currentTimeMillis()
    Voice.transcribe(FloatArray(16_000))
    println("GW load+warm ${System.currentTimeMillis() - t} ms")
    t = System.currentTimeMillis()
    val text = Voice.transcribe(samples)
    println("GW heard '$text' in ${System.currentTimeMillis() - t} ms (${samples.size / 16} ms of audio)")
}
