// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spotify sign-in and playlist reading through Spotify's official Web API.
 *
 * Sign-in uses your own Spotify developer app (Client ID) with the redirect
 * http://127.0.0.1/callback. While you sign in, Glasswave listens on 127.0.0.1 on this
 * phone for a moment to receive the reply, which is the method Spotify recommends for apps.
 */
object SpotifyApi {
    private const val API = "https://api.spotify.com/v1"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** A started sign-in: open [authUrl] in the browser, then call [awaitCode]. */
    class Login(val authUrl: String, private val server: ServerSocket, private val state: String, val redirect: String, val verifier: String) {
        /** Waits (up to 5 minutes) for the browser to come back with the sign-in code. Blocking. */
        fun awaitCode(): String {
            server.soTimeout = 5 * 60 * 1000
            server.use { s ->
                while (true) {
                    val sock = try { s.accept() } catch (e: SocketTimeoutException) { throw IOException("Spotify sign-in timed out") }
                    // Browsers sometimes open extra empty connections; skip any that don't bring the answer.
                    val result = try { handle(sock) } catch (e: IOException) { null }
                    when (result) {
                        null -> continue
                        "" -> throw IOException("Spotify sign-in was cancelled")
                        else -> return result
                    }
                }
            }
        }

        /** Returns the code, "" if Spotify said no, or null if this connection wasn't the answer. */
        private fun handle(sock: java.net.Socket): String? = sock.use { c ->
            c.soTimeout = 10_000
            val line = c.getInputStream().bufferedReader().readLine() ?: return null
            val path = line.split(' ').getOrNull(1) ?: return null
            val uri = Uri.parse("http://127.0.0.1$path")
            if (uri.path != "/callback") {
                c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return null
            }
            val code = uri.getQueryParameter("code")
            val ok = code != null && uri.getQueryParameter("state") == state
            val msg = if (ok) "Signed in to Spotify. Going back to Glasswave..." else "Spotify sign-in didn't finish. Go back to Glasswave and try again."
            val html = "<html><head><meta name=viewport content='width=device-width'>" +
                "<meta http-equiv=refresh content='0;url=glasswave://spotify-done'></head>" +
                "<body style='font-family:sans-serif;background:#070a10;color:#fff;padding:32px'>" +
                "<h2>$msg</h2><p><a style='color:#8fb4ff' href='glasswave://spotify-done'>Open Glasswave</a></p></body></html>"
            val bytes = html.toByteArray()
            c.getOutputStream().write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes,
            )
            c.getOutputStream().flush()
            if (ok) code else ""
        }

