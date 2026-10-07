# Developer notes

## Building

Requirements: JDK 21 and the Android SDK (API 37).

```bash
./gradlew assembleDebug          # the APK, in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # the unit tests
```

A release build needs your own signing key, in a `keystore.properties` file at the root of the
repository (it is ignored by git):

```properties
storeFile=/path/to/your.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

To use Ludolog Link with your build, sign Link with the same key.

## How it works

- [emulators-toml.md](emulators-toml.md) and [systems-toml.md](systems-toml.md): how emulators and
  consoles are described, to add or adjust them.
- [scraper.md](scraper.md): where game info, box art and videos come from.
- [metagame.md](metagame.md): how the Companion turns your sessions into a character.
- [screens.md](screens.md): how the layout adapts to each screen shape and to dual screens, and
  what a theme needs to look right on all of them.
- [ludolog-link.md](ludolog-link.md): how Ludolog and Ludolog Link work together.

## Tools

- [tools/catalog](../tools/catalog/README.md): the scripts that build the game catalog published in
  [ludolog-assets](https://github.com/monkikolab/ludolog-assets).
- [tools/metaprobe](../tools/metaprobe/README.md): the prototype the catalog grew out of.
- [parked](../parked/README.md): working code that isn't built into the app.
