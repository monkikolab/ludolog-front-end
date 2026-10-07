package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun publish(dir: File, id: String, bytes: ByteArray): ThemeStore.Offer {
        File(dir, "$id.zip").writeBytes(bytes)
        File(dir, "themes.tsv").writeText(
            "#ludolog-themes\t1\t2026-09-25\nfile\ttheme\ttitle\tbytes\tsha1\n" +
                "$id.zip\t$id\tGothic\t${bytes.size}\t${GameDb.sha1(bytes)}\n",
        )
        return ThemeStore.remoteIndex(dir.path).getOrThrow().single()
    }

    @Test fun `baja, comprueba y deja el tema en su carpeta`() {
        val pub = tmp.newFolder("pub")
        val themes = tmp.newFolder("themes")
        val offer = publish(pub, "parlour", zip(mapOf("tvs.toml" to "[x]", "sounds/move.ogg" to "ogg", "media/systems/snes.mp4" to "mp4")))
        assertEquals("parlour", offer.id)
        assertFalse(ThemeStore.installed("parlour", themes))
        ThemeStore.install(pub.path, offer, themes).getOrThrow()
        assertTrue(ThemeStore.installed("parlour", themes))
        assertEquals("ogg", File(themes, "parlour/sounds/move.ogg").readText())
        assertFalse(File(themes, "parlour.part").exists())
    }

    @Test fun `un tema que ya esta no se pisa`() {
        val pub = tmp.newFolder("pub")
        val themes = tmp.newFolder("themes")
        File(themes, "parlour/sounds").mkdirs()
        File(themes, "parlour/sounds/move.ogg").writeText("mine")
        val offer = publish(pub, "parlour", zip(mapOf("sounds/move.ogg" to "theirs")))
        ThemeStore.install(pub.path, offer, themes).getOrThrow()
        assertEquals("mine", File(themes, "parlour/sounds/move.ogg").readText())
    }

    @Test fun `un zip que se sale de la carpeta no se escribe`() {
        val pub = tmp.newFolder("pub")
        val themes = tmp.newFolder("themes")
        val offer = publish(pub, "parlour", zip(mapOf("../evil.txt" to "x")))
        assertTrue(ThemeStore.install(pub.path, offer, themes).isFailure)
        assertFalse(File(themes, "evil.txt").exists())
        assertFalse(ThemeStore.installed("parlour", themes))
    }

    @Test fun `un zip roto no se instala`() {
        val pub = tmp.newFolder("pub")
        val themes = tmp.newFolder("themes")
        val offer = publish(pub, "parlour", zip(mapOf("tvs.toml" to "x")))
        File(pub, "parlour.zip").writeBytes(zip(mapOf("tvs.toml" to "otro")))
        assertTrue(ThemeStore.install(pub.path, offer, themes).isFailure)
        assertFalse(ThemeStore.installed("parlour", themes))
    }
}
