// Baja de Wikidata (CC0) lo que el catalogo usa de cada juego, plataforma a plataforma:
// nombre y alias, tipo, generos, primera fecha de salida con su precision, desarrolladores,
// editores, serie, fama (en cuantas Wikipedias tiene articulo) y el titulo del articulo en
// ingles, que es de donde sale la sinopsis.
//
// Una consulta por campo y no una con todo: con varios generos, varios estudios y varios alias a
// la vez las filas se multiplican entre si y la consulta no termina.
//
//   node fetch-wikidata.mjs            las plataformas que falten
//   node fetch-wikidata.mjs --force    todas otra vez
//   node fetch-wikidata.mjs Q1063978   solo esa (o esas)
//
// Escribe data/wd/<QID>.json: { qid: { label, aliases, types, genres, date, devs, pubs, series,
// links, enwiki, steam } }.
import fs from "node:fs";
import { sparql, sleep, UA } from "./lib.mjs";
import { ALL_QIDS } from "./platforms.mjs";

const DIR = "data/wd";
fs.mkdirSync(DIR, { recursive: true });
const force = process.argv.includes("--force");

// Las etiquetas en ingles o en «mul»: Wikidata guarda muchos nombres propios en «mul» y no en
// «en», y sin pedir los dos faltaban 1.441 nombres (Symphony of the Night entre ellos).
const LABEL = (x, v) => `${x} rdfs:label ${v} . BIND(LANG(${v}) AS ?lang) FILTER(?lang IN ("en","mul"))`;
const QUERIES = {
  label: (sel) => `SELECT ?g ?v ?lang WHERE { ${sel} ${LABEL("?g", "?v")} }`,
  alias: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g skos:altLabel ?v FILTER(LANG(?v) IN ("en","mul")) }`,
  type: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wdt:P31 ?v }`,
  genre: (sel) => `SELECT ?g ?x ?v ?lang WHERE { ${sel} ?g wdt:P136 ?x . ${LABEL("?x", "?v")} }`,
  date: (sel) => `SELECT ?g ?v ?p WHERE { ${sel} ?g p:P577 ?s . ?s psv:P577 ?n . ?n wikibase:timeValue ?v ; wikibase:timePrecision ?p }`,
  dev: (sel) => `SELECT ?g ?x ?v ?lang WHERE { ${sel} ?g wdt:P178 ?x . ${LABEL("?x", "?v")} }`,
  pub: (sel) => `SELECT ?g ?x ?v ?lang WHERE { ${sel} ?g wdt:P123 ?x . ${LABEL("?x", "?v")} }`,
  series: (sel) => `SELECT ?g ?x ?v ?lang WHERE { ${sel} ?g wdt:P179 ?x . ${LABEL("?x", "?v")} }`,
  links: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wikibase:sitelinks ?v }`,
  enwiki: (sel) => `SELECT ?g ?v WHERE { ${sel} ?a schema:about ?g ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?v }`,
  steam: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wdt:P1733 ?v }`,
};

/**
 * Los que no salen por plataforma, con su propio selector. Se guardan como data/wd/<nombre>.json.
 *
 * - steam: los juegos de PC, por su numero de Steam, que es lo que lleva el fichero `.steam`.
 *   Por plataforma («Microsoft Windows») serian decenas de miles sin forma de casarlos.
 * - play: los de Android, por su paquete de Google Play, que es como Ludolog los conoce.
 * - doom: los clasicos del motor de Doom, por su articulo en Wikipedia. El paquete de libretro
 *   los reconoce por el WAD, pero sin genero ni fecha.
 */
const DOOM = ["Doom (1993 video game)", "Doom II", "Final Doom", "Heretic (video game)",
  "Hexen: Beyond Heretic", "Strife (1996 video game)", "Chex Quest", "Freedoom", "Doom 64"];
