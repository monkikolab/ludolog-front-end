package com.felp.frontcomp

import com.felp.frontcomp.Metagame.Vertex
import java.util.Calendar

/**
 * Los logros: se sacan del cuaderno cada vez, como el personaje, sin guardar nada. Cada uno dice
 * como se consigue y cuanto se lleva.
 *
 * La telemetria —bateria, calor, fotogramas, vatios— da logros y no experiencia: la experiencia
 * premia jugar, y no calentar la consola ni gastar bateria.
 *
 * La hora de cada partida es la local de la consola que lo muestra (decidido el 26-09-2026).
 */
internal object Achievements {

    enum class Group(val label: String) {
        GROWTH("GROWTH"), TIME("TIME"), HABITS("HABITS"), VARIETY("VARIETY"), ERAS("ERAS"),
        VICTORY("VICTORY"), MACHINE("MACHINE"),
    }

    /** Todo lo que miran los logros. */
    class Input(
        val plays: List<LogStats.Play>,
        val character: Metagame.Character,
        /** Cuando se marco cada juego terminado. */
        val completions: List<Long>,
        val missionsDone: Int,
        /** El año de cada consola, por su id. */
        val years: Map<String, Int>,
    ) {
        val minutes: Double get() = plays.sumOf { it.session.durationMs } / 60_000.0
        val days: List<Long> by lazy { plays.map { dayOf(it.session.startedAt) }.distinct().sorted() }
    }

    class Achievement(
        val id: String,
        val group: Group,
        val name: String,
        val how: String,
        val target: Double,
        val value: (Input) -> Double,
    )

    /** Uno con lo que lleva: conseguido si llega a su meta, y desde cuando (ver [dated]). */
    class State(val a: Achievement, val value: Double, val earnedAt: Long? = null) {
        val earned: Boolean get() = value >= a.target
        val progress: Double get() = (value / a.target).coerceIn(0.0, 1.0)
    }

    fun of(input: Input): List<State> = ALL.map { State(it, runCatching { it.value(input) }.getOrDefault(0.0)) }

    /**
     * Los mismos, con la fecha en que se consiguio cada uno: el primer momento de la historia en
     * que ya se cumplia. No se guarda en ningun sitio; se busca en el cuaderno, asi que tambien la
     * tienen los logros conseguidos antes de que existiera la fecha.
     *
     * Por biseccion sobre los momentos en que algo cambio —el final de cada partida, cada juego
     * terminado, cada mision cumplida—: los logros solo suben con el tiempo, asi que basta con
     * una docena de pasos aunque haya miles de partidas.
     */
    fun dated(
        states: List<State>,
        input: Input,
        missionsDone: List<Pair<String, Long>>,
        bonuses: (List<Pair<String, Long>>) -> List<Metagame.Bonus>,
    ): List<State> {
        val events = (input.plays.map { it.session.startedAt + it.session.durationMs } + input.completions + missionsDone.map { it.second })
            .distinct().sorted()
        if (events.isEmpty()) return states
        val cache = HashMap<Long, Input>()
        fun at(t: Long): Input = cache.getOrPut(t) {
            val plays = input.plays.filter { it.session.startedAt + it.session.durationMs <= t }
            val missions = missionsDone.filter { it.second <= t }
            Input(
                plays, Metagame.of(plays.map { it.session }, t, bonuses(missions)),
                input.completions.filter { it <= t }, missions.size, input.years,
            )
        }
        return states.map { s ->
            if (!s.earned) return@map s
            var lo = 0
            var hi = events.lastIndex
            while (lo < hi) {
                val mid = (lo + hi) / 2
                val ok = runCatching { s.a.value(at(events[mid])) >= s.a.target }.getOrDefault(false)
                if (ok) hi = mid else lo = mid + 1
            }
            State(s.a, s.value, events[lo])
        }
    }

    // ---------------------------------------------------------------- utiles

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
    private fun hourOf(t: Long) = cal(t).get(Calendar.HOUR_OF_DAY)
    private fun dayOf(t: Long): Long {
        val c = cal(t)
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }
    private fun weekend(t: Long) = cal(t).get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
    private fun min(p: LogStats.Play) = p.session.durationMs / 60_000.0

