package com.felp.frontcomp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La grafica con sus lineas: el panel del Mainframe.
 *
 * Lo que habia eran seis cruces de encuadre, las de Signalis, encima de un giro que ocupaba la
 * caja entera. Ahora la grafica es pequeña y de ella salen lineas hacia un rotulo con lo que
 * se sabe de lo que enseña: la ficha tecnica de la consola, o de un juego su genero, su año y
 * lo que pesa. Es como rotula sus planos una maquina: una diagonal que sale de la pieza, un
 * codo y un tramo recto que separa el nombre del dato.
 *
 * Cada linea sale de un LADO distinto de la pieza, en aspa. Saliendo las cuatro de las
 * esquinas de un mismo cuadrado se leian como un marco puesto encima, y no como cuatro cosas
 * señaladas en el objeto. Y de la PIEZA, no del hueco: una caratula vertical deja aire a los
 * lados, un video 4:3 arriba y abajo, y una consola ocupa lo que ocupe dentro de su cuadro
 * negro. Donde queda lo dice la propia vista, ver [calloutTarget].
 *
 * Se dibujan al cambiar de fila, una detras de otra: la linea sale de la pieza, llega al rotulo
 * y el dato se escribe a maquina, como el texto de la caja de abajo. Los puntos de donde salen
 * laten despacio, con el pulso del cursor.
 *
 * Hasta cuatro. Sin rotulos la grafica se queda igual de pequeña y centrada: el hueco es de los
 * rotulos, y que una fila no tenga no es motivo para que la siguiente cambie de tamaño.
 */
