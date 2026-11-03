// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo

/**
 * For test runs on GitHub's simulated iPhone: steps in the GW_SCRIPT setting, separated by ";",
 * e.g. "wait 3; search Essence Wizkid; wait 6; play 0; wait 10; open; lyrics on".
 */
object Script {
    fun start(app: AppState) {
        val script = NSProcessInfo.processInfo.environment["GW_SCRIPT"] as? String ?: return
        Log.i("script: $script")
        MainScope().launch {
            for (step in script.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                val cmd = step.substringBefore(" ")
                val arg = step.substringAfter(" ", "")
                Log.i("step: $step")
                when (cmd) {
                    "wait" -> delay(((arg.toDoubleOrNull() ?: 1.0) * 1000).toLong())
                    "search" -> app.search(arg)
                    "type" -> app.typed(arg)
                    "play" -> app.results.takeIf { it.isNotEmpty() }?.let { app.player.playList(it, arg.toIntOrNull() ?: 0) }
                    "open" -> app.fullOpen = true
                    "close" -> app.fullOpen = false
                    "lyrics" -> app.showLyrics = arg != "off"
                    "queue" -> app.showQueue = arg != "off"
                    "next" -> app.player.next()
                    "toggle" -> app.player.toggle()
                    "seek" -> app.player.seekTo(((arg.toDoubleOrNull() ?: 0.0) * 1000).toLong())
                    "state" -> Log.i(
                        "state: playing=${app.player.playing} loading=${app.player.loading} pos=${app.player.positionMs} " +
                            "dur=${app.player.durationMs} song=${app.player.current?.title} queue=${app.player.queue.size}",
                    )
                }
            }
        }
    }
}