        fun cancel() = runCatching { server.close() }
    }

    fun startLogin(clientId: String): Login {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val redirect = "http://127.0.0.1:${server.localPort}/callback"
        val rnd = SecureRandom()
        val verifier = b64(ByteArray(64).also { rnd.nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64(ByteArray(16).also { rnd.nextBytes(it) })
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build().toString()
        return Login(url, server, state, redirect, verifier)
    }

    private fun tokenRequest(form: FormBody): JSONObject {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(form).build()
        YtRepo.http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (r.code == 400 || r.code == 401) throw SignInExpired("Spotify")
            if (!r.isSuccessful) throw IOException("Spotify sign-in failed (${r.code})")
            return JSONObject(text)
        }
    }

    private fun saveTokens(j: JSONObject) {
        Accounts.spotifyAccess = j.getString("access_token")
        Accounts.spotifyExpiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60_000
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { Accounts.spotifyRefresh = it }
    }

    /** Swaps the code from the browser for tokens and saves them. Blocking. */
    fun finishLogin(login: Login, code: String) {
        saveTokens(
            tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", login.redirect)
                    .add("client_id", Accounts.spotifyClientId)
                    .add("code_verifier", login.verifier)
                    .build(),
            ),
        )
    }

    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        Accounts.spotifyAccess?.let { if (now < Accounts.spotifyExpiresAt) return it }
        val refresh = Accounts.spotifyRefresh ?: throw SignInExpired("Spotify")
        saveTokens(
            tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", Accounts.spotifyClientId)
                    .build(),
            ),
        )
        return Accounts.spotifyAccess!!
    }

    private fun get(url: String): JSONObject {
        repeat(4) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").build()
            YtRepo.http.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> return JSONObject(r.body?.string() ?: "{}")
                    r.code == 401 -> Accounts.spotifyAccess = null // token went stale; refresh and retry
                    r.code == 429 -> Thread.sleep((r.header("Retry-After")?.toLongOrNull() ?: 2) * 1000)
                    r.code == 403 -> throw IOException("Spotify refused access (403). Your Spotify account may need to be added under User Management in your developer app.")
                    else -> throw IOException("Spotify replied ${r.code}")
                }
            }
        }
        throw IOException("Spotify is busy, try again later")
    }

    /** Reads every page of a Spotify list. */
    /**
     * Every page of a list, however long. Follows Spotify's "next" link, and if that's missing
     * while songs are still left (Spotify sometimes stops early on big playlists), asks for the
     * rest by position instead.
     */
    private fun pages(first: String, each: (JSONObject) -> Unit) {
        var url: String? = first
        var guard = 0
        var seen = 0
        while (url != null && guard++ < 1000) {
            val page = get(url)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(each)
            seen += items.length()
            val total = page.optInt("total", -1)
            url = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
                ?: if (total > seen && items.length() > 0) withOffset(first, seen) else null
        }
    }

    private fun withOffset(url: String, offset: Int): String {
        val clean = url.replace(Regex("([?&])offset=\\d+&?"), "$1").trimEnd('&', '?')
        return clean + (if ('?' in clean) "&" else "?") + "offset=$offset"
    }

    private fun trackFrom(row: JSONObject): Track? {
        // Spotify renamed "track" to "item" in 2026; the old key can now be a plain true/false.
        val t = row.optJSONObject("item") ?: row.optJSONObject("track") ?: return null
        if (t.optString("type", "track") != "track") return null
        val title = t.optString("name").ifEmpty { return null }
        val artists = t.optJSONArray("artists")
        val artist = (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.optJSONObject(it)?.optString("name") ?: "" }
        val seconds = t.optLong("duration_ms") / 1000
        return Track(matchUrl(title, artist, seconds), title, artist, seconds, image(t.optJSONObject("album"), small = true))
    }

    /** Spotify lists images biggest first. Lists use a small one to save memory. */
    private fun image(o: JSONObject?, small: Boolean): String? {
        val imgs = o?.optJSONArray("images") ?: return null
        if (imgs.length() == 0) return null
        val idx = if (small) minOf(1, imgs.length() - 1) else 0
        return imgs.optJSONObject(idx)?.optString("url")?.ifEmpty { null }
    }

    /**
     * Pulls your playlists and Liked Songs. [progress] gets a short status line.
     * Spotify only gives the songs of playlists you own or collaborate on; for playlists you
     * just follow, Glasswave reads Spotify's public player page instead (public playlists only).
     */
    suspend fun syncAll(progress: (String) -> Unit): List<Pair<PlaylistInfo, List<Track>>> = withContext(Dispatchers.IO) {
        val me = get("$API/me").optString("id")
        val out = mutableListOf<Pair<PlaylistInfo, List<Track>>>()

        progress("Spotify: Liked Songs")
        val liked = mutableListOf<Track>()
        pages("$API/me/tracks?limit=50") { row -> trackFrom(row)?.let(liked::add) }
        if (liked.isNotEmpty()) {
            out += PlaylistInfo("sp_liked", "Liked Songs", Source.SPOTIFY, liked.firstOrNull()?.thumb, liked.size) to liked
        }

        val lists = mutableListOf<JSONObject>()
        pages("$API/me/playlists?limit=50") { lists += it }
        lists.forEachIndexed { i, p ->
            val id = p.optString("id").ifEmpty { return@forEachIndexed }
            val name = p.optString("name")
            progress("Spotify: ${i + 1}/${lists.size} $name")
            val owned = p.optJSONObject("owner")?.optString("id") == me || p.optBoolean("collaborative")
            val tracks = mutableListOf<Track>()
            try {
                if (owned) {
                    pages("$API/playlists/$id/items?limit=100&additional_types=track") { row -> trackFrom(row)?.let(tracks::add) }
                } else {
                    tracks += publicPlaylist(id)
                }
            } catch (e: SignInExpired) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Skipped Spotify playlist $name", e)
            }
            if (tracks.isNotEmpty()) {
                out += PlaylistInfo("sp_$id", name, Source.SPOTIFY, image(p, small = false), tracks.size) to tracks
            }
        }
        out
    }

    /** Best effort: reads a public playlist from Spotify's embeddable player page. */
    private fun publicPlaylist(id: String): List<Track> {
        val req = Request.Builder()
            .url("https://open.spotify.com/embed/playlist/$id")
            .header("User-Agent", YtDownloader.USER_AGENT)
            .build()
        val html = YtRepo.http.newCall(req).execute().use { it.body?.string() ?: "" }
        val start = html.indexOf("<script id=\"__NEXT_DATA__\"")
        if (start < 0) return emptyList()
        val jsonStart = html.indexOf('>', start) + 1
        val jsonEnd = html.indexOf("</script>", jsonStart)
        if (jsonStart <= 0 || jsonEnd < 0) return emptyList()
        val data = JSONObject(html.substring(jsonStart, jsonEnd))
        val list = findArray(data, "trackList") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").ifEmpty { return@mapNotNull null }
            val artist = t.optString("subtitle").replace(" ", " ")
            val seconds = t.optLong("duration") / 1000
            Track(matchUrl(title, artist, seconds), title, artist, seconds, null)
        }
    }

    /**
     * A playlist or album imported from a share link: its name, cover and songs. [cut] is true when
     * Spotify's public page stopped at its 100-song limit, so the playlist has more than this.
     */
    data class Imported(val name: String, val cover: String?, val tracks: List<Track>, val cut: Boolean = false)

    private val linkRx = Regex("(playlist|album)[/:]([A-Za-z0-9]{22})")

    /**
     * Imports a public Spotify playlist or album from its share link ("Share → Copy link" in Spotify).
     * Needs no account or key: it reads Spotify's public embed page.
     */
    suspend fun importLink(link: String): Imported = withContext(Dispatchers.IO) {
        val m = linkRx.find(link) ?: throw java.io.IOException("That doesn't look like a Spotify playlist or album link")
        val (type, id) = m.destructured
        val req = Request.Builder()
            .url("https://open.spotify.com/embed/$type/$id")
            .header("User-Agent", YtDownloader.USER_AGENT)
            .build()
        val html = YtRepo.http.newCall(req).execute().use { it.body?.string() ?: "" }
        val start = html.indexOf("<script id=\"__NEXT_DATA__\"")
        if (start < 0) throw java.io.IOException("Spotify didn't share this one (is it private?)")
        val jsonStart = html.indexOf('>', start) + 1
        val jsonEnd = html.indexOf("</script>", jsonStart)
        val data = JSONObject(html.substring(jsonStart, jsonEnd))
        val entity = findObject(data, "entity")
        val name = entity?.optString("name")?.ifEmpty { null } ?: entity?.optString("title")?.ifEmpty { null } ?: "Spotify playlist"
        val cover = entity?.let { findArray(it, "sources") }?.optJSONObject(0)?.optString("url")?.ifEmpty { null }
        val list = findArray(data, "trackList") ?: throw java.io.IOException("No songs found")
        val tracks = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").ifEmpty { return@mapNotNull null }
            val artist = t.optString("subtitle").replace(" ", " ")
            val seconds = t.optLong("duration") / 1000
            Track(matchUrl(title, artist, seconds), title, artist, seconds, cover)
        }
        if (tracks.isEmpty()) throw java.io.IOException("No songs found")
        // Spotify's public page lists at most 100 songs.
        Imported(name, cover, tracks, cut = type == "playlist" && list.length() >= 100)
    }

    private fun findObject(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                (node.opt(key) as? JSONObject)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) findObject(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findObject(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun findArray(node: Any?, key: String): JSONArray? {
        when (node) {
            is JSONObject -> {
                (node.opt(key) as? JSONArray)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) findArray(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findArray(node.opt(i), key)?.let { return it }
        }
        return null
    }
}
