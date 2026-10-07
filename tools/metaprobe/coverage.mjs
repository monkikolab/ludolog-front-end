// Cobertura de libretro-database por consola del catalogo: cuantas entradas de identidad hay y
// que parte de ellas trae cada campo (cruzando por crc, serial o nombre, como hara la app).
// Cuenta tambien los juegos «unicos»: el titulo sin region ni version, que es lo que se juega.
import fs from "node:fs";

const ROMAN = { ii: "2", iii: "3", iv: "4", v: "5", vi: "6", vii: "7", viii: "8", ix: "9" };
const norm = (s) => s.toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "").replace(/\([^)]*\)|\[[^\]]*\]/g, " ")
  .replace(/&/g, " and ").replace(/['’`™®©]/g, "").replace(/[^a-z0-9]+/g, " ").split(" ").filter(Boolean)
  .map((w) => ROMAN[w] ?? w).filter((w) => !["the", "a", "an"].includes(w)).join(" ");
const serialKey = (s) => (s || "").toUpperCase().replace(/[^A-Z0-9]/g, "");

function parseDat(file) {
  if (!fs.existsSync(file)) return [];
  const txt = fs.readFileSync(file, "utf8");
  const out = [];
  for (const block of txt.split(/\n(?:game|machine) \(/).slice(1)) {
    const g = { roms: [] };
    // Con comillas o sin ellas: los de jugadores escriben «users 2».
    for (const m of block.matchAll(/^\s*(\w+) (?:"((?:[^"\\]|\\.)*)"|([^\s(]\S*))\s*$/gm)) if (m[1] !== "rom" && !(m[1] in g)) g[m[1]] = m[2] ?? m[3];
    for (const m of block.matchAll(/rom \(((?:"[^"]*"|[^)"])*)\)/g)) {
      const r = {};
      for (const f of m[1].matchAll(/(\w+) ("[^"]*"|\S+)/g)) r[f[1]] = f[2].replace(/^"|"$/g, "");
      if (r.crc) r.crc = r.crc.toLowerCase();
      g.roms.push(r);
    }
    out.push(g);
  }
  return out;
}

const FIELDS = ["genre", "developer", "publisher", "releaseyear", "franchise", "maxusers", "esrb", "serial"];
function loadMeta(ra) {
  const meta = { byCrc: new Map(), bySerial: new Map(), byName: new Map() };
  const put = (m, k, f, v) => { if (!k || !v) return; if (!m.has(k)) m.set(k, {}); m.get(k)[f] ??= v; };
  for (const field of FIELDS) {
    for (const g of parseDat(`ldb/${field}/${ra}.dat`)) {
      const name = g.comment || g.name;
      for (const f of ["genre", "developer", "publisher", "releaseyear", "franchise", "users", "maxusers", "esrb_rating", "esrb", "serial", "description"]) {
        let v = g[f]; if (!v) continue;
        const key = f === "users" ? "maxusers" : f === "esrb_rating" ? "esrb" : f;
        put(meta.byName, name, key, v); put(meta.bySerial, serialKey(g.serial), key, v);
        for (const r of g.roms) { put(meta.byCrc, r.crc, key, v); put(meta.bySerial, serialKey(r.serial), key, v); }
      }
    }
  }
  return meta;
}

const matrix = JSON.parse(fs.readFileSync("matrix.json", "utf8"));
const COLS = ["genre", "releaseyear", "developer", "publisher", "maxusers", "franchise", "esrb", "description"];
const results = [];
console.log(["consola", "fuente", "entradas", "unicos", "genero", "año", "desarr", "editor", "jugad", "serie", "esrb", "sinopsis"].join("\t"));
for (const s of matrix) {
  if (!s.raName) continue;
  const src = ["no-intro", "redump", "dat"].map((f) => [f, `ldb/${f}/${s.raName}.dat`]).filter(([, p]) => fs.existsSync(p));
  if (!src.length) continue;
  const meta = loadMeta(s.raName);
  const seen = new Set();
  const entries = [];
  for (const [f, p] of src) for (const g of parseDat(p)) {
    const name = g.name || g.comment || "";
    if (seen.has(name)) continue; // la misma entrada en dos dats (PSP en no-intro y redump)
    seen.add(name);
    entries.push({ g, f });
  }
  // Fuera las BIOS y las entradas sin nombre.
  const games = entries.filter(({ g }) => (g.name || "") && !/\[BIOS\]|\(BIOS\)/i.test(g.name || ""));
  const has = Object.fromEntries(COLS.map((c) => [c, 0]));
  const titles = new Set();
  for (const { g } of games) {
    const name = g.name || g.comment;
    titles.add(norm(name.replace(/\s*[(\[].*$/, "")));
    const serials = [g.serial, ...g.roms.map((r) => r.serial)].filter(Boolean).flatMap((x) => x.split(/\s*[,\/]\s*/)).map(serialKey);
    const m = { ...(meta.byName.get(name) || {}) };
    for (const k of serials) Object.assign(m, { ...(meta.bySerial.get(k) || {}), ...m });
    for (const r of g.roms) Object.assign(m, { ...(meta.byCrc.get(r.crc) || {}), ...m });
    // Los dat/ de libretro traen a veces los campos dentro de la propia entrada.
    const syn = (d) => d && d !== (g.name || g.comment) && d.length > 60;
    if (!syn(m.description) && !syn(g.description)) { delete m.description; delete g.description; }
    for (const c of COLS) if (m[c] || g[c] || (c === "releaseyear" && g.year) || (c === "developer" && g.manufacturer)) has[c]++;
  }
  const pct = (n) => `${Math.round((100 * n) / games.length)}%`;
  const row = [s.id, src.map(([f]) => f).join("+"), games.length, titles.size, ...COLS.map((c) => pct(has[c]))];
  results.push({ id: s.id, src: row[1], entries: games.length, unique: titles.size, ...Object.fromEntries(COLS.map((c) => [c, Math.round((100 * has[c]) / games.length)])) });
  console.log(row.join("\t"));
}
fs.writeFileSync("coverage.json", JSON.stringify(results, null, 1));
