package com.felp.frontcomp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The window that everything modal in this app is made of: settings, the app launcher and
 * the context menus.
 *
 * They are all the same shape on purpose. A frontend has few interactions, and having one
 * of them mean the same thing everywhere is worth more than giving each screen its own
 * idea of what a list looks like.
 */

/**
 * El velo con el que se atenua lo que hay detras de una ventana.
 *
 * Sale del fondo del tema y no es negro fijo. Sobre una sala oscura, negro al ochenta por
 * ciento es exactamente lo que se quiere; sobre un tema de papel, es un apagon, y la ventana
 * deja de leerse como una hoja encima de otra y pasa a ser un agujero.
 */
private val Scrim: Color
    @Composable get() = LocalTheme.current.ground.copy(alpha = if (LocalTheme.current.light) 0.72f else 0.80f)

internal val LocalRowInverted = compositionLocalOf { false }

/**
 * Si lo elegido se marca QUIETO: un marco del acento, sin relleno y sin latido.
 *
 * Lo pide el cuaderno. Alli hay una lista, filtros elegidos y a veces un segundo nivel a la
 * vez, y con todo latiendo la pantalla se cargaba demasiado: el latido dice «estas aqui», y
 * dicho en tres sitios a la vez ya no dice nada. Y sin relleno: un bloque, aunque fuera quieto,
 * seguia pesando mas que todo lo demas, y dentro tapaba el color de cada consola. Solo cambia
 * algo en los temas que invierten lo elegido; los demas ya lo marcan con una barra o un filete.
 */
internal val LocalCalmSelection = compositionLocalOf { false }

/**
 * El acento del tema apagado hacia su blanco: el bloque de la pestaña elegida donde lo
 * elegido se invierte y late, como en los ajustes del Mainframe.
 *
 * Hacia el blanco y no hacia un gris, para que la letra oscura de encima se lea; mitad y mitad
 * sigue siendo el color del tema.
 */
internal fun mutedAccent(accent: Color, ink: Color): Color = lerp(accent, ink, .5f)

/**
 * El aire a los lados de una fila. Lo necesita tambien quien pone titulos encima de las
 * columnas, para que caigan justo sobre sus cifras.
 */
internal val ModalRowPadX: Dp
    @Composable get() = if (LocalTheme.current.selection == SelectionStyle.BAR) 14.dp else 10.dp

/**
 * Lo que hace B en la ventana que se esta mirando, para quien no tiene mando.
 *
 * El rotulo de la cabecera ya dice «B  close» o «B  back»; esto es lo que le permite ADEMAS
 * ser el boton. Es el mismo criterio que el engranaje del cuaderno y que el nombre de su
 * pestana: lo que dibuja un boton tiene que hacer lo que hace ese boton, o se lee como una
 * averia. Con el dedo solo, hasta ahora no habia forma de cerrar una ventana ni de subir un
 * nivel; con la cruceta sigue siendo B, que no cambia.
 *
 * Va en un CompositionLocal y no en un parametro porque hay veintiocho cabeceras y una sola
 * ventana: el valor que hace falta es el que ya tiene ModalWindow —el mismo `onDismiss` que
 * atiende B— y pasarlo a mano por veintiocho sitios seria veintiocho sitios donde se puede
 * pasar otro. Y es justo lo que se quiere en las ventanas de varios niveles: en los ajustes,
 * `onDismiss` sube un panel y solo cierra desde la raiz, asi que el rotulo hace exactamente
 * lo que dice, sea «back» o «close».
 *
 * Nulo quiere decir que no hay ventana alrededor, y entonces el rotulo se queda en rotulo.
 */
internal val LocalDismiss = compositionLocalOf<(() -> Unit)?> { null }

/**
 * A centred window over whatever is behind, which stays visible and dimmed.
 *
 * @param widthFraction narrower for short menus, wider for lists worth scanning.
 */
