// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flow: a personal mix that plays like a DJ set, without a voice. It strings together short
 * segments built from what you play and like ("On repeat", "Fresh finds", "Throwback"...),
 * announces each one on screen as it starts, lets you skip to the next with "Switch it up",
 * and keeps adding new segments for as long as you listen.
 */
object Flow {
    data class Segment(val title: String, val blurb: String, val tracks: List<Track>)

    var active by mutableStateOf(false)
        private set
    var building by mutableStateOf(false)
        private set
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set
    /** The segment playing now. */
    var current by mutableIntStateOf(0)
        private set

    val now: Segment? get() = segments.getOrNull(current)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val segmentOf = HashMap<String, Int>()
    private var extending = false
    private val heard = LinkedHashSet<String>()

    /** Builds a set and starts playing. [fallback] seeds it when you haven't played anything yet. */
    fun start(ps: PlayerState, fallback: List<Track>) {
        if (building) return
        building = true
        scope.launch {
            try {
                heard.clear()
                val built = buildSegments(seeds(fallback), first = true)
                if (built.isEmpty()) {
                    Menus.say("Play a few songs first, then try Flow")
                    return@launch
                }
                segments = built
                segmentOf.clear()
                built.forEachIndexed { i, s -> s.tracks.forEach { segmentOf[it.url] = i; heard += it.url } }
                current = 0
                active = true
                ps.playList(built.flatMap { it.tracks }, 0, fromFlow = true)
                Menus.say("Flow · ${built[0].title}")
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Flow failed", e)
                Menus.say("Couldn't start Flow. Check your internet.")
            } finally {
                building = false
            }
        }
    }

    fun stop() {
        active = false
        segments = emptyList()
        segmentOf.clear()
    }

    /** Skips to the start of the next segment. */
    fun switchUp(ps: PlayerState) {
        if (!active) return
        val next = segments.getOrNull(current + 1)?.tracks?.firstOrNull() ?: run {
            Menus.say("Lining up more…")
            return
        }
        ps.playUrl(next.url)
    }

    /** Called when the song changes: announces new segments and adds more near the end. */
    fun onTrack(url: String?, ps: PlayerState) {
        if (!active || url == null) return
        val seg = segmentOf[url] ?: return
        if (seg != current) {
            current = seg
            segments.getOrNull(seg)?.let { Menus.say("Flow · ${it.title}") }
        }
        if (current >= segments.size - 2 && !extending) extend(ps)
    }

    private fun extend(ps: PlayerState) {
        extending = true
        scope.launch {
            try {
                val recent = segments.takeLast(2).flatMap { it.tracks }.shuffled().take(4)
                val more = buildSegments(recent + History.tracks.take(10), first = false)
                if (!active || more.isEmpty()) return@launch
                val base = segments.size
                more.forEachIndexed { i, s -> s.tracks.forEach { segmentOf[it.url] = base + i; heard += it.url } }
                segments = segments + more
                ps.appendTracks(more.flatMap { it.tracks })
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Flow couldn't add more", e)
            } finally {
                extending = false
            }
        }
    }

    private suspend fun seeds(fallback: List<Track>): List<Track> {
        val liked = Library.tracks(Library.LIKED_ID)
        return (History.tracks.toList() + liked).ifEmpty { fallback }
    }

    private suspend fun radioOf(t: Track?): List<Track> = if (t == null) emptyList() else withContext(Dispatchers.IO) {
        runCatching {
            val id = YtRepo.videoIdFor(t.url) ?: return@runCatching emptyList()
            YtMusicApi.radio(id)
        }.getOrDefault(emptyList())
    }

    /** Shuffles, then spreads songs out so the same artist never plays twice in a row. */
    private fun mix(list: List<Track>): List<Track> {
        val pool = list.shuffled().toMutableList()
        val out = mutableListOf<Track>()
        while (pool.isNotEmpty()) {
            val last = out.lastOrNull()?.artist?.substringBefore(",")?.trim()
            val pick = pool.firstOrNull { it.artist.substringBefore(",").trim() != last } ?: pool.first()
            pool.remove(pick)
            out += pick
        }
        return out
    }

