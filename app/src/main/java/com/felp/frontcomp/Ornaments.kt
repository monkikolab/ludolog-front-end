package com.felp.frontcomp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow

/**
 * Panel separators, drawn rather than declared.
 *
 * A rule is the one piece of chrome that appears on every screen, so it is where an
 * aesthetic gets stated most cheaply: it costs no vertical space beyond the line that was
 * already there, and it never overlaps the artwork. That matters on a 456dp-tall screen
 * where anything decorative has to justify the pixels it takes from the box art.
 */
@Composable
fun PanelDivider(
    modifier: Modifier = Modifier,
    color: Color = MenuLine,
    emphasis: Float = 1f,
) {
    val style = LocalTheme.current.divider
    val stroke = emphasis
    Canvas(
        modifier.fillMaxWidth().height(
            when (style) {
                DividerStyle.PARLOUR -> 13.dp
                DividerStyle.SCAN -> 5.dp
                DividerStyle.PLAIN -> 2.dp
                DividerStyle.HAIR -> 1.dp
                DividerStyle.LOZENGE -> 7.dp
            }
        )
    ) {
        when (style) {
            DividerStyle.PLAIN -> drawPlain(color, stroke)
            DividerStyle.SCAN -> drawScan(color, stroke)
            DividerStyle.PARLOUR -> drawGothic(color, stroke)
            DividerStyle.HAIR -> drawHair(color)
            DividerStyle.LOZENGE -> drawLozengeRule(color)
        }
    }
}

private fun DrawScope.drawPlain(color: Color, stroke: Float) {
    val y = size.height / 2f
    val w = 2.dp.toPx() * stroke
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = w)
}

/**
 * Una regla de un pixel y nada mas.
 *
 * Es la unica que no dice nada por si misma, y por eso es la del tema PSX: alli el
 * ornamento son las regletas del panel y la barra roja de la seleccion, y una regla con
 * dibujo encima competiria con ellas.
 */
private fun DrawScope.drawHair(color: Color) {
    val y = size.height / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
}

/** Un rombo relleno: el adorno mas pequeno que sigue leyendose como Parlour. */
internal fun DrawScope.lozenge(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        lineTo(c.x + r * 0.62f, c.y)
        lineTo(c.x, c.y + r)
        lineTo(c.x - r * 0.62f, c.y)
        close()
    }
    drawPath(p, color)
}

/** Un rombo sin rellenar: el mismo adorno, dicho en negativo. */
internal fun DrawScope.lozengeOutline(c: Offset, r: Float, color: Color, width: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        lineTo(c.x + r * 0.62f, c.y)
        lineTo(c.x, c.y + r)
        lineTo(c.x - r * 0.62f, c.y)
        close()
    }
    drawPath(p, color, style = Stroke(width = width))
}

/**
 * La regla fina con un rombo en el centro.
 *
 * Es el adorno Parlour reducido a lo minimo que aun se reconoce: la regla se parte en el
 * centro y en el hueco va un losange, con dos puntos a los lados como remate. Mide siete
 * puntos de alto, que es lo que cabe entre el titulo de un panel y su lista sin robarle
 * una fila.
 */
private fun DrawScope.drawLozengeRule(color: Color) {
    val y = size.height / 2f
    val cx = size.width / 2f
    val w = 1.dp.toPx()
    val r = 3.dp.toPx()
    val gap = 7.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(cx - gap, y), strokeWidth = w)
    drawLine(color, Offset(cx + gap, y), Offset(size.width, y), strokeWidth = w)
    lozenge(Offset(cx, y), r, color)
    val dot = 1.1.dp.toPx()
    drawCircle(color, dot, Offset(cx - gap, y))
    drawCircle(color, dot, Offset(cx + gap, y))
}

/** Dos reglas desiguales: la segunda, mas tenue, lee como el eco de un barrido de tubo. */
private fun DrawScope.drawScan(color: Color, stroke: Float) {
    val w = 1.5.dp.toPx() * stroke
    drawLine(color, Offset(0f, w), Offset(size.width, w), strokeWidth = w)
    drawLine(
        color.copy(alpha = .35f),
        Offset(0f, size.height - w), Offset(size.width, size.height - w),
        strokeWidth = w,
    )
}

