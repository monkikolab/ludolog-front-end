// Que traen las bases de arcade de libretro: cuantas maquinas son juegos (no BIOS, ni dispositivos,
// ni mecanicas), cuantas son originales y cuantas clones, y que parte tiene año, fabricante,
// jugadores, controles, orientacion y estado del driver. Y cuantos juegos son de Neo Geo.
import fs from "node:fs";

function machines(file, tag) {
  const xml = fs.readFileSync(file, "utf8");
  const out = [];
  for (const block of xml.split(`<${tag} `).slice(1)) {
    const head = block.slice(0, block.indexOf(">"));
    const attr = (a, s = head) => s.match(new RegExp(`\\b${a}="([^"]*)"`))?.[1];
    const m = {
      name: attr("name"), cloneof: attr("cloneof"), romof: attr("romof"),
      bios: attr("isbios") === "yes", device: attr("isdevice") === "yes", mech: attr("ismechanical") === "yes",
      runnable: attr("runnable") !== "no",
      year: block.match(/<year>([^<]*)<\/year>/)?.[1], maker: block.match(/<manufacturer>([^<]*)<\/manufacturer>/)?.[1],
      players: block.match(/<input[^>]*\bplayers="(\d+)"/)?.[1],
      control: block.match(/<control[^>]*\btype="([^"]+)"/)?.[1] || block.match(/<input[^>]*\bcontrol="([^"]+)"/)?.[1],
      rotate: block.match(/<display[^>]*\brotate="(\d+)"/)?.[1] ?? block.match(/<video[^>]*\borientation="(\w+)"/)?.[1],
      status: block.match(/<driver[^>]*\bstatus="(\w+)"/)?.[1],
      neogeo: attr("romof") === "neogeo" || /neodriv|neogeo/.test(attr("sourcefile") || ""),
      genre: block.match(/<(?:genre|category)>([^<]*)</)?.[1],
    };
    out.push(m);
  }
  return out;
}

const SOURCES = [
  ["FBNeo (arcade)", "ldb/arcade/FinalBurn Neo (ClrMame Pro XML, Arcade only).dat", "game"],
  ["MAME 2003-Plus", "ldb/arcade/MAME 2003-Plus XML.xml", "game"],
  ["MAME 2016 (arcade)", "ldb/arcade/MAME 2016 XML (Arcade Only).xml", "machine"],
];
const pct = (a, b) => `${Math.round((100 * a) / b)}%`;
console.log(["fuente", "maquinas", "juegos", "originales", "clones", "NeoGeo", "año", "fabric", "jugad", "control", "orient", "estado", "bien", "genero"].join("\t"));
for (const [label, file, tag] of SOURCES) {
  const all = machines(file, tag);
  const games = all.filter((m) => !m.bios && !m.device && !m.mech && m.runnable && m.name);
  const parents = games.filter((m) => !m.cloneof);
  const c = (f) => games.filter(f).length;
  console.log([label, all.length, games.length, parents.length, games.length - parents.length, c((m) => m.neogeo),
    pct(c((m) => m.year && /\d{4}/.test(m.year)), games.length), pct(c((m) => m.maker), games.length),
    pct(c((m) => m.players), games.length), pct(c((m) => m.control), games.length), pct(c((m) => m.rotate !== undefined), games.length),
    pct(c((m) => m.status), games.length), pct(c((m) => m.status === "good"), games.length), pct(c((m) => m.genre), games.length)].join("\t"));
}
// MAME.dat (el actual, solo nombre, año y fabricante).
const dat = fs.readFileSync("ldb/arcade/MAME.dat", "utf8").split(/\ngame \(/).slice(1);
console.log(`MAME.dat (actual)\t${dat.length}\t\t\t\t\t${pct(dat.filter((b) => /\n\s*year "/.test(b)).length, dat.length)}\t${pct(dat.filter((b) => /\n\s*developer "/.test(b)).length, dat.length)}`);
