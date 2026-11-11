// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * For checks only: downloads voice search's recogniser if needed and transcribes a WAV file, the way
 * the app does (in the helper process). With -PvoiceFail=cpu the helper stops the way it would on a
 * processor without AVX2, to check Glasswave says so instead of closing.
 */
fun main(args: Array<String>) {
    if (!Voice.ready) {
        var last = -1
        Voice.download { p -> val n = (p * 10).toInt(); if (n != last) { last = n; println("GW download ${n * 10}%") } }
    }
    val target = AudioFormat(16_000f, 16, 1, true, false)
    val stream = AudioSystem.getAudioInputStream(target, AudioSystem.getAudioInputStream(File(args[0])))
    val bytes = stream.readAllBytes()
    val samples = FloatArray(bytes.size / 2) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768f }
    // Started while "recording", as the app does, then given the recording.
    Voice.warmUp()
    Thread.sleep(2500)
    val t = System.currentTimeMillis()
    try {
        val text = Voice.transcribe(samples)
        println("GW heard '$text' in ${System.currentTimeMillis() - t} ms after recording (${samples.size / 16} ms of audio)")
    } catch (e: Exception) {
        println("GW failed: ${e.javaClass.simpleName}: ${e.message}")
        println("GW next time: ${Voice.unsupportedReason ?: "will try again"}")
    }
    // -Pcompare: the same recording in this process (the old way), then the helper again, to compare times.
    if (System.getProperty("gw.voiceCompare") != null) {
        var t1 = System.currentTimeMillis()
        Voice.transcribeHere(FloatArray(16_000))
        println("GW in-app load ${System.currentTimeMillis() - t1} ms")
        t1 = System.currentTimeMillis()
        println("GW in-app heard '${Voice.transcribeHere(samples)}' in ${System.currentTimeMillis() - t1} ms")
        Voice.warmUp()
        Thread.sleep(2500)
        t1 = System.currentTimeMillis()
        println("GW helper heard '${Voice.transcribe(samples)}' in ${System.currentTimeMillis() - t1} ms after recording")
    }
    System.exit(0)
}
