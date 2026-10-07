// La sinopsis de cada juego: la entradilla de su articulo en la Wikipedia inglesa (CC BY-SA 4.0),
// en texto plano. El titulo del articulo sale de Wikidata (fetch-wikidata.mjs).
//
// Se guarda la entradilla entera; el recorte a lo que cabe en la tarjeta se hace al generar el
// catalogo (build.mjs), para poder cambiarlo sin volver a pedir nada.
//
// De veinte en veinte, que es lo que la API da con `exintro`, y de una peticion en una: la de
// Wikipedia no tiene cupo para lecturas, pero pide no ir en paralelo. Lo ya bajado no se pide
// otra vez; --force lo renueva todo.
//
// Escribe data/wp/extracts.json: { titulo: texto }.
import fs from "node:fs";
import { sleep, UA, readJson } from "./lib.mjs";

const OUT = "data/wp/extracts.json";
fs.mkdirSync("data/wp", { recursive: true });
const have = process.argv.includes("--force") ? {} : readJson(OUT, {});

const titles = new Set();
for (const f of fs.readdirSync("data/wd").filter((f) => f.endsWith(".json"))) {
  for (const g of Object.values(readJson(`data/wd/${f}`, {}))) if (g.enwiki) titles.add(g.enwiki);
}
const todo = [...titles].filter((t) => !(t in have));
console.log(`${titles.size} articulos, ${todo.length} por bajar`);

let done = 0;
for (let i = 0; i < todo.length; i += 20) {
  const batch = todo.slice(i, i + 20);
  const u = "https://en.wikipedia.org/w/api.php?" + new URLSearchParams({
    action: "query", prop: "extracts", exintro: "1", explaintext: "1", format: "json",
    formatversion: "2", titles: batch.join("|"),
  });
  let j = null;
  for (let attempt = 1; attempt <= 5 && !j; attempt++) {
    const r = await fetch(u, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(60_000) }).catch(() => null);
    if (r?.ok) j = await r.json().catch(() => null);
    if (!j) await sleep(5000 * attempt);
  }
  // Wikipedia puede devolver el titulo normalizado («Kolibri_(video_game)» -> con espacio): se
  // guarda con el que se pidio, que es el que sale de Wikidata.
  const back = new Map((j?.query?.normalized || []).map((n) => [n.to, n.from]));
  for (const p of j?.query?.pages || []) {
    const asked = back.get(p.title) || p.title;
    have[asked] = (p.extract || "").trim();
  }
  for (const t of batch) if (!(t in have)) have[t] = "";
  done += batch.length;
  if ((i / 20) % 50 === 0) {
    fs.writeFileSync(OUT, JSON.stringify(have));
    console.log(`  ${done}/${todo.length}`);
  }
  await sleep(300);
}
fs.writeFileSync(OUT, JSON.stringify(have));
console.log(`listo: ${Object.values(have).filter(Boolean).length} con texto`);
