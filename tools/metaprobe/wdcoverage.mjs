// Cobertura de Wikidata por consola del catalogo: cuantos juegos (instancia de videojuego) tiene
// cada una y que parte trae cada campo. Una consola puede ser varias plataformas de Wikidata (NES y
// Famicom Disk System): se cuentan juntas, sin repetir juegos. Escribe wdcoverage.json.
import fs from "node:fs";

const MAP = {
  gb: ["Q186437"], gbc: ["Q203992"], gba: ["Q188642"], nes: ["Q172742", "Q135321"], snes: ["Q183259"],
  n64: ["Q184839"], gamecube: ["Q182172"], wii: ["Q8079"], wiiu: ["Q56942"], nds: ["Q170323", "Q637178"],
  "3ds": ["Q203597", "Q17679679"], virtualboy: ["Q164651"], megadrive: ["Q10676"], mastersystem: ["Q209868"],
  gamegear: ["Q751719"], segacd: ["Q1047516"], sega32x: ["Q1063978"], saturn: ["Q200912"], dreamcast: ["Q184198"],
  psx: ["Q10677"], ps2: ["Q10680"], psp: ["Q170325"], psvita: ["Q188808"], switch: ["Q19610114"],
  xbox: ["Q132020"], xbox360: ["Q48263"], ps3: ["Q10683"], pcengine: ["Q1057377"], pcenginecd: ["Q10854461"],
  neogeo: ["Q1054350", "Q3338058", "Q64428080"], ngpc: ["Q1977455"], wonderswan: ["Q1065792"],
  wonderswancolor: ["Q1048035"], atari2600: ["Q206261"], atari5200: ["Q743222"], atari7800: ["Q753600"],
  lynx: ["Q753657"], c64: ["Q99775"], amiga: ["Q100047", "Q471094", "Q695161"], arcade: ["Q192851"],
  colecovision: ["Q1046862"], "3do": ["Q229429"], cpc: ["Q478829"], jaguar: ["Q650601"], atarist: ["Q627302"],
  atomiswave: ["Q757617"], bbcmicro: ["Q749976"], pet: ["Q946661"], plus4: ["Q868568", "Q1115913"],
  dos: ["Q170434", "Q47604"], intellivision: ["Q1061441"], j2me: ["Q193828"], odyssey2: ["Q576932"],
  msx: ["Q853547", "Q11232203"], pc88: ["Q1338888"], pc98: ["Q183505"], pcfx: ["Q1136902"],
  neogeocd: ["Q2703883"], cdi: ["Q1023103"], sg1000: ["Q1136956"], x1: ["Q2710884"], x68000: ["Q1758277"],
  vic20: ["Q918232"], zxspectrum: ["Q23882"],
};
const values = Object.entries(MAP).flatMap(([id, qs]) => qs.map((q) => `("${id}" wd:${q})`)).join(" ");
const FIELDS = {
  total: "",
  genre: "?g wdt:P136 [] .",
  year: "?g wdt:P577 [] .",
  developer: "?g wdt:P178 [] .",
  publisher: "?g wdt:P123 [] .",
  series: "?g wdt:P179 [] .",
  mode: "?g wdt:P404 [] .",
  enwiki: "?a schema:about ?g ; schema:isPartOf <https://en.wikipedia.org/> .",
  eswiki: "?a schema:about ?g ; schema:isPartOf <https://es.wikipedia.org/> .",
};
const UA = "LudologResearch/0.1 (metadata sources evaluation)";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const out = {};
for (const [field, pattern] of Object.entries(FIELDS)) {
  const q = `SELECT ?sys (COUNT(DISTINCT ?g) AS ?n) WHERE { VALUES (?sys ?p) { ${values} } ?g wdt:P31 wd:Q7889 ; wdt:P400 ?p . ${pattern} } GROUP BY ?sys`;
  let rows = null;
  for (let attempt = 1; attempt <= 3 && !rows; attempt++) {
    const r = await fetch("https://query.wikidata.org/sparql", {
      method: "POST",
      headers: { "User-Agent": UA, "Accept": "application/sparql-results+json", "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ query: q }),
    });
    if (r.ok) rows = (await r.json()).results.bindings;
    else { console.error(field, "HTTP", r.status); await sleep(5000 * attempt); }
  }
  for (const b of rows || []) (out[b.sys.value] ??= {})[field] = Number(b.n.value);
  console.error(field, "ok");
  await sleep(1500);
}
fs.writeFileSync("wdcoverage.json", JSON.stringify(out, null, 1));
const pct = (a, b) => (b ? `${Math.round((100 * (a || 0)) / b)}%` : "-");
console.log(["consola", "juegos", ...Object.keys(FIELDS).slice(1)].join("\t"));
for (const id of Object.keys(MAP)) {
  const o = out[id] || {};
  console.log([id, o.total || 0, ...Object.keys(FIELDS).slice(1).map((f) => pct(o[f], o.total))].join("\t"));
}
