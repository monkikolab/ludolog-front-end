// Listados de arte y video, para comprobar despues que se encuentra con el nombre exacto.
//  - libretro-thumbnails: Named_Boxarts, Named_Snaps y Named_Titles de cada consola.
//  - archive.org: los mp4 originales de cada coleccion de videos de partida.
// Se guarda art/<raName>.<carpeta>.json y art/video.<coleccion>.json (listas de nombres).
import fs from "node:fs";

const systems = JSON.parse(fs.readFileSync("systems.json", "utf8"));
const extra = { megadrive: systems.megadrive };
const UA = { "User-Agent": "LudologResearch/0.1" };
fs.mkdirSync("art", { recursive: true });

const raNames = new Set(Object.values({ ...systems, ...extra }).map((s) => s.raName).filter((n) => n && n !== "retro8"));
for (const ra of raNames) {
  for (const folder of ["Named_Boxarts", "Named_Snaps", "Named_Titles"]) {
    const url = `https://thumbnails.libretro.com/${encodeURIComponent(ra)}/${folder}/`;
    const r = await fetch(url, { headers: UA });
    if (!r.ok) { console.log(`${ra} ${folder}: HTTP ${r.status}`); continue; }
    const html = await r.text();
    const names = [...html.matchAll(/href="([^"]+\.png)"/g)].map((m) => decodeURIComponent(m[1]).replace(/\.png$/, ""));
    fs.writeFileSync(`art/${ra}.${folder}.json`, JSON.stringify(names));
    console.log(`${ra} ${folder}: ${names.length}`);
  }
}

const collections = new Set(Object.values({ ...systems, ...extra }).map((s) => s.videoSnaps).filter(Boolean));
for (const id of collections) {
  const r = await fetch(`https://archive.org/download/${id}/${id}_files.xml`, { headers: UA });
  if (!r.ok) { console.log(`video ${id}: HTTP ${r.status}`); continue; }
  const xml = await r.text();
  const names = [...xml.matchAll(/<file name="([^"]+)" source="original"/g)]
    .map((m) => m[1].replace(/&amp;/g, "&").replace(/&apos;/g, "'").replace(/&quot;/g, "\""))
    .filter((n) => n.toLowerCase().endsWith(".mp4"));
  fs.writeFileSync(`art/video.${id}.json`, JSON.stringify(names));
  console.log(`video ${id}: ${names.length}`);
}