const SETS = {
  // En nueve trozos, por la primera cifra del numero de Steam, y sin los nombres de generos,
  // estudios y series, que se piden despues aparte (ver nameLinks): son unos 128.000 juegos, y
  // la consulta de generos con sus nombres no contesta ni en trozos.
  steam: {
    parts: [..."123456789"].map((d) => `?g wdt:P1733 ?sid . FILTER(STRSTARTS(?sid, "${d}"))`),
    fields: {
      ...QUERIES,
      genre: (sel) => `SELECT ?g ?x WHERE { ${sel} ?g wdt:P136 ?x }`,
      dev: (sel) => `SELECT ?g ?x WHERE { ${sel} ?g wdt:P178 ?x }`,
      pub: (sel) => `SELECT ?g ?x WHERE { ${sel} ?g wdt:P123 ?x }`,
      series: (sel) => `SELECT ?g ?x WHERE { ${sel} ?g wdt:P179 ?x }`,
    },
  },
  play: {
    sel: "?g wdt:P3418 [] ; wdt:P31/wdt:P279* wd:Q7889 .",
    fields: { ...QUERIES, play: (sel) => `SELECT ?g ?v WHERE { ${sel} ?g wdt:P3418 ?v }` },
  },
  doom: {
    sel: `VALUES ?t { ${DOOM.map((t) => `"${t}"@en`).join(" ")} } ?w schema:about ?g ; ` +
      `schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?t .`,
    fields: QUERIES,
  },
};

const qidOf = (uri) => uri.split("/").pop();

/** Una entidad con su nombre: el ingles antes que el «mul». */
function named(list, id, label, lang) {
  let e = list.find((x) => x.id === id);
  if (!e) list.push((e = { id, label, lang }));
  else if (lang === "en" && e.lang !== "en") Object.assign(e, { label, lang });
}

async function platform(pq) {
  const set = SETS[pq];
  const sel = set?.sel ?? `?g wdt:P400 wd:${pq} .`;
  const games = {};
  const get = (q) => (games[q] ??= { label: null, lang: null, aliases: [], types: [], genres: [], date: null, prec: 0, devs: [], pubs: [], series: [], links: 0, enwiki: null, steam: [], play: [] });
  for (const [field, build] of Object.entries(set?.fields ?? QUERIES)) {
    const t0 = Date.now();
    const rows = [];
    for (const part of set?.parts ?? [sel]) rows.push(...await sparql(build(part)));
    if (process.env.VERBOSE) console.log(`  ${pq}.${field}: ${rows.length} filas, ${Date.now() - t0} ms`);
    for (const b of rows) {
      const g = get(qidOf(b.g.value));
      const v = b.v?.value;
      const lang = b.lang?.value;
      switch (field) {
        case "label": if (!g.label || (lang === "en" && g.lang !== "en")) { g.label = v; g.lang = lang; } break;
        case "alias": if (!g.aliases.includes(v)) g.aliases.push(v); break;
        case "type": g.types.push(qidOf(v)); break;
        case "genre": named(g.genres, qidOf(b.x.value), v, lang); break;
        case "dev": named(g.devs, qidOf(b.x.value), v, lang); break;
        case "pub": named(g.pubs, qidOf(b.x.value), v, lang); break;
        case "series": named(g.series, qidOf(b.x.value), v, lang); break;
        case "date": {
          // La primera salida, en cualquier sitio. Con la precision que tenga: 9 es el año,
          // 10 el mes y 11 el dia. Una fecha de solo año llega como «1994-01-01» y no es el
          // uno de enero.
          const p = Number(b.p.value);
          if (p < 9 || !/^\d{4}-/.test(v)) break;
          const d = v.slice(0, p >= 11 ? 10 : p === 10 ? 7 : 4);
          if (!g.date || d.slice(0, 4) < g.date.slice(0, 4) || (d.slice(0, 4) === g.date.slice(0, 4) && d.length > g.date.length)) {
            g.date = d; g.prec = p;
          }
          break;
        }
        case "links": g.links = Number(v); break;
        case "enwiki": g.enwiki = v; break;
        case "steam": g.steam.push(v); break;
        case "play": g.play.push(v); break;
      }
    }
    await sleep(1200);
  }
  await nameLinks(games);
  await fillLabels(games);
  for (const g of Object.values(games)) {
    delete g.lang; delete g.prec;
    for (const k of ["genres", "devs", "pubs", "series"]) g[k] = g[k].map(({ id, label }) => ({ id, label }));
  }
  fs.writeFileSync(`${DIR}/${pq}.json`, JSON.stringify(games));
  return Object.keys(games).length;
}

