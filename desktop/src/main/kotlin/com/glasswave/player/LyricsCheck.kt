// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/**
 * Looks up lyrics for a list of songs the way the app does, without the window, and prints which
 * source won, how many lines, and whether the words are timed. Only the first few words of each
 * song's first line are printed. Run with
 *   ./gradlew -p desktop lyricsCheck -Psongs=path/to/songs.json
 * where songs.json is [{"t": title, "a": artist, "s": seconds (0 if unknown), "u": address}, ...].
 */
fun main(args: Array<String>) {
    startUp()
    val songs = org.json.JSONArray(java.io.File(args.first()).readText())
    var found = 0
    for (i in 0 until songs.length()) {
        val o = songs.getJSONObject(i)
        val title = o.getString("t")
        val artist = o.getString("a")
        val url = o.getString("u")
        val t0 = System.currentTimeMillis()
        val ly = runCatching { kotlinx.coroutines.runBlocking { LyricsRepo.get(url, title, artist, o.optLong("s")) } }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        if (ly != null) found++
        val first = ly?.lines?.firstOrNull { it.text.isNotBlank() }?.text?.split(" ")?.take(5)?.joinToString(" ").orEmpty()
        println(
            "GW lyrics | %-34s | %-20s | %-8s | %3d lines | %-5s | %5d ms | %s".format(
                title.take(34), artist.take(20), ly?.source ?: "none", ly?.lines?.size ?: 0,
                if (ly?.wordTimed == true) "words" else if (ly?.synced == true) "lines" else "-", ms, first,
            ),
        )
    }
    println("GW lyrics found for $found of ${songs.length()} songs")
    System.exit(0)
}
