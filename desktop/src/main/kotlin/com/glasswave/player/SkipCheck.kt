// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import org.json.JSONArray
import java.io.File
import kotlin.math.abs

/**
 * Checks Smart order's maths against the training code (no internet, no real history).
 *
 * checks/skipnet-golden.json is a scripted listening history written by the training code,
 * with the exact inputs and the exact answer the network must give at each step. This
 * replays it through [SkipNet.Listener] and the bundled network and fails if anything is off.
 *
 * ./gradlew -p desktop skipCheck
 */
fun main(args: Array<String>) {
    var failed = 0
    fun check(what: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS  " else "FAIL  ") + what + if (detail.isNotEmpty()) "  ($detail)" else "")
        if (!ok) failed++
    }

    val net = SkipNet.loadNet()
    val script = JSONArray(File(args.firstOrNull() ?: "checks/skipnet-golden.json").readText())
    val me = SkipNet.Listener(net)
    var scored = 0
    var worstInput = 0.0
    var worstChance = 0.0
    var low = 1.0
    var high = 0.0
    for (i in 0 until script.length()) {
        val s = script.getJSONObject(i)
        when (s.getString("do")) {
            "listen" -> me.addListen(s.getString("track"), s.getString("artist"), s.getLong("started"), s.getLong("ended"), s.getBoolean("listened"))
            "score" -> {
                val x = me.features(
                    s.getString("track"), s.getString("artist"), s.getLong("now"), s.getLong("length"),
                    s.getBoolean("organic"), s.getBoolean("liked"), s.getInt("artistLikes"),
                )
                val want = s.getJSONArray("features")
                for (j in x.indices) worstInput = maxOf(worstInput, abs(x[j] - want.getDouble(j)))
                val chance = net.chance(x)
                worstChance = maxOf(worstChance, abs(chance - s.getDouble("chance")))
                low = minOf(low, chance); high = maxOf(high, chance)
                scored++
            }
        }
    }
    check("the script scores songs", scored > 100, "$scored songs")
    check("inputs match the training code", worstInput < 1e-6, "largest difference %.2e".format(worstInput))
    check("the network's answers match", worstChance < 1e-6, "largest difference %.2e".format(worstChance))
    check("answers are spread out, not all the same", high - low > 0.3, "from %.3f to %.3f".format(low, high))


    check("artist names reduce to the first artist", SkipNet.artistKey("Burna Boy, Dave & Stormzy") == "burna boy")

    // The whole thing as the app uses it: the player reports listens, the log fills, the order follows.
    val ctx = android.content.AppContext
    Settings.init(ctx); Library.init(ctx)
    val log = File(ctx.filesDir, "listens.jsonl").apply { delete() }
    File(ctx.filesDir, "plays.jsonl").delete()
    SkipNet.init(ctx)
    SkipNet.settle()
    fun song(id: Int, artist: String) = Track("https://music.youtube.com/watch?v=x$id", "Song $id", artist, 200, null)
    val fresh = listOf(song(900, "Alpha"), song(901, "Beta"))
    check("with no history it stays out of the way", SkipNet.chances(fresh) == null)
    for (i in 0 until 15) {
        SkipNet.record(song(i, "Alpha"), 200_000, 200_000, pickedByYou = false)      // played to the end
        SkipNet.record(song(100 + i, "Beta"), 4_000, 200_000, pickedByYou = false)   // skipped after 4 seconds
    }
    SkipNet.record(song(500, "Gamma"), 400, 200_000, pickedByYou = false)            // never really started
    SkipNet.settle()
    check("every listen is logged, skips included", log.readLines().size == 30, "${log.readLines().size} lines")
    val c = SkipNet.chances(fresh)
    check("an artist you always finish beats one you always skip", c != null && c[0] > c[1] + 0.1, c?.joinToString { "%.2f".format(it) } ?: "not ready")
    val alphaFirst = (1..400).count { SkipNet.arrange(fresh).first().artist == "Alpha" }
    check("the favourite usually comes first, but not every time", alphaFirst in 240..399, "$alphaFirst of 400")
    SkipNet.clear()
    SkipNet.settle()
    check("clearing your history clears this too", !log.exists() && SkipNet.chances(fresh) == null)

    println(if (failed == 0) "\nAll checks passed." else "\n$failed check(s) FAILED.")
    if (failed > 0) kotlin.system.exitProcess(1)
}
