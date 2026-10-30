// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Home's song shelves, made from your own taste and different on every load:
 *  - "Picked for you": songs like your most played ones (a few of them picked at random each time,
 *    so the shelf keeps changing), mixed with YouTube Music's own picks.
 *  - "Because you like …": more from the world of one of your top artists.
 *  - "Your top songs": what you've played most this month, to jump back in.
 * Songs you played in the last few plays are left out, so it never just repeats your history.
 */
object ForYou {
    suspend fun shelves(): List<Section> = coroutineScope {
        val month = Stats.summary(Stats.Period.MONTH)
        val all = Stats.summary(Stats.Period.ALL)
        learnTaste(month, all)
        // Your most played (stats), or what you played lately if there are no stats yet.
        val favourites = (month.topSongs + all.topSongs).map { it.track }.distinctBy { it.url }.take(25)
            .ifEmpty { History.tracks.toList().take(25) }
        if (favourites.isEmpty()) return@coroutineScope emptyList()
        val justHeard = History.tracks.take(8).map { it.url }.toSet()

        // A different handful of seeds every time.
        val seeds = favourites.shuffled().take(3)
        val artists = (month.topArtists + all.topArtists).map { it.name }.distinct().take(5)
        val artist = artists.randomOrNull()
        val artistSeed = artist?.let { a -> favourites.filter { it.artist.startsWith(a) }.randomOrNull() }

        suspend fun radio(t: Track?): List<Track> = if (t == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { YtRepo.videoIdFor(t.url)?.let { NotInterested.thin(YtMusicApi.radio(it)) } ?: emptyList() }.getOrDefault(emptyList())
        }
        val radios = seeds.map { s -> async { radio(s) } }
        val artistRadio = async { radio(artistSeed) }

        val picked = (radios.awaitAll().flatMap { it.drop(1).shuffled().take(9) } + favourites.shuffled().take(4))
            .distinctBy { YtRepo.songKey(it.title, it.artist) }
            .filter { it.url !in justHeard }
            .shuffled()
            .take(24)
        val out = mutableListOf<Section>()
        if (picked.size >= 4) out += Section("Picked for you", picked.map { SongItem(it, it.artist) })
        val byArtist = artistRadio.await().filter { it.url !in justHeard }.distinctBy { YtRepo.songKey(it.title, it.artist) }.shuffled().take(16)
        if (artist != null && byArtist.size >= 4) out += Section("Because you like $artist", byArtist.map { SongItem(it, it.artist) })
        val top = month.topSongs.take(16).map { it.track }
        if (top.size >= 4) out += Section("Your top songs this month", top.map { SongItem(it, it.artist) })
        out
    }

    /**
     * The artists you really listen to, lowercased: played more than once (stats), liked or
     * downloaded. (Not everything that ever played: Autoplay's picks would count too.)
     * Worked out with your shelves on each Home load.
     */
    @Volatile private var taste: Set<String> = emptySet()

    private suspend fun learnTaste(month: Stats.Summary, all: Stats.Summary) {
        val liked = runCatching { Library.tracks(Library.LIKED_ID) }.getOrDefault(emptyList())
        taste = ((month.topArtists + all.topArtists).filter { it.plays >= 2 }.map { it.name } +
            (liked + Downloads.tracks.toList()).map { it.artist })
            .flatMap { it.split(',', '&') }
            .map { it.trim().lowercase() }
            .filter { it.length >= 3 }
            .toSet()
    }

    /**
     * Only YouTube shelves that fit what you play: a shelf stays if it has your artists in it.
     * YouTube's general shelves for your country (charts, "All hits"...) go, so Home is about your
     * music even when YouTube Music isn't personalising it (signed out, or the sign-in expired).
     * With too little listening to tell yet, everything stays.
     */
    private fun yourShelves(yt: List<Section>): List<Section> {
        val known = taste
        if (known.size < 3) return yt
        fun yours(i: MusicItem): Boolean {
            val text = ((i as? SongItem)?.track?.artist.orEmpty() + " " + i.subtitle + " " + i.title).lowercase()
            return known.any { text.contains(it) }
        }
        return yt.filter { s ->
            val hits = s.items.count { yours(it) }
            hits >= 4 || (hits > 0 && hits * 2 >= s.items.size)
        }
    }

    /**
     * Mixes your shelves into YouTube Music's Home: "Picked for you" absorbs YouTube's quick picks,
     * every song shelf is reshuffled so it looks different on each load, and the rest follow.
     */
    fun mix(yt: List<Section>, mine: List<Section>): List<Section> {
        fun isSongs(s: Section) = s.items.count { it is SongItem } * 2 > s.items.size
        val shuffledYt = yourShelves(yt).map { s -> if (isSongs(s)) s.copy(items = s.items.shuffled()) else s }.toMutableList()
        val minePicked = mine.firstOrNull { it.title == "Picked for you" }
        if (minePicked != null) {
            val i = shuffledYt.indexOfFirst { it.title == "Picked for you" }
            if (i >= 0) {
                val merged = (minePicked.items + shuffledYt[i].items).distinctBy { m -> (m as? SongItem)?.track?.let { YtRepo.songKey(it.title, it.artist) } ?: m.title.lowercase() }.shuffled().take(28)
                shuffledYt[i] = Section("Picked for you", merged)
            } else {
                shuffledYt.add(0, minePicked)
            }
        }
        val rest = mine.filter { it !== minePicked }
        val at = if (shuffledYt.isEmpty()) 0 else minOf(2, shuffledYt.size)
        shuffledYt.addAll(at, rest)
        return shuffledYt
    }
}
