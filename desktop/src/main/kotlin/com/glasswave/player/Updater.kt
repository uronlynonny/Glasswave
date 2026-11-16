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
 *
 * On a Mac it's the same, from the "mac" release: the new .dmg downloads in the background, and
 * "Restart to update" (or quitting) swaps the new Glasswave.app in where the old one was and opens
 * it again. If that folder can't be written to, the .dmg opens so it can be dragged across by hand.
 */
object Updater {
    private const val REPO = "uronlynonny/Glasswave"
    /**
     * The Windows app lives in one release that's always kept current (tag "windows"), apart from
     * the phone app's releases. Its title says the version ("Glasswave for Windows 1.0.1") and it
     * holds Glasswave-Windows.msi. The website's download button points at the same file.
     */
    private val RELEASE = System.getProperty("gw.testUpdateRelease") // automated checks only: a pretend release
        ?: ("https://api.github.com/repos/$REPO/releases/tags/" + if (Os.isMac) "mac" else "windows")

    /** An Apple Silicon Mac (M1 and later); others are Intel. */
    private val appleSilicon = System.getProperty("os.arch") == "aarch64"
    /** The Mac release's .dmg for this kind of Mac (Intel copies are only made by the checks for now). */
    private val macDmg = if (appleSilicon) "Glasswave-Mac.dmg" else "Glasswave-Mac-Intel.dmg"
    /** What the installer files are: .dmg on a Mac, .msi on Windows. */
    private val ext = if (Os.isMac) ".dmg" else ".msi"
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

    /**
     * Installed from the Microsoft Store? Then the Store keeps Glasswave up to date and the app
     * never downloads installers itself. (Windows says so: a Store app runs inside a "package".)
     */
    val fromStore: Boolean by lazy {
        if (!Os.isWindows) return@lazy false
        runCatching {
            val k = com.sun.jna.Native.load("kernel32", StorePackage::class.java)
            val len = com.sun.jna.ptr.IntByReference(0)
            // 15700 = APPMODEL_ERROR_NO_PACKAGE: a normal (not Store) install.
            k.GetCurrentPackageFullName(len, null) != 15700
        }.getOrDefault(false)
    }

    private interface StorePackage : com.sun.jna.Library {
        fun GetCurrentPackageFullName(length: com.sun.jna.ptr.IntByReference, name: CharArray?): Int
    }

