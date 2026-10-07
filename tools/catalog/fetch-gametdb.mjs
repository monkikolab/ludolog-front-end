// Baja las bases de GameTDB (https://www.gametdb.com) que usa el catalogo y deja su XML en
// data/gtdb/. Su FAQ permite usarlas en programas enlazandoles y avisandoles: van en NOTICE.md.
//
// Cada zip lleva un solo XML. Su servidor rechaza a quien no parece un navegador, de ahi el
// User-Agent.
import fs from "node:fs";
import zlib from "node:zlib";

const FILES = ["wiitdb", "wiiutdb", "dstdb", "3dstdb", "ps3tdb", "switchtdb"];
const BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36";
fs.mkdirSync("data/gtdb", { recursive: true });

/** El primer fichero de un zip, descomprimido: el directorio central dice donde esta. */
function firstEntry(buf) {
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  const cd = buf.readUInt32LE(eocd + 16);
  const method = buf.readUInt16LE(cd + 10), csize = buf.readUInt32LE(cd + 20), local = buf.readUInt32LE(cd + 42);
  const nlen = buf.readUInt16LE(local + 26), elen = buf.readUInt16LE(local + 28);
  const data = buf.subarray(local + 30 + nlen + elen, local + 30 + nlen + elen + csize);
  return method === 8 ? zlib.inflateRawSync(data) : data;
}

for (const f of FILES) {
  const r = await fetch(`https://www.gametdb.com/${f}.zip`, { headers: { "User-Agent": BROWSER }, signal: AbortSignal.timeout(120_000) });
  if (!r.ok) { console.error(`${f}: HTTP ${r.status}`); continue; }
  const xml = firstEntry(Buffer.from(await r.arrayBuffer()));
  fs.writeFileSync(`data/gtdb/${f}.xml`, xml);
  console.log(`${f}: ${(xml.length / 1048576).toFixed(1)} MB`);
}
