# How Ludolog and Ludolog Link work together

[Ludolog Link](https://github.com/monkikolab/ludolog-link) is a separate app: an Android app that
runs next to Ludolog on each device, and a Windows app for the PC. Link does all the networking.
Ludolog has no server and no sync code of its own; its side of the deal is small:

- a data folder that Link can read, plus a `link/` folder where the two apps leave files for each
  other;
- a few broadcasts in each direction;
- a couple of questions Ludolog asks Link directly, such as the save check before a game starts.

Ludolog works on its own. Without Link installed, the files Ludolog writes for it are never read,
its broadcasts reach no one, and nothing else changes.

## Same signing key

Both apps declare the permission `<Ludolog's package>.permission.LINK` (`com.felp.frontcomp.permission.LINK`)
with `signature` protection. Ludolog's receiver (`LinkBridge.kt`) only accepts broadcasts from an app that holds it,
and Ludolog sends its own broadcasts only to Link's package, with that permission. Android grants a
signature permission only to apps signed with the same key, so **Ludolog and Link must be signed
with the same key** to talk to each other. It doesn't matter which one is installed first.

## The data folder

Ludolog keeps its data in a `Ludolog` folder on the device's storage; Link finds it by looking for
`Ludolog/config.xml` on each storage volume.

- **Link reads** the settings in `config.xml` (theme and ROM folders), the Companion logbooks in
  `companion/`, game info, the console catalog, media and themes. Logbooks are copied in a
  consistent way without writing to the originals. The PC app keeps a copy of the essentials, and
  can take full backups.
- **Link writes** only in a few places: other devices' Companion logbooks in `companion/` (never
  this device's own), art and videos in `media/<console>/<kind>/`, and files in `link/`.
- **Link never edits `config.xml` or replaces an open database.** Ludolog keeps its settings in
  memory and would overwrite an outside change, and swapping a SQLite file under an open
  connection corrupts it. Instead Link leaves the change in `link/` and asks Ludolog to apply it.

| File in `link/` | Written by | What it holds |
|---|---|---|
| `played.tsv` | Ludolog | which emulator opened and closed which game |
| `edits.tsv` | Ludolog | game and console info corrected on this device |
| `edits-in.json` | Link | corrections from other devices, waiting to be applied |
| `config-update.json` | Link | settings changed on the PC, waiting to be applied |
| `restore/` | Link | a backup waiting to be put back |

## How the apps notify each other

All actions are named `com.felp.frontcomp.link.<NAME>`.

**From Link to Ludolog** (received by `LinkBridge.kt`):

- `LIBRARY_CHANGED`: ROMs were added or removed; Ludolog rescans its library.
- `MOVED`: ROMs were renamed. A game's data (favorite, play count, chosen emulator, name, info and
  art) is tied to the ROM's path, so Ludolog moves all of it to the new path. Companion history is
  not rewritten.
- `MEDIA_CHANGED`: Link added, replaced or removed art or videos; Ludolog refreshes its art index.
- `CONFIG_CHANGED`, `RESTORE`, `EDITS_IN`: settings, a backup or game info are waiting in `link/`.
- `STATE`: Link was turned on or off, or its sharing or PC Link setting changed.

If Ludolog isn't running, the broadcast starts it. Anything still waiting in `link/` is also
applied the next time Ludolog starts.

**From Ludolog to Link:**

- `GAME_OPENED` and `GAME_CLOSED`: an emulator started or returned (see below).
- `COMPANION_CHANGED`: a Companion session was saved.
- `EDITS_CHANGED`: the user corrected a game's or console's info.
- `KEYS_CHANGED`: the user changed an art source key (IGDB). The keys aren't in the broadcast; Link
  asks for them (see below).

Ludolog also asks Link questions directly through Link's content provider
(`<Link's package>.savecheck`): the save check before playing, and whether Link is listening.

Link reads and writes the art source keys through Ludolog's content provider
(`<Ludolog's package>.keys`), which answers only Link's package with the signature permission:
`get` returns each key with the time it was set, and `put` stores the ones that are newer than
Ludolog's own, sealing them with this device's keystore key.
The development builds (Ludolog Dev, Link Dev) use their own packages, so they only talk to each
other.
If the system stopped Link's background service, Ludolog wakes it when it comes back to the
foreground, since Android lets the app on screen start it.

## Saves and the check before playing

Link shares each emulator's saves between devices. To know when, Ludolog reports every game it
opens and closes: it adds a line to `link/played.tsv` (emulator package, console, file, times) and
sends `GAME_OPENED` or `GAME_CLOSED`. Link reads the file when it starts, to catch up on what was
played while it was off and to avoid touching an emulator that has a game open. This works even
with the Companion turned off.

