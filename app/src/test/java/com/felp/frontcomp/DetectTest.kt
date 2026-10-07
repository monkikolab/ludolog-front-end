package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * These run against the real shipped catalog, not a fixture, so a bad edit to
 * systems.json fails the build instead of shipping as a console that stops being detected.
 */
class DetectTest {

    private val catalog: Catalog by lazy {
        val f = File("src/main/assets/systems.toml")
        assertTrue("no encuentro ${f.absolutePath}", f.isFile)
        Catalog.parse(f.readText())
    }
    private val detector: Detector by lazy { Detector(catalog) }

    @Test fun `el catalogo trae los sistemas esperados`() {
        assertEquals(90, catalog.systems.size)
        assertTrue(catalog.systems.sumOf { it.aliases.size } > 250)
    }

    /**
     * La consola de Android no tiene extensiones, y eso es justo lo que la mantiene fuera
     * del escaneo: sus juegos son paquetes instalados, no ficheros. Si alguien le pone una
     * extension, el escaner empezaria a meterle ROMs y nadie sabria por que.
     */
    @Test fun `la consola de Android no captura ficheros`() {
        val android = catalog.byId["android"]
        assertTrue(android != null)
        assertTrue(android!!.extensions.isEmpty())
    }

    @Test fun `la clave normalizada colapsa todas las convenciones de carpeta`() {
        val expected = "supernintendoentertainmentsystem"
        assertEquals(expected, SystemDef.key("Nintendo - Super Nintendo Entertainment System")
            .removePrefix("nintendo"))
        assertEquals("pico8", SystemDef.key("PICO-8"))
        assertEquals("gameboyadvance", SystemDef.key("Game Boy Advance"))
        assertEquals("segamegadrivegenesis", SystemDef.key("Sega - Mega Drive - Genesis"))
    }

    @Test fun `reconoce una consola escrita de cualquier manera`() {
        val spellings = listOf(
            "SNES", "snes", "Super Nintendo", "superfamicom", "sfc",
            "Nintendo - Super Nintendo Entertainment System", "supernes",
        )
        spellings.forEach { s ->
            assertEquals("fallo con '$s'", "snes", catalog.forFolder(s)?.id)
        }
    }

    @Test fun `distingue consolas que comparten extension por la carpeta`() {
        assertEquals("gamecube", detector.systemForFile("/roms/GameCube/Zelda.iso")?.id)
        assertEquals("ps2", detector.systemForFile("/roms/PS2/Zelda.iso")?.id)
        assertEquals("psp", detector.systemForFile("/roms/PSP/Zelda.iso")?.id)
        // Sin carpeta que lo aclare, .iso es ambiguo y no se adivina.
        assertNull(detector.systemForFile("/descargas/Zelda.iso"))
    }

    @Test fun `una extension con un solo duenno no necesita carpeta`() {
        assertEquals("gba", detector.systemForFile("/lo-que-sea/Metroid.gba")?.id)
        assertEquals("n64", detector.systemForFile("/x/Mario.z64")?.id)
    }

    @Test fun `los cartuchos de PICO-8 no se confunden con imagenes`() {
        assertEquals("p8.png", Catalog.extensionOf("celeste.p8.png"))
        assertEquals("png", Catalog.extensionOf("caratula.png"))
        assertEquals("pico8", detector.systemForFile("/roms/pico8/celeste.p8.png")?.id)
    }

    @Test fun `sube por el arbol hasta encontrar la consola`() {
        assertEquals("snes", detector.systemForPath("/roms/Nintendo/SNES/Hacks")?.id)
    }

    @Test fun `separa el titulo de los corchetes y saca la region`() {
        val info = detector.describe("/roms/snes/Chrono Trigger (USA) [!].sfc")
        assertEquals("Chrono Trigger", info.title)
        assertEquals("usa", info.region)
        assertTrue("!" in info.tags)
    }

    @Test fun `los separadores vuelven a ser espacios pero los guiones se respetan`() {
        assertEquals("Super Mario World", detector.describe("/r/snes/Super_Mario_World.sfc").title)
        assertEquals("Spider-Man", detector.describe("/r/psx/Spider-Man (USA).cue").title)
        assertEquals("Super Mario World", detector.describe("/r/snes/Super.Mario.World (USA).sfc").title)
    }

