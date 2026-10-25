import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// Same version and YouTube library as the phone app, read from its build file so the two stay in step.
val appBuild = file("../app/build.gradle.kts").readText()
// Glasswave for Windows has its own version number, separate from the phone app.
// Raise it for every Windows release: installed copies update themselves to a higher number.
val appVersion = "1.0.10"
val newPipe = Regex("NewPipeExtractor:(v?[0-9][0-9.]*)").find(appBuild)?.groupValues?.get(1)
    ?: error("Couldn't find the NewPipeExtractor version in app/build.gradle.kts")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.animation)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // The YouTube source.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newPipe")

    // Audio: FFmpeg decodes every format YouTube serves; it ships inside the app, nothing to install.
    implementation("org.bytedeco:javacv:1.5.10") {
        // Only the FFmpeg part is used; leave out the camera and computer-vision libraries.
        listOf(
            "opencv", "openblas", "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense",
            "librealsense2", "videoinput", "artoolkitplus", "flandmark", "leptonica", "tesseract",
        ).forEach { exclude(group = "org.bytedeco", module = it) }
    }
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")
    implementation("org.bytedeco:javacpp:1.5.10:windows-x86_64")

    // Talks to Windows directly, for the dark title bar.
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    // Voice search: Whisper speech recognition, run on this PC.
    implementation("io.github.givimad:whisper-jni:1.7.1")
}

// The version number, readable by the app (Settings and the update check show it).
val writeVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = appVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast { out.get().file("glasswave-version.txt").asFile.apply { parentFile.mkdirs() }.writeText(v) }
}
sourceSets.main { resources.srcDir(writeVersion) }

// Plays a few seconds of a song without opening the window, printing each step (for checking audio).
tasks.register<JavaExec>("deckCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.DeckCheckKt")
    val song = (project.findProperty("song") as String?) ?: "daft punk get lucky"
    args(listOfNotNull(song, project.findProperty("only") as String?))
}

// For checks only, silent: runs an audio file through speed, pitch and the sound effects.
// ./gradlew -p desktop fxCheck -Pfile=path/to/song.wav
tasks.register<JavaExec>("fxCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.FxCheckKt")
    (project.findProperty("file") as String?)?.let { args(it) }
}

// For checks only: transcribes a WAV file with voice search's recogniser.
// ./gradlew -p desktop voiceCheck -Pwav=path/to/file.wav
tasks.register<JavaExec>("voiceCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.VoiceCheckKt")
    (project.findProperty("wav") as String?)?.let { args(it) }
    (project.findProperty("voiceModel") as String?)?.let { systemProperty("gw.voiceModel", it) }
}

