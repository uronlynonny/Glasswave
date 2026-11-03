// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The audio side, written in Swift (iosApp/Glasswave/AudioEngine.swift): Apple's player, the lock
 * screen controls, and YouTubeKit, which finds each song's audio. Kotlin tells it what to play.
 */
interface NativeAudio {
    fun load(videoId: String, title: String, artist: String, artUrl: String?, seconds: Double)
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    fun position(): Double
    fun duration(): Double
    fun setListener(listener: AudioListener)
}

/** What the Swift side reports back (always on the main thread). */
interface AudioListener {
    fun onReady()
    fun onEnded()
    fun onFailed(message: String)
    fun onRemoteNext()
    fun onRemotePrevious()
    fun onPlayingChanged(playing: Boolean)
}

/** Called once from Swift: the whole app's screen. */
fun MainViewController(audio: NativeAudio): UIViewController {
    val state = AppState(Player(audio))
    Script.start(state)
    return ComposeUIViewController { App(state) }
}
