// Genera el catalogo: un paquete por consola con lo que el metagame necesita de cada juego, y
// otro con las sinopsis. Todo ya cruzado aqui, para que la app solo tenga que buscar.
//
// Entrada: data/ldb (fetch-libretro.mjs), data/wd (fetch-wikidata.mjs), data/wp
// (fetch-wikipedia.mjs). Salida, en out/:
//
//   <paquete>.tsv.gz        una fila por juego: claves de identidad, nombre y datos
//   <paquete>.text.tsv.gz   la sinopsis de cada juego que la tenga, por su QID
//   index.tsv               que hay, para que consolas, cuanto pesa y su huella
//   NOTICE.md               de donde sale cada cosa y con que licencia
//
// Las claves de una fila, separadas por espacios, son las mismas que calcula la app al leer el
// fichero (GameId.kt):
//   c:<crc32>          cartuchos y ficheros sueltos
//   h:<sha1, 16 hex>   la imagen ISO entera; en un CHD de DVD es la que lleva la cabecera
//   s:<serial>         discos: el de SYSTEM.CNF, UMD_DATA.BIN o PARAM.SFO, sin guiones
//   i:<codigo>         GameCube y Wii: las cuatro primeras letras del ID del disco
//   r:<romset>         arcade: el nombre del zip
// Una fila sin claves es un juego que solo se encuentra por el titulo.
import fs from "node:fs";
import zlib from "node:zlib";
import crypto from "node:crypto";
import { norm, base, serialKey, parseDat, readJson, regionOf } from "./lib.mjs";
import { WD } from "./platforms.mjs";

// La version es el momento en que se genero, en UTC y hasta el minuto: con solo el dia, dos
// catalogos del mismo dia parecian el mismo y las consolas no volvian a llenar sus fichas.
const VERSION = new Date().toISOString().slice(0, 16).replace("T", ".").replace(":", "");
const L = "data/ldb";
fs.mkdirSync("out", { recursive: true });

const toml = fs.readFileSync(new URL("../../app/src/main/assets/systems.toml", import.meta.url), "utf8");
const field = (b, k) => (b.match(new RegExp(`(?:^|\\n)${k} *= *"([^"]*)"`)) || [])[1] || "";
const SYSTEMS = toml.split("\n[[system]]").slice(1).map((b) => ({ id: field(b, "id"), ra: field(b, "raName") })).filter((s) => s.id);

// Que otras consolas miran tambien un paquete. Los Earthworm Jim de la carpeta de 32X son de Mega
// Drive, un juego de Game Boy suele ir en la carpeta de Color, un disco de GameCube en la de Wii,
// y los romsets de Neo Geo y Atomiswave estan en el de arcade.
const ALSO = {
  megadrive: ["sega32x"], sega32x: ["megadrive"], gb: ["gbc"], gbc: ["gb"],
  gamecube: ["wii"], wii: ["gamecube"], arcade: ["neogeo", "atomiswave"],
};
const DISC = new Set(["psx", "ps2", "psp", "segacd", "saturn", "dreamcast", "gamecube", "wii", "wiiu", "pcenginecd",
  "xbox", "xbox360", "ps3", "3do", "neogeocd", "pcfx", "pc98", "cdi"]);
// Donde el CRC del fichero no dice que juego es: un DOS es una carpeta, un ScummVM un texto de
// siete bytes.
const NO_HASH = new Set(["dos", "scummvm", "rpgmaker", "quake", "quake2"]);
const SHA1 = new Set(["ps2", "psp"]);
const GAMECODE = new Set(["gamecube", "wii"]);
// Las que numeran sus juegos con un codigo de cuatro letras en el que las tres primeras son el
// juego y la ultima la region: «AGB-BPEE-USA», «AGB-BPES-ESP».
const FAMILY_CODE = new Set(["gb", "gbc", "gba", "nds", "3ds", "n64", "snes", "virtualboy"]);

// --------------------------------------------------------------------------- Wikidata

