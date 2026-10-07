// El cruce: por cada juego de la biblioteca, que es exactamente (identidad), que se sabe de el
// (metadatos, de cada fuente gratuita) y que arte y video se encuentran con ese nombre exacto.
// Entrada: library.idx, inventory.txt, discid.tsv, systems.json, ldb/, gtdb/, wd/, steam/, art/.
// Salida: report.tsv (una fila por juego), report.json y un resumen por consola en pantalla.
import fs from "node:fs";
import crypto from "node:crypto";

// --------------------------------------------------------------------------- nombres

// Sin la «x»: «Metal Slug X» y «Mega Man X» no son el 10.
const ROMAN = { ii: "2", iii: "3", iv: "4", v: "5", vi: "6", vii: "7", viii: "8", ix: "9" };
/** Un titulo reducido a lo que se compara: sin etiquetas, acentos, articulos ni puntuacion. */
function norm(s) {
  return s.toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "")
    .replace(/\([^)]*\)|\[[^\]]*\]/g, " ")
    .replace(/&/g, " and ").replace(/['’`™®©]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .split(" ").filter(Boolean)
    .map((w) => ROMAN[w] ?? w)
    .filter((w) => !["the", "a", "an"].includes(w))
    .join(" ");
}
// DEVICE_TAG: la etiqueta de aparato que se pone a mano al final del nombre ("_<etiqueta>").
const TAG = process.env.DEVICE_TAG ? new RegExp(`_${process.env.DEVICE_TAG}$`, "i") : null;
/** El titulo sin las etiquetas del final: «Silent Hill 2 (Europe) (En,Fr)» -> «Silent Hill 2». */
const base = (name) => (TAG ? name.replace(TAG, "") : name).replace(/\s*[(\[].*$/, "").replace(/_/g, " ").trim();
/** Lo que se compara de un titulo, con el disco si lo dice: el 2 de FF VII no es el 1. */
const titleKey = (name) => {
  const disc = name.match(/\(Disc (\d+)\)/i)?.[1];
  return norm(base(name)) + (disc ? ` disc ${disc}` : "");
};
const regionOf = (name) => {
  const tags = (name.match(/\(([^)]*)\)/g) || []).join(" ").toLowerCase();
  if (/usa|ntsc-u/.test(tags)) return "usa";
  if (/europe|spain|france|germany|italy|uk|pal/.test(tags)) return "eur";
  if (/japan|ntsc-j/.test(tags)) return "jpn";
  return "";
};
/** Lo que libretro cambia en los nombres de sus miniaturas. */
const thumbName = (name) => name.replace(/[&*/:`<>?\\|"]/g, "_");
const serialKey = (s) => (s || "").toUpperCase().replace(/[^A-Z0-9]/g, "");

// --------------------------------------------------------------------------- lectores

/** Un .dat de clrmamepro: bloques «game ( … )» con campos y roms. */
function parseDat(file) {
  if (!fs.existsSync(file)) return [];
  const txt = fs.readFileSync(file, "utf8");
  const out = [];
  for (const block of txt.split(/\n(?:game|machine) \(/).slice(1)) {
    const g = { roms: [] };
    for (const m of block.matchAll(/^\s*(\w+) "((?:[^"\\]|\\.)*)"/gm)) if (!(m[1] in g)) g[m[1]] = m[2];
    // Los nombres de rom llevan parentesis —«(Europe)»—: el cierre del bloque es el primero que no
    // esta dentro de comillas.
    for (const m of block.matchAll(/rom \(((?:"[^"]*"|[^)"])*)\)/g)) {
      const r = {};
      for (const f of m[1].matchAll(/(\w+) ("[^"]*"|\S+)/g)) r[f[1]] = f[2].replace(/^"|"$/g, "");
      if (r.crc) r.crc = r.crc.toLowerCase();
      if (r.md5) r.md5 = r.md5.toLowerCase();
      if (r.sha1) r.sha1 = r.sha1.toLowerCase();
      g.roms.push(r);
    }
    out.push(g);
  }
  return out;
}

/** Indices de un conjunto de entradas: por hash, por serial, por nombre y por titulo. */
function indexDat(entries) {
  const ix = { sha1: new Map(), md5: new Map(), crc: new Map(), serial: new Map(), name: new Map(), title: new Map(), romName: new Map() };
  const add = (m, k, v) => { if (!k) return; if (!m.has(k)) m.set(k, []); m.get(k).push(v); };
  for (const g of entries) {
    const name = g.name || g.comment || "";
    add(ix.name, name, g);
    add(ix.title, titleKey(name), g);
    add(ix.serial, serialKey(g.serial), g);
    for (const r of g.roms) {
      add(ix.sha1, r.sha1, g); add(ix.md5, r.md5, g); add(ix.crc, r.crc, g);
      add(ix.serial, serialKey(r.serial), g);
      add(ix.romName, (r.name || "").toLowerCase(), g);
    }
  }
  return ix;
}

const LDB = "ldb";
const META_FIELDS = ["genre", "developer", "publisher", "releaseyear", "releasemonth", "franchise", "maxusers", "serial"];
/** Metadatos de libretro de una consola: cada campo en su carpeta, cruzado por crc, serial o nombre. */
function loadMeta(ra) {
  const meta = { byCrc: new Map(), bySerial: new Map(), byName: new Map() };
  const put = (m, k, field, value) => { if (!k || !value) return; if (!m.has(k)) m.set(k, {}); m.get(k)[field] ??= value; };
  for (const field of META_FIELDS) {
    for (const g of parseDat(`${LDB}/${field}/${ra}.dat`)) {
      const name = g.comment || g.name;
      const value = g[field] ?? (field === "maxusers" ? g.users : undefined);
      // En los .dat ricos (PSX, PS2) cada entrada trae varios campos a la vez.
      for (const f of ["genre", "developer", "publisher", "releaseyear", "releasemonth", "franchise", "users", "description"]) {
        const v = g[f];
        if (!v) continue;
        put(meta.byName, name, f, v); put(meta.bySerial, serialKey(g.serial), f, v);
        for (const r of g.roms) { put(meta.byCrc, r.crc, f, v); put(meta.bySerial, serialKey(r.serial), f, v); }
      }
      if (value) {
        put(meta.byName, name, field, value);
        put(meta.bySerial, serialKey(g.serial), field, value);
        for (const r of g.roms) { put(meta.byCrc, r.crc, field, value); put(meta.bySerial, serialKey(r.serial), field, value); }
      }
    }
  }
  return meta;
}

/** GameTDB: un XML con <game> por juego. */
function loadGtdb(file) {
  const byId = new Map(), byTitle = new Map();
  if (!fs.existsSync(file)) return { byId, byTitle };
  const xml = fs.readFileSync(file, "utf8");
  for (const block of xml.split("<game ").slice(1)) {
    const tag = (t) => block.match(new RegExp(`<${t}>([^<]*)</${t}>`))?.[1];
    const titles = [...block.matchAll(/<title>([^<]*)<\/title>/g)].map((m) => m[1]);
    const g = {
      id: tag("id"), type: tag("type"), title: titles[0], titles,
      developer: tag("developer"), publisher: tag("publisher"),
      year: block.match(/<date year="(\d+)"/)?.[1], genre: tag("genre"),
      players: block.match(/<input players="(\d+)"/)?.[1],
    };
    if (!g.id) continue;
    byId.set(g.id.toUpperCase(), g);
    for (const t of titles) { const k = norm(t); if (!byTitle.has(k)) byTitle.set(k, []); byTitle.get(k).push(g); }
  }
  return { byId, byTitle };
}

/** Wikidata: { qid: {label, aliases, genres, year, devs, pubs, series, steam} } -> indice por titulo. */
function loadWd(key) {
  const f = `wd/${key}.json`;
  const games = fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, "utf8")) : {};
  const byTitle = new Map(), bySteam = new Map();
  for (const [qid, g] of Object.entries(games)) {
    g.qid = qid;
    for (const t of [g.label, ...g.aliases].filter(Boolean)) {
      const k = norm(t); if (!k) continue;
      if (!byTitle.has(k)) byTitle.set(k, new Set()); byTitle.get(k).add(g);
    }
    for (const s of g.steam) bySteam.set(s, g);
  }
  return { byTitle, bySteam };
}

function loadList(file) { return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : []; }

// --------------------------------------------------------------------------- consolas

const SYS = JSON.parse(fs.readFileSync("systems.json", "utf8"));
const PLATFORM = {
  snes: { ra: "Nintendo - Super Nintendo Entertainment System", id: "no-intro", wd: ["snes"] },
  gba: { ra: "Nintendo - Game Boy Advance", id: "no-intro", wd: ["gba"] },
  nes: { ra: "Nintendo - Nintendo Entertainment System", id: "no-intro", wd: ["nes"] },
  n64: { ra: "Nintendo - Nintendo 64", id: "no-intro", wd: ["n64"] },
  sega32x: { ra: "Sega - 32X", id: "no-intro", wd: ["sega32x", "megadrive"], also: ["Sega - Mega Drive - Genesis"] },
  psx: { ra: "Sony - PlayStation", id: "redump", wd: ["psx"] },
  ps2: { ra: "Sony - PlayStation 2", id: "redump", wd: ["ps2"] },
  psp: { ra: "Sony - PlayStation Portable", id: "redump", wd: ["psp"], also2: "no-intro" },
  gc: { ra: "Nintendo - GameCube", id: "redump", wd: ["gc"], gtdb: "gtdb/wiitdb.xml" },
  wii: { ra: "Nintendo - Wii", id: "redump", wd: ["wii"], gtdb: "gtdb/wiitdb.xml" },
  ps3: { ra: "Sony - PlayStation 3", id: "redump", wd: ["ps3"], gtdb: "gtdb/ps3tdb.xml" },
  switch: { ra: "Nintendo - Switch", id: null, wd: ["switch"], gtdb: "gtdb/switchtdb.xml" },
  mame: { ra: "FBNeo - Arcade Games", id: "fbneo-split", wd: ["arcade", "neogeo", "neogeomvs", "neogeoaes", "atomiswave"] },
  steam: { ra: null, id: null, wd: ["steam"] },
  pico8: { ra: null, id: null, wd: [] },
};
const cache = {};
const once = (k, f) => (cache[k] ??= f());
/** Todas las plataformas bajadas de Wikidata, para buscar un titulo fuera de la suya. */
const PLATFORM_WD = Object.fromEntries(fs.readdirSync("wd").filter((f) => f.endsWith(".json") && f !== "steam.json").map((f) => [f.slice(0, -5), true]));

// --------------------------------------------------------------------------- entrada

const paths = fs.readFileSync("library.idx", "utf8").split("\n").slice(1).map((s) => s.trim()).filter(Boolean);
const inv = {};
{
  let cur = null;
  for (const l of fs.readFileSync("inventory.txt", "utf8").split("\n")) {
    const [k, a, b, c] = l.split("\t");
    if (k === "FILE") { cur = inv[a] = { zip: [], hex: {} }; continue; }
    if (!cur || !k) continue;
    if (k === "ZIP") cur.zip.push({ crc: a, size: Number(b), name: c });
    else if (k === "HEX") cur.hex[a] = b;
    else cur[k.toLowerCase()] = a;
  }
}
const disc = {};
for (const l of fs.readFileSync("discid.tsv", "utf8").split("\n")) {
  const [p, t, k, v] = l.split("\t");
  if (p) (disc[p] ??= {})[k] = v;
}
// Lo de extra.mjs: huellas de los .smd desentrelazados e ID de disco dentro de un zip.
if (fs.existsSync("extra.tsv")) for (const l of fs.readFileSync("extra.tsv", "utf8").split("\n")) {
  const [p, k, v] = l.split("\t");
  if (p) (disc[p] ??= {})[k] = v;
}

// Los .n64 van al reves: se les da la vuelta y se calcula la huella que usa No-Intro (.z64).
function z64Sha1(file) {
  const b = fs.readFileSync(file);
  const first = b.readUInt32BE(0);
  if (first === 0x40123780) for (let i = 0; i + 3 < b.length; i += 4) { const x = b[i], y = b[i + 1]; b[i] = b[i + 3]; b[i + 1] = b[i + 2]; b[i + 2] = y; b[i + 3] = x; }
  else if (first === 0x37804012) for (let i = 0; i + 1 < b.length; i += 2) { const x = b[i]; b[i] = b[i + 1]; b[i + 1] = x; }
  return crypto.createHash("sha1").update(b).digest("hex");
}

// --------------------------------------------------------------------------- generos

const GENRE_RULES = [
  [/survival horror|psychological horror|horror/, "Horror"],
  [/action role|action rpg|action-rpg/, "Action RPG"],
  [/role.?playing|\brpg\b|jrpg/, "RPG"],
  [/metroidvania/, "Metroidvania"],
  [/platform|run & jump|run and jump|jump'?n'?run/, "Platform"],
  [/shoot|shooter|fps|first.person|third.person|lightgun|shmup|gun/, "Shooter"],
  [/fighting|versus|brawler/, "Fighting"],
  [/beat'?em|beat 'em|hack and slash|hack & slash|character.action/, "Beat 'em up"],
  [/action.adventure|action \/ adventure/, "Action-Adventure"],
  [/racing|driving/, "Racing"],
  [/sport|soccer|football|baseball|basketball|tennis|golf/, "Sports"],
  [/puzzle|labyrinth/, "Puzzle"],
  [/strategy|tactic/, "Strategy"],
  [/simulat/, "Simulation"],
  [/music|rhythm|danc/, "Music"],
  [/party/, "Party"],
  [/stealth/, "Stealth"],
  [/adventure/, "Adventure"],
  [/action/, "Action"],
];
function canon(genres) {
  const out = new Set();
  for (const raw of genres) {
    for (const piece of String(raw).toLowerCase().split(/[,/]| - /)) {
      const s = piece.trim(); if (!s) continue;
      for (const [rx, g] of GENRE_RULES) if (rx.test(s)) { out.add(g); break; }
    }
  }
  return [...out];
}

// --------------------------------------------------------------------------- por juego

const rows = [];
for (const path of paths) {
  const folder = path.split("/").slice(-2, -1)[0];
  const file = path.split("/").pop();
  const ext = file.split(".").pop().toLowerCase();
  const stem = file.replace(/\.[^.]+$/, "");
  const P = PLATFORM[folder];
  const r = { folder, file, ext, idMethod: "", canonical: "", serial: "", sources: {}, genresRaw: {}, horror: false };
  const I = inv[path] || { zip: [], hex: {} };
  const D = disc[path] || {};

  if (folder === "pico8") { r.idMethod = "no es un juego (perfil de DoomForge)"; rows.push(r); continue; }

  // --- identidad
  const idx = P.ra && P.id ? once(`id:${P.id}:${P.ra}`, () => indexDat(parseDat(`${LDB}/${P.id}/${P.ra}.dat`))) : null;
  const extraIdx = (P.also || []).map((ra) => ({ ra, ix: once(`id:no-intro:${ra}`, () => indexDat(parseDat(`${LDB}/no-intro/${ra}.dat`))) }));
  const noIntroPsp = P.also2 ? once(`id:no-intro:${P.ra}`, () => indexDat(parseDat(`${LDB}/no-intro/${P.ra}.dat`))) : null;
  let entry = null, entryRa = P.ra;
  r.exact = false;
  // exact: la identidad sale del contenido o de un nombre que es el de la base, no de un parecido.
  const take = (list, how, ra = P.ra, exact = true) => {
    if (!entry && list && list.length) { entry = list[0]; r.idMethod = how; entryRa = ra; r.exact = exact; }
  };
  const all = [{ ra: P.ra, ix: idx }, ...extraIdx].filter((x) => x.ix);

  if (idx) {
    take(idx.sha1.get(I.sha1), "huella (sha1)");
    take(idx.sha1.get(I.sha1nohdr), "huella sin cabecera (sha1)");
    if (I.pulled) take(idx.sha1.get(z64Sha1(I.pulled)), "huella .z64 (sha1)");
    for (const z of I.zip) for (const e of all) take(e.ix.crc.get(z.crc), `CRC dentro del zip (${z.name})${e.ra !== P.ra ? " -> " + e.ra : ""}`, e.ra);
    for (const e of all) take(e.ix.crc.get(D.smdcrc), `CRC del .smd desentrelazado${e.ra !== P.ra ? " -> " + e.ra : ""}`, e.ra);
    if (folder === "mame") {
      take(idx.romName.get(file.toLowerCase()), "romset (FBNeo)");
      const mame = once("id:mame", () => indexDat(parseDat(`${LDB}/mame/MAME.dat`)));
      take(mame.romName.get(file.toLowerCase()), "romset (MAME)", "MAME");
    }
    take(idx.sha1.get(D.rawsha1), "SHA1 de la cabecera CHD = Redump");
    const serial = D.serial || D["sfo.TITLE_ID"] || D["sfo.DISC_ID"] || (stem.match(/\[([A-Z]{4}-\d{5})\]/) || [])[1];
    if (serial) { r.serial = serial; take(idx.serial.get(serialKey(serial)), `serial del disco (${serial})`); }
    if (D.gameid) {
      for (const [k, list] of idx.serial) if (k.includes(serialKey(D.gameid.slice(0, 4)))) { take(list, `ID del disco (${D.gameid}) en Redump`); break; }
    }
    // El nombre del fichero, cuando es tal cual el de la base: casi tan bueno como una huella.
    for (const e of all) take(e.ix.name.get(stem), `nombre exacto de la base${e.ra !== P.ra ? " -> " + e.ra : ""}`, e.ra);
    // Por titulo, lo ultimo: el del fichero, con su region si la dice. Y si no, el titulo que la
    // propia ROM lleva en su cabecera (SNES): un hack suele conservar el del juego original.
    if (!entry && I.hex["32688"]) {
      const internal = Buffer.from(I.hex["32688"], "hex").toString("latin1", 16, 37).trim();
      const cands = internal && idx.title.get(titleKey(internal));
      if (cands && cands.length && norm(internal) !== norm(base(stem))) {
        r.hackOf = internal;
        take([cands.find((g) => regionOf(g.name || "") === "usa") || cands[0]], `titulo interno de la ROM («${internal}»): posible hack`, P.ra, false);
      }
    }
    if (!entry) {
      const want = regionOf(stem);
      for (const e of [...all, ...(noIntroPsp ? [{ ra: P.ra, ix: noIntroPsp }] : [])]) {
        const cands = e.ix.title.get(titleKey(stem)) || [];
        const pick = cands.find((g) => want && regionOf(g.name || "") === want) || cands.find((g) => regionOf(g.name || "") === "usa") || cands[0];
        if (pick) { take([pick], `titulo del fichero${want ? "" : " (region supuesta)"}${e.ra !== P.ra ? " -> " + e.ra : ""}`, e.ra, false); break; }
      }
    }
  }
  if (entry) r.canonical = entry.name || entry.comment || "";
  if (entry?.serial && !r.serial) r.serial = entry.serial;

  // --- GameTDB
  let gt = null;
  if (P.gtdb) {
    const G = once(`gtdb:${P.gtdb}`, () => loadGtdb(P.gtdb));
    const key = D.gameid || (D["sfo.TITLE_ID"] || "").toUpperCase();
    gt = (key && G.byId.get(key.toUpperCase())) || null;
    if (!gt) {
      const t = norm(base(r.canonical || stem));
      const list = (G.byTitle.get(t) || []).filter((g) => folder !== "gc" || g.type === "GameCube");
      // La edicion normal antes que la de «[Starter Pack]»: el titulo sin corchetes primero.
      list.sort((a, b) => /\[/.test(a.title) - /\[/.test(b.title));
      gt = list.find((g) => regionOf(stem) === "eur" ? /P$/.test(g.id.slice(3, 4)) : true) || list[0] || null;
      if (gt && !r.idMethod) { r.idMethod = "titulo en GameTDB"; r.exact = false; }
    } else if (!r.idMethod || !r.exact) { r.idMethod = `ID del disco (${key}) en GameTDB`; r.exact = true; }
    if (gt && !r.canonical) r.canonical = gt.title;
    if (gt) r.sources.gametdb = { id: gt.id, genre: gt.genre, dev: gt.developer, pub: gt.publisher, year: gt.year, players: gt.players };
  }

  // --- Steam
  let steamApp = null;
  if (folder === "steam") {
    steamApp = (I.text || "").trim();
    const f = `steam/${steamApp}.json`;
    if (fs.existsSync(f)) {
      const d = Object.values(JSON.parse(fs.readFileSync(f, "utf8")))[0]?.data;
      if (d) {
        r.idMethod = `appid de Steam (${steamApp})`; r.canonical = d.name; r.exact = true;
        r.sources.steam = { genre: (d.genres || []).map((g) => g.description).join(", "), dev: (d.developers || []).join(", "), pub: (d.publishers || []).join(", "), year: (d.release_date?.date || "").match(/\d{4}/)?.[0] };
      }
    }
  }
  if (folder === "switch" && !r.canonical) { r.canonical = base(stem); r.idMethod = r.idMethod || "nombre del fichero"; }
  if (folder === "switch") r.serial = D.titleid || "";

  // --- libretro metadatos
  if (P.ra) {
    const metas = [P.ra, ...(P.also || [])].map((ra) => ({ ra, m: once(`meta:${ra}`, () => loadMeta(ra)) }));
    const m = metas.find((x) => x.ra === entryRa)?.m || metas[0].m;
    const hit = { ...(m.byName.get(r.canonical) || {}) };
    for (const rom of entry?.roms || []) Object.assign(hit, { ...m.byCrc.get(rom.crc), ...hit });
    Object.assign(hit, { ...m.bySerial.get(serialKey(r.serial)), ...hit });
    if (Object.keys(hit).length) r.sources.libretro = { genre: hit.genre, dev: hit.developer, pub: hit.publisher, year: hit.releaseyear, franchise: hit.franchise, players: hit.users || hit.maxusers, synopsis: hit.description ? "si" : "" };
    if (!r.serial && hit.serial) r.serial = hit.serial;
    if (folder === "mame" && entry) r.sources.libretro = { ...(r.sources.libretro || {}), year: entry.releaseyear || entry.year, pub: entry.publisher, dev: entry.developer || entry.manufacturer };
  }

  // --- hermanos: el mismo juego en otras regiones, por su codigo (GBA «AGB-BPEx», Wii/GC «R3Mx»).
  // Sirven para cruzar con fuentes que solo tienen la version inglesa: Wikidata, los videos.
  r.siblings = [];
  {
    // El codigo de 4 letras, venga como «AGB-BPES-SPA» o como «BPES»: las 3 primeras son el juego
    // y la ultima la region.
    const code = ["gba", "nes", "snes", "n64"].includes(folder) ? (r.serial.match(/(?:^|-)([A-Z0-9]{4})(?:-|$)/) || [])[1]?.slice(0, 3) : null;
    if (code && P.ra) {
      const serials = once(`serials:${P.ra}`, () => parseDat(`${LDB}/serial/${P.ra}.dat`));
      for (const g of serials) {
        const s = g.serial || "";
        if ((s.startsWith(code) || s.includes(`-${code}`)) && g.comment !== r.canonical) r.siblings.push(g.comment);
      }
      // Y en la propia base de identidad, que lleva el serial corto («BPEE») de otras entradas.
      if (idx) for (const [k, list] of idx.serial) {
        if (k.startsWith(code) || k.startsWith(`AGB${code}`)) for (const g of list) if (g.name !== r.canonical) r.siblings.push(g.name);
      }
    }
    // Solo Wii y GameCube: su ID es juego (3) + region (1) + editor (2). En Switch y PS3 el codigo
    // no agrupa por juego, y «ANK…» junto Blasphemous con De Blob.
    if (gt && (folder === "wii" || folder === "gc")) {
      const G = once(`gtdb:${P.gtdb}`, () => loadGtdb(P.gtdb));
      for (const [id, g] of G.byId) if (id.slice(0, 3) === gt.id.slice(0, 3) && id !== gt.id) r.siblings.push(g.title);
    }
    r.siblings = [...new Set(r.siblings.filter(Boolean))].slice(0, 12);
  }

  // --- Wikidata
  {
    let wd = null;
    if (steamApp) wd = once("wd:steam", () => loadWd("steam")).bySteam.get(steamApp) || null;
    const titles = [...new Set([r.canonical, stem, gt?.title, ...(gt?.titles || []), ...r.siblings].filter(Boolean).map((t) => norm(base(t))))];
    const want = Number(r.sources.libretro?.year || gt?.year || r.sources.steam?.year || 0);
    const choose = (set) => [...set].sort((a, b) => (want ? Math.abs((a.year || 0) - want) - Math.abs((b.year || 0) - want) : 0) || (b.genres.length - a.genres.length))[0];
    for (const key of P.wd) {
      if (wd) break;
      const W = once(`wd:${key}`, () => loadWd(key));
      for (const t of titles) {
        const set = W.byTitle.get(t);
        if (set && set.size) { wd = choose(set); r.wdHow = "titulo"; break; }
      }
    }
    // Ultimo recurso: lo de antes del subtitulo («Resident Evil 2 - Dual Shock Ver.» -> «Resident
    // Evil 2»), y solo si el año cuadra: «Mortal Kombat - Shaolin Monks» no es «Mortal Kombat».
    if (!wd && want) {
      const prefix = norm(base(r.canonical || stem).split(/ - |: /)[0]);
      for (const key of P.wd) {
        if (wd) break;
        const set = once(`wd:${key}`, () => loadWd(key)).byTitle.get(prefix);
        const c = set && choose(set);
        if (c && Math.abs((c.year || 0) - want) <= 1) { wd = c; r.wdHow = "prefijo + año"; }
      }
    }
    // Y el parecido por palabras, con el año como aval: «Dissidia 012 - Duodecim Final Fantasy»
    // frente a «Dissidia 012 Final Fantasy». Sin año no se arriesga.
    if (!wd && want) {
      const mine = new Set(norm(base(r.canonical || stem)).split(" "));
      // Lo que distingue a un juego de su secuela —numeros, romanos, letras sueltas como la X— tiene
      // que coincidir entero: «Metal Slug X» se parecia un 0,63 a «Metal Slug 2» y pasaba.
      const marks = (words) => [...words].filter((w) => /^\d+$/.test(w) || w.length === 1).sort().join(" ");
      const myMarks = marks(mine);
      let best = null, bestScore = 0;
      for (const key of P.wd) {
        for (const [t, set] of once(`wd:${key}`, () => loadWd(key)).byTitle) {
          const theirs = t.split(" ");
          if (marks(theirs) !== myMarks) continue;
          const common = theirs.filter((w) => mine.has(w)).length;
          const score = common / (mine.size + theirs.length - common);
          if (score > bestScore) for (const c of set) if (Math.abs((c.year || 0) - want) <= 1) { best = c; bestScore = score; }
        }
      }
      if (best && bestScore >= 0.6) { wd = best; r.wdHow = `parecido ${bestScore.toFixed(2)} + año`; }
    }
    // Ultimo: el titulo exacto en cualquier otra plataforma. El genero no cambia de consola, y hay
    // juegos que Wikidata solo apunta en una: Metal Slug X, en PlayStation y no en Neo Geo.
    if (!wd) {
      const prefix = norm(base(r.canonical || stem).split(/ - |: /)[0]);
      for (const key of Object.keys(PLATFORM_WD)) {
        if (wd || P.wd.includes(key)) continue;
        const W = once(`wd:${key}`, () => loadWd(key));
        for (const t of [...titles, prefix]) {
          const set = W.byTitle.get(t);
          if (set && set.size) { wd = choose(set); r.wdHow = `titulo en otra plataforma (${key})`; break; }
        }
      }
    }
    if (wd) r.sources.wikidata = { qid: wd.qid, label: wd.label, how: r.wdHow, genre: wd.genres.map((g) => g.label).join(", "), dev: wd.devs.join(", "), pub: wd.pubs.join(", "), year: wd.year, series: wd.series.join(", ") };
  }

  // --- elegir
  const S = r.sources;
  const g = {
    wikidata: canon((S.wikidata?.genre || "").split(",")),
    gametdb: canon((S.gametdb?.genre || "").split(",")),
    libretro: canon([S.libretro?.genre || ""]),
    steam: canon((S.steam?.genre || "").split(",")),
  };
  r.genresRaw = g;
  r.genre = [...new Set([...g.wikidata, ...g.gametdb, ...g.libretro, ...g.steam])];
  r.genreFrom = ["wikidata", "gametdb", "libretro", "steam"].filter((k) => g[k].length);
  r.horror = r.genre.includes("Horror");
  r.year = S.libretro?.year || S.gametdb?.year || S.steam?.year || S.wikidata?.year || "";
  r.dev = S.libretro?.dev || S.gametdb?.dev || S.steam?.dev || S.wikidata?.dev || "";
  r.pub = S.libretro?.pub || S.gametdb?.pub || S.steam?.pub || S.wikidata?.pub || "";
  r.series = S.wikidata?.series || S.libretro?.franchise || "";

  // --- arte y video: de la consola que dice la identidad, no de la carpeta. Los Earthworm Jim de
  // la carpeta de 32X son de Mega Drive, y sus videos estan en la coleccion de Mega Drive.
  const sysInfo = Object.values(SYS).find((s) => s.raName === entryRa) || SYS[folder] || {};
  const artRa = entryRa || sysInfo.raName;
  const listFor = (folderName) => once(`art:${artRa}:${folderName}`, () => loadList(`art/${artRa}.${folderName}.json`));
  const byTitleFor = (list) => once(`artT:${artRa}:${list.length}:${list[0]}`, () => {
    const m = new Map(); for (const n of list) { const k = norm(base(n)); if (!m.has(k)) m.set(k, []); m.get(k).push(n); } return m;
  });
  const findArt = (folderName) => {
    const list = listFor(folderName); if (!list.length) return { exact: "", title: "", file: "" };
    const set = once(`artS:${artRa}:${folderName}`, () => new Set(list));
    let exact = "";
    if (r.canonical && set.has(thumbName(r.canonical))) exact = thumbName(r.canonical);
    const bt = byTitleFor(list);
    const pickRegion = (cands) => cands && (cands.find((n) => regionOf(n) === regionOf(r.canonical || stem)) || cands[0]);
    const byFile = pickRegion(bt.get(norm(base(stem)))) || "";
    // En arcade, solo el nombre exacto: por titulo, el «Metal Slug 6» de las miniaturas de MAME es
    // un pirata del 3, y el arte seria de otro juego.
    if (folder === "mame") return { exact, title: "", file: byFile };
    const title = pickRegion(bt.get(norm(base(r.canonical || stem))))
      || [...r.siblings, S.wikidata?.label].filter(Boolean).map((s) => pickRegion(bt.get(norm(base(s))))).find(Boolean) || "";
    return { exact, title, file: byFile };
  };
  r.art = { box: findArt("Named_Boxarts"), snap: findArt("Named_Snaps"), title: findArt("Named_Titles") };

  const vid = sysInfo.videoSnaps ? once(`vid:${sysInfo.videoSnaps}`, () => loadList(`art/video.${sysInfo.videoSnaps}.json`).map((n) => n.replace(/\.mp4$/i, ""))) : [];
  {
    const set = once(`vidS:${sysInfo.videoSnaps}`, () => new Set(vid));
    const bt = once(`vidT:${sysInfo.videoSnaps}`, () => { const m = new Map(); for (const n of vid) { const k = norm(base(n)); if (!m.has(k)) m.set(k, []); m.get(k).push(n); } return m; });
    const key = folder === "mame" ? stem : r.canonical;
    const exact = key && set.has(key) ? key : "";
    // El titulo que confirma Wikidata sirve de puente: el video de «Resident Evil 2 - Dual Shock
    // Ver.» se llama «Resident Evil 2 (USA)».
    const cands = bt.get(norm(base(r.canonical || stem)))
      || [...r.siblings, S.wikidata?.label].filter(Boolean).map((s) => bt.get(norm(base(s)))).find(Boolean) || [];
    const title = folder === "mame" ? "" : (cands.find((n) => regionOf(n) === regionOf(r.canonical || stem)) || cands[0] || "");
    const byFile = (bt.get(norm(base(stem))) || [])[0] || "";
    r.video = { exact, title, file: byFile, collection: sysInfo.videoSnaps || "" };
  }
  r.gametdbId = gt?.id || "";
  r.steamApp = steamApp || "";
  rows.push(r);
}

// --------------------------------------------------------------------------- arte por ID (GameTDB, Steam)

async function head(url) {
  try { const res = await fetch(url, { method: "HEAD", headers: { "User-Agent": "LudologResearch/0.1" } }); return res.ok; } catch { return false; }
}
for (const r of rows) {
  r.idArt = "";
  if (r.gametdbId) {
    const plat = r.folder === "ps3" ? "ps3" : r.folder === "switch" ? "switch" : "wii";
    for (const reg of ["US", "EN", "ES", "JA"]) {
      for (const extn of ["png", "jpg"]) {
        const u = `https://art.gametdb.com/${plat}/cover/${reg}/${r.gametdbId}.${extn}`;
        if (await head(u)) { r.idArt = u; break; }
      }
      if (r.idArt) break;
    }
  }
  if (r.steamApp) {
    const u = `https://cdn.cloudflare.steamstatic.com/steam/apps/${r.steamApp}/library_600x900.jpg`;
    if (await head(u)) r.idArt = u;
  }
}

