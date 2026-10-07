# Game catalog scripts

These scripts build Ludolog's game catalog from free sources, merged in advance so the app only
has to look things up. The result is published as release files of
[ludolog-assets](https://github.com/monkikolab/ludolog-assets). How the app uses it:
[docs/scraper.md](../../docs/scraper.md).

Each console gets a package with one row per game: its identity keys (the same ones the app reads
from each file), its canonical name, each source's genres, release date, developer, publisher and
series, and other names to search its art by. A second file per console holds short synopses.

## Running them

You need Node 20. Run the scripts from this folder: they download their sources into `data/`
(not part of the repository) and write the catalog to `out/`. The first run takes a long time;
later runs only fetch what is missing, and `--force` fetches a source again.

```bash
export CATALOG_CONTACT="<an e-mail or the repository page>"   # Wikimedia asks for a contact
node fetch-libretro.mjs      # libretro-database, with FBNeo and MAME
node fetch-wikidata.mjs      # Wikidata, platform by platform
node fetch-wikipedia.mjs     # Wikipedia introductions, for the synopses
node fetch-gametdb.mjs       # GameTDB: Nintendo consoles and PS3
node build.mjs               # out/: the packages, index.tsv and NOTICE.md
node norm-cases.mjs          # title-matching test cases shared with the app's tests
```

Everything in `out/` goes up as release files. To try a catalog without publishing it, copy `out/`
to the device and choose that folder in **Settings → Catalog → Catalog source**.

## Licences

The catalog is distributed under CC BY-SA 4.0, with [NOTICE.md](NOTICE.md) alongside it. It
draws on libretro-database (CC BY-SA 4.0), Wikidata (CC0), Wikipedia (CC BY-SA 4.0; the app names
the article behind each synopsis) and GameTDB (allowed in software that links to it). The app only
reads the packages, so their licence does not extend to it.
