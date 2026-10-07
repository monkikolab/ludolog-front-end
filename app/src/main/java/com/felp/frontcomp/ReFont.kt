package com.felp.frontcomp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * La fuente de los diálogos: la de Resident Evil en la PSX, tal cual salió de la consola.
 *
 * font/re-dialog.png, en la carpeta del tema Parlour, es la hoja de letras del juego
 * (ripeada por Badassbill, que pide crédito: aquí lo tiene), recortada a las nueve filas de la fuente grande. Celdas de
 * 8x14, una letra por celda. No hay fuente vectorial de por medio: cada letra se copia
 * píxel a píxel de la hoja y se amplía por vecino más próximo, que es lo que hacía el
 * juego con su textura de letras.
 *
 * La hoja trae las letras con el relleno biselado en varios grises y un contorno oscuro.
 * Para pintarlas de un color, el relleno se multiplica por el color y el contorno se deja
 * como está: el título en rojo y el texto en blanco son la misma fuente con el mismo
 * borde, y ese borde es lo que deja al texto flotar sobre la sala sin una caja detrás.
 *
 * Es proporcional aunque la hoja sea de celdas: el ancho de cada letra se mide en la hoja
 * (hasta donde llega su tinta) y se avanza eso más un píxel. Una fuente de celdas fijas
 * escrita monoespaciada se ve como una tabla; escrita a su ancho, como un diálogo.
 */
object ReFont {
    const val CELL_W = 8
    const val CELL_H = 14
    private const val COLS = 32
    /** Lo que avanza un espacio, en píxeles de la hoja. */
    private const val SPACE = 4
    /** Contorno de la hoja, (49,49,74). Se conserva tal cual, sea cual sea el color. */
    private const val OUTLINE = 0xFF31314A.toInt()
    /** El gris más claro del relleno: lo que se toma como «color al cien por cien». */
    private const val FILL_MAX = 222f

    /**
     * Qué hay en cada celda, fila a fila. Un espacio es una celda que no se usa: botones
     * del mando, ligaduras («S.T.A.R.»), duplicados. Las tildes que faltan (ñ, Ú...) caen
     * a la letra sin tilde al dibujar.
     */
    private val ROWS = listOf(
        "  ▸    △○×□▾012345",
        "6789:;,\"!?‽ABCDEFG",
        "HIJKLMNOPQRSTUVWXY",
        "Z(/)'-ãabcdefghijk",
        "lmnopqrstuvwxyzÄÍ",
        "ö üßÁàÃâÂèÉéÊêÑ õí",
        "ÓôóúÛáÇç     .   +",
        "=",
    )

    class Glyph(val sx: Int, val sy: Int, val left: Int, val right: Int, val dy: Int = 0) {
        val width get() = right - left + 1
        val advance get() = width + 1
    }

    class Sheet(private val code: IntArray, private val w: Int, private val glyphs: Map<Char, Glyph>) {

        /** La letra para un carácter, o nada si ni ella ni su base sin tilde están. */
        fun glyph(ch: Char): Glyph? {
            glyphs[ch]?.let { return it }
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull()
            return base?.let { glyphs[it] }
        }

        fun advance(ch: Char): Int = if (ch == ' ') SPACE else glyph(ch)?.advance ?: SPACE

        fun measure(s: String): Int = s.sumOf { advance(it) }

