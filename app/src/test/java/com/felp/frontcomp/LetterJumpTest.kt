package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Izquierda y derecha en las listas: de letra en letra y dando la vuelta. La lista de juegos es
 * la de PlayStation del aparato, tal cual sale ordenada.
 */
class LetterJumpTest {

    private val psx = listOf(
        "BLOOD OMEN - LEGACY OF KAIN", "CASTLEVANIA-SYMPHONY OF THE NIGHT", "ECHO NIGHT",
        "FINAL FANTASY VII", "FINAL FANTASY VII", "FINAL FANTASY VII",
        "MEDIEVIL", "MEDIEVIL II", "MEGA MAN X4",
    )

    @Test fun `derecha va a la primera de la letra siguiente`() {
        assertEquals(1, letterJump(psx, 0, 1))
        assertEquals(6, letterJump(psx, 3, 1))
        // Desde el medio de un tramo tambien.
        assertEquals(6, letterJump(psx, 4, 1))
    }

    @Test fun `izquierda va a la primera de la letra anterior`() {
        assertEquals(2, letterJump(psx, 4, -1))
        assertEquals(3, letterJump(psx, 7, -1))
    }

    @Test fun `en los extremos da la vuelta`() {
        // Derecha en la ultima letra vuelve a la primera, e izquierda en la primera a la ultima.
        assertEquals(0, letterJump(psx, 8, 1))
        assertEquals(6, letterJump(psx, 0, -1))
    }

    @Test fun `las cifras son un tramo y las tildes no separan`() {
        val list = listOf("007", "1942", "ALIEN", "ÉCHO", "ECHO")
        assertEquals(2, letterJump(list, 0, 1))
        assertEquals(3, letterJump(list, 2, 1))
        assertEquals(0, letterJump(list, 3, 1))
    }

    @Test fun `las consolas van por tramos, no por abecedario`() {
        val consoles = listOf("PLAYSTATION", "PLAYSTATION 2", "SUPER NINTENDO", "GAME BOY ADVANCE")
        assertEquals(2, letterJump(consoles, 0, 1))
        assertEquals(3, letterJump(consoles, 2, 1))
    }
}
