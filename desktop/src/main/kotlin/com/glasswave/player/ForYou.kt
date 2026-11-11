// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 *  - "New from your artists": this year's albums and singles from the artists you play most.
 *  - "Forgotten favourites": songs you played a lot but haven't heard for a month or more.
 *  - "Playlists for you": playlists people made around a couple of your top artists.
 * Plus "Your mixes" for Made for you: two mixes around groups of your top artists and a Discover
 * mix of artists you haven't played. All of it works without signing in to YouTube Music.
 * Songs you played in the last few plays are left out, so it never just repeats your history.
 */
object ForYou {
    data class Mix(val title: String, val subtitle: String, val tracks: List<Track>, val color: Long, val discover: Boolean = false)

    /** Home's mixes (Made for you), made with the shelves. */
    var mixes by mutableStateOf<List<Mix>>(emptyList())
        private set

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
        val topArtists = (month.topArtists + all.topArtists).map { it.name }.distinct()
        val artists = topArtists.take(5)
        val artist = artists.randomOrNull()
        val artistSeed = artist?.let { a -> favourites.filter { it.artist.startsWith(a) }.randomOrNull() }

        suspend fun radio(t: Track?): List<Track> = if (t == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { YtRepo.videoIdFor(t.url)?.let { NotInterested.thin(YtMusicApi.radio(it)) } ?: emptyList() }.getOrDefault(emptyList())
        }
        val radios = seeds.map { s -> async { radio(s) } }
        val artistRadio = async { radio(artistSeed) }
        // Your mixes: your top artists in two groups, each mix made from their songs' radios.
        val groups = listOf(topArtists.take(6).filterIndexed { i, _ -> i % 2 == 0 }, topArtists.take(6).filterIndexed { i, _ -> i % 2 == 1 })
            .filter { it.size >= 2 }
        val groupRadios = groups.map { g ->
            g.map { a -> favourites.filter { it.artist.startsWith(a) }.randomOrNull() }.map { s -> async { listOfNotNull(s) + radio(s).drop(1) } }
        }
        val fresh = async { newFromArtists(topArtists.take(4)) }
        val forgotten = async { forgottenFavourites(justHeard) }
        val peoples = async { CommunityPlaylists.forArtists(topArtists.take(4).shuffled().take(2)) }

        val picked = (radios.awaitAll().flatMap { it.drop(1).shuffled().take(9) } + favourites.shuffled().take(4))
            .distinctBy { YtRepo.songKey(it.title, it.artist) }
            .filter { it.url !in justHeard }
            .shuffled()
            .take(24)
        val out = mutableListOf<Section>()
        if (picked.size >= 4) out += Section("Picked for you", picked.map { SongItem(it, it.artist) })
        val byArtist = artistRadio.await().filter { it.url !in justHeard }.distinctBy { YtRepo.songKey(it.title, it.artist) }.shuffled().take(16)
        if (artist != null && byArtist.size >= 4) out += Section("Because you like $artist", byArtist.map { SongItem(it, it.artist) })
        fresh.await().takeIf { it.size >= 2 }?.let { out += Section("New from your artists", it) }
        peoples.await().takeIf { it.size >= 4 }?.let { out += Section("Playlists for you", it) }
        forgotten.await().takeIf { it.size >= 4 }?.let { f -> out += Section("Forgotten favourites", f.map { SongItem(it, it.artist) }) }
        val top = month.topSongs.take(16).map { it.track }
        if (top.size >= 4) out += Section("Your top songs this month", top.map { SongItem(it, it.artist) })

        val colours = listOf(0xFFB388FF, 0xFFFFD166)
        val made = groups.mapIndexedNotNull { i, g ->
            val lists = groupRadios[i].awaitAll()
            // Take turns between the artists' radios, so one artist doesn't fill the mix.
            val woven = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { k -> lists.mapNotNull { it.getOrNull(k) } }
                .distinctBy { YtRepo.songKey(it.title, it.artist) }.take(30)
            if (woven.size < 8) null else Mix("Mix ${i + 1}", g.take(2).joinToString(", ") + " and more", woven, colours[i])
        }.toMutableList()
        val heard = taste
        val discover = (radios.awaitAll().flatten() + groupRadios.flatten().map { it.await() }.flatten() + artistRadio.await())
            .filter { t -> val a = t.artist.lowercase(); heard.none { a.contains(it) } }
            .distinctBy { YtRepo.songKey(it.title, it.artist) }
            .shuffled().take(30)
        if (discover.size >= 8) made += Mix("Discover", "Artists new to you", discover, 0xFFFF8A65, discover = true)
        android.os.Handler(android.os.Looper.getMainLooper()).post { mixes = made }
        out
    }

    /** Songs you played 5 or more times that haven't come on for a month: most played first, shuffled a little. */
    private suspend fun forgottenFavourites(justHeard: Set<String>): List<Track> = withContext(Dispatchers.IO) {
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        Stats.allPlays().groupBy { it.track.url }
            .mapNotNull { (url, plays) -> if (plays.size >= 5 && plays.maxOf { it.at } < monthAgo && url !in justHeard) plays.maxBy { it.at }.track to plays.size else null }
            .sortedByDescending { it.second }
            .take(24).map { it.first }.shuffled().take(16)
    }

    @Volatile private var freshCache: Triple<Long, List<String>, List<MusicItem>>? = null

    /** This year's albums and singles from [names] (their artist pages), at most a few each. Cached for 6 hours. */
    private suspend fun newFromArtists(names: List<String>): List<MusicItem> = withContext(Dispatchers.IO) {
        if (names.isEmpty()) return@withContext emptyList()
        freshCache?.let { (at, who, items) -> if (who == names && System.currentTimeMillis() - at < 6 * 60 * 60 * 1000L) return@withContext items }
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        // Early in the year, last year's releases still count as new.
        val years = if (cal.get(java.util.Calendar.MONTH) < 2) listOf("$year", "${year - 1}") else listOf("$year")
        val items = coroutineScope {
            names.map { name ->
                async {
                    runCatching {
                        val artist = YtMusicApi.search(name, YtMusicApi.Filter.ARTISTS).sections.flatMap { it.items }
                            .filterIsInstance<ArtistItem>().firstOrNull { it.title.equals(name, ignoreCase = true) } ?: return@runCatching emptyList()
                        YtMusicApi.page(artist).sections
                            .filter { s -> s.title.contains("album", true) || s.title.contains("single", true) || s.title.contains("EP", false) }
                            .flatMap { it.items }.filterIsInstance<AlbumItem>()
                            .filter { a -> years.any { a.subtitle.contains(it) } }
                            .take(3)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.browseId }
        }
        freshCache = Triple(System.currentTimeMillis(), names, items)
        items
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
