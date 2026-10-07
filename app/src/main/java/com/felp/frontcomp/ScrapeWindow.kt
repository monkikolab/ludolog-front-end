package com.felp.frontcomp

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/*
 * Lo que pasó en el último scrapeo.
 *
 * El scraper llevaba desde el principio contando lo que le salía bien y lo que no, y ese
 * recuento no se enseñaba en ninguna parte: se calculaba, se guardaba en el modelo y ahí se
 * quedaba. El resultado era que un juego sin carátula no se distinguía de uno que nadie
 * había intentado buscar, y no había forma de saber si el fallo estaba en la fuente, en el
 * nombre del fichero o en la red.
 *
 * La ventana se abre sola al terminar, porque ese es el momento en que a alguien le importa,
 * y se puede volver a abrir desde los ajustes mientras la aplicación siga viva.
 */

/** Cómo se llama cada final en una fila. */
private fun ScrapeOutcome.label(): String = when (this) {
    ScrapeOutcome.FETCHED -> "fetched"
    ScrapeOutcome.ALREADY_HAD -> "had it"
    ScrapeOutcome.NO_MATCH -> "no match"
    ScrapeOutcome.NO_SOURCE -> "no source"
    ScrapeOutcome.FAILED -> "failed"
}

/**
 * Qué hacer con cada clase de fallo, en una frase.
 *
 * Va debajo del detalle técnico porque el detalle dice QUÉ pasó y esto dice qué se puede
 * hacer, que no es lo mismo y casi siempre es lo que se quería saber.
 */
private fun ScrapeOutcome.advice(): String = when (this) {
    ScrapeOutcome.NO_MATCH ->
        "Rename the file to its No-Intro name, or give the game your own title from its menu."
    ScrapeOutcome.NO_SOURCE ->
        "Set the console's No-Intro name in the console manager, or use another source."
    // No solo una descarga cortada: tambien una fuente a la que no se pudo preguntar —sin
    // red, el servidor caido, las claves de IGDB rechazadas—. Ver ArtScraper.missFor.
    ScrapeOutcome.FAILED ->
        "Not the name's fault: the network, the source or the card failed. Worth trying again."
    else -> ""
}