const wdCache = new Map();
function wdPlatform(q) {
  if (!wdCache.has(q)) {
    const games = readJson(`data/wd/${q}.json`, {});
    for (const [qid, g] of Object.entries(games)) g.qid = qid;
    wdCache.set(q, games);
  }
  return wdCache.get(q);
}
function titleIndex(qids) {
  const byTitle = new Map();
  for (const q of qids) {
    for (const g of Object.values(wdPlatform(q))) {
      for (const t of [g.label, ...(g.aliases || [])].filter(Boolean)) {
        const k = norm(t);
        if (!k) continue;
        if (!byTitle.has(k)) byTitle.set(k, new Set());
        byTitle.get(k).add(g);
      }
    }
  }
  return byTitle;
}
const GLOBAL = titleIndex([...new Set(Object.values(WD).flat())]);
const yearOf = (g) => Number((g.date || "").slice(0, 4)) || 0;

/** Entre varios con el mismo titulo: el del año mas cercano, y si no el que mas generos trae. */
function choose(set, want) {
  return [...set].sort((a, b) =>
    (want ? Math.abs(yearOf(a) - want) - Math.abs(yearOf(b) - want) : 0) || (b.genres.length - a.genres.length))[0];
}

// Lo que distingue a un juego de su secuela —numeros, romanos, letras sueltas como la X— tiene que
// coincidir entero: «Metal Slug X» se parecia un 0,63 a «Metal Slug 2» y pasaba.
const marks = (words) => [...words].filter((w) => /^\d+$/.test(w) || w.length === 1).sort().join(" ");

function matchWd(entry, idx, tokens) {
  const t = norm(base(entry.name));
  const want = entry.year;
  const set = idx.get(t);
  if (set?.size) return { g: choose(set, want), how: "t" };
  const prefix = norm(base(entry.name).split(/ - |: /)[0]);
  // Lo de antes del subtitulo, solo si el año cuadra: «Mortal Kombat - Shaolin Monks» no es
  // «Mortal Kombat».
  if (want && prefix !== t) {
    const c = idx.get(prefix)?.size && choose(idx.get(prefix), want);
    if (c && Math.abs(yearOf(c) - want) <= 1) return { g: c, how: "p" };
  }
  // El parecido por palabras, con el año como aval. Sin año no se arriesga.
  if (want) {
    const mine = new Set(t.split(" "));
    const myMarks = marks(mine);
    let best = null, bestScore = 0;
    for (const [k, theirs, gs] of tokens) {
      if (theirs.marks !== myMarks) continue;
      let common = 0;
      for (const w of theirs.words) if (mine.has(w)) common++;
      const score = common / (mine.size + theirs.words.length - common);
      if (score <= bestScore || score < 0.6) continue;
      for (const c of gs) if (Math.abs(yearOf(c) - want) <= 1) { best = c; bestScore = score; }
    }
    if (best) return { g: best, how: "f" };
  }
  // El titulo exacto en cualquier otra plataforma: el genero no cambia de consola, y hay juegos
  // que Wikidata solo apunta en una (Metal Slug X, en PlayStation y no en Neo Geo). Con el año,
  // si se sabe, dentro de dos.
  const other = GLOBAL.get(t);
  if (other?.size) {
    const c = choose(other, want);
    if (!want || !yearOf(c) || Math.abs(yearOf(c) - want) <= 2) return { g: c, how: "x" };
  }
  // Y lo de antes del subtitulo en otra plataforma, solo con el año como aval: «Metal Slug X -
  // Super Vehicle-001» de FBNeo es el «Metal Slug X» que Wikidata apunta como de PlayStation.
  if (want && prefix !== t) {
    const set = GLOBAL.get(prefix);
    const c = set?.size && choose(set, want);
    if (c && yearOf(c) && Math.abs(yearOf(c) - want) <= 1) return { g: c, how: "x" };
  }
  return null;
}

// --------------------------------------------------------------------------- libretro

