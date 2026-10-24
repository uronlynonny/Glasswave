// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Remembers each Moods & genres tile's picture between launches, so the grid fills in instantly. */
object GenreArt {
    private var prefs: android.content.SharedPreferences? = null
    fun init(context: android.content.Context) { prefs = context.getSharedPreferences("genre_art", android.content.Context.MODE_PRIVATE) }
    fun cached(title: String): String? = prefs?.getString(title, null)
    fun save(title: String, url: String) { prefs?.edit()?.putString(title, url)?.apply() }
}

enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library"), SETTINGS("Settings") }

/** A page opened on top of a tab. */
sealed interface Route {
    data class Online(val item: MusicItem) : Route
    data class Saved(val info: PlaylistInfo) : Route
    /** Songs downloaded to the phone. */
    data object Offline : Route
    /** Your listening stats: top songs and artists, history. */
    data object Stats : Route
}

/** Loading state for anything fetched from the internet. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class MainViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
        private set
    val stack = mutableStateListOf<Route>()

    // Scroll positions survive switching tabs.
    val homeList = androidx.compose.foundation.lazy.LazyListState()
    val searchList = androidx.compose.foundation.lazy.LazyListState()
    val libGrid = androidx.compose.foundation.lazy.grid.LazyGridState()

    fun selectTab(t: Tab) {
        if (t == tab && stack.isNotEmpty()) stack.clear()
        tab = t
        stack.clear()
    }

    fun open(route: Route) {
        stack.add(route)
        if (route is Route.Saved) loadSaved(route.info)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    private fun failText(e: Exception) =
        if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) "No internet connection."
        else "Couldn't load this. YouTube may have changed and the app may need an update."

    /* ---- Home ---- */
    var home by mutableStateOf<Load<List<Section>>>(Load.Loading)
        private set
    var homeChips by mutableStateOf<List<YtMusicApi.Chip>>(emptyList())
        private set
    var homeChip by mutableStateOf<YtMusicApi.Chip?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    fun pickChip(c: YtMusicApi.Chip?) {
        homeChip = if (homeChip == c) null else c
        loadHome()
    }

    /** Pull-to-refresh: keeps the current feed on screen while the new one loads. */
    fun refreshHome() {
        if (refreshing) return
        refreshing = true
        loadHome(keep = true)
    }

    private var homeJob: Job? = null

    fun loadHome(keep: Boolean = false) {
        if (!keep) home = Load.Loading
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val old = home
            home = try {
                // YouTube's feed and your own shelves, fetched at the same time.
                val chip = homeChip
                val mineJob: kotlinx.coroutines.Deferred<List<Section>>? = if (chip == null) async {
                    try { ForYou.shelves() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList<Section>() }
                } else null
                val feed = YtMusicApi.home(chip?.params)
                if (homeChip == null || homeChips.isEmpty()) homeChips = feed.chips
                Load.Done(ForYou.mix(cleanHome(feed.sections), mineJob?.await() ?: emptyList()))
            } catch (e: CancellationException) {
                refreshing = false
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Glasswave", "Home failed", e)
                // A failed pull-to-refresh keeps what's already on screen.
                if (keep && old is Load.Done) old else Load.Failed(failText(e))
            }
            refreshing = false
        }
    }

    /* ---- Pages (albums, artists, playlists) ---- */
    private val pages = HashMap<MusicItem, Load<Page>>()
    var pagesVersion by mutableStateOf(0)
        private set

    /** Current state of a page. Reading [pagesVersion] makes screens refresh when it finishes loading. */
    fun pageState(item: MusicItem): Load<Page> {
        pagesVersion
        return pages[item] ?: Load.Loading
    }

    /** Starts loading a page unless it's loaded or loading already. */
    fun ensurePage(item: MusicItem) {
        if (pages[item].let { it != null && it !is Load.Failed }) return
        pages[item] = Load.Loading
        pagesVersion++
        viewModelScope.launch {
            pages[item] = try {
                Load.Done(YtMusicApi.page(item))
            } catch (e: CancellationException) {
                pages.remove(item)
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Glasswave", "Page failed for ${item.title}", e)
                Load.Failed(failText(e))
            }
            pagesVersion++
        }
    }

    fun retryPage(item: MusicItem) {
        pages.remove(item)
        ensurePage(item)
    }

    /* ---- Search ---- */
    var query by mutableStateOf("")
        private set
    var filter by mutableStateOf(YtMusicApi.Filter.ALL)
        private set
    var results by mutableStateOf<Load<List<Section>>?>(null)
        private set
    val recent = mutableStateListOf<String>().apply { addAll(Accounts.recentSearches) }
    private var searchJob: Job? = null

    /** Suggestions shown while typing; hidden once a search runs. */
    var suggestions by mutableStateOf<YtMusicApi.Suggestions?>(null)
        private set
    var showSuggestions by mutableStateOf(false)
        private set
    private var suggestJob: Job? = null

    /** More results for a single-type search, loaded as you scroll. */
    private var moreToken: Pair<String, Boolean>? = null
    var loadingMore by mutableStateOf(false)
        private set
    val canLoadMore get() = moreToken != null

    /** Moods & genres for browsing when the search box is empty. */
    var genres by mutableStateOf<List<YtMusicApi.Genre>>(emptyList())
        private set

    fun loadGenres() {
        if (genres.isNotEmpty()) return
        viewModelScope.launch { genres = runCatching { YtMusicApi.genres() }.getOrDefault(emptyList()) }
    }

    /** Which query the results on screen are for (results stay up while the next ones load). */
    var resultsFor by mutableStateOf("")
        private set
    /** A search is running; the old results stay on screen meanwhile. */
    var searching by mutableStateOf(false)
        private set
    var voiceOpen by mutableStateOf(false)

    /** Recent results, so going back to a search (or switching filters back) is instant. */
    private val searchCache = object : LinkedHashMap<String, Pair<List<Section>, Pair<String, Boolean>?>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<Section>, Pair<String, Boolean>?>>?) = size > 30
    }

    fun onQuery(q: String) {
        query = q
        suggestJob?.cancel()
        if (q.isBlank()) {
            searchJob?.cancel()
            results = null
            resultsFor = ""
            searching = false
            suggestions = null
            showSuggestions = false
            return
        }
        // Like Spotify: suggestions almost at once, then real results as soon as you pause typing.
        showSuggestions = true
        suggestJob = viewModelScope.launch {
            delay(120)
            suggestions = runCatching { YtMusicApi.suggestions(q.trim()) }.getOrNull() ?: suggestions
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(420)
            runSearch(remember = false)
        }
    }

    fun submitSearch() {
        searchJob?.cancel()
        suggestJob?.cancel()
        showSuggestions = false
        searchJob = viewModelScope.launch { runSearch(remember = true) }
    }

    /** Saves the current search in Recent (when you tap one of its results). */
    fun rememberQuery() {
        val q = query.trim()
        if (q.isEmpty()) return
        recent.remove(q)
        recent.add(0, q)
        while (recent.size > 12) recent.removeAt(recent.lastIndex)
        Accounts.recentSearches = recent.toList()
    }

    fun loadMoreResults() {
        val token = moreToken ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = YtMusicApi.searchMore(token)
                val now = (results as? Load.Done)?.value ?: emptyList()
                val extra = page.sections.flatMap { it.items }
                if (extra.isNotEmpty() && now.isNotEmpty()) {
                    // Add the new results to the end of the last list.
                    val last = now.last()
                    results = Load.Done(now.dropLast(1) + last.copy(items = last.items + extra))
                }
                moreToken = if (extra.isEmpty()) null else page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                moreToken = null
            } finally {
                loadingMore = false
            }
        }
    }

    fun pickRecent(q: String) {
        query = q
        submitSearch()
    }

    fun clearRecent() {
        recent.clear()
        Accounts.recentSearches = emptyList()
    }

    fun setSearchFilter(f: YtMusicApi.Filter) {
        filter = f
        if (query.isNotBlank()) submitSearch()
    }

    private suspend fun runSearch(remember: Boolean) {
        val q = query.trim()
        if (q.isEmpty()) return
        if (remember) rememberQuery()
        val key = "${filter.name}|${q.lowercase()}"
        searchCache[key]?.let { (sections, token) ->
            results = Load.Done(sections)
            moreToken = token
            resultsFor = q
            showSuggestions = false
            return
        }
        // Keep what's on screen while the new results load; only an empty screen shows placeholders.
        if (results !is Load.Done) results = Load.Loading
        searching = true
        val f = filter
        val fresh = try {
            val page = if (f == YtMusicApi.Filter.ALL) YtMusicApi.searchAll(q, History.tracks.map { it.url }.toSet())
            else YtMusicApi.search(q, f)
            moreToken = page.next
            if (page.sections.isNotEmpty()) searchCache[key] = page.sections to page.next
            if (page.sections.isEmpty()) backupSearch(q) else Load.Done(page.sections)
        } catch (e: CancellationException) {
            searching = false
            throw e
        } catch (e: Exception) {
            android.util.Log.e("Glasswave", "Search failed for \"$q\"", e)
            // YouTube Music's search didn't answer: try the other search engine before giving up.
            try {
                backupSearch(q)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                Load.Failed(failText(e))
            }
        }
        results = fresh
        resultsFor = q
        searching = false
        showSuggestions = false
    }

    /** Songs from the older YouTube search, used when YouTube Music's own search fails. */
    private suspend fun backupSearch(q: String): Load<List<Section>> {
        val songs = YtRepo.search(q)
        return Load.Done(if (songs.isEmpty()) emptyList() else listOf(Section("Songs", songs.map { SongItem(it, it.artist) })))
    }

    /* ---- Library ---- */
    val playlists = Library.playlists
    var libFilter by mutableStateOf<Source?>(null)
    var savedTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    /** True once the open playlist's songs have been read (an empty playlist is then really empty). */
    var savedLoaded by mutableStateOf(false)
        private set

    private fun loadSaved(p: PlaylistInfo) {
        savedTracks = emptyList()
        savedLoaded = false
        reloadSaved(p.id)
    }

    /** Re-reads an open playlist after its songs changed. */
    fun reloadSaved(id: String) {
        viewModelScope.launch {
            savedTracks = Library.tracks(id)
            savedLoaded = true
        }
    }

    /* ---- Pictures for the Moods & genres tiles ---- */
    val genreArt = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val artAsked = HashSet<String>()
    private val artSlots = kotlinx.coroutines.sync.Semaphore(4)

    fun loadGenreArt(g: YtMusicApi.Genre) {
        if (!artAsked.add(g.title)) return
        GenreArt.cached(g.title)?.let {
            genreArt[g.title] = it
            return
        }
        viewModelScope.launch {
            val art = artSlots.withPermit { runCatching { YtMusicApi.genreArt(g.item) }.getOrNull() }
            if (art != null) {
                genreArt[g.title] = art
                GenreArt.save(g.title, art)
            } else {
                artAsked.remove(g.title) // try again next time
            }
        }
    }

    /* ---- Accounts and sync ---- */
    var ytSignedIn by mutableStateOf(Accounts.ytSignedIn)
        private set
    var spSignedIn by mutableStateOf(Accounts.spotifySignedIn)
        private set
    var spClientId by mutableStateOf(Accounts.spotifyClientId)
    var showYtLogin by mutableStateOf(false)
    var syncing by mutableStateOf(false)
        private set
    var syncStatus by mutableStateOf<String?>(null)
        private set
    private var spLogin: SpotifyApi.Login? = null

    init {
        loadHome()
        // Refresh in the background when the last sync is over 12 hours old.
        if ((ytSignedIn || spSignedIn) && System.currentTimeMillis() - Accounts.lastSync > 12 * 3600_000L) syncAll()
    }

    fun onYtCookie(cookie: String) {
        Accounts.ytCookie = cookie
        ytSignedIn = true
        showYtLogin = false
        loadHome() // personalized now
        syncAll(spotify = false)
        Profile.refresh()
        Sync.start() // find this account's other devices
    }

    /** The profile sheet opened from your picture on Home. */
    var profileOpen by mutableStateOf(false)

    /** A Settings page to open next time Settings shows (from shortcuts elsewhere in the app). */
    var settingsJump by mutableStateOf<String?>(null)

    fun openSettings(page: String) {
        settingsJump = page
        selectTab(Tab.SETTINGS)
    }

    fun signOutYt() {
        Sync.stop()
        Accounts.ytCookie = null
        YtSignIn.forget() // the sign-in window's Google session too, so the next sign-in starts fresh
        ytSignedIn = false
        Profile.clear()
        Library.clearSource(Source.YT)
        loadHome()
    }

    /** Starts Spotify sign-in and calls [openInBrowser] with Spotify's sign-in page. */
    fun startSpotifyLogin(openInBrowser: (String) -> Unit) {
        Accounts.spotifyClientId = spClientId
        if (spClientId.isBlank()) {
            syncStatus = "Paste your Spotify Client ID first."
            return
        }
        spLogin?.cancel()
        viewModelScope.launch {
            val login = try {
                withContext(Dispatchers.IO) { SpotifyApi.startLogin(spClientId.trim()) }
            } catch (e: Exception) {
                syncStatus = "Couldn't start Spotify sign-in."
                return@launch
            }
            spLogin = login
            syncStatus = "Finish signing in to Spotify in your browser..."
            openInBrowser(login.authUrl)
            try {
                withContext(Dispatchers.IO) {
                    val code = login.awaitCode()
                    SpotifyApi.finishLogin(login, code)
                }
                spSignedIn = true
                syncAll(youtube = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Glasswave", "Spotify sign-in failed", e)
                syncStatus = "Spotify sign-in didn't finish. Check the Client ID and that the redirect is http://127.0.0.1/callback"
            } finally {
                if (spLogin === login) spLogin = null
            }
        }
    }

    var importing by mutableStateOf(false)
        private set

    /** Imports a public Spotify playlist or album from its link into your own playlists. No sign-in needed. */
    fun importSpotifyLink(link: String) {
        if (importing || link.isBlank()) return
        importing = true
        viewModelScope.launch {
            try {
                val got = SpotifyApi.importLink(link.trim())
                withContext(Dispatchers.IO) { Library.createWith(got.name, got.cover, got.tracks) }
                Menus.say(
                    if (got.cut) "Imported the first ${got.tracks.size} songs of ${got.name}. Spotify only shares 100 by link; sign in to Spotify in Settings to get them all."
                    else "Imported ${got.name} · ${got.tracks.size} songs",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Menus.say(e.message?.takeIf { it.length < 70 } ?: "Couldn't import that link")
            } finally {
                importing = false
            }
        }
    }

    fun signOutSpotify() {
        Accounts.signOutSpotify()
        spSignedIn = false
        Library.clearSource(Source.SPOTIFY)
    }

    fun syncAll(youtube: Boolean = true, spotify: Boolean = true) {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            val problems = mutableListOf<String>()
            if (youtube && Accounts.ytSignedIn) {
                try {
                    syncYt()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SignInExpired) {
                    Accounts.ytCookie = null
                    ytSignedIn = false
                    problems += "YouTube Music sign-in expired. Sign in again."
                } catch (e: Exception) {
                    android.util.Log.e("Glasswave", "YouTube Music sync failed", e)
                    problems += "YouTube Music sync failed. Check your internet."
                }
            }
            if (spotify && Accounts.spotifySignedIn) {
                try {
                    val fresh = SpotifyApi.syncAll { syncStatus = it }
                    withContext(Dispatchers.IO) { Library.replaceSource(Source.SPOTIFY, fresh) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SignInExpired) {
                    Accounts.signOutSpotify()
                    spSignedIn = false
                    problems += "Spotify sign-in expired. Sign in again."
                } catch (e: Exception) {
                    android.util.Log.e("Glasswave", "Spotify sync failed", e)
                    problems += e.message?.takeIf { it.startsWith("Spotify") } ?: "Spotify sync failed. Check your internet."
                }
            }
            if (problems.isEmpty()) Accounts.lastSync = System.currentTimeMillis()
            syncStatus = problems.joinToString("\n").ifEmpty { "Synced ${playlists.value.size} playlists" }
            syncing = false
        }
    }

    private suspend fun syncYt() = withContext(Dispatchers.IO) {
        syncStatus = "YouTube Music: reading your library"
        val lists = YtMusicApi.playlists()
        // Everyone has at least Liked Music, so nothing at all means YouTube's reply changed. Keep what we have.
        if (lists.isEmpty()) throw java.io.IOException("YouTube Music returned no playlists")
        val fresh = lists.mapIndexedNotNull { i, p ->
            syncStatus = "YouTube Music: ${i + 1}/${lists.size} ${p.name}"
            val tracks = try {
                YtMusicApi.tracks(p.id.removePrefix("yt_"))
            } catch (e: SignInExpired) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Skipped YouTube Music playlist ${p.name}", e)
                return@mapIndexedNotNull null
            }
            if (tracks.isEmpty()) null
            else p.copy(count = tracks.size, thumb = p.thumb ?: tracks.first().thumb) to tracks
        }
        Library.replaceSource(Source.YT, fresh)
    }

    override fun onCleared() {
        spLogin?.cancel()
    }
}
