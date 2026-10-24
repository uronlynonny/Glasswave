// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/**
 * A quick check of the audio path without the window: finds a song, looks up its audio, and
 * plays a few seconds of it, printing each step. Run with  ./gradlew -p desktop deckCheck
 */
fun main(args: Array<String>) {
    val t0 = System.currentTimeMillis()
    fun say(s: String) = println("[%5d ms] %s".format(System.currentTimeMillis() - t0, s))
    startUp()
    say("YouTube ready")
    val query = args.firstOrNull() ?: "daft punk get lucky"
    val song = kotlinx.coroutines.runBlocking { YtRepo.search(query) }.first()
    say("found ${song.title} · ${song.url}")
    // What Autoplay and Flow would add after this song (songs only, no music videos).
    // "-Ponly=radio:<videoId>" starts the radio from that video instead (e.g. an official music video).
    runCatching {
        val seed = args.getOrNull(1)?.takeIf { it.startsWith("radio:") }?.removePrefix("radio:") ?: YtRepo.videoIdFor(song.url) ?: ""
        val radio = YtMusicApi.radio(seed)
        say("radio: ${radio.size} songs, e.g. ${radio.take(6).joinToString(" | ") { it.title + " - " + it.artist }}")
    }.onFailure { say("radio failed: $it") }
    if (args.getOrNull(1)?.startsWith("radio") == true) System.exit(0)
    val stream = YtRepo.streamUrl(song.url)
    say("audio address found (${stream.take(80)}...)")
    // How long the whole song takes to arrive through the player's reader ("-Ponly=fetch").
    if (args.getOrNull(1) == "fetch") {
        val t = System.currentTimeMillis()
        val input = HttpSeekStream(stream)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) { val n = input.read(buf, 0, buf.size); if (n < 0) break; total += n }
        input.close()
        say("fetched ${total / 1024} KB of ${input.length / 1024} KB in ${System.currentTimeMillis() - t} ms")
        System.exit(0)
    }
    // What the decoder hands over: full-precision samples, with sensible levels.
    runCatching {
        val g = org.bytedeco.javacv.FFmpegFrameGrabber(HttpSeekStream(stream), Int.MAX_VALUE - 8)
        g.sampleFormat = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
        g.sampleRate = 48_000
        g.audioChannels = 2
        g.start()
        g.setTimestamp(30_000_000)
        var peak = 0f
        var kind = ""
        repeat(200) {
            val f = g.grabSamples() ?: return@repeat
            val b = f.samples?.firstOrNull()
            kind = b?.javaClass?.simpleName ?: "none"
            if (b is java.nio.FloatBuffer) for (i in b.position() until b.limit()) peak = maxOf(peak, kotlin.math.abs(b.get(i)))
        }
        g.stop(); g.release()
        say("decoder gives $kind, peak level ${"%.3f".format(peak)} (1.0 = full scale)")
    }.onFailure { say("decoder probe failed: $it") }
    AppVolume.muteForChecks()
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
    val to = if (args.getOrNull(1) == "end" && deck.durationMs > 0) deck.durationMs * 85 / 100 else 60_000L
    say("seeking to ${to / 1000} s")
    deck.seekTo(to)
    repeat(16) { Thread.sleep(500); say("position ${deck.positionMs} ms") }
    deck.stop()
    say("stopped")
    System.exit(0)
}