const META_FIELDS = ["genre", "developer", "publisher", "releaseyear", "releasemonth", "franchise"];
function loadMeta(ra) {
  const meta = { byCrc: new Map(), bySerial: new Map(), byName: new Map() };
  const put = (m, k, f, v) => { if (!k || !v) return; if (!m.has(k)) m.set(k, {}); m.get(k)[f] ??= v; };
  for (const fld of [...META_FIELDS, "serial"]) {
    for (const g of parseDat(`${L}/${fld}/${ra}.dat`)) {
      const name = g.comment || g.name;
      for (const f of META_FIELDS) {
        const v = g[f];
        if (!v) continue;
        put(meta.byName, name, f, v);
        put(meta.bySerial, serialKey(g.serial), f, v);
        for (const r of g.roms) { put(meta.byCrc, r.crc, f, v); put(meta.bySerial, serialKey(r.serial), f, v); }
      }
      if (fld === "serial" && g.serial) put(meta.byName, name, "serial", g.serial);
    }
  }
  return meta;
}

const serialsOf = (g) => [g.serial, ...g.roms.map((r) => r.serial)].filter(Boolean)
  .flatMap((s) => String(s).split(/\s*[,/]\s*/)).map((s) => s.trim()).filter(Boolean);

/** Las entradas de identidad de una consola, con sus claves y lo que ellas mismas traen. */
function identity(sys) {
  const entries = [];
  const seen = new Set();
  const push = (name, keys, g, meta) => {
    if (!name || /\[BIOS\]|\(BIOS\)/i.test(name)) return;
    const id = name + "|" + [...keys].sort().join(" ");
    if (seen.has(id)) return;
    seen.add(id);
    entries.push({ name, keys, g, meta });
  };
  if (sys.id === "arcade") {
    // FBNeo primero y MAME despues: el mismo romset en los dos se queda con el de FBNeo, que es
    // el que usan estas consolas.
    const romsets = new Set();
    for (const [file, src] of [[`${L}/fbneo-split/FBNeo - Arcade Games.dat`, "fbneo"], [`${L}/arcade/MAME.dat`, "mame"]]) {
      for (const g of parseDat(file)) {
        const set = (g.roms[0]?.name || "").replace(/\.zip$/i, "").toLowerCase();
        if (!set || romsets.has(set)) continue;
        romsets.add(set);
        push(g.name, new Set([`r:${set}`]), g, {
          releaseyear: g.releaseyear || g.year, developer: g.developer || g.manufacturer, publisher: g.publisher,
        });
      }
    }
    return entries;
  }
  const meta = sys.ra ? loadMeta(sys.ra) : null;
  const lr = (g) => {
    const name = g.name || g.comment;
    const m = { ...(meta?.byName.get(name) || {}) };
    for (const s of serialsOf(g)) Object.assign(m, { ...(meta?.bySerial.get(serialKey(s)) || {}), ...m });
    for (const r of g.roms) Object.assign(m, { ...(meta?.byCrc.get(r.crc) || {}), ...m });
    // Los dat/ propios de libretro traen los campos dentro de la entrada.
    for (const [to, from] of [["genre", "genre"], ["developer", "developer"], ["developer", "manufacturer"],
      ["publisher", "publisher"], ["releaseyear", "releaseyear"], ["releaseyear", "year"],
      ["releasemonth", "releasemonth"], ["franchise", "franchise"]]) m[to] ??= g[from];
    if (!m.serial && g.serial) m.serial = g.serial;
    return m;
  };
  const keysFor = (g, from) => {
    const k = new Set();
    if (DISC.has(sys.id)) {
      for (const s of serialsOf(g)) {
        if (GAMECODE.has(sys.id)) {
          const code = (s.match(/(?:DOL|RVL)-([A-Z0-9]{4})/) || [])[1] || (/^[A-Z0-9]{6}$/.test(s) ? s.slice(0, 4) : null);
          if (code) k.add(`i:${code}`);
        } else {
          k.add(`s:${serialKey(s)}`);
        }
      }
      if (SHA1.has(sys.id) && g.roms.length === 1 && /\.iso$/i.test(g.roms[0].name || "") && g.roms[0].sha1) {
        k.add(`h:${g.roms[0].sha1.slice(0, 16)}`);
      }
    } else if (sys.id === "atomiswave" && g.id) {
      k.add(`r:${g.id.toLowerCase()}`);
    } else if (!NO_HASH.has(sys.id)) {
      for (const r of g.roms) if (r.crc && /^[0-9a-f]{8}$/.test(r.crc)) k.add(`c:${r.crc}`);
      // Los de PSN en el No-Intro de PSP se buscan por su serial.
      if (sys.id === "psp") for (const s of serialsOf(g)) k.add(`s:${serialKey(s)}`);
      // DS y 3DS: el codigo de juego de cuatro letras, que la app lee de la cabecera. Un cartucho
      // de DS pasa de los 128 MB que se leen enteros, y el CRC no llega a calcularse.
      if (sys.id === "nds" || sys.id === "3ds") {
        for (const s of serialsOf(g)) {
          const code = (s.match(/^(?:[A-Z]{3}-[A-Z]-)?([A-Z0-9]{4})$/) || [])[1];
          if (code) k.add(`g:${code}`);
        }
      }
    }
    return k;
  };
  for (const folder of ["no-intro", "redump", "dat"]) {
    for (const g of parseDat(`${L}/${folder}/${sys.ra}.dat`)) push(g.name || g.comment, keysFor(g, folder), g, lr(g));
  }
  return entries;
}

