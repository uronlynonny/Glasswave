// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.Manifest
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A music file on this phone, with what's needed to sort and group it. */
data class PhoneSong(
    val track: Track,
    val album: String,
    val albumId: Long,
    /** The folder it's in, like "Music/Bob Marley/". */
    val folder: String,
    /** Track number on the album (disc × 1000 + track), 0 when not known. */
    val trackNo: Int,
    /** When it was put on the phone, in seconds. */
    val added: Long,
)

/**
 * Music files already on the phone (copied over, bought, recorded), found through Android's music
 * library. Each song's address is its content:// address, which the player opens straight from the phone.
 */
object PhoneMusic {
    /** The app, so the player can open phone songs even when it was started without the app on screen. */
    @Volatile
    var app: Context? = null

    /** The songs found last time we looked, A to Z. */
    var songs by mutableStateOf<List<PhoneSong>>(emptyList())
        private set

    /** We've looked at least once. */
    var loaded by mutableStateOf(false)
        private set

    var scanning by mutableStateOf(false)
        private set

    /** Shorter than this is a ringtone or voice note, not a song. */
    private const val MIN_MS = 30_000

    /** The permission to ask for: "Music and audio" on Android 13 and newer, "Files" before. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** A song file on this phone (not from YouTube or Spotify). */
    fun isPhoneSong(url: String): Boolean = url.startsWith("content://") || url.startsWith("file://")

    /** Looks through the phone's music again. */
    suspend fun scan(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        if (!allowed(ctx)) return
        scanning = true
        val found = withContext(Dispatchers.IO) {
            runCatching { query(ctx) }.onFailure { android.util.Log.w("Glasswave", "Couldn't read the phone's music", it) }.getOrNull()
        }
        if (found != null) songs = found
        loaded = true
        scanning = false
    }

    private fun query(ctx: Context): List<PhoneSong> {
        val base = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val folderCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val cols = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            folderCol,
        )
        val where = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= $MIN_MS"
        val out = ArrayList<PhoneSong>()
        ctx.contentResolver.query(base, cols, where, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val trackNo = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val folder = c.getColumnIndexOrThrow(folderCol)
            while (c.moveToNext()) {
                val songId = c.getLong(id)
                val aId = c.getLong(albumId)
                val songTitle = c.text(title) ?: c.text(name)?.substringBeforeLast('.') ?: "Untitled"
                val songArtist = c.text(artist) ?: "Unknown artist"
                val path = c.text(folder) ?: ""
                out += PhoneSong(
                    track = Track(
                        url = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId).toString(),
                        title = songTitle,
                        artist = songArtist,
                        seconds = c.getLong(duration) / 1000,
                        thumb = if (aId > 0) "content://media/external/audio/albumart/$aId" else null,
                    ),
                    album = c.text(album) ?: "Unknown album",
                    albumId = aId,
                    // Before Android 10 this is the file's full path, so keep just its folder.
                    folder = if (Build.VERSION.SDK_INT >= 29) path else path.substringBeforeLast('/', "") + "/",
                    trackNo = if (c.isNull(trackNo)) 0 else c.getInt(trackNo),
                    added = c.getLong(added),
                )
            }
        }
        return out
    }

    /** A column's text, or null when it's empty or Android's "<unknown>". */
    private fun Cursor.text(i: Int): String? =
        if (isNull(i)) null else getString(i)?.trim()?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }

    /** A folder's own name ("Music/Bob Marley/" is "Bob Marley"). */
    fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "Phone storage" }
}

/**
 * Starts with the app (before anything else) and hands [PhoneMusic] the app, so the player can open
 * songs on this phone even when Android starts it from the media controls. It shares nothing.
 */
class PhoneMusicStarter : ContentProvider() {
    override fun onCreate(): Boolean {
        PhoneMusic.app = context?.applicationContext
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
