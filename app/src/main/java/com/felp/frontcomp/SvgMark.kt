package com.felp.frontcomp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File

/**
 * La silueta de un SVG, rellena de un solo color.
 *
 * Un tema trae su propia marca para la tarjeta que asoma encima del juego, y la trae dibujada
 * —un SVG— porque una marca dibujada a mano se ve mejor que una calculada, y porque quien la
 * dibuja no tiene por que tocar codigo para cambiarla.
 *
 * Lo que se lee de ese fichero es la SILUETA y no el dibujo. Un SVG de un diamante trae sus
 * caras en cuatro grises —asi es como se dibuja un diamante— y esos grises son del dibujo, no
 * del tema: sobre papel, un blanco roto se pierde, y ademas la tarjeta se tine del acento y un
 * gris no se tine de nada. Uniendo todas las figuras queda el contorno, y eso si se puede
 * pintar del color que haga falta.
 *
 * No es un renderizador de SVG y no pretende serlo. Entiende lo que trae una silueta hecha con
 * las herramientas de siempre: `path`, `polygon`, `polyline`, y un `translate` por figura.
 * Degradados, curvas raras, grupos anidados y rotaciones se ignoran o fallan, y fallar aqui no
 * cuesta nada: si no sale una silueta, la tarjeta usa la marca que trae el programa.
 *
 * Y una convencion propia: una figura con `id="cut"` es un hueco en la silueta. Ver [Shape].
 */
object SvgMark {

    /** Lo que ocupa el dibujo dentro del cuadro, dejando aire alrededor. */
    private const val FILL = 0.92f