/**
 * A pointed arch flanked by two rules, with a lozenge at the crown.
 *
 * Everything here is geometry rather than a glyph, so it scales with the panel and stays
 * one stroke thick at any width — an ornament that thickens unevenly is the usual tell
 * that a decoration was pasted in rather than drawn.
 */
private fun DrawScope.drawGothic(color: Color, stroke: Float) {
    val y = size.height * 0.62f
    val cx = size.width / 2f
    val w = 1.2.dp.toPx() * stroke
    val half = 26.dp.toPx().coerceAtMost(size.width * 0.28f)
    val rise = size.height * 0.5f

    // Las reglas se detienen antes del centro: el ornamento ocupa el hueco en vez de
    // superponerse, que es lo que lo hace legible a 13dp de alto.
    drawLine(color, Offset(0f, y), Offset(cx - half, y), strokeWidth = w)
    drawLine(color, Offset(cx + half, y), Offset(size.width, y), strokeWidth = w)

    // El arco apuntado: dos cuadraticas que se cortan en vertice en vez de encontrarse
    // tangentes, que es justo lo que separa el Parlour del romanico.
    val arch = Path().apply {
        moveTo(cx - half, y)
        quadraticTo(cx - half * 0.55f, y - rise * 0.55f, cx, y - rise)
        moveTo(cx + half, y)
        quadraticTo(cx + half * 0.55f, y - rise * 0.55f, cx, y - rise)
    }
    drawPath(arch, color, style = Stroke(width = w))

    // Losange en la clave.
    val r = 2.6.dp.toPx()
    val lozenge = Path().apply {
        moveTo(cx, y - rise - r)
        lineTo(cx + r * 0.7f, y - rise)
        lineTo(cx, y - rise + r)
        lineTo(cx - r * 0.7f, y - rise)
        close()
    }
    drawPath(lozenge, color)

    // Dos puntos al pie de cada arranque, que cierran la figura sin pedir mas alto.
    val dot = 1.1.dp.toPx()
    drawCircle(color, dot, Offset(cx - half, y))
    drawCircle(color, dot, Offset(cx + half, y))
}

/** Regla fina para separar filas dentro de una ventana: sin ornamento a esa escala. */
@Composable
fun HairLine(color: Color = MenuLine, alpha: Float = .5f, thickness: Dp = 1.dp) {
    androidx.compose.material3.HorizontalDivider(
        color = color.copy(alpha = alpha), thickness = thickness,
    )
}

/* ------------------------------------------------------------------ marcos de panel */

/**
 * Two loose rules, top and bottom, and nothing at the sides.
 *
 * The first version of this look was a box with an L at each corner, and it was too
 * literal a copy of the menus it was inspired by. This says the same with less: a rule
 * closing the panel above and another below, a lozenge at each end and one at the
 * middle, and the sides left open. Without a closed frame the panel stops being a window
 * pasted over the room and reads as lettering on it.
 *
 * @param line the rules themselves.
 * @param mark the lozenges and the dots, a shade brighter than the line so the ends carry
 *   the shape.
 * @param top a colour for the top rule when it means something: red on a dialog that is
 *   asking a question. The lozenges of that rule take the same colour.
 * @param ornament without it, two plain rules; it is the theme's call.
 */
fun Modifier.looseRules(
    line: Color,
    mark: Color,
    top: Color? = null,
    ornament: Boolean = true,
): Modifier = this.drawBehind {
    // Las reglas van metidas lo que mide el rombo, para que este quede dentro del panel
    // en vez de recortado por su borde.
    val inset = 4.dp.toPx()
    drawLooseRule(inset, top ?: line, top ?: mark, ornament)
    drawLooseRule(size.height - inset, line, mark, ornament)
}

/**
 * Una regla con tres rombos: uno en cada extremo y otro en el centro.
 *
 * La regla se parte alrededor de cada rombo y remata en un punto, igual que la regla del
 * separador: es el mismo dibujo repetido, que es lo que hace que un panel y su separador
 * parezcan del mismo juego.
 */
