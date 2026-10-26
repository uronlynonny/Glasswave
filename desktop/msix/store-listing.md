# Glasswave in the Microsoft Store: listing text (draft)

These are the words for Partner Center. Nothing here goes live until nonny dev approves it and
pastes it in. The privacy policy at the bottom also has to go on the website (Microsoft needs a
link to it).

## Product name
Glasswave

## Category
Music

## Short description (up to 1,000 characters; shown first)
A free, ad-free music player for Windows, with a liquid-glass look that takes on the colours of
whatever is playing.

## Description
Glasswave is a free music player for Windows. It needs an internet connection and plays music
from YouTube Music.

- Ad-free playback, in the background while you work or game
- A see-through, liquid-glass look that takes on each song's cover colours
- Synced lyrics that light up word by word
- A 10-band equalizer with ready-made sounds, bass and treble boost, speed, pitch, reverb and more
- Crossfade and Automix, which blends one song into the next like a DJ
- Flow: a nonstop mix made from what you like
- Import playlists from a link: Spotify, YouTube Music, YouTube, SoundCloud, Apple Music, Audiomack and Deezer
- Play the music files on your PC
- Your own look: highlight colour, a picture, GIF or video wallpaper, and your own font
- Phone and PC together: see and control what's playing on your other devices
- Light on your PC: stays under 300 MB of memory

## What's new in this version
- Now in the Microsoft Store
- Much lighter on memory and processor
- A see-through title bar in the song's colours
- Highlight colour can match the song or stay your own

## Search terms (up to 7)
music player, youtube music, lyrics, equalizer, playlists, music, offline

## Privacy policy URL
https://uronlynonny.github.io/Glasswave/privacy.html (once the page below is on the website)

## Notes for certification (Partner Center, "Notes for certification")
Glasswave is a desktop (Win32) music player packaged as MSIX. It needs runFullTrust because it is a
Java desktop app that plays audio with FFmpeg and draws its window with OpenGL. No account is
needed to try it: open the app, search for any song and press play. Signing in to YouTube Music is
optional.

## Why it needs runFullTrust (Partner Center asks for this)
Glasswave is a classic desktop app (Java with a bundled runtime) rather than a UWP app; it needs
full trust to run its own executable, play audio and use the network like any desktop app.

---

## Privacy policy (draft, for the website)

**Glasswave privacy policy**

Glasswave is made by nonny dev. This page says what Glasswave keeps, what it sends, and to whom.

**Kept on your device.** Your library, playlists, liked songs, listening history, listening stats,
settings, downloads and the lyrics it has found are stored on your own device. They aren't sent to
nonny dev.

**Signing in.** If you sign in to YouTube Music (optional), the sign-in is kept on your device and
used only to talk to YouTube Music for you. If you connect Spotify (optional), its sign-in is kept
on your device the same way.

**Services Glasswave talks to.** To play music and show lyrics, Glasswave connects to YouTube and
YouTube Music (music, search and your account if signed in), and to lyrics services (LRCLIB and KuGou),
which receive the song's title, artist and length. Downloading updates (outside the
Microsoft Store) goes through GitHub. Each of these services has its own privacy policy.

**Phone and PC sync (optional).** When you're signed in to YouTube Music and sync is on, your
devices share what they're playing and the songs they've played, as encrypted messages through
ntfy.sh, a public message relay that deletes messages after 12 hours. The key comes from your
account, so only your own devices can read them. You can turn this off in Settings.

**Usage count (optional).** Once a day Glasswave sends "in use today" with a random number made
when it was first opened, the platform and the app version, so nonny dev can count how many people
use it. It isn't linked to you, your device or any account. You can turn this off in Settings.

**Songs that won't play (optional).** When a song fails to play, Glasswave can tell nonny dev which
song it was, the error, and the Windows and app versions, so it can be fixed. Nothing about you is
included. You can turn this off in Settings.

**Discord.** While the Discord app is open on your PC, Glasswave tells it what you're playing, so it
shows on your Discord profile. You can turn this off in Settings.

**Voice search.** Voice search listens through your microphone only while you're using it, and the
speech is turned into text on your device. The recording isn't sent anywhere.

**Children.** Glasswave isn't aimed at children and doesn't knowingly collect anything from them.

**Changes and contact.** If what Glasswave collects changes, this page will be updated first.
Questions: open an issue at https://github.com/uronlynonny/Glasswave/issues.
