package com.glasswave.player

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.log10

/**
 * Puts search results in a better order. A song scores for how well it matches the words typed,
 * for matching what people most often search with those words (YouTube Music's suggestions, so
 * "last last" leans to Burna Boy's), for how many times it's been played on YouTube, and for being
 * one you know ([known]: played or picked from a search before; the more, the higher). Songs
 * hardly anyone has played sink to the bottom.
 *
 * A song you know well comes first as soon as what's typed is the start of its name (or of a
 * search you found it with), even after only a word or two ([favourite]).
 *
 * When the words are a line from a song ([lyrics]), YouTube Music has already found the song from
 * its lyrics, so its own order counts most and the names aren't matched word for word.
 */
class SearchRank(
    query: String,
    popular: List<String>,
    private val known: Map<String, SearchPicks.Known> = emptyMap(),
    private val lyrics: Boolean = false,
    /** Artists you know, by [SearchPicks.artistKey]. */
    private val artists: Map<String, SearchPicks.KnownArtist> = emptyMap(),
) {
    private val q = norm(query)
    private val words = q.split(' ').filter { it.isNotEmpty() }.distinct()

    // The words the most popular search adds to what was typed ("burna", "boy").
    private val intent: List<String> = popular.map(::norm)
        .firstOrNull { it.startsWith(q) && it.length > q.length }
        ?.split(' ')?.filter { it.isNotEmpty() && it !in words }.orEmpty()

    // Songs you know, also by name and main artist, so the song version counts when you played the video.
    private val knownByName: Map<String, SearchPicks.Known> by lazy {
        known.values.groupBy { key(it.track) }.mapValues { (_, l) -> l.maxBy { it.weight } }
    }

    /** What you know about this song (or its song/video twin), or null. */
    fun knownOf(t: Track): SearchPicks.Known? = known[t.url] ?: knownByName[key(t)]

    /**
     * True when [t] is a song you know well ([k]) and what's typed (3 letters at least) is the
     * start of its name, of its name and artist, or of a search you found it with. Word by word,
     * the last word maybe half typed: "slide" or "slide h" for "Slide Her (feat. X)".
     */
    fun favourite(t: Track, k: SearchPicks.Known? = knownOf(t)): Boolean {
        if (k == null || !k.often || q.length < 3) return false
        val bare = norm(t.title.replace(extras, ""))
        return bare.startsWith(q) || norm(t.title).startsWith(q) || "$bare ${norm(t.artist)}".startsWith(q) ||
            k.queries.any { norm(it).startsWith(q) }
    }

    /** The songs you know well that start like what's typed, best known first. */
    fun favourites(): List<SearchPicks.Known> =
        known.values.filter { favourite(it.track, it) }.sortedByDescending { it.weight }.distinctBy { key(it.track) }

    /**
     * [found] plus up to 3 of your [favourites] it doesn't have (a few words of a name often
     * aren't enough for YouTube Music to find the song you mean).
     */
    fun withMine(found: List<SongItem>): List<SongItem> {
        val have = found.mapTo(HashSet()) { key(it.track) }
        return found + favourites().filter { key(it.track) !in have }.take(3).map { SongItem(it.track, it.track.artist) }
    }

    /**
     * True when [k] is an artist you know well (opened their page twice, say, or played their
     * songs) and what's typed (3 letters at least) is the start of their name, or of a search
     * you found them with: "bur" for Burna Boy.
     */
    fun favouriteArtist(k: SearchPicks.KnownArtist): Boolean {
        if (!k.often || q.length < 3) return false
        return norm(k.name).startsWith(q) || SearchPicks.artistKey(k.name).startsWith(q) || k.queries.any { norm(it).startsWith(q) }
    }

    /** The artists you know well whose name starts like what's typed, best known first. */
    fun favouriteArtists(): List<SearchPicks.KnownArtist> =
        artists.values.filter { favouriteArtist(it) }.sortedByDescending { it.weight }

    /**
     * The artist you know best whose name starts like what's typed: as it is in [items] (the
     * search's own results), or from the page you opened before when the results don't have them.
     */
    fun artistFor(items: List<MusicItem>): ArtistItem? {
        val found = items.filterIsInstance<ArtistItem>()
        for (k in favouriteArtists()) {
            val key = SearchPicks.artistKey(k.name)
            found.firstOrNull { (k.artist != null && it.browseId == k.artist.browseId) || SearchPicks.artistKey(it.title) == key }?.let { return it }
            k.artist?.let { return it }
        }
        return null
    }

    /** How well you know [a] (0 when not at all). */
    fun artistWeight(a: ArtistItem): Double = artists[SearchPicks.artistKey(a.title)]?.weight ?: 0.0

    /** How well you know the song [t] (0 when not at all). */
    fun songWeight(t: Track): Double = knownOf(t)?.weight ?: 0.0

    /**
     * The All search's top result: a song or an artist you know well whose name starts like
     * what's typed (whichever you know better), otherwise [usual] (what the search picked anyway).
     */
    fun top(best: SongItem?, artist: ArtistItem?, usual: MusicItem?): MusicItem? {
        val song = best?.takeIf { favourite(it.track) }
        return when {
            artist != null && (song == null || artistWeight(artist) >= songWeight(song.track)) -> artist
            song != null -> song
            else -> usual
        }
    }

    fun score(s: SongItem, index: Int): Double {
        val t = s.track
        val title = norm(t.title)
        val bare = norm(t.title.replace(extras, ""))
        val all = (title + " " + norm(t.artist)).split(' ').toSet()
        var score = 0.0
        if (lyrics) {
            score += 0.9 * (if (s.plays > 0) log10(s.plays.toDouble()) else 4.5)
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
        // A song you know only counts for searches it's about, so songs from your history that
        // YouTube fills the list with don't climb up. The more you've played it, the more it counts.
        val k = knownOf(t)
        if (k != null && relevant(s)) score += 3.0 + ln(1.0 + k.weight).coerceAtMost(3.0)
        else if (s.plays in 1 until 20_000) score -= 5.0
        score += 2.0 * (1.0 - index / 60.0).coerceAtLeast(0.0) // YouTube's own order counts a little
        return score
    }

    /**
     * True when the song is about what was typed: at least half the words are in its name or
     * artist, or the words appear together. YouTube Music pads a search with other songs (often
     * ones you've played); those aren't.
     */
    fun relevant(s: SongItem): Boolean {
        if (lyrics || words.isEmpty()) return true
        val title = norm(s.track.title)
        val artist = norm(s.track.artist)
        if (title.contains(q) || "$title $artist".contains(q)) return true
        val all = "$title $artist".split(' ').toSet()
        val hit = words.count { w -> w in all || (w == words.last() && all.any { it.startsWith(w) }) }
        return hit * 2 >= words.size
    }

    private class Ranked(val song: SongItem, val score: Double, val tier: Int, val fav: Int)

    /**
     * Best first: songs you know well that start like what's typed (at most 3, best known first),
     * then the rest by score; songs with under a thousand plays next to last, songs that don't
     * match the search last.
     */
    fun sort(songs: List<SongItem>): List<SongItem> {
        val favs = songs.mapNotNull { s -> knownOf(s.track)?.takeIf { favourite(s.track, it) }?.let { s to it } }
            // Best known first; of a song and its video twin, the one you played.
            .sortedWith(compareByDescending<Pair<SongItem, SearchPicks.Known>> { it.second.weight }.thenBy { if (it.first.track.url in known) 0 else 1 })
            .map { it.first }.distinctBy { key(it.track) }.take(3)
        val favRank = favs.withIndex().associate { (i, s) -> s.track.url to i }
        return songs.mapIndexed { i, s ->
            val fav = favRank[s.track.url]
            val tier = when {
                fav != null -> -1
                !relevant(s) -> 2
                rare(s) && knownOf(s.track) == null -> 1
                else -> 0
            }
            Ranked(s, score(s, i), tier, fav ?: 0)
        }
            .sortedWith(compareBy<Ranked> { it.tier }.thenBy { it.fav }.thenByDescending { it.score })
            .map { it.song }
    }

    companion object {
        private val extras = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val marks = Regex("""\p{M}+""")
        private val junk = Regex("""[^\p{L}\p{N} ]+""")
        private val spaces = Regex("""\s+""")

        fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(marks, "").replace("&", " and ").replace("'", "").replace(junk, " ")
            .replace(spaces, " ").trim()

        /** A song's name (without "(feat. ...)" and the like) and main artist: the same for its song and video versions. */
        fun key(t: Track) = norm(t.title.replace(extras, "")) + "|" + norm(t.artist.substringBefore(','))

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

        /** The same artist, album or playlist (or the same song). */
        fun sameItem(a: MusicItem, b: MusicItem): Boolean = when {
            a is ArtistItem && b is ArtistItem -> a.browseId == b.browseId || SearchPicks.artistKey(a.title) == SearchPicks.artistKey(b.title)
            a is AlbumItem && b is AlbumItem -> a.browseId == b.browseId
            a is PlaylistItem && b is PlaylistItem -> a.browseId == b.browseId
            a is SongItem && b is SongItem -> same(a, b)
            else -> a == b
        }

        /**
         * The shelves under the top result ([shelves]: artists, albums, playlists...): [top] taken
         * out of them; YouTube Music's own top result [ytTop] put back on its shelf when it's no
         * longer the top (so a search for an album still shows it); and [artist], an artist you
         * know, at the front of the artists.
         */
        fun shelves(shelves: List<Section>, ytTop: MusicItem?, top: MusicItem?, artist: ArtistItem?): List<Section> {
            val out = shelves.map { s -> if (top == null) s else s.copy(items = s.items.filterNot { sameItem(it, top) }) }.toMutableList()
            fun putFirst(item: MusicItem) {
                val word = when (item) { is ArtistItem -> "artist"; is AlbumItem -> "album"; is PlaylistItem -> "playlist"; else -> return }
                val i = out.indexOfFirst { it.title.contains(word, true) && it.items.any { x -> x::class == item::class } }
                    .takeIf { it >= 0 } ?: out.indexOfFirst { it.title.contains(word, true) }
                if (i >= 0) out[i] = out[i].copy(items = listOf(item) + out[i].items.filterNot { sameItem(it, item) })
                else out += Section(word.replaceFirstChar { it.uppercase() } + "s", listOf(item))
            }
            if (ytTop != null && ytTop !is SongItem && (top == null || !sameItem(ytTop, top))) putFirst(ytTop)
            if (artist != null && (top == null || !sameItem(artist, top))) putFirst(artist)
            return out
        }

        /** Played fewer than a thousand times on YouTube. */
        fun rare(s: SongItem) = s.plays in 1 until 1_000

        /** The same song (say its song and video versions): same name and main artist. */
        fun same(a: SongItem, b: SongItem) = a.track.url == b.track.url || key(a.track) == key(b.track)
    }
}
