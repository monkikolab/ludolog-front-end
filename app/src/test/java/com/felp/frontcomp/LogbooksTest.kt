package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogbooksTest {

    private fun tmp(): File = kotlin.io.path.createTempDirectory("logbooks").toFile()

    @Test fun `el fichero se llama como la consola y su ID, como en RetroCompanion`() {
        assertEquals("HandheldA-c050.db", Logbooks.fileName("HandheldA", "c050"))
        assertEquals("Handheld Model 5-dbed.db", Logbooks.fileName("Handheld Model 5", "dbed"))
        // Lo que no vale en un nombre de fichero, fuera; y sin modelo, «console».
        assertEquals("Brand Model 2 Plus-0a1b.db", Logbooks.fileName("Brand/Model 2:  Plus", "0a1b"))
        assertEquals("console-0a1b.db", Logbooks.fileName("¿?", "0a1b"))
    }

    @Test fun `el ID nuevo no repite el de otra consola igual que ya este en la carpeta`() {
        val seen = mutableListOf<String>()
        val id = Logbooks.newId("HandheldA") { candidate -> seen += candidate; seen.size < 3 }
        assertEquals(3, seen.size)
        assertEquals(seen.last(), id)
        assertEquals(4, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test fun `el cuaderno de antes se muda con su nombre nuevo, sin copiarlo`() {
        val home = tmp()
        val old = File(home, "logbook.db").apply { writeText("partidas") }
        File(home, "logbook.db-journal").createNewFile()
        val wanted = File(home, "companion/HandheldA-c050.db")
        val got = Logbooks.settle(old, wanted)
        assertEquals(wanted, got)
        assertEquals("partidas", wanted.readText())
        assertFalse(old.exists())
        assertTrue(File(home, "companion/HandheldA-c050.db-journal").exists())
        assertFalse(File(home, "logbook.db-journal").exists())
    }

    @Test fun `con una escritura a medias se usa el de siempre, sin tocarlo`() {
        val home = tmp()
        val old = File(home, "logbook.db").apply { writeText("partidas") }
        File(home, "logbook.db-journal").writeText("a medias")
        val wanted = File(home, "companion/HandheldA-c050.db")
        assertEquals(old, Logbooks.settle(old, wanted))
        assertTrue(old.exists())
        assertFalse(wanted.exists())
    }

    @Test fun `si ya tiene el suyo, el de antes se queda donde esta`() {
        val home = tmp()
        val old = File(home, "logbook.db").apply { writeText("viejo") }
        val wanted = File(home, "companion/HandheldA-c050.db").apply { parentFile!!.mkdirs(); writeText("suyo") }
        assertEquals(wanted, Logbooks.settle(old, wanted))
        assertEquals("viejo", old.readText())
        assertEquals("suyo", wanted.readText())
    }

    @Test fun `sin cuaderno de antes, el nombre nuevo sin crear nada`() {
        val home = tmp()
        val wanted = File(home, "companion/HandheldA-c050.db")
        assertEquals(wanted, Logbooks.settle(File(home, "logbook.db"), wanted))
        assertFalse(wanted.exists())
        assertNotEquals(Logbooks.fileName("HandheldA", "c050"), Logbooks.fileName("HandheldA", "d42f"))
    }
}
