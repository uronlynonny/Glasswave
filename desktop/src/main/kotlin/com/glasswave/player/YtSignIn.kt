// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.AppDirs
import java.io.File

/**
 * Signing in to YouTube Music on Windows. Opens Google's own sign-in page in a small window of its
 * own (GlasswaveSignIn.exe, built from desktop/signin with Microsoft's WebView2, the Edge engine
 * that comes with Windows 11). You type your details into Google's page; once it lands on YouTube
 * Music the window hands back that site's cookies, the same thing the phone app keeps after
 * signing in, and closes.
 */
object YtSignIn {
    sealed interface Result {
        data class Cookie(val value: String) : Result
        /** Google refused to sign in from this window. */
        data object Blocked : Result
        /** WebView2 (Edge's engine) isn't on this PC. */
        data object NoRuntime : Result
        /** The sign-in window isn't part of this copy of the app. */
        data object Missing : Result
        /** Closed before signing in. */
        data object Closed : Result
    }

    /** Where the sign-in window keeps Google's session, so it can be wiped on sign-out. */
    private val dataDir get() = File(AppDirs.cache, "signin")

    private fun helper(): File? =
        (System.getProperty("compose.application.resources.dir") ?: System.getProperty("gw.appResources"))
            ?.let { File(it, "signin/GlasswaveSignIn.exe") }
            ?.takeIf { it.isFile }

    @Volatile private var running: Process? = null

    // If Glasswave closes while the sign-in window is open, close that too.
    init { Runtime.getRuntime().addShutdownHook(Thread { cancel() }) }

    /** Shows the sign-in window and waits until it's done. Call off the UI thread. */
    fun run(url: String): Result {
        val exe = helper() ?: return Result.Missing
        val out = File.createTempFile("gw-signin", ".txt").apply { delete() }
        try {
            val p = ProcessBuilder(exe.absolutePath, url, dataDir.absolutePath, out.absolutePath)
                .directory(exe.parentFile)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            running = p
            p.waitFor()
            val text = out.takeIf { it.exists() }?.readText().orEmpty()
            return when {
                text.startsWith("COOKIE ") && "=" in text -> Result.Cookie(text.removePrefix("COOKIE ").trim())
                text.startsWith("BLOCKED") -> Result.Blocked
                text.startsWith("NORUNTIME") -> Result.NoRuntime
                else -> Result.Closed
            }
        } finally {
            running = null
            out.delete()
        }
    }

    /** Closes the sign-in window if it's open. */
    fun cancel() {
        running?.let { p -> p.descendants().forEach { it.destroy() }; p.destroy() }
    }

    /** Forgets the Google session the sign-in window kept (on sign-out). */
    fun forget() {
        runCatching { dataDir.deleteRecursively() }
    }
}
