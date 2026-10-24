import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// Same version and YouTube library as the phone app, read from its build file so the two stay in step.
val appBuild = file("../app/build.gradle.kts").readText()
// Glasswave for Windows has its own version number, separate from the phone app.
// Raise it for every Windows release: installed copies update themselves to a higher number.
val appVersion = "1.0.7"
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
}

// Remakes the website's link preview picture, docs/og.png (see OgImage.kt).
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
