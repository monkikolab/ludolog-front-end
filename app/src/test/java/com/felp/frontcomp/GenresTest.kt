package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Los generos tal como llegan de cada fuente, y el nombre propio que les toca. */
class GenresTest {

    @Test fun `los de Wikidata, y gana el mas concreto`() {
        // Terranigma: los dos, y gana el de rol de accion aunque llegue el segundo.
        assertEquals("Action RPG", Genres.label(listOf("role-playing video game", "action role-playing game")))
        // Symphony of the Night: metroidvania antes que rol de accion.
        assertEquals("Metroidvania", Genres.label(listOf("action role-playing game", "Metroidvania", "fantasy video game")))
        // Parasite Eve II: el terror manda.
        assertEquals("Horror", Genres.label(listOf("action role-playing game", "Survival Horror")))
        assertEquals(listOf("Horror", "Action-Adventure"), Genres.canon(listOf("action-adventure game", "survival horror")))
        assertEquals("Horror", Genres.label(listOf("psychological horror fiction")))
        assertEquals("Platform", Genres.label(listOf("2D platform game")))
        assertEquals("Shooter", Genres.label(listOf("shoot 'em up")))
        assertEquals("Sports", Genres.label(listOf("association football video game")))
        assertEquals("Tactical RPG", Genres.label(listOf("tactical role-playing game")))
    }

    @Test fun `los de libretro y GameTDB`() {
        assertEquals("Beat 'em up", Genres.label(listOf("Beat'em Up")))
        assertEquals("Horror", Genres.label(listOf("Survival Horror")))
        assertEquals(listOf("Adventure", "Action"), Genres.canon(listOf("action,adventure")))
        assertEquals("RPG", Genres.label(listOf("Role-playing (RPG)")))
    }

    @Test fun `sin regla, el crudo con mayuscula`() {
        assertEquals("Christmas video game", Genres.label(listOf("christmas video game")))
        assertEquals(null, Genres.label(emptyList()))
    }
}
