package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La tarjeta de la lista tiene que encontrar las partidas de un juego aunque se apuntaran con
 * otro nombre. Los casos salen del cuaderno del aparato: partidas viejas sin extension y con
 * el nombre bonito, partidas nuevas con el fichero entero y el nombre de la lista.
 */
class PlayedTest {

    private fun row(system: String, file: String?, name: String, sessions: Int, ms: Long, at: Long) =
        LogStats.Played.Row(system, file, name, sessions, ms, at)

    @Test fun `el fichero se compara sin extension ni carpeta`() {
        assertEquals("blood omen - legacy of kain", fileKey("Blood Omen - Legacy of Kain.chd"))
        assertEquals(
            fileKey("Castlevania-Symphony of the Night"),
            fileKey("/storage/XXXX-XXXX/ROMs/psx/Castlevania-Symphony of the Night.chd"),
        )
        // Un punto que no abre extension se queda donde esta.
        assertEquals("dr. mario (usa)", fileKey("Dr. Mario (USA)"))
        assertEquals("game vol.2", fileKey("Game Vol.2"))
        // Y la doble de PICO-8 queda igual por los dos lados.
        assertEquals("celeste.p8", fileKey("celeste.p8.png"))
        // Las largas del catalogo tambien: aqui se apunta el fichero entero y RetroCompanion sin
        // extension, y en una consola de pruebas la tarjeta de Kingdom Hearts III decia «Not played yet».
        assertEquals(
            fileKey("KINGDOM HEARTS III + Re Mind (DLC)"),
            fileKey("KINGDOM HEARTS III + Re Mind (DLC).steam"),
        )
        // Pero una palabra cualquiera tras un punto no es una extension.
        assertEquals("mr.driller", fileKey("Mr.Driller"))
    }

    @Test fun `todas las extensiones largas del catalogo se quitan`() {
        val shipped = Catalog.parse(java.io.File("src/main/assets/systems.toml").readText())
        val long = shipped.systems.flatMap { it.extensions }
            .filter { it.length > 4 && '.' !in it }.toSet()
        assertEquals("el catalogo declara extensiones que fileKey no quita", emptySet<String>(), long - LONG_EXTENSIONS)
    }

    @Test fun `encuentra un juego apuntado con otro nombre`() {
        val book = LogStats.Played(
            listOf(row("psx", "Castlevania-Symphony of the Night", "Castlevania: Symphony of the Night", 2, 399_000, 10)),
            emptyList(),
        )
        val t = book.game("psx", "Castlevania-Symphony of the Night.chd", "Castlevania-Symphony of the Night")
        assertEquals(2, t?.sessions)
        assertEquals(399_000L, t?.totalMs)
        // Y no en otra consola.
        assertNull(book.game("saturn", "Castlevania-Symphony of the Night.chd", "Castlevania-Symphony of the Night"))
    }

    @Test fun `los discos de un juego son un juego`() {
        val book = LogStats.Played(
            listOf(
                row("psx", "Final Fantasy VII (Disc 1).chd", "Final Fantasy VII", 3, 3_000, 5),
                row("psx", "Final Fantasy VII (Disc 2).chd", "Final Fantasy VII", 1, 1_000, 9),
                row("psx", "Mega Man X6 (Europe)", "Mega Man X6", 2, 2_500, 7),
            ),
            emptyList(),
        )
        val games = book.console("psx")
        assertEquals(2, games.size)
        assertEquals(4_000L, games.first().tally.totalMs)
        assertEquals(9L, games.first().tally.lastAt)
    }

    @Test fun `una fila junta dos nombres del mismo fichero`() {
        val book = LogStats.Played(
            listOf(
                row("ps2", "Mortal Kombat - Shaolin Monks", "Mortal Kombat : Shaolin Monks", 1, 209_000, 1),
                row("ps2", "Glass Rose (Europe)", "Glass Rose", 1, 206_000, 2),
                row("ps2", "Mortal Kombat - Shaolin Monks.chd", "Mortal Kombat - Shaolin Monks", 2, 100_000, 3),
            ),
            emptyList(),
        )
        assertEquals(2, book.console("ps2").size)
        assertEquals(3, book.game("ps2", "Mortal Kombat - Shaolin Monks.chd", "Mortal Kombat - Shaolin Monks")?.sessions)
    }

    @Test fun `lo que queda sale de la carga de ahora y de las diez ultimas sin cargador`() {
        // Las diez ultimas gastan 500 mAh por hora; las dos mas viejas, el doble, y no cuentan.
        val drains = (1..10).map {
            LogStats.Played.Drain("gba", "Aria.gba", "Aria", uah = 500_000.0, ms = 3_600_000.0)
        } + (1..2).map {
            LogStats.Played.Drain("gba", "Aria.gba", "Aria", uah = 1_000_000.0, ms = 3_600_000.0)
        }
        val book = LogStats.Played(listOf(row("gba", "Aria.gba", "Aria", 12, 43_200_000, 1)), drains)
        val t = book.game("gba", "Aria.gba", "Aria")!!
        assertEquals(500f, t.mahPerHour!!, 0.01f)
        // Con 1500 mAh en la bateria, tres horas; recien cargada no importa.
        assertEquals(3f, t.hoursLeft(1_500_000)!!, 0.01f)
        // Sin la carga del aparato, o sin partidas sin cargador, no hay estimacion.
        assertNull(t.hoursLeft(null))
        val plugged = LogStats.Played(listOf(row("gba", "Aria.gba", "Aria", 12, 43_200_000, 1)), emptyList())
        assertNull(plugged.game("gba", "Aria.gba", "Aria")!!.hoursLeft(1_500_000))
    }
}
