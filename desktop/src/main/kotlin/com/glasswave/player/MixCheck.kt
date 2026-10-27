// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import java.io.File
import java.io.RandomAccessFile

/**
 * A check of Automix's listening and planning without the window: finds a few songs, works out
 * each one's tempo, beats, key and where it starts and ends, plans the transition between each pair,
 * and renders every transition to a WAV file to listen to.
 * Run with  ./gradlew -p desktop mixCheck  (or -Psongs="song one;song two;..." and -Pout=folder)
 */
fun main(args: Array<String>) {
    val t0 = System.currentTimeMillis()
    fun say(s: String) = println("[%6d ms] %s".format(System.currentTimeMillis() - t0, s))
    args.drop(1).flatMap { it.split(";") }.firstOrNull { it.startsWith("play=") }?.let { playCheck(it.removePrefix("play="), ::say); System.exit(0) }
    startUp()
    val out = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "mixcheck").apply { mkdirs() }
    val queries = args.drop(1).flatMap { it.split(";") }.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty {
        listOf("daft punk get lucky", "avicii levels", "calvin harris summer", "dua lipa levitating", "the weeknd blinding lights", "adele someone like you")
    }
    if ("selftest" in queries) { selfTest(::say); System.exit(0) }

    class Song(val track: Track, val src: AudioSource, val head: MixProfile?, val tail: MixProfile?, val ms: Long)
    // "dl=<downloads folder>[,count]" uses downloaded songs instead of searching (works offline).
    val picked: List<Pair<Track, AudioSource?>> = queries.flatMap { q ->
        if (q == "order") return@flatMap emptyList()
        if (!q.startsWith("dl=")) return@flatMap listOf(Track("", q, "", 0, null) to null)
        val (dir, count) = q.removePrefix("dl=").split(",").let { File(it[0]) to (it.getOrNull(1)?.toIntOrNull() ?: 6) }
        val arr = org.json.JSONArray(File(dir, "index.json").readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { File(dir, it.optString("f")).exists() }.take(count).map { o ->
            Track(o.optString("u"), o.optString("t"), o.optString("a"), o.optLong("s"), null) to AudioSource.LocalFile(File(dir, o.optString("f")))
        }
    }
    val songs = picked.mapNotNull { (pick, local) ->
        val q = pick.title
        runCatching {
            val t = if (local != null) pick else kotlinx.coroutines.runBlocking { YtRepo.search(q) }.first()
            val src = local ?: MixAudio.source(t.url)
            val a = System.currentTimeMillis()
            val hp = MixAudio.read(src, MixPart.HEAD, MixAudio.RATE, 1)
            val head = hp?.let { MixAnalysis.analyse(it) }
            val hd = MixAnalysis.lastDebug
            val tp = MixAudio.read(src, MixPart.TAIL, MixAudio.RATE, 1)
            val tail = tp?.let { MixAnalysis.analyse(it) }
            val td = MixAnalysis.lastDebug
            say("${t.title} · ${t.artist}  (${System.currentTimeMillis() - a} ms to read and listen)")
            for ((name, p, d) in listOf(Triple("head", head, hd), Triple("tail", tail, td))) {
                if (p == null) { say("   $name: couldn't read"); continue }
                say("   $name ${p.fromMs / 1000}-${p.toMs / 1000}s: %.2f BPM, steady %.2f, key %s (%.2f), bars %d, downbeat %d, sound %.1f-%.1fs, loud %.1f dB".format(
                    p.bpm, p.confidence, p.keyName(), p.keyStrength, p.bars, p.downbeat, p.soundStartMs / 1000.0, p.soundEndMs / 1000.0, p.loudDb,
                ))
                say("        $d")
            }
            Song(t, src, head, tail, hp?.songMs ?: tp?.songMs ?: 0)
        }.onFailure { say("\"$q\" failed: $it") }.getOrNull()
    }

    // "order" plays them in the order Automix would pick, instead of as listed.
    val ordered = if ("order" in queries) {
        for (s in songs) { s.head?.let { MixLibrary.remember(s.track.url, MixPart.HEAD, it) }; s.tail?.let { MixLibrary.remember(s.track.url, MixPart.TAIL, it) } }
        val chain = MixPlanner.chain(songs.map { it.track }, 7)
        say("Automix order: " + chain.joinToString(" → ") { it.title })
        chain.map { t -> songs.first { it.track.url == t.url } }
    } else songs

    for (i in 0 until ordered.size - 1) {
        val a = ordered[i]
        val b = ordered[i + 1]
        val plan = MixPlanner.plan(a.ms, a.tail, b.head, 8)
        say("${a.track.title} → ${b.track.title}: ${plan.description}")
        say("   out at %.2fs, in at %.2fs, %.2fs together, fit %.2f".format(plan.outStartMs / 1000.0, plan.inStartMs / 1000.0, plan.lengthMs / 1000.0, MixPlanner.compat(a.tail, b.head)))
        runCatching {
            val file = File(out, "%02d %s to %s.wav".format(i + 1, a.track.title.take(24), b.track.title.take(24)).replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            render(a.src, b.src, plan, file)
            say("   rendered ${file.absolutePath}")
        }.onFailure { say("   render failed: $it") }
    }
    System.exit(0)
}

/** Plays a transition offline exactly as the player would, into a WAV file: 6 s before the mix, the mix, and 10 s after. */
private fun render(srcA: AudioSource, srcB: AudioSource, plan: MixPlan, file: File) {
    val rate = 48_000
    val pre = 6_000L
    val post = 10_000L
    val glide = plan.glideMs
    val old = MixAudio.readRange(srcA, plan.outStartMs - pre, plan.outStartMs + plan.lengthMs + 200, rate, 2) ?: error("old song")
    val new = MixAudio.readRange(srcB, plan.inStartMs, plan.inStartMs + plan.lengthMs + post + glide + 2_000, rate, 2) ?: error("new song")
    val total = ((pre + plan.lengthMs + post) * rate / 1000).toInt()
    val mix = FloatArray(total * 2)
    val eqOld = MixEq(rate, 2)
    val eqNew = MixEq(rate, 2)
    val stretch = TimeStretch(2).apply { reset(0) }
    val oldStart = ((plan.outStartMs - pre - old.startMs) * rate / 1000).toInt()
    val newOffset = ((plan.inStartMs - new.startMs) * rate / 1000).toLong().coerceAtLeast(0)
    var fed = newOffset
    // Skip the part of the new song before the in-point.
    stretch.reset(newOffset)
    val block = FloatArray(TimeStretch.HOP * 2)
    val tmp = FloatArray(TimeStretch.HOP * 2)
    val mixStart = (pre * rate / 1000).toInt()
    var f = 0
    while (f < total) {
        val n = minOf(TimeStretch.HOP, total - f)
        val tMs = (f - mixStart) * 1000.0 / rate
        val k = (tMs / plan.lengthMs).toFloat()
        val lv = plan.levels(k)
        // The old song.
        if (tMs < plan.lengthMs) {
            val from = oldStart + f
            for (i in 0 until n) {
                val s = from + i
                tmp[i * 2] = old.samples.getOrElse(s * 2) { 0f }
                tmp[i * 2 + 1] = old.samples.getOrElse(s * 2 + 1) { 0f }
            }
            val g = if (tMs < 0) 1f else lv.oldGain
            eqOld.bass = if (tMs < 0) 1f else lv.oldBass
            eqOld.sweepHz = if (tMs < 0) 0f else lv.oldSweepHz
            eqOld.process(tmp, 0, n)
            for (i in 0 until n * 2) mix[f * 2 + i] += tmp[i] * g
        }
        // The new song, tempo-matched and gliding back after.
        if (tMs >= plan.inDelayMs) {
            stretch.speed = when {
                tMs < plan.lengthMs || glide <= 0 -> plan.speed.toDouble()
                tMs < plan.lengthMs + glide -> plan.speed + (1 - plan.speed) * (tMs - plan.lengthMs) / glide
                else -> 1.0
            }
            while (true) {
                val got = stretch.pull(block)
                if (got > 0) break
                val chunk = 4096
                val avail = (new.samples.size / 2 - fed).toInt()
                if (avail <= 0) { block.fill(0f); break }
                val m = minOf(chunk, avail)
                stretch.push(new.samples.copyOfRange((fed * 2).toInt(), ((fed + m) * 2).toInt()), m)
                fed += m
            }
            val g = if (tMs > plan.lengthMs) 1f else lv.newGain
            eqNew.bass = if (tMs > plan.lengthMs) 1f else lv.newBass
            eqNew.process(block, 0, n)
            for (i in 0 until n * 2) mix[f * 2 + i] += block[i] * g
        }
        f += n
    }
    writeWav(file, mix, rate)
}

private fun writeWav(file: File, samples: FloatArray, rate: Int) {
    val bytes = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val v = (kotlin.math.tanh(samples[i].toDouble() * 1.1) / kotlin.math.tanh(1.1) * 32767).toInt().coerceIn(-32768, 32767)
        bytes[i * 2] = v.toByte()
        bytes[i * 2 + 1] = (v shr 8).toByte()
    }
    RandomAccessFile(file, "rw").use { f ->
        f.setLength(0)
        fun i32(v: Int) { f.write(v and 0xFF); f.write(v shr 8 and 0xFF); f.write(v shr 16 and 0xFF); f.write(v shr 24 and 0xFF) }
        fun i16(v: Int) { f.write(v and 0xFF); f.write(v shr 8 and 0xFF) }
        f.writeBytes("RIFF"); i32(36 + bytes.size); f.writeBytes("WAVE")
        f.writeBytes("fmt "); i32(16); i16(1); i16(2); i32(rate); i32(rate * 4); i16(4); i16(16)
        f.writeBytes("data"); i32(bytes.size)
        f.write(bytes)
    }
}