Before launching a game in an emulator, if Link is installed, Ludolog asks it whether another
device has a newer save. Link has usually asked the other devices already, so the answer is quick;
Ludolog shows "Checking saves with Ludolog Link…" and waits up to 9 seconds. Link answers:

- **ok**: play.
- **synced**: Link brought the newer save over; Ludolog says so and starts the game.
- **stale**: the newer save is on a device that doesn't answer. Ludolog offers *Wait* or *Play
  anyway*.
- **conflict**: the save changed on both devices. Ludolog offers *Wait*, *Play anyway* or *Open
  Link*.
- **off**: the newer save is elsewhere, but sharing is turned off in Link. Ludolog offers *Turn on
  sharing* or *Play anyway*. Link never turns sharing back on by itself.

If Link isn't installed, "Check before playing" is off in Link, or Link fails or doesn't answer in
time, Ludolog launches the game as usual.

## Settings, art and backups from the PC

- **Settings.** Changes prepared in the PC app are written to `link/config-update.json`, and Ludolog
  applies them itself, then restarts as it does after a theme change, waiting until no game is
  being recorded and the app is in front. Credentials and the device's Companion identity can
  never be changed from outside.
- **Art and videos.** The PC searches for art with Ludolog's own scraper code and the same sources,
  IGDB included, and Link saves the results in the device's `media/` folder. As on the device, a
  single game's box art or video can be searched and chosen from what turns up.
- **Restoring a backup.** Link stages the files in `link/restore/` and marks the set complete only
  when every file has arrived. Ludolog restarts and moves them into place at startup, before
  anything opens the databases. An interrupted upload is never applied, and `config.xml` is never
  replaced as a file: restored settings go through `config-update.json` like any other change.

## Sharing between devices

- **Companion sessions.** After each saved session Ludolog sends `COMPANION_CHANGED`. Link exchanges
  logbooks with paired devices and writes theirs into `companion/`, under a temporary hidden name
  until each one is complete. Ludolog reads other devices' logbooks from a cached copy, so they can
  be replaced while it runs. For games played only on another device, Link also brings a small cover
  into `companion/covers/`.
- **Game info.** Corrections to a game's name, description or genre, and to a console's name or
  description, are logged in `link/edits.tsv`, and Link passes them to the other devices and the PC.
  Games are matched by their contents, so a correction still applies when the file has a different
  name. The latest correction wins, and a correction always beats the catalog; information that
  comes only from the catalog doesn't travel. Corrections that arrive are applied without being
  logged again, so they don't bounce back.
- **Art source keys.** The IGDB keys entered on one device, or in the PC app, reach every paired
  device and the PC; the most recent change wins, and clearing them travels too. Link's traffic
  isn't encrypted yet, so the keys travel in a box of their own: each side makes a one-time key pair
  (ECDH on P-256), and AES-GCM with a key derived from both and from the pairing key. On the device
  Ludolog seals them as always; on the PC they are kept encrypted with the Windows account (DPAPI).

## Link inside Ludolog

With Link installed, Ludolog adds a Ludolog Link entry after the Companion in the console list: it
opens Link and shows whether it is listening and sharing, whether PC Link is on, and how many
devices are paired. In the Parlour theme the room shows a radio whose light is green while Link
is listening and red when it isn't; themes can pick a room for this with the `link` condition in
`tvs.toml`. The last step of the welcome screen offers to install Link: it downloads the APK from Link's latest
release on GitHub (or uses one already on the device), and accepts it only if it is signed with the
same key as Ludolog. Settings → Data → Install Ludolog Link does the same later.

## For contributors

Link compiles some of Ludolog's own source files straight from this repository, not copies, so both
apps share one implementation: the theme data and ornaments, the Companion's calculations and tabs,
the game catalog and game info, and the art scraper. The lists are in Link's
`pc/build.gradle.kts` and `console/app/build.gradle.kts`.

- **Keep those files buildable outside Android.** Write them in Kotlin and common Compose, and put
  Android-specific code in other files. The few Android classes and Ludolog symbols they already
  use from elsewhere have stand-ins in Link (in its `kit/` and `pc/` folders). Using anything new
  from Android, or from a Ludolog file that Link doesn't compile, breaks Link's build: add a
  stand-in there too.
- **Keep their names and package.** Renaming or splitting one of these files means updating
  Link's lists.
- **Keep the contract stable.** The folder name, the `config.xml` keys Link reads, the files in
  `link/`, the logbook names and schema, and the action names and their extras are all things Link
  depends on. If you change one, change Link to match.
- **New per-game settings** tied to the ROM's path must be added to the list Ludolog moves when a
  ROM is renamed (`Prefs.GAME_KEYS`).

The version number of all three apps lives in one place, `version.properties` at the root of this
repository.
