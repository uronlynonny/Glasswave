// Glasswave for iPhone. A separate build from the phone and PC apps. It only builds on a Mac
// (GitHub's Macs build it: .github/workflows/build-ios.yml):
//   ./gradlew -p ios linkDebugFrameworkIosSimulatorArm64
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
    }
}
rootProject.name = "glasswave-ios"
