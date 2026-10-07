// La ficha de la tienda de Steam de cada appid (sin clave). La respuesta viene a veces bajo otra
// clave que el appid pedido —NieR:Automata llego como «4267420»—, asi que se toma la primera.
import fs from "node:fs";
const ids = process.argv[2].split(",");
fs.mkdirSync("steam", { recursive: true });
for (const id of ids) {
  const r = await fetch(`https://store.steampowered.com/api/appdetails?appids=${id}&l=english`, { headers: { "User-Agent": "LudologResearch/0.1" } });
  const j = await r.json();
  fs.writeFileSync(`steam/${id}.json`, JSON.stringify(j));
  const d = Object.values(j)[0]?.data;
  console.log(id, "|", d ? `${d.name} | ${(d.genres || []).map((g) => g.description).join(", ")}` : "sin datos");
  await new Promise((res) => setTimeout(res, 3000));
}
