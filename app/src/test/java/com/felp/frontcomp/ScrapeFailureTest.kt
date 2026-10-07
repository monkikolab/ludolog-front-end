package com.felp.frontcomp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * Lo que el informe del scraper dice cuando no pudo preguntar, contra un servidor de verdad en
 * la propia maquina: sin red y con el servidor caido es un fallo que se arregla volviendo a
 * probar, y no un «no la tiene» que manda a renombrar ficheros que estaban bien.
 */
class ScrapeFailureTest {

    private val catalog = Catalog.parse(File("src/main/assets/systems.toml").readText())
    private val game = Game("/roms/snes/Super Metroid (USA).sfc", "snes", "Super Metroid", "usa")

    private fun miss(base: String): ScrapeMiss = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("scrape").toFile()
        try {
            val scraper = ArtScraper(
                catalog = catalog,
                prefs = null,
                sources = listOf(LibretroSource(LibretroThumbnails(base = base, cacheDir = tmp))),
                mediaRoot = File(tmp, "media"),
                snaps = VideoSnaps(cacheDir = tmp),
            )
            scraper.run(listOf(game), existing = null).misses.single()
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Un servidor que contesta a todo con `code`. */
    private fun <T> answering(code: Int, body: (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> ex.sendResponseHeaders(code, -1); ex.close() }
        server.start()
        try {
            return body("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test fun `sin conexion es un fallo, no un nombre que no casa`() {
        // Un puerto cerrado en la propia maquina: se rechaza al momento, como sin red.
        val m = miss("http://127.0.0.1:1")
        assertEquals(ScrapeOutcome.FAILED, m.outcome)
        assertTrue(m.detail, "could not get its list of names (no connection)" in m.detail)
    }

    @Test fun `con el servidor caido tambien`() {
        val m = answering(503) { miss(it) }
        assertEquals(ScrapeOutcome.FAILED, m.outcome)
        assertTrue(m.detail, "HTTP 503" in m.detail)
    }

    @Test fun `una consola que la coleccion no tiene sigue siendo sin fuente`() {
        val m = answering(404) { miss(it) }
        assertEquals(ScrapeOutcome.NO_SOURCE, m.outcome)
        assertTrue(m.detail, "has nothing for" in m.detail)
    }
}