/**
 * Los nombres de los generos, estudios y series que llegaron solo con su identificador (ver
 * SETS.steam): son unos pocos miles distintos, pedidos de 400 en 400.
 */
async function nameLinks(games) {
  const lists = Object.values(games).flatMap((g) => [g.genres, g.devs, g.pubs, g.series]);
  const ids = [...new Set(lists.flat().filter((e) => !e.label).map((e) => e.id))];
  const names = new Map();
  for (let i = 0; i < ids.length; i += 400) {
    const values = ids.slice(i, i + 400).map((q) => `wd:${q}`).join(" ");
    for (const b of await sparql(`SELECT ?x ?v ?lang WHERE { VALUES ?x { ${values} } ${LABEL("?x", "?v")} }`)) {
      const id = qidOf(b.x.value);
      if (!names.has(id) || b.lang.value === "en") names.set(id, b.v.value);
    }
    await sleep(1200);
  }
  for (const list of lists) for (const e of list) if (!e.label) e.label = names.get(e.id) ?? null;
  for (const g of Object.values(games)) for (const k of ["genres", "devs", "pubs", "series"]) g[k] = g[k].filter((e) => e.label);
}

/**
 * Los que el SPARQL devolvio sin nombre, por la API de entidades, cincuenta por peticion. Con
 * calma: esa API corta con «too many requests» si se le pide deprisa.
 */
async function fillLabels(games) {
  const missing = Object.keys(games).filter((q) => !games[q].label);
  for (let i = 0; i < missing.length; i += 50) {
    const ids = missing.slice(i, i + 50).join("|");
    const u = `https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids}&props=labels|aliases&languages=en%7Cmul&format=json`;
    let j = null;
    for (let attempt = 1; attempt <= 5 && !j; attempt++) {
      const body = await fetch(u, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(60_000) }).then((r) => r.text()).catch(() => "");
      try { j = JSON.parse(body); } catch { await sleep(20000 * attempt); }
    }
    for (const [q, e] of Object.entries(j?.entities || {})) {
      const label = e.labels?.en?.value || e.labels?.mul?.value;
      if (label) games[q].label = label;
      for (const a of [...(e.aliases?.en || []), ...(e.aliases?.mul || [])]) {
        if (!games[q].aliases.includes(a.value)) games[q].aliases.push(a.value);
      }
    }
    await sleep(2000);
  }
}

// Dos a la vez: el servicio admite unas pocas consultas simultaneas por direccion, y de una en
// una son casi cincuenta minutos.
const only = process.argv.slice(2).filter((a) => /^Q\d+$/.test(a) || a in SETS);
const todo = only.length ? only : [...new Set([...ALL_QIDS, ...Object.keys(SETS)])].filter((q) => force || !fs.existsSync(`${DIR}/${q}.json`));
console.log(`${todo.length} plataformas por bajar`);
async function worker() {
  while (todo.length) {
    const q = todo.shift();
    const t0 = Date.now();
    try {
      const n = await platform(q);
      console.log(`${q}: ${n} juegos (${Math.round((Date.now() - t0) / 1000)} s)`);
    } catch (e) {
      console.log(`${q}: FALLO ${e.message}`);
    }
  }
}
await Promise.all([worker(), worker()]);
