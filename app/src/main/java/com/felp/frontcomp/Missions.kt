package com.felp.frontcomp

import com.felp.frontcomp.Metagame.Vertex

/**
 * Las misiones: encargos genericos, que no nombran un juego concreto, y que se cumplen jugando.
 *
 * Se siguen a mano desde su pestaña. Mientras se siguen, los juegos que sirven llevan una marca
 * en la lista, y al lanzar uno la tarjeta de encima del emulador recuerda lo que pide. Se cumplen
 * con lo jugado DESDE que se empezaron a seguir, y dan experiencia: a su vertice, o neutra.
 * Se pueden repetir.
 */
internal object Missions {

    /** Un juego de la biblioteca, visto por las misiones: para saber si sirve. */
    data class Candidate(
        val system: String,
        /** Nombres propios de Genres. */
        val genres: List<String>,
        /** El año de su consola; 0 si no se sabe. */
        val consoleYear: Int,
        val playedMs: Long,
        val lastPlayed: Long?,
        val completed: Boolean,
    )

    /** Lo que hay para comprobar una mision: lo jugado desde que se empezo y lo de antes. */
    class Check(
        val since: List<LogStats.Play>,
        val before: List<LogStats.Play>,
        val completions: List<Long>,
        val startedAt: Long,
        val years: Map<String, Int>,
    )

    class Mission(
        val id: String,
        val title: String,
        /** Como se cumple: una o dos lineas, que es lo que cabe en la tarjeta. */
        val how: String,
        val vertex: Vertex?,
        val reward: Int,
        val eligible: (Candidate) -> Boolean,
        /** Lo que lleva, de 0 a 1. */
        val progress: (Check) -> Double,
    )

    private fun minutes(p: LogStats.Play) = p.session.durationMs / 60_000.0
    private fun toward(p: LogStats.Play, v: Vertex) = minutes(p) * (Metagame.split(p.session.genres)[v] ?: 0.0)
    private fun of(v: Vertex, genres: List<String>) = (Metagame.split(genres)[v] ?: 0.0) >= 0.5

    private val HANDHELDS = setOf(
        "gb", "gbc", "gba", "nds", "3ds", "psp", "psvita", "gamegear", "lynx", "ngpc", "wonderswan",
        "wonderswancolor", "virtualboy", "switch",
    )

    private fun vertexMission(id: String, title: String, v: Vertex, what: String, need: Double) = Mission(
        id, title, "Play $what for ${need.toInt()} minutes, in one go or several.", v, need.toInt(),
        eligible = { of(v, it.genres) },
        progress = { c -> c.since.sumOf { toward(it, v) } / need },
    )