// --------------------------------------------------------------------------- GameTDB

// De que fichero de GameTDB sale cada consola y que tipos valen. Los «VC» son juegos de otras
// consolas vendidos para esta, y «Homebrew» y «CUSTOM» no son juegos comerciales.
// Su FAQ permite usar la base en programas enlazandoles y avisandoles: va en NOTICE.md.
const GT = {
  gamecube: ["wiitdb", (t) => t === "GameCube"],
  wii: ["wiitdb", (t) => !t || t === "WiiWare" || t === "Channel"],
  wiiu: ["wiiutdb", (t) => t === "WiiU" || t === "eShop"],
  nds: ["dstdb", (t) => ["DS", "DSi", "DSiWare"].includes(t)],
  "3ds": ["3dstdb", (t) => ["3DS", "3DSWare", "New3DS"].includes(t)],
  ps3: ["ps3tdb", () => true],
  switch: ["switchtdb", () => true],
};
const unxml = (s) => String(s || "").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"")
  .replace(/&apos;/g, "'").replace(/&amp;/g, "&");
const gtCache = new Map();
/** Los juegos de un fichero de GameTDB: ID, titulo y sinopsis en ingles, y sus datos. */
function gametdb(file) {
  if (gtCache.has(file)) return gtCache.get(file);
  const out = [];
  const f = `data/gtdb/${file}.xml`;
  if (fs.existsSync(f)) {
    for (const block of fs.readFileSync(f, "utf8").split("<game ").slice(1)) {
      const tag = (t) => unxml(block.match(new RegExp(`<${t}>([^<]*)</${t}>`))?.[1]).trim();
      const en = block.match(/<locale lang="EN">([\s\S]*?)<\/locale>/)?.[1] || "";
      const id = tag("id");
      const title = unxml(en.match(/<title>([^<]*)<\/title>/)?.[1] || block.match(/<title>([^<]*)<\/title>/)?.[1]).trim();
      if (!id || !title) continue;
      const d = block.match(/<date year="(\d*)" month="(\d*)" day="(\d*)"/);
      const date = d?.[1] ? d[1] + (d[2] ? "-" + d[2].padStart(2, "0") + (d[3] ? "-" + d[3].padStart(2, "0") : "") : "") : "";
      out.push({
        id, type: tag("type"), title, date, genre: tag("genre"), developer: tag("developer"), publisher: tag("publisher"),
        synopsis: unxml(en.match(/<synopsis>([^<]*)<\/synopsis>/)?.[1]).trim(),
      });
    }
  }
  gtCache.set(file, out);
  return out;
}
/** La clave con que un juego de GameTDB se une a su entrada: la misma que calcula la app. */
function gtKey(sysId, id) {
  if (sysId === "gamecube" || sysId === "wii") return `i:${id.slice(0, 4)}`;
  if (sysId === "nds" || sysId === "3ds") return `g:${id.slice(0, 4)}`;
  if (sysId === "ps3" || sysId === "wiiu") return `s:${serialKey(id)}`;
  return null;
}

// --------------------------------------------------------------------------- una consola

const clean = (s) => String(s ?? "").replace(/[\t\r\n]+/g, " ").trim();
const joinU = (list) => [...new Set(list.filter(Boolean).map(clean))].join("|");