private fun DrawScope.drawLooseRule(y: Float, line: Color, mark: Color, ornament: Boolean) {
    val w = 1.dp.toPx()
    if (!ornament) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = w)
        return
    }
    val r = 3.5.dp.toPx()
    val gap = 7.dp.toPx()
    val cx = size.width / 2f
    val ends = listOf(r, cx, size.width - r)
    // Dos tramos: del rombo de la izquierda al del centro, y de este al de la derecha.
    val dot = 1.1.dp.toPx()
    for (i in 0 until ends.size - 1) {
        val a = ends[i] + gap
        val b = ends[i + 1] - gap
        drawLine(line, Offset(a, y), Offset(b, y), strokeWidth = w)
        drawCircle(mark, dot, Offset(a, y))
        drawCircle(mark, dot, Offset(b, y))
    }
    for (x in ends) lozenge(Offset(x, y), r, mark)
}

/**
 * The chrome of a panel or a window, as the theme wants it.
 *
 * One place for it so the main menu, the settings window and the context menus agree:
 * a frontend has few surfaces and they should look like the same machine drew them.
 *
 * @param fill the ground of the panel: opaque for a window, transparent for the menu over
 *   the room, which is not a box at all but lettering with a rule above and below.
 * @param top the colour of the top rule when it is meant to stand out (a dialog); only the
 *   RULES chrome honours it.
 */
@Composable
fun Modifier.panelChrome(fill: Color = MenuGround, top: Color? = null): Modifier {
    val t = LocalTheme.current
    val shape = RoundedCornerShape(t.corner)
    val base = this.background(fill, shape)
    return when (t.chrome) {
        Chrome.RULES -> base.looseRules(
            line = t.dim.copy(alpha = .55f),
            mark = t.ink.copy(alpha = .85f),
            top = top,
            ornament = t.ornament,
        )
        // El marco de una pantalla de fosforo: grueso arriba, fino en los otros tres, y rayado
        // por dentro.
        //
        // La ceja de arriba es la que lleva el rotulo, como en los paneles de un instrumento:
        // la caja no se presenta con un titulo encima sino que el titulo ES el canto de la
        // caja. Y va rayada como todo lo demas, porque en esta interfaz un bloque de color
        // lleno no existe: lo dibuja un tubo, linea a linea.
        Chrome.INSTRUMENT -> base.phosphorFrame(t.accent)
        Chrome.BORDER -> base.border(2.dp, MenuLine, shape)
    }
}

/**
 * Lo que mide la ceja de arriba.
 *
 * Lo bastante para que el ROTULO DE LA VENTANA quepa dentro, que es de lo que va: la caja no
 * se presenta con un titulo encima, el titulo es el canto de la caja. Son los 16 puntos de
 * relleno que la ventana deja arriba mas lo que ocupa el titulo.
 */
private val BROW = 54.dp

/** Y los otros tres cantos, que solo tienen que cerrar la caja. */
private val EDGE = 3.dp

/**
 * Marco de instrumento: ceja gruesa arriba y canto fino alrededor.
 *
 * Sin rayas propias: el barrido lo pone la pantalla entera, por encima de todo. Ver
 * `ScanlineOverlay`. Rayandolo aqui ademas, las lineas del marco y las de la pantalla caian
 * desfasadas y el canto salia mas oscuro que el resto por el simple hecho de llevar dos.
 */
fun Modifier.phosphorFrame(colour: Color, brow: Dp = BROW): Modifier = drawBehind {
    val top = brow.toPx()
    val edge = EDGE.toPx()
    drawRect(colour, size = Size(size.width, top))
    drawRect(colour, topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge))
    drawRect(colour, size = Size(edge, size.height))
    drawRect(colour, topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
}

/**
 * La ceja de las cajas del panel.
 *
 * Mas fina que la de una ventana, que lleva un titulo grande dentro: estas solo tienen que
 * decir cual es el canto de arriba —que es lo que distingue un marco de instrumento de un
 * rectangulo— y, la de la lista de juegos, el nombre de la consola. Nueve puntos lo decian de
 * refilon: con el barrido cortandola en tres franjas, una ceja fina se lee como un borde grueso
 * y no como una ceja. Diecisiete ya se leia como canto; veinticuatro es lo que pidio el ojo
 * para que la caja pese arriba, y deja sitio a un rotulo sin apretarlo.
 */