@Composable
internal fun CalloutFrame(
    callouts: List<Pair<String, String>>,
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val line = LocalTheme.current.accent
    val ink = MenuInk
    val dim = MenuDim
    val body = MenuBody
    val items = callouts.take(4)
    val target = remember { CalloutTarget() }
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        // Se espera a saber donde esta la pieza, con un tope. Una linea que sale del hueco y
        // un momento despues salta a la caratula se ve como un fallo, no como una animacion.
        withTimeoutOrNull(WAIT_MS) { snapshotFlow { target.bounds }.first { it != null } }
        progress.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
    }
    val pulse = rememberCursorPulse(low = 0.35f)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    // Los dos estilos enteros, y el MISMO para medir que para pintar. El de fondo de la app
    // trae medio punto de espacio entre letras y un renglon de 24: midiendo sin el, «ARM7TDMI
    // 16.8 MHz» parecia caber, no encogia y salia cortado con puntos. Y el dato va sin ese
    // espacio: es letra de ancho fijo, no le hace falta, y son diecisiete letras que no caben.
    val base = LocalTextStyle.current
    val titleStyle = remember(base, body) {
        base.merge(
            TextStyle(
                fontFamily = body, fontSize = TITLE_SP, lineHeight = TITLE_LINE,
                letterSpacing = 1.sp,
            ),
        )
    }
    val valueStyle = remember(base, body) {
        base.merge(
            TextStyle(
                fontFamily = body, fontSize = VALUE_SP, lineHeight = VALUE_LINE,
                letterSpacing = 0.sp,
            ),
        )
    }

    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val gw = w * GRAPHIC_W
        val gh = h * GRAPHIC_H
        val gx = (w - gw) / 2f
        val gy = (h - gh) / 2f

        Box(Modifier.align(Alignment.Center).fillMaxWidth(GRAPHIC_W).fillMaxHeight(GRAPHIC_H)) {
            CompositionLocalProvider(LocalCalloutTarget provides target) { content() }
        }

        // La pieza en esta caja: donde dice la vista que esta, o el hueco entero si no dice
        // nada —un «no image», por ejemplo—.
        val wanted = target.bounds?.translate(-origin) ?: Rect(gx, gy, gx + gw, gy + gh)
        val piece = remember { Animatable(wanted, Rect.VectorConverter) }
        LaunchedEffect(wanted) {
            // Antes de dibujar, se pone sin mas. Con las lineas ya fuera —la caratula que da paso
            // al video, que tiene otra proporcion— se desliza, y las lineas la siguen.
            if (progress.value == 0f && !progress.isRunning) piece.snapTo(wanted)
            else piece.animateTo(wanted, tween(GLIDE_MS, easing = FastOutSlowInEasing))
        }

        val m = with(density) {
            Metrics(
                // Las columnas: de su canto de la caja hasta un poco antes de la grafica. Ahi
                // acaba cada tramo recto y ahi se alinea su texto.
                leftEdge = gx - GUTTER.toPx(),
                rightEdge = gx + gw + GUTTER.toPx(),
                rise = RISE.toPx(),
                leg = MIN_LEG.toPx(),
                inset = INSET.toPx(),
                // Que el nombre, encima de la linea, y el dato, debajo, no se salgan de la caja.
                rowMin = TITLE_LINE.toPx() + PAD.toPx() * 2f,
                rowMax = h - VALUE_LINE.toPx() - PAD.toPx() * 2f,
            )
        }
        val geo = items.indices.map { route(it, piece.value, m) }

        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            val dot = 3.dp.toPx()
            geo.forEachIndexed { i, g ->
                val on = phase(progress.value, i).line
                if (on <= 0f) return@forEachIndexed
                drawRect(
                    line.copy(alpha = pulse.value),
                    Offset(g.anchor.x - dot, g.anchor.y - dot), Size(dot * 2f, dot * 2f),
                )
                // Las dos piernas se reparten el avance por su largo, para que la linea salga a
                // la misma velocidad en la diagonal que en el tramo recto.
                val d1 = (g.elbow - g.anchor).getDistance()
                val d2 = abs(g.end.x - g.elbow.x)
                val drawn = (d1 + d2) * on
                val c = line.copy(alpha = 0.9f)
                if (drawn <= d1) {
                    val f = if (d1 > 0f) drawn / d1 else 1f
                    drawLine(c, g.anchor, g.anchor + (g.elbow - g.anchor) * f, stroke)
                } else {
                    drawLine(c, g.anchor, g.elbow, stroke)
                    val f = if (d2 > 0f) (drawn - d1) / d2 else 1f
                    drawLine(c, g.elbow, Offset(g.elbow.x + (g.end.x - g.elbow.x) * f, g.elbow.y), stroke)
                }
            }
        }

        // Las dos columnas miden lo mismo: de su canto de la caja al final de los tramos.
        val colPx = m.leftEdge.coerceAtLeast(0f)
        val colDp = with(density) { colPx.toDp() }
        val pad = with(density) { PAD.toPx() }
        items.forEachIndexed { i, (title, value) ->
            val g = geo[i]
            val p = phase(progress.value, i)
            val x = if (g.left) 0 else m.rightEdge.roundToInt()
            val align = if (g.left) TextAlign.End else TextAlign.Start
            // El nombre encima del tramo recto y el dato debajo: la linea los separa, como en
            // un plano. El nombre sale con la linea y el dato se escribe cuando ya ha llegado.
            Text(
                typed(title, p.line), color = dim, style = titleStyle, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = align,
                modifier = Modifier.width(colDp).at(x) { tall -> g.end.y - pad - tall },
            )
            // Del tamaño de siempre si cabe, y si no un poco menor: medido con el dato ENTERO,
            // para que no encoja mientras se escribe. Lo que ni asi quepa, con puntos.
            val size = remember(value, colPx, valueStyle) { fitted(measurer, value, valueStyle, colPx) }
            Text(
                typed(value, p.text), color = ink, style = valueStyle.copy(fontSize = size),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                textAlign = align,
                modifier = Modifier.width(colDp).at(x) { g.end.y + pad },
            )
        }
    }
}

/**
 * Donde esta, dentro de la caja, lo que se enseña de verdad.
 *
 * La caja es un hueco y lo que va dentro casi nunca lo llena. La unica que sabe donde queda es
 * la propia vista, que ya lo mide para ponerse el marco, asi que lo dice ella: la caratula
 * cuando sabe su proporcion, el video cuando el reproductor dice su tamaño, el giro cuando se
 * ha medido la consola dentro de su cuadro.
 */
internal class CalloutTarget {
    /** En coordenadas de la raiz. Nulo mientras nadie lo ha dicho. */
    var bounds by mutableStateOf<Rect?>(null)
        private set
    private var owner: Any? = null

    fun report(who: Any, rect: Rect) {
        owner = who
        bounds = rect
    }

    /** Solo borra lo suyo: la caratula que se va no puede borrar el video que acaba de llegar. */
    fun release(who: Any) {
        if (owner === who) {
            owner = null
            bounds = null
        }
    }
}