    private val TAG = Regex("""<(path|polygon|polyline)\b([^>]*)>""")
    private val ATTR_D = Regex("""\bd\s*=\s*"([^"]*)"""")
    private val ATTR_POINTS = Regex("""\bpoints\s*=\s*"([^"]*)"""")
    private val TRANSLATE = Regex("""translate\(\s*(-?[\d.]+)[\s,]+(-?[\d.]+)\s*\)""")
    /** El negro con el que la norma rellena una figura que no dice de que color va. */
    private const val DEFAULT_FILL = 0xFF000000.toInt()

    // Las reglas se leen dentro de los bloques <style>, con el selector entero: Illustrator
    // agrupa las clases del mismo color —`.cls-1,.cls-2{fill:#fcfcfc}`— y leyendo solo la
    // ultima clase antes de la llave, las demas se quedaban sin color y salian negras.
    private val STYLE_BLOCK = Regex("""<style[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
    private val STYLE_RULE = Regex("""([^{}]+)\{([^}]*)\}""")
    private val CLASS_SELECTOR = Regex("""\.([A-Za-z_][A-Za-z0-9_-]*)""")
    private val FILL_PROP = Regex("""fill\s*:\s*([^;}\s]+)""")
    private val ATTR_CLASS = Regex("""\bclass\s*=\s*"([^"]*)"""")
    private val ATTR_FILL = Regex("""\bfill\s*=\s*"([^"]*)"""")
    private val ATTR_ID = Regex("""(?:^|\s)id\s*=\s*"([^"]*)"""")
    private val CUT_ID = Regex("""^cut(?:[-_].*)?$""")

    /**
     * Una figura del dibujo: su trazado, con que se rellena —o nada si no se rellena— y si
     * es un HUECO.
     *
     * Un hueco se pinta como cualquier otra figura en la version sombreada, con su gris, y se
     * recorta en la silueta de un color, que es la unica forma que tiene un solo tono de
     * decir «aqui hay algo mas oscuro». Ver [bitmap].
     */
    class Shape(val path: Path, val fill: Int?, val cut: Boolean = false)

    /**
     * Las figuras del fichero, en orden y con su color, en las unidades del dibujo.
     *
     * En orden porque un SVG se pinta como un monton de papeles: la ultima tapa a la anterior,
     * y ese apilado ES el dibujo. Un diamante son cuatro caras de cuatro grises puestas una
     * encima de otra; barajadas, deja de ser un diamante.
     *
     * El color se busca donde la norma dice que esta, en este orden: el atributo `fill` de la
     * propia figura, la clase que lleve, y si no hay ninguno, negro. Las clases viven en un
     * bloque `<style>` —asi las escribe Illustrator— y por eso hay que leerlo antes.
     */
    fun shapes(file: File): Pair<List<Shape>, RectF>? =
        parsed.getOrPut(file.path) { java.util.Optional.ofNullable(parse(file)) }.orElse(null)

    /**
     * Las figuras ya leidas, por fichero. Se pedian cada vez que la lista pasaba por el Companion o
     * por Link, y leer y desarmar el SVG en el hilo de la pantalla trababa la lista (08-10-2026).
     * Se dibujan siempre sobre una copia (ver [tinted]), asi que se pueden reusar. Ver ThemeFiles.forget.
     */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Pair<List<Shape>, RectF>>>()

    /** Olvida lo leido: un tema instalado de nuevo puede traer otra marca con el mismo nombre. */
    fun forget() = parsed.clear()

    private fun parse(file: File): Pair<List<Shape>, RectF>? = runCatching {
        val svg = file.readText()
        // La regla que venga despues pisa a la de antes, como en CSS.
        val byClass = HashMap<String, String>()
        for (block in STYLE_BLOCK.findAll(svg)) {
            for (r in STYLE_RULE.findAll(block.groupValues[1])) {
                val fill = FILL_PROP.find(r.groupValues[2])?.groupValues?.get(1) ?: continue
                for (selector in r.groupValues[1].split(',')) {
                    val cls = CLASS_SELECTOR.findAll(selector).lastOrNull()?.groupValues?.get(1) ?: continue
                    byClass[cls] = fill
                }
            }
        }

        val out = ArrayList<Shape>()
        val all = Path()
        for (m in TAG.findAll(svg)) {
            val attrs = m.groupValues[2]
            val path = when (m.groupValues[1]) {
                "path" -> ATTR_D.find(attrs)?.groupValues?.get(1)?.let(::fromPathData)
                else -> ATTR_POINTS.find(attrs)?.groupValues?.get(1)?.let(::fromPoints)
            } ?: continue
            TRANSLATE.find(attrs)?.let { t ->
                path.transform(
                    Matrix().apply {
                        setTranslate(t.groupValues[1].toFloat(), t.groupValues[2].toFloat())
                    },
                )
            }
            val named = ATTR_CLASS.find(attrs)?.groupValues?.get(1)
                ?.split(' ')?.firstNotNullOfOrNull { byClass[it] }
            val fill = ATTR_FILL.find(attrs)?.groupValues?.get(1) ?: named
            // Hueco si su id es `cut` o empieza por `cut-` / `cut_`: es lo que escribe
            // Illustrator al poner nombre a un objeto en la paleta de capas, y al repetir el
            // nombre le anade el numero con guion.
            val cut = ATTR_ID.find(attrs)?.groupValues?.get(1)?.let(CUT_ID::matches) == true
            out.add(Shape(path, colourOf(fill), cut))
            all.op(path, Path.Op.UNION)
        }
        if (out.isEmpty()) return null
        val bounds = RectF()
        all.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null
        out to bounds
    }.getOrNull()

    /**
     * El contorno de todas las figuras juntas, en las unidades del dibujo.
     *
     * Devuelve tambien su caja, porque el trazado sale en esas unidades y quien lo pinte tiene
     * que saber contra que escalarlo. La caja se MIDE y no se lee del viewBox: un SVG guardado
     * con las capas desplazadas trae un viewBox que ya no ajusta —este llevaba un translate de
     * -36.5,-17.5 en la mitad de las figuras— y escalando contra el, la marca sale pequena y
     * descentrada.
     *
     * Los huecos se restan DESPUES de sumar todo lo demas, no en su sitio del monton: un hueco
     * es un agujero en la pieza entera, y restado a mitad del apilado lo taparia la cara
     * siguiente.
     */
    fun outline(file: File): Pair<Path, RectF>? {
        val (all, bounds) = shapes(file) ?: return null
        val out = Path()
        // Sin las que no se rellenan, como en tinted(): un contorno solo de trazo, sumado,
        // llenaba de solido su interior y la silueta salia como una mancha.
        for (s in all) if (!s.cut && s.fill != null) out.op(s.path, Path.Op.UNION)
        for (s in all) if (s.cut) out.op(s.path, Path.Op.DIFFERENCE)
        return out to bounds
    }

    /**
     * Un color de SVG: `#rgb`, `#rrggbb`, o `none` para no rellenar.
     *
     * Los nombres —«red», «cornflowerblue»— no estan: son ciento cuarenta y siete casos para
     * un fichero que sale de un programa de dibujo, y esos escriben hexadecimal.
     */
    private fun colourOf(raw: String?): Int? {
        val v = raw?.trim()?.lowercase() ?: return DEFAULT_FILL
        if (v == "none" || v == "transparent") return null
        if (!v.startsWith("#")) return DEFAULT_FILL
        val hex = v.removePrefix("#")
        val rgb = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return DEFAULT_FILL
        }
        val n = rgb.toLongOrNull(16) ?: return DEFAULT_FILL
        return (0xFF000000L or n).toInt()
    }

    // ------------------------------------------------------------------ pintarlo

    /** La matriz que lleva el dibujo, centrado, a un cuadro de `side` puntos. */
    private fun fitTo(bounds: RectF, side: Int): Matrix {
        val k = side * FILL / maxOf(bounds.width(), bounds.height())
        return Matrix().apply {
            setTranslate(-bounds.centerX(), -bounds.centerY())
            postScale(k, k)
            postTranslate(side / 2f, side / 2f)
        }
    }

    private fun square(side: Int): Bitmap =
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        }

    /**
     * La silueta, de un solo color.
     *
     * Es lo que quiere la tarjeta que asoma encima del juego: ahi la marca se tiñe del acento
     * del tema, y para eso no puede traer colores propios.
     *
     * Y en un solo color, un detalle oscuro solo se puede dibujar de una manera: dejandolo
     * vacio. Por eso las figuras marcadas como hueco —ver [Shape.cut]— se RESTAN del contorno
     * en vez de sumarse. Es lo que hace que la pupila de la gema del Gallery se vea aqui:
     * pintada encima, en una silueta de un tono se fundia con la cara de debajo y la gema
     * salia ciega. El libro del Parlour y la chapa del Mainframe ya llevaban el ojo
     * recortado; esto deja hacer lo mismo sin tener que partir a mano las caras que lo tapan.
     */
    fun bitmap(file: File, side: Int, colour: Int): Bitmap? {
        val (path, bounds) = outline(file) ?: return null
        val shown = Path(path).apply { transform(fitTo(bounds, side)) }
        val bmp = square(side)
        Canvas(bmp).drawPath(
            shown,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = colour
                style = Paint.Style.FILL
            },
        )
        return bmp
    }

    /**
     * El dibujo con SU sombreado y EL color del tema.
     *
     * Es lo que quiere el panel de la lista de consolas, donde la marca ocupa el sitio que en
     * las demas filas ocupa el giro de una consola: ahi al lado hay caratulas a todo color y
     * una silueta plana se lee como un icono, no como la pieza que representa.
     *
     * Pero tampoco valen los colores del fichero tal cual. Lo que un dibujo guarda en sus
     * grises no es «gris», es la LUZ: que cara mira al foco y cual esta en sombra. Eso hay que
     * conservarlo. El tono es otra cosa y ese sí es del tema, asi que de cada relleno se saca
     * su claridad y se vuelve a montar con el tono y la viveza del acento.
     *
     * De paso arregla un problema real: el diamante trae dos caras en el mismo gris claro, y
     * sobre el papel de un tema claro ese gris y el fondo valen casi lo mismo —medido en
     * pantalla, 0xC1C1C1 contra 0xB3B5B2— asi que una de las cuatro caras desaparecia. Con el
     * tono del acento, la cara sigue siendo la mas clara de las tres pero ya no es del color
     * del fondo.
     *
     * Claridad y no el canal mas alto: asi un dibujo que ya venga en color —y no en grises—
     * conserva el sombreado que su autor le dio en vez del capricho de su canal mas fuerte.
     */
    fun tinted(file: File, side: Int, accent: Int): Bitmap? {
        val (all, bounds) = shapes(file) ?: return null
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent, hsv)
        val m = fitTo(bounds, side)
        val bmp = square(side)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (s in all) {
            val fill = s.fill ?: continue
            val light = (
                0.299f * android.graphics.Color.red(fill) +
                    0.587f * android.graphics.Color.green(fill) +
                    0.114f * android.graphics.Color.blue(fill)
                ) / 255f
            paint.color = android.graphics.Color.HSVToColor(
                floatArrayOf(hsv[0], hsv[1], light),
            )
            canvas.drawPath(Path(s.path).apply { transform(m) }, paint)
        }
        return bmp
    }

    // ------------------------------------------------------------------ el trazado

    /**
     * Un `d` de SVG, con lo que hace falta y nada mas.
     *
     * M/m, L/l, H/h, V/v, C/c y Z/z. La regla que se olvida siempre: una orden de mover
     * seguida de mas pares de numeros NO son mas movimientos, son lineas —asi esta en la
     * norma— y es justo lo que usan las herramientas para escribir un poligono corto. Sin eso,
     * la mitad de las figuras de un fichero real salen como puntos sueltos.
     */
    private fun fromPathData(d: String): Path? {
        val p = Path()
        var i = 0
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var cmd = ' '
        var started = false
        val n = numbersWithCommands(d)
        while (i < n.size) {
            val tok = n[i]
            if (tok.letter != null) {
                cmd = tok.letter
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    p.close()
                    cx = sx; cy = sy
                    continue
                }
            }
            fun need(k: Int): FloatArray? {
                if (i + k > n.size) return null
                val v = FloatArray(k)
                for (j in 0 until k) {
                    v[j] = n[i + j].value ?: return null
                }
                i += k
                return v
            }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val v = need(2) ?: return null
                    cx = if (rel) cx + v[0] else v[0]
                    cy = if (rel) cy + v[1] else v[1]
                    p.moveTo(cx, cy)
                    sx = cx; sy = cy
                    started = true
                    // Los pares que sigan a una M son lineas, no movimientos.
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> {
                    val v = need(2) ?: return null
                    cx = if (rel) cx + v[0] else v[0]
                    cy = if (rel) cy + v[1] else v[1]
                    if (!started) { p.moveTo(cx, cy); sx = cx; sy = cy; started = true }
                    else p.lineTo(cx, cy)
                }
                'H' -> {
                    val v = need(1) ?: return null
                    cx = if (rel) cx + v[0] else v[0]
                    p.lineTo(cx, cy)
                }
                'V' -> {
                    val v = need(1) ?: return null
                    cy = if (rel) cy + v[0] else v[0]
                    p.lineTo(cx, cy)
                }
                'C' -> {
                    val v = need(6) ?: return null
                    val x1 = if (rel) cx + v[0] else v[0]
                    val y1 = if (rel) cy + v[1] else v[1]
                    val x2 = if (rel) cx + v[2] else v[2]
                    val y2 = if (rel) cy + v[3] else v[3]
                    cx = if (rel) cx + v[4] else v[4]
                    cy = if (rel) cy + v[5] else v[5]
                    p.cubicTo(x1, y1, x2, y2, cx, cy)
                }
                else -> return null
            }
        }
        p.close()
        return p
    }

    private fun fromPoints(points: String): Path? {
        val v = numbers(points)
        if (v.size < 6) return null
        val p = Path()
        p.moveTo(v[0], v[1])
        var i = 2
        while (i + 1 < v.size) {
            p.lineTo(v[i], v[i + 1])
            i += 2
        }
        p.close()
        return p
    }

    // ------------------------------------------------------------------ los numeros

    private class Token(val letter: Char?, val value: Float?)

    /**
     * Parte un `d` en ordenes y numeros.
     *
     * Lo delicado es el signo menos: en un SVG vale como separador —`1,1043,300-627`— porque
     * un numero negativo no necesita coma delante. Partir por espacios y comas deja `300-627`
     * de una pieza y el trazado sale a tomar viento.
     */
    private fun numbersWithCommands(d: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() -> { out.add(Token(c, null)); i++ }
                c == ',' || c.isWhitespace() -> i++
                else -> {
                    val start = i
                    if (d[i] == '-' || d[i] == '+') i++
                    // Un punto solo: el segundo empieza el numero siguiente. Los exportadores
                    // que compactan escriben `c.28.07` por `c 0.28 0.07`, y leido de una pieza
                    // no era un numero y la figura se cortaba ahi.
                    var dot = false
                    while (i < d.length && (d[i].isDigit() || (d[i] == '.' && !dot))) {
                        if (d[i] == '.') dot = true
                        i++
                    }
                    if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                        i++
                        if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
                        while (i < d.length && d[i].isDigit()) i++
                    }
                    if (i == start) return out
                    out.add(Token(null, d.substring(start, i).toFloatOrNull() ?: return out))
                }
            }
        }
        return out
    }

    private fun numbers(s: String): List<Float> =
        numbersWithCommands(s).mapNotNull { it.value }
}