    @Test fun `los puntos de un titulo con espacios son del titulo`() {
        // Salia «SUPER MARIO BROS» en una consola de pruebas.
        assertEquals("Super Mario Bros.", detector.describe("/r/nes/Super Mario Bros. (Europe) (Rev A).nes").title)
        assertEquals("Dr. Mario", detector.describe("/r/nes/Dr. Mario (World).nes").title)
    }

    @Test fun `la etiqueta del aparato no es parte del nombre`() {
        // Los nombres tal cual estan en la tarjeta de una consola de pruebas.
        fun t(file: String) = detector.describe(file).title
        assertEquals("God of War III", t("/r/ps3/God of War III_Odyn3.iso"))
        assertEquals("Blasphemous", t("/r/switch/Blasphemous [0100698009C6E000]+[v1.0.5+DLC]_Odyn3.xci"))
        assertEquals("SUPER MARIO ODYSSEY", t("/r/switch/SUPER MARIO ODYSSEY [0100000000010000] [v262144] (1G+1U)_Odyn3.xci"))
        assertEquals("Blasphemous 2", t("/r/switch/Blasphemous 2 [010040801A4C8000] [v393216] (1G+1U+1D)_RP5.xci"))
        // Lo que no lleva guion bajo delante, o esta en medio, se queda.
        assertEquals("KINGDOM HEARTS III + Re Mind", t("/r/steam/KINGDOM HEARTS III + Re Mind (DLC).steam"))
        assertEquals("Odin Sphere", t("/r/ps2/Odin Sphere (USA).iso"))
        assertEquals("Street Fighter EX3", t("/r/ps2/Street Fighter EX3 (USA).iso"))
    }

    @Test fun `el archivo de usuario anniade una consola sin tocar las demas`() {
        val overlay = Catalog.parse(
            """
            [[system]]
            id = "futurebox"
            name = "FutureBox"
            label = "FB"
            extensions = ["fbx"]
            aliases = ["future", "fbox"]
            """.trimIndent()
        )
        val merged = Catalog.merge(catalog, overlay)

        assertEquals(catalog.systems.size + 1, merged.systems.size)
        assertEquals("futurebox", merged.forFolder("FutureBox")?.id)
        assertEquals("futurebox", merged.forFolder("fbox")?.id)
        assertEquals("snes", merged.forFolder("SNES")?.id)   // lo anterior sigue vivo
        assertEquals(setOf("zip", "7z"), merged.zipExtensions)
    }

    @Test fun `el archivo de usuario puede corregir un sistema existente`() {
        val overlay = Catalog.parse(
            """
            [[system]]
            id = "snes"
            name = "Super Nintendo"
            label = "SNES"
            extensions = ["sfc", "smc", "bs"]
            aliases = ["snes"]
            """.trimIndent()
        )
        val merged = Catalog.merge(catalog, overlay)
        assertEquals(catalog.systems.size, merged.systems.size)
        assertTrue("bs" in merged.byId.getValue("snes").extensions)
    }

    @Test fun `el m3u se queda y sus discos desaparecen`() {
        val games = listOf(
            Game("/roms/psx/Final Fantasy VII.m3u", "psx", "Final Fantasy VII"),
            Game("/roms/psx/Final Fantasy VII (Disc 1).cue", "psx", "Final Fantasy VII"),
            Game("/roms/psx/Final Fantasy VII (Disc 2).cue", "psx", "Final Fantasy VII"),
            Game("/roms/psx/Vagrant Story.cue", "psx", "Vagrant Story"),
        )
        val kept = detector.collapseMultiDisc(games).map { it.fileName }
        assertTrue("Final Fantasy VII.m3u" in kept)
        assertTrue("Vagrant Story.cue" in kept)
        assertEquals(2, kept.size)
    }

