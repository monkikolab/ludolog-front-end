package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionsTest {

    private val min = 60_000L
    private val hour = 60 * min

    private fun play(id: Long, start: Long, minutes: Long, system: String = "psx", file: String = "game$id", vararg genres: String, charged: Boolean = false) =
        LogStats.Play(
            Metagame.Session(id, start, minutes * min, genres.toList()), "handheld-a", system, file,
            emulator = "duckstation", batteryEndPct = 50, charged = charged, tempMaxC = 60f, fpsMean = 60f, powerMeanW = 4f,
        )

    private val years = mapOf("psx" to 1994, "nes" to 1983, "gba" to 2001)

    @Test fun `solo cuenta lo jugado desde que se empezo a seguir`() {
        val terror = Missions.byId("terror")!!
        val plays = listOf(play(1, 0, 40, genres = arrayOf("Horror")), play(2, 10 * hour, 15, genres = arrayOf("Horror")))
        assertEquals(0.5, Missions.progress(terror, plays, emptyList(), 5 * hour, years), 0.001)
        assertEquals(1.0, Missions.progress(terror, plays, emptyList(), 0, years), 0.001)
    }

    @Test fun `los juegos que sirven`() {
        fun c(system: String = "psx", genres: List<String> = emptyList(), year: Int = 1994, played: Long = 0) =
            Missions.Candidate(system, genres, year, played, null, completed = false)
        assertTrue(Missions.byId("terror")!!.eligible(c(genres = listOf("Horror"))))
        assertFalse(Missions.byId("terror")!!.eligible(c(genres = listOf("RPG"))))
        assertTrue(Missions.byId("oldschool")!!.eligible(c(system = "nes", year = 1983)))
        assertTrue(Missions.byId("pocket")!!.eligible(c(system = "gba", year = 2001)))
        assertTrue(Missions.byId("first")!!.eligible(c()))
        assertFalse(Missions.byId("first")!!.eligible(c(played = 1)))
        assertTrue(Missions.byId("road")!!.eligible(c(genres = listOf("Action RPG"))))
    }

    @Test fun `first contact pide un juego nuevo, no uno ya jugado`() {
        val first = Missions.byId("first")!!
        val old = play(1, 0, 30, file = "viejo")
        assertEquals(0.0, Missions.progress(first, listOf(old, play(2, 5 * hour, 30, file = "viejo")), emptyList(), hour, years), 0.001)
        assertEquals(1.0, Missions.progress(first, listOf(old, play(3, 5 * hour, 25, file = "nuevo")), emptyList(), hour, years), 0.001)
    }

    @Test fun `unplugged no cuenta lo jugado con cargador`() {
        val m = Missions.byId("unplugged")!!
        assertEquals(0.0, Missions.progress(m, listOf(play(1, hour, 60, charged = true)), emptyList(), 0, years), 0.001)
        assertEquals(1.0, Missions.progress(m, listOf(play(2, hour, 50)), emptyList(), 0, years), 0.001)
    }

    @Test fun `cada logro lleva la fecha en que se cumplio`() {
        val plays = (0 until 12).map { play(it.toLong(), it * 25 * hour, 30) }
        val character = Metagame.of(plays.map { it.session }, now = 400 * hour)
        val input = Achievements.Input(plays, character, emptyList(), 0, years)
        val dated = Achievements.dated(Achievements.of(input), input, emptyList(), Missions::bonuses)
        // La decima partida empieza a las 9 × 25 h y dura media hora.
        assertEquals(9 * 25 * hour + 30 * min, dated.first { it.a.id == "s10" }.earnedAt)
        assertEquals(null, dated.first { it.a.id == "done1" }.earnedAt)
    }

    @Test fun `energia y baterias se acumulan partida a partida`() {
        // 50 partidas de una hora a 4 W: 200 Wh. La bateria, de 95 a 107 ciclos: 12 cargas.
        val plays = (0 until 50).map { play(it.toLong(), it * 25 * hour, 60).copy(cycles = 95 + it / 4) }
        val character = Metagame.of(plays.map { it.session }, now = 2000 * hour)
        val states = Achievements.of(Achievements.Input(plays, character, emptyList(), 0, years)).associateBy { it.a.id }
        assertEquals(200.0, states.getValue("wh200").value, 0.001)
        assertTrue(states.getValue("wh200").earned)
        assertFalse(states.getValue("wh1000").earned)
        assertEquals(12.0, states.getValue("ch10").value, 0.001)
        assertTrue(states.getValue("ch10").earned)
        // Con el cargador puesto no cuenta energia: lo medido es la carga, no el juego.
        val plugged = Achievements.of(Achievements.Input(listOf(play(1, 0, 60, charged = true)), character, emptyList(), 0, years))
        assertEquals(0.0, plugged.first { it.a.id == "wh200" }.value, 0.001)
        // Cada consola cuenta sus ciclos: dos con 3 cargas cada una son 6, no la resta de una con otra.
        val two = listOf(
            play(1, 0, 30).copy(cycles = 95), play(2, hour, 30).copy(cycles = 98),
            play(3, 2 * hour, 30).copy(device = "handheld-b", cycles = 3), play(4, 3 * hour, 30).copy(device = "handheld-b", cycles = 6),
        )
        assertEquals(6.0, Achievements.of(Achievements.Input(two, character, emptyList(), 0, years)).first { it.a.id == "ch10" }.value, 0.001)
        // Sin medida no suma nada, en vez de romper.
        val bare = Achievements.of(Achievements.Input(listOf(play(1, 0, 60)), character, emptyList(), 0, years))
        assertEquals(0.0, bare.first { it.a.id == "ch10" }.value, 0.001)
    }

    @Test fun `los logros se calculan todos y los de hoy salen`() {
        val plays = (0 until 12).map { play(it.toLong(), it * 25 * hour, 30, system = if (it % 2 == 0) "psx" else "nes", genres = arrayOf("Horror")) }
        val character = Metagame.of(plays.map { it.session }, now = 400 * hour)
        val states = Achievements.of(Achievements.Input(plays, character, emptyList(), 0, years))
        assertTrue("unos 50: ${states.size}", states.size >= 50)
        assertTrue(states.first { it.a.id == "s10" }.earned)
        assertTrue(states.first { it.a.id == "arch" }.earned)
        assertFalse(states.first { it.a.id == "done1" }.earned)
        assertEquals(states.size, states.map { it.a.id }.distinct().size)
    }
}
