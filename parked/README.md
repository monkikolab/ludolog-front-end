# Parked

Working code that is not part of the app. Nothing in this folder is built: only `app/src` goes
into the APK.

## ArtScreenScraper.kt

An art source for ScreenScraper. It is complete but has never been run, because ScreenScraper's
API refuses every request that doesn't include a pair of developer credentials, which are requested
on its forum. It is the only known source that serves gameplay videos as files rather than YouTube
links.

To bring it back:

1. Move the file to `app/src/main/java/com/felp/frontcomp/`.
2. Add `ScreenScraperSource()` to the list of sources in `Scrape.kt`.
3. Add its entry back to `ART_TIERS` in `ScrapeSetup.kt`, with fields for the user account and the
   developer credentials.

## TapeSound.kt

An audio chain that makes a video's sound play like a worn tape on an old TV across a large room,
using Android's built-in equalizer and reverb effects.
