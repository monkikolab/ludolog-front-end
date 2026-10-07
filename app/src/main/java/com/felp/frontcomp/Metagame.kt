package com.felp.frontcomp

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/**
 * El personaje: subir de nivel jugando. Ver docs/metagame.md.
 *
 * Todo se calcula del cuaderno cada vez, sin guardar puntos aparte: asi cuenta el pasado, no hay
 * nada que se desincronice entre consolas, y la curva se puede ajustar sin perder nada.
 *
 * - 1 minuto jugado es 1 XP, de todas las consolas, contando una vez la partida repetida.
 * - Cada minuto va a uno o dos vertices segun el genero del juego (ver [weights]); sin genero, un
 *   quinto a cada uno: la experiencia neutra.
 * - Cada vertice llega a 19,8 niveles a las 146 h, y el nivel es la suma de los cinco. La unica
 *   forma de llegar al 99 es llenarlos todos: jugar de todo.
 * - Descanso: el tiempo sin jugar llena una reserva a razon de un minuto por cada diez, hasta una
 *   hora; jugando, cada minuto vale doble mientras queda reserva.
 * - Cansancio: desde las 3 h seguidas en una partida, cada minuto vale la mitad.
 */
internal object Metagame {

    enum class Vertex(val label: String) { POWER("POWER"), REFLEX("REFLEX"), SOUL("SOUL"), NERVE("NERVE"), MIND("MIND") }

    /** Las horas de un vertice lleno: un año de dos horas diarias, repartido entre cinco. */
    const val VERTEX_MINUTES = 730.0 * 60 / 5

    /** Los niveles de un vertice lleno: los 98 que se suben, repartidos entre cinco. */
    const val VERTEX_LEVELS = 98.0 / 5

    /** La forma de la curva: los primeros niveles rapidos, los ultimos caros. */
    private const val CURVE = 2.2

    const val REST_RATE = 10.0
    const val REST_CAP = 60.0
    const val TIRED_AFTER = 180.0

    /** Una partida tal como la usa el personaje: cuando, cuanto y de que genero (nombres propios de Genres). */
    data class Session(
        val id: Long,
        val startedAt: Long,
        val durationMs: Long,
        val genres: List<String>,
    )

    /** Lo que dio una partida: la base, lo que sumo el descanso, lo que resto el cansancio, y a donde fue. */
    data class Gain(
        val base: Double,
        val rested: Double,
        val tired: Double,
        val byVertex: Map<Vertex, Double>,
    ) {
        val total: Double get() = base + rested - tired
    }

    data class Character(
        /** 1 a 99. */
        val level: Int,
        /** Lo que va del nivel al siguiente, de 0 a 1. */
        val progress: Double,
        /** Minutos de juego neutro que faltan para el siguiente nivel; nulo en el 99. */
        val toNext: Double?,
        val xp: Map<Vertex, Double>,
        /** El nivel de cada vertice, de 0 a [VERTEX_LEVELS]. */
        val vertexLevels: Map<Vertex, Double>,
        /** La reserva de descanso ahora mismo, en minutos: lo que se jugara a doble. */
        val rested: Double,
        val gains: Map<Long, Gain>,
    ) {
        val total: Double get() = xp.values.sum()
    }

    /**
     * La experiencia como se enseña: un punto por segundo jugado. Por dentro se cuenta en minutos
     * —la curva y los topes estan en minutos—; fuera, en segundos, que da numeros grandes y se
     * nota cada partida.
     */
    fun xp(minutes: Double): String = String.format(java.util.Locale.US, "%,d", Math.round(minutes * 60))

    /** El nivel de un vertice con esa experiencia, en minutos. */
    fun vertexLevel(minutes: Double): Double =
        VERTEX_LEVELS * (min(minutes, VERTEX_MINUTES) / VERTEX_MINUTES).pow(1 / CURVE)

    private fun levelOf(xp: Map<Vertex, Double>): Double =
        1 + Vertex.entries.sumOf { vertexLevel(xp[it] ?: 0.0) }

