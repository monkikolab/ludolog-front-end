package com.felp.frontcomp

import com.felp.frontcomp.Metagame.Vertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetagameTest {

    private val min = 60_000L
    private val hour = 60 * min
    private fun s(id: Long, start: Long, minutes: Long, vararg genres: String) =
        Metagame.Session(id, start, minutes * min, genres.toList())

    @Test fun `sin jugar, nivel 1 y sin clase`() {
        val c = Metagame.of(emptyList())
        assertEquals(1, c.level)
        assertNull(Metagame.classOf(c))
    }

    @Test fun `el 99 solo llenando los cinco vertices`() {
        // Un año de dos horas diarias.
        fun year(vararg genres: String) = Metagame.of((0 until 365).map { d -> s(d.toLong(), d * 24 * hour, 120, *genres) })
        // Todo de un genero: un vertice lleno y los demas vacios, en torno al 20.
        val rpg = year("RPG")
        assertTrue("solo rol: ${rpg.level}", rpg.level in 19..21)
        // Sin genero, repartido por igual: el 99.
        assertEquals(99, year().level)
    }

    @Test fun `el genero manda a su vertice, los hibridos a dos, y sin genero a los cinco`() {
        val c = Metagame.of(
            listOf(s(1, 0, 60, "Horror"), s(2, 10 * hour, 60, "Action RPG"), s(3, 20 * hour, 50)),
            now = 20 * hour,
        )
        // El descanso de entre partidas dobla lo jugado mientras dura la reserva: se descuenta.
        val g1 = c.gains.getValue(1)
        assertEquals(60.0, g1.byVertex.getValue(Vertex.NERVE), 0.001)
        val g2 = c.gains.getValue(2)
        assertEquals(g2.total / 2, g2.byVertex.getValue(Vertex.POWER), 0.001)
        assertEquals(g2.total / 2, g2.byVertex.getValue(Vertex.SOUL), 0.001)
        val g3 = c.gains.getValue(3)
        for (v in Vertex.entries) assertEquals(g3.total / 5, g3.byVertex.getValue(v), 0.001)
    }

    @Test fun `el descanso es una decima del tiempo sin jugar, hasta una hora, y se gasta jugando`() {
        // 5 h de pausa: 30 min de reserva, que doblan los primeros 30 de la segunda partida.
        val c = Metagame.of(listOf(s(1, 0, 60), s(2, 6 * hour, 60)), now = 7 * hour)
        assertEquals(0.0, c.gains.getValue(1).rested, 0.001)
        assertEquals(30.0, c.gains.getValue(2).rested, 0.001)
        // Tras una pausa larguisima, el tope: una hora.
        val long = Metagame.of(listOf(s(1, 0, 10), s(2, 100 * hour, 120)), now = 102 * hour)
        assertEquals(60.0, long.gains.getValue(2).rested, 0.001)
        // Y la reserva de ahora: lo que lleva sin jugar desde la ultima.
        assertEquals(6.0, Metagame.of(listOf(s(1, 0, 60)), now = 2 * hour).rested, 0.001)
    }

    @Test fun `desde las 3 horas seguidas, la mitad`() {
        val c = Metagame.of(listOf(s(1, 0, 240, "RPG")), now = 0)
        assertEquals(30.0, c.gains.getValue(1).tired, 0.001)
        assertEquals(210.0, c.gains.getValue(1).total, 0.001)
    }

    @Test fun `la clase son los dos vertices mas altos, o uno solo si dobla al segundo`() {
        val pair = Metagame.of(listOf(s(1, 0, 600, "Horror"), s(2, 20 * hour, 500, "RPG")), now = 30 * hour)
        assertEquals(listOf(Vertex.SOUL, Vertex.NERVE), Metagame.classOf(pair))
        assertEquals("Exorcist", Metagame.className(Metagame.classOf(pair)!!, "parlour"))
        assertEquals("Psi-Ops", Metagame.className(Metagame.classOf(pair)!!, "mainframe"))
        val pure = Metagame.of(listOf(s(1, 0, 600, "Horror")), now = 10 * hour)
        assertEquals(listOf(Vertex.NERVE), Metagame.classOf(pure))
        assertEquals("Survivor", Metagame.className(Metagame.classOf(pure)!!, "gallery"))
    }

    @Test fun `lo que falta para el siguiente nivel lo sube de verdad`() {
        val c = Metagame.of(listOf(s(1, 0, 300, "Platform")), now = 0)
        val more = Metagame.of(listOf(s(1, 0, 300, "Platform"), s(2, 0, c.toNext!!.toLong() + 1)), now = 0)
        assertTrue(more.level >= c.level + 1)
    }

    @Test fun `a medio contar una partida, el personaje esta entre el de antes y el de ahora`() {
        // Una larga de rol que sube varios niveles, sobre otras de antes.
        val before = listOf(s(1, 0, 600, "Action"), s(2, 30 * hour, 300, "Puzzle"))
        val long = s(3, 60 * hour, 240, "RPG")
        val now = Metagame.of(before + long, now = 70 * hour)
        val gain = now.gains.getValue(3)
        val start = Metagame.partial(now, gain, 0.0)
        // Con cero, igual que sin haberla jugado (salvo el descanso, que es de ahora).
        val without = Metagame.of(before, now = 70 * hour)
        assertEquals(without.level, start.level)
        assertEquals(without.progress, start.progress, 1e-9)
        Vertex.entries.forEach { assertEquals(without.vertexLevels.getValue(it), start.vertexLevels.getValue(it), 1e-9) }
        // Con uno, el de ahora; y a medias, en medio: el nivel no baja y el rol crece.
        assertEquals(now, Metagame.partial(now, gain, 1.0))
        val half = Metagame.partial(now, gain, 0.5)
        assertTrue(half.level in start.level..now.level)
        assertTrue(half.vertexLevels.getValue(Vertex.SOUL) > start.vertexLevels.getValue(Vertex.SOUL))
        assertTrue(half.vertexLevels.getValue(Vertex.SOUL) < now.vertexLevels.getValue(Vertex.SOUL))
        assertTrue("la partida sube de nivel: ${start.level} -> ${now.level}", now.level > start.level)
    }
}
