package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El .toml de ejemplo tiene que cargar tal cual, o no sirve de ejemplo.
 *
 * Empezó apuntando a Atari Jaguar, que ya estaba en el catálogo: el merge la reemplazaba
 * en vez de añadirla y el ejemplo no demostraba nada. Este test existe para que eso no
 * vuelva a pasar sin que nadie se entere.
 */
class ExampleTomlTest {

    @Test fun `el toml de ejemplo anade una consola que no estaba`() {
        val f = File("../docs/examples/game-and-watch.toml")
        assertTrue("no encuentro ${f.absolutePath}", f.isFile)

        val base = Catalog.parse(File("src/main/assets/systems.toml").readText())
        assertTrue(
            "el ejemplo tiene que ser una consola que NO esté ya",
            base.byId["gameandwatch"] == null,
        )

        val overlay = Catalog.parse(f.readText())
        assertEquals(1, overlay.systems.size)

        val gw = overlay.systems.first()
        assertEquals("gameandwatch", gw.id)
        assertEquals("Handheld Electronic Game", gw.raName)
        assertTrue("mgw" in gw.extensions)
        assertEquals("systems/gameandwatch.png", gw.image)

        val merged = Catalog.merge(base, overlay)
        assertEquals(base.systems.size + 1, merged.systems.size)
        // Y se reconoce por cualquiera de sus grafías.
        listOf("Game & Watch", "gameandwatch", "G&W", "Handheld Electronic Game").forEach {
            assertEquals("fallo con '$it'", "gameandwatch", merged.forFolder(it)?.id)
        }
    }
}