    @Test fun `las pistas de un cue+bin no salen como juegos`() {
        val games = listOf(
            Game("/roms/psx/Tomb Raider (USA).cue", "psx", "Tomb Raider"),
            Game("/roms/psx/Tomb Raider (USA) (Track 01).bin", "psx", "Tomb Raider"),
            Game("/roms/psx/Tomb Raider (USA) (Track 02).bin", "psx", "Tomb Raider"),
            Game("/roms/psx/Crash Bandicoot (USA).cue", "psx", "Crash Bandicoot"),
            Game("/roms/psx/Crash Bandicoot (USA).bin", "psx", "Crash Bandicoot"),
            // Un .bin sin hoja al lado es un juego, y el de otra region tambien.
            Game("/roms/psx/Spyro (Europe).bin", "psx", "Spyro"),
            Game("/roms/psx/Tomb Raider (Europe).bin", "psx", "Tomb Raider"),
        )
        val kept = detector.collapseMultiDisc(games).map { it.fileName }.toSet()
        assertEquals(
            setOf(
                "Tomb Raider (USA).cue", "Crash Bandicoot (USA).cue",
                "Spyro (Europe).bin", "Tomb Raider (Europe).bin",
            ),
            kept,
        )
    }

    @Test fun `un archivo de otra consola en una carpeta con nombre no se la lleva`() {
        // md solo lo reclama Mega Drive, pero un README.md en la de PlayStation no es un juego.
        assertNull(detector.systemForFile("/roms/psx/README.md"))
        assertNull(detector.systemForFile("/roms/psvita/Game.pkg"))
        assertEquals("megadrive", detector.systemForFile("/roms/megadrive/Sonic.md")?.id)
        // Y los formatos de copiadora de SNES son de SNES en su carpeta.
        assertEquals("snes", detector.systemForFile("/roms/snes/Game.fig")?.id)
    }

    @Test fun `un toml del usuario sin defaults no toca las extensiones ambiguas`() {
        val overlay = Catalog.parse(
            """
            [[system]]
            id = "futurebox"
            name = "FutureBox"
            label = "FB"
            extensions = [".fbx"]
            """.trimIndent()
        )
        val merged = Catalog.merge(catalog, overlay)
        assertEquals(catalog.ambiguousExtensions, merged.ambiguousExtensions)
        assertTrue("elf" in merged.ambiguousExtensions)
        // Y la extension escrita con punto, como en ES-DE, casa igual.
        assertTrue("fbx" in merged.byId.getValue("futurebox").extensions)
    }

    @Test fun `las carpetas de ES-DE de MSX, Neo Geo CD, CD-i y Videopac se reconocen`() {
        assertEquals("msx", catalog.forFolder("msx2")?.id)
        assertEquals("msx", catalog.forFolder("msxturbor")?.id)
        assertEquals("neogeocd", catalog.forFolder("neogeocdjp")?.id)
        assertEquals("cdi", catalog.forFolder("cdimono1")?.id)
        assertEquals("odyssey2", catalog.forFolder("videopac")?.id)
    }

    @Test fun `los accesos directos app no se cuelan como juegos de 3DS`() {
        // ES-DE deja estos en el arbol de ROMs; ".app" solo la reclama 3DS, asi que sin
        // la regla de extension ambigua "Google Play Store" acababa siendo un juego.
        assertNull(detector.systemForFile("/roms/androidapps/Google Play Store.app"))
        assertNull(detector.systemForFile("/roms/androidgames/Warframe.app"))
        // Pero dentro de una carpeta que si nombra la consola, sigue valiendo.
        assertEquals("3ds", detector.systemForFile("/roms/3ds/Zelda.app")?.id)
    }

    @Test fun `una extension generica necesita que la carpeta lo confirme`() {
        assertNull(detector.systemForFile("/descargas/algo.bin"))
        assertEquals("atari2600", detector.systemForFile("/roms/atari2600/Pitfall.bin")?.id)
    }

    @Test fun `dos consolas distintas nunca comparten una grafia`() {
        // Al ampliar el catalogo desde otra fuente, un alias corto llego a fusionar
        // consolas que no tienen nada que ver: "pico" hizo que Sega PICO acabara dentro de
        // PICO-8. Una colision aqui significa que una carpeta se asignaria al sistema
        // equivocado, asi que se comprueba el catalogo entero.
        val owner = HashMap<String, String>()
        val clashes = mutableListOf<String>()
        catalog.systems.forEach { sys ->
            sys.keys().forEach { k ->
                val prev = owner.put(k, sys.id)
                if (prev != null && prev != sys.id) clashes += "$k: $prev vs ${sys.id}"
            }
        }
        assertTrue("grafías compartidas: $clashes", clashes.isEmpty())
    }

