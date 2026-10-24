// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/**
 * A quick check of the audio path without the window: finds a song, looks up its audio, and
 * plays a few seconds of it, printing each step. Run with  ./gradlew -p desktop deckCheck
 */
fun main(args: Array<String>) {
    val t0 = System.currentTimeMillis()
    fun say(s: String) = println("[%5d ms] %s".format(System.currentTimeMillis() - t0, s))
    Settings.init(android.content.AppContext)
    YtRepo.init()
    say("YouTube ready")
    val query = args.firstOrNull() ?: "daft punk get lucky"
    val song = kotlinx.coroutines.runBlocking { YtRepo.search(query) }.first()
    say("found ${song.title} · ${song.url}")
    val stream = YtRepo.streamUrl(song.url)
    say("audio address found (${stream.take(80)}...)")
    val deck = AudioDeck("check")
    val done = java.util.concurrent.CountDownLatch(1)
    deck.listener = object : AudioDeck.Listener {
        override fun onReady(deck: AudioDeck) = say("ready, length ${deck.durationMs} ms")
        override fun onEnded(deck: AudioDeck) { say("ended"); done.countDown() }
        override fun onError(deck: AudioDeck, e: Exception) { say("ERROR $e"); e.printStackTrace(); done.countDown() }
    }
    deck.load(AudioSource.Remote(stream), 0, true, null)
    repeat(16) {
        if (done.count == 0L) return@repeat
        Thread.sleep(500)
        say("position ${deck.positionMs} ms, ready=${deck.ready}")
    }
    if (!deck.ready) {
        Thread.getAllStackTraces().filterKeys { it.name.startsWith("deck-") }.forEach { (t, st) ->
            say("still opening; ${t.name} is at:")
            st.take(14).forEach { println("      $it") }
        }
    }
    say("seeking to 60 s")
    deck.seekTo(60_000)
    repeat(6) { Thread.sleep(500); say("position ${deck.positionMs} ms") }
    deck.stop()
    say("stopped")
    System.exit(0)
}