internal val LocalCalloutTarget = staticCompositionLocalOf<CalloutTarget?> { null }

/** Toda la vista: una caratula o un video son pieza de canto a canto. */
internal val WHOLE = Rect(0f, 0f, 1f, 1f)

/**
 * Donde hay dibujo en una imagen de fondo transparente, en fracciones de su lado.
 *
 * Es la pieza de una marca pintada en un cuadro: el cuadro es cuadrado y la marca casi nunca lo
 * es. Se mira el canal alfa entero una vez —una marca se pinta una vez por tema— y lo casi
 * transparente no cuenta, que es lo que deja el suavizado de los bordes.
 */
internal fun inkBounds(b: android.graphics.Bitmap): Rect {
    val w = b.width
    val h = b.height
    if (w == 0 || h == 0) return WHOLE
    val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
    var left = w
    var top = h
    var right = -1
    var bottom = -1
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            if (px[row + x] ushr 24 > 16) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    if (right < 0) return WHOLE
    return Rect(left / w.toFloat(), top / h.toFloat(), (right + 1) / w.toFloat(), (bottom + 1) / h.toFloat())
}

/**
 * Esto es lo que se enseña, y las lineas salen de sus lados.
 *
 * `piece` es la parte que ocupa el objeto dentro de la vista, en fracciones: todo en una
 * caratula o en un video, y en un giro lo que ocupa la consola dentro de su cuadro negro. Con
 * `enabled` en falso no dice nada: una caratula que aun no sabe su proporcion ocupa el hueco
 * entero, y ahi no es donde va a estar.
 *
 * Fuera del panel del Mainframe no hay nadie escuchando y no hace nada.
 */
internal fun Modifier.calloutTarget(piece: Rect = WHOLE, enabled: Boolean = true): Modifier = composed {
    val target = LocalCalloutTarget.current ?: return@composed Modifier
    val me = remember { Any() }
    val last = remember { arrayOfNulls<Rect>(1) }
    val on by rememberUpdatedState(enabled)
    val part by rememberUpdatedState(piece)
    DisposableEffect(target) { onDispose { target.release(me) } }
    // Al encenderse, o al cambiar la pieza, con lo ultimo que se midio: el aviso de la
    // colocacion solo llega cuando algo se mueve, y encenderse no mueve nada.
    SideEffect {
        val r = last[0]
        if (!enabled) target.release(me)
        else if (r != null) target.report(me, r.part(piece))
    }
    Modifier.onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        last[0] = r
        if (on) target.report(me, r.part(part))
    }
}

private fun Rect.part(f: Rect) =
    Rect(left + width * f.left, top + height * f.top, left + width * f.right, top + height * f.bottom)

/** Lo que ocupa la grafica dentro de la caja: lo que queda a los lados es de los rotulos. */
private const val GRAPHIC_W = 0.40f
private const val GRAPHIC_H = 0.68f

/** El aire entre la grafica y las columnas de texto. */
private val GUTTER = 24.dp
/** Lo que sube o baja la diagonal, si hay sitio. */
private val RISE = 30.dp
/** El tramo recto mas corto: sin el, el codo se pega al texto y no se lee como codo. */
private val MIN_LEG = 8.dp
/** Cuanto se mete hacia dentro el punto de salida, para que caiga encima del canto. */
private val INSET = 3.dp
/** Entre la linea y el texto de encima o de debajo. */
private val PAD = 2.dp

// Un punto y medio mas que al principio: con la lista del Mainframe mas estrecha hay sitio,
// y los rotulos a diez eran de lo que no se leia de la pantalla.
private val TITLE_SP = 11.5.sp
private val TITLE_LINE = 15.sp
private val VALUE_SP = 14.sp
private val VALUE_LINE = 18.sp
/** Hasta aqui encoge un dato largo antes de cortarse con puntos. */
private val VALUE_MIN_SP = 11.sp

private const val STAGGER_MS = 180
private const val LINE_MS = 420
private const val TYPE_MS = 340
private const val TOTAL_MS = STAGGER_MS * 3 + LINE_MS + TYPE_MS
/** Lo mas que se espera a la pieza antes de dibujar sobre el hueco. */
private const val WAIT_MS = 700L
private const val GLIDE_MS = 320