function familyOf(sys, e) {
  const out = [`t:${norm(base(e.name))}${(e.name.match(/\(Disc (\d+)\)/i) || [])[1] ? " disc" + e.name.match(/\(Disc (\d+)\)/i)[1] : ""}`];
  if (FAMILY_CODE.has(sys.id)) {
    const code = (String(e.meta.serial || "").match(/(?:^|-)([A-Z0-9]{4})(?:-|$)/) || [])[1];
    if (code) out.push(`c:${code.slice(0, 3)}`);
  }
  if (GAMECODE.has(sys.id)) for (const k of e.keys) if (k.startsWith("i:")) out.push(`c:${k.slice(2, 5)}`);
  return out;
}

function buildSystem(sys) {
  const entries = identity(sys);
  const qids = WD[sys.id] || [];
  if (!entries.length && !qids.length) return null;
  const idx = titleIndex(qids);
  const tokens = [...idx].map(([k, gs]) => { const words = k.split(" "); return [k, { words, marks: marks(words) }, gs]; });

  // GameTDB se une a cada entrada por su ID (o por el titulo, en Switch, que no tiene), antes
  // de buscar en Wikidata: su fecha sirve de aval para los parecidos. Lo que no casa con
  // ninguna entrada entra como juego nuevo, con su clave si la tiene.
  const gtSpec = GT[sys.id];
  if (gtSpec) {
    const byKey = new Map();
    const byTitle = new Map();
    for (const e of entries) {
      for (const k of e.keys) if (!byKey.has(k)) byKey.set(k, e);
      const t = norm(base(e.name));
      if (!byTitle.has(t)) byTitle.set(t, e);
    }
    for (const g of gametdb(gtSpec[0])) {
      if (!gtSpec[1](g.type)) continue;
      const key = gtKey(sys.id, g.id);
      const e = key ? byKey.get(key) : byTitle.get(norm(g.title));
      if (e) {
        // Dos del mismo titulo sin clave (Switch): se queda el que se llama asi a secas. Venia
        // antes «Super Mario Odyssey [Starter Pack]», y con el su ID y la caratula del lote.
        if (!key && !e.keys.size && e.gt && g.title.length < e.name.length) { e.name = g.title; e.gt = g; }
        else e.gt ??= g;
        continue;
      }
      const n = { name: g.title, keys: new Set(key ? [key] : []), g: { roms: [] }, meta: {}, gt: g };
      entries.push(n);
      if (key) byKey.set(key, n);
      byTitle.set(norm(g.title), n);
    }
  }

  // El año de cada entrada, para los emparejamientos que lo piden.
  for (const e of entries) e.year = Number(e.meta.releaseyear) || Number((e.gt?.date || "").slice(0, 4)) || 0;
  for (const e of entries) e.wd = matchWd(e, idx, tokens);

  // Las variantes de un mismo juego se prestan lo que sepan: la version española de Pokémon
  // Esmeralda no se encuentra por su titulo, pero comparte codigo con la americana.
  const fams = new Map();
  for (const e of entries) for (const f of familyOf(sys, e)) { if (!fams.has(f)) fams.set(f, []); fams.get(f).push(e); }
  // Los hermanos para el arte: las versiones de otras regiones que se llaman distinto. La
  // española de Pokémon Esmeralda no tiene caratula con su nombre, y la americana si. Primero la
  // americana, luego la europea o mundial, y la japonesa al final. Solo por el codigo del
  // cartucho: en GameCube y Wii el codigo lo comparten tambien las traducciones y los hacks
  // («Metroid Prime: Trilogy - Google Translated»), y ahi la caratula sale por el ID de GameTDB.
  const regionRank = (name) => ({ usa: 0, eur: 1, "": 2, jpn: 3 })[regionOf(name)] ?? 2;
  for (const [fk, members] of fams) {
    if (members.length < 2) continue;
    if (fk.startsWith("c:") && FAMILY_CODE.has(sys.id)) {
      for (const m of members) {
        const own = norm(base(m.name));
        const others = members.filter((x) => x !== m && norm(base(x.name)) !== own)
          .sort((a, b) => regionRank(a.name) - regionRank(b.name)).map((x) => x.name);
        m.alt = [...new Set([...(m.alt || []), ...others])].slice(0, 3);
      }
    }
    const donor = members.find((m) => m.wd && m.wd.how === "t") || members.find((m) => m.wd);
    for (const m of members) {
      if (!m.wd && donor) m.wd = { g: donor.wd.g, how: "v" };
      // El de GameTDB, mejor de una que comparta clave: la europea de God of War III tomaba el ID
      // de la asiatica, que era la primera, y con el su caratula.
      if (!m.gt) {
        const d = members.find((x) => x.gt && [...x.keys].some((k) => m.keys.has(k))) || members.find((x) => x.gt);
        if (d) m.gt = d.gt;
      }
      for (const f of ["genre", "developer", "publisher", "franchise"]) {
        if (!m.meta[f]) { const d = members.find((x) => x.meta[f]); if (d) m.meta[f] = d.meta[f]; }
      }
    }
  }

  const rows = [];
  const used = new Set();
  // Las sinopsis de esta consola, por su clave: el QID si sale de Wikipedia, «gt:<id>» si sale
  // de GameTDB. Wikipedia primero, que tiene la licencia mas clara; GameTDB tapa los huecos.
  const texts = new Map();
  const row = (keys, name, wd, m, gt, alt) => {
    const w = wd?.g;
    if (w) used.add(w.qid);
    const lrDate = m.releaseyear ? String(m.releaseyear).slice(0, 4) + (m.releasemonth ? "-" + String(m.releasemonth).padStart(2, "0") : "") : "";
    // La fecha de Wikidata es la primera salida en cualquier sitio, y muchas veces con dia: es la
    // que sirve para los aniversarios. La de libretro y la de GameTDB son de una region.
    const date = w?.date || lrDate || gt?.date || "";
    const dev = w?.devs?.length ? joinU(w.devs.map((d) => d.label)) : clean(m.developer) || clean(gt?.developer);
    const pub = w?.pubs?.length ? joinU(w.pubs.map((d) => d.label)) : clean(m.publisher) || clean(gt?.publisher);
    const series = w?.series?.length ? joinU(w.series.map((d) => d.label)) : clean(m.franchise);
    const src = [
      w?.date ? "w" : lrDate ? "l" : gt?.date ? "g" : "-",
      w?.devs?.length ? "w" : m.developer ? "l" : gt?.developer ? "g" : "-",
      w?.pubs?.length ? "w" : m.publisher ? "l" : gt?.publisher ? "g" : "-",
      w?.series?.length ? "w" : m.franchise ? "l" : "-",
    ].join("");
    let tx = "";
    const wp = w?.enwiki && synopsis(extracts[w.enwiki]);
    if (wp) { tx = w.qid; texts.set(tx, [w.enwiki, wp, "wp"]); }
    else if (gt?.synopsis) { tx = `gt:${gt.id}`; texts.set(tx, [gt.title, synopsis(gt.synopsis), "gt"]); }
    rows.push([[...keys].join(" "), clean(name), joinU((w?.genres || []).map((g) => g.label)), clean(m.genre),
      clean(gt?.genre).replace(/,/g, "|"), date, dev, pub, series, w?.links || "", w?.qid || "", tx, src,
      wd?.how || "", clean(gt?.id), (alt || []).map(clean).join("|"),
      // El titulo de Wikidata cuando es otro: hace de puente para el arte y el video. «Resident
      // Evil 2 - Dual Shock Ver.» tiene su video como «Resident Evil 2».
      w?.label && norm(w.label) !== norm(base(name)) ? clean(w.label) : "",
      // El titulo ya reducido (norm(base(nombre))), que es por lo que busca la app cuando ni las
      // claves ni el nombre casan. Hecho aqui, una vez: en la consola, reducir los 124.000 del
      // paquete de PC era mas de la mitad de lo que tardaba en abrirlo.
      norm(base(clean(name)))].join("\t"));
  };
  for (const e of entries) row(e.keys, e.name, e.wd, e.meta, e.gt, e.alt);

  // Los que Wikidata conoce y ninguna base de identidad: solo se encuentran por el titulo. En
  // arcade no, que ahi solo vale el nombre exacto del romset.
  if (sys.id !== "arcade") {
    for (const q of qids) {
      for (const g of Object.values(wdPlatform(q))) {
        if (used.has(g.qid) || !g.label || !(g.types || []).includes("Q7889")) continue;
        // En PC y Android la clave es el numero de Steam o el paquete de Google Play: es lo que
        // Ludolog sabe de esos juegos (el fichero `.steam`, la app instalada).
        const keys = new Set([
          ...(sys.id === "pc" ? (g.steam || []).map((s) => `v:${s}`) : []),
          ...(sys.id === "android" ? (g.play || []).map((p) => `a:${p.toLowerCase()}`) : []),
        ]);
        row(keys, g.label, { g, how: keys.size ? "k" : "w" }, {}, null, null);
      }
    }
  }
  return { rows, used, texts };
}