    /**
     * A que vertices va un genero, y cuanto. Los hibridos, mitad y mitad; lo que no esta en la
     * tabla, a ninguno (y entonces cuenta el siguiente genero de la ficha, o el neutro).
     */
    fun weights(genre: String): Map<Vertex, Double>? = when (genre) {
        "Horror", "Stealth" -> mapOf(Vertex.NERVE to 1.0)
        "RPG", "Visual Novel" -> mapOf(Vertex.SOUL to 1.0)
        "Platform", "Shooter", "Racing", "Sports", "Music", "Party" -> mapOf(Vertex.REFLEX to 1.0)
        "Fighting", "Beat 'em up", "Action" -> mapOf(Vertex.POWER to 1.0)
        "Puzzle", "Strategy", "Simulation", "Adventure" -> mapOf(Vertex.MIND to 1.0)
        "Metroidvania" -> mapOf(Vertex.REFLEX to .5, Vertex.MIND to .5)
        "Action RPG" -> mapOf(Vertex.POWER to .5, Vertex.SOUL to .5)
        "Tactical RPG" -> mapOf(Vertex.SOUL to .5, Vertex.MIND to .5)
        "Roguelike" -> mapOf(Vertex.REFLEX to .5, Vertex.SOUL to .5)
        "Action-Adventure" -> mapOf(Vertex.POWER to .5, Vertex.MIND to .5)
        else -> null
    }

    private val NEUTRAL = Vertex.entries.associateWith { 1.0 / Vertex.entries.size }

    /** El reparto de un juego: el de su genero mas concreto que este en la tabla, o el neutro. */
    fun split(genres: List<String>): Map<Vertex, Double> = genres.firstNotNullOfOrNull(::weights) ?: NEUTRAL

    /** Experiencia que no sale de una partida: la recompensa de una mision. Sin vertice, neutra. */
    data class Bonus(val xp: Double, val vertex: Vertex?)

    /** El personaje de un cuaderno. Las partidas, en cualquier orden. */
    fun of(sessions: List<Session>, now: Long = System.currentTimeMillis(), bonuses: List<Bonus> = emptyList()): Character {
        val xp = Vertex.entries.associateWith { 0.0 }.toMutableMap()
        for (b in bonuses) {
            val to = b.vertex?.let { mapOf(it to 1.0) } ?: NEUTRAL
            for ((v, w) in to) xp[v] = xp.getValue(v) + b.xp * w
        }
        val gains = HashMap<Long, Gain>()
        var pool = 0.0
        var lastEnd: Long? = null
        for (s in sessions.sortedBy { it.startedAt }) {
            lastEnd?.let { end -> if (s.startedAt > end) pool = min(REST_CAP, pool + (s.startedAt - end) / 60_000.0 / REST_RATE) }
            val minutes = s.durationMs / 60_000.0
            val rested = min(pool, minutes)
            pool -= rested
            val tired = (minutes - TIRED_AFTER).coerceAtLeast(0.0) * 0.5
            val total = minutes + rested - tired
            val byVertex = split(s.genres).mapValues { (_, w) -> total * w }
            for ((v, m) in byVertex) xp[v] = xp.getValue(v) + m
            gains[s.id] = Gain(minutes, rested, tired, byVertex)
            lastEnd = maxOf(lastEnd ?: 0L, s.startedAt + s.durationMs)
        }
        lastEnd?.let { end -> if (now > end) pool = min(REST_CAP, pool + (now - end) / 60_000.0 / REST_RATE) }
        return shaped(xp, pool, gains)
    }

    /** El personaje que corresponde a esa experiencia: nivel, avance, vertices. */
    private fun shaped(xp: Map<Vertex, Double>, rested: Double, gains: Map<Long, Gain>): Character {
        val raw = levelOf(xp)
        val level = min(99, floor(raw).toInt())
        return Character(
            level = level,
            progress = if (level >= 99) 1.0 else raw - floor(raw),
            toNext = if (level >= 99) null else toNext(xp, level + 1),
            xp = xp,
            vertexLevels = xp.mapValues { (_, m) -> vertexLevel(m) },
            rested = rested,
            gains = gains,
        )
    }