    @Test fun `ninguna extension de proposito general identifica un sistema`() {
        // Un ".json" o un ".exe" reclamado por una consola convertiria ficheros corrientes
        // —incluida la configuracion de la propia app— en juegos.
        val forbidden = setOf("json", "exe", "bat", "cmd", "txt", "xml", "html", "js", "pdf")
        catalog.systems.forEach { sys ->
            val bad = sys.extensions.intersect(forbidden)
            assertTrue("${sys.id} reclama $bad", bad.isEmpty())
        }
    }

    @Test fun `los discos sin m3u se juntan en un juego, y solo si cada uno es un disco distinto`() {
        val paths = listOf(
            "/roms/psx/Final Fantasy VII (USA) (Disc 2).chd",
            "/roms/psx/Final Fantasy VII (USA) (Disc 1).chd",
            "/roms/psx/Final Fantasy VII (USA) (Disc 3).chd",
            // Otra region: otro juego.
            "/roms/psx/Final Fantasy VII (Europe) (Disc 1).chd",
            // Dos «Disc 1» del mismo titulo: no se sabe cual es cual, se quedan sueltos.
            "/roms/psx/Parasite Eve (USA) (Disc 1).chd",
            "/roms/psx/Parasite Eve (USA) (Disc 1) (Rev 1).chd",
            "/roms/psx/Crash Bandicoot (USA).chd",
        )
        val kept = detector.collapseMultiDisc(paths.mapNotNull { detector.gameFor(it) })
        val ff7 = kept.single { it.title == "Final Fantasy VII" && it.region != null && "(USA)" in it.path }
        assertEquals(
            listOf("(Disc 1)", "(Disc 2)", "(Disc 3)"),
            ff7.discs.map { Regex("""\(Disc \d\)""").find(it)!!.value },
        )
        assertEquals(ff7.discs.first(), ff7.path)
        // Siete ficheros: los tres de FF7 (USA) son uno; los otros cuatro, sueltos.
        assertEquals(5, kept.size)
        assertTrue(kept.none { it.discs.isNotEmpty() && "Parasite" in it.title })
    }

    @Test fun `los perfiles de DoomForge salen cada uno, y juegan como su IWAD`() {
        val dir = kotlin.io.path.createTempDirectory("doom").toFile().resolve("ROMs/doom").apply { mkdirs() }
        fun profile(name: String, iwad: String, last: Long?) = File(dir, "$name.doomforge").apply {
            // Como los escribe DoomForge: JSON con las barras escapadas.
            val played = last?.let { ",\n  \"lastPlayed\": $it" } ?: ""
            writeText("""{
  "name": "$name",
  "iwad": "\/storage\/DoomForge\/iwads\/$iwad"$played
}""")
        }
        val files = listOf(
            profile("DOOM (v1.9) + Brutal EOA ts", "DOOM (v1.9).WAD", System.currentTimeMillis() + 60_000),
            profile("DOOM (v1.9)", "DOOM (v1.9).WAD", 100),
            profile("Vanilla", "DOOM.WAD", null),
            profile("Doom II - Hell on Earth", "DOOM2.WAD", 50),
            File(dir, "roto.doomforge").apply { writeText("no es json") },
        )
        val games = files.mapNotNull { detector.gameFor(it.path.replace(File.separatorChar, '/')) }
        assertEquals(5, games.size)
        val kept = detector.collapseMultiDisc(games).associateBy { it.fileName.substringBeforeLast('.') }
        // Los cinco en la lista, cada uno con su nombre: es lo que se elige al lanzar.
        assertEquals(5, kept.size)
        assertEquals("DOOM + Brutal EOA ts", kept.getValue("DOOM (v1.9) + Brutal EOA ts").title)
        // Y para el cuaderno, su IWAD: DOOM.WAD y DOOM (v1.9).WAD son el mismo Doom.
        assertEquals("Doom", kept.getValue("DOOM (v1.9) + Brutal EOA ts").playsAs)
        assertEquals("Doom", kept.getValue("DOOM (v1.9)").playsAs)
        assertEquals("Doom", kept.getValue("Vanilla").playsAs)
        assertEquals("Doom II: Hell on Earth", kept.getValue("Doom II - Hell on Earth").playsAs)
        // El que no se puede leer, como estaba.
        assertEquals(null, kept.getValue("roto").playsAs)
        dir.parentFile.parentFile.deleteRecursively()
    }
}
