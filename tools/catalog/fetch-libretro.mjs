// Baja de libretro-database (CC-BY-SA 4.0) lo que el catalogo usa de cada consola de
// systems.toml: la base de identidad (No-Intro, Redump o la suya propia de dat/) y los campos de
// metadat que sirven al metagame. Y las dos de arcade: FBNeo y MAME.
//
//   node fetch-libretro.mjs            lo que falte
//   node fetch-libretro.mjs --force    todo otra vez (el catalogo del mes)
import fs from "node:fs";
import { UA } from "./lib.mjs";

const RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/";
const force = process.argv.includes("--force");

// El arbol entero del repositorio, para saber que existe sin pedir fichero a fichero.
const treeRes = await fetch("https://api.github.com/repos/libretro/libretro-database/git/trees/master?recursive=1",
  { headers: { "User-Agent": UA, "Accept": "application/vnd.github+json" } });
if (!treeRes.ok) throw new Error(`arbol de libretro: HTTP ${treeRes.status}`);
const treeJson = await treeRes.json();
fs.mkdirSync("data", { recursive: true });
fs.writeFileSync("data/tree.json", JSON.stringify(treeJson));
const tree = new Set(treeJson.tree.filter((e) => e.type === "blob").map((e) => e.path));

const toml = fs.readFileSync(new URL("../../app/src/main/assets/systems.toml", import.meta.url), "utf8");
const raNames = [...new Set(toml.split("\n[[system]]").slice(1)
  .map((b) => (b.match(/(?:^|\n)raName *= *"([^"]*)"/) || [])[1]).filter(Boolean))];

const IDENTITY = ["no-intro", "redump"];
const FIELDS = ["genre", "developer", "publisher", "releaseyear", "releasemonth", "franchise", "serial"];
const todo = [];
for (const ra of raNames) {
  for (const f of [...IDENTITY, ...FIELDS]) {
    const p = `metadat/${f}/${ra}.dat`;
    if (tree.has(p)) todo.push([p, `data/ldb/${f}/${ra}.dat`]);
  }
  if (tree.has(`dat/${ra}.dat`)) todo.push([`dat/${ra}.dat`, `data/ldb/dat/${ra}.dat`]);
}
todo.push(["metadat/mame/MAME.dat", "data/ldb/arcade/MAME.dat"]);
todo.push(["metadat/fbneo-split/FBNeo - Arcade Games.dat", "data/ldb/fbneo-split/FBNeo - Arcade Games.dat"]);

const queue = todo.filter(([, local]) => force || !fs.existsSync(local));
let got = 0, bytes = 0;
async function worker() {
  while (queue.length) {
    const [remote, local] = queue.shift();
    const r = await fetch(RAW + remote.split("/").map(encodeURIComponent).join("/"), { headers: { "User-Agent": UA } });
    if (!r.ok) { console.error(`HTTP ${r.status} ${remote}`); continue; }
    const buf = Buffer.from(await r.arrayBuffer());
    fs.mkdirSync(local.split("/").slice(0, -1).join("/"), { recursive: true });
    fs.writeFileSync(local, buf);
    got++; bytes += buf.length;
  }
}
await Promise.all([1, 2, 3, 4].map(worker));
console.log(`libretro: ${got} bajados (${(bytes / 1048576).toFixed(1)} MB), ${todo.length - got} ya estaban`);
