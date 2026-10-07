// Baja de libretro-database lo que falte para medir la cobertura de TODAS las consolas del
// catalogo: identidad (no-intro, redump, dat/) y los campos de metadat. Mas los XML de arcade.
import fs from "node:fs";

const matrix = JSON.parse(fs.readFileSync("matrix.json", "utf8"));
const tree = new Map(JSON.parse(fs.readFileSync("tree.json", "utf8")).tree.filter((e) => e.type === "blob").map((e) => [e.path, e.size]));
const RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/";
const FOLDERS = ["no-intro", "redump", "genre", "developer", "publisher", "releaseyear", "releasemonth", "franchise", "maxusers", "serial", "esrb"];

const todo = [];
for (const s of matrix) {
  if (!s.raName) continue;
  for (const f of FOLDERS) {
    const p = `metadat/${f}/${s.raName}.dat`;
    if (tree.has(p)) todo.push([p, `ldb/${f}/${s.raName}.dat`]);
  }
  const d = `dat/${s.raName}.dat`;
  if (tree.has(d)) todo.push([d, `ldb/dat/${s.raName}.dat`]);
}
for (const p of ["metadat/mame/MAME 2003-Plus XML.xml", "metadat/mame/MAME 2016 XML (Arcade Only).xml",
  "metadat/fbneo-split/FinalBurn Neo (ClrMame Pro XML, Arcade only).dat", "metadat/mame/MAME.dat"]) {
  todo.push([p, `ldb/arcade/${p.split("/").pop()}`]);
}

let got = 0, bytes = 0;
const queue = todo.filter(([, local]) => !fs.existsSync(local));
async function worker() {
  while (queue.length) {
    const [remote, local] = queue.shift();
    const url = RAW + remote.split("/").map(encodeURIComponent).join("/");
    const r = await fetch(url, { headers: { "User-Agent": "LudologResearch/0.1" } });
    if (!r.ok) { console.error(`HTTP ${r.status} ${remote}`); continue; }
    const buf = Buffer.from(await r.arrayBuffer());
    fs.mkdirSync(local.split("/").slice(0, -1).join("/"), { recursive: true });
    fs.writeFileSync(local, buf);
    got++; bytes += buf.length;
  }
}
await Promise.all([1, 2, 3, 4].map(worker));
console.log(`bajados ${got} de ${todo.length} (${(bytes / 1048576).toFixed(1)} MB); ya estaban ${todo.length - got}`);
