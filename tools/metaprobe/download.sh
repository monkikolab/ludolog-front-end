#!/usr/bin/env bash
# Descarga las bases gratuitas que usa probe.mjs: libretro-database (identidad y metadatos),
# la de MAME, y GameTDB (Wii/GameCube, PS3 y Switch). Wikidata, Steam y los listados de arte
# tienen sus propios scripts (wikidata.mjs, steam.mjs, artindex.mjs).
set -u
base="https://raw.githubusercontent.com/libretro/libretro-database/master/metadat"
systems=(
  "Nintendo - Super Nintendo Entertainment System" "Nintendo - Game Boy Advance"
  "Nintendo - Nintendo Entertainment System" "Nintendo - Nintendo 64" "Sega - Mega Drive - Genesis"
  "Sega - 32X" "Sony - PlayStation" "Sony - PlayStation 2" "Sony - PlayStation Portable"
  "Nintendo - GameCube" "Nintendo - Wii" "Sony - PlayStation 3" "FBNeo - Arcade Games"
)
n=0
for d in no-intro redump fbneo-split genre developer publisher releaseyear releasemonth franchise maxusers serial; do
  mkdir -p "ldb/$d"
  for s in "${systems[@]}"; do
    out="ldb/$d/$s.dat"
    code=$(curl -s -o "$out" -w "%{http_code}" "$base/$d/$(printf '%s' "$s" | sed 's/ /%20/g').dat")
    if [ "$code" = "200" ]; then n=$((n + 1)); else rm -f "${out:?}"; fi
  done
done
mkdir -p ldb/mame && curl -s -o ldb/mame/MAME.dat "$base/mame/MAME.dat" && n=$((n + 1))
echo "libretro: $n ficheros"

UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"
mkdir -p gtdb
for f in wiitdb ps3tdb switchtdb; do
  curl -s -A "$UA" -o "gtdb/$f.zip" "https://www.gametdb.com/$f.zip" && (cd gtdb && unzip -o -q "$f.zip")
done
ls -la gtdb/*.xml
