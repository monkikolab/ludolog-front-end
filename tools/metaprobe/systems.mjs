import fs from "node:fs";
const t = fs.readFileSync(new URL("../../app/src/main/assets/systems.toml", import.meta.url), "utf8");
const blocks = t.split("\n[[system]]").slice(1);
const want = ["psx", "ps2", "snes", "mame", "switch", "gba", "steam", "pico8", "sega32x", "nes", "n64", "wii", "psp", "ps3", "gc", "megadrive"];
const field = (b, k) => (b.match(new RegExp("(?:^|\\n)" + k + " *= *\"([^\"]*)\"")) || [null, ""])[1];
const out = {};
for (const b of blocks) {
  const id = field(b, "id");
  const alias = (b.match(/\naliases *= *\[([^\]]*)\]/) || [null, ""])[1]
    .split(",").map((s) => s.trim().replace(/"/g, "")).filter(Boolean);
  for (const w of want) {
    if (w === id || (alias.includes(w) && !out[w])) {
      out[w] = { id, raName: field(b, "raName"), videoSnaps: field(b, "videoSnaps"), raCore: field(b, "raCore") };
    }
  }
}
fs.writeFileSync("systems.json", JSON.stringify(out, null, 1));
for (const [k, v] of Object.entries(out)) console.log(k.padEnd(9), v.id.padEnd(10), v.raName.padEnd(48), v.videoSnaps);
