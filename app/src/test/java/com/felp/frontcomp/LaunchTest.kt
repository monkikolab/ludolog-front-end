package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs against the shipped emulators.json, so breaking an intent template — a wrong
 * activity name, a lost extra key — fails the build instead of turning into a game that
 * silently refuses to start on the device.
 */
class LaunchTest {

    private val emulators: Emulators by lazy {
        val f = File("src/main/assets/emulators.toml")
        assertTrue("no encuentro ${f.absolutePath}", f.isFile)
        Emulators.parse(f.readText())
    }
    private val catalog: Catalog by lazy {
        Catalog.parse(File("src/main/assets/systems.toml").readText())
    }

    @Test fun `la tabla trae los emuladores esperados`() {
        assertEquals(42, emulators.defs.size)
        assertTrue(emulators.defs.all { it.pkg.isNotEmpty() })
    }

    @Test fun `cada emulador sabe como recibir el juego`() {
        emulators.defs.filterNot { it.isRetroArch }.forEach { def ->
            // Si el juego va en un extra, la clave del extra es obligatoria: sin ella el
            // emulador arranca pero se queda en su menu principal.
            if (def.hand == Hand.EXTRA_PATH || def.hand == Hand.EXTRA_URI || def.hand == Hand.FILE_INT ||
                def.hand == Hand.TITLE_ARGS
            ) {
                assertTrue("${def.id} usa ${def.hand} sin extraKey", def.extraKey.isNotEmpty())
            }
            if (def.hand == Hand.STORE_ID) {
                assertTrue("${def.id} usa STORE_ID sin idExtras", def.idExtras.isNotEmpty())
            }
        }
    }

    @Test fun `Vita3K y GameHub reciben el juego como lo leen`() {
        val vita = emulators.byId.getValue("vita3k")
        assertEquals(Hand.TITLE_ARGS, vita.hand)
        assertEquals("AppStartParameters", vita.extraKey)
        val hub = emulators.byId.getValue("gamehub")
        assertEquals(Hand.STORE_ID, hub.hand)
        assertEquals("steamAppId", hub.idExtras["steam"])
        assertEquals("localGameId", hub.idExtras["gamehub"])
        assertEquals(true, hub.boolExtras["autoStartGame"])
    }

