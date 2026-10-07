// Que trae GameTDB por tipo de juego: cuantos y que parte tiene cada campo, con las sinopsis por
// idioma (ingles y español) y la clasificacion por edades.
import fs from "node:fs";

const stats = {};
for (const f of ["wiitdb", "wiiutdb", "dstdb", "3dstdb", "ps3tdb", "switchtdb"]) {
  const xml = fs.readFileSync(`gtdb/${f}.xml`, "utf8");
  for (const block of xml.split("<game ").slice(1)) {
    const type = block.match(/<type>([^<]*)<\/type>/)?.[1] || "(sin tipo)";
    const key = `${f.replace("tdb", "")}:${type}`;
    const s = (stats[key] ??= { n: 0, genre: 0, year: 0, dev: 0, pub: 0, players: 0, synEn: 0, synEs: 0, titleEs: 0, rating: 0 });
    s.n++;
    const locale = (lang) => block.match(new RegExp(`<locale lang="${lang}">([\\s\\S]*?)</locale>`))?.[1] || "";
    if (/<genre>[^<]+<\/genre>/.test(block)) s.genre++;
    if (/<date year="\d+/.test(block)) s.year++;
    if (/<developer>[^<]+<\/developer>/.test(block)) s.dev++;
    if (/<publisher>[^<]+<\/publisher>/.test(block)) s.pub++;
    if (/<input players="[1-9]/.test(block)) s.players++;
    if (/<synopsis>[^<]{20,}/.test(locale("EN"))) s.synEn++;
    if (/<synopsis>[^<]{20,}/.test(locale("ES"))) s.synEs++;
    if (/<title>[^<]+/.test(locale("ES"))) s.titleEs++;
    if (/<rating type="[^"]+" value="[^"]+/.test(block)) s.rating++;
  }
}
const pct = (a, b) => `${Math.round((100 * a) / b)}%`;
console.log(["tipo", "juegos", "genero", "año", "desarr", "editor", "jugad", "sinop EN", "sinop ES", "titulo ES", "edad"].join("\t"));
for (const [k, s] of Object.entries(stats).sort((a, b) => b[1].n - a[1].n)) {
  if (s.n < 50) continue;
  console.log([k, s.n, pct(s.genre, s.n), pct(s.year, s.n), pct(s.dev, s.n), pct(s.pub, s.n), pct(s.players, s.n), pct(s.synEn, s.n), pct(s.synEs, s.n), pct(s.titleEs, s.n), pct(s.rating, s.n)].join("\t"));
}
