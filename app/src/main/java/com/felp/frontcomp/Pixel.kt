package com.felp.frontcomp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity

/*
 * Iconos de baja resolución, dibujados píxel a píxel.
 *
 * Un icono vectorial reducido y ampliado por vecino sale con los bordes rotos al azar, y
 * uno pensado píxel a píxel sale con los bordes rotos a propósito, que es distinto. Van a
 * la misma escala que el texto del diálogo de la sala (ReFont.kt), que es lo que hace que
 * la barra de arriba y el diálogo parezcan de la misma máquina.
 */

/** Un icono de mapa de bits: cada `#` es un píxel encendido. */
@Composable
fun PixelIcon(rows: List<String>, color: Color, scale: Int = 3, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val cols = rows.maxOf { it.length }
    val w = with(density) { (cols * scale).toDp() }
    val h = with(density) { (rows.size * scale).toDp() }
    Canvas(modifier.size(w, h)) {
        val s = scale.toFloat()
        for ((y, row) in rows.withIndex()) {
            for ((x, ch) in row.withIndex()) {
                if (ch == '#') {
                    drawRect(color, androidx.compose.ui.geometry.Offset(x * s, y * s),
                        androidx.compose.ui.geometry.Size(s, s))
                }
            }
        }
    }
}

/** Los iconos de la barra de arriba: lo que dice el estado del aparato sin una palabra. */
object PixelIcons {
    /** La lupa de la busqueda, al lado del engranaje. */
    val search = listOf(
        "..####.....",
        ".#....#....",
        "#......#...",
        "#......#...",
        "#......#...",
        "#......#...",
        ".#....#....",
        "..####.#...",
        ".......##..",
        "........##.",
        ".........##",
    )

    val gear = listOf(
        "....###....",
        "..#.###.#..",
        ".#########.",
        ".##.....##.",
        "###..#..###",
        "###.###.###",
        "###..#..###",
        ".##.....##.",
        ".#########.",
        "..#.###.#..",
        "....###....",
    )

    val wifi = listOf(
        ".#######.",
        "#.......#",
        "..#####..",
        ".#.....#.",
        "...###...",
        "..#...#..",
        "....#....",
    )

    val headphones = listOf(
        "..#####..",
        ".#.....#.",
        "#.......#",
        "#.......#",
        "##.....##",
        "##.....##",
        "##.....##",
    )

    /**
     * La runa de bluetooth: el palo vertical, los dos triangulos de la derecha y los dos
     * trazos que los cruzan por la izquierda.
     *
     * Nueve de alto y no siete como sus vecinos. A siete no hay sitio y los trazos de la
     * izquierda quedan sueltos: el dibujo se lee como un asterisco. Con nueve la fila del
     * medio es solo el palo, que es lo que le da el cruce. Y esos trazos van rectos y
     * pegados en vez de en diagonal, porque una diagonal de un pixel solo toca por la
     * esquina y a esta escala tambien se lee como un punto suelto. Sigue siendo mas bajo que
     * el engranaje, asi que la barra no crece por esto.
     */
    val bluetooth = listOf(
        "..#..",
        "..##.",
        "..#.#",
        "####.",
        "..#..",
        "####.",
        "..#.#",
        "..##.",
        "..#..",
    )

    /**
     * El rayo de la carga. Va al lado de la pila, no dentro: dentro no cabe y ademas taparia
     * el nivel, que es lo que se ha venido a mirar.
     */
    val bolt = listOf(
        "..##",
        ".##.",
        "##..",
        ".###",
        "..##",
        ".##.",
        "##..",
    )

    /**
     * La batería se dibuja según el nivel: la carcasa siempre, y dentro tantas columnas
     * como carga quede. A escala tres el hueco tiene nueve columnas, o sea que la carga se
     * ve en pasos del 11%, y con eso sobra para saber si hay que enchufarlo.
     */
    fun battery(percent: Int): List<String> {
        val cells = if (percent < 0) 0 else (percent.coerceIn(0, 100) * 9 + 50) / 100
        val inner = "#".repeat(cells) + ".".repeat(9 - cells)
        return listOf(
            "###########.",
            "#.........#.",
            "#$inner#" + "#",
            "#$inner#" + "#",
            "#$inner#" + "#",
            "#.........#.",
            "###########.",
        )
    }
}
