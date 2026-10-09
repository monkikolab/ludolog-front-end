package com.felp.frontcomp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Buscar un juego en todas las consolas por su nombre (09-10-2026).
 *
 * Con bibliotecas de miles de juegos bajar por la lista hasta uno era lo mas lento de todo, y es lo
 * primero que echa de menos quien viene de otro launcher. Se abre con el atajo (Y de fabrica, ver
 * Shortcuts.Action.SEARCH) o con la lupa de la barra de arriba.
 *
 * Dos momentos: escribiendo, con el teclado y los primeros resultados debajo, que no se pueden
 * elegir; y con Enter, la lista entera, que se recorre con la cruceta. A lleva al juego dentro de su
 * consola, con el cursor encima: alli se ve su arte y su ficha, y otra A lo lanza. La primera fila
 * de la lista vuelve a escribir.
 *
 * Busca en el nombre que se ve (el propio, el del catalogo o el del fichero) y en el del fichero,
 * sin mayusculas, acentos ni signos, y con las palabras en cualquier orden: «zelda link» encuentra
 * «The Legend of Zelda - A Link to the Past».
 */
@Composable
internal fun SearchWindow(vm: LibraryViewModel, onPick: (Game) -> Unit, onClose: () -> Unit) {
    // Todos los juegos con su nombre ya preparado, en otro hilo: leer los nombres toca las fichas,
    // que la primera vez estan en la tarjeta.
    val index by produceState<List<Found>?>(null) {
        value = withContext(Dispatchers.IO) { searchIndex(vm) }
    }
    var query by remember { mutableStateOf(vm.lastSearch) }
    var typing by remember { mutableStateOf(true) }
    val hits by produceState(emptyList<Found>(), query, index) {
        val all = index ?: return@produceState
        value = withContext(Dispatchers.Default) { searchMatch(all, query) }
    }
    var selected by remember { mutableIntStateOf(0) }
    LaunchedEffect(query) { vm.lastSearch = query; selected = 0 }

    // Al pasar a la lista se cierra el teclado: el campo se va, pero el teclado no siempre con el.
    val view = LocalView.current
    LaunchedEffect(typing) {
        if (!typing) view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    ModalWindow(
        // B escribiendo va a la lista si hay algo; si no, y en la lista, cierra.
        onDismiss = { if (typing && hits.isNotEmpty()) { selected = 1; typing = false } else onClose() },
        widthFraction = 0.72f, heightFraction = 0.90f,
    ) {
        val total = index?.size
        WindowFrame(
            title = "SEARCH",
            subtitle = when {
                total == null -> "reading…"
                query.isBlank() -> "${gameCount(total)}"
                else -> "${hits.size} of $total"
            },
            description = when {
                typing -> "Part of a name, words in any order: «zelda link» finds A Link to the Past. " +
                    "Enter shows the results."
                selected == 0 -> "Type again."
                else -> hits.getOrNull(selected - 1)?.let { "${it.console}  ·  ${it.game.fileName}" }.orEmpty()
            },
            hint = if (typing) "Enter  results      B  back" else "A  go to it      B  close",
        ) {
            if (typing) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    NativeField(
                        initial = query,
                        onValue = { query = it },
                        onImeDone = { text ->
                            query = text
                            // Con lo que hay ya, sin esperar a la cuenta de arriba: es rapida.
                            // En el primer resultado, no en «type again»: Enter y A llevan directo.
                            if (index?.let { searchMatch(it, text) }?.isNotEmpty() == true) { selected = 1; typing = false }
                        },
                        multiline = false,
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, MenuLine, RoundedCornerShape(2.dp))
                            .background(MenuGround)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    // Los primeros, para ver que va bien; elegir es despues, con Enter.
                    for (h in hits.take(PREVIEW)) {
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                h.title, color = MenuInk, fontSize = 13.sp, fontFamily = MenuBody,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(h.console, color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody, maxLines = 1)
                        }
                    }
                    if (hits.size > PREVIEW) {
                        Text("and ${hits.size - PREVIEW} more", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
                    }
                }
            } else {
                ModalRows(
                    count = hits.size + 1,
                    selected = selected.coerceIn(0, hits.size),
                    onSelect = { selected = it },
                    onActivate = {
                        if (selected == 0) typing = true
                        else hits.getOrNull(selected - 1)?.let { onPick(it.game) }
                    },
                ) { i ->
                    if (i == 0) ModalRow(label = "«$query»", value = "type again")
                    else hits[i - 1].let { ModalRow(label = it.title, value = it.console) }
                }
            }
        }
    }
}

/** Un juego de la biblioteca, listo para buscarlo. */
internal class Found(val game: Game, val title: String, val console: String, val key: String, val titleKey: String)

/** Lo que se busca: todas las consolas que se ven, sin las filas que repiten juegos de otras. */
internal fun searchIndex(vm: LibraryViewModel): List<Found> {
    val cat = vm.catalog
    return vm.libraryGroups()
        .filter { (sys, _) -> sys != FAVORITES_SYSTEM && sys != RECENT_SYSTEM }
        .flatMap { (sys, games) ->
            val console = vm.displayName(cat?.byId?.get(sys), sys)
            games.map { g ->
                val title = vm.displayTitle(g)
                val t = searchKey(title)
                Found(g, title, console, t + " " + searchKey(g.fileName.substringBeforeLast('.')), t)
            }
        }
}

/**
 * Los que tienen todas las palabras de [query], los que empiezan por ella primero, despues los que
 * tienen una palabra que empieza por la primera, y el resto; dentro de cada grupo, por nombre.
 */
internal fun searchMatch(all: List<Found>, query: String): List<Found> {
    val q = searchKey(query)
    if (q.isEmpty()) return emptyList()
    val words = q.split(' ')
    val first = words.first()
    return all.asSequence()
        .filter { f -> words.all { it in f.key } }
        .sortedWith(
            compareBy<Found>(
                { if (it.titleKey.startsWith(q)) 0 else if (it.titleKey.split(' ').any { w -> w.startsWith(first) }) 1 else 2 },
                { it.title.lowercase() },
            ),
        )
        .take(MAX_HITS)
        .toList()
}

/** Un nombre como se compara: minusculas, sin acentos y sin nada que no sea letra o cifra. */
internal fun searchKey(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace(NOT_WORD, " ")
        .trim()

private val MARKS = Regex("\\p{Mn}+")
private val NOT_WORD = Regex("[^a-z0-9]+")

/** Los que se ven debajo del campo mientras se escribe. */
private const val PREVIEW = 6

/** Los mas que se enseñan: con mas, la busqueda tiene que afinarse. */
private const val MAX_HITS = 200
