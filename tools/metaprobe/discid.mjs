// Identidad de los juegos de disco sin copiarlos: se leen unos pocos sectores por adb.
//  - .iso/.bin: ISO 9660 -> SYSTEM.CNF (PSX/PS2), UMD_DATA.BIN y PSP_GAME/PARAM.SFO (PSP),
//    PS3_GAME/PARAM.SFO (PS3). Un .bin crudo (2352 bytes por sector) se lee saltando la cabecera.
//  - .chd: la cabecera v5 lleva el SHA1 de los datos sin comprimir (rawsha1). En un DVD es el SHA1
//    de la ISO, el mismo que da Redump.
//  - .rvz: la cabecera guarda copia de los primeros 0x80 bytes del disco, con el ID de 6 letras.
// Escribe discid.tsv: ruta, tipo, clave, valor.
import fs from "node:fs";
import { spawnSync } from "node:child_process";

const ADB = process.env.ADB_WIN || "adb";
// Sin SERIAL, adb usa el unico aparato conectado.
const DEV = process.env.SERIAL ? ["-s", process.env.SERIAL] : [];

function readBlocks(path, bs, skip, count) {
  const q = "'" + path.replace(/'/g, "'\\''") + "'";
  const r = spawnSync(ADB, [...DEV, "exec-out", `dd if=${q} bs=${bs} skip=${skip} count=${count} 2>/dev/null`],
    { maxBuffer: 64 * 1024 * 1024, env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
  return r.stdout;
}

/** Lector de sectores logicos de 2048 bytes, para ISO o BIN crudo. */
function sectorReader(path, raw) {
  if (!raw) return (lba, n = 1) => readBlocks(path, 2048, lba, n);
  // BIN crudo: modo 1 -> datos en +16; modo 2 forma 1 -> +24.
  const probe = readBlocks(path, 2352, 16, 1);
  const mode = probe[15];
  const off = mode === 2 ? 24 : 16;
  return (lba, n = 1) => {
    const buf = readBlocks(path, 2352, lba, n);
    const out = Buffer.alloc(2048 * n);
    for (let i = 0; i < n; i++) buf.copy(out, i * 2048, i * 2352 + off, i * 2352 + off + 2048);
    return out;
  };
}

function dirEntries(read, lba, size) {
  const n = Math.ceil(size / 2048);
  const buf = read(lba, n);
  const out = [];
  let p = 0;
  while (p < buf.length) {
    const len = buf[p];
    if (len === 0) { p = (Math.floor(p / 2048) + 1) * 2048; continue; }
    const extent = buf.readUInt32LE(p + 2), dsize = buf.readUInt32LE(p + 10), flags = buf[p + 25];
    const nlen = buf[p + 32];
    const name = buf.toString("latin1", p + 33, p + 33 + nlen).replace(/;1$/, "");
    if (nlen === 1 && (buf[p + 33] === 0 || buf[p + 33] === 1)) { p += len; continue; }
    out.push({ name: name.toUpperCase(), extent, size: dsize, dir: (flags & 2) !== 0 });
    p += len;
  }
  return out;
}

function readFile(read, e) {
  return read(e.extent, Math.ceil(e.size / 2048)).subarray(0, e.size);
}

function parseSfo(buf) {
  if (buf.toString("latin1", 0, 4) !== "\0PSF") return {};
  const keyTable = buf.readUInt32LE(8), dataTable = buf.readUInt32LE(12), count = buf.readUInt32LE(16);
  const out = {};
  for (let i = 0; i < count; i++) {
    const e = 20 + i * 16;
    const keyOff = buf.readUInt16LE(e), fmt = buf.readUInt16LE(e + 2), len = buf.readUInt32LE(e + 4), dOff = buf.readUInt32LE(e + 12);
    let k = keyTable + keyOff, key = "";
    while (buf[k]) key += String.fromCharCode(buf[k++]);
    const at = dataTable + dOff;
    out[key] = fmt === 0x0404 ? buf.readUInt32LE(at) : buf.toString("utf8", at, at + len).replace(/\0+$/, "");
  }
  return out;
}

function iso(path, raw) {
  const read = sectorReader(path, raw);
  const pvd = read(16);
  if (pvd.toString("latin1", 1, 6) !== "CD001") return [["error", "no ISO9660 PVD"]];
  const root = { extent: pvd.readUInt32LE(156 + 2), size: pvd.readUInt32LE(156 + 10) };
  const top = dirEntries(read, root.extent, root.size);
  const found = [["volume", pvd.toString("latin1", 40, 72).trim()]];
  const cnf = top.find((e) => e.name === "SYSTEM.CNF");
  if (cnf) {
    const txt = readFile(read, cnf).toString("latin1");
    const m = txt.match(/BOOT2?\s*=\s*cdrom0?:\\?([A-Z]{4}[_-]\d{3}\.\d{2})/i);
    found.push(["system.cnf", txt.replace(/\s+/g, " ").trim()]);
    if (m) found.push(["serial", m[1].toUpperCase().replace("_", "-").replace(".", "")]);
  }
  const umd = top.find((e) => e.name === "UMD_DATA.BIN");
  if (umd) {
    const t = readFile(read, umd).toString("latin1").split("|")[0];
    found.push(["serial", t.trim()]);
  }
  for (const d of ["PSP_GAME", "PS3_GAME"]) {
    const dir = top.find((e) => e.name === d && e.dir);
    if (!dir) continue;
    const sfo = dirEntries(read, dir.extent, dir.size).find((e) => e.name === "PARAM.SFO");
    if (!sfo) continue;
    const p = parseSfo(readFile(read, sfo));
    for (const k of ["TITLE", "TITLE_ID", "DISC_ID", "CATEGORY", "APP_VER", "VERSION"]) if (p[k] !== undefined) found.push([`sfo.${k}`, String(p[k])]);
  }
  return found;
}

// Cabeceras ya volcadas por inventory.sh.
const inv = fs.readFileSync("inventory.txt", "utf8").split("\n");
const hex = {};
let cur = null;
for (const l of inv) {
  const [k, a, b] = l.split("\t");
  if (k === "FILE") cur = a;
  else if (k === "HEX" && a === "0") hex[cur] = Buffer.from(b.trim(), "hex");
}

const rows = [];
const paths = fs.readFileSync("library.idx", "utf8").split("\n").slice(1).map((s) => s.trim()).filter(Boolean);
for (const path of paths) {
  const ext = path.split(".").pop().toLowerCase();
  try {
    if (ext === "chd") {
      const h = hex[path];
      if (!h || h.toString("latin1", 0, 8) !== "MComprHD") { rows.push([path, "chd", "error", "no header"]); continue; }
      const version = h.readUInt32BE(12);
      const comp = [0, 1, 2, 3].map((i) => h.toString("latin1", 16 + i * 4, 20 + i * 4).replace(/\0/g, "")).filter(Boolean).join(",");
      rows.push([path, "chd", "version", String(version)]);
      rows.push([path, "chd", "compressors", comp]);
      rows.push([path, "chd", "logicalbytes", String(h.readBigUInt64BE(32))]);
      rows.push([path, "chd", "unitbytes", String(h.readUInt32BE(60))]);
      rows.push([path, "chd", "rawsha1", h.subarray(64, 84).toString("hex")]);
      rows.push([path, "chd", "sha1", h.subarray(84, 104).toString("hex")]);
    } else if (ext === "rvz") {
      const h = hex[path];
      rows.push([path, "rvz", "magic", h.toString("latin1", 0, 3)]);
      rows.push([path, "rvz", "gameid", h.toString("latin1", 0x58, 0x5e)]);
      rows.push([path, "rvz", "title", h.toString("latin1", 0x58 + 0x20, 0x58 + 0x80).replace(/\0.*$/s, "")]);
    } else if (ext === "iso" || ext === "bin") {
      for (const [k, v] of iso(path, ext === "bin")) rows.push([path, ext, k, v]);
    } else if (ext === "xci") {
      const m = path.match(/\[(01[0-9A-Fa-f]{14})\]/);
      rows.push([path, "xci", "titleid", m ? m[1].toUpperCase() : ""]);
    }
  } catch (e) {
    rows.push([path, ext, "error", String(e.message)]);
  }
}
fs.writeFileSync("discid.tsv", rows.map((r) => r.join("\t")).join("\n") + "\n");
for (const r of rows.filter((r) => !["logicalbytes", "unitbytes", "version", "sha1", "system.cnf"].includes(r[2]))) {
  console.log(r[0].split("/").pop().slice(0, 48).padEnd(48), r[2].padEnd(12), r[3]);
}