/** Checks the maths on made-up sounds, where the right answers are known. */
private fun selfTest(say: (String) -> Unit) {
    val rate = 22_050
    // 1. A click track at 123.4 BPM starting at 0.37 s, with a quiet hum: the grid should land on it.
    run {
        val beat = 60.0 / 123.4
        val x = FloatArray(rate * 60)
        for (i in x.indices) x[i] = (0.02 * kotlin.math.sin(2 * Math.PI * 220 * i / rate)).toFloat()
        var t = 0.37
        var k = 0
        while (t < 59.9) {
            val s = (t * rate).toInt()
            val loud = if (k % 4 == 0) 0.9 else 0.5
            for (j in 0 until 400) if (s + j < x.size) x[s + j] += (loud * kotlin.math.exp(-j / 60.0) * kotlin.math.sin(2 * Math.PI * 80 * j / rate)).toFloat()
            t += beat; k++
        }
        val p = MixAnalysis.analyse(Pcm(x, rate, 0.0, 60_000))
        val phase = ((p.firstBeatMs - 370) / (beat * 1000)).let { it - Math.round(it) } * beat * 1000
        say("click track: %.2f BPM (want 123.40), steady %.2f, grid off by %.1f ms, downbeat on beat %d".format(p.bpm, p.confidence, phase, ((p.firstBeatMs - 370) / (beat * 1000)).let { Math.round(it).toInt() } + p.downbeat))
    }
    // 2. Time stretch: a 440 Hz tone sped up 6% should keep its pitch and its loudness steady.
    run {
        val r = 48_000
        val n = r * 4
        val src = FloatArray(n * 2) { (0.5 * kotlin.math.sin(2 * Math.PI * 440 * (it / 2) / r)).toFloat() }
        val ts = TimeStretch(2).apply { reset(0); speed = 1.06 }
        val out = ArrayList<Float>()
        val block = FloatArray(TimeStretch.HOP * 2)
        var fed = 0
        while (true) {
            if (ts.pull(block) > 0) { for (i in 0 until TimeStretch.HOP) out += block[i * 2]; continue }
            if (fed >= n) break
            val m = minOf(4096, n - fed)
            ts.push(src.copyOfRange(fed * 2, (fed + m) * 2), m); fed += m
        }
        val y = out.drop(r / 10).dropLast(r / 10)
        var zc = 0
        for (i in 1 until y.size) if (y[i - 1] < 0 && y[i] >= 0) zc++
        val freq = zc.toDouble() / (y.size.toDouble() / r)
        val rmsBlocks = y.chunked(480).map { b -> kotlin.math.sqrt(b.sumOf { (it * it).toDouble() } / b.size) }
        say("stretch 1.06x: %.1f Hz (want 440), %.2f s out of %.2f s in (want %.2f), loudness %.3f..%.3f (want ~0.354)".format(
            freq, out.size.toDouble() / r, n.toDouble() / r, n.toDouble() / r / 1.06, rmsBlocks.min(), rmsBlocks.max()))
    }
    // 3. Bass isolator: with the bass off, 60 Hz should mostly go and 1 kHz should stay.
    run {
        val r = 48_000
        fun level(freq: Double, bass: Float): Double {
            val eq = MixEq(r, 2).apply { this.bass = bass }
            val b = FloatArray(r * 4) { (0.5 * kotlin.math.sin(2 * Math.PI * freq * (it / 2) / r)).toFloat() }
            eq.process(b, 0, r); eq.process(b, r * 2, r)
            val tail = b.copyOfRange(r * 2, r * 4)
            return 20 * kotlin.math.log10(kotlin.math.sqrt(tail.sumOf { (it * it).toDouble() } / tail.size) / 0.3536)
        }
        say("isolator: bass on 60 Hz %.1f dB, 1 kHz %.1f dB | bass off 60 Hz %.1f dB, 1 kHz %.1f dB".format(level(60.0, 1f), level(1000.0, 1f), level(60.0, 0f), level(1000.0, 0f)))
    }
}

