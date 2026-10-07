package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {

    private fun index(vararg names: String) = ThumbIndex(names.map(::ThumbEntry))

    /** La clave de un nombre de la coleccion, que es un fichero: como la saca ThumbEntry. */
    private fun remote(fileName: String) = ThumbEntry(fileName).key

    @Test fun `el artículo movido al final casa con el titulo normal`() {
        assertEquals(MatchKey.of("The 7th Saga"), remote("7th Saga, The (USA).png"))
        assertEquals(MatchKey.of("The Legend of Zelda"), remote("Legend of Zelda, The (USA).png"))
    }

    @Test fun `una coma que no es un articulo se deja en paz`() {
        // "Castlevania II - Simon's Quest" no debe reordenarse por la coma de otro sitio.
        assertEquals(
            MatchKey.of("Oddworld - Abe's Oddysee"),
            remote("Oddworld - Abe's Oddysee (USA).png")
        )
    }

    @Test fun `los numeros romanos y arabigos son el mismo juego`() {
        assertEquals(MatchKey.of("Final Fantasy VII"), remote("Final Fantasy 7 (USA).png"))
        assertEquals(MatchKey.of("God of War II"), MatchKey.of("God of War 2"))
    }

    @Test fun `los separadores de subtitulo no importan`() {
        assertEquals(
            MatchKey.of("Donkey Kong Country 2 - Diddy's Kong Quest"),
            remote("Donkey Kong Country 2: Diddy's Kong Quest (USA).png")
        )
    }

    @Test fun `saca la region de los parentesis`() {
        assertEquals(setOf("usa"), MatchKey.regionsOf("Super Metroid (USA).png"))
        assertEquals(setOf("europe"), MatchKey.regionsOf("Terranigma (Europe).png"))
        assertTrue("japan" in MatchKey.regionsOf("Seiken Densetsu 3 (Japan) (Rev 1).png"))
    }

    @Test fun `prefiere la region del propio juego`() {
        val idx = index(
            "Super Bomberman 2 (Europe).png",
            "Super Bomberman 2 (Japan).png",
            "Super Bomberman 2 (USA).png",
        )
        assertEquals("Super Bomberman 2 (Europe).png", idx.best("Super Bomberman 2", "europe")?.fileName)
        assertEquals("Super Bomberman 2 (Japan).png", idx.best("Super Bomberman 2", "japan")?.fileName)
    }

    @Test fun `sin region conocida cae en la version inglesa`() {
        val idx = index(
            "Terranigma (Japan).png",
            "Terranigma (Europe).png",
        )
        // Sin USA disponible, Europe gana a Japan.
        assertEquals("Terranigma (Europe).png", idx.best("Terranigma", null)?.fileName)
    }

    @Test fun `la version final gana a la beta y al prototipo`() {
        val idx = index(
            "Star Fox 2 (Japan) (Proto).png",
            "Star Fox 2 (USA).png",
            "Star Fox 2 (Europe) (Beta).png",
        )
        assertEquals("Star Fox 2 (USA).png", idx.best("Star Fox 2", null)?.fileName)
    }

    @Test fun `no inventa una coincidencia cuando no la hay`() {
        val idx = index("Super Mario World (USA).png")
        assertNull(idx.best("Chrono Trigger", "usa"))
    }

    @Test fun `casa el nombre de archivo real de la biblioteca`() {
        // Nombres tal cual están en la microSD del dispositivo.
        val idx = index(
            "Demon's Crest (USA).png",
            "Donkey Kong Country 2 - Diddy's Kong Quest (USA).png",
            "Terranigma (Europe).png",
        )
        // Como los pide el scraper: el nombre del fichero, sin su extension.
        fun file(name: String) = idx.best(MatchKey.stem(name), null)?.fileName
        assertEquals("Demon's Crest (USA).png", file("Demon's Crest.sfc"))
        assertEquals(
            "Donkey Kong Country 2 - Diddy's Kong Quest (USA).png",
            file("Donkey Kong Country 2 - Diddy's Kong Quest.sfc")
        )
        assertEquals("Terranigma (Europe).png", file("Terranigma.sfc"))
    }

    @Test fun `un titulo con punto no se corta como si fuera una extension`() {
        val idx = index(
            "Super Mario Bros. (World).png",
            "Super Mario Bros. 3 (USA).png",
            "Dr. Mario (World).png",
        )
        // El nombre que le pone alguien a mano, y el que saca Detector del fichero.
        assertEquals("Super Mario Bros. 3 (USA).png", idx.best("Super Mario Bros. 3")?.fileName)
        assertEquals("Dr. Mario (World).png", idx.best("Dr. Mario")?.fileName)
        // Y el fichero, que si trae extension.
        assertEquals(
            "Super Mario Bros. 3 (USA).png",
            idx.best(MatchKey.stem("Super Mario Bros. 3 (USA).nes"))?.fileName
        )
        assertEquals(
            "Super Mario Bros. (World).png",
            idx.best(MatchKey.stem("Super Mario Bros. (World).nes"))?.fileName
        )
    }

    @Test fun `el ampersand casa con el guion bajo de libretro`() {
        // libretro cambia «&» por «_» en sus nombres de fichero.
        val idx = index("Sonic _ Knuckles (World).png", "Mario _ Luigi - Superstar Saga (USA).png")
        assertEquals(
            "Sonic _ Knuckles (World).png",
            idx.best(MatchKey.stem("Sonic & Knuckles (World).md"))?.fileName
        )
        assertEquals(
            "Mario _ Luigi - Superstar Saga (USA).png",
            idx.best("Mario & Luigi - Superstar Saga")?.fileName
        )
    }

    @Test fun `el ampersand y el guion bajo no rompen la comparacion`() {
        assertEquals(MatchKey.of("Chip & Dale"), remote("Chip and Dale (USA).png"))
        assertEquals(MatchKey.of("Super_Mario_World"), remote("Super Mario World (USA).png"))
    }

    private val pes = index(
        "Winning Eleven - Pro Evolution Soccer 2007 (USA).png",
        "Pro Evolution Soccer 6 (Europe).png",
        "Pro Evolution Soccer 2008 (Europe).png",
        "Pro Yakyuu Spirits 2013 (Japan).png",
    )

    @Test fun `un nombre a mano casa aunque le falten palabras`() {
        assertEquals(
            "Winning Eleven - Pro Evolution Soccer 2007 (USA).png",
            pes.near("Pro Evolution Soccer 2007")?.fileName,
        )
    }

    @Test fun `a mano, otro numero es otro juego`() {
        assertNull(pes.near("Pro Evolution Soccer 2013"))
    }

    @Test fun `a mano, con menos de tres palabras no se adivina`() {
        assertNull(index("Final Doom (USA).png").near("Doom"))
    }

    @Test fun `a mano, dos juegos igual de cerca no se elige ninguno`() {
        val idx = index("Mega Man Legends Extra (USA).png", "Mega Man Legends Special (Japan).png")
        assertNull(idx.near("Mega Man Legends"))
    }

    @Test fun `las opciones empiezan por el mismo nombre, una por region`() {
        val idx = index(
            "Contra (USA).png",
            "Castlevania III - Dracula's Curse (USA).png",
            "Castlevania (Europe).png",
            "Castlevania (USA) (Rev 1).png",
            "Castlevania (USA).png",
            "Castlevania II - Simon's Quest (USA).png",
        )
        val got = idx.around(listOf("Castlevania"), 9)
        assertEquals(
            listOf(
                "Castlevania (USA).png" to true,
                "Castlevania (Europe).png" to true,
                "Castlevania II - Simon's Quest (USA).png" to false,
                "Castlevania III - Dracula's Curse (USA).png" to false,
            ),
            got.map { it.first.fileName to it.second },
        )
    }

    @Test fun `las opciones parecidas traen la serie y no lo que solo comparte el año`() {
        val got = pes.around(listOf("Pro Evolution Soccer 2013"), 9)
        assertEquals(
            listOf(
                "Pro Evolution Soccer 6 (Europe).png",
                "Pro Evolution Soccer 2008 (Europe).png",
                "Winning Eleven - Pro Evolution Soccer 2007 (USA).png",
            ),
            got.map { it.first.fileName },
        )
        assertTrue(got.none { it.second })
    }

    @Test fun `las opciones no se parecen por las palabras de relleno`() {
        val idx = index("House of the Dead, The (USA).png", "Legend of Zelda, The (USA).png")
        val got = idx.around(listOf("The Story of Thor"), 9)
        assertTrue(got.isEmpty())
    }

    @Test fun `las opciones respetan el tope`() {
        assertEquals(2, pes.around(listOf("Pro Evolution Soccer 2013"), 2).size)
    }
}
