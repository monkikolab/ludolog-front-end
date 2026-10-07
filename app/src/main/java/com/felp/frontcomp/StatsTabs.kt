package com.felp.frontcomp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * El contenido de cada pestana del cuaderno.
 *
 * Aparte de la carcasa porque son dos cosas distintas: StatsWindow decide DONDE estas —que
 * pestana, que nivel, que atajos— y esto decide QUE se ve. Con siete pestanas, un solo
 * fichero pasaba de las mil lineas y la navegacion se perdia entre las tablas.
 */

/**
 * La portada: el reparto del tiempo, y como se ha repartido entre los aparatos.
 *
 * El grafico de arriba responde «cuando juego» y el de abajo «a que y en cual».
 *
 * Los cuatro gruesos —hora, dia, semana, mes— van escritos y no escondidos en Select. Es la
 * unica pregunta que esta pantalla sabe contestar de cuatro maneras, y con el grueso solo en
 * el titulo nadie sabia que se podia cambiar. Se cambian con Izquierda y Derecha.
 */
@Composable
internal fun OverviewTab(
    b: Book,
    buckets: List<LogStats.Slice>,
    grain: LogStats.Grain,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
    cover: (String) -> java.io.File?,
    onGrain: (LogStats.Grain) -> Unit,
    onOpen: (String) -> Unit,
    onSession: (LogStats.Entry) -> Unit,
    onAllSessions: () -> Unit,
) {
    Chips(LogStats.Grain.entries.map { "by ${it.label}" }, LogStats.Grain.entries.indexOf(grain)) {
        onGrain(LogStats.Grain.entries[it])
    }
    Spacer(Modifier.height(10.dp))
    Histogram(buckets, accent)
    Spacer(Modifier.height(18.dp))
    Heading("BY CONSOLE AND DEVICE")
    // Con dos aparatos en el registro, el reparto entre ellos primero: es la pregunta que las
    // barras de abajo no contestan —ellas dicen a QUE se juega, esta en cual— y de paso hace
    // de leyenda, porque ensena los dos brillos con el nombre al lado. Con un aparato solo no
    // se dibuja, porque diria lo evidente.
    if (b.devices.size > 1) {
        DeviceSplit(b.splits, b.devices)
        Spacer(Modifier.height(10.dp))
    }
    // Sin las ultimas partidas: ahora van en la portada, con lo que dio cada una (ver CharacterTab).
    StackedBars(b.splits, b.devices, rank, accent, name, onOpen, emptyList(), cover, onSession, onAllSessions)
}

/** Cuantas partidas se ven en la portada, bajo las consolas. */
private const val LAST_SESSIONS = 5

/**
 * Una sola barra con todo el tiempo, partida entre los aparatos.
 *
 * Misma escala de brillo que las barras de abajo —el mas jugado el mas claro— para que la
 * leyenda de aparatos sirva para las dos. Darle a cada aparato un color propio meteria una
 * segunda familia de colores en una pantalla donde el color ya significa consola.
 *
 * Los trozos se reparten POR PESO y no pidiendo cada uno su fraccion del ancho.
 *
 * `fillMaxWidth(f)` dentro de una fila no es «f del ancho de la fila»: es f de lo que QUEDA
 * cuando ya se han medido los anteriores. Con dos aparatos, el segundo pedia su 39% del 39%
 * que sobraba y pintaba un 15%: la barra se quedaba corta y el reparto que ensenaba no era el
 * reparto que decian los numeros de debajo. Con pesos, cada trozo recibe su parte del ancho
 * entero, que es lo que aqui significa la palabra reparto.
 */
@Composable
private fun DeviceSplit(rows: List<LogStats.Split>, devices: List<String>) {
    val deco = LocalTheme.current.ornament
    val byDevice = devices.map { d -> d to rows.sumOf { s -> s.parts.filter { it.first == d }.sumOf { it.second } } }
    Row(Modifier.fillMaxWidth().height(14.dp)) {
        for ((i, d) in byDevice.withIndex()) {
            if (d.second <= 0L) continue
            Box(
                Modifier.weight(d.second.toFloat()).fillMaxHeight()
                    .glass(MenuInk, if (i == 0) .75f else .35f, ornament = deco),
            )
        }
    }
    Spacer(Modifier.height(5.dp))
    Row {
        for ((i, d) in byDevice.withIndex()) {
            Text(
                "${d.first}  ${span(d.second)}",
                color = if (i == 0) MenuDim else MenuFaint, fontSize = 10.5.sp,
                fontFamily = MenuBody,
            )
            Spacer(Modifier.width(18.dp))
        }
    }
}

/**
 * Una barra por consola, partida por aparato.
 *
 * El color es el de la consola, el mismo que en todas partes; lo que separa a un aparato de
 * otro dentro de la barra es el brillo, de mas a menos segun el puesto del aparato en el
 * registro. Usar otro color para el aparato haria dos escalas de color en la misma barra y no
 * se sabria cual leer.
 *
 * Va en una lista y no en una columna suelta, y de ahi salen las tres cosas que le faltaban:
 * se baja con la cruceta, se arrastra con el dedo y se entra en una consola con A. Antes se
 * cortaba a las seis primeras porque no habia manera de ver la septima; ahora estan todas.
 *
 * El largo de cada trozo va por PESO, por lo mismo que en la barra de aparatos: pedir una
 * fraccion del ancho dentro de una fila da una fraccion de LO QUE SOBRA, y las barras salian
 * mas cortas de lo que decian sus propios minutos.
 */
@Composable
private fun StackedBars(
    rows: List<LogStats.Split>,
    devices: List<String>,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
    onOpen: (String) -> Unit,
    recent: List<LogStats.Entry>,
    cover: (String) -> java.io.File?,
    onSession: (LogStats.Entry) -> Unit,
    onAllSessions: () -> Unit,
) {
    if (rows.isEmpty()) {
        Text("nothing yet", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
        return
    }
    // El mayor, no el primero. Son el mismo mientras la lista venga ordenada por tiempo, y
    // «mientras» es exactamente lo que no conviene dar por hecho en la escala de un grafico.
    val top = rows.maxOf { it.totalMs }.coerceAtLeast(1L)
    val deco = LocalTheme.current.ornament
    // Y debajo, las ultimas partidas, en la MISMA lista: con dos listas en la pagina la de
    // arriba se quedaba todo el alto y la de abajo no se veria. La fila de su rotulo es a la vez
    // el enlace a la pestana entera; cada partida abre su ficha, como en SESSIONS.
    val last = recent.take(LAST_SESSIONS)
    val link = rows.size
    val count = if (last.isEmpty()) rows.size else rows.size + 1 + last.size
    val stamp = remember { SimpleDateFormat("d MMM  HH:mm", Locale.ENGLISH) }
    var selected by remember(rows) { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, count - 1)
    ModalRows(
        count = count,
        selected = sel,
        onSelect = { selected = it },
        onActivate = {
            when {
                sel < rows.size -> rows.getOrNull(sel)?.let { onOpen(it.label) }
                sel == link -> onAllSessions()
                else -> last.getOrNull(sel - link - 1)?.let(onSession)
            }
        },
    ) { index ->
        if (index == link) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LAST SESSIONS", color = MenuInk, fontSize = 12.sp, fontFamily = MenuDisplay,
                    letterSpacing = LocalTheme.current.titleTracking, maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text("ALL ›", color = accent, fontSize = 11.sp, fontFamily = MenuBody, maxLines = 1)
            }
            return@ModalRows
        }
        if (index > link) {
            val e = last[index - link - 1]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(cover(e.title), 26.dp, consoleColour(e.system, rank, accent))
                Spacer(Modifier.width(8.dp))
                GameLine(e.title, e.system, rank, accent, name, Modifier.weight(1f), elsewhere(e))
                Cell(stamp.format(Date(e.startedAt)), SESSION_WHEN, MenuDim)
                Cell(span(e.durationMs), SESSION_PLAYED, MenuInk)
            }
            return@ModalRows
        }
        val s = rows[index]
        // El color de la consola sale de su puesto en el REGISTRO, no de su puesto en esta
        // lista. Son casi siempre el mismo numero —esta ordenada por tiempo, igual que el
        // registro— pero «casi» no sirve: en cuanto se filtre algo, la SNES cambiaria de
        // color aqui y seguiria roja en todas las demas pantallas.
        val colour = consoleColour(s.label, rank, accent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name(s.label).uppercase(), color = colour, fontSize = 10.sp,
                fontFamily = MenuBody, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp),
            )
            Row(Modifier.weight(1f).height(9.dp)) {
                for ((label, ms) in s.parts) {
                    if (ms <= 0L) continue
                    val place = devices.indexOf(label).coerceAtLeast(0)
                    Box(
                        Modifier
                            .weight(ms.toFloat())
                            .fillMaxHeight()
                            .glass(colour, if (place == 0) .75f else .35f, ornament = deco),
                    )
                }
                // Y el hueco hasta la mas jugada, tambien por peso: es lo que convierte los
                // trozos de esta fila en una fraccion del ancho entero y no del suyo propio.
                val rest = (top - s.totalMs).toFloat()
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
            Spacer(Modifier.width(10.dp))
            Cell(span(s.totalMs), 60.dp, MenuInk)
        }
    }
    // Sin leyenda de aparatos: la barra de BY DEVICE, justo encima, ya los nombra en el mismo
    // orden y con los mismos brillos.
}

