package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Test

class GameNamesTest {

    // La biblioteca de esta consola: Metal Slug renombrado; un Mario cuyo fichero aqui lleva
    // «_RP5» y el catalogo reconoce; y Terranigma, con genero del catalogo.
    private val here = GameNames.of(
        mapOf(
            ("arcade" to "mslug") to GameNames.Known("Metal Slug"),
            ("switch" to "super mario odyssey_rp5") to GameNames.Known(
                "Super Mario Odyssey", identity = "Super Mario Odyssey", catalogGenres = listOf("platform game"),
            ),
            ("snes" to "terranigma") to GameNames.Known(
                "Terranigma", identity = "Terranigma (Europe)", catalogGenres = listOf("action role-playing game"),
            ),
            ("psx" to "tetris plus") to GameNames.Known(
                "Tetris Plus", catalogGenres = listOf("puzzle video game"), ownGenres = listOf("Party"),
            ),
        ),
    )

    private fun ref(system: String, file: String, name: String, identity: String? = null, at: Long = 1, genre: String? = null) =
        Logbook.NameRef(system, file, name, identity, at, genre)

    private val noCatalog: (String, String) -> List<String> = { _, _ -> emptyList() }

    @Test fun `el nombre de hoy en la biblioteca manda sobre el apuntado`() {
        assertEquals("Metal Slug", here.resolve("arcade", "mslug", "mslug", emptyList()))
        // Aunque otra consola lo llame de otra forma: aqui se ve como aqui se llama.
        assertEquals("Metal Slug", here.resolve("arcade", "mslug", "mslug", listOf(ref("arcade", "mslug", "Metal Slug X", at = 9))))
    }

    @Test fun `un juego que aqui no esta toma la referencia mas reciente`() {
        val refs = listOf(
            ref("psp", "dissidia 012", "Dissidia 012", at = 1),
            ref("psp", "dissidia 012", "Dissidia 012: Duodecim Final Fantasy", at = 5),
        )
        assertEquals("Dissidia 012: Duodecim Final Fantasy", here.resolve("psp", "dissidia 012", "dissidia012", refs))
    }

    @Test fun `por su identidad, el juego de aqui aunque el fichero se llame distinto`() {
        val refs = listOf(ref("switch", "super mario odyssey_odyn3", "SUPER MARIO ODYSSEY", identity = "Super Mario Odyssey"))
        assertEquals("Super Mario Odyssey", here.resolve("switch", "super mario odyssey_odyn3", "SUPER MARIO ODYSSEY", refs))
    }

    @Test fun `sin nada que decir, el apuntado`() {
        assertEquals("Warframe", here.resolve("android", "com.digitalextremes.warframe", "Warframe", emptyList()))
        // Una referencia del mismo fichero no vale para otra consola emulada.
        assertEquals("Tetris", here.resolve("gb", "tetris", "Tetris", listOf(ref("nes", "tetris", "Tetris (NES)"))))
    }

    @Test fun `el genero de un juego de aqui sale de su ficha, y lo puesto a mano manda`() {
        assertEquals(listOf("Action RPG"), here.genres("snes", "terranigma", emptyList(), noCatalog))
        assertEquals(listOf("Party"), here.genres("psx", "tetris plus", emptyList(), noCatalog))
    }

    @Test fun `lo puesto a mano en otra consola manda sobre el catalogo de aqui`() {
        val refs = listOf(ref("snes", "terranigma", "Terranigma", genre = "RPG"))
        assertEquals(listOf("RPG"), here.genres("snes", "terranigma", refs, noCatalog))
    }

    @Test fun `un juego de la otra consola, por su identidad`() {
        // El mismo juego aqui con otro fichero: su ficha.
        val mario = listOf(ref("switch", "super mario odyssey_odyn3", "SUPER MARIO ODYSSEY", identity = "Super Mario Odyssey"))
        assertEquals(listOf("Platform"), here.genres("switch", "super mario odyssey_odyn3", mario, noCatalog))
        // Uno que aqui no esta: el catalogo de su consola, por su nombre canonico.
        val gow = listOf(ref("ps3", "god of war iii_odyn3", "God of War III", identity = "God of War III (USA, Canada) (v01.03)"))
        val catalog: (String, String) -> List<String> = { system, identity ->
            if (system == "ps3" && identity.startsWith("God of War III")) listOf("hack and slash", "action-adventure game") else emptyList()
        }
        assertEquals(listOf("Beat 'em up", "Action-Adventure"), here.genres("ps3", "god of war iii_odyn3", gow, catalog))
    }

    @Test fun `sin genero de nadie, vacio, que es experiencia neutra`() {
        assertEquals(emptyList<String>(), here.genres("pico8", "doom ii", emptyList(), noCatalog))
        assertEquals(emptyList<String>(), here.genres("arcade", "mslug", emptyList(), noCatalog))
    }
}
