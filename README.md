<p align="center"><img src="docs/media/banner.png" alt="Ludolog" width="720"></p>

# Ludolog

**v0.5.0** · A front-end for Android retro handhelds. Your games sorted by console and opened in the
right emulator, with box art, video previews and themes, plus a Companion that turns what you play
into a character.

<p align="center"><img src="docs/media/gallery.gif" alt="Browsing consoles in Ludolog with the Gallery theme" width="760"></p>

## Features

- **Your games, sorted.** Point Ludolog at your ROM folders and it sorts them by console,
  recognising each game by what's inside the file, even when its name is wrong.
- **Press A to play.** Each game opens in the right emulator. About 40 are supported, from
  RetroArch cores to standalone emulators and PC game launchers.
- **Box art and videos.** Covers and gameplay previews are fetched for you, and a free game catalog
  adds names, genres, release dates and synopses.
- **Made for handhelds.** Built for a gamepad, works with touch, adapts to any screen shape and to
  dual-screen devices, and can be your home screen.

### Three themes

| Parlour | Mainframe | Gallery |
|:---:|:---:|:---:|
| <img src="docs/media/theme-parlour.png" alt="The Parlour theme: a dark room with the game on an old TV" width="270"> | <img src="docs/media/theme-mainframe.png" alt="The Mainframe theme: an amber terminal with the game's cover and facts" width="270"> | <img src="docs/media/theme-gallery.png" alt="The Gallery theme: a clean list with the game's video" width="270"> |

Each theme has its own look, sounds and spinning consoles. Gallery comes with the app; Parlour and
Mainframe are a quick download from the welcome screen or Settings.

### The Companion

<p align="center"><img src="docs/media/companion.png" alt="The Companion: level, class, a five-sided profile and the latest sessions" width="760"></p>

Ludolog keeps a logbook of everything you play and turns it into a character. Its five-sided
profile grows with the kinds of games you play, and it gains levels, a class, achievements and
missions along the way. Open it with L2 + R2.

### Better with Ludolog Link

[Ludolog Link](https://github.com/monkikolab/ludolog-link) is an optional companion app. It shares
saves and Companion sessions between your handhelds, and lets you manage games, art and backups
from a Windows PC.

## Install

1. Download the APK from the [latest release](https://github.com/monkikolab/ludolog-front-end/releases/latest)
   and open it on your device.
2. Open Ludolog. The welcome screen sets up the rest: where your games are, the game catalog, the
   themes and, if you like, Ludolog Link.

## Still in testing

Ludolog is young. So far it has been tried on an AYN Odin 3 (Android 15) and a Retroid Pocket 5
(Android 13), with RetroArch, DuckStation, AetherSX2 / NetherSX2, ARMSX3, Dolphin, PrimeHack,
PPSSPP, Azahar, Eden, GameNative, GameHub Lite and DoomForge. Other emulators are set up but
untested. Expect rough edges, and please
[report what you find](https://github.com/monkikolab/ludolog-front-end/issues).

## Support the project

Ludolog is free, made in spare time. If you'd like to help it keep growing (more themes, more
emulators, more devices tested), you can buy me a coffee on
**[Ko-fi](https://ko-fi.com/monkikolab)**. Bug reports and feedback help just as much.

## AI assistance disclosure

AI assistance was used while building this project. I reviewed the code throughout development and
understand how the app works and what it does.

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html). See also [NOTICE](NOTICE.md).
For developers: [building from source and technical notes](docs/README.md).