    private fun fresh(list: List<Track>, used: MutableSet<String>, n: Int, allowHeard: Boolean = false): List<Track> {
        val out = mix(list.filter { it.url !in used && (allowHeard || it.url !in heard) }.distinctBy { it.title.lowercase() }).take(n)
        used += out.map { it.url }
        return out
    }

    /**
     * Builds the segments. Nothing here is in a fixed order: the seeds are drawn at random from what
     * you play, each segment mixes several radios, songs are shuffled and artists spread out, and
     * the segments themselves come in a different order each time (On repeat always opens a set).
     */
    private suspend fun buildSegments(seeds: List<Track>, first: Boolean): List<Segment> = coroutineScope {
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        val history = History.tracks.toList()
        val liked = Library.tracks(Library.LIKED_ID)
        // Your top artists lately, and a few random seeds from what you've been playing.
        val artistCounts = history.groupingBy { it.artist.substringBefore(",").trim() }.eachCount().filterKeys { it.isNotBlank() }
        val topArtists = artistCounts.entries.sortedByDescending { it.value }.take(3).map { it.key }
        val topArtist = topArtists.firstOrNull()
        val recentPool = seeds.take(30)
        val topSeeds = topArtists.mapNotNull { a -> history.filter { it.artist.startsWith(a) }.randomOrNull() }.ifEmpty { listOf(recentPool.random()) }
        val freshSeeds = recentPool.shuffled().take(3)
        val wildSeeds = (liked.ifEmpty { seeds }).shuffled().take(2)

        // All the radios at once.
        val radios = (topSeeds + freshSeeds + wildSeeds).distinctBy { it.url }.map { s -> s to async { radioOf(s) } }
            .associate { (s, d) -> s.url to d.await() }
        fun radio(of: List<Track>) = of.flatMap { radios[it.url] ?: emptyList() }.distinctBy { it.url }.shuffled()
        val topRadio = radio(topSeeds)
        val freshRadio = radio(freshSeeds)
        val wildRadio = radio(wildSeeds)

        val used = HashSet<String>()
        val out = mutableListOf<Segment>()
        if (first) {
            val byTop = topRadio.filter { t -> topArtists.any { a -> t.artist.contains(a, true) } }
            val rotation = fresh(history.take(12).shuffled().take(3) + byTop + topRadio, used, 6, allowHeard = true)
            if (rotation.size >= 3) out += Segment("On repeat", if (topArtist != null) "Opening with  and the songs you keep coming back to" else "The songs you keep coming back to", rotation)
        }
        val rest = mutableListOf<Segment>()
        val newOnes = fresh(freshRadio, used, 6)
        if (newOnes.size >= 3) rest += Segment("Fresh finds", "New to you, close to what you've been playing", newOnes)
        val old = fresh(history.drop(15).shuffled(), used, 5, allowHeard = true)
        if (old.size >= 3) rest += Segment("Throwback", "Songs you haven't played in a while", old)
        val likedPicks = fresh(liked.shuffled(), used, 5, allowHeard = true)
        if (likedPicks.size >= 3) rest += Segment("From your likes", "A few you hearted", likedPicks)
        val wild = fresh(wildRadio, used, 6)
        if (wild.size >= 3) rest += Segment("Wildcard", "Something a little different", wild)
        val deeper = fresh(topRadio, used, 6)
        if (deeper.size >= 3) rest += Segment("Deeper cuts", "More in the same vein", deeper)
        val blend = fresh((freshRadio + wildRadio + topRadio).shuffled(), used, 7)
        if (blend.size >= 4) rest += Segment("The blend", "A bit of everything you like, mixed up", blend)
        out + rest.shuffled()
    }
}