@Composable
internal fun ModalWindow(
    onDismiss: () -> Unit,
    widthFraction: Float = 0.62f,
    heightFraction: Float = 0.82f,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Lo mismo que atiende B queda a mano de la cabecera, para que su rotulo pueda ser boton.
    CompositionLocalProvider(LocalDismiss provides onDismiss) {
        Box(
            Modifier.fillMaxSize().background(Scrim)
                // El scrim se come el toque para que no llegue a lo que hay detrás.
                .clickable(enabled = false) {}
                // Bajo la barra de estado: una ventana, por grande que sea, no le tapa el
                // reloj ni el engranaje a nadie. Las fracciones de alto son de lo que queda.
                .padding(top = 44.dp),
            contentAlignment = Alignment.Center,
        ) {
            val t = LocalTheme.current
            Column(
                Modifier.fillMaxWidth(widthFraction).fillMaxHeight(heightFraction)
                    // La regla roja de arriba es la firma de un aviso en los menus de la PSX:
                    // el panel es igual que los demas, pero lleva una linea de color en la
                    // cabecera que dice «esto te esta preguntando algo».
                    .panelChrome(MenuGround, top = if (t.chrome == Chrome.RULES) t.accent else null)
                    .padding(horizontal = 22.dp, vertical = 16.dp)
                    .padBack(onDismiss),
                content = content,
            )
        }
    }
}

/**
 * La pista de la cabecera, con la parte de B convertida en boton.
 *
 * La pista se escribe entera como texto —«A  open      B  back»— porque asi se lee de un
 * tiron, y se corta por el ULTIMO «B  » para que solo esa parte responda al dedo. Cortar por
 * el ultimo y no por el primero es lo que la deja a salvo de una pista que nombre otra tecla
 * antes; y si no hay ninguna, o no hay ventana alrededor que cerrar, se queda como estaba.
 *
 * El hueco que se toca es mayor que las letras: ocho puntos por arriba y unos pocos a los
 * lados. Va DESPUES del gesto en la cadena de modificadores, que es lo que hace que el relleno
 * cuente como parte del boton en vez de quedarse fuera; y solo por arriba, porque la fila se
 * alinea por abajo y un relleno inferior movería el renglon.
 */
@Composable
internal fun Hint(hint: String, color: Color = MenuFaint) {
    val dismiss = LocalDismiss.current
    val cut = if (dismiss == null) -1 else hint.lastIndexOf("B  ")
    if (cut < 0) {
        Text(hint, color = color, fontSize = 10.sp, fontFamily = MenuBody, maxLines = 1)
        return
    }
    if (cut > 0) {
        Text(
            // Con el color que le pasan, igual que el tramo de la B.
            //
            // Estaba fijo en MenuFaint y por eso el parametro solo valia para la ultima
            // palabra. Sobre la ceja de un tema de fosforo —una barra del color del acento—
            // eso dejaba «A open · Start app · Select drawer» practicamente invisible y el
            // «B close» al lado bien negro, que es como se vio que pasaba.
            hint.substring(0, cut), color = color, fontSize = 10.sp,
            fontFamily = MenuBody, maxLines = 1,
        )
    }
    Text(
        hint.substring(cut), color = color, fontSize = 10.sp,
        fontFamily = MenuBody, maxLines = 1,
        modifier = Modifier
            // Sin sonido propio: el toque tiene que hacer lo que hace B y nada mas. Quien
            // cierra una ventana ya suena donde corresponde, y anadirlo aqui lo duplicaba.
            .pointerInput(dismiss) { detectTapGestures { dismiss?.invoke() } }
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
    )
}

/**
 * El titulo como lo escribe la cabecera. Los llamantes lo pasan en mayusculas, y una
 * blackletter en mayusculas no se lee, asi que la caja la decide el tema y no cada pantalla.
 */
internal fun frameTitle(title: String, t: Theme): String =
    if (t.upperTitles) title.uppercase()
    else title.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/**
 * Lo ancha que tiene que ser una ventana para que su titulo quepa en UNA linea con el
 * subtitulo y la pista al lado: nunca menos que [min] ni mas que [max] de la pantalla.
 *
 * Es para los menus de algo con nombre, un juego o una consola, donde el titulo es ese nombre
 * y es lo unico que dice de quien es el menu. Con el ancho fijo, uno largo se partia en dos
 * lineas, y en los temas de instrumento la segunda se salia de la ceja. Medido con el mismo
 * estilo con que se pinta, heredado incluido: el cuerpo del tema trae su propio espaciado de
 * letras, y medir sin el se quedaba corto.
 */