/**
 * Barras verticales con su rotulo debajo. Vacio es una barra de altura cero, no un hueco.
 *
 * El rotulo tiene su sitio reservado y la barra se queda con lo que sobra, en vez de medirse
 * las dos contra la altura entera. Asi, la barra mas alta no puede empujar a su propio rotulo
 * fuera de la fila: antes, el dia mas jugado escribia su fecha un renglon por debajo de las
 * demas, como si fuera de otra tabla.
 */
/**
 * «Nada todavia» en una pagina que es una lista, con donde dejar el foco.
 *
 * Volviendo antes de las filas, la pagina se quedaba sin nada enfocable y el foco sin sitio
 * —al abrir el cuaderno vacio, o al cerrar las pestanas encima de una lista vacia—: B, los
 * hombros y Select no llegaban a la ventana y no habia forma de salir. Las filas vacias de
 * ModalRows traen su ancla.
 */
@Composable
private fun NothingYet() {
    Text("nothing yet", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
    ModalRows(count = 0, selected = 0, onSelect = {}, onActivate = {}) {}
}

@Composable
internal fun Histogram(rows: List<LogStats.Slice>, accent: Color) {
    if (rows.isEmpty()) {
        Text("nothing yet", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
        return
    }
    val top = rows.maxOf { it.totalMs }.coerceAtLeast(1L)
    val deco = LocalTheme.current.ornament
    // Las fechas a diez puntos y no a ocho, que era de lo que costaba leer. A ese tamaño,
    // veintiun dias no caben uno bajo cada barra, asi que se escribe uno de cada dos —o de cada
    // tres— segun lo que quepa, medido: legibles y salteados, mejor que todos y diminutos.
    val labelStyle = LocalTextStyle.current.merge(TextStyle(fontFamily = MenuBody, fontSize = 10.sp))
    val measurer = rememberTextMeasurer()
    // El eje: cuanto vale la barra mas alta, arriba, y el cero en la base. Sin el, las barras
    // decian que un tramo era el doble que otro pero no si eran minutos u horas.
    //
    // Con la misma estructura que cada columna de barras —lo que se estira, el hueco y el
    // renglon de las fechas—, para que el cero caiga justo en la base y no a ojo.
    // Las cifras del eje con el renglon justo de su letra: con el heredado, de veinticuatro para una
    // letra de diez, el cero quedaba flotando un dedo por encima de la base.
    val axisStyle = labelStyle.merge(TextStyle(lineHeight = 10.sp))
    Row(Modifier.fillMaxWidth().height(104.dp)) {
    Column(Modifier.fillMaxHeight().padding(end = 6.dp), horizontalAlignment = Alignment.End) {
        Row(Modifier.weight(1f)) {
            Box(Modifier.fillMaxHeight()) {
                Text(span(top), color = MenuFaint, style = axisStyle, maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd))
                Text("0", color = MenuFaint, style = axisStyle, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomEnd))
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(1.dp).fillMaxHeight().background(MenuFaint.copy(alpha = .45f)))
        }
        Spacer(Modifier.height(4.dp))
        Text("", style = labelStyle, maxLines = 1)
    }
    BoxWithConstraints(Modifier.weight(1f)) {
        val density = LocalDensity.current
        val gap = with(density) { 3.dp.toPx() }
        val air = with(density) { 6.dp.toPx() }
        val column = (constraints.maxWidth - gap * (rows.size - 1)) / rows.size
        val widest = remember(rows, labelStyle) {
            rows.maxOf { measurer.measure(it.label, labelStyle, softWrap = false, maxLines = 1).size.width }
        }
        val stride = if (column <= 0f) 1
                     else kotlin.math.ceil((widest + air) / (column + gap)).toInt().coerceAtLeast(1)
        Row(
            Modifier.fillMaxWidth().height(104.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for ((i, r) in rows.withIndex()) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .fillMaxHeight((r.totalMs.toFloat() / top).coerceAtLeast(0.004f))
                                .capped(accent, if (r.totalMs > 0) .60f else .15f, r.totalMs > 0 && deco),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Mas ancho que su barra si hace falta: se sale por los lados, sobre el hueco
                    // de las que no llevan fecha.
                    Text(
                        if (i % stride == 0) r.label else "", color = MenuFaint, style = labelStyle,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
    }
    }
}

/**
 * Los juegos, el mas jugado arriba, y se entra en cualquiera.
 *
 * Con la caratula al lado. Es la que ya bajo el scraper del front-end: aqui no se busca nada
 * ni se descarga nada, se mira lo que hay.
 */
@Composable
internal fun GamesTab(
    b: Book,
    rank: List<String>,
    accent: Color,
    cover: (String) -> java.io.File?,
    name: (String) -> String,
    feeds: (title: String, system: String?) -> Map<Metagame.Vertex, Double>? = { _, _ -> null },
    onOpen: (LogStats.Ranked) -> Unit,
) {
    Heading("GAMES")
    val rows = remember(b) { b.ranking.sortedByDescending { it.totalMs } }
    if (rows.isEmpty()) {
        NothingYet()
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, rows.lastIndex)
    val frames = mostHave(rows) { it.fps }
    Row(Modifier.padding(horizontal = ModalRowPadX), verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(44.dp))
        HeadLabel("GAME", Modifier.weight(1f))
        HeadCell("PLAYED", GAME_PLAYED)
        if (frames) HeadCell("FRAMES", GAME_FRAMES)
        HeadCell("PEAK", GAME_PEAK)
        HeadCell("DRAIN", GAME_DRAIN)
    }
    Spacer(Modifier.height(4.dp))
    ModalRows(
        count = rows.size,
        selected = sel,
        onSelect = { selected = it },
        onActivate = { onOpen(rows[sel]) },
    ) { index ->
        val g = rows[index]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(cover(g.title), 34.dp, consoleColour(g.system, rank, accent))
            Spacer(Modifier.width(10.dp))
            GameLine(g.title, g.system, rank, accent, name, Modifier.weight(1f), feeds = feeds(g.title, g.system))
            Cell(span(g.totalMs), GAME_PLAYED, MenuInk)
            if (frames) Cell(g.fps?.let { "%.0f fps".format(it) } ?: "—", GAME_FRAMES, MenuDim)
            Cell(g.peakTempC?.let { "%.0f°".format(it) } ?: "—", GAME_PEAK, MenuDim)
            Cell(g.mahPerHour?.let { "%.0f mAh/h".format(it) } ?: "—", GAME_DRAIN, MenuDim)
        }
    }
}

/**
 * Si la mayoria de las filas tienen el dato: si no, su columna no se pinta.
 *
 * Los fotogramas solo se miden con algunos emuladores, y en el cuaderno de dos consolas los
 * traian 7 partidas de 63: la columna era una fila entera de «—», y lo poco que decia estaba en
 * la ficha de cada juego igual.
 */
private fun <T> mostHave(rows: List<T>, value: (T) -> Any?): Boolean =
    rows.count { value(it) != null } * 2 >= rows.size

// Los de la tabla de juegos: «12h 40m», «59 fps», «105°», «2073 mAh/h».
private val GAME_PLAYED = 58.dp
private val GAME_FRAMES = 52.dp
private val GAME_PEAK = 38.dp
private val GAME_DRAIN = 78.dp

/**
 * Una fila por partida, la mas reciente arriba, con su caratula.
 *
 * Es el cuaderno en su forma mas literal: que jugaste, cuando, cuanto y lo que costo.
 */
@Composable
internal fun SessionsTab(
    rows: List<LogStats.Entry>,
    rank: List<String>,
    accent: Color,
    cover: (String) -> java.io.File?,
    name: (String) -> String,
    feeds: (title: String, system: String?) -> Map<Metagame.Vertex, Double>? = { _, _ -> null },
    onOpen: (LogStats.Entry) -> Unit,
) {
    Heading("SESSIONS")
    if (rows.isEmpty()) {
        NothingYet()
        return
    }
    // En ingles, como el resto del programa, y con el mes de tres letras: en el idioma del aparato
    // salia «22 sept.», que ni cuadraba con el texto de al lado ni median lo mismo dos meses, y
    // la columna de la hora bailaba de fila en fila.
    val stamp = remember { SimpleDateFormat("d MMM  HH:mm", Locale.ENGLISH) }
    var selected by remember(rows) { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, rows.lastIndex)
    val frames = mostHave(rows) { it.fpsMean }
    // Los titulos de las columnas, con los mismos anchos que las celdas de debajo.
    Row(Modifier.padding(horizontal = ModalRowPadX), verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(40.dp))
        HeadLabel("GAME", Modifier.weight(1f))
        HeadCell("WHEN", SESSION_WHEN)
        HeadCell("PLAYED", SESSION_PLAYED)
        HeadCell("BATTERY", SESSION_BATTERY)
        HeadCell("PEAK", SESSION_PEAK)
        if (frames) HeadCell("FRAMES", SESSION_FRAMES)
    }
    Spacer(Modifier.height(4.dp))
    ModalRows(
        count = rows.size,
        selected = sel,
        onSelect = { selected = it },
        onActivate = { onOpen(rows[sel]) },
    ) { index ->
        val e = rows[index]
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Sin el cuadradito de color que habia aqui: la consola ya va escrita debajo del
            // titulo y en el mismo color, asi que el cuadrado decia lo mismo dos veces.
            Cover(cover(e.title), 30.dp, consoleColour(e.system, rank, accent))
            Spacer(Modifier.width(10.dp))
            GameLine(e.title, e.system, rank, accent, name, Modifier.weight(1f), elsewhere(e), feeds(e.title, e.system))
            Cell(stamp.format(Date(e.startedAt)), SESSION_WHEN, MenuDim)
            Cell(span(e.durationMs), SESSION_PLAYED, MenuInk)
            // Con el cargador puesto no hay gasto que medir: el contador sube mientras se juega.
            // Eran la mitad de los «—» de la columna, y parecian partidas sin medir.
            Cell(
                e.mah?.let { "%.0f mAh".format(it) } ?: if (e.charged) "charging" else "—",
                SESSION_BATTERY, MenuDim,
            )
            Cell(e.tempMaxC?.let { "%.0f°".format(it) } ?: "—", SESSION_PEAK, MenuDim)
            if (frames) Cell(e.fpsMean?.let { "%.0f fps".format(it) } ?: "—", SESSION_FRAMES, MenuDim)
        }
    }
}

