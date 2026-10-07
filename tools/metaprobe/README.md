# metaprobe

The prototype behind Ludolog's game identification and catalog, kept for reference. From a PC it
reads a device's library over adb, without changing anything on the device, and checks for each
game what it is by its contents, what free sources know about it, and how much box art and video
its exact name finds compared with its file name. Its findings shaped
[docs/scraper.md](../../docs/scraper.md), and [tools/catalog](../catalog/README.md) grew out of it.

## Usage

You need Node 20, bash (Git Bash on Windows), adb, and a debug build of Ludolog so `run-as` can read
its library index. Set `SERIAL` (from `adb devices`) if more than one device is connected. Run
everything from this folder: downloads and reports are written here.

```bash
adb ${SERIAL:+-s $SERIAL} exec-out run-as com.felp.frontcomp cat files/library.idx > library.idx
bash inventory.sh; node discid.mjs; node extra.mjs   # read each game file on the device
bash download.sh                                     # libretro-database and GameTDB
node wikidata.mjs; node filllabels.mjs               # Wikidata
node steam.mjs <appids>                              # Steam store data for those games
node systems.mjs; node artindex.mjs                  # video collections and art listings
node probe.mjs                                       # report.tsv, report.json, a summary per console
```

The other scripts (`matrix.mjs`, `fetchall.mjs`, `coverage.mjs`, `wdplatforms.mjs`,
`wdcoverage.mjs`, `gtdbstats.mjs`, `arcadestats.mjs`, `packsize.mjs`) measure what each source
covers for every console in the catalog, with no device needed.
