package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lo que escribe el gestor de consolas tiene que leerse igual que lo que se guardo.
 *
 * La entrada del usuario sustituye entera a la del catalogo, asi que un campo que el escritor
 * se deja no se queda como estaba: desaparece. Paso con la ficha tecnica y despues con la
 * coleccion de videos, que se perdio al editar Arcade en una consola de pruebas y la dejo sin videos. Este
 * test pasa por el escritor TODAS las consolas del catalogo, con todo lo que traen, y el dia
 * que SystemDef gane un campo que el escritor no conozca, falla aqui y no en un aparato.
 */
class CatalogWriterTest {

    private val shipped = Catalog.parse(File("src/main/assets/systems.toml").readText())

    @Test fun `cada consola del catalogo sale igual de escribirla y leerla`() {
        assertTrue(shipped.systems.size > 10)
        for (sys in shipped.systems) {
            val back = Catalog.parse(CatalogWriter.render(sys)).systems
            assertEquals("${sys.id}: una sola entrada", 1, back.size)
            assertEquals("${sys.id}: algo se perdio o cambio al guardarla", sys, back.first())
        }
    }

    @Test fun `editar arcade en el gestor conserva su coleccion de videos`() {
        val arcade = shipped.byId.getValue("arcade")
        assertTrue(arcade.videoSnaps.isNotEmpty())
        // Lo mismo que hace el gestor: el borrador, un cambio, y a guardar.
        val saved = ConsoleDraft.from(arcade, null).copy(name = "Arcade (editada)").toSystemDef(arcade)
        val back = Catalog.parse(CatalogWriter.render(saved)).systems.first()
        assertEquals("Arcade (editada)", back.name)
        assertEquals(arcade.videoSnaps, back.videoSnaps)
        assertEquals(arcade.copy(name = "Arcade (editada)"), back)
    }

    @Test fun `guardar sin tocar nada deja cada consola como estaba`() {
        for (sys in shipped.systems) {
            val saved = ConsoleDraft.from(sys, null).toSystemDef(sys)
            val back = Catalog.parse(CatalogWriter.render(saved)).systems.first()
            assertEquals("${sys.id}: guardarla sin cambios la cambio", sys, back)
        }
    }

    @Test fun `los campos que el catalogo no trae tambien se conservan`() {
        val full = SystemDef(
            id = "prueba",
            name = "Prueba \"con comillas\"",
            label = "PRUEBA",
            raName = "Fabricante - Consola",
            raCore = "nucleo",
            zipOk = false,
            extensions = setOf("abc", "de"),
            aliases = setOf("otra", "mas"),
            // Con saltos de linea: la descripcion se escribe en una pagina de varias lineas.
            description = "Una frase con \\ y \"comillas\".\nY otra linea,\ttabulada.",
            image = "systems/prueba.png",
            video = "systems/prueba.mp4",
            videoSnaps = "coleccion-de-prueba",
            specs = listOf("CPU" to "8 bits", "RAM" to "2 KB"),
            maker = "Alguien",
            year = 1983,
        )
        assertEquals(full, Catalog.parse(CatalogWriter.render(full)).systems.first())
    }
}
