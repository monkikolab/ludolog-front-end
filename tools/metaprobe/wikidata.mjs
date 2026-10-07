// Baja de Wikidata (CC0) lo que sabe de los juegos de cada consola: una consulta por consola y
// por campo, para no multiplicar filas (generos x desarrolladores x alias). Se guarda un JSON por
// consola en wd/<id>.json con { qid: { label, aliases[], genres[{id,label}], year, devs[], pubs[],
// series[], steam[] } }. Con --steam <appid,...> baja ademas los juegos de PC por su id de Steam.
import fs from "node:fs";

const PLATFORMS = {
  snes: "Q183259", gba: "Q188642", nes: "Q172742", n64: "Q184839", megadrive: "Q10676",
  sega32x: "Q1063978", psx: "Q10677", ps2: "Q10680", psp: "Q170325", gc: "Q182172",
  wii: "Q8079", ps3: "Q10683", switch: "Q19610114", arcade: "Q192851",
  // Los Metal Slug no estan como «arcade»: estan como Neo Geo (MVS o AES) o Atomiswave.
  neogeo: "Q1054350", neogeomvs: "Q3338058", neogeoaes: "Q64428080", atomiswave: "Q757617",
};
const UA = `LudologResearch/0.1 (metadata sources evaluation; ${process.env.CATALOG_CONTACT || "contact via the project repository"})`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function sparql(q) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    const r = await fetch("https://query.wikidata.org/sparql", {
      method: "POST",
      headers: { "User-Agent": UA, "Accept": "application/sparql-results+json",
        "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ query: q }),
    });
    if (r.ok) return (await r.json()).results.bindings;
    console.error(`  HTTP ${r.status}, retry ${attempt}`);
    await sleep(5000 * attempt);
  }
  throw new Error("sparql failed");
}

const FIELDS = {
  label: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g rdfs:label ?v FILTER(LANG(?v) IN ("en","mul")) }`,
  alias: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g skos:altLabel ?v FILTER(LANG(?v) IN ("en","mul")) }`,
  genre: (sel) => `SELECT ?g ?x ?v WHERE { ${sel} ?g wdt:P136 ?x . ?x rdfs:label ?v FILTER(LANG(?v) IN ("en","mul")) }`,
  year: (sel) => `SELECT ?g (MIN(YEAR(?d)) AS ?v) WHERE { ${sel} ?g wdt:P577 ?d } GROUP BY ?g`,
  dev: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wdt:P178 ?x . ?x rdfs:label ?v FILTER(LANG(?v) IN ("en","mul")) }`,
  pub: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wdt:P123 ?x . ?x rdfs:label ?v FILTER(LANG(?v) IN ("en","mul")) }`,
  series: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wdt:P179 ?x . ?x rdfs:label ?v FILTER(LANG(?v) IN ("en","mul")) }`,
  steam: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wdt:P1733 ?v }`,
};

async function pack(name, selector) {
  const games = {};
  const get = (qid) => (games[qid] ??= { label: null, aliases: [], genres: [], year: null, devs: [], pubs: [], series: [], steam: [] });
  for (const [field, build] of Object.entries(FIELDS)) {
    const rows = await sparql(build(selector));
    for (const b of rows) {
      const qid = b.g.value.split("/").pop();
      const v = b.v?.value;
      const g = get(qid);
      if (field === "label") g.label = v;
      else if (field === "alias") g.aliases.push(v);
      else if (field === "genre") g.genres.push({ id: b.x.value.split("/").pop(), label: v });
      else if (field === "year") g.year = Number(v);
      else if (field === "dev") g.devs.push(v);
      else if (field === "pub") g.pubs.push(v);
      else if (field === "series") g.series.push(v);
      else if (field === "steam") g.steam.push(v);
    }
    console.log(`  ${name}.${field}: ${rows.length} rows`);
    await sleep(1500);
  }
  fs.mkdirSync("wd", { recursive: true });
  fs.writeFileSync(`wd/${name}.json`, JSON.stringify(games));
  console.log(`${name}: ${Object.keys(games).length} games`);
}

const args = process.argv.slice(2);
const steamAt = args.indexOf("--steam");
if (steamAt >= 0) {
  const ids = args[steamAt + 1].split(",").map((s) => `"${s.trim()}"`).join(" ");
  await pack("steam", `VALUES ?sid { ${ids} } ?g wdt:P1733 ?sid .`);
} else {
  const only = args.length ? args : Object.keys(PLATFORMS);
  for (const p of only) await pack(p, `?g wdt:P400 wd:${PLATFORMS[p]} .`);
}