    @Test fun `el numero de titulo de Vita sale del nombre o de la primera linea`() {
        val dir = kotlin.io.path.createTempDirectory("vita").toFile()
        try {
            assertEquals("PCSE00120", Launcher.vitaTitle(File(dir, "Persona 4 Golden (pcse00120).vpk")))
            val psvita = File(dir, "Persona 4 Golden.psvita").apply { writeText("PCSB00245\n") }
            assertEquals("PCSB00245", Launcher.vitaTitle(psvita))
            assertNull(Launcher.vitaTitle(File(dir, "Sin numero.vpk")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `los intents conocidos son los correctos`() {
        val duck = emulators.byId.getValue("duckstation")
        assertEquals("com.github.stenzek.duckstation", duck.pkg)
        assertEquals("com.github.stenzek.duckstation.EmulationActivity", duck.activity)
        assertEquals(Hand.EXTRA_PATH, duck.hand)
        assertEquals("bootPath", duck.extraKey)

        val ppsspp = emulators.byId.getValue("ppsspp")
        assertEquals(Hand.DATA_URI, ppsspp.hand)
        assertEquals("android.intent.action.VIEW", ppsspp.action)

        val melon = emulators.byId.getValue("melonds")
        assertEquals("me.magnum.melonds.LAUNCH_ROM", melon.action)
        assertEquals("uri", melon.extraKey)

        // Visto en una consola de pruebas: con el fichero por URI, GameNative abria su biblioteca y ya.
        val gn = emulators.byId.getValue("gamenative")
        assertEquals("app.gamenative.LAUNCH_GAME", gn.action)
        assertEquals(Hand.FILE_INT, gn.hand)
        assertEquals("app_id", gn.extraKey)
        assertEquals("game_source", gn.sourceExtra)
    }

    @Test fun `un fichero de tienda da su numero, y uno que no lo es no da nada`() {
        val dir = kotlin.io.path.createTempDirectory("store").toFile()
        try {
            fun id(text: String) = File(dir, "g.steam").apply { writeText(text) }.let(Launcher::storeId)
            assertEquals(1367590, id("1367590"))
            assertEquals(1367590, id(" 1367590 \n"))       // con espacios y salto de linea
            assertEquals(2552450, id("2552450\r\nresto"))  // solo cuenta la primera linea
            assertEquals(null, id(""))
            assertEquals(null, id("[Desktop Entry]"))       // un .desktop no es un numero
            assertEquals(null, id("0"))
            assertEquals(null, Launcher.storeId(File(dir, "no-existe.steam")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `los forks se resuelven por el mismo emulador`() {
        val dolphin = emulators.byId.getValue("dolphin")
        assertTrue("org.dolphinemu.handheld" in dolphin.packages())
        assertEquals("org.dolphinemu.dolphinemu", dolphin.packages().first())
    }

    @Test fun `retroarch construye la ruta del core`() {
        val ra = emulators.byId.getValue("retroarch")
        assertTrue(ra.isRetroArch)
        assertTrue("{core}" in ra.corePath && "{pkg}" in ra.corePath)
        assertEquals("ROM", ra.romExtra)
        assertEquals("LIBRETRO", ra.coreExtra)
        assertTrue("com.retroarch" in ra.packages())
    }

    @Test fun `los sistemas con core ofrecen retroarch como alternativa`() {
        catalog.systems.filter { it.raCore.isNotEmpty() }.forEach { sys ->
            val ids = emulators.forSystem(sys.id).map { it.id }
            assertTrue("${sys.id} no ofrece retroarch", "retroarch" in ids)
        }
    }

    @Test fun `el emulador dedicado va antes que retroarch`() {
        // RetroArch es el comodin: sirve para casi todo, pero cuando hay un emulador
        // especifico instalado ese suele ir mejor, asi que se propone primero.
        listOf("psx" to "duckstation", "psp" to "ppsspp", "nds" to "drastic").forEach { (sys, first) ->
            val ids = emulators.forSystem(sys).map { it.id }
            assertEquals("orden equivocado en $sys", first, ids.first())
            assertEquals("retroarch debería cerrar $sys", "retroarch", ids.last())
        }
    }

    @Test fun `gamecube y wii van por dolphin`() {
        listOf("gamecube", "wii").forEach { sys ->
            assertNotNull(emulators.forSystem(sys).firstOrNull { it.id == "dolphin" })
        }
    }

    @Test fun `cada fichero de tienda lleva el nombre de su tienda para GameNative`() {
        assertEquals("STEAM", Launcher.STORE_SOURCES["steam"])
        assertEquals("CUSTOM_GAME", Launcher.STORE_SOURCES["pcgame"])
        // Uno de una tienda que GameNative no conoce no es suyo: va al siguiente lanzador.
        assertNull(Launcher.STORE_SOURCES["gamehub"])
        assertNull(Launcher.STORE_SOURCES["desktop"])
    }

    @Test fun `un nucleo escrito a mano se queda en el nombre que pide la ruta`() {
        assertEquals("snes9x", RetroCores.clean("snes9x_libretro_android.so"))
        assertEquals("snes9x", RetroCores.clean(" Snes9x "))
        assertEquals("mesen-s", RetroCores.clean("mesen-s_libretro.so"))
        assertEquals(
            "mupen64plus_next_gles3",
            RetroCores.clean("/data/data/com.retroarch.aarch64/cores/mupen64plus_next_gles3_libretro_android.so"),
        )
        assertEquals("", RetroCores.clean("   "))
    }

    @Test fun `retroarch se ensena con su nucleo`() {
        val l = Launcher(emulators, catalog)
        val ra = emulators.byId.getValue("retroarch")
        assertEquals("Snes9x (RetroArch)", l.labelFor(ra, "snes"))
        assertEquals("FinalBurn Neo (RetroArch)", l.labelFor(ra, "arcade"))
        assertEquals("RetroArch, no core", l.labelFor(ra, "no-such-console"))
        val duck = emulators.byId.getValue("duckstation")
        assertEquals(duck.label, l.labelFor(duck, "psx"))
        // Cada nucleo del catalogo con el nombre que le da RetroArch, no el de su fichero. Los
        // de esta lista se llaman igual en los dos sitios.
        val same = setOf("a5200", "b2", "vecx")
        catalog.systems.map { it.raCore }.filter { it.isNotEmpty() && it !in same }.forEach { core ->
            assertTrue("$core sin nombre de RetroArch", RetroCores.name(core) != core)
        }
    }
}
