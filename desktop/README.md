# Glasswave for Windows and Mac

The desktop version of Glasswave. The same code builds for Windows and for Mac: build on the
computer you're building for (a Mac build has to be made on a Mac). It's a separate build from the Android app: the shared code
was copied in from `app/` and adapted, so changes to one don't reach the other by themselves.

## Run, build, check

```
./gradlew -p desktop run              # open the app
./gradlew -p desktop packageMsi       # Windows installer (in %LOCALAPPDATA%\GlasswaveBuild\desktop\compose\binaries\main\msi)
./gradlew -p desktop deckCheck        # play a few seconds of a song without the window (checks audio)
./gradlew -p desktop screenCheck -Pgwscript=path\to\script.txt   # draw the app off-screen and run a click script (see DevScript.kt)
./gradlew -p desktop makeIcon         # remake icons/glasswave.ico after changing the icon design
```

The version number and the YouTube library version are read from `app/build.gradle.kts`, so the
two apps stay in step.

## How it differs from the phone app

- **Audio**: FFmpeg (through JavaCV) instead of Android's player. Two audio "decks" let songs
  follow each other without a gap, crossfade and Automix. See `AudioDeck.kt` and `PlayerState.kt`.
- **Android basics**: the few Android classes the shared code uses (saved settings, web addresses,
  logging, "back") have small desktop stand-ins under `src/main/kotlin/android*`, so the shared
  files stay close to the phone versions.
- **Layout**: from 900 px wide, a sidebar and a player bar along the bottom (`DesktopShell.kt`);
  narrower windows use the phone layout. Sideways shelves get arrow buttons (`Shelves.kt`).
- **Feel**: animations run at 65% of the phone's length; buttons have a hover highlight and a hand
  cursor; type is Windows 11's Segoe UI Variable.
- **Shortcuts**: Space plays/pauses, Ctrl+→ / Ctrl+← skip, Esc or the mouse's back button goes back.
- **Not on desktop yet**: YouTube Music sign-in, music videos, voice search, the home-screen widget.

## On a Mac

Needs Java 21 (`brew install openjdk@21`, then `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`).
Run Gradle with `sh ./gradlew` (the script isn't marked executable in this repository).

```
sh ./gradlew -p desktop run            # open the app
sh ./gradlew -p desktop packageDmg     # Mac installer (in desktop/build/compose/binaries/main/dmg)
sh ./gradlew -p desktop makeMacIcon    # remake icons/glasswave.icns after changing the icon design
```

- **Files**: `~/Library/Application Support/Glasswave` and `~/Library/Caches/Glasswave`.
- **Window**: the Mac's own title bar, made see-through, so the red, yellow and green buttons sit
  over the song's colours. The red button hides the window and the music keeps playing; click
  Glasswave in the Dock to bring it back, ⌘Q to quit.
- **Shortcuts**: ⌘→ / ⌘← skip (instead of Ctrl). Type is San Francisco.
- **Sign-in**: YouTube Music sign-in opens Google's page in a small window of its own, built from
  `signin/SignIn.swift` with Apple's WebKit (needs Xcode's command line tools to build). It starts
  fresh and private each time and keeps nothing after it closes.
- **Not on Mac yet**: updating itself (get new versions from the website), media keys outside the window and Now Playing
  in Control Center. The app isn't signed with an Apple Developer ID, so the first time, right-click
  it and choose Open.