    /** Opens the Store's Downloads and updates page. */
    private fun openStoreUpdates() {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("ms-windows-store://downloadsandupdates")) }
    }

    /** Closes the app (set by the window), so the installer can replace its files. */
    var exitApp: () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { android.content.AppContext.getSharedPreferences("updater", Context.MODE_PRIVATE) }
    private val dir by lazy { File(android.content.AppDirs.cache, "updates").apply { mkdirs() } }
    private fun fileFor(r: Release) = File(dir, "Glasswave-${r.version}$ext")

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        lastChecked = prefs.getLong("last", 0L)
        // Installers for this version or older aren't needed any more.
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".sh") || f.name.endsWith(".log")) return@forEach
            val v = f.name.removePrefix("Glasswave-").removeSuffix(".part").removeSuffix(".msi").removeSuffix(".dmg")
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
        System.getProperty("gw.testAppVersion") // automated checks only: pretend to be an older copy
            ?: Updater::class.java.getResource("/glasswave-version.txt")?.readText()?.trim() ?: "0"

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
        // Test copies (started with -Dgw.noUpdate) never update or install anything.
        if (System.getProperty("gw.noUpdate") != null) {
            if (tell) status = "This is a test copy, so it doesn't update."
            return
        }
        if (!Os.isWindows && !Os.isMac) {
            if (tell) status = "On Linux, get new versions from Glasswave's releases on GitHub."
            return
        }
        if (fromStore) {
            if (tell) {
                status = "Glasswave updates through the Microsoft Store."
                openStoreUpdates()
            }
            return
        }
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
                    if (tell) status = if (r == null && Os.isMac && !appleSilicon) "New versions for Intel Macs aren't on GitHub yet: for now they're made for Apple Silicon Macs (M1 and later)."
                        else "You're on the latest version (${currentVersion()})."
                }
            } catch (e: Exception) {
                if (tell) status = "Couldn't check for updates. Check your internet."
            } finally {
                checking = false
            }
        }
    }

    fun checkIfDue() {
        if (fromStore || !(Os.isWindows || Os.isMac)) return
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
            val name = a.optString("name")
            if (if (Os.isMac) name == macDmg else name.lowercase().endsWith(".msi")) {
                val version = versionIn.find(json.optString("name"))?.value ?: return null
                return Release(version, json.optString("body"), a.optString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /**
     * For updates only: waits up to a minute when the connection pauses (the normal client gives up
     * after 20 seconds, which a big download on a shaky connection kept hitting).
     */
    private val downloadClient by lazy {
        YtRepo.http.newBuilder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Downloads the new installer in the background (once), showing progress on the Update button.
     * If the connection drops it keeps what it already has and carries on from there: a few quick
     * retries now, and the next check (or the Update button) picks up where it stopped.
     */
    private fun download(r: Release) {
        val final = fileFor(r)
        if (final.exists() && (r.size <= 0 || final.length() == r.size)) { ready = final; return }
        if (progress != null) return
        progress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val part = File(dir, final.name + ".part")
                    val waits = listOf(2L, 5L, 10L, 20L, 30L)
                    var attempt = 0
                    while (true) {
                        try {
                            fetchInto(part, r) { p -> scope.launch { progress = p } }
                            break
                        } catch (e: IOException) {
                            if (attempt >= waits.size) throw e
                            android.util.Log.w("Glasswave", "Update download paused (${e.message}); trying again", e)
                            kotlinx.coroutines.delay(waits[attempt++] * 1000)
                        }
                    }
                    if (r.size > 0 && part.length() != r.size) { part.delete(); throw IOException("The download didn't match the update") }
                    final.delete()
                    if (!part.renameTo(final)) throw IOException("Couldn't save the update")
                }
                ready = final
                status = null
                // Automated checks only: install it straight away, as if "Restart to update" was clicked.
                if (System.getProperty("gw.testUpdateNow") != null) restartToUpdate()
            } catch (e: Exception) {
                android.util.Log.w("Glasswave", "Update download failed", e)
                status = "Couldn't finish downloading the update. It'll carry on from where it stopped."
            } finally {
                progress = null
            }
        }
    }

    /** One go at downloading into [part], continuing after whatever it already holds. */
    private fun fetchInto(part: File, r: Release, onProgress: (Float) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (r.size > 0 && have >= r.size) { if (have > r.size) { part.delete(); have = 0 } else return }
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "Glasswave")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            // 206: the server sent the rest. 200: it sent the whole file, so start again.
            val append = have > 0 && resp.code == 206
            if (!append) have = 0
            val body = resp.body ?: throw IOException("Empty download")
            val total = r.size.takeIf { it > 0 } ?: (body.contentLength().takeIf { it > 0 }?.plus(have) ?: -1L)
            var done = have
            // Automated checks only: pretend the connection drops after this many bytes, once.
            val dropAt = System.getProperty("gw.testUpdateDrop")?.toLongOrNull()?.also { System.clearProperty("gw.testUpdateDrop") }
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (dropAt != null && done - have >= dropAt) throw IOException("test: connection dropped")
                        if (total > 0 && done - lastShown > 512 * 1024) { lastShown = done; onProgress((done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
            if (r.size > 0 && done < r.size) throw IOException("The download stopped early")
        }
    }

    /** The Update button: install now if it's downloaded, otherwise check (and download). */
    fun install(@Suppress("UNUSED_PARAMETER") activity: Any?) {
        when {
            ready != null -> restartToUpdate()
            progress != null -> status = "Still downloading the update (${((progress ?: 0f) * 100).toInt()}%). It'll be ready to install when it's done."
            else -> check(tell = true)
        }
    }

    /** Installs the downloaded update and opens Glasswave again. */
    fun restartToUpdate() {
        val msi = ready ?: return
        if (Os.isMac && macApp()?.let { canReplace(it) } != true) return openDmgByHand(msi)
        if (runInstaller(msi, reopen = true)) exitApp()
    }

    /** Called as the app closes: a downloaded update installs silently, ready for next time. */
    fun installOnExit() {
        if (fromStore || !(Os.isWindows || Os.isMac) || System.getProperty("gw.noUpdate") != null) return
        val f = ready ?: return
        if (Os.isMac && macApp()?.let { canReplace(it) } != true) return
        runInstaller(f, reopen = false)
    }

    /**
     * "Restart to update": Glasswave Setup (desktop/setup) waits for the app to close, installs the
     * update in its own small window (the liquid glass opening and a progress bar), then reopens
     * Glasswave. It runs from a copy in the updates folder, as its own file gets replaced. False if
     * it isn't there (then the plain installer runs instead).
     */
    private fun runSetupWindow(msi: File, app: String?): Boolean = runCatching {
        val shipped = System.getProperty("compose.application.resources.dir")
            ?.let { File(it, "setup/GlasswaveSetup.exe") }?.takeIf { it.isFile } ?: return false
        val setup = File(dir, "GlasswaveSetup.exe")
        shipped.copyTo(setup, overwrite = true)
        val me = ProcessHandle.current()
        val pids = listOfNotNull(me.pid(), me.parent().orElse(null)?.takeIf { it.info().command().orElse("").endsWith("Glasswave.exe", true) }?.pid())
        val cmd = mutableListOf(setup.absolutePath, "--msi", msi.absolutePath, "--update", "--wait", pids.joinToString(","))
        if (app != null) { cmd += "--launch"; cmd += app }
        ProcessBuilder(cmd)
            .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        ready = null
        true
    }.getOrDefault(false)

    /**
     * Starts the installer from a tiny script that outlives the app: it waits for Glasswave to
     * close, installs quietly (a small progress window after "Restart to update", nothing at all
     * when you just close the app), then reopens it only after "Restart to update".
     */
    private fun runInstaller(msi: File, reopen: Boolean): Boolean =
        if (Os.isMac) runMacScript(msi, reopen)
        else (reopen && runSetupWindow(msi, System.getProperty("jpackage.app-path"))) || runScript(msi, reopen)

    /** This copy's Glasswave.app (from its launcher, .../Glasswave.app/Contents/MacOS/Glasswave), if it is one. */
    private fun macApp(): File? = System.getProperty("jpackage.app-path")
        ?.let { File(it).parentFile?.parentFile?.parentFile }
        ?.takeIf { it.name.endsWith(".app") && it.isDirectory }

    /** The app can be swapped for the new one: its folder (usually Applications) can be written to. */
    private fun canReplace(app: File) = app.parentFile?.canWrite() == true && app.canWrite()

    /**
     * When the app can't replace itself (it's running from the .dmg, or this user can't write to
     * Applications): the new .dmg opens in Finder, to drag Glasswave into Applications as the first time.
     */
    private fun openDmgByHand(dmg: File) {
        runCatching { ProcessBuilder("open", dmg.absolutePath).start() }
        status = "The new version is open in Finder: drag Glasswave into Applications and choose Replace, then open it again."
    }

    /** A shell word: [s] in single quotes. */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The Mac's update: a small script that outlives the app waits for it to close, copies the new
     * Glasswave.app out of the .dmg next to the old one, swaps the two, and opens it again (only
     * after "Restart to update"). If anything goes wrong the old app stays where it was.
     */
    private fun runMacScript(dmg: File, reopen: Boolean): Boolean = try {
        val app = macApp() ?: throw IOException("Not running from Glasswave.app")
        val script = File(dir, "install-update.sh")
        val log = File(dir, "install-update.log")
        script.writeText(
            """
            |#!/bin/bash
            |APP=${q(app.absolutePath)}
            |DMG=${q(dmg.absolutePath)}
            |PID=${ProcessHandle.current().pid()}
            |echo "Updating ${'$'}APP from ${'$'}DMG"
            |# Wait (up to a minute) for Glasswave to close.
            |for i in ${'$'}(seq 1 60); do kill -0 ${'$'}PID 2>/dev/null || break; sleep 1; done
            |MNT=${'$'}(mktemp -d /tmp/glasswave-update.XXXXXX)
            |if hdiutil attach "${'$'}DMG" -nobrowse -readonly -noautoopen -mountpoint "${'$'}MNT" >/dev/null; then
            |  DIR=${'$'}(dirname "${'$'}APP")
            |  NEW="${'$'}DIR/.Glasswave-update.app"; OLD="${'$'}DIR/.Glasswave-old.app"
            |  rm -rf "${'$'}NEW" "${'$'}OLD"
            |  if ditto "${'$'}MNT/Glasswave.app" "${'$'}NEW"; then
            |    if mv "${'$'}APP" "${'$'}OLD"; then
            |      if mv "${'$'}NEW" "${'$'}APP"; then rm -rf "${'$'}OLD"; rm -f "${'$'}DMG"; echo "Updated"
            |      else mv "${'$'}OLD" "${'$'}APP"; echo "Could not put the new app in place"; fi
            |    fi
            |    xattr -dr com.apple.quarantine "${'$'}APP" 2>/dev/null
            |  else echo "Could not copy the new app"; fi
            |  rm -rf "${'$'}NEW"
            |  hdiutil detach "${'$'}MNT" -quiet || hdiutil detach "${'$'}MNT" -force -quiet
            |else echo "Could not open the update"; fi
            |rmdir "${'$'}MNT" 2>/dev/null
            |${if (reopen) "open \"\$APP\"" else ""}
            |rm -f "${'$'}0"
            |""".trimMargin(),
        )
        ProcessBuilder("/usr/bin/nohup", "/bin/bash", script.absolutePath)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(log)
            .redirectErrorStream(true)
            .start()
        ready = null
        true
    } catch (e: Exception) {
        android.util.Log.e("Glasswave", "Couldn't start the update", e)
        status = "Couldn't start the update."
        false
    }

    private fun runScript(msi: File, reopen: Boolean): Boolean = try {
        val app = System.getProperty("jpackage.app-path")
        val script = File(dir, "install-update.cmd")
        // This copy of Glasswave and its launcher: the installer waits until both have closed.
        val me = ProcessHandle.current()
        val pids = listOfNotNull(me.pid(), me.parent().orElse(null)?.takeIf { it.info().command().orElse("").endsWith("Glasswave.exe", true) }?.pid())
        script.writeText(
            buildString {
                appendLine("@echo off")
                // (ping is the pause: "timeout" doesn't work without a window.)
                appendLine("ping -n 3 127.0.0.1 >nul")
                // Wait (up to a minute) until Glasswave has really closed, so files aren't in use.
                for (pid in pids) {
                    appendLine("set n=0")
                    appendLine(":wait$pid")
                    appendLine("set /a n+=1")
                    appendLine("if %n% gtr 60 goto go$pid")
                    appendLine("tasklist /FI \"PID eq $pid\" /NH | find \" $pid \" >nul && (ping -n 2 127.0.0.1 >nul & goto wait$pid)")
                    appendLine(":go$pid")
                }
                appendLine("msiexec /i \"${msi.absolutePath}\" ${if (reopen) "/passive" else "/qn"} /norestart")
                // Only when you clicked "Restart to update". Closing the app never reopens it.
                if (reopen && app != null) appendLine("start \"\" \"$app\"")
                appendLine("del \"%~f0\"")
            },
        )
        // Runs with no window of its own.
        ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/b", "cmd.exe", "/c", script.absolutePath)
            .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        ready = null
        true
    } catch (e: Exception) {
        android.util.Log.e("Glasswave", "Couldn't start the update", e)
        status = "Couldn't start the update."
        false
    }
}