    /**
     * El personaje con solo una parte [f] (0 a 1) de lo que dio [gain]: con 0, como estaba antes
     * de esa partida; con 1, como esta. Es lo que anima la portada del cuaderno: la barra y el
     * pentagono suben con la experiencia de la partida, y si cruza un nivel la barra se llena,
     * vuelve a empezar y el numero cambia en ese momento, igual que pasaria jugando.
     */
    fun partial(c: Character, gain: Gain, f: Double): Character {
        if (f >= 1.0) return c
        val xp = c.xp.mapValues { (v, m) -> (m - (gain.byVertex[v] ?: 0.0) * (1 - f)).coerceAtLeast(0.0) }
        return shaped(xp, c.rested, c.gains)
    }

    /**
     * Cuanto juego neutro falta para llegar a [target]: por biseccion, porque la suma de cinco
     * curvas no se despeja. Nulo si ni llenando todo se llega.
     */
    private fun toNext(xp: Map<Vertex, Double>, target: Int): Double? {
        fun reaches(extra: Double) = levelOf(xp.mapValues { (_, m) -> m + extra / Vertex.entries.size }) >= target
        var hi = 1.0
        while (!reaches(hi)) { hi *= 2; if (hi > VERTEX_MINUTES * 10) return null }
        var lo = 0.0
        repeat(40) { val mid = (lo + hi) / 2; if (reaches(mid)) hi = mid else lo = mid }
        return hi
    }

    // ---------------------------------------------------------------- clase

    /**
     * Los dos vertices mas altos, o uno solo si dobla al segundo. Nulo sin jugar.
     *
     * Era «uno solo si pasa del 40 % del total», y con dos vertices jugados el primero siempre
     * pasaba: todo el que jugaba dos generos salia con clase pura.
     */
    fun classOf(c: Character): List<Vertex>? {
        val order = c.vertexLevels.entries.sortedByDescending { it.value }
        if (order[0].value <= 0.0) return null
        return if (order[1].value * 2 <= order[0].value) listOf(order[0].key)
        else listOf(order[0].key, order[1].key).sortedBy { it.ordinal }
    }

    /** El nombre de la clase en el tema puesto: cada tema tiene los suyos. */
    fun className(vertices: List<Vertex>, themeId: String): String {
        val table = CLASSES[themeId] ?: CLASSES.getValue("gallery")
        return table[vertices.toSet()] ?: vertices.joinToString("-") { it.label }
    }

    private fun pure(v: Vertex, name: String) = setOf(v) to name
    private fun pair(a: Vertex, b: Vertex, name: String) = setOf(a, b) to name

    private val CLASSES: Map<String, Map<Set<Vertex>, String>> = run {
        val P = Vertex.POWER; val R = Vertex.REFLEX; val S = Vertex.SOUL; val N = Vertex.NERVE; val M = Vertex.MIND
        mapOf(
            "parlour" to mapOf(
                pure(P, "Knight"), pure(R, "Huntsman"), pure(S, "Mystic"), pure(N, "Nightwalker"), pure(M, "Alchemist"),
                pair(P, R, "Vampire Hunter"), pair(P, S, "Templar"), pair(P, N, "Executioner"), pair(P, M, "Inquisitor"),
                pair(R, S, "Pilgrim"), pair(R, N, "Grave Robber"), pair(R, M, "Artificer"),
                pair(S, N, "Exorcist"), pair(S, M, "Occultist"), pair(N, M, "Medium"),
            ),
            "mainframe" to mapOf(
                pure(P, "Trooper"), pure(R, "Pilot"), pure(S, "Psionic"), pure(N, "Survivor"), pure(M, "Analyst"),
                pair(P, R, "Commando"), pair(P, S, "Cyber Knight"), pair(P, N, "Xenohunter"), pair(P, M, "Tactician"),
                pair(R, S, "Navigator"), pair(R, N, "Scout"), pair(R, M, "Engineer"),
                pair(S, N, "Psi-Ops"), pair(S, M, "Xenologist"), pair(N, M, "Investigator"),
            ),
            "gallery" to mapOf(
                pure(P, "Fighter"), pure(R, "Ace"), pure(S, "Adventurer"), pure(N, "Survivor"), pure(M, "Thinker"),
                pair(P, R, "Brawler"), pair(P, S, "Hero"), pair(P, N, "Slayer"), pair(P, M, "Strategist"),
                pair(R, S, "Explorer"), pair(R, N, "Hunter"), pair(R, M, "Tinkerer"),
                pair(S, N, "Seer"), pair(S, M, "Sage"), pair(N, M, "Detective"),
            ),
        )
    }
}