    /** La racha mas larga de dias seguidos con algo jugado. */
    private fun bestStreak(i: Input): Double {
        val days = i.plays.map { val c = cal(it.session.startedAt); c.set(Calendar.HOUR_OF_DAY, 12); c.timeInMillis / 86_400_000L }
            .distinct().sorted()
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (d in days) { run = if (d == prev + 1) run + 1 else 1; best = maxOf(best, run); prev = d }
        return best.toDouble()
    }

    private fun level(id: String, name: String, n: Int) =
        Achievement(id, Group.GROWTH, name, "Reach level $n.", n.toDouble()) { it.character.level.toDouble() }

    private fun adept(v: Vertex, name: String) =
        Achievement("adept_${v.name.lowercase()}", Group.GROWTH, name, "Reach 5 in ${v.label}.", 5.0) { it.character.vertexLevels[v] ?: 0.0 }

    private fun master(v: Vertex, name: String) =
        Achievement("master_${v.name.lowercase()}", Group.GROWTH, name, "Fill ${v.label} to the top.", Metagame.VERTEX_LEVELS - 0.01) {
            it.character.vertexLevels[v] ?: 0.0
        }

    private fun hours(id: String, name: String, h: Int) =
        Achievement(id, Group.TIME, name, "Play for $h hours in all.", h * 60.0) { it.minutes }

    private fun sessions(id: String, name: String, n: Int) =
        Achievement(id, Group.TIME, name, "Play $n sessions.", n.toDouble()) { it.plays.size.toDouble() }

    private fun streak(id: String, name: String, n: Int) =
        Achievement(id, Group.HABITS, name, "Play on $n days in a row.", n.toDouble(), ::bestStreak)

