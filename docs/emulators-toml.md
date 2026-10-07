# Emulators: emulators.toml

Each emulator is an entry in `app/src/main/assets/emulators.toml` that says how to hand it a game,
so adding an emulator or a fork takes a few lines of text, not code. The file ships inside the app:
changes need a rebuild.

```toml
[[emulator]]
id = "duckstation"
label = "DuckStation"
pkg = "com.github.stenzek.duckstation"
activity = "com.github.stenzek.duckstation.EmulationActivity"
hand = "EXTRA_PATH"
extraKey = "bootPath"

[bySystem]
psx = ["duckstation", "retroarch"]
```

| Key | Meaning |
|---|---|
| `id`, `label` | Internal name (used in `[bySystem]`) and the name shown. |
| `pkg`, `altPkgs` | Android package, and forks that accept the same intent. |
| `activity`, `action` | Activity to start and intent action. Optional. |
| `hand`, `extraKey` | How the game is passed, and the intent extra that carries it. |
| `mimeType`, `boolExtras` | Optional MIME type, and extras set to `true` (such as `autoStartGame`). |

`hand` is `DATA_URI` (the default: a `content://` URI as the intent's data), `EXTRA_PATH` (the file
path as text), `EXTRA_URI` (a `content://` URI in an extra), `FILE_INT` or `STORE_ID` (a store's game
number read from a `.steam`-style file, for PC launchers such as GameNative and GameHub Lite), or
`TITLE_ARGS` (a Vita title ID, for Vita3K).

`[bySystem]` lists each console's emulators in order of preference. Ludolog uses the first one
installed, unless the user picked another for that console or game. RetroArch (`kind = "retroarch"`)
also needs a core: `{core}` in its `corePath` becomes the console's `raCore` from
[systems.toml](systems-toml.md). It goes last, and is offered for any console with a core even when
it isn't listed.

## Adding an emulator

1. Find its package (`adb shell pm list packages`), its activity and how it takes a game: open a game
   from inside the emulator and check `adb shell dumpsys activity activities`, or read its manifest.
2. Add an `[[emulator]]` block and put its `id` in `[bySystem]`. For a fork that accepts the same
   intent, adding its package to the existing entry's `altPkgs` is usually enough.
3. Run `./gradlew testDebugUnitTest`. `LaunchTest` checks every entry in the real file; it also counts
   them, so update that number.