internal val PANE_BROW = 24.dp

/**
 * Un rotulo escrito DENTRO de la ceja de una caja del panel, como el titulo de una ventana: con
 * el color del fondo sobre el del acento, que es la fila invertida aplicada al canto de la caja.
 *
 * Dibujado encima del contenido y no como un elemento mas, para no mover nada de lo que hay
 * dentro: la caja mide lo mismo con rotulo que sin el. Nulo o vacio, no escribe nada.
 */
fun Modifier.browLabel(text: String?): Modifier = composed {
    if (text.isNullOrEmpty()) return@composed Modifier
    val t = LocalTheme.current
    val measurer = rememberTextMeasurer()
    val shown = if (t.upperTitles) text.uppercase() else text
    val style = TextStyle(
        color = t.ground, fontFamily = t.display, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, letterSpacing = t.titleTracking,
    )
    Modifier.drawWithContent {
        drawContent()
        val inset = 14.dp.toPx()
        val room = (size.width - inset * 2f).toInt().coerceAtLeast(0)
        val label = measurer.measure(
            shown, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = room),
        )
        drawText(label, topLeft = Offset(inset, (PANE_BROW.toPx() - label.size.height) / 2f))
    }
}

/**
 * Una caja del panel: el marco, y encima las seis cruces de encuadre.
 *
 * Las cruces van DELANTE de lo que haya dentro y no detras, que es lo que las convierte en
 * marcas de una pantalla en vez de en un dibujo del fondo: pasan por encima de la consola
 * igual que pasarian por encima de cualquier cosa que el aparato estuviera enseñando. Son de
 * tinta y no de acento a proposito —el acento es del chasis, la tinta es de lo que el
 * aparato dice— y estan puestas por fraccion del cuadro, no por puntos: la caja de arriba y
 * la de abajo tienen alturas muy distintas y con una distancia fija las de la baja se le
 * juntaban en el centro.
 */
fun Modifier.instrumentPane(ticks: Boolean): Modifier = composed {
    val t = LocalTheme.current
    val mark = t.ink
    val frame = t.accent
    this
        .phosphorFrame(frame, PANE_BROW)
        .drawWithContent {
            drawContent()
            if (ticks) {
                val arm = 9.dp.toPx()
                val w = 2.dp.toPx()
                for (fx in listOf(0.14f, 0.5f, 0.86f)) {
                    for (fy in listOf(0.26f, 0.74f)) {
                        val cx = size.width * fx
                        val cy = size.height * fy
                        drawRect(mark, Offset(cx - arm, cy - w / 2f), Size(arm * 2f, w))
                        drawRect(mark, Offset(cx - w / 2f, cy - arm), Size(w, arm * 2f))
                    }
                }
            }
        }
}

/**
 * El latido del cursor: entre 0,72 y 1 en poco menos de un segundo por sentido.
 *
 * Se nota si se mira y no si se lee. Un parpadeo de verdad, a cero, cansa en una lista
 * que se recorre despacio. Lo comparten la barra de las listas y la celda elegida de la
 * rejilla de apps, para que los dos cursores respiren al mismo ritmo.
 *
 * Devuelve el estado, no el numero, a proposito. Leer .value aqui hacia que la lectura
 * ocurriera en la COMPOSICION del que llama -cada fila de cada lista, cada casilla de la
 * rejilla- y como la animacion no termina nunca, la fila elegida se recomponia sesenta
 * veces por segundo mientras el tema Parlour estuviera puesto. Leido dentro del dibujado
 * solo se vuelve a pintar, que es lo unico que cambia.
 */
//
// Y al ritmo de Motion, no al de la pantalla: con una transicion infinita pedia un fotograma en
// cada refresco, 120 por segundo en una pantalla de 120 Hz, y era lo unico que se movia en el Gallery. En
// reposo se queda quieto y encendido.
@Composable
fun rememberCursorPulse(low: Float = 0.72f): State<Float> = rememberPulse(low)

