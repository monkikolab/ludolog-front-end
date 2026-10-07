package com.felp.frontcomp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * El emblema de los temas que no decoran: un rombo, y nada mas.
 *
 * Es el compañero de [ReckoningMark], que es el libro con el ojo. Dos marcas y no una porque
 * una marca no es un adorno que se pueda quitar: es lo que firma la tarjeta, y quitandola la
 * tarjeta podria ser de cualquier programa que se ponga encima de un juego. Lo que cambia con
 * el tema es QUE marca, no si la hay.
 *
 * Un libro con un ojo es una palabra de sala encantada. En un tema de lineas rectas la misma
 * firma tiene que decir lo mismo sin contar una historia, y para eso sirve una figura
 * geometrica: se reconoce de un vistazo, no se parece a nada mas en pantalla y no envejece.
 *
 * Va HUECO con un rombo macizo dentro, no relleno del todo. Un rombo lleno a veinte puntos es
 * un cuadrado girado —o una mancha— y podria ser el cursor de cualquier lista; el anillo con
 * su nucleo se lee como una piedra y eso ya solo puede ser una marca.
 */
object DiamondMark {

    /** El grosor del anillo, en fracciones del lado. */
    private const val RING = 0.085f

    /** Y el nucleo, sobre el mismo lado. */
    private const val CORE = 0.17f

    /** El aire alrededor, para que el rombo no toque el borde del mapa de bits. */
    private const val MARGIN = 0.10f

    fun path(side: Float): Path {
        val mid = side / 2f
        val r = mid * (1f - MARGIN * 2f)

        fun diamond(radius: Float): Path = Path().apply {
            moveTo(mid, mid - radius)
            lineTo(mid + radius, mid)
            lineTo(mid, mid + radius)
            lineTo(mid - radius, mid)
            close()
        }

        val mark = diamond(r)
        mark.op(diamond(r - RING * side), Path.Op.DIFFERENCE)
        mark.op(diamond(CORE * side), Path.Op.UNION)
        return mark
    }

    /**
     * Pintado en un mapa de bits cuadrado, para las vistas planas.
     *
     * La tarjeta que asoma encima del emulador se dibuja con vistas y no con Compose —cuelga
     * de un servicio, no de una actividad— asi que ahi hace falta un bitmap. Sin densidad
     * apuntada dentro, para que nadie lo reescale por el camino.
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
