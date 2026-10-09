# Consoles: systems.toml

Everything Ludolog knows about a console is data, not code. The shipped catalog is
`app/src/main/assets/systems.toml`, and consoles can be added or corrected on the device without
rebuilding: Ludolog also reads `systems.toml` and then `systems/*.toml` (one console per file) from
its data folder, each merged by `id` over the previous one: a new id adds a console, an existing one
replaces it whole, and the rest stays as shipped. The Console manager in Settings saves its edits in that folder too. After editing, use
*Settings → Reload catalog*. A file with a mistake is skipped, and Settings names the line.

## A console

From [examples/game-and-watch.toml](examples/game-and-watch.toml):

```toml
[[system]]
id = "gameandwatch"
name = "Game & Watch"
label = "G&W"
raName = "Handheld Electronic Game"
raCore = "gw"
image = "systems/gameandwatch.png"
extensions = ["mgw"]
aliases = ["gameandwatch", "gw", "handheldelectronicgame"]
```

| Key | Meaning |
|---|---|
| `id` | Internal name and merge key. Required. |
| `name`, `label` | Full name, and a short one for tight spaces. |
| `raName` | No-Intro name. The [scraper](scraper.md) needs it to find box art. |
| `raCore` | RetroArch core (`snes9x`). Without it, RetroArch can't open these games. |
| `extensions`, `zipOk` | File extensions (lowercase, no dot), and whether `.zip` and `.7z` count (default yes). |
| `aliases` | Other folder names for the console. |
| `image`, `video` | Picture and spin video, relative to a media folder or absolute. By default, `systems/<id>.png` and `.mp4`, for example in `Ludolog/media/`. |
| `description`, `maker`, `year`, `specs` | Text and facts shown with the console. |
| `videoSnaps` | An archive.org collection of gameplay videos. |

## How games are matched

Folder names are compared with each console's `id`, `name`, `label`, `raName` and `aliases` using
only lowercase letters and digits, so `Super Nintendo`, `SNES` and `snes` match the same console, and
aliases only need the spellings that can't be derived that way. Nested folders such as
`ROMs/Nintendo/SNES/Hacks` work. A file belongs to the console its folder names, if the extension
fits. Elsewhere, only an extension claimed by a single console decides, and never one listed in
`ambiguousExtensions` under `[defaults]` (`.bin`, `.iso`, `.app`…).

The files use a subset of TOML: inline tables, dotted keys, dates and multi-line strings are rejected
with an error naming the line.
