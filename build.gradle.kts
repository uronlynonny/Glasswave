plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// On Windows, build outside the project folder. OneDrive syncing locks build files mid-build otherwise.
System.getenv("LOCALAPPDATA")?.let { local ->
    allprojects {
        layout.buildDirectory.set(File(local, "GlasswaveBuild/${project.name}"))
    }
}
