# The Companion metagame

The Companion keeps a logbook of what you play: each session's game, when it started and how long
it lasted, and what the device did meanwhile (battery, temperature, frame rate, power draw). The
metagame turns that logbook into a character that levels up as you play. Playing is the only way
to earn experience, and playing a bit of everything earns the most.

## What you see

Open the Companion (L2+R2). Its first tab, **OVERVIEW**, is the character:

- a **pentagon** with five axes, which is both your level (the bigger, the higher) and your
  profile (where it stretches shows what you play);
- your **level**, from 1 to 99, a bar to the next one, and your **class**;
- your latest sessions. Pick one and it plays back what it gave you, with the pentagon and the
  bar growing from where they were before it.

The **ACHIEVEMENTS** and **MISSIONS** tabs are described below. When you come back from a game, a
short card in the current theme's style shows what the session gave you, with a jingle if you
levelled up, completed a mission or earned an achievement. Each game's quick menu also has a
**Genre** row, to choose its genre yourself, and a **Completed** row, to mark a game you have beaten.

## Experience

Experience is time played. Sessions shared from your other devices through Ludolog Link count too,
and a session that shows up in two logbooks counts once. The Companion shows experience as one
point per second played. Two adjustments:

- **Rested bonus.** Time away from playing fills a reserve, one minute for every ten away, up to
  an hour. While the reserve lasts, each minute played counts double.
- **Tiredness.** After three hours in a single session, each further minute counts half: an
  emulator left open is not playing.

## The five axes

Each minute played goes to one or two axes, depending on the game's genre:

| axis | genres |
|---|---|
| POWER | fighting, beat 'em up, action |
| REFLEX | platform, shooter, racing, sports, music, party |
| SOUL | RPG, visual novel |
| NERVE | horror, stealth |
| MIND | puzzle, strategy, simulation, adventure |

Hybrid genres split their time half and half: a metroidvania feeds REFLEX and MIND, an action RPG
POWER and SOUL, and so on. When a game has several genres, the most specific one decides. A game
whose genre is unknown gives neutral experience, shared equally among the five.

## Levels and classes

Each axis fills along its own curve, quick at first and slow near the top, and your level is 1 plus
the sum of the five. Level 99 means all five are full: about 730 hours of play (a year at two hours
a day) spread across every kind of game. Someone who plays a single genre rises quickly and then
stalls around level 20.

Your **class** comes from the shape of the pentagon: your two strongest axes, or just one when it
is at least twice the next. That makes five single classes and ten pairs, and each theme names them
its own way (in Gallery, the single classes are Fighter, Ace, Adventurer, Survivor and Thinker).

## Where the genre comes from

Genres come from the game catalog, which gathers them from Wikidata, libretro-database and GameTDB
(see [game info, box art and videos](scraper.md)). They are kept as each source gives them and only
translated into axes when the character is worked out, so changing that translation never needs a
new download. Wikidata is the most detailed source and the only one that reliably knows which games
are horror. A genre you choose in the quick menu wins over the catalog, and Ludolog Link carries it
to your other devices.

## Achievements

Achievements come in groups: growth (levels and axes), time, habits, variety, eras, victories and
the machine. Each one says how it is earned and shows how close you are; earned ones show the date
you reached them. For example:

- **Night Owl:** start 10 sessions between midnight and 5 am.
- **Time Traveler:** play consoles from five different decades.
- **First Victory:** beat a game and mark it as completed.
- **On Fumes:** end a session under 10 % battery, off the charger.

Telemetry only ever gives achievements, never experience: the character rewards playing, not
heating up the device.

## Missions

Missions are small tasks that never name a particular game: play a horror game for 30 minutes,
play a game you have never played before, go back to one you have not touched in a month, and so
on. You pick the ones to track in the **MISSIONS** tab. While you track a mission, the games that
fit it are marked in the list, and the card shown when you launch one reminds you what it asks.
Only play after you start tracking counts. A completed mission gives experience, to its own axis
or neutral, and can be tracked again.

## How it works inside

Everything is worked out from the logbook each time: the character, the achievements and the dates
they were earned are never stored. Past sessions always count, nothing can drift out of sync
between devices, and the curve can be retuned without losing anything. Only a little is stored:
the missions you track and complete, the games marked as completed, your own genre choices, and
what was last shown, so new things can be announced.

The code is in `app/src/main/java/com/felp/frontcomp/`: `Metagame.kt` (experience, axes, levels,
classes), `Achievements.kt`, `Missions.kt`, `Genres.kt` (how each source's genre names map to
Ludolog's), and the tabs in `CharacterTab.kt` and `MetaTabs.kt`. Ludolog Link's PC app compiles
these same files, so a change here also changes the Companion on the PC.
