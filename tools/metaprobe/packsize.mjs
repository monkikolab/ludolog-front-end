// Cuanto ocuparia un paquete por consola con solo lo que la app necesita, ya cruzado:
// identidad (crc+tamaño, serial, sha1 de la ISO en PS2, romset) -> nombre canonico y metadatos
// de libretro y de Wikidata. Mide filas, bytes en TSV y bytes en gzip; y aparte la sinopsis.
import fs from "node:fs";
import zlib from "node:zlib";

const ROMAN = { ii: "2", iii: "3", iv: "4", v: "5", vi: "6", vii: "7", viii: "8", ix: "9" };
function norm(s) {
  return s.toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "")
    .replace(/\([^)]*\)|\[[^\]]*\]/g, " ")
    .replace(/&/g, " and ").replace(/['’`™®©]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .split(" ").filter(Boolean)
    .map((w) => ROMAN[w] ?? w)
    .filter((w) => !["the", "a", "an"].includes(w))
    .join(" ");
}
const base = (name) => name.replace(/\s*[(\[].*$/, "").trim();
const serialKey = (s) => (s || "").toUpperCase().replace(/[^A-Z0-9]/g, "");

function parseDat(file) {
  if (!fs.existsSync(file)) return [];
  const txt = fs.readFileSync(file, "utf8");
  const out = [];
  for (const block of txt.split(/\n(?:game|machine) \(/).slice(1)) {
    const g = { roms: [] };
    for (const m of block.matchAll(/^\s*(\w+) "((?:[^"\\]|\\.)*)"/gm)) if (!(m[1] in g)) g[m[1]] = m[2];
    for (const m of block.matchAll(/rom \(((?:"[^"]*"|[^)"])*)\)/g)) {
      const r = {};
      for (const f of m[1].matchAll(/(\w+) ("[^"]*"|\S+)/g)) r[f[1]] = f[2].replace(/^"|"$/g, "");
      if (r.crc) r.crc = r.crc.toLowerCase();
      if (r.sha1) r.sha1 = r.sha1.toLowerCase();
      g.roms.push(r);
    }
    out.push(g);
  }
  return out;
}

const FIELDS = ["genre", "developer", "publisher", "releaseyear", "releasemonth", "franchise", "maxusers", "serial"];
function loadMeta(ra) {
  const meta = { byCrc: new Map(), bySerial: new Map(), byName: new Map() };
  const put = (m, k, f, v) => { if (!k || !v) return; if (!m.has(k)) m.set(k, {}); m.get(k)[f] ??= v; };
  for (const field of FIELDS) {
    for (const g of parseDat(`ldb/${field}/${ra}.dat`)) {
      const name = g.comment || g.name;
      for (const f of ["genre", "developer", "publisher", "releaseyear", "franchise", "users", "maxusers", "description"]) {
        const v = g[f]; if (!v) continue;
        put(meta.byName, name, f, v); put(meta.bySerial, serialKey(g.serial), f, v);
        for (const r of g.roms) { put(meta.byCrc, r.crc, f, v); put(meta.bySerial, serialKey(r.serial), f, v); }
      }
    }
  }
  return meta;
}

function loadWd(keys) {
  const byTitle = new Map();
  for (const key of keys) {
    const f = `wd/${key}.json`;
    if (!fs.existsSync(f)) continue;
    for (const [qid, g] of Object.entries(JSON.parse(fs.readFileSync(f, "utf8")))) {
      g.qid = qid;
      for (const t of [g.label, ...g.aliases].filter(Boolean)) {
        const k = norm(t); if (k && !byTitle.has(k)) byTitle.set(k, g);
      }
    }
  }
  return byTitle;
}

const SYSTEMS = [
  ["snes", "Nintendo - Super Nintendo Entertainment System", "no-intro", ["snes"]],
  ["gba", "Nintendo - Game Boy Advance", "no-intro", ["gba"]],
  ["nes", "Nintendo - Nintendo Entertainment System", "no-intro", ["nes"]],
  ["n64", "Nintendo - Nintendo 64", "no-intro", ["n64"]],
  ["megadrive", "Sega - Mega Drive - Genesis", "no-intro", ["megadrive"]],
  ["sega32x", "Sega - 32X", "no-intro", ["sega32x"]],
  ["psx", "Sony - PlayStation", "redump", ["psx"]],
  ["ps2", "Sony - PlayStation 2", "redump", ["ps2"]],
  ["psp", "Sony - PlayStation Portable", "redump", ["psp"]],
  ["gc", "Nintendo - GameCube", "redump", ["gc"]],
  ["wii", "Nintendo - Wii", "redump", ["wii"]],
  ["ps3", "Sony - PlayStation 3", "redump", ["ps3"]],
  ["arcade", "FBNeo - Arcade Games", "fbneo-split", ["arcade", "neogeo", "neogeomvs", "neogeoaes", "atomiswave"]],
];

const clean = (s) => (s ?? "").toString().replace(/[\t\n\r]+/g, " ");
const tot = { rows: 0, tsv: 0, gz: 0, syn: 0, synGz: 0, wd: 0, genre: 0 };
console.log("consola\tfilas\tcon genero\tWikidata\tTSV\tgzip\t+sinopsis gzip");
for (const [id, ra, kind, wdKeys] of SYSTEMS) {
  const entries = parseDat(`ldb/${kind}/${ra}.dat`);
  const meta = loadMeta(ra);
  const wd = loadWd(wdKeys);
  const lines = [], syn = [];
  let withGenre = 0, withWd = 0;
  for (const g of entries) {
    const name = g.name || g.comment || "";
    const rom = g.roms[0] || {};
    const serial = g.serial || rom.serial || "";
    const m = { ...(meta.byName.get(name) || {}), ...(serialKey(serial) && meta.bySerial.get(serialKey(serial)) || {}), ...(rom.crc && meta.byCrc.get(rom.crc) || {}) };
    const t = norm(base(name));
    const w = wd.get(t) || (base(name).match(/^(.+?)(?: - |: )/) && wd.get(norm(base(name).split(/ - |: /)[0])));
    if (w) withWd++;
    const genres = [m.genre, ...(w?.genres || []).map((x) => x.label)].filter(Boolean);
    if (genres.length) withGenre++;
    // Clave: en cartuchos crc y tamaño; en discos, el serial; en PS2 ademas el sha1 de la ISO
    // (es el de la cabecera del CHD); en arcade, el nombre del romset.
    const single = kind === "no-intro";
    const key = single ? `${rom.crc || ""}\t${rom.size || ""}` : kind === "fbneo-split" ? name : serial;
    const sha1 = id === "ps2" ? (rom.sha1 || "") : "";
    const desc = g.description && g.description !== name ? g.description : "";
    lines.push([key, sha1, kind === "fbneo-split" ? desc : name, genres.join("|"), m.releaseyear || w?.year || "",
      m.developer || w?.devs?.[0] || "", m.publisher || w?.pubs?.[0] || "", m.users || m.maxusers || "",
      m.franchise || w?.series?.[0] || "", w?.qid || ""].map(clean).join("\t"));
    if (m.description) syn.push(`${serial}\t${clean(m.description)}`);
  }
  const tsv = Buffer.from(lines.join("\n"));
  const gz = zlib.gzipSync(tsv, { level: 9 }).length;
  const synGz = syn.length ? zlib.gzipSync(Buffer.from(syn.join("\n")), { level: 9 }).length : 0;
  tot.rows += lines.length; tot.tsv += tsv.length; tot.gz += gz; tot.synGz += synGz; tot.wd += withWd; tot.genre += withGenre;
  const kb = (b) => `${Math.round(b / 1024)} KB`;
  console.log(`${id}\t${lines.length}\t${Math.round(100 * withGenre / lines.length)}%\t${Math.round(100 * withWd / lines.length)}%\t${kb(tsv.length)}\t${kb(gz)}\t${synGz ? kb(synGz) : "-"}`);
}
console.log(`TOTAL\t${tot.rows}\t${Math.round(100 * tot.genre / tot.rows)}%\t${Math.round(100 * tot.wd / tot.rows)}%\t${(tot.tsv / 1048576).toFixed(1)} MB\t${(tot.gz / 1048576).toFixed(2)} MB\t${(tot.synGz / 1048576).toFixed(2)} MB`);

// GameTDB, filtrado igual: ID, titulo, genero, año, desarrollador, editor, jugadores.
for (const f of ["wiitdb", "ps3tdb", "switchtdb"]) {
  const xml = fs.readFileSync(`gtdb/${f}.xml`, "utf8");
  const rows = [];
  for (const block of xml.split("<game ").slice(1)) {
    const tag = (t) => block.match(new RegExp(`<${t}>([^<]*)</${t}>`))?.[1] || "";
    rows.push([tag("id"), block.match(/<title>([^<]*)<\/title>/)?.[1] || "", tag("genre"),
      block.match(/<date year="(\d+)"/)?.[1] || "", tag("developer"), tag("publisher"),
      block.match(/<input players="(\d+)"/)?.[1] || ""].map(clean).join("\t"));
  }
  const tsv = Buffer.from(rows.join("\n"));
  console.log(`gametdb ${f}\t${rows.length}\t\t\t${Math.round(tsv.length / 1024)} KB\t${Math.round(zlib.gzipSync(tsv, { level: 9 }).length / 1024)} KB`);
}