@Composable
internal fun headerFraction(title: String, subtitle: String, hint: String, min: Float, max: Float = 0.92f): Float {
    val t = LocalTheme.current
    val base = LocalTextStyle.current
    val measurer = rememberTextMeasurer()
    val head = base.merge(
        TextStyle(fontSize = 18.sp, fontFamily = MenuDisplay, fontWeight = FontWeight.Bold, letterSpacing = t.titleTracking),
    )
    val aside = base.merge(TextStyle(fontSize = 11.sp, fontFamily = MenuBody))
    val small = base.merge(TextStyle(fontSize = 10.sp, fontFamily = MenuBody))
    val shown = frameTitle(title, t)
    val px = remember(shown, subtitle, hint, head, aside, small) {
        fun w(s: String, st: TextStyle) = measurer.measure(s, st, maxLines = 1, softWrap = false).size.width
        w(shown, head) + w(subtitle, aside) + w(hint, small)
    }
    val need = with(LocalDensity.current) { px.toDp() } + HEADER_CHROME
    val screen = LocalConfiguration.current.screenWidthDp.toFloat()
    return (need.value / screen).coerceIn(min, max)
}

/**
 * Lo que la cabecera ocupa ademas de sus tres textos: el margen de la ventana a cada lado
 * (22 + 22), los dos huecos entre textos (12 + 14), el relleno del boton «B» (4 + 4) y el
 * marco, con algo de holgura para que no quede al justo.
 */
private val HEADER_CHROME = 96.dp

/**
 * Window chrome: title, scrolling body, and the explanation of whatever is selected
 * pinned to the bottom so it never moves as you walk the list.
 */
