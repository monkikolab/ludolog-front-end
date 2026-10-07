package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TvSkinTest {

    @Test
    fun `screen rect is read as x y width height`() {
        val skins = TvSkins.parse(
            """
            [[tv]]
            id = "set"
            name = "Set"
            image = "tv/set.png"
            aspect = 1.4
            screen = [0.10, 0.08, 0.70, 0.60]
            """.trimIndent()
        )
        val s = skins.single()
        assertEquals(0.10f, s.screen.left, 1e-4f)
        assertEquals(0.08f, s.screen.top, 1e-4f)
        // Lo que se escribe es ancho y alto, no los bordes: es lo que se mide en un editor.
        assertEquals(0.70f, s.screen.width, 1e-4f)
        assertEquals(0.60f, s.screen.height, 1e-4f)
        assertEquals(1.4f, s.aspect, 1e-4f)
    }

    @Test
    fun `a malformed screen falls back instead of collapsing the picture`() {
        val s = TvSkins.parse(
            """
            [[tv]]
            id = "half"
            screen = [0.1, 0.2]
            """.trimIndent()
        ).single()
        assertEquals(TvSkin.Drawn.screen, s.screen)
    }

    @Test
    fun `the shipped example parses and describes a real screen`() {
        val text = File("../docs/examples/tvs.toml").takeIf { it.isFile }
            ?: File("docs/examples/tvs.toml")
        val s = TvSkins.parse(text.readText()).single()
        assertEquals("portable-crt", s.id)
        assertTrue("la pantalla debe caber dentro de la imagen", s.screen.right <= 1f)
        assertTrue("la pantalla debe caber dentro de la imagen", s.screen.bottom <= 1f)
        assertTrue("el cristal va en su propio fichero", s.overlay.isNotEmpty())
    }
}