        /**
         * Dibuja el texto en un mapa de bits de `width` píxeles de hoja de ancho.
         *
         * @param visibleChars cuántos caracteres se enseñan, para la máquina de escribir.
         *   El ajuste de línea se hace sobre el texto ENTERO y se recorta después, para que
         *   la palabra que se está escribiendo no salte de línea a mitad.
         */
        fun render(
            text: String,
            color: Color,
            width: Int,
            visibleChars: Int,
            maxLines: Int,
            lineGap: Int,
        ): Bitmap {
            val lines = wrap(text, width).take(maxLines)
            val lineH = CELL_H + lineGap
            val h = max(1, lines.size * lineH)
            val out = IntArray(width * h)
            val r = (color.red * 255).roundToInt()
            val g = (color.green * 255).roundToInt()
            val b = (color.blue * 255).roundToInt()

            var budget = visibleChars
            for ((i, line) in lines.withIndex()) {
                if (budget <= 0) break
                val shown = if (line.length <= budget) line else line.substring(0, budget)
                var x = 0
                for (ch in shown) {
                    val gl = if (ch == ' ') null else glyph(ch)
                    if (gl == null) { x += SPACE; continue }
                    blit(out, width, h, x, i * lineH + gl.dy, gl, r, g, b)
                    x += gl.advance
                }
                // La línea entera cuenta aunque se recorte, y el espacio que la separa de
                // la siguiente también: así el ritmo del tecleo no se acelera al cambiar
                // de línea.
                budget -= line.length + 1
            }
            return Bitmap.createBitmap(out, width, h, Bitmap.Config.ARGB_8888)
        }

        private fun blit(out: IntArray, ow: Int, oh: Int, x0: Int, y0: Int, gl: Glyph, r: Int, g: Int, b: Int) {
            for (cy in 0 until CELL_H) {
                val y = y0 + cy
                if (y < 0 || y >= oh) continue
                for (cx in gl.left..gl.right) {
                    val x = x0 + cx - gl.left
                    if (x < 0 || x >= ow) continue
                    val c = code[(gl.sy + cy) * w + gl.sx + cx]
                    if (c == 0) continue
                    out[y * ow + x] = if (c < 0) OUTLINE else {
                        val k = c / 255f
                        (0xFF shl 24) or ((r * k).roundToInt() shl 16) or
                            ((g * k).roundToInt() shl 8) or (b * k).roundToInt()
                    }
                }
            }
        }

