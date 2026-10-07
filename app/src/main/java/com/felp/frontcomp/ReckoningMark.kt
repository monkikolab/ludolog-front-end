package com.felp.frontcomp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * El emblema del cuaderno como silueta, dibujado con trazados y no con pixeles.
 *
 * Antes era un mapa de bits de veinte por dieciseis, y a ese tamano el libro era un borron: no
 * hay veinte columnas que repartir entre una tapa, un canto y un ojo. Con trazados, el mismo
 * dibujo sale igual de limpio a veinte puntos que a doscientos, y quien lo pide decide el
 * tamano en vez de heredarlo de la rejilla.
 *
 * La pose es la del emblema que gira en la sala: el libro echado hacia atras y girado, visto
 * algo desde arriba. Quien haya visto uno reconoce el otro, que es para lo que sirve tener un
 * emblema y no dos dibujos del mismo tema.
 *
 * El ojo va en HUECO —restado de la tapa— y la pupila vuelve a sumarse dentro. Con una silueta
 * de un solo tono, una forma rellena dentro de otra se funden en una mancha; vaciada, la
 * pupila flota en el agujero y eso ya solo se puede leer como un ojo.
 */
object ReckoningMark {

    /** Lo que el libro se escora, en grados. */
    private const val TURN = 20.0

    /** La tapa, en fracciones del lado del cuadro. */
    private const val COVER_W = 0.29f
    private const val COVER_H = 0.40f

    /** Lo que el bloque de hojas asoma por detras, en los ejes de la tapa. */
    private const val LEAF = 0.055f

    /** El pelo de aire entre la tapa y el bloque de hojas. */
    private const val SEAM = 0.016f

    /** El ojo: medio ancho, medio alto y cuanto sube sobre el centro de la tapa. */
    private const val EYE_W = 0.20f
    private const val EYE_H = 0.098f
    private const val EYE_UP = 0.03f

    private const val PUPIL = 0.052f

    /**
     * El trazado dentro de un cuadro de `side` puntos.
     *
     * Todo se mide en fracciones del lado, asi que el dibujo es el mismo a cualquier tamano y
     * no hay una version para la tarjeta y otra para lo que venga despues.
     */
    fun path(side: Float): Path {
        val c = cos(Math.toRadians(TURN)).toFloat()
        val s = sin(Math.toRadians(TURN)).toFloat()
        val mid = side / 2f

        // De los ejes de la tapa a los del cuadro: girar y llevar al centro.
        fun px(u: Float, v: Float) = mid + (u * c - v * s) * side
        fun py(u: Float, v: Float) = mid + (u * s + v * c) * side

        fun quad(u0: Float, v0: Float, u1: Float, v1: Float): Path {
            val p = Path()
            p.moveTo(px(u0, v0), py(u0, v0))
            p.lineTo(px(u1, v0), py(u1, v0))
            p.lineTo(px(u1, v1), py(u1, v1))
            p.lineTo(px(u0, v1), py(u0, v1))
            p.close()
            return p
        }

        // La tapa, y detras el bloque de hojas asomando por dos lados.
        val book = quad(-COVER_W, -COVER_H, COVER_W, COVER_H)
        book.op(
            quad(-COVER_W + LEAF, -COVER_H + LEAF, COVER_W + LEAF, COVER_H + LEAF),
            Path.Op.UNION,
        )

        // Y una junta de un pelo entre la tapa y las hojas.
        //
        // Sin ella, la union de las dos es una sola mancha y el libro se lee como un
        // rectangulo girado: en una silueta de un tono no hay sombra que diga donde acaba una
        // pieza y empieza la otra. Con la junta, ese escalon de la derecha y de abajo pasa a
        // ser lo unico que dice que hay grosor detras, que es lo que hace que sea un libro.
        val seam = quad(COVER_W - SEAM, -COVER_H + LEAF, COVER_W, COVER_H)
        seam.op(quad(-COVER_W + LEAF, COVER_H - SEAM, COVER_W, COVER_H), Path.Op.UNION)
        book.op(seam, Path.Op.DIFFERENCE)

        // El ojo: dos arcos que se juntan en punta. Una elipse no vale —le faltan las dos
        // puntas de los extremos, que es de donde un ojo saca su forma.
        val eye = Path()
        val ecx = 0f
        val ecy = -EYE_UP
        eye.moveTo(px(-EYE_W, ecy), py(-EYE_W, ecy))
        eye.cubicTo(
            px(-EYE_W * 0.45f, ecy - EYE_H * 1.35f), py(-EYE_W * 0.45f, ecy - EYE_H * 1.35f),
            px(EYE_W * 0.45f, ecy - EYE_H * 1.35f), py(EYE_W * 0.45f, ecy - EYE_H * 1.35f),
            px(EYE_W, ecy), py(EYE_W, ecy),
        )
        eye.cubicTo(
            px(EYE_W * 0.45f, ecy + EYE_H * 1.35f), py(EYE_W * 0.45f, ecy + EYE_H * 1.35f),
            px(-EYE_W * 0.45f, ecy + EYE_H * 1.35f), py(-EYE_W * 0.45f, ecy + EYE_H * 1.35f),
            px(-EYE_W, ecy), py(-EYE_W, ecy),
        )
        eye.close()
        book.op(eye, Path.Op.DIFFERENCE)

        // Y la pupila, de vuelta dentro del hueco.
        val pupil = Path()
        val r = PUPIL * side
        pupil.addOval(
            RectF(
                px(ecx, ecy) - r, py(ecx, ecy) - r,
                px(ecx, ecy) + r, py(ecx, ecy) + r,
            ),
            Path.Direction.CW,
        )
        book.op(pupil, Path.Op.UNION)
        return book
    }

    /**
     * El emblema pintado en un mapa de bits cuadrado, para las vistas planas.
     *
     * La tarjeta que asoma encima del emulador se dibuja con vistas y no con Compose —cuelga
     * de un servicio, no de una actividad— asi que ahi hace falta un bitmap. Se pinta a pelo
     * con suavizado, que es justo lo contrario de lo que queria el dibujo de pixeles: aqui el
     * borde es una curva y se quiere limpia.
     */
    fun bitmap(side: Int, colour: Int): Bitmap {
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bmp.density = Bitmap.DENSITY_NONE
        Canvas(bmp).drawPath(
            path(side.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = colour
                style = Paint.Style.FILL
            },
        )
        return bmp
    }
}
