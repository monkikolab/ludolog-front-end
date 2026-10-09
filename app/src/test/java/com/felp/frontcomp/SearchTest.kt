package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private fun found(title: String, file: String = "$title.sfc", system: String = "snes"): Found {
        val t = searchKey(title)
        return Found(Game("/roms/$system/$file", system, title), title, system, t + " " + searchKey(file.substringBeforeLast('.')), t)
    }

    private val all = listOf(
        found("The Legend of Zelda - A Link to the Past"),
        found("Zelda II - The Adventure of Link", system = "nes", file = "Zelda II.nes"),
        found("Super Mario World"),
        found("Pokémon Emerald", system = "gba", file = "pokemon emerald.gba"),
        found("Final Fantasy VI", file = "FF6 (USA).sfc"),
    )

    @Test fun `sin acentos ni mayusculas ni signos`() {
        assertEquals("pokemon emerald", searchKey("Pokémon  Emerald!"))
        assertEquals("the legend of zelda a link to the past", searchKey("The Legend of Zelda - A Link to the Past"))
    }

    @Test fun `las palabras en cualquier orden`() {
        val hits = searchMatch(all, "link zelda")
        assertEquals(2, hits.size)
        assertTrue(hits.all { "zelda" in it.titleKey })
    }

    @Test fun `primero los que empiezan por lo buscado`() {
        val hits = searchMatch(all, "zelda")
        assertEquals("Zelda II - The Adventure of Link", hits.first().title)
    }

    @Test fun `tambien por el nombre del fichero`() {
        assertEquals("Final Fantasy VI", searchMatch(all, "ff6").single().title)
    }

    @Test fun `con acento en la busqueda`() {
        assertEquals("Pokémon Emerald", searchMatch(all, "pokémon").single().title)
    }

    @Test fun `vacio no encuentra nada`() {
        assertTrue(searchMatch(all, "  ").isEmpty())
    }
}
