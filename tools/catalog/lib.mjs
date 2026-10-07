// Lo comun a los guiones del catalogo: como se compara un titulo, como se lee un .dat de
// clrmamepro y como se le pregunta a Wikidata. Sacado de tools/metaprobe, que fue la prueba.
import fs from "node:fs";

// Wikimedia pide un User-Agent con forma de contactar. La direccion no se escribe aqui: va en
// CATALOG_CONTACT (un correo o la pagina del repo) al generar.
export const UA = `LudologCatalog/1.0 (${process.env.CATALOG_CONTACT || "game catalog builder"})`;
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Sin la «x»: «Metal Slug X» y «Mega Man X» no son el 10.
const ROMAN = { ii: "2", iii: "3", iv: "4", v: "5", vi: "6", vii: "7", viii: "8", ix: "9" };

/**
 * Un titulo reducido a lo que se compara: sin etiquetas, acentos, articulos ni puntuacion.
 * La app hace EXACTAMENTE lo mismo (GameDb.norm): si cambia aqui, cambia alli, y los casos de
 * tools/catalog/norm-cases.tsv lo comprueban en los dos lados.
 */
export function norm(s) {
  return String(s).toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "")
    .replace(/\([^)]*\)|\[[^\]]*\]/g, " ")
    .replace(/&/g, " and ").replace(/['’`™®©]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .split(" ").filter(Boolean)
    .map((w) => ROMAN[w] ?? w)
    .filter((w) => !["the", "a", "an"].includes(w))
    .join(" ");
}

/** El titulo sin las etiquetas del final: «Silent Hill 2 (Europe) (En,Fr)» -> «Silent Hill 2». */
export const base = (name) => String(name).replace(/\s*[(\[].*$/, "").replace(/_/g, " ").trim();

/** Un serial como se compara: «SLUS-00820» y «SLUS_008.20» son «SLUS00820». */
export const serialKey = (s) => String(s || "").toUpperCase().replace(/[^A-Z0-9]/g, "");

/** La region que dice un nombre No-Intro o Redump, para elegir entre variantes. */
export function regionOf(name) {
  const tags = (String(name).match(/\(([^)]*)\)/g) || []).join(" ").toLowerCase();
  if (/usa|ntsc-u/.test(tags)) return "usa";
  if (/europe|spain|france|germany|italy|uk|pal/.test(tags)) return "eur";
  if (/japan|ntsc-j/.test(tags)) return "jpn";
  return "";
}

/**
 * Un .dat de clrmamepro: bloques «game ( … )» con campos y roms.
 *
 * Los valores van con comillas o sin ellas («users 2», «releaseyear 2014»). Y los nombres de
 * rom llevan parentesis —«(Europe)»—, asi que el cierre de un «rom ( … )» es el primero que no
 * esta dentro de comillas.
 */
export function parseDat(file) {
  if (!fs.existsSync(file)) return [];
  const txt = fs.readFileSync(file, "utf8");
  const out = [];
  for (const block of txt.split(/\n(?:game|machine) \(/).slice(1)) {
    const g = { roms: [] };
    for (const m of block.matchAll(/^\s*(\w+) (?:"((?:[^"\\]|\\.)*)"|([^\s(]\S*))\s*$/gm)) {
      if (m[1] !== "rom" && !(m[1] in g)) g[m[1]] = m[2] ?? m[3];
    }
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

/**
 * Una consulta SPARQL a Wikidata, con reintentos: a veces contesta 502 o 429. Y con un tope de
 * dos minutos: fetch no tiene ninguno, y una conexion que se queda colgada paraba todo el guion
 * sin decir nada (paso con la de Amstrad CPC).
 */
export async function sparql(q) {
  for (let attempt = 1; attempt <= 5; attempt++) {
    const r = await fetch("https://query.wikidata.org/sparql", {
      method: "POST",
      headers: { "User-Agent": UA, "Accept": "application/sparql-results+json",
        "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ query: q }),
      signal: AbortSignal.timeout(120_000),
    }).catch((e) => ({ ok: false, status: e.message }));
    if (r.ok) {
      const body = await r.json().catch(() => null);
      if (body) return body.results.bindings;
    }
    console.error(`  SPARQL ${r.status}, reintento ${attempt}`);
    await sleep(8000 * attempt);
  }
  throw new Error("SPARQL no contesta");
}

/** Lee un JSON si existe. */
export const readJson = (f, dflt) => (fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, "utf8")) : dflt);