    val ALL: List<Mission> = listOf(
        Mission(
            "terror", "Night of Terror", "Play a horror game for 30 minutes. Lights off helps.", Vertex.NERVE, 30,
            eligible = { "Horror" in it.genres },
            progress = { c -> c.since.filter { "Horror" in it.session.genres }.sumOf(::minutes) / 30 },
        ),
        vertexMission("brawl", "Brawl", Vertex.POWER, "fighting, beat 'em up or action games", 30.0),
        vertexMission("hands", "Quick Hands", Vertex.REFLEX, "platform, shooting, racing or sports games", 30.0),
        vertexMission("road", "The Long Road", Vertex.SOUL, "role-playing games", 60.0),
        vertexMission("brain", "Brain Teaser", Vertex.MIND, "puzzle, strategy, simulation or adventure games", 30.0),
        Mission(
            "oldschool", "Old School", "Play 30 minutes on a console released before 1990.", null, 30,
            eligible = { it.consoleYear in 1..1989 },
            progress = { c -> c.since.filter { (c.years[it.system] ?: 0) in 1..1989 }.sumOf(::minutes) / 30 },
        ),
        Mission(
            "pocket", "Pocket Classic", "Play 30 minutes of a game from a handheld console.", null, 30,
            eligible = { it.system in HANDHELDS },
            progress = { c -> c.since.filter { it.system in HANDHELDS }.sumOf(::minutes) / 30 },
        ),
        Mission(
            "first", "First Contact", "Play a game you have never played before, for 20 minutes.", null, 30,
            eligible = { it.playedMs == 0L },
            progress = { c ->
                val old = c.before.map { it.system to it.file }.toSet()
                (c.since.filter { (it.system to it.file) !in old }.groupBy { it.system to it.file }
                    .maxOfOrNull { (_, ps) -> ps.sumOf(::minutes) } ?: 0.0) / 20
            },
        ),
        Mission(
            "dusty", "Dusty Shelf", "Go back to a game you have not touched in a month, for 20 minutes.", null, 30,
            eligible = { g -> g.lastPlayed?.let { System.currentTimeMillis() - it > 30L * DAY } == true },
            progress = { c ->
                val last = c.before.groupBy { it.system to it.file }.mapValues { (_, ps) -> ps.maxOf { it.session.startedAt } }
                (c.since.filter { p -> last[p.system to p.file]?.let { c.startedAt - it > 30L * DAY } == true }
                    .groupBy { it.system to it.file }.maxOfOrNull { (_, ps) -> ps.sumOf(::minutes) } ?: 0.0) / 20
            },
        ),
        Mission(
            "unplugged", "Unplugged", "Play 45 minutes in a single session, without the charger.", null, 30,
            eligible = { true },
            progress = { c -> (c.since.filter { !it.charged }.maxOfOrNull(::minutes) ?: 0.0) / 45 },
        ),
        Mission(
            "marathon", "Marathon", "Play a single session of two hours.", null, 60,
            eligible = { true },
            progress = { c -> (c.since.maxOfOrNull(::minutes) ?: 0.0) / 120 },
        ),
        Mission(
            "finish", "Finish Line", "Beat a game, then mark it as completed in its quick menu.", null, 60,
            eligible = { it.playedMs >= 3_600_000L && !it.completed },
            progress = { c -> if (c.completions.any { it >= c.startedAt }) 1.0 else 0.0 },
        ),
    )

    private const val DAY = 86_400_000L

    fun byId(id: String): Mission? = ALL.firstOrNull { it.id == id }

    /** Lo que lleva una mision que se sigue desde [startedAt]. */
    fun progress(m: Mission, plays: List<LogStats.Play>, completions: List<Long>, startedAt: Long, years: Map<String, Int>): Double {
        val (since, before) = plays.partition { it.session.startedAt >= startedAt }
        return m.progress(Check(since, before, completions, startedAt, years)).coerceIn(0.0, 1.0)
    }

    /**
     * Las que se siguen y ya se cumplieron: pasan a cumplidas y dejan de seguirse. Devuelve las
     * nuevas, para anunciarlas.
     */
    // De uno en uno (07-10-2026): al volver de un juego se relee dos veces casi a la vez —y el cuaderno
    // del Companion tambien llama aqui—, las dos veian la mision seguida y la apuntaban, y su
    // experiencia se cobraba dos veces para siempre (y viajaba asi a las otras consolas).
    @Synchronized
    fun settle(
        prefs: Prefs,
        book: Logbook,
        plays: List<LogStats.Play>,
        completions: List<Long>,
        years: Map<String, Int>,
        now: Long = System.currentTimeMillis(),
    ): List<Mission> {
        // Las que una version anterior guardo en los ajustes pasan al cuaderno, una vez.
        prefs.doneMissions.takeIf { it.isNotEmpty() }?.let { old ->
            for ((id, at) in old) runCatching { book.missionDone(id, at) }
            prefs.doneMissions = emptyList()
        }
        val tracked = prefs.trackedMissions
        val done = tracked.mapNotNull { (id, at) -> byId(id)?.takeIf { progress(it, plays, completions, at, years) >= 1.0 } }
        if (done.isNotEmpty()) {
            for (m in done) runCatching { book.missionDone(m.id, now) }
            prefs.trackedMissions = tracked - done.map { it.id }.toSet()
        }
        return done
    }

    /** La experiencia de las cumplidas, de todas las consolas, para el personaje. */
    fun bonuses(done: List<Pair<String, Long>>): List<Metagame.Bonus> =
        done.mapNotNull { (id, _) -> byId(id)?.let { Metagame.Bonus(it.reward.toDouble(), it.vertex) } }
}
