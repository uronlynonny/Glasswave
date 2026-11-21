// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * For checks only: how accurate the lyrics Glasswave picks are, over many songs. Songs come from
 * play history files given as arguments (plays.jsonl) and from popular playlists in many genres.
 * Each song's lyrics are looked up the way the app does, then compared with YouTube Music's own
 * lyrics for that exact song (its plain text, and its timing where it has one). Prints one line per
 * song and a summary, and writes build/check-data/lyrics-audit.tsv.
 *   ./gradlew -p desktop lyricsAudit -Pplays=path/plays.jsonl[;more.jsonl] [-Pmax=400]
 */
fun main(args: Array<String>) = runBlocking {
    startUp()
    if (System.getProperty("gw.ytSyncDebug") != null) {
        println("GW ytsync signedIn=${Accounts.ytSignedIn}")
        val lists = runCatching { YtMusicApi.playlists() }
        lists.exceptionOrNull()?.let { e -> println("GW ytsync playlists failed: ${e.javaClass.name}: ${e.message}"); e.stackTrace.take(8).forEach { println("GW ytsync   at $it") } }
        lists.getOrNull()?.let { l ->
            println("GW ytsync ${l.size} playlists: " + l.take(8).joinToString { it.name })
            l.firstOrNull()?.let { p -> println("GW ytsync first playlist tracks: " + runCatching { YtMusicApi.tracks(p.id.removePrefix("yt_")).size }.let { r -> r.getOrNull()?.toString() ?: ("failed " + r.exceptionOrNull()) }) }
        }
        System.exit(0)
    }
    System.getProperty("gw.auditDebug")?.let { path ->
        val arr = org.json.JSONArray(File(path).readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            println("GW debug ${o.getString("t")}: " + withContext(Dispatchers.IO) { LyricsRepo.debugYoutube(o.getString("u")) })
        }
        System.exit(0)
    }
    val max = System.getProperty("gw.auditMax")?.toIntOrNull() ?: 400
    val songs = LinkedHashMap<String, Track>()
    // Your own most played songs first.
    val counts = HashMap<String, Int>()
    val seen = HashMap<String, Track>()
    for (path in args.flatMap { it.split(';') }.filter { it.isNotBlank() }) {
        runCatching {
            File(path).forEachLine { line ->
                val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return@forEachLine
                val u = o.optString("u").takeIf { it.contains("youtube.com/watch") } ?: return@forEachLine
                counts[u] = (counts[u] ?: 0) + 1
                seen[u] = Track(u, o.optString("t"), o.optString("a"), 0, null)
            }
        }
    }
    counts.entries.sortedByDescending { it.value }.take(max / 3).forEach { songs[it.key] = seen.getValue(it.key) }
    // Then popular songs across genres and languages.
    val queries = listOf(
        "Top 100 Global", "Top 100 Nigeria", "Afrobeats hits", "Amapiano hits", "Hip hop hits", "R&B hits",
        "Pop hits", "K-pop hits", "Latin hits", "Reggaeton hits", "Rock classics", "Gospel hits",
        "Country hits", "Throwback 2000s hits", "Drill hits", "Bollywood hits", "J-pop hits", "Alternative hits",
    )
    for (q in queries) {
        if (songs.size >= max) break
        val pl = runCatching {
            YtMusicApi.search(q, YtMusicApi.Filter.PLAYLISTS).sections.flatMap { it.items }.filterIsInstance<PlaylistItem>().firstOrNull()
        }.getOrNull() ?: continue
        val page = runCatching { YtMusicApi.page(pl) }.getOrNull() ?: continue
        page.tracks.filter { it.url.contains("watch?v=") }.take(18).forEach { if (songs.size < max) songs.putIfAbsent(it.url, it) }
        println("GW audit playlist '$q' -> ${pl.title}: ${songs.size} songs so far")
    }
    println("GW audit ${songs.size} songs")

    data class Row(val t: Track, val source: String, val kind: String, val text: Double?, val timing: Double?, val verdict: String, val first: String)
    val gate = Semaphore(4)
    val rows = songs.values.map { t ->
        async(Dispatchers.IO) {
            gate.withPermit {
                val id = runCatching { YtRepo.videoIdFor(t.url) }.getOrNull()
                val seconds = runCatching { YtRepo.lengthOf(t.url) }.getOrDefault(0L)
                val ly = runCatching { LyricsRepo.get(t.url, t.title, t.artist, seconds) }.getOrNull()
                val plain = id?.let { runCatching { YtMusicApi.lyrics(it) }.getOrNull() }
                val timed = id?.let { runCatching { YtMusicApi.timedLyrics(it) }.getOrNull() }
                val ref = plain?.let { p -> Lyrics(p.lines().map { LyricLine(-1, it.trim()) }, false, "YouTube Music") }
                val refTimed = timed?.let { l -> Lyrics(l.map { (ms, s) -> LyricLine(ms, s) }, true, "YouTube Music") }
                val text = if (ly != null && ref != null) LyricsRepo.textScore(ref, ly) else null
                val timing = if (ly != null && refTimed != null && ly.source != "YouTube Music") LyricsRepo.timeScore(refTimed, ly) else null
                val verdict = when {
                    ly == null && (ref != null || refTimed != null) -> "MISSING"
                    ly == null -> "none"
                    ly.source == "YouTube Music" -> "ok-exact"
                    text != null && text < 0.5 -> "WRONG"
                    timing != null && timing < 0.6 -> "OFF"
                    text != null && text < 0.75 -> "doubtful"
                    text == null && timing == null -> "unverified"
                    else -> "ok"
                }
                val first = ly?.lines?.firstOrNull { it.text.isNotBlank() }?.text?.split(" ")?.take(4)?.joinToString(" ").orEmpty()
                Row(t, ly?.source ?: "-", if (ly?.wordTimed == true) "words" else if (ly?.synced == true) "lines" else "-", text, timing, verdict, first).also { r ->
                    println("GW audit | %-9s | %-30s | %-18s | %-14s | text %s | timing %s".format(
                        r.verdict, t.title.take(30), t.artist.take(18), r.source,
                        r.text?.let { "%.2f".format(it) } ?: " -  ", r.timing?.let { "%.2f".format(it) } ?: " -  ",
                    ))
                }
            }
        }
    }.awaitAll()

    val out = File(android.content.AppDirs.data.parentFile ?: android.content.AppDirs.data, "lyrics-audit.tsv")
    withContext(Dispatchers.IO) {
        out.writeText("verdict\ttitle\tartist\turl\tsource\tkind\ttext\ttiming\tfirst\n" + rows.joinToString("\n") { r ->
            listOf(r.verdict, r.t.title, r.t.artist, r.t.url, r.source, r.kind, r.text?.let { "%.2f".format(it) } ?: "", r.timing?.let { "%.2f".format(it) } ?: "", r.first).joinToString("\t")
        })
    }
    println("GW audit summary: " + rows.groupingBy { it.verdict }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
    println("GW audit sources: " + rows.groupingBy { it.source }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
    println("GW audit file ${out.absolutePath}")
    System.exit(0)
}
