// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File

/**
 * For automated checks only: two pretend devices ("phone" and "pc") on a made-up test account, each
 * in its own process with its own sandbox data, talk through the real relay. Checks that each sees
 * the other, that plays are shared both ways (once), that remote pause works, and that playback can
 * be moved. No real account, no sound, nothing on screen.  ./gradlew -p desktop syncCheck
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "device") return device(args[1])
    val base = File(System.getProperty("gw.syncDir") ?: error("gw.syncDir"))
    val account = "test-" + java.util.UUID.randomUUID().toString().take(12)
    val java = ProcessHandle.current().info().command().get()
    val kids = listOf("A", "B").map { role ->
        val dir = File(base, role).apply { deleteRecursively(); mkdirs() }
        ProcessBuilder(java, "-Dgw.syncTestAccount=$account", "-Dgw.noCheckIn=1", "-cp", System.getProperty("java.class.path"),
            "com.glasswave.player.SyncCheckKt", "device", role)
            .apply { environment()["APPDATA"] = dir.absolutePath; environment()["LOCALAPPDATA"] = dir.absolutePath }
            .redirectErrorStream(true).start()
    }
    val threads = kids.mapIndexed { i, p ->
        Thread { p.inputStream.bufferedReader().forEachLine { l -> println("[${"AB"[i]}] $l") } }.apply { start() }
    }
    kids.forEach { it.waitFor() }
    threads.forEach { it.join() }
    System.exit(0)
}

private class FakePlayer(var track: Track?, var playing: Boolean) : Sync.Remote {
    val log = mutableListOf<String>()
    override fun current() = track
    override fun isPlaying() = playing
    override fun positionMs() = 42_000L
    override fun durationMs() = 200_000L
    override fun upcoming(limit: Int) = listOf(Track("https://music.youtube.com/watch?v=next1", "Next One", "Someone", 180, null))
    override fun toggle() { playing = !playing; log += "toggle" }
    override fun play() { playing = true; log += "play" }
    override fun pause() { playing = false; log += "pause" }
    override fun next() { log += "next" }
    override fun previous() { log += "prev" }
    override fun seekTo(ms: Long) { log += "seek $ms" }
    override fun playQueue(tracks: List<Track>, index: Int, startMs: Long) {
        track = tracks[index]; playing = true; log += "playQueue ${tracks.size} songs, '${tracks[index].title}' from ${startMs / 1000}s"
    }
}

private fun device(role: String) {
    val ctx = android.content.AppContext
    Settings.init(ctx); Accounts.init(ctx); Stats.init(ctx); Sync.init(ctx)
    val mine = if (role == "A") listOf("Song A1", "Song A2", "Song A3") else listOf("Song B1")
    mine.forEachIndexed { i, t -> Stats.record(Track("https://music.youtube.com/watch?v=$role$i", t, "Artist $role", 200, null), 60_000L + i) }
    Thread.sleep(500)
    val player = if (role == "A") FakePlayer(Track("https://music.youtube.com/watch?v=nowA", "Playing On A", "Artist A", 200, null), true) else FakePlayer(null, false)
    javax.swing.SwingUtilities.invokeAndWait { Sync.remote = player }
    if (role == "B") Thread.sleep(4000) // A first, then B arrives
    javax.swing.SwingUtilities.invokeAndWait { Sync.start() }

    fun devices() = Sync.devices.joinToString { "${it.name}(${it.kind}) playing=${it.playingNow()} track=${it.track?.title}" }
    fun wait(s: Int, done: () -> Boolean) { repeat(s * 4) { if (done()) return; Thread.sleep(250) } }

    wait(25) { Sync.devices.isNotEmpty() && (role == "A" || Sync.devices.any { it.track != null }) }
    println("GW $role sees: ${devices()}")
    wait(25) { Stats.ownPlaysAfter(0).size + (Sync.syncedPlaysFile()?.takeIf { it.exists() }?.readLines()?.size ?: 0) >= 4 }
    val synced = Sync.syncedPlaysFile()?.takeIf { it.exists() }?.readLines().orEmpty()
    println("GW $role has ${Stats.ownPlaysAfter(0).size} own plays + ${synced.size} from the other device: ${synced.map { org.json.JSONObject(it).optString("t") }}")

    if (role == "B") {
        val a = Sync.devices.first()
        Sync.command(a, "pause")
        Thread.sleep(6000)
        // Take over what A was playing.
        Sync.playHere(Sync.devices.first())
        Thread.sleep(3000)
        println("GW B player: ${player.log}")
    } else {
        wait(30) { player.log.size >= 2 }
        println("GW A player (after B's commands): ${player.log}")
        // Now move playback from A to B: B's player should receive it.
        player.track = Track("https://music.youtube.com/watch?v=moveMe", "Move Me", "Artist A", 200, null)
        player.playing = true
        javax.swing.SwingUtilities.invokeAndWait { Sync.playOn(Sync.devices.first()) }
        Thread.sleep(5000)
    }
    if (role == "B") Thread.sleep(9000).also { println("GW B player after A moved playback: ${player.log.lastOrNull()}") }
    // Run again: nothing should be counted twice.
    val again = Sync.syncedPlaysFile()?.takeIf { it.exists() }?.readLines()?.size ?: 0
    println("GW $role synced plays at the end: $again")
    Sync.goodbye()
    System.exit(0)
}
