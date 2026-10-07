package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RomFilesTest {

    private fun dir() = kotlin.io.path.createTempDirectory("roms").toFile()
    private fun game(f: File, discs: List<File> = emptyList()) =
        Game(f.path, "psx", f.nameWithoutExtension, discs = discs.map { it.path })

    @Test fun `un cue se lleva sus pistas y nada mas`() {
        val d = dir()
        File(d, "Tomb Raider (Track 01).bin").writeText("x")
        File(d, "Tomb Raider (Track 02).bin").writeText("x")
        File(d, "Otro juego.bin").writeText("x")
        val cue = File(d, "Tomb Raider.cue").apply {
            writeText("FILE \"Tomb Raider (Track 01).bin\" BINARY\n  TRACK 01 MODE2/2352\n" +
                "FILE \"Tomb Raider (Track 02).bin\" BINARY\n  TRACK 02 AUDIO\n")
        }
        val names = RomFiles.of(game(cue)).map { it.name }
        assertEquals(listOf("Tomb Raider.cue", "Tomb Raider (Track 01).bin", "Tomb Raider (Track 02).bin"), names)
        d.deleteRecursively()
    }

    @Test fun `un m3u se lleva sus discos y las pistas de cada uno`() {
        val d = dir()
        val sub = File(d, "discos").apply { mkdirs() }
        for (n in 1..2) {
            File(sub, "FF7 (Disc $n).bin").writeText("x")
            File(sub, "FF7 (Disc $n).cue").writeText("FILE \"FF7 (Disc $n).bin\" BINARY\n")
        }
        val m3u = File(d, "FF7.m3u").apply { writeText("# lista\ndiscos/FF7 (Disc 1).cue\ndiscos/FF7 (Disc 2).cue\n") }
        assertEquals(5, RomFiles.of(game(m3u)).size)
        d.deleteRecursively()
    }

    @Test fun `varios discos sin lista, todos`() {
        val d = dir()
        val discs = (1..3).map { File(d, "Parasite Eve (Disc $it).chd").apply { writeText("x") } }
        assertEquals(3, RomFiles.of(game(discs[0], discs)).size)
        d.deleteRecursively()
    }

    @Test fun `nada de fuera de la carpeta, y un perfil de DoomForge no se lleva el WAD`() {
        val d = dir()
        val fuera = File(d, "fuera.bin").apply { writeText("x") }
        val juegos = File(d, "juegos").apply { mkdirs() }
        val cue = File(juegos, "raro.cue").apply { writeText("FILE \"../fuera.bin\" BINARY\n") }
        assertEquals(listOf(cue), RomFiles.of(game(cue)))
        File(juegos, "DOOM.WAD").writeText("x")
        val perfil = File(juegos, "Doom + Brutal.doomforge").apply { writeText("{\"iwad\": \"DOOM.WAD\"}") }
        assertEquals(listOf(perfil), RomFiles.of(game(perfil)))
        assertTrue(fuera.exists())
        d.deleteRecursively()
    }

    @Test fun `borrar devuelve lo que no se pudo`() {
        val d = dir()
        val a = File(d, "a.sfc").apply { writeText("x") }
        assertTrue(RomFiles.delete(listOf(a)).isEmpty())
        assertFalse(a.exists())
        d.deleteRecursively()
    }
}