private class Metrics(
    val leftEdge: Float,
    val rightEdge: Float,
    val rise: Float,
    val leg: Float,
    val inset: Float,
    val rowMin: Float,
    val rowMax: Float,
)

private class Geo(val left: Boolean, val anchor: Offset, val elbow: Offset, val end: Offset)

/**
 * Por donde va la linea del rotulo `i`.
 *
 * Sale de un lado distinto de la pieza cada una, en aspa: la de arriba a la izquierda del canto
 * de arriba, la de arriba a la derecha del canto derecho, la de abajo a la izquierda del
 * izquierdo y la de abajo a la derecha del de abajo. Asi ninguna cruza la pieza y ninguna
 * repite lado. De ahi, en diagonal a cuarenta y cinco grados, y en recto hasta su columna.
 *
 * La diagonal sube lo que pida si cabe: no mas de lo que deje la columna —con un tramo recto
 * de minimo— ni de lo que deje la caja para el texto de encima o de debajo.
 */
private fun route(i: Int, o: Rect, m: Metrics): Geo {
    val left = i % 2 == 0
    val up = i < 2
    val anchor = when (i) {
        0 -> Offset(o.left + o.width * 0.28f, o.top + m.inset)
        1 -> Offset(o.right - m.inset, o.top + o.height * 0.30f)
        2 -> Offset(o.left + m.inset, o.top + o.height * 0.70f)
        else -> Offset(o.left + o.width * 0.72f, o.bottom - m.inset)
    }
    val edge = if (left) m.leftEdge else m.rightEdge
    val across = (if (left) anchor.x - edge else edge - anchor.x) - m.leg
    val down = if (up) anchor.y - m.rowMin else m.rowMax - anchor.y
    val run = minOf(m.rise, across, down).coerceAtLeast(0f)
    val elbow = Offset(
        if (left) anchor.x - run else anchor.x + run,
        if (up) anchor.y - run else anchor.y + run,
    )
    return Geo(left, anchor, elbow, Offset(edge, elbow.y))
}

/**
 * Pone lo medido en `x` y a la altura que diga `y` segun lo que mida de alto.
 *
 * Con lo que MIDE y no con un alto fijo: con uno puesto a ojo, la letra del tema no cabia y el
 * dato salia cortado por la mitad.
 */
private fun Modifier.at(x: Int, y: (Float) -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(x, y(placeable.height.toFloat()).roundToInt())
    }
}

/**
 * El tamaño del dato: el de siempre, o menos si entero no cabe en su columna.
 *
 * Las fichas no miden lo mismo —«2 MB» y «Pentium III 733 MHz»— y cortar con puntos la CPU de
 * una Xbox para que la de una Game Boy se lea grande seria perder el dato que se queria dar.
 *
 * Probando de cuarto en cuarto de punto y no con una regla de tres: medido y pintado no casan
 * al pixel, y encogido justo a la medida, un dato de 287 pixeles en una columna de 289 salia
 * cortado igual. De ahi tambien la holgura.
 */
private fun fitted(measurer: TextMeasurer, text: String, style: TextStyle, width: Float): TextUnit {
    if (width <= 0f || text.isEmpty()) return VALUE_SP
    var size = VALUE_SP.value
    while (size > VALUE_MIN_SP.value) {
        val w = measurer.measure(text, style.copy(fontSize = size.sp), softWrap = false, maxLines = 1).size.width
        if (w <= width * FIT_SLACK) break
        size -= 0.25f
    }
    return size.coerceAtLeast(VALUE_MIN_SP.value).sp
}

/** Lo que se deja libre de la columna al encoger un dato. */
private const val FIT_SLACK = 0.97f

private class Phase(val line: Float, val text: Float)

/** Por donde va cada rotulo: el suyo empieza un poco despues del anterior. */
private fun phase(p: Float, i: Int): Phase {
    val t = p * TOTAL_MS - i * STAGGER_MS
    return Phase((t / LINE_MS).coerceIn(0f, 1f), ((t - LINE_MS) / TYPE_MS).coerceIn(0f, 1f))
}

/** El texto escrito hasta donde toque: a maquina, como la caja de abajo. */
private fun typed(s: String, f: Float): String = s.take(ceil(s.length * f).toInt())
