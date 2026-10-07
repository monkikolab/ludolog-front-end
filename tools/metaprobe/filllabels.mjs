// El servicio SPARQL de Wikidata a veces devuelve juegos sin su etiqueta ni alias en ingles, aunque
// los tengan (Castlevania: Symphony of the Night llego con label null). Sin nombre no se puede
// encontrar por titulo, asi que se rellenan con la API de entidades, 50 por peticion.
import fs from "node:fs";

const UA = { "User-Agent": "LudologResearch/0.1 (metadata sources evaluation)" };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let filled = 0;
for (const f of fs.readdirSync("wd").filter((f) => f.endsWith(".json"))) {
  const pack = JSON.parse(fs.readFileSync(`wd/${f}`, "utf8"));
  const missing = Object.keys(pack).filter((q) => !pack[q].label);
  for (let i = 0; i < missing.length; i += 50) {
    const ids = missing.slice(i, i + 50).join("|");
    const u = `https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids}&props=labels|aliases&languages=en%7Cmul&format=json`;
    // Con calma: la API corta con «too many requests» si se le pide deprisa, y entonces se espera.
    let j = null;
    for (let attempt = 1; attempt <= 5 && !j; attempt++) {
      const body = await (await fetch(u, { headers: UA })).text();
      try { j = JSON.parse(body); } catch { console.log(`  espera (${body.slice(0, 40)})`); await sleep(20000 * attempt); }
    }
    if (!j) continue;
    for (const [q, e] of Object.entries(j.entities || {})) {
      const label = e.labels?.en?.value || e.labels?.mul?.value;
      if (label) { pack[q].label = label; filled++; }
      const al = [...(e.aliases?.en || []), ...(e.aliases?.mul || [])].map((a) => a.value);
      pack[q].aliases = [...new Set([...pack[q].aliases, ...al])];
    }
    await sleep(2000);
  }
  fs.writeFileSync(`wd/${f}`, JSON.stringify(pack));
  console.log(`${f}: ${missing.length} sin etiqueta`);
}
console.log(`rellenadas: ${filled}`);