        /** Ajuste de línea por palabras, a ancho en píxeles; una palabra más larga que la línea se parte. */
        private fun wrap(text: String, width: Int): List<String> {
            val out = mutableListOf<String>()
            for (paragraph in text.split('\n')) {
                var line = StringBuilder()
                var lineW = 0
                for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                    var wd = word
                    while (measure(wd) > width) {
                        if (line.isNotEmpty()) { out += line.toString(); line = StringBuilder(); lineW = 0 }
                        var cut = 1
                        while (cut < wd.length && measure(wd.substring(0, cut + 1)) <= width) cut++
                        out += wd.substring(0, cut)
                        wd = wd.substring(cut)
                    }
                    val ww = measure(wd)
                    when {
                        line.isEmpty() -> { line.append(wd); lineW = ww }
                        lineW + SPACE + ww <= width -> { line.append(' ').append(wd); lineW += SPACE + ww }
                        else -> { out += line.toString(); line = StringBuilder(wd); lineW = ww }
                    }
                }
                out += line.toString()
            }
            return out
        }
    }

    /** El nombre que un tema tiene que usar para sustituirla. */
    private const val SHEET = "re-dialog.png"

    @Volatile private var cached: Sheet? = null

    /**
     * La lamina del tema puesto, o nula si no la trae.
     *
     * Solo del tema: el APK no lleva ninguna (07-10-2026). Ludolog trae dentro solo el Gallery,
     * que no la usa; la del Parlour viene con el Parlour, que se baja de ludolog-assets. Sin
     * lamina, quien la pedia escribe con la letra de siempre (ver TypedDetails).
     */
    fun load(ctx: Context): Sheet? {
        cached?.let { return it }
        //
        // Y solo si se puede leer y es del tamano de la rejilla. Una que no se decodifica —un
        // marcador, otro formato, una cabecera rota— daba null y cerraba la aplicacion al
        // arrancar, porque el rotulo de la sala sale enseguida; y una redibujada sin la fila
        // del «=» leia fuera de la hoja. Sin poder llegar a los ajustes para cambiar de tema.
        val needW = ROWS.maxOf { it.length }.coerceAtMost(COLS) * CELL_W
        val needH = ROWS.size * CELL_H
        val own = ThemeFiles.font(ctx, SHEET)
            ?.let { f -> runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }
            ?.let { b ->
                if (b.width >= needW && b.height >= needH) b
                else { b.recycle(); null }
            }
        if (own == null) {
            if (ThemeFiles.font(ctx, SHEET) != null) android.util.Log.w("Ludolog", "$SHEET del tema no vale: se escribe sin ella")
            return null
        }
        val bmp = own
        val w = bmp.width
        val px = IntArray(w * bmp.height)
        bmp.getPixels(px, 0, w, 0, 0, w, bmp.height)
        bmp.recycle()

        // Cada píxel de la hoja se clasifica una vez: 0 nada, -1 contorno, 1..255 cuánto
        // relleno (el bisel son grises distintos, y es lo que se conserva al teñir).
        val code = IntArray(px.size) { i ->
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            when {
                r == 0 && g == 0 && b == 0 -> 0
                r == 49 && g == 49 && b == 74 -> -1
                else -> ((max(r, max(g, b)) / FILL_MAX).coerceAtMost(1f) * 255)
                    .roundToInt().coerceAtLeast(1)
            }
        }

        val glyphs = HashMap<Char, Glyph>()
        for ((row, chars) in ROWS.withIndex()) {
            for ((col, ch) in chars.withIndex()) {
                if (ch == ' ' || col >= COLS) continue
                val sx = col * CELL_W
                val sy = row * CELL_H
                var left = CELL_W
                var right = -1
                for (cy in 0 until CELL_H) for (cx in 0 until CELL_W) {
                    if (code[(sy + cy) * w + sx + cx] != 0) {
                        if (cx < left) left = cx
                        if (cx > right) right = cx
                    }
                }
                if (right < 0) continue
                glyphs[ch] = Glyph(sx, sy, left, right)
            }
        }
        // El punto medio de «18 games · 18 with art» no está en la hoja: es el punto,
        // subido a media altura.
        glyphs['.']?.let { p -> glyphs['·'] = Glyph(p.sx, p.sy, p.left, p.right, dy = -5) }
        glyphs['-']?.let { d -> glyphs['—'] = d; glyphs['–'] = d }
        glyphs['\'']?.let { q -> glyphs['’'] = q }

        return Sheet(code, w, glyphs).also { cached = it }
    }
}

/**
 * Un texto con la fuente de los diálogos, ampliado por `scale` sin filtrar.
 *
 * Con 2 mide lo mismo que el texto del menú; con 3 es un rótulo. El mapa se vuelve a
 * dibujar en cada tecleo y no importa: son unos cientos de píxeles de ancho por tres o
 * cuatro líneas.
 */
@Composable
fun ReText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    scale: Int = 2,
    visibleChars: Int = Int.MAX_VALUE,
    maxLines: Int = 4,
    lineGap: Int = 2,
) {
    val ctx = LocalContext.current
    val sheet = remember { ReFont.load(ctx) }
    if (sheet == null) {
        // Sin lamina no se queda en blanco: el mismo texto, con la letra del sistema.
        androidx.compose.material3.Text(
            text.take(visibleChars), color = color, modifier = modifier,
            fontSize = androidx.compose.ui.unit.TextUnit(7f * scale, androidx.compose.ui.unit.TextUnitType.Sp),
            maxLines = maxLines,
        )
        return
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val smallW = (constraints.maxWidth / scale).coerceAtLeast(ReFont.CELL_W)
        val bmp = remember(text, color, smallW, visibleChars, maxLines, lineGap) {
            sheet.render(text, color, smallW, visibleChars, maxLines, lineGap)
        }
        val w = with(density) { (bmp.width * scale).toDp() }
        val h = with(density) { (bmp.height * scale).toDp() }
        Canvas(Modifier.size(w, h)) {
            drawImage(
                bmp.asImageBitmap(),
                dstSize = IntSize(bmp.width * scale, bmp.height * scale),
                filterQuality = FilterQuality.None,
            )
        }
    }
}