// --------------------------------------------------------------------------- sinopsis

const extracts = readJson("data/wp/extracts.json", {});
/**
 * La entradilla, recortada a lo que cabe en la tarjeta: el primer parrafo, sin la pronunciacion
 * ni el nombre en japones entre parentesis, y como mucho unos 600 caracteres, cortando al final
 * de una frase.
 */
function synopsis(text) {
  let t = String(text || "").split(/\n+/)[0] || "";
  t = t.replace(/\s*\((?:[^()]*?(?:Japanese|Hepburn|Korean|Chinese|lit\.|pronounced|stylized|also known)[^()]*)\)/gi, "");
  t = t.replace(/\s{2,}/g, " ").trim();
  if (t.length > 600) {
    const cut = t.slice(0, 600);
    const end = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "));
    t = end > 150 ? cut.slice(0, end + 1) : cut.replace(/\s+\S*$/, "") + "…";
  }
  return t;
}

// --------------------------------------------------------------------------- salida

const index = [];
const gz = (s) => zlib.gzipSync(Buffer.from(s), { level: 9 });
const sha1 = (b) => crypto.createHash("sha1").update(b).digest("hex");
function write(file, pack, systems, kind, header, lines) {
  const body = gz([header, ...lines].join("\n") + "\n");
  fs.writeFileSync(`out/${file}`, body);
  index.push([file, pack, systems.join(","), kind, body.length, sha1(body), lines.length - 1].join("\t"));
  return body.length;
}