// --------------------------------------------------------------------------- informe

fs.writeFileSync("report.json", JSON.stringify(rows, null, 1));
const cols = ["folder", "file", "idMethod", "canonical", "serial", "genre", "genreFrom", "horror", "year", "dev", "pub", "series", "box.exact", "box.title", "box.file", "snap.exact", "title.exact", "video.exact", "video.title", "video.file", "idArt"];
const val = (r, c) => {
  if (c.startsWith("box.")) return r.art?.box?.[c.slice(4)] ?? "";
  if (c.startsWith("snap.")) return r.art?.snap?.[c.slice(5)] ?? "";
  if (c.startsWith("title.")) return r.art?.title?.[c.slice(6)] ?? "";
  if (c.startsWith("video.")) return r.video?.[c.slice(6)] ?? "";
  const v = r[c]; return Array.isArray(v) ? v.join(", ") : (v ?? "");
};
fs.writeFileSync("report.tsv", [cols.join("\t"), ...rows.map((r) => cols.map((c) => String(val(r, c))).join("\t"))].join("\n") + "\n");

const pct = (n, d) => d ? `${Math.round((100 * n) / d)}%` : "-";
const groups = {};
for (const r of rows) (groups[r.folder] ??= []).push(r);
const line = (name, list) => {
  const games = list.filter((r) => r.folder !== "pico8");
  const n = games.length;
  const exactId = games.filter((r) => r.exact).length;
  const anyId = games.filter((r) => r.canonical).length;
  const genre = games.filter((r) => r.genre.length).length;
  const year = games.filter((r) => r.year).length;
  const dev = games.filter((r) => r.dev).length;
  const box = games.filter((r) => r.art.box.exact || r.art.box.title || r.idArt).length;
  const boxFile = games.filter((r) => r.art.box.file).length;
  const vid = games.filter((r) => r.video.exact || r.video.title).length;
  const vidFile = games.filter((r) => r.video.file).length;
  return [name.padEnd(8), String(n).padStart(3), pct(exactId, n).padStart(5), pct(anyId, n).padStart(5), pct(genre, n).padStart(5), pct(year, n).padStart(5), pct(dev, n).padStart(5),
    `${pct(boxFile, n)}->${pct(box, n)}`.padStart(10), `${pct(vidFile, n)}->${pct(vid, n)}`.padStart(10)].join(" ");
};
console.log("consola   n  idExacto  id  genero  año  desarr  caratula(fichero->ahora)  video(fichero->ahora)");
for (const [k, list] of Object.entries(groups)) console.log(line(k, list));
console.log(line("TOTAL", rows));
console.log("\nterror detectado:", rows.filter((r) => r.horror).map((r) => r.canonical || r.file).join(" | "));
