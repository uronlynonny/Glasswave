plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.glasswave.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glasswave.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.16.8"
        // Only English text from libraries; keeps the APK small.
        resourceConfigurations += "en"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the local debug key so the APK installs straight onto your phone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation("androidx.core:core-ktx:1.15.0")
    // Lets the sign-in page leave out the header that tells Google it's inside an app.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Installs the speed-up profiles that Compose ships with, so screens and scrolling run as
    // precompiled code from the first launches instead of being interpreted.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    // Rounded icon set (unused icons are stripped from the release build).
    implementation("androidx.compose.material:material-icons-extended")
    // Picks colours out of album covers for the background.
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // Moving GIF wallpapers.
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The YouTube source. If YouTube stops working, bump this version first.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
