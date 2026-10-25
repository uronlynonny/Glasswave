// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.AppContext
import android.content.AppDirs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.json.JSONArray
import org.json.JSONObject

/** A music file on this PC, with what's needed to sort and group it. */
data class PcSong(
    val track: Track,
    val album: String,
    /** Which album it's on: the album's name plus its artist (or its folder). */
    val albumKey: String,
    /** The folder it's in (full path). */
    val folder: String,
    /** Track number on the album (disc × 1000 + track), 0 when not known. */
    val trackNo: Int,
    /** When it was put on the PC, in milliseconds. */
    val added: Long,
)

/**
 * Music files on this PC ("On this PC" in the Library): the Music folder plus any folders you add.
 * Each song's address is its file:// address, which the player opens straight from the disk.
 * What's found is kept in a small index (by path and date changed), so reopening is instant.
 */
object PcMusic {
    /** The songs found last time we looked, A to Z. */
    var songs by mutableStateOf<List<PcSong>>(emptyList())
        private set

    /** We've looked at least once (or have last time's list). */
    var loaded by mutableStateOf(false)
        private set

    var scanning by mutableStateOf(false)
        private set

    /** Folders you added on the page. The Music folder is always looked in too. */
    var folders by mutableStateOf<List<String>>(emptyList())
        private set

    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
    private val COVERS = listOf("cover", "folder", "front", "album", "albumart")

    /** Shorter than this is a sound effect or voice note, not a song. */
    private const val MIN_MS = 30_000L