@Composable
internal fun ColumnScope.WindowFrame(
    title: String,
    subtitle: String,
    description: String?,
    hint: String,
    /** Un botón antes del título: es lo que da acceso con el dedo a lo que abre un botón. */
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shown = frameTitle(title, LocalTheme.current)
    // El titulo lleva el peso y se mide el ultimo: un nombre de juego largo se corta con
    // puntos suspensivos en vez de comerse el sitio de los demas. Antes se media primero,
    // ocupaba la fila entera y a la pista «B close» le quedaba un caracter de ancho: salia
    // en vertical, letra a letra, y la fila crecia hasta media ventana.
    // En las cajas de instrumento el rotulo va DENTRO de la ceja del marco, y entonces se escribe
    // con el color del fondo: la ceja es del color del acento y la tinta encima no se leeria.
    // Es la misma idea que la fila invertida, aplicada al canto de la caja.
    val onBrow = LocalTheme.current.chrome == Chrome.INSTRUMENT
    val head = if (onBrow) MenuGround else MenuInk
    val aside = if (onBrow) MenuGround.copy(alpha = .78f) else MenuFaint
    Row(verticalAlignment = Alignment.Bottom) {
        leading?.let {
            Box(Modifier.padding(end = 12.dp, bottom = 2.dp)) { it() }
        }
        Text(
            shown, color = head, fontSize = 18.sp, fontFamily = MenuDisplay,
            fontWeight = FontWeight.Bold,
            letterSpacing = LocalTheme.current.titleTracking,
            // En la ceja, una sola: tiene alto para un renglon, y el segundo se salia por debajo
            // y quedaba encima de la primera fila. Para que no haga falta cortarlo, los menus
            // de algo con nombre ensanchan la ventana: ver `headerFraction`.
            maxLines = if (onBrow) 1 else 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(subtitle, color = aside, fontSize = 11.sp, fontFamily = MenuBody, maxLines = 1)
        Spacer(Modifier.width(14.dp))
        Hint(hint, aside)
    }
    Spacer(Modifier.height(4.dp))
    // Con la ceja no hace falta regleta: la caja ya esta cerrada por arriba.
    if (!onBrow) PanelDivider()

    Box(Modifier.weight(1f)) { content() }

    if (description != null) {
        HairLine()
        Spacer(Modifier.height(10.dp))
        Text(
            description,
            color = MenuDim, fontSize = 11.5.sp, lineHeight = 17.sp,
            fontFamily = MenuBody,
            modifier = Modifier.heightIn(min = 52.dp),
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The rows.
 *
 * Selection follows focus, and the first row takes it when the window opens: a modal that
 * opens with nothing selected answers to no direction on a pad.
 */
@Composable
internal fun ModalRows(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    onActivate: () -> Unit,
    /**
     * Izquierda y derecha sobre la fila elegida: -1 o +1. Devuelve si la fila las atiende; si
     * no, siguen su camino como siempre. Es para las filas que eligen entre unos pocos valores
     * sin abrir otra ventana, como la de los discos.
     */
    onStep: ((Int) -> Boolean)? = null,
    row: @Composable (Int) -> Unit,
) {
    // Una lista vacia no tiene donde poner el foco, y sin foco B no llega a la ventana:
    // se va al sistema, que cierra la ventana entera en vez de volver un nivel.
    if (count == 0) {
        val anchor = rememberFocusRequester()
        // Y lo vuelve a pedir cuando se cierra lo que se abrio encima: la ventana de encima se
        // lleva el foco y al cerrarse no vuelve solo, como con las filas (ver `refocus`).
        AutoFocus(anchor, enabled = LocalPadEnabled.current)
        FocusAnchor(anchor)
        return
    }
    // El foco inicial va a la fila que ya estaba elegida, no a la primera.
    //
    // Con la primera, abrir la lista de colores la SELECCIONABA, y como esa lista aplica al
    // moverse, abrir Ajustes para mirar cambiaba el acento al primero de la lista sin tocar
    // nada. Se captura una vez, al abrir: despues el foco es del usuario.
    val first = remember { selected }
    // Y SOLO una vez. En una lista perezosa, una fila que se sale de la vista se descompone
    // y al volver crea un peticionario nuevo, asi que el foco automatico se disparaba otra
    // vez y devolvia la seleccion a la fila guardada. Subiendo al principio de la lista de
    // colores y bajando una fila, la seleccion saltaba de golpe siete filas abajo.
    val autoFocused = remember { booleanArrayOf(false) }
    // Cuando la lista vuelve a estar a mano —se cerro una ventana abierta ENCIMA de ella, como
    // la de elegir caratula sobre el menu del juego—, el foco vuelve a su fila. La de encima se
    // lo llevo al abrirse y al cerrarse no vuelve solo, y sin foco la cruceta no hace nada.
    val padOn = LocalPadEnabled.current
    var refocus by remember { mutableStateOf(false) }
    // Y a la fila que HABIA al apagarse, no a la que quede elegida. Al apagarse, la fila con el
    // foco deja de poder tenerlo y lo pasa a la siguiente, que tambien se apaga y lo pasa a la
    // otra: cada paso la elegia, y se volvia dos filas mas abajo de donde se estaba.
    val onAt = remember { intArrayOf(selected) }
    val away = remember { booleanArrayOf(false) }
    SideEffect {
        if (!padOn) away[0] = true
        else if (!away[0]) onAt[0] = selected
    }
    LaunchedEffect(padOn) {
        if (padOn && away[0]) {
            away[0] = false
            onSelect(onAt[0])
            refocus = true
        }
    }
    // Si la lista se acorta (una fila que se quito, como «show again» en las apps ocultas), el foco
    // vuelve a una fila: se quedaba en ninguna, A no hacia nada y B cerraba la ventana entera en vez
    // de volver un nivel (revision del 09-10-2026).
    val lastCount = remember { intArrayOf(count) }
    LaunchedEffect(count) {
        if (count < lastCount[0]) {
            if (selected >= count) onSelect(count - 1)
            refocus = true
        }
        lastCount[0] = count
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { runCatching { listState.animateScrollToItem(selected) } }
    // El clic va aqui y no en quien cambia la seleccion, porque la cambian tres cosas: la
    // cruceta, el toque y el foco que vuelve al cerrar un modal. La primera composicion no
    // suena: abrir una lista no es moverse por ella.
    val lastRow = remember { intArrayOf(selected) }
    LaunchedEffect(selected) {
        if (lastRow[0] != selected) Sfx.play(Sfx.Cue.MOVE)
        lastRow[0] = selected
    }

    /*
     * Tocar una fila la elige y DESPUÉS la activa.
     *
     * Estas filas no respondían al dedo en absoluto: padItem solo escucha teclas, así que
     * elegir emulador o cualquier otra cosa solo se podía con la cruceta. Y activar en el
     * mismo gesto no vale, porque onActivate actúa sobre lo que hubiera seleccionado: se
     * apunta el índice y se activa cuando la selección ya es esa.
     */
    var pendingTap by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingTap, selected) {
        val p = pendingTap ?: return@LaunchedEffect
        if (p == selected) { pendingTap = null; onActivate() }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // items(count) en vez de itemsIndexed(List(count)): la version de lista creaba un
        // ArrayList de enteros con caja en cada recomposicion, uno por fila, y esto se
        // recompone en cada movimiento del cursor.
        items(count) { index ->
            val requester = remember { FocusRequester() }
            if (index == first && !autoFocused[0]) {
                AutoFocus(requester)
                LaunchedEffect(Unit) { autoFocused[0] = true }
            }
            if (index == selected && padOn && refocus) {
                LaunchedEffect(Unit) {
                    runCatching { requester.requestFocus() }
                    refocus = false
                }
            }
            val t = LocalTheme.current
            val bar = t.selection == SelectionStyle.BAR
            val inverted = t.selection == SelectionStyle.INVERT
            val calm = LocalCalmSelection.current
            val chosen = index == selected
            Box(
                Modifier.fillMaxWidth()
                    .focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) onSelect(index) }
                    .pointerInput(index) {
                        detectTapGestures { onSelect(index); pendingTap = index }
                    }
                    .then(
                        if (onStep == null || !chosen) Modifier
                        else Modifier.onKeyEvent { e ->
                            val d = when (e.key) {
                                androidx.compose.ui.input.key.Key.DirectionLeft -> -1
                                androidx.compose.ui.input.key.Key.DirectionRight -> 1
                                else -> 0
                            }
                            when {
                                d == 0 -> false
                                // La bajada cambia; la subida solo se come, como en padItem.
                                e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown -> onStep(d)
                                else -> true
                            }
                        }
                    )
                    .padItem(
                        onActivate = onActivate,
                        scaleWhenFocused = 1f,
                        borderColor = Color.Transparent,
                        borderWidth = 0.dp,
                    )
                    // Tres maneras de senalar la fila, y las tres las dice `selection`. Con
                    // BAR el fondo se oscurece y se pinta la barra del borde; con INVERT la
                    // fila se invierte entera y late; con OUTLINE se rodea y ya.
                    .then(
                        when {
                            chosen && bar -> Modifier.selectionBar(t.selectionFill, t.accent, ornament = t.ornament)
                            // Invertida de verdad: el bloque toma el color de la TINTA y el
                            // texto el del fondo. Estaba tomando el de la regleta, y eso solo
                            // funciona si la regleta resulta ser clara: en un tema de terminal
                            // —regleta casi negra sobre fondo negro— la fila elegida salia
                            // como un bloque oscuro con el texto en negro encima, ilegible.
                            // Invertir es cambiar tinta por fondo, no pintar de otro color.
                            //
                            // Salvo en el cuaderno, que lo pide quieto: ver LocalCalmSelection.
                            chosen && inverted && calm -> Modifier.border(2.dp, t.accent)
                            chosen && inverted -> Modifier.pulseFill(MenuInk)
                            chosen -> Modifier.border(1.dp, MenuLine)
                            else -> Modifier
                        }
                    )
                    .padding(horizontal = ModalRowPadX, vertical = 9.dp)
            ) {
                // Solo INVERT invierte el texto: sin eso desapareceria, blanco sobre blanco.
                // Con la barra y con el marco el texto se queda como esta, y con el marco
                // quieto del cuaderno tambien: dentro no hay bloque que invertir.
                CompositionLocalProvider(LocalRowInverted provides (chosen && inverted && !calm)) {
                    row(index)
                }
            }
        }
    }
}

/**
 * One row: what it is on the left, what it is set to on the right.
 *
 * The value sits on the row rather than only in the description so the whole list can be
 * read at a glance, without walking it.
 */
@Composable
internal fun ModalRow(
    label: String,
    value: String = "",
    dimmed: Boolean = false,
    chevron: Boolean = false,
    swatch: Color? = null,
    /** El rombo relleno cuando la fuente está lista, y hueco cuando falta configurarla. */
    swatchFilled: Boolean = true,
) {
    val inverted = LocalRowInverted.current
    val main = if (inverted) MenuGround else if (dimmed) MenuFaint else MenuInk
    val secondary = if (inverted) MenuGround.copy(alpha = .65f) else MenuDim

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Un rombo del color, para las listas de colores: se ve el color antes de leer
        // el nombre.
        if (swatch != null) {
            Canvas(Modifier.size(12.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                if (swatchFilled) lozenge(c, 5.dp.toPx(), swatch)
                else lozengeOutline(c, 5.dp.toPx(), swatch, 1.5.dp.toPx())
            }
            Spacer(Modifier.width(10.dp))
        }
        // El nombre y el valor se reparten el ancho con el nombre por delante. Antes el valor se
        // medía primero y se quedaba con todo lo que quisiera: con una ruta larga de valor, la
        // fila de las carpetas de imagenes se leia «Image fold…» y no se sabia que era. Ahora
        // el nombre entero si cabe, y si no, como poco la mitad; el valor, con lo que quede.
        LabelAndValue(
            label = {
                Text(
                    label, color = main,
                    fontSize = 13.sp, fontFamily = MenuBody,
                    fontWeight = if (inverted) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            value = if (value.isEmpty()) null else ({
                Text(
                    value, color = secondary, fontSize = 12.sp, fontFamily = MenuBody,
                    maxLines = 1, softWrap = false, textAlign = TextAlign.End,
                    // Una ruta se corta por el PRINCIPIO: lo que dice cual es, es su final.
                    overflow = if (value.startsWith("/")) TextOverflow.StartEllipsis
                               else TextOverflow.Ellipsis,
                )
            }),
            modifier = Modifier.weight(1f),
        )
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = secondary, fontSize = 15.sp, fontFamily = MenuBody)
        }
    }
}

/**
 * Un nombre a la izquierda y su valor pegado a la derecha, repartiendose el ancho con el nombre
 * por delante: entero si el valor le deja, y si no, como poco la mitad. El valor se queda lo
 * que sobre y se recorta el. Ver ModalRow.
 */
@Composable
private fun LabelAndValue(
    label: @Composable () -> Unit,
    value: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.layout.Layout(
        content = { label(); value?.invoke() },
        modifier = modifier,
    ) { parts, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val name = parts[0]
        val v = parts.getOrNull(1)
        val nameWant = name.maxIntrinsicWidth(constraints.maxHeight)
        val valueWant = v?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
        val nameW = when {
            v == null -> minOf(nameWant, width)
            nameWant + gap + valueWant <= width -> nameWant
            else -> minOf(nameWant, maxOf(width / 2, width - gap - valueWant))
        }
        val valueW = if (v == null) 0 else minOf(valueWant, (width - nameW - gap).coerceAtLeast(0))
        val a = name.measure(constraints.copy(minWidth = 0, maxWidth = nameW))
        val b = v?.measure(constraints.copy(minWidth = 0, maxWidth = valueW))
        val height = maxOf(a.height, b?.height ?: 0)
        layout(width, height) {
            a.placeRelative(0, (height - a.height) / 2)
            b?.placeRelative(width - b.width, (height - b.height) / 2)
        }
    }
}

/**
 * A button you can land on with the pad.
 *
 * Three states, and they are the three from the PSX system menus: idle is an outline in
 * the line colour; focused is the dark selection fill with the accent on the border and
 * on the text; and `primary` — the YES of a yes/no — wears the fill and the accent border
 * at rest too, so the safe answer and the dangerous one never look the same. In the
 * themes without the bar style the focus ring comes from padItem, as everywhere else.
 */
@Composable
internal fun PadButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    /** Para darle el foco al abrirse la ventana, cuando es lo unico que se puede pulsar. */
    focusRequester: FocusRequester? = null,
) {
    val t = LocalTheme.current
    val bar = t.selection == SelectionStyle.BAR
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = RoundedCornerShape(if (bar) 0.dp else 2.dp)
    val lit = bar && (focused || primary)
    // El toque llama siempre al de AHORA: pointerInput(Unit) se queda con el del primer dibujo,
    // y quien llama puede cambiarlo sin que el boton se vuelva a crear.
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            // El boton solo atendia al mando: padItem es de teclas, y al dedo no le contestaba
            // nadie. En la ventana de renombrar se nota enseguida, porque con el teclado en
            // pantalla lo natural es tocar SAVE, y solo guardaba llegando con la cruceta y A.
            // Por gestos y no con clickable, como en el resto: clickable añade un segundo nodo
            // de foco y la cruceta se atasca en el.
            .pointerInput(Unit) { detectTapGestures { click() } }
            .padItem(
                onActivate = onClick,
                focusRequester = focusRequester,
                scaleWhenFocused = if (bar) 1f else 1.06f,
                borderColor = if (bar) Color.Transparent else null,
                borderWidth = if (bar) 0.dp else 3.dp,
            )
            .background(if (lit) t.selectionFill else Color.Transparent, shape)
            .border(1.dp, if (lit) t.accent else MenuLine, shape)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            if (bar) label.uppercase() else label,
            color = if (bar && focused && !primary) t.accent else MenuInk,
            fontSize = 13.sp, fontFamily = MenuBody,
            fontWeight = if (bar) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = if (bar) t.captionTracking else 0.sp,
        )
    }
}
