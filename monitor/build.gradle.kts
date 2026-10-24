plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

// Test with exactly the YouTube library version the app ships, read from the app's build file.
val appBuild = file("../app/build.gradle.kts").readText()
val newPipe = Regex("NewPipeExtractor:(v?[0-9][0-9.]*)").find(appBuild)?.groupValues?.get(1)
    ?: error("Couldn't find the NewPipeExtractor version in app/build.gradle.kts")

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newPipe")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("MonitorKt")
}

tasks.named<JavaExec>("run") {
    // Paths given on the command line are relative to the repository folder.
    workingDir = rootDir.parentFile
    environment("NEWPIPE_VERSION", newPipe)
}

// On Windows, build outside OneDrive (it locks build files mid-build).
System.getenv("LOCALAPPDATA")?.let { layout.buildDirectory.set(File(it, "GlasswaveBuild/monitor")) }
