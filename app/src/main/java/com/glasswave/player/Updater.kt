// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Updates from inside the app. New versions are published as GitHub releases of the Glasswave
 * repository; the app checks the latest one, downloads its APK and hands it to Android's installer
 * (which asks you to confirm, then reinstalls over the current version, keeping your data).
 */
object Updater {
    private const val REPO = "uronlynonny/Glasswave"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    var available by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** 0..1 while downloading, or null. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var lastChecked by mutableStateOf(0L)
        private set

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The new version, already downloaded in the background and waiting to be installed. */
    var ready by mutableStateOf<File?>(null)
        private set
    private var prefetching = false

    fun init(context: Context) {
        app = context.applicationContext
        lastChecked = app.getSharedPreferences("updater", Context.MODE_PRIVATE).getLong("last", 0L)
        // Installer files for versions that are now installed (or older) aren't needed any more.
        File(app.cacheDir, "updates").listFiles()?.forEach { f ->
            val v = f.name.removePrefix("Glasswave-").removeSuffix(".apk")
            if (!f.name.endsWith(".apk") || !newer(v)) f.delete()
        }
    }

    private fun fileFor(r: Release) = File(File(app.cacheDir, "updates"), "Glasswave-${r.version}.apk")

    /** A complete download of [r], if there is one. */
    private fun downloaded(r: Release): File? = fileFor(r).takeIf { it.exists() && it.length() > 0 && (r.size <= 0 || it.length() == r.size) }

    /** Fetches the new version quietly as soon as it's found, so updating is a single tap. */
    private fun prefetch(r: Release) {
        downloaded(r)?.let { ready = it; return }
        if (prefetching || progress != null) return
        prefetching = true
        scope.launch {
            try {
                ready = withContext(Dispatchers.IO) { download(r, showProgress = false) }
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Background update download failed", e)
            } finally {
                prefetching = false
            }
        }
    }

    /* ---------------- What's new ---------------- */

    /** Shown once after an update: the version and a few short lines about what changed. */
    class WhatsNew(val version: String, val lines: List<String>)

    var whatsNew by mutableStateOf<WhatsNew?>(null)
        private set

    /** The short "- ..." lines of a release's notes, without the install instructions. */
    fun highlights(notes: String, max: Int = 5): List<String> = notes.lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.drop(2).trim().replace(Regex("\\s*\\(from [\\d.]+\\)"), "") }
        .filter { it.isNotEmpty() }
        .take(max)

    /**
     * On the first launch after an update, fetches that version's release notes and shows them
     * once. A fresh install shows nothing (there's nothing "new" yet).
     */
    fun checkWhatsNew() {
        val prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = currentVersion()
        val seen = prefs.getString("seen_version", null)
        if (seen == now) return
        prefs.edit().putString("seen_version", now).apply()
        if (seen == null) return
        scope.launch {
            val notes = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/tags/v$now")
                        .header("Accept", "application/vnd.github+json").header("User-Agent", "Glasswave").build()
                    YtRepo.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}").optString("body") else null }
                }.getOrNull()
            } ?: return@launch
            val lines = highlights(notes)
            if (lines.isNotEmpty()) whatsNew = WhatsNew(now, lines)
        }
    }

    fun dismissWhatsNew() { whatsNew = null }

    fun currentVersion(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0" }.getOrDefault("0")

    private fun parts(v: String) = v.removePrefix("v").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /** True when [v] is newer than the installed version. */
    private fun newer(v: String): Boolean {
        val a = parts(v)
        val b = parts(currentVersion())
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Looks for a newer release. Quiet unless [tell] is set (then it reports "up to date" too). */
    fun check(tell: Boolean = false) {
        if (checking) return
        checking = true
        if (tell) status = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { fetchLatest() }
                lastChecked = System.currentTimeMillis()
                app.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putLong("last", lastChecked).apply()
                if (r != null && newer(r.version)) {
                    available = r
                    prefetch(r)
                } else {
                    available = null
                    if (tell) status = "You're on the latest version (${currentVersion()})."
                }
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Update check failed", e)
                if (tell) status = "Couldn't check for updates. Check your internet."
            } finally {
                checking = false
            }
        }
    }

    /** Checks at most every three hours, on launch. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - lastChecked > 3 * 3600_000L) check()
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").header("User-Agent", "Glasswave").build()
        val json = YtRepo.http.newCall(req).execute().use { r ->
            if (r.code == 404) return null // no release yet, or the repository isn't public
            if (!r.isSuccessful) throw IOException("GitHub replied ${r.code}")
            JSONObject(r.body?.string() ?: return null)
        }
        val assets = json.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) { apk = a; break }
        }
        val url = apk?.optString("browser_download_url")?.ifEmpty { null } ?: return null
        return Release(json.optString("tag_name").removePrefix("v"), json.optString("body"), url, apk.optLong("size"))
    }

    private fun openInstaller(activity: Context, file: File) {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Opens Android's installer for the new version: straight away when it was already downloaded
     * in the background, otherwise after downloading it (with progress). Needs the "install unknown
     * apps" permission once.
     */
    fun install(activity: Context) {
        val r = available ?: return
        if (progress != null) return
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            status = "Allow Glasswave to install updates, then tap Update again."
            runCatching {
                activity.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        status = null
        // Already downloaded in the background: one tap, straight to Install.
        downloaded(r)?.let { f ->
            ready = f
            runCatching { openInstaller(activity, f) }.onFailure { status = "Couldn't open the installer. Try again." }
            return
        }
        progress = 0f
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(r) }
                ready = file
                openInstaller(activity, file)
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Update failed", e)
                status = "Couldn't download the update. Try again later."
            } finally {
                progress = null
            }
        }
    }

    /** Downloads to a temporary file and only then names it, so a cut-off download is never installed. */
    private fun download(r: Release, showProgress: Boolean = true): File {
        val final = fileFor(r)
        final.parentFile?.mkdirs()
        val out = File(final.parentFile, final.name + ".part")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "Glasswave").build()
        YtRepo.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: r.size.takeIf { it > 0 }
            var done = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (showProgress && total != null) {
                            val p = done.toFloat() / total
                            scope.launch { progress = p }
                        }
                    }
                }
            }
            if (total != null && done != total) { out.delete(); throw IOException("Download cut off") }
        }
        final.delete()
        if (!out.renameTo(final)) throw IOException("Couldn't save the update")
        return final
    }
}
