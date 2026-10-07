package com.felp.frontcomp

/**
 * Maps a rectangle onto the four corners of a screen seen at an angle.
 *
 * The television in the scene is not square to the camera, so the place its picture has to
 * go is a general quadrilateral, not a rectangle. Stretching a video into a bounding box
 * would leave it visibly sliding off the glass; what is needed is the same projective
 * transform the renderer used, applied in reverse.
 *
 * Only eight numbers describe it, and they are the four corners — which is what the render
 * measures and what a person can nudge by hand in a TOML file. The matrix is derived here
 * rather than stored, so the file stays readable.
 */
data class Homography(
    val a: Float, val b: Float, val c: Float,
    val d: Float, val e: Float, val f: Float,
    val g: Float, val h: Float, val i: Float,
) {
    /** Applies the transform to a point, dividing through by the homogeneous coordinate. */
    fun map(x: Float, y: Float): Pair<Float, Float> {
        val w = g * x + h * y + i
        if (w == 0f) return 0f to 0f
        return ((a * x + b * y + c) / w) to ((d * x + e * y + f) / w)
    }

    /**
     * The inverse, as the adjugate.
     *
     * The determinant is left out on purpose: a homography is only defined up to scale, and
     * dividing x, y and w by the same number changes nothing after the perspective divide.
     */
    fun inverse(): Homography = Homography(
        a = e * i - f * h, b = c * h - b * i, c = b * f - c * e,
        d = f * g - d * i, e = a * i - c * g, f = c * d - a * f,
        g = d * h - e * g, h = b * g - a * h, i = a * e - b * d,
    )

    /**
     * Por columnas, que es como lee las matrices un uniform de AGSL.
     *
     * Mandarla por filas no da ningun error: compila, se aplica y transpone la
     * transformacion en silencio, con lo que el video acaba muestreado de cualquier sitio
     * menos del que toca.
     */
    fun toShaderArray(): FloatArray = floatArrayOf(a, d, g, b, e, h, c, f, i)

    companion object {
        /**
         * The transform taking the unit square to the given quadrilateral.
         *
         * Corners go clockwise from the top left, which is the order the render writes
         * them. The construction is the standard one: solve for the two perspective terms
         * first from how far the quad is from a parallelogram, then the rest follows
         * directly.
         */
        fun squareTo(
            x0: Float, y0: Float,   // (0,0) arriba izquierda
            x1: Float, y1: Float,   // (1,0) arriba derecha
            x2: Float, y2: Float,   // (1,1) abajo derecha
            x3: Float, y3: Float,   // (0,1) abajo izquierda
        ): Homography {
            val sx = x0 - x1 + x2 - x3
            val sy = y0 - y1 + y2 - y3

            // Un cuadrilatero que ya es paralelogramo no tiene fuga: la transformacion es
            // afin y los dos terminos de perspectiva valen cero. Tratarlo aparte evita
            // dividir por un determinante nulo.
            if (kotlin.math.abs(sx) < 1e-7f && kotlin.math.abs(sy) < 1e-7f) {
                return Homography(
                    a = x1 - x0, b = x3 - x0, c = x0,
                    d = y1 - y0, e = y3 - y0, f = y0,
                    g = 0f, h = 0f, i = 1f,
                )
            }

            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val den = dx1 * dy2 - dx2 * dy1
            if (kotlin.math.abs(den) < 1e-9f) {
                // Cuadrilatero degenerado: se cae a algo que al menos no rompe la pantalla.
                return Homography(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }

            val g = (sx * dy2 - dx2 * sy) / den
            val h = (dx1 * sy - sx * dy1) / den
            return Homography(
                a = x1 - x0 + g * x1, b = x3 - x0 + h * x3, c = x0,
                d = y1 - y0 + g * y1, e = y3 - y0 + h * y3, f = y0,
                g = g, h = h, i = 1f,
            )
        }

        /** Desde la lista de ocho numeros que trae el TOML. */
        fun fromCorners(v: List<Float>): Homography? {
            if (v.size != 8) return null
            return squareTo(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
        }
    }
}