kotlin {
    compilerOptions {
        // The copied phone code has a few spots Kotlin warns about; they're harmless.
        suppressWarnings.set(true)
        // Desktop-only Compose features used here: system fonts, mouse events.
        optIn.addAll("androidx.compose.ui.text.ExperimentalTextApi", "androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

compose.desktop {
    application {
        mainClass = "com.glasswave.player.MainKt"
        // ZGC: memory clean-up without pauses, so animations never hitch.
        // Light on memory: the app's own data is small (about 20-30 MB), so it uses the simple
        // "Serial" collector, which needs almost no memory of its own (G1 needed ~50 MB just for
        // bookkeeping), with a small heap that grows only when needed and shrinks back after.
        jvmArgs += listOf(
            "-Xms24m", "-Xmx512m",
            "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
            "-XX:ReservedCodeCacheSize=64m", "-XX:CICompilerCount=2", "-Xss512k",
            "-Dsun.java2d.uiScale.enabled=true",
        )
        nativeDistributions {
            // Files shipped beside the app: the YouTube Music sign-in window (see signInHelper).
            appResourcesRootDir.set(layout.buildDirectory.dir("appres"))
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Glasswave"
            packageVersion = appVersion
            description = "Glasswave music player"
            vendor = "nonny dev"
            // Java modules the app uses (from ./gradlew -p desktop suggestRuntimeModules), plus secure-connection support.
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.sql", "java.scripting", "jdk.dynalink", "jdk.unsupported", "java.desktop", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("icons/glasswave.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                menuGroup = "Glasswave"
                // Installs for this user only, so the app can update itself without asking for admin rights.
                perUserInstall = true
                upgradeUuid = "6c1f3b8e-2a4d-4f7e-9b1a-5d8c0e7f3a21"
            }
        }
    }
}

// The YouTube Music sign-in window (signin/SignIn.cs): a small Windows program that shows Google's
// sign-in page in Microsoft's WebView2 (Edge, built into Windows 11). It is compiled with the C#
// compiler that comes with Windows, against Microsoft's WebView2 SDK from NuGet (checked by hash),
// and shipped next to the app with the SDK's files and license.
val webView2Version = "1.0.4191.47"
val webView2Sha256 = "f492bbf547d0da329553b6727435b677579b1e9f91cc9e4a1ad029366d5f23d0"
val signInHelper by tasks.registering {
    val src = file("signin")
    val icon = file("icons/glasswave.ico")
    val work = layout.buildDirectory.dir("signin")
    val out = layout.buildDirectory.dir("appres/windows/signin")
    inputs.dir(src)
    inputs.file(icon)
    inputs.property("webView2", webView2Version)
    outputs.dir(out)
    doLast {
        val w = work.get().asFile.apply { mkdirs() }
        val o = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        val pkg = File(w, "webview2-$webView2Version.nupkg")
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!pkg.exists() || sha(pkg) != webView2Sha256) {
            val url = "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$webView2Version/microsoft.web.webview2.$webView2Version.nupkg"
            URI(url).toURL().openStream().use { input -> pkg.outputStream().use { input.copyTo(it) } }
            check(sha(pkg) == webView2Sha256) { "The WebView2 SDK download doesn't match its expected hash" }
        }
        val wanted = mapOf(
            "lib/net462/Microsoft.Web.WebView2.Core.dll" to "Microsoft.Web.WebView2.Core.dll",
            "lib/net462/Microsoft.Web.WebView2.WinForms.dll" to "Microsoft.Web.WebView2.WinForms.dll",
            "runtimes/win-x64/native/WebView2Loader.dll" to "WebView2Loader.dll",
            "LICENSE.txt" to "WebView2-LICENSE.txt",
        )
        ZipFile(pkg).use { z ->
            wanted.forEach { (from, to) -> z.getInputStream(z.getEntry(from)).use { i -> File(o, to).outputStream().use { i.copyTo(it) } } }
        }
        val csc = File(System.getenv("WINDIR") ?: "C:\\Windows", "Microsoft.NET/Framework64/v4.0.30319/csc.exe")
        val cmd = listOf(
            csc.absolutePath, "-nologo", "-target:winexe", "-platform:x64", "-optimize",
            "-out:" + File(o, "GlasswaveSignIn.exe").absolutePath,
            "-win32manifest:" + File(src, "SignIn.manifest").absolutePath,
            "-win32icon:" + icon.absolutePath,
            "-r:System.Windows.Forms.dll", "-r:System.Drawing.dll", "-r:System.Core.dll",
            "-r:" + File(o, "Microsoft.Web.WebView2.Core.dll").absolutePath,
            "-r:" + File(o, "Microsoft.Web.WebView2.WinForms.dll").absolutePath,
            File(src, "SignIn.cs").absolutePath,
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "Couldn't build the sign-in window: $log" }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(signInHelper) }

// Checks (screenCheck, deckCheck, voiceCheck…) never touch your real Glasswave: unless a check
// picks its own folder, it keeps its library, history, listening stats and cache in build/check-data.
// (Only "run", the real app, uses your own data.)
tasks.withType<JavaExec>().configureEach {
    if (name == "run") return@configureEach
    val data = layout.buildDirectory.dir("check-data")
    doFirst {
        val dir = data.get().asFile.apply { mkdirs() }
        if (environment["APPDATA"] == System.getenv("APPDATA")) environment("APPDATA", File(dir, "roaming").absolutePath)
        if (environment["LOCALAPPDATA"] == System.getenv("LOCALAPPDATA")) environment("LOCALAPPDATA", File(dir, "local").absolutePath)
    }
}

// On Windows, build outside OneDrive (it locks build files mid-build).
System.getenv("LOCALAPPDATA")?.let { layout.buildDirectory.set(File(it, "GlasswaveBuild/desktop")) }

// Draws the app off-screen and runs a script of clicks against it, saving pictures (for checks).
tasks.register<JavaExec>("screenCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.DevScriptKt")
    (project.findProperty("gwscript") as String?)?.let { args(it) }
    (project.findProperty("voiceWav") as String?)?.let { systemProperty("gw.voiceTestWav", it) }
    if (project.hasProperty("testDrop")) systemProperty("gw.testDrop", "1")
    // -Poffline: cuts the app off from the internet (everything goes to a proxy that isn't there).
    if (project.hasProperty("offline")) { systemProperty("socksProxyHost", "127.0.0.1"); systemProperty("socksProxyPort", "9") }
    // -PsyncTest: signed in to a made-up account as far as phone/PC sync goes (for pictures of Devices).
    if (project.hasProperty("syncTest")) { systemProperty("gw.syncTestAccount", "screen-test-account"); systemProperty("gw.noCheckIn", "1") }
    // -PsignInTest=<folder>: the YouTube Music sign-in window hands back signed-out cookies (no
    // account needed), and the app keeps its data in that folder instead of yours.
    (project.findProperty("signInTest") as String?)?.let { dir ->
        dependsOn(signInHelper)
        environment("GW_SIGNIN_TEST", "1")
        systemProperty("gw.signInUrl", "https://music.youtube.com/")
        environment("APPDATA", "$dir/roaming")
        environment("LOCALAPPDATA", "$dir/local")
        systemProperty("gw.appResources", layout.buildDirectory.dir("appres/windows").get().asFile.absolutePath)
    }
}

// Remakes the website's link preview picture, docs/og.png (see OgImage.kt).
// Downloads the live update as an older copy would, with the connection dropping partway
// (-Pdir: a sandbox folder for its files).
tasks.register<JavaExec>("updateCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.UpdateCheckKt")
    (project.findProperty("dir") as String?)?.let { environment("LOCALAPPDATA", it); environment("APPDATA", it) }
    systemProperty("gw.testAppVersion", "1.0.0")
    systemProperty("gw.testUpdateDrop", "3000000")
}

// Two pretend devices on a made-up account talk through the relay (sync between phone and PC).
tasks.register<JavaExec>("syncCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.SyncCheckKt")
    systemProperty("gw.syncDir", layout.buildDirectory.dir("check-data/sync").get().asFile.absolutePath)
}

// Checks that a second launch hands over to the open copy (-Pdir: a sandbox folder for its files).
tasks.register<JavaExec>("instanceCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.InstanceCheckKt")
    (project.findProperty("dir") as String?)?.let { environment("APPDATA", it) }
}

tasks.register<JavaExec>("titleCheck") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.TitleCheckKt")
    (project.findProperty("out") as String?)?.let { args(it) }
}

tasks.register<JavaExec>("makeOg") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.OgImageKt")
    args(file("../docs/og.png").absolutePath)
}

// Remakes icons/glasswave.ico from the icon design (see IconMaker.kt).
tasks.register<JavaExec>("makeIcon") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.glasswave.player.IconMakerKt")
    args(file("icons/glasswave.ico").absolutePath)
}
