// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Updates itself on Windows, no reinstalling by hand:
 *  1. On launch, and every hour or so while open, it looks at the Windows release on GitHub.
 *  2. If that release has a newer Windows installer, it downloads it quietly in the background.
 *  3. The Update button then says "Restart to update": one click installs it and reopens Glasswave.
 *     Or just close the app: the update installs silently on the way out, ready for next time.
 * The app installs for your user only, so updating never needs admin permission.
 */
object Updater {
    private const val REPO = "uronlynonny/Glasswave"
    /**
     * The Windows app lives in one release that's always kept current (tag "windows"), apart from
     * the phone app's releases. Its title says the version ("Glasswave for Windows 1.0.1") and it
     * holds Glasswave-Windows.msi. The website's download button points at the same file.
     */
    private const val RELEASE = "https://api.github.com/repos/$REPO/releases/tags/windows"
    private val versionIn = Regex("""\d+\.\d+(\.\d+)?""")

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
    /** The new version's installer, downloaded and waiting. */
    var ready by mutableStateOf<File?>(null)
        private set

    /** Closes the app (set by the window), so the installer can replace its files. */
    var exitApp: () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { android.content.AppContext.getSharedPreferences("updater", Context.MODE_PRIVATE) }
    private val dir by lazy { File(android.content.AppDirs.cache, "updates").apply { mkdirs() } }
    private fun fileFor(r: Release) = File(dir, "Glasswave-${r.version}.msi")

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        lastChecked = prefs.getLong("last", 0L)
        // Installers for this version or older aren't needed any more.
        dir.listFiles()?.forEach { f ->
            val v = f.name.removePrefix("Glasswave-").removeSuffix(".msi").removeSuffix(".part")
            if (!newer(v)) f.delete()
        }
    }

    class WhatsNew(val version: String, val lines: List<String>)

    var whatsNew by mutableStateOf<WhatsNew?>(null)
        private set

    fun highlights(notes: String, max: Int = 5): List<String> = notes.lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.drop(2).trim().replace(Regex("\\s*\\(from [\\d.]+\\)"), "") }
        .filter { it.isNotEmpty() }
        .take(max)

    /** After an update, says once what's new (from the release notes). */
    fun checkWhatsNew() {
        val now = currentVersion()
        val seen = prefs.getString("seen_version", null)
        if (seen == now) return
        prefs.edit().putString("seen_version", now).apply()
        if (seen == null) return
        scope.launch {
            val notes = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() }
                ?.takeIf { it.version == now }?.notes ?: return@launch
            val lines = highlights(notes)
            if (lines.isNotEmpty()) whatsNew = WhatsNew(now, lines)
        }
    }

    fun dismissWhatsNew() { whatsNew = null }

    /** The version this copy was built as (the Windows app's own version number). */
    fun currentVersion(): String =
        Updater::class.java.getResource("/glasswave-version.txt")?.readText()?.trim() ?: "0"

    private fun parts(v: String) = v.removePrefix("v").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /** True when [v] is newer than this version. */
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

    fun check(tell: Boolean = false) {
        if (checking) return
        checking = true
        if (tell) status = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { fetchLatest() }
                lastChecked = System.currentTimeMillis()
                prefs.edit().putLong("last", lastChecked).apply()
                if (r != null && newer(r.version)) {
                    available = r
                    download(r)
                } else {
                    available = null
                    if (tell) status = "You're on the latest version (${currentVersion()})."
                }
            } catch (e: Exception) {
                if (tell) status = "Couldn't check for updates. Check your internet."
            } finally {
                checking = false
            }
        }
    }

    fun checkIfDue() {
        if (System.currentTimeMillis() - lastChecked > 3600_000L) check()
    }

    /** The current Windows release, if it has an installer. */
    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(RELEASE).header("Accept", "application/vnd.github+json").header("User-Agent", "Glasswave").build()
        val json = YtRepo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string() ?: return null)
        }
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").lowercase().endsWith(".msi")) {
                val version = versionIn.find(json.optString("name"))?.value ?: return null
                return Release(version, json.optString("body"), a.optString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /** Downloads the new installer in the background (once), showing progress on the Update button. */
    private fun download(r: Release) {
        val final = fileFor(r)
        if (final.exists() && (r.size <= 0 || final.length() == r.size)) { ready = final; return }
        if (progress != null) return
        progress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val part = File(dir, final.name + ".part")
                    val req = Request.Builder().url(r.apkUrl).header("User-Agent", "Glasswave").build()
                    YtRepo.http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
                        val body = resp.body ?: throw IOException("Empty download")
                        val total = body.contentLength().takeIf { it > 0 } ?: r.size
                        var done = 0L
                        body.byteStream().use { input ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) withContext(Dispatchers.Main) { progress = (done.toFloat() / total).coerceIn(0f, 1f) }
                                }
                            }
                        }
                    }
                    final.delete()
                    if (!part.renameTo(final)) throw IOException("Couldn't save the update")
                }
                ready = final
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Update download failed", e)
                status = "Couldn't download the update. It'll try again later."
            } finally {
                progress = null
            }
        }
    }

    /** The Update button: install now if it's downloaded, otherwise check (and download). */
    fun install(@Suppress("UNUSED_PARAMETER") activity: Any?) {
        when {
            ready != null -> restartToUpdate()
            progress != null -> Unit
            else -> check(tell = true)
        }
    }

    /** Installs the downloaded update and opens Glasswave again. */
    fun restartToUpdate() {
        val msi = ready ?: return
        if (runInstaller(msi, reopen = true)) exitApp()
    }

    /** Called as the app closes: a downloaded update installs silently, ready for next time. */
    fun installOnExit() {
        ready?.let { runInstaller(it, reopen = false) }
    }

    /**
     * Starts the installer from a tiny script that outlives the app: it waits for Glasswave to
     * close, installs quietly (a small progress window, no questions), then reopens it if asked.
     */
    private fun runInstaller(msi: File, reopen: Boolean): Boolean = try {
        val app = System.getProperty("jpackage.app-path")
        val script = File(dir, "install-update.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("timeout /t 2 /nobreak >nul")
                appendLine("msiexec /i \"${msi.absolutePath}\" ${if (reopen) "/passive" else "/qn"} /norestart")
                if (reopen && app != null) appendLine("start \"\" \"$app\"")
                appendLine("del \"%~f0\"")
            },
        )
        ProcessBuilder("cmd.exe", "/c", "start", "\"Glasswave update\"", "/min", "cmd.exe", "/c", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        ready = null
        true
    } catch (e: Exception) {
        android.util.Log.e("Glasswave", "Couldn't start the update", e)
        status = "Couldn't start the update."
        false
    }
}
