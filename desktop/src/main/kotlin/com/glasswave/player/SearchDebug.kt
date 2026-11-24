// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlinx.coroutines.runBlocking

/**
 * For checks only: runs real searches the way the All search does (signed out, nothing you've
 * played counts) and prints what comes out on top, and every song's YouTube plays and score.
 *   ./gradlew -p desktop searchDebug -Pq="call me by your name;last last;essence"
 */
fun main(args: Array<String>) = runBlocking {
    startUp()
    for (q in args.joinToString(" ").split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
        println("GW search ===== $q")
        // What shows under the box while you type (YouTube Music's suggestions, as they come).
        for (part in listOf(q.take((q.length * 0.6).toInt()), q)) {
            val sug = runCatching { YtMusicApi.suggestions(part) }.getOrNull() ?: continue
            fun show(l: List<MusicItem>) = l.take(6).joinToString(" / ") { i ->
                i.title + ((i as? SongItem)?.let { " - ${it.track.artist} (${it.plays})" } ?: " [${i::class.simpleName}]")
            }
            println("GW search typing \"$part\" from YouTube: " + show(sug.items))
            println("GW search typing \"$part\" as shown:    " + show(SearchRank(part, sug.queries).suggestions(sug.items)))
        }
        if (System.getProperty("gw.typingOnly") != null) continue
        val page = runCatching { YtMusicApi.searchAll(q, emptyMap()) }.getOrElse { println("GW search failed: $it"); continue }
        for (s in page.sections.take(3)) {
            println("GW search [${s.title}]")
            s.items.take(8).forEach { i ->
                val extra = (i as? SongItem)?.let { " | ${it.track.artist} | plays=${it.plays}" } ?: " (${i::class.simpleName})"
                println("GW search    ${i.title}$extra")
            }
        }
        val popular = runCatching { YtMusicApi.suggestions(q).queries }.getOrNull().orEmpty()
        println("GW search popular searches: ${popular.take(4)}")
        val songs = runCatching { YtMusicApi.search(q, YtMusicApi.Filter.SONGS).sections.flatMap { it.items }.filterIsInstance<SongItem>() }.getOrNull().orEmpty()
        val rank = SearchRank(q, popular)
        println("GW search Songs search, YouTube Music's order, with scores:")
        songs.take(12).forEachIndexed { i, s ->
            println("GW search    %2d %6.2f  %s | %s | plays=%d".format(i, rank.score(s, i), s.track.title, s.track.artist, s.plays))
        }
    }
    System.exit(0)
}
