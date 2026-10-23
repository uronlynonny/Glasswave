# Glasswave

A light Android music player with a liquid glass look, in the spirit of Riff. It plays from YouTube Music
and syncs your playlists from YouTube Music and Spotify. Built with Kotlin, Jetpack Compose and Media3.

## What it does
- **Home**: YouTube Music's feed (Quick picks, new releases, charts...). Personal when signed in.
- **Search**: songs, albums, artists and playlists, with filters and recent searches. Open any album or artist.
- **Apple Music-style player**: big cover that shrinks when paused, background in the cover's colours,
  swipe down to close, swipe the mini player to skip.
- **Settings**: audio quality (Low / Normal / High), skip silence, animations and glass on/off, accounts, storage.
- **Fast**: the whole song downloads as it starts, the next song is looked up ahead of time, and played
  songs are kept on the phone (up to 512 MB), so seeking and skipping don't stall.
- **Library**: all your synced playlists in a grid, filterable by YouTube Music / Spotify.
- **Sync**: sign in to YouTube Music and Spotify on the Accounts tab. Playlists and liked songs come in,
  and Glasswave re-syncs by itself when it's been more than 12 hours. Removed playlists disappear on the next sync.
- **Spotify songs play from YouTube Music**: each Spotify song is matched to the closest YouTube Music
  song (by name and length) the first time it plays, and the match is remembered.
- **Search** any song on YouTube Music.
- Background playback with notification and lock-screen controls, shuffle and repeat.
- **Liquid glass**: the floating tab bar, mini player and buttons bend and blur what's behind them, with a
  bright rim. Full effect on Android 13+, blur only on Android 12, plain frosted glass on older phones.

## Kept light on memory
- Release build is shrunk and optimized (R8).
- Glass effects run on the GPU from recorded drawing, no extra bitmaps.
- Artwork memory cache capped at 10% of the app's memory; the blurred background loads a tiny image.
- Audio buffer capped at about a minute / 3 MB.
- Only the list of playlists stays in memory. Songs are read from disk when you open a playlist.

## Build the APK
On this PC:

```
gradlew.bat assembleRelease
```

On Windows the build happens outside OneDrive (OneDrive locks build files mid-build), so the APK lands in
`%LOCALAPPDATA%\GlasswaveBuild\app\outputs\apk\release\app-release.apk`. Copy it to your phone and install it.
It's signed with this PC's debug key, so later builds from this PC install over it as updates.

## Spotify setup (once)
Spotify only lets apps read your playlists through a developer key you create yourself:
1. Go to https://developer.spotify.com/dashboard and create an app.
2. Add the redirect URI `http://127.0.0.1/callback` and tick **Web API**.
3. Copy the **Client ID** into Glasswave's Accounts tab and tap Sign in.

Limits set by Spotify (since February 2026): the account that creates the key needs **Spotify Premium**,
and Spotify only shares the songs of playlists you own or collaborate on. For playlists you only follow,
Glasswave reads Spotify's public playlist page instead, which works for public playlists only.

## If something stops working
- **Playback or search**: YouTube changed. Move `NewPipeExtractor` in `app/build.gradle.kts` to a newer
  version (tags: https://github.com/TeamNewPipe/NewPipeExtractor/tags) and rebuild.
- **YouTube Music sync**: `YtMusicApi.kt` reads the same private API the YouTube Music website uses.
- **Spotify sync**: `SpotifyApi.kt` uses Spotify's official Web API.
