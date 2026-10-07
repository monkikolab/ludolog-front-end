// Las plataformas de videojuegos de Wikidata con cuantos juegos tiene cada una (P400), para
// casarlas con las consolas del catalogo. Escribe wdplatforms.tsv: qid, nombre, juegos.
import fs from "node:fs";

const UA = "LudologResearch/0.1 (metadata sources evaluation)";
const q = `SELECT ?p ?pLabel ?n WHERE {
  { SELECT ?p (COUNT(DISTINCT ?g) AS ?n) WHERE { ?g wdt:P31 wd:Q7889 ; wdt:P400 ?p } GROUP BY ?p }
  FILTER(?n >= 15)
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul". }
} ORDER BY DESC(?n)`;
const r = await fetch("https://query.wikidata.org/sparql", {
  method: "POST",
  headers: { "User-Agent": UA, "Accept": "application/sparql-results+json", "Content-Type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({ query: q }),
});
if (!r.ok) { console.error("HTTP", r.status, (await r.text()).slice(0, 300)); process.exit(1); }
const rows = (await r.json()).results.bindings.map((b) => [b.p.value.split("/").pop(), b.pLabel.value, b.n.value]);
fs.writeFileSync("wdplatforms.tsv", rows.map((x) => x.join("\t")).join("\n"));
console.log(rows.length, "plataformas");
for (const x of rows.slice(0, 200)) console.log(x.join("\t"));