// Los anchos de las columnas de partidas, a once puntos: lo que ocupa lo mas largo que puede
// salir en cada una —«22 Sep  01:45», «2h 13m», «123 mAh», «105°», «59 fps»— y su titulo.
internal val SESSION_WHEN = 96.dp
internal val SESSION_PLAYED = 52.dp
private val SESSION_BATTERY = 60.dp
private val SESSION_PEAK = 38.dp
private val SESSION_FRAMES = 52.dp

/**
 * El titulo de un juego con su consola debajo.
 *
 * La consola va en SU color —el mismo que tiene en la portada, en las barras y en todo lo
 * demas— y no en un gris cualquiera. Eso es lo que convierte el color en un idioma: visto una
 * vez que la barra morada es PlayStation, la palabra morada debajo de un titulo ya no hay que
 * leerla, se reconoce.
 *
 * En dos lineas y no pegada al titulo con un punto en medio: asi, los titulos largos se comen
 * la consola al recortarse, y es justo en los titulos largos —los de las consolas de disco—
 * donde mas falta hace saber de donde sale.
 */
@Composable
internal fun GameLine(
    title: String,
    system: String?,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
    modifier: Modifier = Modifier,
    /** En que consola se jugo, si no fue en esta: ver [elsewhere]. */
    device: String? = null,
    /** A que vertices del pentagono va lo jugado en el: ver [MiniPentagon]. */
    feeds: Map<Metagame.Vertex, Double>? = null,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            feeds?.let {
                MiniPentagon(it)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                title, color = MenuInk, fontSize = 11.sp, fontFamily = MenuBody,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SystemLabel(system, rank, accent, name)
            device?.let {
                Text(
                    "  ·  ${it.uppercase()}", color = MenuFaint, fontSize = 10.sp,
                    fontFamily = MenuBody, letterSpacing = 1.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * La consola de una partida, si no es esta. Con los cuadernos de otras consolas al lado, la
 * lista de partidas las mezcla, y sin decirlo una partida de otra consola parecia jugada aqui.
 */
internal fun elsewhere(e: LogStats.Entry): String? = e.device.takeIf { it.isNotEmpty() && it != HERE }

/** Como se llama esta consola en el cuaderno: lo que apunta el seguidor en cada partida. */
private val HERE: String = android.os.Build.MODEL.orEmpty().ifEmpty { "handheld" }

/**
 * El nombre de una consola en su color.
 *
 * El color es `categorical` sobre el puesto de la consola en el registro ENTERO, que es el
 * mismo calculo que hacen las barras: asi la palabra y la barra salen del mismo sitio y no
 * hay dos sistemas de color compitiendo.
 *
 * Sin puesto no hay color. Pintarla como la primera del registro seria decir que es otra
 * consola, y eso es peor que no decir nada.
 */
@Composable
private fun SystemLabel(
    system: String?,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
) {
    // A diez y no a ocho: era el texto mas pequeño del cuaderno, y justo el que dice de donde
    // sale cada cosa.
    Text(
        system?.let { name(it).uppercase() } ?: "—",
        color = consoleColour(system, rank, accent),
        fontSize = 10.sp, fontFamily = MenuBody, letterSpacing = 1.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * La caratula de un juego, pequena.
 *
 * Un hueco del mismo tamano cuando no hay: sin el, las filas con arte y las que no quedarian
 * desalineadas y la columna del titulo bailaria de una a otra.
 */
@Composable
internal fun Cover(file: java.io.File?, side: androidx.compose.ui.unit.Dp, tint: Color? = null) {
    Box(Modifier.size(side * 0.75f, side), contentAlignment = Alignment.Center) {
        if (file != null) {
            AsyncImage(
                model = artModel(file), contentDescription = null, contentScale = ContentScale.Fit,
                colorFilter = phosphorFilter(),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        } else if (tint != null) {
            // Sin caratula —un juego de otra consola, que aqui no tiene fichero, o uno sin arte—,
            // el hueco en el color de su consola, como la palabra de debajo. Gris parecia una
            // imagen que no habia cargado.
            Box(
                Modifier.fillMaxWidth().fillMaxHeight().background(tint.copy(alpha = .18f))
                    .border(1.dp, tint.copy(alpha = .55f)),
            )
        } else {
            Box(Modifier.fillMaxWidth().fillMaxHeight().background(MenuLine))
        }
    }
}

/**
 * Una sola pantalla para bateria, calor, velocidad y consumo.
 *
 * Es la forma de RetroCompanion traida entera, y por la misma razon que alli: las cuatro hacen
 * la misma pregunta —«que juegos cuestan mas»— a columnas distintas. Cuatro pantallas
 * separadas acaban divergiendo; una usada cuatro veces, no.
 *
 * Lo que se mide y como se agrupa se eligen aqui mismo, en las dos hileras de arriba, y se
 * cambian con Izquierda y Derecha sin salir de la lista. Antes esto eran tres tablas fijas de
 * cinco filas cada una: no habia forma de ver la sexta, ni de preguntar lo mismo por consola.
 *
 * La barra ocupa el ancho entero y el rotulo va encima, no al lado. Con el rotulo a la
 * izquierda la barra se quedaba en un tercio de la pantalla y todas parecian igual de largas,
 * que es justo lo que una barra esta para evitar.
 */
@Composable
internal fun MetricTab(
    rows: List<LogStats.Row>?,
    metrics: List<LogStats.Metric>,
    metric: LogStats.Metric,
    groupBy: LogStats.GroupBy,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
    note: String,
    onMetric: (Int) -> Unit,
    onGroupBy: (LogStats.GroupBy) -> Unit,
    onOpen: (LogStats.Row) -> Unit,
    footer: (@Composable () -> Unit)? = null,
    /** Si la lista cuenta solo esta consola, habiendo otras en el cuaderno: se dice arriba. */
    onlyHere: Boolean = false,
) {
    // La pista va en la fila de abajo, que siempre son tres, y no en la de arriba, que en
    // velocidad son cinco y no dejaban sitio: "measure" se partia en dos renglones encima de
    // las propias pestanas.
    Chips(metrics.map { "${it.label} ${it.unit}" }, metrics.indexOf(metric), onPick = onMetric)
    Spacer(Modifier.height(5.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Chips(
            LogStats.GroupBy.entries.map { it.label },
            LogStats.GroupBy.entries.indexOf(groupBy),
        ) { onGroupBy(LogStats.GroupBy.entries[it]) }
        Spacer(Modifier.weight(1f))
        Text(
            "↑  filters      A  choose",
            color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody, maxLines = 1,
        )
    }
    Spacer(Modifier.height(9.dp))
    HairLine()
    Spacer(Modifier.height(8.dp))
    if (onlyHere) {
        Text(
            "Played on this console only. By device compares them.",
            color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody, maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
    }

    if (rows == null) {
        Text("working it out…", color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody)
        return
    }
    if (rows.isEmpty()) {
        Text("nothing to compare yet", color = MenuInk, fontSize = 11.sp, fontFamily = MenuBody)
        Spacer(Modifier.height(4.dp))
        Text(note, color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody)
        footer?.let { Spacer(Modifier.height(12.dp)); it() }
        return
    }

    val top = rows.maxOf { it.value }.takeIf { it > 0f } ?: 1f
    var selected by remember(metric, groupBy) { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, rows.lastIndex)
    // Una fila mas al final: la nota y, en bateria, la salud. Van DENTRO de la lista y no
    // debajo para que se pueda bajar hasta ellas; fuera, con veinte juegos por delante,
    // quedarian tapadas para siempre.
    ModalRows(
        count = rows.size + 1,
        selected = sel,
        onSelect = { selected = it },
        onActivate = { rows.getOrNull(sel)?.let(onOpen) },
    ) { index ->
        if (index == rows.size) {
            Column {
                Text(note, color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody)
                footer?.let { Spacer(Modifier.height(12.dp)); it() }
            }
            return@ModalRows
        }
        val r = rows[index]
        // Agrupado por consola, la barra y el nombre van del color de esa consola: es el mismo
        // emparejamiento que usa la portada, asi que un tono aprendido alli se lee aqui.
        // Agrupado de cualquier otra forma, las barras van del acento y el color se lo queda
        // la consola que va escrita al final de la linea de abajo.
        val byConsole = groupBy == LogStats.GroupBy.SYSTEM
        val tint = consoleColour(if (byConsole) r.label else r.system, rank, accent)
        RankBar(
            label = if (byConsole) name(r.label).uppercase() else r.label,
            labelColour = if (byConsole) tint else MenuInk,
            value = "${figure(r.value)} ${metric.unit}",
            fraction = r.value / top,
            colour = if (byConsole) tint else accent,
            system = if (groupBy == LogStats.GroupBy.GAME) r.system else null,
            systemName = { name(it).uppercase() },
            systemColour = tint,
            totalMs = r.totalMs,
            sessions = r.sessions,
        )
    }
}

/**
 * Una fila del ranking: rotulo arriba, barra de ancho entero, y debajo de que esta hecha.
 *
 * Las tres lineas dicen cosas distintas a proposito. Arriba, que es y cuanto. En medio, cuanto
 * comparado con el resto —que es lo unico que una barra sabe hacer y una cifra no. Y abajo, de
 * cuanto material sale esa cifra, porque «el juego que mas calienta» con tres minutos detras y
 * con nueve horas no son la misma afirmacion.
 */
@Composable
private fun RankBar(
    label: String,
    labelColour: Color,
    value: String,
    fraction: Float,
    colour: Color,
    system: String?,
    systemName: (String) -> String,
    systemColour: Color,
    totalMs: Long,
    sessions: Int,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label, color = labelColour, fontSize = 11.sp, fontFamily = MenuBody,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(value, color = colour, fontSize = 11.sp, fontFamily = MenuBody)
        }
        Spacer(Modifier.height(3.dp))
        // El canal detras, muy tenue: sin el, una barra corta flota sola y no se ve contra
        // que es corta.
        Box(
            Modifier.fillMaxWidth().height(8.dp).background(colour.copy(alpha = .10f)),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                    .glass(colour, .70f, ornament = LocalTheme.current.ornament),
            )
        }
        Spacer(Modifier.height(3.dp))
        Row {
            Text(
                "${span(totalMs)}  ·  ${if (sessions == 1) "1 session" else "$sessions sessions"}",
                color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody,
            )
            if (system != null) {
                Text("  ·  ", color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody)
                Text(
                    systemName(system), color = systemColour, fontSize = 10.sp,
                    fontFamily = MenuBody, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Una hilera de opciones, con la de ahora encendida.
 *
 * Se recorren con la cruceta y se tocan con el dedo. Se aplican al confirmar y no al pasar por
 * encima: con el filtro siguiendo al foco, acercarse a la hilera desde la lista CAMBIABA el
 * filtro por el hecho de acercarse —estando en «by day», la primera pulsacion hacia arriba lo
 * dejaba en «by hour»— y de paso lanzaba una consulta por cada una que se atravesaba.
 *
 * La forma no es un rectangulo. Los lados se cortan en pico a media altura, como el remate de
 * un galon: es la misma geometria de los rombos de las reglas y de la punta del cursor, asi
 * que una pestana no parece un boton de otro sitio pegado encima de la sala.
 */
@Composable
internal fun Chips(
    labels: List<String>,
    chosen: Int,
    /** Entre una pestana y la siguiente. Los ajustes, con siete, las juntan mas. */
    gap: Dp = 14.dp,
    /** Aire a cada lado del nombre, dentro de la pestana. */
    padX: Dp = 12.dp,
    /**
     * Si la cruceta puede pararse en ellas.
     *
     * En los ajustes no: alli se cambia de pestana con los hombros, y la lista de debajo se
     * rehace con cada una. Al irse la vieja, Compose le daba el foco de paso a la primera
     * pestana, que lo celebraba con su clic, y el cambio sonaba dos veces.
     */
    focusable: Boolean = true,
    /**
     * El tamaño del nombre. Diez y medio y no nueve: a nueve, los filtros del cuaderno eran de
     * lo que costaba leer, y a esto caben igual —si no, la fila encoge sola, ver abajo—.
     */
    fontSize: TextUnit = 10.5.sp,
    /**
     * Nombres en negrita y todos con la tinta, no solo el elegido.
     *
     * Para las pestañas de los ajustes, que son los titulos de lo que hay debajo y no filtros
     * de un grafico: se tienen que leer como titulos, no en gris y a nueve puntos. La elegida lo
     * sigue diciendo su marca, que es lo que ya lo decia; el color del texto no hacia falta.
     */
    strong: Boolean = false,
    /**
     * Si la fila se desplaza de lado en vez de encoger la letra cuando no cabe.
     *
     * Para los ajustes (07-10-2026): ocho pestañas, y con cada una que se añada habria que
     * ensanchar la ventana o achicar mas la letra. Asi la letra se queda a su tamaño y la elegida
     * se trae a la vista al cambiar con los hombros.
     */
    scroll: Boolean = false,
    onPick: (Int) -> Unit,
) {
    // El toque llama siempre al de AHORA. pointerInput(i) guarda el del primer toque y no se
    // entera de uno nuevo; en el cuaderno las paginas de calor, fotogramas, reloj y consumo
    // comparten estas pestanas, y tocar una despues de cambiar de pagina le ponia a esta la
    // cifra de la otra: SPEED listando grados.
    val pick by rememberUpdatedState(onPick)
    val t = LocalTheme.current
    // Los colores se resuelven AQUI y se le pasan hechos al modificador: el lambda de dibujo
    // no es composable y no ve ni el tema ni los colores de la sala.
    val accent = t.accent
    val ink = MenuInk
    val deco = t.ornament
    val inverted = t.selection == SelectionStyle.INVERT
    // En los temas que invierten lo elegido —el Mainframe—, las pestañas en blanco, letra
    // y regla, y la elegida un bloque del acento APAGADO, que el latido hace el resto. La letra
    // era la del `faint`, que en ese tema era rojo y no se leia; y la elegida era un bloque de
    // tinta tan blanco como el cursor de la lista, asi que las dos cosas se confundian.
    val line = if (inverted) ink.copy(alpha = .70f) else MenuLine
    val chosenFill = if (inverted) mutedAccent(accent, t.ink) else ink
    val calm = inverted && LocalCalmSelection.current
    val weight = if (strong) FontWeight.Bold else null
    val style = LocalTextStyle.current.merge(
        TextStyle(fontFamily = MenuBody, letterSpacing = 1.sp, fontWeight = weight),
    )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        // Si la fila no cabe en su sitio, la letra encoge lo justo en vez de que la ultima
        // pestana se corte: siete pestanas en negrita a once puntos pasaban del ancho de la
        // ventana del Mainframe, y la de los datos salia como «DA». Medido con los
        // nombres ENTEROS y con el mismo estilo con que se pintan, que en cada tema es otra letra.
        val fixed = with(LocalDensity.current) { (padX * 2 * labels.size + gap * (labels.size - 1)).toPx() }
        val room = constraints.maxWidth - fixed
        val bounded = constraints.hasBoundedWidth
        //
        // Se mide otra vez a cada paso en vez de encoger en proporcion: el aire entre letras no
        // encoge con la letra, y la cuenta proporcional se quedaba corta por unos pixeles. Y si a
        // ocho puntos tampoco cabe, se quita ese aire antes de bajar mas: la letra del Gallery es
        // ancha, y la ultima pestaña de los ajustes salia como «DA» (07-10-2026). Cada pestaña
        // redondea su ancho hacia arriba: un pixel por pestaña de margen.
        val (size, spacing) = remember(labels, room, style, fontSize, bounded) {
            fun fits(sp: Float, ls: TextUnit) = labels.sumOf {
                measurer.measure(it.uppercase(), style.copy(fontSize = sp.sp, letterSpacing = ls), softWrap = false, maxLines = 1)
                    .size.width
            } <= room - labels.size
            if (scroll || !bounded || fits(fontSize.value, style.letterSpacing)) fontSize to style.letterSpacing
            else {
                var sp = fontSize.value
                var ls = style.letterSpacing
                while (sp > 8f && !fits(sp, ls)) sp -= 0.25f
                if (!fits(sp, ls)) {
                    ls = 0.sp
                    while (sp > 6.5f && !fits(sp, ls)) sp -= 0.25f
                }
                sp.sp to ls
            }
        }
        val sideways = if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier
        Row(Modifier.focusGroup().then(sideways), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for ((i, l) in labels.withIndex()) {
                val on = i == chosen
                var focused by remember { mutableStateOf(false) }
                // La elegida, siempre a la vista cuando la fila se desplaza.
                val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                if (scroll) LaunchedEffect(on) { if (on) bring.bringIntoView() }
                Box(
                    Modifier
                        .then(if (scroll) Modifier.bringIntoViewRequester(bring) else Modifier)
                        // Suena al llegar el foco, como cualquier otra cosa por la que se pasa.
                        .onFocusChanged {
                            if (it.isFocused && !focused) Sfx.play(Sfx.Cue.MOVE)
                            focused = it.isFocused
                        }
                        .pointerInput(i) { detectTapGestures { Sfx.play(Sfx.Cue.MOVE); pick(i) } }
                        .padItem(
                            onActivate = { onPick(i) },
                            enabled = focusable,
                            scaleWhenFocused = 1f,
                            borderColor = Color.Transparent,
                            borderWidth = 0.dp,
                        )
                        .then(
                            // Con la seleccion INVERT la pestana puesta es un bloque que late,
                            // como todo lo demas que esta elegido. El subrayado vale para un tema
                            // de papel; aqui la regla es una sola: lo elegido se enciende. Lo
                            // decidia `phosphor`, que habla del color: era la quinta forma de
                            // marcar lo elegido que no preguntaba a `selection`.
                            //
                            // En el cuaderno, quieto —ver LocalCalmSelection—: el cursor, si esta
                            // en la hilera, es un marco del acento sin relleno, como el de la
                            // lista; y el filtro puesto, un velo del acento sobre el texto quieto,
                            // con su regla. Con el filtro latiendo ademas de la fila, la pantalla
                            // no paraba.
                            when {
                                calm && focused -> Modifier.border(2.dp, accent)
                                // El velo aparte: sin adorno, la regla solo pinta su subrayado.
                                calm && on -> Modifier.background(accent.copy(alpha = .22f))
                                    .splitRule(edge = accent, fill = Color.Transparent, solid = true, ornament = deco)
                                inverted && on -> Modifier.pulseFill(chosenFill)
                                else -> Modifier.splitRule(
                                    edge = when {
                                        on -> accent
                                        focused -> ink
                                        else -> line
                                    },
                                    fill = if (on) accent.copy(alpha = .12f) else Color.Transparent,
                                    solid = on,
                                    ornament = deco,
                                )
                            }
                        )
                        .padding(horizontal = padX, vertical = 6.dp),
                ) {
                    Text(
                        l.uppercase(),
                        // Sobre un bloque encendido se escribe con el fondo.
                        color = when {
                            calm && focused -> MenuInk
                            calm -> MenuInk
                            inverted && on -> MenuGround
                            on || focused || strong || inverted -> MenuInk
                            else -> MenuFaint
                        },
                        style = style.copy(fontSize = size, letterSpacing = spacing), maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * La regla partida: el divisor de los paneles, hecho pestana.
 *
 * Dos reglas y ningun costado. Una caja cerrada encierra la palabra; dos reglas la subrayan,
 * que es lo unico que hace falta para decir donde empieza y donde acaba una opcion. Es como
 * lo resuelven los menus de Dark Souls, y por la misma razon: a la distancia a la que se mira
 * esto, cuatro lados son cuatro cosas que mirar y dos son dos.
 *
 * La de arriba se abre en el centro y en el hueco va el rombo —el mismo `lozenge` que remata
 * las reglas de los paneles, relleno cuando esta puesta y hueco cuando no—, y la de abajo se
 * queda entera para que el bloque tenga suelo. Asi una pestana no es un adorno nuevo: es un
 * trozo de la regla que ya divide la sala.
 */
private fun Modifier.splitRule(
    edge: Color,
    fill: Color,
    solid: Boolean,
    /** Si el tema decora. En falso son dos reglas enteras y ningun rombo. */
    ornament: Boolean = true,
): Modifier = drawBehind {
    val thick = if (solid) 1.6f else 1f
    // Sin adorno, una pestana es un SUBRAYADO y no un bloque acotado.
    //
    // Dos reglas, una arriba y otra abajo, son las de los paneles de la sala partidas en
    // trozos: reconocibles aunque se les quite el rombo. La que se usa hoy en todas partes es
    // la linea de debajo, gruesa en la elegida y fina en las demas, y ademas dice por si sola
    // cual esta puesta sin necesitar ni relleno ni marco.
    if (!ornament) {
        val rule = if (solid) 2.5f else 1f
        drawRect(edge, topLeft = Offset(0f, size.height - rule), size = Size(size.width, rule))
        return@drawBehind
    }
    val r = 3.5.dp.toPx()
    // El hueco es algo mas ancho que el rombo: pegados, la regla y la punta se tocan y el
    // adorno deja de leerse como un adorno.
    val gap = r * 2.2f
    val cx = size.width / 2f
    if (fill != Color.Transparent) drawRect(fill)
    drawRect(edge, size = Size(cx - gap, thick))
    drawRect(edge, topLeft = Offset(cx + gap, 0f), size = Size(cx - gap, thick))
    drawRect(edge, topLeft = Offset(0f, size.height - thick), size = Size(size.width, thick))
    if (solid) lozenge(Offset(cx, 0f), r, edge) else lozengeOutline(Offset(cx, 0f), r, edge, 1f)
}

/**
 * El color de una consola por su puesto en el registro, o nada si no esta en el.
 *
 * Composable porque el gris de «no esta» lo pone el tema, no esta constante.
 */
@Composable
internal fun consoleColour(system: String?, rank: List<String>, accent: Color): Color {
    val at = rank.indexOf(system ?: "?")
    // Contra el fondo que tenga DEBAJO, no contra el del tema. Dentro de una fila invertida el
    // fondo es claro aunque el tema sea oscuro, y el tono vivo que alli se lee como un color se
    // lee aqui como un subrayado de rotulador.
    val onLight = LocalTheme.current.light || LocalRowInverted.current
    return if (at >= 0) categorical(accent, at, onLight) else MenuFaint
}

/** Tres cifras significativas, que es lo que cabe sin que la columna baile. */
private fun figure(v: Float): String = when {
    v >= 100f -> v.toInt().toString()
    v >= 10f -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

/** La salud de la bateria, debajo del ranking de la pestana de bateria. */
@Composable
internal fun BatteryHealth(b: Book) {
    val h = b.health
    // Hasta 100: una consola de pruebas declara de fabrica 7.838 mAh y aguanta 8.705, y «111 %» se leia como
    // un fallo. Las dos cifras siguen debajo, tal cual.
    val health = if (h.fullUah != null && h.designUah != null && h.designUah > 0) {
        (h.fullUah * 100f / h.designUah).coerceAtMost(100f)
    } else null
    // La pila, dibujada: llena hasta lo que aguanta, y con su estado dicho a la manera de cada tema.
    if (health != null) {
        Heading("BATTERY HEALTH")
        BatteryGauge(health, h.cycles)
        Spacer(Modifier.height(10.dp))
    }
    Figures(
        if (health != null) null else "BATTERY HEALTH",
        listOfNotNull(
            h.fullUah?.let { "Holds now" to "${it / 1000} mAh" },
            h.designUah?.let { "From new" to "${it / 1000} mAh" },
            b.chargeNowUah?.let { "Right now" to "${it / 1000} mAh" },
        ),
        listOfNotNull(
            // La salud va en la pila dibujada, y los ciclos tambien si la hay.
            h.cycles?.takeIf { health == null }?.let { "Cycles" to it.toString() },
        ),
    )
}

/**
 * La salud de la bateria como una pila: el cuerpo lleno hasta lo que aguanta hoy frente a nueva,
 * en el acento si esta bien y apagandose si no. Al lado, el estado en tres niveles, con las
 * palabras de cada tema: el Parlour habla de una vida que se apaga, el Mainframe de un parte
 * tecnico, el gallery lo dice llano.
 */
@Composable
private fun BatteryGauge(health: Float, cycles: Int?) {
    val t = LocalTheme.current
    val level = when {
        health >= 80f -> 0
        health >= 60f -> 1
        else -> 2
    }
    val colour = when (level) {
        0 -> t.accent
        1 -> t.accent.copy(alpha = .70f)
        else -> Color(0xFFE0805F)
    }
    val words = when (t.id) {
        "parlour" -> listOf("The cell's heart beats strong.", "The cell grows weary.", "The cell is dying.")
        "mainframe" -> listOf("CELL INTEGRITY NOMINAL", "CELL INTEGRITY DEGRADED", "CELL INTEGRITY CRITICAL")
        else -> listOf("In good shape.", "Showing its age.", "Worn out: consider a replacement.")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val deco = t.ornament
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(150.dp).height(46.dp).border(2.dp, MenuInk.copy(alpha = .7f)).padding(4.dp)) {
                Box(Modifier.fillMaxWidth((health / 100f).coerceIn(0.03f, 1f)).fillMaxHeight().glass(colour, .80f, ornament = deco))
            }
            // El borne.
            Box(Modifier.width(7.dp).height(18.dp).background(MenuInk.copy(alpha = .7f)))
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text("%.0f %%".format(health), color = colour, fontSize = 22.sp, fontFamily = MenuDisplay)
            Text(words[level], color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody)
            cycles?.let { Text("$it charge cycles", color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody) }
        }
    }
}

/**
 * Dos columnas de cifras, solo con las que se saben.
 *
 * Una fila «—» no dice nada, y un bloque entero de ellas menos: muchas partidas no traen los
 * fotogramas, la carga o el consumo, y en algunos aparatos no se pueden leer nunca. Si no se
 * sabe ninguna, ni el titulo.
 */
@Composable
private fun Figures(heading: String?, left: List<Pair<String, String>>, right: List<Pair<String, String>>) {
    if (left.isEmpty() && right.isEmpty()) return
    heading?.let { Heading(it) }
    Row {
        Column(Modifier.width(230.dp)) { left.forEach { (label, value) -> Figure(label, value) } }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.width(230.dp)) { right.forEach { (label, value) -> Figure(label, value) } }
    }
}

/**
 * Una consola: lo que le has echado, y a que.
 *
 * Dos columnas de cifras y no tres. Con tres de ancho fijo la suma pasaba del hueco que queda
 * a la derecha del avatar y la ultima se estrujaba hasta partir "489 mAh/h" en vertical.
 */
@Composable
internal fun ConsoleView(
    id: String,
    name: String,
    accent: Color,
    cover: (String) -> java.io.File?,
    onOpen: (String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val data by produceState<Pair<LogStats.ConsoleSummary, List<LogStats.Slice>>?>(null, id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Logbook(ctx, withOthers = true).use { db ->
                    val s = LogStats(db)
                    s.console(id) to s.gamesOf(id)
                }
            }.getOrNull()
        }
    }

    Title(name)
    val d = data
    if (d == null) {
        Text("reading…", color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody)
        return
    }
    val s = d.first
    Figures(
        null,
        listOf(
            "Played" to span(s.totalMs),
            "Sessions" to s.sessions.toString(),
            "Games" to s.games.toString(),
        ),
        listOfNotNull(
            s.peakTempC?.let { "CPU peak" to "%.0f °C".format(it) },
            s.mahPerHour?.let { "Cost" to "%.0f mAh/h".format(it) },
        ),
    )
    Spacer(Modifier.height(16.dp))
    Heading("GAMES")
    val games = d.second
    if (games.isEmpty()) {
        NothingYet()
        return
    }
    val top = games.first().totalMs.coerceAtLeast(1L)
    var selected by remember(id) { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, games.lastIndex)
    ModalRows(
        count = games.size,
        selected = sel,
        onSelect = { selected = it },
        onActivate = { onOpen(games[sel].label) },
    ) { index ->
        val g = games[index]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(cover(g.label), 30.dp, accent)
            Spacer(Modifier.width(10.dp))
            Text(
                g.label.uppercase(), color = MenuDim, fontSize = 10.sp, fontFamily = MenuBody,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp),
            )
            Bar(g.totalMs.toFloat() / top, accent, Modifier.weight(1f).height(9.dp))
            Spacer(Modifier.width(10.dp))
            Cell(span(g.totalMs), 60.dp, MenuInk)
            Cell(if (g.sessions == 1) "1×" else "${g.sessions}×", 34.dp, MenuFaint)
        }
    }
}

/**
 * Un juego: lo que se sabe de el, y todas sus partidas.
 *
 * Las medias van ponderadas por tiempo, y el peor momento de fotogramas va aparte de la
 * media, porque una media buena esconde justo el minuto malo que uno recuerda.
 */
@Composable
internal fun GameView(
    title: String,
    /** Su consola: el mismo nombre en otra es otro juego. Ver LogStats.GAME_KEY. */
    system: String,
    rank: List<String>,
    accent: Color,
    cover: (String) -> java.io.File?,
    name: (String) -> String,
    onOpen: (LogStats.Entry) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Con sus generos: los que el metagame reparte entre sus vertices (ver LogStats.genresOf).
    val data by produceState<Triple<LogStats.Summary, List<LogStats.Entry>, List<String>>?>(null, title, system) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Logbook(ctx, withOthers = true).use { db ->
                    val s = LogStats(db)
                    Triple(s.summary(title, system), s.sessionsOf(title, system), s.genresOf(title, system))
                }
            }.getOrNull()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Cover(cover(title), 46.dp, consoleColour(system, rank, accent))
        Spacer(Modifier.width(12.dp))
        // La consola por encima del titulo y no debajo, aqui: el titulo lleva su regla
        // pegada por abajo y meterle una linea en medio la despegaria de el. La que se pidio, no
        // la leida: mientras se leia salia «—».
        Column {
            SystemLabel(system, rank, accent, name)
            Spacer(Modifier.height(3.dp))
            Title(title)
        }
    }
    val d = data
    if (d == null) {
        Text("reading…", color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody)
        return
    }
    val s = d.first
    Figures(
        null,
        listOfNotNull(
            "Played" to span(s.totalMs),
            "Sessions" to s.sessions.toString(),
            "Typical" to span(s.meanSessionMs),
            s.mahPerHour?.let { "Cost" to "%.0f mAh/h".format(it) },
        ),
        listOfNotNull(
            d.third.takeIf { it.isNotEmpty() }?.let { "Genre" to it.take(2).joinToString(", ") },
            s.fps?.let { "Frames" to "%.0f fps".format(it) },
            s.worstFps?.let { "Worst" to "%.0f fps".format(it) },
            s.peakTempC?.let { "CPU peak" to "%.0f °C".format(it) },
            s.powerW?.let { "Power" to "%.1f W".format(it) },
        ),
    )
    Spacer(Modifier.height(16.dp))
    SessionsTab(d.second, rank, accent, cover, name, onOpen = onOpen)
}

/**
 * Una partida por dentro.
 *
 * Es lo unico que separa una partida que fue bien de otra que fue mal con la misma media.
 * Sesenta de media con una caida a veinte en el minuto tres y sesenta clavados dan la misma
 * cifra en la lista y no se parecen en nada.
 */
@Composable
internal fun Detail(
    e: LogStats.Entry,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
    armed: Boolean,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val trace by produceState<List<LogStats.Point>?>(null, e.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { Logbook(ctx, withOthers = true).use { LogStats(it).trace(e.id) } }.getOrDefault(emptyList())
        }
    }
    // En ingles, como la tabla de partidas: ver SessionsTab.
    val stamp = remember { SimpleDateFormat("d MMM yyyy  HH:mm", Locale.ENGLISH) }

    // Con desplazamiento, para el dedo: cinco curvas y su cabecera llegan justas al borde de
    // abajo, y con cualquier cosa un poco mas grande la ultima quedaba cortada sin forma de
    // verla. Como la portada, que tambien se arrastra.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SystemLabel(e.system, rank, accent, name)
        Spacer(Modifier.height(3.dp))
        Text(
            e.title.uppercase(), color = if (armed) accent else MenuInk, fontSize = 13.sp,
            fontFamily = MenuDisplay, letterSpacing = LocalTheme.current.titleTracking,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        // Y en que consola, si no fue en esta: al lado, el panel dice el nombre de esta.
        Text(
            listOfNotNull(stamp.format(Date(e.startedAt)), span(e.durationMs), elsewhere(e)?.let { "on $it" })
                .joinToString("   ·   "),
            color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody,
        )
        Spacer(Modifier.height(10.dp))
        HairLine()
        Spacer(Modifier.height(12.dp))

        val points = trace
        if (points == null) {
            Text("reading…", color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody)
            return@Column
        }
        if (points.size < 2) {
            // Una tanda de medidas es cada diez segundos: por debajo de veinte no hay curva que
            // dibujar, solo un punto, y una linea de un punto no dice nada.
            Text(
                "too short to chart — ${points.size} ${if (points.size == 1) "sample" else "samples"}",
                color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody,
            )
            return@Column
        }
        // Solo las curvas que se midieron. Una fila «not measured» no dice nada de la partida, y
        // en una consola de pruebas, que no deja leer la carga de CPU, o sin un emulador que de los fotogramas,
        // eran la mitad de la pantalla.
        val curves = listOf(
            Triple("FRAMES", points.map { it.fps }, "fps"),
            Triple("CPU HEAT", points.map { it.tempC }, "°C"),
            Triple("GPU HEAT", points.map { it.gpuTempC }, "°C"),
            Triple("GPU LOAD", points.map { it.gpu }, "%"),
            Triple("CPU CLOCK", points.map { it.cpuMhz?.toFloat() }, "MHz"),
        ).filter { (_, values, _) -> values.count { it != null } >= 2 }
        if (curves.isEmpty()) {
            Text("nothing was measured inside this session", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
            return@Column
        }
        for ((title, values, unit) in curves) {
            Trace(title, values, unit, accent)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Lo alto que va cada curva. Ver Trace. */
private val TRACE_HEIGHT = 46.dp

/**
 * Una curva de la partida.
 *
 * Escalada a SU propio minimo y maximo, no a un cero absoluto: entre cincuenta y ocho y
 * sesenta fotogramas no hay nada que ver si el eje empieza en cero, y es justo ahi donde esta
 * la diferencia entre ir fino y dar tirones.
 *
 * Por eso los dos extremos van pegados al eje —el maximo a la altura del techo, el minimo a
 * la del suelo— y no juntos en una celda al final. Escritos "58-60 fps" al lado, las dos
 * cifras estan una al lado de la otra pero la curva no dice a cual toca cada pico; puestos
 * cada uno a su altura, la escala se lee de un vistazo y deja de enganar.
 *
 * Y alta, cuarenta y seis en vez de treinta: con cinco curvas seguidas y tan poco recorrido
 * entre el minimo y el maximo, a treinta puntos se apelotonan hasta parecer la misma linea.
 */
@Composable
private fun Trace(title: String, values: List<Float?>, unit: String, accent: Color) {
    val real = values.filterNotNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody,
            modifier = Modifier.width(86.dp),
        )
        if (real.size < 2) {
            Text("not measured", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
            return@Row
        }
        val lo = real.min()
        val hi = real.max()
        // El color se toma AQUI: el lambda de dibujo no es composable y no ve el tema.
        val floor = MenuFaint.copy(alpha = .25f)
        Canvas(Modifier.weight(1f).height(TRACE_HEIGHT)) {
            val range = (hi - lo).takeIf { it > 0.001f } ?: 1f
            val step = if (values.size > 1) size.width / (values.size - 1) else size.width
            // Suelo tenue: da un borde inferior contra el que leer la curva sin dibujar ejes.
            drawRect(floor, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f))
            // Un solo trazo por tramo seguido, de dos puntos y medio y con las juntas redondas.
            // Eran segmentos sueltos de dos PIXELES: el barrido del tema, a dos pixeles cada
            // seis, los cortaba en trocitos, y en cada codo quedaba una muesca.
            val stroke = Stroke(
                width = 2.5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            var path: androidx.compose.ui.graphics.Path? = null
            values.forEachIndexed { i, v ->
                if (v == null) {
                    path?.let { drawPath(it, accent, style = stroke) }
                    path = null
                    return@forEachIndexed
                }
                val p = Offset(i * step, size.height - ((v - lo) / range) * size.height)
                val open = path
                if (open == null) path = androidx.compose.ui.graphics.Path().apply { moveTo(p.x, p.y) }
                else open.lineTo(p.x, p.y)
            }
            path?.let { drawPath(it, accent, style = stroke) }
        }
        Spacer(Modifier.width(8.dp))
        // El eje: techo arriba, suelo abajo. Cuando los dos son el mismo numero se escribe
        // una vez y centrado —"59 / 59" se lee como un error, no como una linea plana.
        val flat = kotlin.math.abs(hi - lo) < 0.5f
        Column(
            Modifier.height(TRACE_HEIGHT).width(48.dp),
            verticalArrangement = if (flat) Arrangement.Center else Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text("%.0f".format(hi), color = MenuInk, fontSize = 10.sp, fontFamily = MenuBody)
            if (!flat) {
                Text("%.0f".format(lo), color = MenuDim, fontSize = 10.sp, fontFamily = MenuBody)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(unit, color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody)
    }
}

/** El titulo de un nivel, con su regla debajo. */
@Composable
private fun Title(text: String) {
    Column {
        Text(
            text.uppercase(), color = MenuInk, fontSize = 13.sp, fontFamily = MenuDisplay,
            letterSpacing = LocalTheme.current.titleTracking, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        HairLine()
        Spacer(Modifier.height(10.dp))
    }
}

/** El plomo: casi el fondo de la sala, para que separe sin pesar tanto como el negro. */
private val LEAD = Color(0xFF07060A)

/**
 * El relleno de una barra: vitral.
 *
 * Sustituye al enrejado diagonal que llevaban antes. Aquel convertia el rectangulo en una
 * superficie, que era lo que se buscaba, pero era el mismo dibujo de las casillas del cajon
 * de apps repetido en otro sitio; esto dice de donde viene la sala sin repetirse.
 *
 * Dos piezas. La luz —un degradado blanco que entra por arriba y se apaga a media altura—
 * levanta el color y hace que el plano parezca vidrio. Y los plomos, que lo parten en panos
 * verticales con el contorno emplomado alrededor.
 *
 * `leaded` decide si ademas van los plomos, y va a false en las verticales del histograma.
 * No es capricho: los plomos son verticales, y en una barra vertical no parten nada —corren
 * en la misma direccion que la barra— asi que lo unico que hacen es rayarla. La luz, en
 * cambio, funciona en las dos: siempre entra por arriba.
 */
internal fun Modifier.glass(
    colour: Color,
    alpha: Float,
    leaded: Boolean = true,
    /** Si el tema decora. En falso la barra es color y ya: ni luz de cristal ni plomos. */
    ornament: Boolean = true,
): Modifier =
    drawBehind {
        drawRect(colour.copy(alpha = alpha))
        // Un tema sin adorno quiere una barra, no una vidriera. Y no es solo quitar los
        // plomos: el degradado blanco de arriba, sobre un tema de papel, no se lee como luz
        // sino como que a la barra le falta tinta.
        if (!ornament) return@drawBehind
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = .22f),
                .7f to Color.Transparent,
            ),
        )
        if (!leaded) return@drawBehind
        val pane = 11.dp.toPx()
        var x = pane
        while (x < size.width - 1f) {
            drawRect(LEAD.copy(alpha = .85f), Offset(x - 1f, 0f), Size(2f, size.height))
            x += pane
        }
        drawRect(
            LEAD.copy(alpha = .9f),
            topLeft = Offset(1f, 1f),
            size = Size((size.width - 2f).coerceAtLeast(0f), (size.height - 2f).coerceAtLeast(0f)),
            style = Stroke(2f),
        )
    }

/**
 * El relleno de una barra de pie: color liso, filo claro arriba y un rombo encima.
 *
 * Sin degradado. En una barra tumbada la luz entra por arriba y el degradado la levanta; de
 * pie, ese mismo degradado la aclara justo donde termina, que es donde esta el dato, y la
 * punta se desdibuja. Aqui el color va liso y lo que remata es un filo del mismo color a plena
 * opacidad: se ve donde acaba sin inventarse una luz que no viene de ninguna parte.
 *
 * Y encima, el rombo. Es el mismo `lozenge` que parte las reglas de los paneles y que marca la
 * pestana elegida, asi que la pantalla repite el mismo adorno arriba y abajo en vez de tener
 * uno para cada cosa. Va acotado al ancho de la barra: con veinticuatro columnas de hora, un
 * rombo de tamano fijo se comeria la barra entera.
 */
internal fun Modifier.capped(
    colour: Color,
    alpha: Float,
    ornament: Boolean = true,
): Modifier =
    drawBehind {
        drawRect(colour.copy(alpha = alpha))
        // Un tramo vacio se queda en la rayita del suelo y ya. Con filo y rombo, los doce
        // dias sin jugar salian como doce rombos en fila sobre el eje y parecian tener algo.
        if (!ornament) return@drawBehind
        drawRect(colour, size = Size(size.width, 2f))
        lozenge(Offset(size.width / 2f, 1f), (size.width * .22f).coerceAtMost(4.dp.toPx()), colour)
    }

/** Una barra de vitral, recortada a la parte llena. */
@Composable
internal fun Bar(fraction: Float, colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight()) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .glass(colour, .60f, ornament = LocalTheme.current.ornament),
        )
    }
}