    private val prefs by lazy { AppContext.getSharedPreferences("pc_music", 0) }
    private val dir by lazy { File(AppDirs.cache, "pc music").apply { mkdirs() } }
    private val indexFile get() = File(dir, "index.json")
    private val artDir by lazy { File(dir, "art").apply { mkdirs() } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Windows' Music folder (it may have been moved, for example into OneDrive). */
    val musicFolder: File by lazy {
        runCatching {
            File(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(com.sun.jna.platform.win32.KnownFolders.FOLDERID_Music))
        }.getOrNull()?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"), "Music")
    }

    init {
        folders = runCatching {
            val a = JSONArray(prefs.getString("folders", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** A song file on this PC (not from YouTube or Spotify). */
    fun isPcSong(url: String): Boolean = url.startsWith("file:")

    /** The file behind a song on this PC, or null for anything else. */
    fun fileOf(url: String): File? = if (!isPcSong(url)) null else runCatching { File(java.net.URI(url)) }.getOrNull()

    /** Every folder we look in: the Music folder first, then yours. */
    fun allFolders(): List<File> = (listOf(musicFolder) + folders.map(::File)).distinctBy { it.absolutePath.lowercase() }

    fun addFolder(f: File) {
        val path = f.absolutePath
        if (allFolders().any { it.absolutePath.equals(path, true) }) return
        folders = folders + path
        saveFolders()
        refresh()
    }

    fun removeFolder(path: String) {
        folders = folders.filterNot { it.equals(path, true) }
        saveFolders()
        refresh()
    }

    private fun saveFolders() = prefs.edit().putString("folders", JSONArray(folders).toString()).apply()

    /** A folder's own name ("C:\Music\Bob Marley" is "Bob Marley"). */
    fun folderName(path: String): String = File(path).name.ifEmpty { path }

    /** Looks through the folders again, in the background. */
    fun refresh() {
        if (scanning) { again = true; return }
        scanning = true
        scope.launch {
            try {
                do {
                    again = false
                    runCatching { scan() }.onFailure { android.util.Log.w("Glasswave", "Couldn't look through the music on this PC", it) }
                } while (again)
            } finally {
                withContext(Dispatchers.Main) { loaded = true; scanning = false }
            }
        }
    }

    /** Folders changed while we were looking: look once more when done. */
    @Volatile private var again = false

    /* ---- The index: what each file held, last time we read it ---- */

    private class Entry(
        val path: String, val modified: Long, val size: Long, val added: Long,
        val title: String, val artist: String, val album: String, val albumArtist: String?,
        val trackNo: Int, val ms: Long, val art: String?,
        /** Only in OneDrive (not downloaded), so its tags weren't read yet. */
        val cloud: Boolean,
    ) {
        fun json(): JSONObject = JSONObject().put("p", path).put("m", modified).put("z", size).put("c", added)
            .put("t", title).put("a", artist).put("al", album).put("aa", albumArtist ?: "")
            .put("n", trackNo).put("d", ms).put("i", art ?: "").put("o", cloud)
    }

    private var index: MutableMap<String, Entry>? = null

    private fun loadIndex(): MutableMap<String, Entry> {
        index?.let { return it }
        val out = HashMap<String, Entry>()
        runCatching {
            val a = JSONArray(indexFile.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val e = Entry(
                    o.getString("p"), o.getLong("m"), o.optLong("z"), o.optLong("c"),
                    o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa").ifEmpty { null },
                    o.optInt("n"), o.optLong("d"), o.optString("i").ifEmpty { null }, o.optBoolean("o"),
                )
                out[e.path] = e
            }
        }
        index = out
        return out
    }

    private fun saveIndex(entries: Collection<Entry>) {
        runCatching {
            val a = JSONArray()
            entries.forEach { a.put(it.json()) }
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
        }
    }

    /* ---- Looking ---- */

    private class Found(val file: File, val modified: Long, val size: Long, val added: Long, val cloud: Boolean)

    private suspend fun scan() {
        val idx = loadIndex()
        val roots = allFolders()
        val covers = HashMap<String, String?>()
        // Last time's list straight away, while we look for changes.
        if (!loaded && songs.isEmpty() && idx.isNotEmpty()) {
            val quick = build(idx.values.filter { e -> roots.any { e.path.startsWith(it.absolutePath, true) } }, covers)
            withContext(Dispatchers.Main) { songs = quick; loaded = true }
        }

        val found = LinkedHashMap<String, Found>()
        for (root in roots) if (root.isDirectory) walk(root.toPath(), found)

        val keep = ArrayList<Entry>(found.size)
        val toRead = ArrayList<Found>()
        for (f in found.values) {
            val old = idx[f.file.absolutePath]
            if (old != null && old.modified == f.modified && old.size == f.size && (!old.cloud || f.cloud)) keep += old
            else toRead += f
        }
        // Read new and changed files a few at a time; show what's found as we go.
        val reader = Dispatchers.IO.limitedParallelism(3)
        for (chunk in toRead.chunked(60)) {
            keep += withContext(reader) { chunk.map { f -> async { read(f) } }.awaitAll() }
            val now = build(keep, covers)
            withContext(Dispatchers.Main) { songs = now; loaded = true }
        }
        val all = build(keep, covers)
        withContext(Dispatchers.Main) { songs = all; loaded = true }
        index = keep.associateByTo(HashMap()) { it.path }
        saveIndex(keep)
    }

    /** Every audio file under [root], skipping hidden and system folders. */
    private fun walk(root: Path, found: MutableMap<String, Found>) {
        runCatching {
            Files.walkFileTree(root, emptySet(), 12, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = d.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (d != root && (name.startsWith(".") || name.startsWith("$") || isHidden(d))) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = p.fileName.toString()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in AUDIO || !attrs.isRegularFile || name.startsWith("._")) return FileVisitResult.CONTINUE
                    val f = p.toFile()
                    val key = f.absolutePath
                    if (key !in found) found[key] = Found(f, attrs.lastModifiedTime().toMillis(), attrs.size(), attrs.creationTime().toMillis(), isCloudOnly(p))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(p: Path, e: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }.onFailure { android.util.Log.w("Glasswave", "Couldn't look in $root", it) }
    }

    private fun dosAttributes(p: Path): Int =
        runCatching { Files.getAttribute(p, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int }.getOrDefault(0)

    private fun isHidden(p: Path): Boolean = dosAttributes(p) and 0x6 != 0 // hidden or system

    /** Kept only in OneDrive, not on this PC yet: reading it would download it, so we don't. */
    private fun isCloudOnly(p: Path): Boolean = dosAttributes(p) and (0x1000 or 0x40000 or 0x400000) != 0

    /** A cover picture saved next to the songs (cover.jpg, folder.jpg, front.jpg...), as a file:// address. */
    private fun coverIn(folder: File?): String? {
        val files = folder?.listFiles() ?: return null
        val pics = files.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        for (name in COVERS) pics.firstOrNull { it.nameWithoutExtension.equals(name, true) }?.let { return it.toPath().toUri().toString() }
        return null
    }

    /** Puts the found files into songs, A to Z. [covers] remembers each folder's cover picture. */
    private fun build(entries: Collection<Entry>, covers: MutableMap<String, String?>): List<PcSong> =
        entries.asSequence()
            .filter { it.ms == 0L || it.ms >= MIN_MS }
            .map { e ->
                val file = File(e.path)
                val folder = file.parent ?: ""
                if (folder !in covers) covers[folder] = coverIn(file.parentFile)
                val art = e.art?.takeIf { fileOf(it)?.exists() == true } ?: covers[folder]
                PcSong(
                    track = Track(file.toPath().toUri().toString(), e.title, e.artist, e.ms / 1000, art),
                    album = e.album,
                    albumKey = e.album.lowercase() + "|" + (e.albumArtist?.lowercase() ?: folder.lowercase()),
                    folder = folder,
                    trackNo = e.trackNo,
                    added = e.added,
                )
            }
            .sortedBy { it.track.title.lowercase() }
            .toList()

    /* ---- Reading a file's tags (with FFmpeg, already in the app for playing) ---- */

    private val loggingQuiet by lazy { avutil.av_log_set_level(avutil.AV_LOG_ERROR) }

    private fun read(f: Found): Entry {
        val path = f.file.absolutePath
        val name = nameGuess(f.file)
        val tags = if (f.cloud) null else runCatching { tags(path) }.getOrNull()
        val art = tags?.picture?.let { runCatching { saveArt(it) }.getOrNull() }
        return Entry(
            path, f.modified, f.size, f.added,
            title = tags?.get("title") ?: name.second,
            artist = tags?.get("artist") ?: tags?.get("album_artist") ?: name.first ?: "Unknown artist",
            album = tags?.get("album") ?: "Unknown album",
            albumArtist = tags?.get("album_artist"),
            trackNo = (tags?.get("disc")?.number() ?: 0) * 1000 + (tags?.get("track")?.number() ?: 0),
            ms = tags?.ms ?: 0,
            art = art,
            cloud = f.cloud,
        )
    }

    /** "3/12" is 3. */
    private fun String.number(): Int? = trim().takeWhile { it.isDigit() }.toIntOrNull()

    /** "Artist - Title" from the file name; "01 - " or "01. " in front is left out. */
    private fun nameGuess(f: File): Pair<String?, String> {
        val base = f.nameWithoutExtension.replace('_', ' ').replace(Regex("^\\d{1,3}\\s*[.\\-)]\\s*"), "").trim()
        val i = base.indexOf(" - ")
        return if (i > 0) base.substring(0, i).trim() to base.substring(i + 3).trim().ifEmpty { base }
        else null to base.ifEmpty { f.nameWithoutExtension }
    }

    private class Tags(val values: Map<String, String>, val ms: Long, val picture: ByteArray?) {
        operator fun get(key: String): String? = values[key]
    }

    private fun tags(path: String): Tags? {
        loggingQuiet
        val ctx = AVFormatContext(null)
        // FFmpeg takes the path as UTF-8, so names with accents and other scripts work.
        if (avformat.avformat_open_input(ctx, BytePointer(path, "UTF-8"), null as AVInputFormat?, null as AVDictionary?) < 0) return null
        try {
            avformat.avformat_find_stream_info(ctx, null as PointerPointer<*>?)
            val values = HashMap<String, String>()
            fun take(d: AVDictionary?) {
                if (d == null || d.isNull) return
                for (key in listOf("title", "artist", "album", "album_artist", "track", "disc")) {
                    if (key in values) continue
                    val e = avutil.av_dict_get(d, key, null as org.bytedeco.ffmpeg.avutil.AVDictionaryEntry?, 0) ?: continue
                    if (e.isNull) continue
                    e.value()?.getString("UTF-8")?.trim()?.takeIf { it.isNotEmpty() }?.let { values[key] = it }
                }
            }
            take(ctx.metadata())
            var picture: ByteArray? = null
            for (i in 0 until ctx.nb_streams()) {
                val st = ctx.streams(i) ?: continue
                // Ogg and Opus keep their tags on the stream.
                take(st.metadata())
                if (picture == null && st.disposition() and avformat.AV_DISPOSITION_ATTACHED_PIC != 0) {
                    val pkt = st.attached_pic()
                    val n = pkt.size()
                    if (n in 1..(20 shl 20) && pkt.data() != null) picture = ByteArray(n).also { pkt.data().position(0).get(it) }
                }
            }
            val d = ctx.duration()
            return Tags(values, if (d > 0) d / 1000 else 0, picture)
        } finally {
            avformat.avformat_close_input(ctx)
        }
    }

    /** Saves a song's own cover picture as a small JPEG (one per picture, shared by an album's songs). */
    private fun saveArt(bytes: ByteArray): String? {
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        val out = File(artDir, "%08x_%x.jpg".format(crc, bytes.size))
        if (!out.exists()) {
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return null
            val scale = minOf(1.0, 400.0 / maxOf(img.width, img.height))
            val w = maxOf(1, (img.width * scale).toInt())
            val h = maxOf(1, (img.height * scale).toInt())
            val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            small.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, w, h, null)
                dispose()
            }
            val tmp = File(artDir, out.name + ".tmp")
            if (!javax.imageio.ImageIO.write(small, "jpg", tmp)) return null
            if (!tmp.renameTo(out)) tmp.delete()
        }
        return out.toPath().toUri().toString()
    }

    /* ---- Choosing a folder ---- */

    /** Asks which folder to add, with Windows' own look. Null if nothing was picked. */
    fun pickFolder(): File? {
        val old = javax.swing.UIManager.getLookAndFeel()
        val chooser = try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
            javax.swing.JFileChooser(musicFolder)
        } catch (e: Exception) {
            javax.swing.JFileChooser(musicFolder)
        } finally {
            runCatching { javax.swing.UIManager.setLookAndFeel(old) }
        }
        chooser.dialogTitle = "Add a music folder"
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        val parent = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: java.awt.Window.getWindows().firstOrNull { it.isShowing }
        if (chooser.showDialog(parent, "Add folder") != javax.swing.JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf { it.isDirectory }
    }
}
