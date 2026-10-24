// Glasswave for Windows. A separate build from the Android app: run it with
//   ./gradlew -p desktop run
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}
rootProject.name = "glasswave-desktop"
