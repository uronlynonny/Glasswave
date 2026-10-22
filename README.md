# Glasswave (stage 1)

A native Android music player with a glass look. It searches YouTube Music, plays audio in the
background, and has a mini player and a full-screen player. Built with Kotlin, Jetpack Compose and Media3.

## What stage 1 does
- Search songs, tap one to play. The search results become the queue (next / previous work).
- Background playback with a notification and lock-screen controls.
- Glass look: frosted translucent panels with a bright edge, drifting colour behind them, and the
  song's artwork blurred behind everything on Android 12+. Older phones get the colour drift without the blur.

Not in this stage: saved playlists, Spotify import, YouTube Music library import, a true refraction shader.

## Build the APK (GitHub, no Android Studio)
1. Make a new private repository.
2. Upload everything in this folder (not the zip itself) using "uploading an existing file".
3. Actions tab > "set up a workflow yourself", name it `build-apk.yml`, paste in the text of
   `build-apk.yml.txt` from this folder, and commit.
4. When the run is green, download **glasswave-apk** at the bottom of the run, unzip it and install `app-debug.apk`.

## If YouTube stops working
All YouTube code is in `YtRepo.kt` and `YtDownloader.kt`. Most breakages are fixed by moving
`NewPipeExtractor` in `app/build.gradle.kts` to a newer version, then rebuilding.
If the build says that version cannot be found, try another tag from
https://github.com/TeamNewPipe/NewPipeExtractor/tags
