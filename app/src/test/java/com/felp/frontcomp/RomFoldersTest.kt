package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La carpeta de ROMs que hace la bienvenida tiene que ser la de ES-DE, y cada subcarpeta tiene
 * que volver a leerse como su consola: una carpeta con un nombre que el escaner no reconoce es
 * una trampa, porque los juegos que se dejan ahi no salen nunca.
 */
class RomFoldersTest {

    private val catalog = Catalog.parse(File("src/main/assets/systems.toml").readText())

    @Test fun `los nombres son los de ES-DE`() {
        fun f(id: String) = RomFolders.folderFor(catalog.byId.getValue(id))
        assertEquals("gc", f("gamecube"))
        assertEquals("steam", f("pc"))
        assertEquals("psx", f("psx"))
        assertEquals("snes", f("snes"))
        assertEquals("arcade", f("arcade"))
        // Lo que ES-DE no tiene se queda con el id de aqui.
        assertEquals("futurebox", RomFolders.folderFor(SystemDef("futurebox", "Future Box", "FBOX", aliases = setOf("fbox"))))
    }

    @Test fun `cada carpeta se lee como su consola`() {
        for (sys in catalog.systems) {
            val name = RomFolders.folderFor(sys)
            assertEquals("${sys.id} va a «$name», que se lee como otra", sys.id, catalog.forFolder(name)?.id)
        }
    }

    @Test fun `hace las que faltan y no toca las que hay`() {
        val root = File(kotlin.io.path.createTempDirectory("roms").toFile(), "ROMs")
        try {
            // Una tarjeta a medias: PlayStation con otro nombre y un fichero suyo dentro.
            File(root, "playstation").mkdirs()
            File(root, "playstation/juego.chd").writeText("x")
            val made = RomFolders.create(root, catalog)
            assertTrue(made > 50)
            assertFalse("la de PlayStation ya estaba, con otro nombre", File(root, "psx").exists())
            assertTrue(File(root, "playstation/juego.chd").isFile)
            assertFalse("una carpeta que ya estaba no lleva systeminfo", File(root, "playstation/systeminfo.txt").exists())
            assertFalse("Android no tiene carpeta: sus juegos son apps", File(root, "android").exists())
            assertTrue(File(root, "gc").isDirectory)
            assertTrue(File(root, "gc/systeminfo.txt").readText().contains(".rvz"))
            // Y otra vez no hace nada.
            assertEquals(0, RomFolders.create(root, catalog))
            // Todas se leen como una consola.
            assertEquals(made + 1, RomFolders.consoles(root, catalog))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }
}
