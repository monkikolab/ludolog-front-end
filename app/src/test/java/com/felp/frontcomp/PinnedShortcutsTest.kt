package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedShortcutsTest {

    @Test fun `se lee como se escribe, sin pisar otro del mismo nombre ni duplicar el mismo`() {
        val dir = kotlin.io.path.createTempDirectory("steam").toFile()
        val hk = PinnedShortcuts.Entry("gamehub.lite", "game_123", "Hollow Knight: Voidheart")
        val f = PinnedShortcuts.write(dir, hk)
        // Los dos puntos no valen en un nombre de fichero de la tarjeta.
        assertEquals("Hollow Knight Voidheart.shortcut", f.name)
        assertEquals(hk, PinnedShortcuts.read(f))
        // Otro juego con el mismo nombre: al lado, no encima.
        val other = PinnedShortcuts.write(dir, hk.copy(id = "game_456"))
        assertEquals("Hollow Knight Voidheart (2).shortcut", other.name)
        // El mismo acceso otra vez: el mismo fichero.
        assertEquals(f, PinnedShortcuts.write(dir, hk))
        assertEquals(2, dir.listFiles()!!.size)
        dir.deleteRecursively()
    }
}
