package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TOML reader is what every config file now depends on, and those files are edited by
 * hand. So the cases that matter are not the happy ones — they are the mistakes someone
 * will actually make, and whether the error says where.
 */
class TomlTest {

    @Test fun `lee tablas repetidas en orden`() {
        val doc = Toml.parse(
            """
            [[system]]
            id = "a"
            [[system]]
            id = "b"
            """.trimIndent()
        )
        assertEquals(listOf("a", "b"), doc.all("system").map { it.string("id") })
    }

    @Test fun `tipos basicos`() {
        val t = Toml.parse(
            """
            [x]
            s = "hola"
            lit = 'sin \n escapes'
            n = 1.4
            i = 42
            yes = true
            no = false
            """.trimIndent()
        ).first("x")!!

        assertEquals("hola", t.string("s"))
        assertEquals("sin \\n escapes", t.string("lit"))
        assertEquals(1.4, t.number("n")!!, 0.0001)
        assertEquals(42.0, t.number("i")!!, 0.0001)
        assertEquals(true, t.bool("yes"))
        assertEquals(false, t.bool("no"))
    }

    @Test fun `arrays en una linea y repartidos en varias`() {
        val t = Toml.parse(
            """
            [x]
            corto = ["a", "b"]
            largo = [
              "uno", "dos",
              "tres",
            ]
            """.trimIndent()
        ).first("x")!!

        assertEquals(listOf("a", "b"), t.strings("corto"))
        assertEquals(listOf("uno", "dos", "tres"), t.strings("largo"))
    }

    @Test fun `los comentarios se quitan salvo dentro de las comillas`() {
        val t = Toml.parse(
            """
            [x]
            a = "vale"   # esto es un comentario
            b = "con # dentro"
            c = ["p", "q"]  # y aqui otro
            """.trimIndent()
        ).first("x")!!

        assertEquals("vale", t.string("a"))
        assertEquals("con # dentro", t.string("b"))
        assertEquals(listOf("p", "q"), t.strings("c"))
    }

    @Test fun `escapes dentro de cadenas`() {
        val t = Toml.parse(
            """
            [x]
            a = "primera\nsegunda"
            b = "comillas \" dentro"
            c = "barra \\ suelta"
            """.trimIndent()
        ).first("x")!!

        assertEquals("primera\nsegunda", t.string("a"))
        assertEquals("comillas \" dentro", t.string("b"))
        assertEquals("barra \\ suelta", t.string("c"))
    }

    @Test fun `una coma con corchete dentro de una cadena no parte el array`() {
        val t = Toml.parse(
            """
            [x]
            a = ["uno, con coma", "dos]con corchete"]
            """.trimIndent()
        ).first("x")!!
        assertEquals(listOf("uno, con coma", "dos]con corchete"), t.strings("a"))
    }

    @Test fun `una cadena sin cerrar dice en que linea esta`() {
        val e = runCatching {
            Toml.parse(
                """
                [x]
                bien = "vale"
                mal = "se me olvido cerrar
                """.trimIndent()
            )
        }.exceptionOrNull() as? Toml.ParseError
        assertTrue("debería fallar", e != null)
        assertEquals(3, e!!.line)
    }

    @Test fun `un valor fuera de toda tabla se rechaza`() {
        val e = runCatching { Toml.parse("suelto = 1") }.exceptionOrNull() as? Toml.ParseError
        assertEquals(1, e?.line)
    }

    @Test fun `una cabecera sin cerrar se rechaza`() {
        val e = runCatching { Toml.parse("[[system\nid = \"a\"") }
            .exceptionOrNull() as? Toml.ParseError
        assertEquals(1, e?.line)
    }

    @Test fun `pedir una clave que no esta devuelve nulo, no revienta`() {
        val t = Toml.parse("[x]\na = 1").first("x")!!
        assertNull(t.string("no-existe"))
        assertNull(t.bool("tampoco"))
        assertEquals(emptyList<String>(), t.strings("ni-esta"))
    }

    @Test fun `el catalogo real se lee entero`() {
        val f = java.io.File("src/main/assets/systems.toml")
        assertTrue(f.isFile)
        val doc = Toml.parse(f.readText())
        assertEquals(90, doc.all("system").size)
        // Un sistema cualquiera tiene que llegar completo, no a medias.
        val snes = doc.all("system").first { it.string("id") == "snes" }
        assertEquals("Super Nintendo", snes.string("name"))
        assertEquals("snes9x", snes.string("raCore"))
        assertEquals(1.4, snes.number("boxAspect")!!, 0.001)
        assertTrue("sfc" in snes.strings("extensions"))
        assertTrue(snes.strings("aliases").size > 5)
    }

    @Test fun `un fichero guardado con BOM se lee`() {
        val doc = Toml.parse(Char(0xFEFF) + "[[system]]\nid = \"snes\"\n")
        assertEquals("snes", doc.all("system").first().string("id"))
    }

    @Test fun `las cadenas de tres comillas se rechazan con su nombre`() {
        val e = runCatching { Toml.parse("[x]\ndescription = \"\"\"hola\"\"\"") }
            .exceptionOrNull() as? Toml.ParseError
        assertEquals(2, e?.line)
        assertTrue(e?.message.orEmpty().contains("multi-line"))
    }
}
