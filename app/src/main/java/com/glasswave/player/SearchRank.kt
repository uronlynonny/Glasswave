package com.glasswave.player

import java.text.Normalizer
import kotlin.math.log10

/**
 * Puts search results in a better order. A song scores for how well it matches the words typed,
 * for matching what people most often search with those words (YouTube Music's suggestions, so
 * "last last" leans to Burna Boy's), for how many times it's been played on YouTube, and for being
 * one you've played before. Songs hardly anyone has played sink to the bottom.
 *
 * When the words are a line from a song ([lyrics]), YouTube Music has already found the song from
 * its lyrics, so its own order counts most and the names aren't matched word for word.
 */
class SearchRank(query: String, popular: List<String>, private val played: Set<String>, private val lyrics: Boolean = false) {
    private val q = norm(query)
    private val words = q.split(' ').filter { it.isNotEmpty() }.distinct()

    // The words the most popular search adds to what was typed ("burna", "boy").
    private val intent: List<String> = popular.map(::norm)
        .firstOrNull { it.startsWith(q) && it.length > q.length }
        ?.split(' ')?.filter { it.isNotEmpty() && it !in words }.orEmpty()

    fun score(s: SongItem, index: Int): Double {
        val t = s.track
        val title = norm(t.title)
        val bare = norm(t.title.replace(extras, ""))
        val all = (title + " " + norm(t.artist)).split(' ').toSet()
        var score = 0.0
        if (lyrics) {
            score += 0.9 * (if (s.plays > 0) log10(s.plays.toDouble()) else 4.5)
            if (t.url in played) score += 3.0
            return score + 12.0 * (1.0 - index / 40.0).coerceAtLeast(0.0)
        }
        if (words.isNotEmpty()) {
            // The last word may still be half typed.
            val hit = words.count { w -> w in all || (w == words.last() && all.any { it.startsWith(w) }) }
            score += 5.0 * hit / words.size
        }
        if (q.isNotEmpty()) {
            if (bare == q) score += 3.0
            else if (bare.startsWith(q)) score += 1.5
            if (title.contains(q) || (title + " " + norm(t.artist)).contains(q)) score += 2.0
        }
        if (intent.isNotEmpty()) score += 3.0 * intent.count { it in all } / intent.size
        score += 0.9 * (if (s.plays > 0) log10(s.plays.toDouble()) else 4.5)
        if (t.url in played) score += 3.0
        else if (s.plays in 1 until 20_000) score -= 5.0
        score += 2.0 * (1.0 - index / 60.0).coerceAtLeast(0.0) // YouTube's own order counts a little
        return score
    }

    /** Best first; songs with under a thousand plays always last. */
    fun sort(songs: List<SongItem>): List<SongItem> =
        songs.mapIndexed { i, s -> Triple(s, score(s, i), rare(s) && s.track.url !in played) }
            .sortedWith(compareBy<Triple<SongItem, Double, Boolean>> { it.third }.thenByDescending { it.second })
            .map { it.first }

    companion object {
        private val extras = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val marks = Regex("""\p{M}+""")
        private val junk = Regex("""[^\p{L}\p{N} ]+""")
        private val spaces = Regex("""\s+""")

        fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(marks, "").replace("&", " and ").replace("'", "").replace(junk, " ")
            .replace(spaces, " ").trim()

        /**
         * True when [query] looks like a line from the song YouTube Music put on top, not its name:
         * four or more words, and most of them aren't in the song's name or artist.
         */
        fun looksLikeLyrics(query: String, top: SongItem): Boolean {
            val words = norm(query).split(' ').filter { it.isNotEmpty() }.distinct()
            if (words.size < 4) return false
            val have = (norm(top.track.title) + " " + norm(top.track.artist)).split(' ').toSet()
            return words.count { it in have } * 2 < words.size
        }

        /** Played fewer than a thousand times on YouTube. */
        fun rare(s: SongItem) = s.plays in 1 until 1_000

        /** The same song (say its song and video versions): same name and main artist. */
        fun same(a: SongItem, b: SongItem) = a.track.url == b.track.url ||
            (norm(a.track.title.replace(extras, "")) == norm(b.track.title.replace(extras, "")) &&
                norm(a.track.artist.substringBefore(',')) == norm(b.track.artist.substringBefore(',')))
    }
}
