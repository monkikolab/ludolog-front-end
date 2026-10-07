package com.felp.frontcomp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomographyTest {

    private fun assertPoint(ex: Float, ey: Float, got: Pair<Float, Float>, tol: Float = 1e-4f) {
        assertEquals(ex, got.first, tol)
        assertEquals(ey, got.second, tol)
    }

    @Test
    fun `the unit square maps to the four corners given`() {
        // Un trapecio cualquiera, claramente en perspectiva.
        val h = Homography.squareTo(
            0.20f, 0.10f,
            0.80f, 0.18f,
            0.86f, 0.70f,
            0.14f, 0.62f,
        )
        assertPoint(0.20f, 0.10f, h.map(0f, 0f))
        assertPoint(0.80f, 0.18f, h.map(1f, 0f))
        assertPoint(0.86f, 0.70f, h.map(1f, 1f))
        assertPoint(0.14f, 0.62f, h.map(0f, 1f))
    }

    @Test
    fun `the inverse takes the corners back to the square`() {
        val h = Homography.squareTo(
            0.5986f, 0.2708f,
            0.8277f, 0.2553f,
            0.8228f, 0.5386f,
            0.5972f, 0.5300f,
        )
        val inv = h.inverse()
        assertPoint(0f, 0f, inv.map(0.5986f, 0.2708f), 1e-3f)
        assertPoint(1f, 0f, inv.map(0.8277f, 0.2553f), 1e-3f)
        assertPoint(1f, 1f, inv.map(0.8228f, 0.5386f), 1e-3f)
        assertPoint(0f, 1f, inv.map(0.5972f, 0.5300f), 1e-3f)
    }

    @Test
    fun `a screen square to the camera stays affine`() {
        // Sin fuga, los dos terminos de perspectiva tienen que ser exactamente cero: si no,
        // una tele de frente saldria con la imagen ligeramente torcida.
        val h = Homography.squareTo(
            0.10f, 0.20f,
            0.60f, 0.20f,
            0.60f, 0.55f,
            0.10f, 0.55f,
        )
        assertEquals(0f, h.g, 1e-7f)
        assertEquals(0f, h.h, 1e-7f)
        assertPoint(0.35f, 0.375f, h.map(0.5f, 0.5f))
    }

    @Test
    fun `the centre of the quad is not the centre of its bounding box`() {
        // Es justo lo que se perderia usando un rectangulo: en perspectiva el centro de la
        // imagen NO cae en el centro geometrico del cuadrilatero.
        val h = Homography.squareTo(
            0.0f, 0.0f,
            1.0f, 0.2f,
            1.0f, 0.8f,
            0.0f, 0.4f,
        )
        val mid = h.map(0.5f, 0.5f)
        val boxMid = 0.5f to 0.4f
        assertTrue(
            "el centro proyectado deberia desviarse del centro de la caja",
            kotlin.math.abs(mid.second - boxMid.second) > 1e-3f,
        )
    }

    @Test
    fun `a degenerate quad does not blow up`() {
        val h = Homography.squareTo(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val p = h.map(0.5f, 0.5f)
        assertTrue(p.first.isFinite() && p.second.isFinite())
    }

    @Test
    fun `eight numbers from a file build the same transform`() {
        val v = listOf(0.1f, 0.1f, 0.9f, 0.15f, 0.88f, 0.6f, 0.12f, 0.55f)
        val h = Homography.fromCorners(v)!!
        assertPoint(0.9f, 0.15f, h.map(1f, 0f))
        // Una lista de otro tamano no es un cuadrilatero.
        assertEquals(null, Homography.fromCorners(v.dropLast(1)))
    }
}

class HomographyLayoutTest {
    @Test
    fun `the shader array is the transpose of the row-major one`() {
        // Los campos van por filas: 1 2 3 / 4 5 6 / 7 8 9.
        val h = Homography(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        // Por columnas, que es lo que espera AGSL. Mandar la otra no da error: transpone la
        // transformacion en silencio y el video se muestrea de donde no es.
        assertArrayEquals(
            floatArrayOf(1f, 4f, 7f, 2f, 5f, 8f, 3f, 6f, 9f), h.toShaderArray(), 0f,
        )
    }
}
