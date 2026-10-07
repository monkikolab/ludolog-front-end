// Casos de prueba de `norm`: la app tiene que reducir cada titulo exactamente igual que el
// generador, o no encontrara por titulo lo que el catalogo si tiene. Escribe los casos en las
// pruebas de la app (GameDbTest los lee y los compara con GameDb.norm).
import fs from "node:fs";
import { norm, base } from "./lib.mjs";

const CASES = [
  "Pokémon - Emerald Version (USA, Europe)", "Pokemon - Version Emeraude (France)", "Metal Slug X",
  "Metal Slug X - Super Vehicle-001 (NGM-2500 ~ NGH-2500)", "Final Fantasy VII (USA) (Disc 1)",
  "Street Fighter II' - Champion Edition", "The Legend of Zelda: A Link to the Past",
  "Tom & Jerry (USA)", "Castlevania: Symphony of the Night", "Mega Man X", "Chrono Trigger™",
  "Super Mario Bros. 3", "Ōkami", "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 1)", "SaGa Frontier II",
  "Final Fantasy IX", "Rock n' Roll Racing", "A Boy and His Blob - Trouble on Blobolonia",
  "An American Tail - Fievel Goes West", "Dragon Quest VIII: Journey of the Cursed King",
  "Kingdom Hearts III", "Terranigma (Europe) (En,Fr,De,Es)", "Earthworm Jim (USA)",
  "Silent Hill 2 - Director's Cut (Europe) (En,Fr,De,Es,It)", "007 - The World Is Not Enough",
  "Ys I & II", "Tony Hawk's Pro Skater 2", "F-Zero X", "Mario Kart: Double Dash!!", "Donkey Kong Country 2 - Diddy's Kong Quest",
  "Æon Flux", "Crash Bandicoot - Warped", "Mortal Kombat Trilogy", "The Legend of Zelda - Ocarina of Time [T-Es]",
  "Grand Theft Auto: Vice City Stories", "Ratchet & Clank: Up Your Arsenal", "Brütal Legend", "Pokémon Mystery Dungeon: Explorers of Sky",
];
const lines = CASES.map((c) => `${c}\t${norm(base(c))}\t${norm(c)}`);
const out = new URL("../../app/src/test/resources/norm-cases.tsv", import.meta.url);
fs.mkdirSync(new URL(".", out), { recursive: true });
fs.writeFileSync(out, "# titulo\tnorm(base(titulo))\tnorm(titulo)\n" + lines.join("\n") + "\n");
console.log(`${lines.length} casos`);