/**
 * The bar that marks a selected row in the PSX look.
 *
 * A dark fill and a bright strip on the left edge, and on that strip three lozenges: one
 * at each end and a larger one at the middle, the same three the rules carry, turned
 * upright. The fill is washed from the strip inward, as if the strip lit it. And the mark
 * breathes, slowly: the cursor of a PSX menu blinked, and one that never moves reads as a
 * highlight rather than as "you are here". Drawn behind the row's content so it is exactly
 * the row's size, whatever the row holds.
 *
 * @param ornament without it, the plain fill: the theme's call, like the rules.
 *
 * Y late en TODOS los temas, no solo en el decorado: el latido es lo que dice «estas aqui» en
 * vez de «esta fila es distinta», y eso no es un adorno. Sin adorno late un velo del acento
 * encima del relleno. El relleno solo, modulado, apenas se movia: en estos temas es casi el
 * fondo, y medido en el Gallery la fila subia y bajaba cinco niveles de luma sobre
 * doscientos cincuenta y cinco. Encima y no en su lugar, para que en el punto bajo del latido
 * la fila siga marcada.
 */
@Composable
fun Modifier.selectionBar(
    fill: Color,
    bar: Color,
    width: Dp = 3.dp,
    ornament: Boolean = true,
): Modifier {
    // Sin adorno (el Gallery) no late: el velo del acento iba del 0 al 16 % y casi no se veia,
    // y era lo unico que obligaba a redibujar la pantalla con la lista quieta.
    val pulse = if (ornament) rememberCursorPulse(low = 0.72f) else remember { mutableFloatStateOf(1f) }

    return this.drawBehind {
        val p = pulse.value
        // Un tema sin adorno se senala con el relleno y nada mas, y encima late el acento.
        //
        // La barra roja del canto es LA firma de los menus de la PSX, y mientras estuvo ahi
        // ningun otro tema parecia otro tema: se cambiaba la letra, el color y el fondo, y el
        // cursor seguia siendo el mismo cursor. Un relleno de borde a borde dice lo mismo
        // —esta fila y no otra— sin decir de donde viene.
        if (!ornament) {
            drawRect(fill)
            drawRect(bar.copy(alpha = 0.16f * p))
            return@drawBehind
        }
        drawRect(fill)
        val w = width.toPx()
        // El lavado: del rojo de la barra, muy tenue, a nada, en algo menos de la mitad
        // de la fila. Es lo que hace que la fila parezca alumbrada por la barra en vez de
        // pintada.
        drawRect(
            Brush.horizontalGradient(
                0f to bar.copy(alpha = .20f * p), 1f to Color.Transparent,
                startX = 0f, endX = size.width * 0.42f,
            ),
            size = size,
        )
        val c = bar.copy(alpha = p)
        drawRect(c, size = Size(w, size.height))
        val cx = w / 2f
        val big = 6.dp.toPx()
        val small = 3.dp.toPx()
        lozenge(Offset(cx, size.height / 2f), big, c)
        // Los de los extremos, metidos su radio, para que queden dentro de la fila y no a
        // caballo de la de al lado.
        lozenge(Offset(cx, small), small, c)
        lozenge(Offset(cx, size.height - small), small, c)
    }
}

/**
 * El bloque que late: la fila invertida de una ventana, la celda elegida de la rejilla, la
 * pestaņa puesta del cuaderno. Es el selector de los temas de fosforo, donde lo elegido no se
 * subraya ni se encuadra: se ENCIENDE.
 *
 * Late mas hondo que el cursor del Parlour —del 45 % al 100 %, no del 72— y eso es a proposito.
 * Alli el latido es un adorno sobre una barra que ya se ve sola: la barra tiene color, borde y
 * rombos, y el latido solo le da vida. Aqui el bloque ES toda la seņal; si apenas modula, lo que
 * queda es un resaltado quieto, que dice «esta fila es distinta» y no «estas aqui». Con el doble
 * de recorrido se lee como un pulso.
 *
 * Y tampoco hasta cero, por la razon de siempre: un parpadeo completo obliga a esperar a que
 * vuelva para saber donde estas.
 */
