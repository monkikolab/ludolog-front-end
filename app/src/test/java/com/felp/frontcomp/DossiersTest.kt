package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DossiersTest {

    @Test fun `la ficha se escribe y se lee igual`() {
        val d = Dossier(
            "/storage/X/ROMs/snes/Terranigma.sfc", 4194304, 1727000000000,
            linkedMapOf(
                "keys" to "c:a31e3d42", "how" to "crc", "exact" to "1", "name" to "Terranigma (Europe)",
                "g.wd" to "action role-playing game", "g.lr" to "Action", "date" to "1995-10-20",
                "syn" to "Terranigma is an action role-playing game.\tWith a tab",
                "my.genre" to "RPG",
            ),
        )
        val back = Dossiers.parse(Dossiers.format(listOf(d)).lineSequence())
        val e = back.getValue(d.path)
        assertEquals(d.size, e.size)
        assertEquals(d.mtime, e.mtime)
        assertEquals("Terranigma is an action role-playing game. With a tab", e.synopsis)
        assertEquals(listOf("c:a31e3d42"), e.keys)
        assertTrue(e.exact)
    }

    @Test fun `los generos de todas las fuentes, y el escrito a mano manda`() {
        val auto = Dossier("p", 1, 1, mapOf("g.wd" to "metroidvania|action-adventure game", "g.lr" to "Adventure|metroidvania"))
        assertEquals(listOf("metroidvania", "action-adventure game", "Adventure"), auto.genres)
        val mine = auto.copy(fields = auto.fields + ("my.genre" to "Platform"))
        assertEquals(listOf("Platform"), mine.genres)
        assertEquals("1995", Dossier("p", 1, 1, mapOf("date" to "1995-10-20")).year)
    }

    @Test fun `un fichero leido que no dio claves lo sigue diciendo`() {
        // Un perfil de DoomForge: se leyo y no dio nada. Si «keys» se perdiera al guardar, se
        // volveria a leer en cada arranque.
        val d = Dossier("/x/Doom.p8", 10, 20, mapOf("keys" to "", "cat" to "v+2"))
        val back = Dossiers.parse(Dossiers.format(listOf(d)).lineSequence()).getValue(d.path)
        assertTrue("keys" in back.fields)
        assertEquals(emptyList<String>(), back.keys)
    }

    @Test fun `una ficha de otra version se ignora`() {
        assertTrue(Dossiers.parse(sequenceOf("otra cosa", "p\t1\t1\tkeys=c:1")).isEmpty())
    }
}
