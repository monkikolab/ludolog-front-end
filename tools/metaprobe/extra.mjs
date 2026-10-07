// Dos identidades que no salen de una cabecera suelta:
//  - Un .smd (Mega Drive intercalado en bloques de 16 KB tras 512 bytes de cabecera) solo coincide
//    con No-Intro despues de desentrelazarlo. Los zip son pequenos: se traen y se calcula ahi.
//  - Un .rvz dentro de un zip: se leen los primeros 64 KB del zip, se descomprime lo justo del
//    primer fichero y se lee el ID del disco en la copia de su cabecera (0x58).
// Escribe extra.tsv: ruta, clave, valor.
import fs from "node:fs";
import zlib from "node:zlib";
import { spawnSync } from "node:child_process";

const ADB = process.env.ADB_WIN || "adb";
// Sin SERIAL, adb usa el unico aparato conectado.
const DEV = process.env.SERIAL ? ["-s", process.env.SERIAL] : [];
const adb = (args, opts = {}) => spawnSync(ADB, [...DEV, ...args], { maxBuffer: 256 * 1024 * 1024, env: { ...process.env, MSYS_NO_PATHCONV: "1" }, ...opts });
const q = (p) => "'" + p.replace(/'/g, "'\\''") + "'";

const CRC_TABLE = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
const crc32 = (buf) => { let c = 0xffffffff; for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8); return ((c ^ 0xffffffff) >>> 0).toString(16).padStart(8, "0"); };

/** Los ficheros de un zip, con sus datos ya descomprimidos. */
function unzip(buf) {
  const out = [];
  let eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  const count = buf.readUInt16LE(eocd + 10), cdOff = buf.readUInt32LE(eocd + 16);
  let p = cdOff;
  for (let i = 0; i < count; i++) {
    const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20);
    const nlen = buf.readUInt16LE(p + 28), elen = buf.readUInt16LE(p + 30), clen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString("utf8", p + 46, p + 46 + nlen);
    const lnl = buf.readUInt16LE(local + 26), lel = buf.readUInt16LE(local + 28);
    const data = buf.subarray(local + 30 + lnl + lel, local + 30 + lnl + lel + csize);
    out.push({ name, data: method === 8 ? zlib.inflateRawSync(data) : data });
    p += 46 + nlen + elen + clen;
  }
  return out;
}

function deinterleaveSmd(buf) {
  const body = buf.subarray(512);
  const out = Buffer.alloc(body.length);
  for (let blk = 0; blk < body.length; blk += 16384) {
    const half = Math.min(8192, (body.length - blk) / 2);
    for (let i = 0; i < half; i++) {
      out[blk + i * 2 + 1] = body[blk + i];
      out[blk + i * 2] = body[blk + 8192 + i];
    }
  }
  return out;
}

const rows = [];
const paths = fs.readFileSync("library.idx", "utf8").split("\n").slice(1).map((s) => s.trim()).filter(Boolean);
fs.mkdirSync("pulled", { recursive: true });
for (const path of paths) {
  if (path.includes("/sega32x/")) {
    const local = `pulled/${path.split("/").pop()}`;
    if (!fs.existsSync(local)) adb(["pull", path, local]);
    for (const e of unzip(fs.readFileSync(local))) {
      if (/\.smd$/i.test(e.name)) rows.push([path, "smdcrc", crc32(deinterleaveSmd(e.data))]);
      else if (/\.(md|gen|bin|32x)$/i.test(e.name)) rows.push([path, "crc", crc32(e.data)]);
    }
  }
  if (path.includes("/gc/") && /\.zip$/i.test(path)) {
    const head = adb(["exec-out", `dd if=${q(path)} bs=65536 count=1 2>/dev/null`]).stdout;
    const nlen = head.readUInt16LE(26), elen = head.readUInt16LE(28), method = head.readUInt16LE(8);
    const data = head.subarray(30 + nlen + elen);
    const raw = method === 8 ? zlib.inflateRawSync(data, { finishFlush: zlib.constants.Z_SYNC_FLUSH }) : data;
    if (raw.toString("latin1", 0, 3) === "RVZ") {
      rows.push([path, "gameid", raw.toString("latin1", 0x58, 0x5e)]);
      rows.push([path, "title", raw.toString("latin1", 0x78, 0xd8).replace(/\0.*$/s, "")]);
    }
  }
}
fs.writeFileSync("extra.tsv", rows.map((r) => r.join("\t")).join("\n") + "\n");
for (const r of rows) console.log(r[0].split("/").pop().padEnd(60), r[1].padEnd(8), r[2]);