fun Modifier.pulseFill(colour: Color): Modifier = composed {
    val pulse = rememberCursorPulse(low = 0.45f)
    drawBehind { drawRect(colour.copy(alpha = pulse.value)) }
}

/**
 * El recuadro que late: la casilla elegida del cajon en los temas que ni invierten ni decoran.
 *
 * El mismo pulso que el cursor de las listas, sobre el filete. Era un recuadro quieto, y la
 * rejilla era el unico sitio del programa donde el cursor no respiraba. El relleno se queda
 * fijo y tenue; lo que late es el filete, que es lo que marca la casilla.
 */
fun Modifier.pulseFrame(line: Color, radius: Dp = 6.dp, stroke: Dp = 2.dp): Modifier = composed {
    val pulse = rememberCursorPulse(low = 0.45f)
    drawBehind {
        val s = stroke.toPx()
        val r = radius.toPx()
        drawRoundRect(line.copy(alpha = .10f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        // Por dentro de la casilla, como `border`: centrado en el canto se saldria medio grosor.
        drawRoundRect(
            line.copy(alpha = line.alpha * pulse.value),
            topLeft = Offset(s / 2f, s / 2f),
            size = Size(size.width - s, size.height - s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius((r - s / 2f).coerceAtLeast(0f)),
            style = Stroke(s),
        )
    }
}

/**
 * El barrido, encima de todo.
 *
 * Las rayas no son un adorno de cada caja: son LA PANTALLA. En las maquinas que inspiran este
 * tema las lineas cortan el marco, el texto, la caratula y el cursor por igual, porque lo que
 * las dibuja no es el programa sino el tubo. Puestas elemento a elemento nunca acaban de
 * cuadrar —cada caja empieza su ciclo donde empieza la caja, no donde empieza la pantalla— y
 * se nota en cuanto dos elementos se tocan.
 *
 * Va la ultima de todo el arbol, asi que alcanza tambien a las ventanas, al cuaderno y a la
 * barra de estado. No come toques: es un dibujo, no un boton.
 *
 * Una raya de un pixel cada CUATRO, en negro y no bajando la opacidad de lo que hay debajo: el
 * fosforo o emite o no emite. Un degradado es justo lo que un tubo no hace.
 *
 * El alfa es CUANTO se nota la raya; el paso, cuanta luz se pierde y a que distancia se
 * distingue una raya de la siguiente. Medido sobre la ceja de un marco, en el rojo del acento:
 * con 0,60 cada tres las filas iban de 216 a 86; con 0,85 cada cuatro van de 216 a 32.
 *
 * Las dos cosas a la vez, y a proposito. Oscurecer sube el contraste de la raya y espaciarla
 * baja su frecuencia, que es lo que el ojo distingue mejor a un palmo de la cara, asi que la
 * raya se marca mucho mas; y la pantalla pierde la misma luz que antes, 0,85/4 contra 0,60/3,
 * un 21 contra un 20 % —medida entera, la media baja de 32,5 a 32,2—. Solo oscurecer, con el
 * paso de tres, apagaba un 28 %. Y engordar la raya tampoco sirve: con dos filas de cada tres
 * apagadas la luz cae y la linea no se lee mas.
 *
 * Despues se pidio mas marcada, y se engordo la raya pero separandola a la vez: DOS filas de
 * cada SEIS, y a la mitad de frecuencia cada franja encendida es de cuatro filas: el fosforo
 * se lee en tiras. Negra al 85 % se paso —la pantalla perdia un 28 % de la luz y la letra
 * pequeña salia troceada—, y se quedo en el 65 %: las mismas tiras, que se siguen viendo,
 * con la raya mas clara y la luz de antes, un 22 % contra el 21 de la de un pixel.
 */
@Composable
fun ScanlineOverlay(alpha: Float = 0.65f, period: Float = 6f, thickness: Float = 2f) {
    Canvas(Modifier.fillMaxSize()) {
        val ink = Color.Black.copy(alpha = alpha)
        var y = 0f
        while (y < size.height) {
            drawRect(ink, Offset(0f, y), Size(size.width, thickness))
            y += period
        }
    }
}
