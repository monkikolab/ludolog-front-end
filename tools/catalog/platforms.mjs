// Que plataformas de Wikidata corresponden a cada consola del catalogo de Ludolog (systems.toml).
// Una consola puede ser varias: la NES y el Famicom Disk System, la DS y la DSi. Sacado de las
// plataformas con mas juegos de Wikidata (tools/metaprobe/wdplatforms.mjs), comprobando cada una.
//
// Arcade va aparte: los Metal Slug no estan como «arcade» sino como Neo Geo (MVS o AES), y hay
// juegos de Atomiswave. Por eso la de arcade junta las cinco.
export const WD = {
  gb: ["Q186437"], gbc: ["Q203992"], gba: ["Q188642"], nes: ["Q172742", "Q135321"], snes: ["Q183259"],
  n64: ["Q184839"], gamecube: ["Q182172"], wii: ["Q8079"], wiiu: ["Q56942"], nds: ["Q170323", "Q637178"],
  "3ds": ["Q203597", "Q17679679"], virtualboy: ["Q164651"], megadrive: ["Q10676"], mastersystem: ["Q209868"],
  gamegear: ["Q751719"], segacd: ["Q1047516"], sega32x: ["Q1063978"], saturn: ["Q200912"], dreamcast: ["Q184198"],
  psx: ["Q10677"], ps2: ["Q10680"], psp: ["Q170325"], psvita: ["Q188808"], switch: ["Q19610114"],
  xbox: ["Q132020"], xbox360: ["Q48263"], ps3: ["Q10683"], pcengine: ["Q1057377"], pcenginecd: ["Q10854461"],
  neogeo: ["Q1054350", "Q3338058", "Q64428080"], ngpc: ["Q1977455"], wonderswan: ["Q1065792"],
  wonderswancolor: ["Q1048035"], atari2600: ["Q206261"], atari5200: ["Q743222"], atari7800: ["Q753600"],
  lynx: ["Q753657"], c64: ["Q99775"], amiga: ["Q100047", "Q471094", "Q695161"],
  arcade: ["Q192851", "Q1054350", "Q3338058", "Q64428080", "Q757617"],
  colecovision: ["Q1046862"], "3do": ["Q229429"], cpc: ["Q478829"], jaguar: ["Q650601"], atarist: ["Q627302"],
  atomiswave: ["Q757617"], bbcmicro: ["Q749976"], pet: ["Q946661"], plus4: ["Q868568", "Q1115913"],
  dos: ["Q170434", "Q47604"], intellivision: ["Q1061441"], j2me: ["Q193828"], odyssey2: ["Q576932"],
  msx: ["Q853547", "Q11232203"], pc88: ["Q1338888"], pc98: ["Q183505"], pcfx: ["Q1136902"],
  neogeocd: ["Q2703883"], cdi: ["Q1023103"], sg1000: ["Q1136956"], x1: ["Q2710884"], x68000: ["Q1758277"],
  vic20: ["Q918232"], zxspectrum: ["Q23882"],
  // No son plataformas sino conjuntos propios (ver SETS en fetch-wikidata.mjs): los juegos de
  // Steam por su numero, los de Google Play por su paquete y los clasicos del motor de Doom.
  pc: ["steam"], android: ["play"], doom: ["doom"],
};

/** Las plataformas de Wikidata sin repetir: cada una se baja una vez aunque la usen dos consolas. */
export const ALL_QIDS = [...new Set(Object.values(WD).flat())].filter((q) => /^Q\d+$/.test(q));
