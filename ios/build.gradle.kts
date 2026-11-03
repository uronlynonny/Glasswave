// Glasswave for iPhone: the screens and player logic in Kotlin (Compose), packed as a framework
// that the small Xcode app in iosApp/ shows. iosApp/ also holds the Swift side: Apple's audio
// player and YouTubeKit, which finds each song's audio.
plugins {
    kotlin("multiplatform") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.10.3"
}

// On Windows, build outside the project folder (OneDrive locks build files mid-build otherwise).
// On a Mac it stays in ios/build, where the Xcode project looks for the framework.
System.getenv("LOCALAPPDATA")?.let { layout.buildDirectory.set(File(it, "GlasswaveBuild/ios")) }

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "GlasswaveKit"
            isStatic = true
        }
    }
    sourceSets {
        iosMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("io.ktor:ktor-client-core:3.3.1")
            implementation("io.ktor:ktor-client-darwin:3.3.1")
            implementation("io.coil-kt.coil3:coil-compose:3.3.0")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
        }
    }
}