let total = 0, totalRows = 0;
for (const sys of SYSTEMS) {
  const built = buildSystem(sys);
  if (!built || !built.rows.length) continue;
  const systems = [sys.id, ...(ALSO[sys.id] || [])];
  const head = `#ludolog-games\t1\t${sys.id}\t${VERSION}`;
  const cols = "keys\tname\tgenre.wd\tgenre.lr\tgenre.gt\tdate\tdev\tpub\tseries\tfame\tqid\ttx\tsrc\thow\tgt\talt\tlabel\tnt";
  const bytes = write(`${sys.id}.tsv.gz`, sys.id, systems, "games", head, [cols, ...built.rows]);
  const texts = [...built.texts].map(([k, [title, text, from]]) => [k, clean(title), clean(text), from].join("\t"));
  let tb = 0;
  if (texts.length) tb = write(`${sys.id}.text.tsv.gz`, sys.id, systems, "text", `#ludolog-text\t1\t${sys.id}\t${VERSION}`, ["key\ttitle\ttext\tsrc", ...texts]);
  total += bytes + tb; totalRows += built.rows.length;
  console.log(`${sys.id.padEnd(16)} ${String(built.rows.length).padStart(6)} filas ${String(Math.round(bytes / 1024)).padStart(5)} KB  sinopsis ${String(texts.length).padStart(5)} ${String(Math.round(tb / 1024)).padStart(5)} KB`);
}

function findAnywhere(q) {
  for (const p of new Set(Object.values(WD).flat())) { const g = wdPlatform(p)[q]; if (g) return g; }
  return null;
}

fs.writeFileSync("out/index.tsv", [`#ludolog-catalog\t1\t${VERSION}`, "file\tpack\tsystems\tkind\tbytes\tsha1\trows", ...index].join("\n") + "\n");
fs.copyFileSync("NOTICE.md", "out/NOTICE.md");
console.log(`\ntotal: ${totalRows} filas, ${(total / 1048576).toFixed(2)} MB`);
