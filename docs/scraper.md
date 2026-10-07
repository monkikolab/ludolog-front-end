# Game info, box art and videos

Ludolog works out what each game file is from its contents, looks it up in its game catalog for
names, genres, dates and synopses, and then fetches box art and a gameplay video by the game's
exact name. Without the catalog it still works, searching art by file name.

## Identifying a game

File names are often wrong: a disc labelled with the wrong region, a Mega Drive game in the 32X
folder, a ROM renamed by hand. So Ludolog reads what is inside each file instead: the CRC of a
cartridge (inside a zip, without extracting it), the serial or checksum of a disc image, the disc
ID or game code of Nintendo discs and DS/3DS cartridges, the romset name of an arcade game, the
app ID in a `.steam` shortcut. Switch games are the exception: their contents are encrypted, so the
title ID in the file name is used.

This happens once per file. It gives the game's canonical name (No-Intro, Redump, FinalBurn Neo or
MAME), its region and the console it really belongs to, whatever folder it is in.

## The game catalog

The catalog is built on a PC by the scripts in [tools/catalog](../tools/catalog/README.md) and
published as release files of [ludolog-assets](https://github.com/monkikolab/ludolog-assets): one
package per console, plus one with synopses. The welcome screen offers to download it and Settings
can update it; only the packages for your consoles are downloaded.

A game is looked up from the surest match to the least: by the keys read from its contents, by its
file name when that is exactly the database name, and finally by title. What is found is saved as
plain text in Ludolog's data folder, one file per console. Corrections you make by hand are kept
apart and never overwritten, and **Game info** in a game's quick menu shows each fact and where it
came from. ES-DE gamelists, if you have them, only fill in what the catalog does not know.

| source | what it gives | licence |
|---|---|---|
| libretro-database | identity; genre, date, developer, publisher, series for many consoles | CC BY-SA 4.0 |
| Wikidata | detailed genres, first release date, developer, publisher, series | CC0 |
| Wikipedia | synopses, from each game's English article | CC BY-SA 4.0 |
| GameTDB | genres, dates, studios and synopses for Nintendo consoles and PS3 | free for software that links to it |

The catalog as a whole is under CC BY-SA 4.0; the app only reads it. Genres are kept as each
source gives them and mapped to Ludolog's own genre names when shown. Wikidata's are the most
detailed, and the only ones that reliably tell horror apart. Other game databases were left out
because of their terms or per-user limits.

## Box art and videos

Box art is tried from the surest source to the least: **libretro thumbnails** by exact name, then
**GameTDB** by disc or game ID (Nintendo consoles and PS3, the game's own region first), then **Steam**
for PC games, and finally **IGDB** if you have added your own keys.

Gameplay videos come from community collections on archive.org, one per console, named in
`systems.toml` (`videoSnaps`), so a collection that moves can be fixed without a new app version.

Both are searched in the console the game really belongs to, trying in turn the exact catalog
name, the names of the same game in other regions, its Wikidata title and the file name. Arcade
games use the exact name only. **Fetch box art** and **Fetch video** in a game's quick menu search
again and let you choose when more than one result could fit.

### IGDB keys (optional)

IGDB can find covers the open sources miss, using a free Twitch developer application of your own.
Enter its Client ID and secret in **Settings → Artwork → Art sources**. They are kept encrypted on the device
and only used to talk to Twitch and IGDB. Ludolog ships no keys of its own: a key inside an APK
could be read by anyone and would be shared by every install. IGDB is never used for the catalog.

## Known limits

- No free gameplay videos for Steam, PS3 and Switch.
- PlayStation games in CHD files are not identified by their contents yet: they are matched by
  exact name, a serial in brackets in the file name, or title.
- Lesser-known games (Japan-only releases, prototypes, homebrew) are often missing from Wikidata,
  so their genre comes from the coarser sources.

## For contributors

The app side is in `app/src/main/java/com/felp/frontcomp/`: `GameId.kt` reads files, `GameDb.kt`
searches the catalog, `Dossiers.kt` keeps what is known about each game, `Genres.kt` maps genre names,
and `Scrape.kt`, `ArtSources.kt`, `ArtFree.kt` and `ArtVideo.kt` fetch art and videos.
[tools/metaprobe](../tools/metaprobe/README.md) is the prototype the catalog scripts grew out of.