/**
 * Plays two downloaded songs through the real player with Automix on (muted), jumps to just before
 * the planned mix, and reports how well the beats stay lined up. "play=<downloads folder>,<title>,<title>"
 */
private fun playCheck(spec: String, say: (String) -> Unit) {
    val parts = spec.split(",")
    val dir = File(parts[0])
    val want = parts.drop(1)
    val arr = org.json.JSONArray(File(dir, "index.json").readText())
    val entries = (0 until arr.length()).map { arr.getJSONObject(it) }
        .filter { o -> want.any { w -> o.optString("t").contains(w, ignoreCase = true) } && File(dir, o.optString("f")).exists() }
        .sortedBy { o -> want.indexOfFirst { w -> o.optString("t").contains(w, ignoreCase = true) } }
    require(entries.size >= 2) { "need two songs, found ${entries.size}" }
    // The check's own downloads folder gets just these songs.
    val mine = File(android.content.AppDirs.data, "downloads").apply { mkdirs() }
    val index = org.json.JSONArray()
    for (o in entries) { File(dir, o.optString("f")).copyTo(File(mine, o.optString("f")), overwrite = true); index.put(o) }
    File(mine, "index.json").writeText(index.toString())
    startUp()
    AppVolume.muteForChecks()
    Settings.updateAutomix(true)
    val tracks = entries.map { Track(it.optString("u"), it.optString("t"), it.optString("a"), it.optLong("s"), null) }
    val ps = PlayerState()
    javax.swing.SwingUtilities.invokeAndWait {
        ps.connect()
        ps.mixLog = say
        ps.playList(tracks, 0)
    }
    say("playing ${tracks[0].title}, then ${tracks[1].title}")
    // Wait for Automix to have listened to the end of the first song and the start of the second.
    val until = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < until && (MixLibrary.profile(tracks[0].url, MixPart.TAIL) == null || MixLibrary.profile(tracks[1].url, MixPart.HEAD) == null)) Thread.sleep(200)
    Thread.sleep(1500)
    var plan: MixPlan? = null
    javax.swing.SwingUtilities.invokeAndWait { ps.tick() }
    // Look at the plan (it's made once the song is playing), then jump to 20 s before it.
    repeat(50) {
        javax.swing.SwingUtilities.invokeAndWait { plan = ps.nextPlan }
        if (plan != null) return@repeat
        Thread.sleep(100)
    }
    val p = plan ?: run {
        // The plan is made as the mix approaches: jump near the end first.
        javax.swing.SwingUtilities.invokeAndWait { ps.seekTo(maxOf(0, tracks[0].seconds * 1000 - 40_000)) }
        Thread.sleep(1500)
        var q: MixPlan? = null
        javax.swing.SwingUtilities.invokeAndWait { q = ps.nextPlan }
        q ?: error("no plan")
    }
    say("plan: ${p.description}; out at ${p.outStartMs} ms, in at ${p.inStartMs} ms, ${p.lengthMs} ms together")
    javax.swing.SwingUtilities.invokeAndWait { ps.seekTo(p.outStartMs - 18_000) }
    Thread.sleep(18_000 + p.lengthMs + 8_000)
    var now = ""
    javax.swing.SwingUtilities.invokeAndWait { ps.tick(); now = "${ps.title} at ${ps.positionMs} ms" }
    say("now playing $now")
}
