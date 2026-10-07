package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameDbTest {

    private val cols = "keys\tname\tgenre.wd\tgenre.lr\tdate\tdev\tpub\tseries\tfame\tqid\tsrc\thow"

    private fun pack(id: String, vararg rows: String) =
        GameDb.parsePack(sequenceOf("#ludolog-games\t1\t$id\t2026-09-25", cols, *rows))!!

    private val snes = pack(
        "snes",
        "c:a31e3d42\tTerranigma (Europe) (En,Fr,De,Es)\taction role-playing game\tAction\t1995-10-20\tQuintet\tNintendo\t\t21\tQ1137005\twww-\tt",
        "c:12345678\tSuper Metroid (Japan, USA) (En,Ja)\tmetroidvania|action-adventure game\tAdventure\t1994-03-19\tNintendo R&D1|Intelligent Systems\tNintendo\tMetroid\t40\tQ1332595\twwww\tt",
        "c:87654321\tSuper Metroid (Europe) (En,Fr,De)\tmetroidvania\tAdventure\t1994\t\t\t\t40\tQ1332595\twl--\tv",
        "\tSeiken Densetsu 3\taction role-playing game\t\t1995\tSquare\tSquare\tMana\t30\tQ1322541\twwww\tw",
    )
    private val megadrive = pack(
        "megadrive",
        "c:de2df7d3\tEarthworm Jim (USA)\tplatformer\tPlatform\t1994-10\tShiny Entertainment\tPlaymates\tEarthworm Jim\t25\tQ1142435\twwww\tt",
    )

    @Test fun `el paquete se lee por el nombre de sus columnas`() {
        assertEquals("snes", snes.id)
        assertEquals("2026-09-25", snes.version)
        assertEquals(4, snes.rows.size)
        val t = snes.rows.first()
        assertEquals(listOf("c:a31e3d42"), t.keys)
        assertEquals("action role-playing game", t.genreWd)
        assertEquals(21, t.fame)
        // Con las columnas en otro orden y una de mas, lo mismo.
        val shuffled = GameDb.parsePack(sequenceOf(
            "#ludolog-games\t1\tx\tv", "extra\tname\tkeys\tfame", "?\tFoo (USA)\tc:00000001\t3",
        ))!!
        assertEquals("Foo (USA)", shuffled.rows.single().name)
        assertEquals(listOf("c:00000001"), shuffled.rows.single().keys)
        assertEquals(3, shuffled.rows.single().fame)
    }

    @Test fun `los nombres para el arte y el ID de GameTDB`() {
        val p = GameDb.parsePack(sequenceOf(
            "#ludolog-games\t1\tgba\tv", "keys\tname\tgt\talt\tlabel",
            "c:8c4d3108\tPokemon - Edicion Esmeralda (Spain)\t\tPokemon - Emerald Version (USA, Europe)|Pokemon - Smaragd-Edition (Germany)\tPokémon Emerald",
            "i:R3ME\tMetroid Prime Trilogy (USA)\tR3ME01\t\t",
        ))!!
        val (es, mp) = p.rows
        assertEquals(listOf("Pokemon - Emerald Version (USA, Europe)", "Pokemon - Smaragd-Edition (Germany)"), es.alt)
        assertEquals("Pokémon Emerald", es.label)
        assertEquals("", es.gt)
        assertEquals("R3ME01", mp.gt)
        assertEquals(emptyList<String>(), mp.alt)
        // Un catalogo de antes, sin esas columnas: vacias.
        assertEquals("", snes.rows.first().gt)
        assertEquals(emptyList<String>(), snes.rows.first().alt)
    }

    @Test fun `la caratula de GameTDB, primero la de su region`() {
        assertEquals(
            listOf("US", "EN", "JA").map { "https://art.gametdb.com/wii/cover/$it/R3ME01.png" },
            GameTdbSource.urls("wii", "R3ME01"),
        )
        assertEquals("https://art.gametdb.com/ps3/coverM/EN/BCES00510.jpg", GameTdbSource.urls("ps3", "BCES00510").first())
        assertEquals("https://art.gametdb.com/ps3/coverM/ZH/BCAS25003.jpg", GameTdbSource.urls("ps3", "BCAS25003").first())
        assertEquals("https://art.gametdb.com/ds/coverM/EN/IPKP.jpg", GameTdbSource.urls("ds", "IPKP").first())
        assertEquals("https://art.gametdb.com/3ds/coverM/JA/BPEJ.jpg", GameTdbSource.urls("3ds", "BPEJ").first())
        // Switch no dice su region: la que mas tiene primero.
        assertEquals(3, GameTdbSource.urls("switch", "ANKSA").size)
        assertEquals("https://art.gametdb.com/switch/coverM/US/ANKSA.jpg", GameTdbSource.urls("switch", "ANKSA").first())
    }

    @Test fun `por la clave del fichero, seguro`() {
        val hit = GameDb.find(listOf(snes), listOf("r:terranigma", "c:a31e3d42"), "Terranigma.zip", "Terranigma")!!
        assertEquals("Terranigma (Europe) (En,Fr,De,Es)", hit.row.name)
        assertEquals("crc", hit.how)
        assertTrue(hit.exact)
    }

    @Test fun `el juego de otra consola se encuentra en su paquete`() {
        // Los Earthworm Jim de la carpeta de 32X: el paquete que casa dice de que consola es.
        val hit = GameDb.find(listOf(pack("sega32x"), megadrive), listOf("c:de2df7d3"), "Earthworm Jim.zip", "Earthworm Jim")!!
        assertEquals("megadrive", hit.pack)
    }

    @Test fun `por el nombre exacto de la base`() {
        val hit = GameDb.find(listOf(snes), emptyList(), "Super Metroid (Europe) (En,Fr,De).sfc", "Super Metroid")!!
        assertEquals("name", hit.how)
        assertTrue(hit.exact)
        assertEquals("Super Metroid (Europe) (En,Fr,De)", hit.row.name)
    }

    @Test fun `por el titulo, con la region del nombre, y no es seguro`() {
        val eu = GameDb.find(listOf(snes), emptyList(), "Super Metroid (EU).sfc", "Super Metroid")!!
        assertEquals("title", eu.how)
        assertFalse(eu.exact)
        val us = GameDb.find(listOf(snes), emptyList(), "Super Metroid (USA) [!].sfc", "Super Metroid")!!
        assertEquals("Super Metroid (Japan, USA) (En,Ja)", us.row.name)
        // Una fila sin claves, que solo conoce Wikidata, se encuentra por el titulo.
        assertEquals("Q1322541", GameDb.find(listOf(snes), emptyList(), "Seiken Densetsu 3 [T-En].sfc", "Seiken Densetsu 3")!!.row.qid)
        assertNull(GameDb.find(listOf(snes), emptyList(), "Nada.sfc", "Nada"))
    }

    @Test fun `norm da lo mismo que el generador del catalogo`() {
        val f = File("src/test/resources/norm-cases.tsv")
        assertTrue("no encuentro ${f.absolutePath}", f.isFile)
        val cases = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertTrue(cases.size > 30)
        for ((title, baseNorm, fullNorm) in cases) {
            assertEquals(title, baseNorm, GameDb.norm(GameDb.base(title)))
            assertEquals(title, fullNorm, GameDb.norm(title))
        }
    }

    @Test fun `region e idiomas del nombre`() {
        assertEquals("eur", GameDb.regionOf("Terranigma (Europe) (En,Fr,De,Es)"))
        assertEquals("usa", GameDb.regionOf("Super Metroid (Japan, USA)"))
        assertEquals("jpn", GameDb.regionOf("Seiken Densetsu 3 (Japan)"))
        assertEquals(listOf("En", "Fr", "De", "Es"), GameDb.languagesOf("Terranigma (Europe) (En,Fr,De,Es)"))
        assertEquals(emptyList<String>(), GameDb.languagesOf("Earthworm Jim (USA)"))
    }

    @Test fun `el indice dice que fichero sirve a que consolas`() {
        val idx = GameDb.parseIndex(
            "#ludolog-catalog\t1\t2026-09-25\nfile\tpack\tsystems\tkind\tbytes\tsha1\trows\n" +
                "megadrive.tsv.gz\tmegadrive\tmegadrive,sega32x\tgames\t79000\tabc\t3365\n" +
                "megadrive.text.tsv.gz\tmegadrive\tmegadrive,sega32x\ttext\t50000\tdef\t900\n" +
                "../evil.tsv.gz\tx\tx\tgames\t1\tx\t1\n",
        )
        assertEquals(2, idx.size)
        assertEquals(listOf("megadrive", "sega32x"), idx.first().systems)
        assertEquals(129000L, GameDb.sizeFor(idx, setOf("sega32x")))
        assertEquals(0L, GameDb.sizeFor(idx, setOf("snes")))
    }

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    private fun gz(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    /** Un catalogo publicado de mentira, con SNES y PSX, en [dir]. */
    private fun publish(dir: File): Pair<ByteArray, ByteArray> {
        val snesGz = gz("#ludolog-games\t1\tsnes\t2026-09-25\n$cols\nc:a31e3d42\tTerranigma (Europe)\taction role-playing game\t\t1995\t\t\t\t21\tQ1\twww-\tt\n")
        val psxGz = gz("#ludolog-games\t1\tpsx\t2026-09-25\n$cols\ns:SLUS00820\tEcho Night (USA)\tsurvival horror\t\t1998\t\t\t\t5\tQ2\tw---\tt\n")
        File(dir, "snes.tsv.gz").writeBytes(snesGz)
        File(dir, "psx.tsv.gz").writeBytes(psxGz)
        File(dir, "index.tsv").writeText(
            "#ludolog-catalog\t1\t2026-09-25\nfile\tpack\tsystems\tkind\tbytes\tsha1\trows\n" +
                "snes.tsv.gz\tsnes\tsnes\tgames\t${snesGz.size}\t${GameDb.sha1(snesGz)}\t1\n" +
                "psx.tsv.gz\tpsx\tpsx\tgames\t${psxGz.size}\t${GameDb.sha1(psxGz)}\t1\n",
        )
        return snesGz to psxGz
    }

    @Test fun `baja solo lo de sus consolas, y una vez`() {
        val pub = tmp.newFolder("pub")
        val local = tmp.newFolder("local")
        publish(pub)
        assertTrue(GameDb.missingFor(setOf("snes"), local))
        assertEquals(1, GameDb.update(pub.path, setOf("snes"), local).getOrThrow())
        assertEquals(listOf("snes.tsv.gz"), GameDb.installed(local).map { it.file })
        assertEquals("2026-09-25", GameDb.version(local))
        assertFalse(GameDb.missingFor(setOf("snes"), local))
        // Lo que no se pidio sigue faltando, y no se bajo.
        assertTrue(GameDb.missingFor(setOf("psx"), local))
        assertFalse(File(local, "psx.tsv.gz").exists())
        // Otra vez, nada que bajar.
        assertEquals(0, GameDb.update(pub.path, setOf("snes"), local).getOrThrow())
        assertEquals("Terranigma (Europe)", GameDb.packsFor("snes", local).single().rows.single().name)
        GameDb.forget()
    }

    @Test fun `un fichero que llega roto no se queda`() {
        val pub = tmp.newFolder("pub")
        val local = tmp.newFolder("local")
        publish(pub)
        GameDb.update(pub.path, setOf("snes"), local).getOrThrow()
        // Publicado de nuevo, pero el fichero no es el que dice el indice.
        val index = File(pub, "index.tsv")
        index.writeText(index.readText().replace(Regex("snes\\.tsv\\.gz\tsnes\tsnes\tgames\t(\\d+)\t[0-9a-f]+"), "snes.tsv.gz\tsnes\tsnes\tgames\t$1\t" + "0".repeat(40)))
        assertTrue(GameDb.update(pub.path, setOf("snes"), local).isFailure)
        assertFalse(File(local, "snes.tsv.gz.part").exists() && File(local, "snes.tsv.gz.part").length() > 0 && GameDb.installed(local).none { it.file == "snes.tsv.gz" })
        GameDb.forget()
    }

    @Test fun `tambien por la red`() {
        val pub = tmp.newFolder("pub")
        val local = tmp.newFolder("local")
        publish(pub)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val f = File(pub, ex.requestURI.path.substringAfterLast('/'))
            if (f.isFile) { val b = f.readBytes(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            else { ex.sendResponseHeaders(404, -1); ex.close() }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/releases/latest/download"
            assertEquals(2, GameDb.update(url, setOf("snes", "psx"), local).getOrThrow())
            assertEquals(setOf("snes.tsv.gz", "psx.tsv.gz"), GameDb.installed(local).map { it.file }.toSet())
        } finally {
            server.stop(0)
            GameDb.forget()
        }
    }

    @Test fun `las sinopsis por QID`() {
        val t = GameDb.parseTexts(sequenceOf(
            "#ludolog-text\t1\tsnes\tv", "key\ttitle\ttext\tsrc",
            "Q1137005\tTerranigma\tTerranigma is an action role-playing game...\twp",
            "gt:RMCE01\tMario Kart Wii\tRace with Mario...\tgt",
            // Uno de antes, sin la columna de la fuente: es de Wikipedia.
            "Q2\tOld\tA file from before",
        ))
        assertNotNull(t["Q1137005"])
        assertEquals("Terranigma", t["Q1137005"]!!.title)
        assertEquals("gt", t["gt:RMCE01"]!!.source)
        assertEquals("wp", t["Q2"]!!.source)
    }

    @Test fun `las sugerencias del renombrado casan por palabras, sin region y los famosos antes`() {
        val rows = listOf(
            "#ludolog-games	1	mame	v",
            "keys	name	fame	nt",
            "r:mslug	Metal Slug - Super Vehicle-001 (World)	40	metal slug super vehicle 001",
            "r:mslugj	Metal Slug - Super Vehicle-001 (Japan)	40	metal slug super vehicle 001",
            "r:mslug2	Metal Slug 2 - Super Vehicle-001-II	30	metal slug 2 super vehicle 001 2",
            "r:mslugx	Metal Slug X - Super Vehicle-001	35	metal slug x super vehicle 001",
            "r:sam	Samurai Shodown	20	samurai shodown",
            // Sin columna nt en la fila: se reduce aqui.
            "r:heavy	Heavy Metal Slugger	1	",
        )
        val pack = GameDb.parsePack(rows.asSequence())!!
        val got = pack.search("metal slug", 10)
        // Los siete volcados de uno son un nombre; los que empiezan asi, primero y por fama.
        assertEquals("Metal Slug - Super Vehicle-001", got.first())
        assertEquals(1, got.count { it.startsWith("Metal Slug - Super") })
        assertTrue("Heavy Metal Slugger" in got)
        assertEquals("Heavy Metal Slugger", got.last())
        assertTrue(pack.search("samu", 5).single() == "Samurai Shodown")
        assertTrue(pack.search("zzzz", 5).isEmpty())
    }
}