@Composable
internal fun ScrapeReportWindow(report: ScrapeReport, onClose: () -> Unit) {
    val misses = report.misses
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, (misses.size - 1).coerceAtLeast(0))
    val current = misses.getOrNull(sel)

    ModalWindow(onDismiss = onClose, widthFraction = 0.66f, heightFraction = 0.80f) {
        WindowFrame(
            title = "ARTWORK",
            subtitle = report.summary(),
            description = current?.let { "${it.detail}\n${it.outcome.advice()}" }
                ?: "Everything that was looked for was found.",
            hint = "B  close",
        ) {
            if (misses.isEmpty()) {
                // Sin filas no hay donde poner el foco, y sin foco la B se va al sistema.
                val anchor = rememberFocusRequester()
                AutoFocus(anchor)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FocusAnchor(anchor)
                    Text(
                        "Nothing left to find.",
                        color = MenuDim, fontSize = 12.sp, fontFamily = MenuBody,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else {
                ModalRows(
                    count = misses.size,
                    selected = sel,
                    onSelect = { selected = it },
                    onActivate = {},
                ) { index ->
                    val m = misses[index]
                    ModalRow(label = m.title, value = "${m.systemId}  ·  ${m.outcome.label()}")
                }
            }
        }
    }
}

/**
 * Las carátulas que podría tener un juego buscado a mano, para elegir una.
 *
 * Una rejilla y no una lista: se elige mirando, y lo que separa una carátula de otra es el
 * dibujo, no el nombre, que la mitad de las veces es el mismo con otra región. Debajo de cada
 * una, cómo se llama allí; abajo, entero, el nombre de la marcada, de dónde sale y si es el
 * mismo nombre o solo uno parecido.
 *
 * El cursor es un recuadro quieto del color del tema y no un bloque que late: encima de una
 * imagen un relleno la tapa, y lo que se está mirando es justo la imagen.
 */
@Composable
internal fun CoverPickWindow(hunt: CoverHunt, onPick: (CoverChoice) -> Unit, onClose: () -> Unit) {
    val choices = hunt.choices
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, choices.lastIndex)
    val current = choices[sel]
    val close = { Sfx.play(Sfx.Cue.CLOSE); onClose() }
    LaunchedEffect(Unit) { Sfx.play(Sfx.Cue.MENU) }
    // Como en las listas: suena al moverse, no al abrir.
    val lastRow = remember { intArrayOf(sel) }
    LaunchedEffect(sel) {
        if (lastRow[0] != sel) Sfx.play(Sfx.Cue.MOVE)
        lastRow[0] = sel
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(sel) { runCatching { grid.animateScrollToItem(sel) } }
    // El foco a la primera al abrir, reintentado: una rejilla perezosa no tiene celdas en el
    // primer fotograma. Ver AppsWindow.
    val focus = rememberFocusRequester()
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(20) {
            runCatching { focus.requestFocus() }
            if (hasFocus) return@LaunchedEffect
            delay(40)
        }
    }

    val title = hunt.shown.uppercase()
    val subtitle = "${choices.size} box art found"
    val hint = "A  choose      B  cancel"
    ModalWindow(
        onDismiss = close,
        // Con un nombre largo, mas ancha: en la ceja de los temas de instrumento solo cabe una
        // linea. Ver headerFraction.
        widthFraction = headerFraction(title, subtitle, hint, min = 0.80f),
        heightFraction = 0.92f,
    ) {
        WindowFrame(
            title = title,
            subtitle = subtitle,
            description = current.label + "\n" + current.source + "  ·  " +
                if (current.exact) "same name" else "similar name, check it is the right game",
            hint = hint,
        ) {
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(top = 6.dp),
            ) {
                itemsIndexed(choices) { i, c ->
                    CoverCell(
                        choice = c,
                        selected = i == sel,
                        requester = if (i == sel) focus else null,
                        onFocused = { selected = i; hasFocus = true },
                        onActivate = { onPick(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverCell(
    choice: CoverChoice,
    selected: Boolean,
    requester: FocusRequester?,
    onFocused: () -> Unit,
    onActivate: () -> Unit,
) {
    val t = LocalTheme.current
    Column(
        Modifier.fillMaxWidth().aspectRatio(0.66f)
            // Por gestos y no con clickable, como en el cajon de apps: clickable anade un
            // segundo nodo de foco y la cruceta se atasca. Tocar una la elige.
            .pointerInput(onActivate) { detectTapGestures { onFocused(); onActivate() } }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .padItem(
                onActivate = onActivate,
                focusRequester = requester,
                scaleWhenFocused = 1f,
                borderColor = Color.Transparent,
                borderWidth = 0.dp,
            )
            .border(2.dp, if (selected) t.accent else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = choice.preview,
            contentDescription = choice.label,
            contentScale = ContentScale.Fit,
            colorFilter = phosphorFilter(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            choice.label,
            color = if (selected) MenuInk else MenuDim,
            fontSize = 10.sp, lineHeight = 12.sp, fontFamily = MenuBody,
            textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * Los videos que podria tener un juego buscado a mano, para elegir uno.
 *
 * Una lista y no una rejilla como las caratulas: para ensenar cada video habria que bajarlos
 * todos, y cada uno pesa megas. Los nombres son los del conjunto de volcado, que ya dicen el
 * juego y la region.
 */
@Composable
internal fun VideoPickWindow(hunt: VideoHunt, onPick: (ThumbEntry) -> Unit, onClose: () -> Unit) {
    val choices = hunt.choices
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, choices.lastIndex)
    val (current, exact) = choices[sel]
    val close = { Sfx.play(Sfx.Cue.CLOSE); onClose() }
    LaunchedEffect(Unit) { Sfx.play(Sfx.Cue.MENU) }
    val title = hunt.shown.uppercase()
    val subtitle = "${choices.size} ${if (choices.size == 1) "video" else "videos"} found"
    val hint = "A  choose      B  cancel"
    ModalWindow(
        onDismiss = close,
        widthFraction = headerFraction(title, subtitle, hint, min = 0.66f),
        heightFraction = 0.80f,
    ) {
        WindowFrame(
            title = title,
            subtitle = subtitle,
            description = current.fileName.substringBeforeLast('.') + "\narchive.org  ·  " +
                if (exact) "same name" else "similar name, check it is the right game",
            hint = hint,
        ) {
            ModalRows(
                count = choices.size,
                selected = sel,
                onSelect = { selected = it },
                onActivate = { onPick(choices[sel].first) },
            ) { i ->
                val (entry, same) = choices[i]
                ModalRow(label = entry.fileName.substringBeforeLast('.'), value = if (same) "same name" else "")
            }
        }
    }
}
