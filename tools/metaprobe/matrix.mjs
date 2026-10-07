// Que tiene libretro-database para cada consola del catalogo de Ludolog: en que carpetas de
// metadat (o de dat/) hay un fichero con su raName, y cuanto pesa. Lee tree.json (arbol del repo).
import fs from "node:fs";

const toml = fs.readFileSync(new URL("../../app/src/main/assets/systems.toml", import.meta.url), "utf8");
const systems = toml.split("\n[[system]]").slice(1).map((b) => {
  const f = (k) => (b.match(new RegExp("(?:^|\\n)" + k + " *= *\"([^\"]*)\"")) || [null, ""])[1];
  return { id: f("id"), name: f("name"), raName: f("raName") };
});
const tree = JSON.parse(fs.readFileSync("tree.json", "utf8")).tree.filter((e) => e.type === "blob");
const files = new Map(); // path -> size
for (const e of tree) files.set(e.path, e.size);

const FOLDERS = ["no-intro", "redump", "tosec", "fbneo-split", "mame", "genre", "developer", "publisher", "releaseyear",
  "releasemonth", "franchise", "maxusers", "serial", "esrb", "origin", "rumble", "hacks", "homebrew", "lost-level-archive"];
const short = { "no-intro": "NI", redump: "RD", tosec: "TO", "fbneo-split": "FB", mame: "MA", genre: "gen", developer: "dev",
  publisher: "pub", releaseyear: "yr", releasemonth: "mo", franchise: "fra", maxusers: "ply", serial: "ser", esrb: "esrb",
  origin: "ori", rumble: "rum", hacks: "hak", homebrew: "hb", "lost-level-archive": "lla" };
const out = [];
for (const s of systems) {
  if (!s.raName) { out.push({ ...s, have: [], dat: false }); continue; }
  const have = FOLDERS.filter((f) => files.has(`metadat/${f}/${s.raName}.dat`)).map((f) => ({ f, size: files.get(`metadat/${f}/${s.raName}.dat`) }));
  const dat = files.has(`dat/${s.raName}.dat`) ? files.get(`dat/${s.raName}.dat`) : 0;
  out.push({ ...s, have, dat });
}
for (const r of out) {
  const cols = r.have.map((h) => `${short[h.f]}${h.f.match(/^(no-intro|redump|tosec|fbneo-split|mame|developer)$/) ? "(" + Math.round(h.size / 1024) + "K)" : ""}`);
  console.log(`${r.id.padEnd(16)} ${(r.raName || "-").slice(0, 44).padEnd(44)} ${r.dat ? "dat(" + Math.round(r.dat / 1024) + "K) " : ""}${cols.join(" ")}`);
}
fs.writeFileSync("matrix.json", JSON.stringify(out, null, 1));
// Lo que hay en las carpetas de arcade y en dat/, para ver nombres que no son raName del catalogo.
for (const f of ["fbneo-split", "mame", "mame-split", "mame-nonmerged", "mame-member", "fbneo-member"]) {
  console.log(`\n${f}:`, tree.filter((e) => e.path.startsWith(`metadat/${f}/`)).map((e) => `${e.path.split("/").pop()} (${Math.round(e.size / 1024)}K)`).join(", "));
}
console.log("\ndat/:", tree.filter((e) => /^dat\/[^/]+\.dat$/.test(e.path)).length, "ficheros");