    val ALL: List<Achievement> = listOf(
        // Crecer: niveles y vertices.
        level("lv5", "First Steps", 5),
        level("lv10", "Apprentice", 10),
        level("lv25", "Journeyman", 25),
        level("lv50", "Halfway There", 50),
        level("lv75", "Elite", 75),
        level("lv99", "Legend", 99),
        adept(Vertex.POWER, "Strong Arm"), adept(Vertex.REFLEX, "Quick Draw"), adept(Vertex.SOUL, "Kindred Spirit"),
        adept(Vertex.NERVE, "Steady Nerves"), adept(Vertex.MIND, "Sharp Mind"),
        master(Vertex.POWER, "Master of POWER"), master(Vertex.REFLEX, "Master of REFLEX"), master(Vertex.SOUL, "Master of SOUL"),
        master(Vertex.NERVE, "Master of NERVE"), master(Vertex.MIND, "Master of MIND"),
        Achievement("balanced", Group.GROWTH, "Well Rounded", "Reach 3 in all five at once.", 3.0) {
            it.character.vertexLevels.values.minOrNull() ?: 0.0
        },

        // Tiempo.
        hours("h1", "Warming Up", 1), hours("h10", "Hooked", 10), hours("h100", "Devoted", 100), hours("h500", "Lifer", 500),
        sessions("s10", "Regular", 10), sessions("s100", "Frequent Flyer", 100), sessions("s1000", "Thousand Nights", 1000),

        // Costumbres.
        streak("streak3", "Habit", 3), streak("streak7", "Week Long", 7), streak("streak30", "Unbroken", 30),
        streak("streak100", "Centurion", 100), streak("streak365", "Every Single Day", 365),
        Achievement("owl", Group.HABITS, "Night Owl", "Start 10 sessions between midnight and 5 am.", 10.0) { i ->
            i.plays.count { hourOf(it.session.startedAt) < 5 }.toDouble()
        },
        Achievement("bird", Group.HABITS, "Early Bird", "Start 10 sessions between 5 and 8 am.", 10.0) { i ->
            i.plays.count { hourOf(it.session.startedAt) in 5..7 }.toDouble()
        },
        Achievement("midnight", Group.HABITS, "Midnight Terror", "Play a horror game after midnight, 30 minutes in one night.", 30.0) { i ->
            i.plays.filter { hourOf(it.session.startedAt) < 5 && "Horror" in it.session.genres }
                .groupBy { dayOf(it.session.startedAt) }.maxOfOrNull { (_, ps) -> ps.sumOf(::min) } ?: 0.0
        },
        Achievement("weekend", Group.HABITS, "Weekend Warrior", "Play on 10 weekend days.", 10.0) { i ->
            i.plays.filter { weekend(it.session.startedAt) }.map { dayOf(it.session.startedAt) }.distinct().size.toDouble()
        },
        Achievement("marathon", Group.HABITS, "Marathon", "Play a single session of three hours.", 180.0) { i ->
            i.plays.maxOfOrNull(::min) ?: 0.0
        },
        Achievement("rested", Group.HABITS, "Well Rested", "Spend a full hour of rested bonus in one session.", 60.0) { i ->
            i.character.gains.values.maxOfOrNull { it.rested } ?: 0.0
        },

        // Variedad.
        Achievement("consoles", Group.VARIETY, "Collector", "Play games from 10 different consoles.", 10.0) { i ->
            i.plays.map { it.system }.distinct().size.toDouble()
        },
        Achievement("games", Group.VARIETY, "Curator", "Play 25 different games.", 25.0) { i ->
            i.plays.map { it.system to it.file }.distinct().size.toDouble()
        },
        Achievement("genres", Group.VARIETY, "Genre Explorer", "Play games of 10 different genres.", 10.0) { i ->
            i.plays.mapNotNull { it.session.genres.firstOrNull() }.distinct().size.toDouble()
        },
        Achievement("devices", Group.VARIETY, "Two Consoles, One Soul", "Play on two different handhelds.", 2.0) { i ->
            i.plays.map { it.device }.distinct().size.toDouble()
        },
        Achievement("emulators", Group.VARIETY, "Polyglot", "Play through 5 different emulators.", 5.0) { i ->
            i.plays.mapNotNull { it.emulator }.distinct().size.toDouble()
        },

        // Epocas, por el año de la consola.
        Achievement("arch", Group.ERAS, "Archaeologist", "Play a console released before 1985.", 1.0) { i ->
            i.plays.count { (i.years[it.system] ?: 0) in 1..1984 }.toDouble().coerceAtMost(1.0)
        },
        Achievement("decades", Group.ERAS, "Time Traveler", "Play consoles from five different decades.", 5.0) { i ->
            i.plays.mapNotNull { i.years[it.system]?.takeIf { y -> y > 0 }?.div(10) }.distinct().size.toDouble()
        },
        Achievement("bits16", Group.ERAS, "16-Bit Veteran", "Play 5 hours on Super Nintendo or Mega Drive.", 300.0) { i ->
            i.plays.filter { it.system in setOf("snes", "megadrive", "sfc", "genesis") }.sumOf(::min)
        },
        Achievement("modern", Group.ERAS, "Modern Times", "Play a console released in 2015 or later.", 1.0) { i ->
            i.plays.count { (i.years[it.system] ?: 0) >= 2015 }.toDouble().coerceAtMost(1.0)
        },

        // Victorias: juegos terminados y misiones.
        Achievement("done1", Group.VICTORY, "First Victory", "Beat a game and mark it as completed.", 1.0) { it.completions.size.toDouble() },
        Achievement("done10", Group.VICTORY, "Boss Slayer", "Mark 10 games as completed.", 10.0) { it.completions.size.toDouble() },
        Achievement("done25", Group.VICTORY, "Completionist", "Mark 25 games as completed.", 25.0) { it.completions.size.toDouble() },
        Achievement("quest1", Group.VICTORY, "Errand Runner", "Complete a mission.", 1.0) { it.missionsDone.toDouble() },
        Achievement("quest10", Group.VICTORY, "Questmaster", "Complete 10 missions.", 10.0) { it.missionsDone.toDouble() },

        // La maquina: lo que dice la telemetria.
        Achievement("hot", Group.MACHINE, "Red Hot", "Push the CPU to 90 °C in a session.", 90.0) { i ->
            i.plays.mapNotNull { it.tempMaxC?.toDouble() }.maxOrNull() ?: 0.0
        },
        Achievement("cool", Group.MACHINE, "Cool Head", "Play an hour without the CPU passing 60 °C.", 60.0) { i ->
            i.plays.filter { (it.tempMaxC ?: 99f) < 60f }.maxOfOrNull(::min) ?: 0.0
        },
        Achievement("fumes", Group.MACHINE, "On Fumes", "End a session under 10 % battery, off the charger.", 1.0) { i ->
            i.plays.count { !it.charged && (it.batteryEndPct ?: 100) < 10 }.toDouble().coerceAtMost(1.0)
        },
        Achievement("charge", Group.MACHINE, "One Charge Wonder", "Play two hours in one session, off the charger.", 120.0) { i ->
            i.plays.filter { !it.charged }.maxOfOrNull(::min) ?: 0.0
        },
        Achievement("tethered", Group.MACHINE, "Tethered", "Play 10 sessions on the charger.", 10.0) { i ->
            i.plays.count { it.charged }.toDouble()
        },
        Achievement("silky", Group.MACHINE, "Silky", "Play 30 minutes at a steady 59 frames or more.", 30.0) { i ->
            i.plays.filter { (it.fpsMean ?: 0f) >= 59f }.maxOfOrNull(::min) ?: 0.0
        },
        Achievement("slideshow", Group.MACHINE, "Slideshow Survivor", "Stick with a game for 10 minutes under 20 frames.", 10.0) { i ->
            i.plays.filter { (it.fpsMean ?: 99f) < 20f }.maxOfOrNull(::min) ?: 0.0
        },
        Achievement("eco", Group.MACHINE, "Eco Mode", "Play an hour drawing under 3 watts.", 60.0) { i ->
            i.plays.filter { (it.powerMeanW ?: 99f) < 3f }.maxOfOrNull(::min) ?: 0.0
        },
        Achievement("furnace", Group.MACHINE, "Furnace", "Play a session drawing 8 watts or more on average.", 8.0) { i ->
            i.plays.mapNotNull { it.powerMeanW?.toDouble() }.maxOrNull() ?: 0.0
        },

        // Lo acumulado: toda la energia que los juegos le han sacado a la bateria, y cuantas
        // baterias enteras suman. Una portatil tira unos 5 W, asi que el primero son unas 40 h y
        // el ultimo, unas 600: de los que llegan con el personaje, no en una tarde.
        watts("wh200", "Space Heater", 200.0, "Draw 200 Wh playing: a 100 W heater running for two hours."),
        watts("wh1000", "Kilowatt", 1000.0, "Draw a full kilowatt-hour playing."),
        watts("wh3000", "Power Plant", 3000.0, "Draw 3 kWh playing: a fridge running for a week."),
        cycles("ch10", "Recharged", 10),
        cycles("ch50", "Juice Junkie", 50),
        cycles("ch150", "Battery Eater", 150),
    )

    private fun watts(id: String, name: String, wh: Double, how: String) =
        Achievement(id, Group.MACHINE, name, how, wh) { i -> i.plays.sumOf { it.energyWh } }

    /**
     * Las cargas hechas: los ciclos que ha sumado la bateria de cada consola desde su primera
     * partida apuntada. Un ciclo es la bateria entera cargada, en una vez o en varias a medias.
     *
     * Eran las baterias gastadas JUGANDO, y no se parecian a lo que uno vive: se carga por lo
     * jugado y por todo lo demas —reposo, menus, otras apps—, y el numero apenas se movia.
     */
    private fun cycles(id: String, name: String, n: Int) =
        Achievement(id, Group.MACHINE, name, "Charge your consoles through $n full cycles.", n.toDouble()) { i ->
            i.plays.filter { it.cycles != null }.groupBy { it.device }
                .values.sumOf { ps -> ps.maxOf { it.cycles!! } - ps.minOf { it.cycles!! } }.toDouble()
        }
}
