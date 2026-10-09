package com.felp.frontcomp

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Las listas de consolas y de juegos y su distribucion en pantalla. Salio de MainActivity.kt el 07-10-2026.

@Composable
internal fun SystemsMenu(
    vm: LibraryViewModel,
    /** La consola en la que estaba el cursor la ultima vez, o el cuaderno. */
    initial: String?,
    onOpen: (String) -> Unit,
    onReckoning: () -> Unit,
    refocus: Any?,
    enabled: Boolean,
    onSelectionChange: (String?) -> Unit,
    onLongPress: () -> Unit,
) {
    val res = vm.result
    val cat = vm.catalog

    if (res == null) {
        StartPanel(vm)
        return
    }

    // Las consolas ocultas desde su menú contextual no aparecen, pero sus juegos siguen
    // en la biblioteca: ocultar es una decisión de la vista, no un borrado. La de Android
    // entra aquí con las demás cuando hay alguna app mandada a ella.
    val groups = remember(res, vm.androidGames, vm.prefs.hiddenSystems, vm.prefs.favoriteRevision) { vm.libraryGroups() }
    // El cuaderno va al final de la lista de consolas, y solo si esta encendido.
    //
    // Al final y no al principio: es lo ultimo que se mira, no lo primero. Y en la lista de
    // consolas y no en un boton aparte porque es donde uno va a buscar «las cosas que hay»;
    // el acorde de gatillos sigue funcionando desde cualquier sitio, esto es para quien no lo
    // conoce todavia.
    val book = vm.prefs.logbook
    val label = COMPANION_LABEL
    // Y despues del cuaderno, Ludolog Link, si esta instalado: abre Link. Lo de debajo de la lista
    // —el cuaderno y Link— va en `extras`, en su orden; las consolas, en `groups`.
    val link = LinkSaveCheck.present.value
    val extras = remember(book, link) {
        listOfNotNull(RECKONING_SYSTEM.takeIf { book }, LINK_SYSTEM.takeIf { link })
    }
    // Donde se estaba, y no la primera. Esta lista sale de la pantalla al entrar en una
    // consola, y con ella lo que recordaba: al volver con B el cursor saltaba arriba del todo y
    // habia que bajar otra vez hasta la consola de la que se venia.
    var selected by remember(groups) {
        mutableStateOf(
            if (initial in extras) groups.size + extras.indexOf(initial)
            else groups.indexOfFirst { it.first == initial }.coerceAtLeast(0)
        )
    }
    // Si Link desaparece estando en su fila, el cursor no se queda fuera de la lista.
    LaunchedEffect(extras) {
        if (selected >= groups.size + extras.size) selected = (groups.size + extras.size - 1).coerceAtLeast(0)
    }
    val extra = if (selected >= groups.size) extras.getOrNull(selected - groups.size) else null
    val current = groups.getOrNull(selected)
    val sys = current?.let { cat?.byId?.get(it.first) }

    // Los rotulos se calculan una vez, no en cada recomposicion. Cada uno cuesta una
    // consulta a preferencias y una cadena nueva, y antes se pagaba la lista entera en
    // cada movimiento del cursor.
    val labels = remember(groups, cat, vm.prefs.labelRevision, extras, label) {
        groups.map { vm.displayName(cat?.byId?.get(it.first), it.first).uppercase() } +
            extras.map { if (it == RECKONING_SYSTEM) label else LINK_LABEL }
    }
    val onBook = extra == RECKONING_SYSTEM
    val onLink = extra == LINK_SYSTEM
    // Y lo que se cuenta de la consola elegida, que recorre la biblioteca contando cuantos
    // juegos tienen arte. Se pedia DOS veces seguidas, una por el texto y otra por el pie.
    val info = remember(
        current?.first, onBook, vm.result, vm.androidGames, vm.art, vm.prefs.labelRevision,
        vm.played, book, onLink, LinkSaveCheck.on.value, LinkSaveCheck.address.value, LinkSaveCheck.peers.intValue,
    ) {
        // Y del cuaderno se cuenta lo suyo, que es lo que ya se cuenta en la sala. Sin esto,
        // pararse en su fila dejaba el pie del panel en blanco: la unica fila de la lista sin
        // una palabra debajo, y justo la que mas falta hace explicar.
        if (onBook) reckoningDetails(vm, COMPANION_NAME)
        else if (onLink) linkDetails()
        else current?.let { vm.systemCard(it.first) }
    }

    LaunchedEffect(current?.first, extra) {
        onSelectionChange(extra ?: current?.first)
    }

    // Las dos filas que no son una maquina de otra epoca tambien tienen ficha, cada una la suya.
    // Los juegos de Android corren en ESTE aparato, asi que la suya es la de el; y la del
    // cuaderno es la del cuaderno, leida del disco al pararse en su fila.
    val ctx = LocalContext.current
    val deviceSpecs = remember { DeviceSpecs.of(ctx) }
    val bookFacts by produceState(emptyList<Pair<String, String>>(), onBook, vm.played) {
        value = if (!onBook) emptyList() else withContext(Dispatchers.IO) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { companionFacts(ctx) }
                .onFailure { android.util.Log.w("Ludolog", "ficha del cuaderno: $it") }
                .getOrDefault(emptyList())
                .also { android.util.Log.i("Ludolog", "companion: ficha de la lista en ${android.os.SystemClock.elapsedRealtime() - t0} ms") }
        }
    }

    MenuLayout(
        entries = labels,
        selected = selected,
        onSelect = { selected = it },
        onActivate = {
            if (onBook) onReckoning()
            else if (onLink) LinkSaveCheck.openHome(ctx)
            else current?.let { onOpen(it.first) }
        },
        onLongPress = onLongPress,
        refocus = refocus,
        enabled = enabled,
        description = info?.text.orEmpty(),
        footer = info?.footer,
        // La ficha tecnica de la consola, del catalogo; y las dos que no salen de el.
        callouts = when {
            onBook -> bookFacts
            onLink -> emptyList()
            current?.first == ANDROID_SYSTEM -> deviceSpecs
            else -> sys?.specs.orEmpty()
        },
        showDetails = vm.scene == null,
        title = "Consoles",
        preview = {
            // Con una sala de fondo el panel derecho ya no existe: la escena ES el panel.
            if (vm.scene != null) return@MenuLayout
            // El cuaderno no es una consola y no tiene giro que ensenar, pero tiene marca.
            //
            // Sin esto, pararse en su fila dejaba el panel con un «no image» —un hueco pidiendo
            // perdon— en la unica fila de la lista cuyo dibujo SI existe y ademas esta ya en la
            // carpeta del tema. Es la misma silueta que firma la tarjeta encima del juego, asi
            // que las dos veces que se ve el Companion se ve lo mismo.
            val look = LocalTheme.current
            val here = LocalContext.current
            val markFile = remember(look.id) { ThemeFiles.companion(here) }
            // Link, en un tema sin sala: su giro si el tema lo trae, y si no su marca (link.svg), con
            // el mismo sombreado que la del cuaderno. Apagado, la marca se queda a media luz: es lo
            // que en la sala dice el LED.
            val linkMark = remember(look.id) { ThemeFiles.link(here) }
            val linkSpin = remember(look.id, LinkSaveCheck.on.value) { LinkArt.spin(LinkSaveCheck.on.value) }
            if (onLink && linkMark != null && linkSpin == null) {
                val side = with(LocalDensity.current) { 210.dp.roundToPx() }
                val tint = look.accent.toArgb()
                val drawn = remember(linkMark, side, tint) { SvgMark.tinted(linkMark, side, tint) }
                val bmp = remember(drawn) { drawn?.asImageBitmap() }
                val piece = remember(drawn) { drawn?.let(::inkBounds) ?: WHOLE }
                if (bmp != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(
                            bmp, contentDescription = null,
                            alpha = if (LinkSaveCheck.on.value) 1f else 0.45f,
                            modifier = Modifier.calloutTarget(piece),
                        )
                    }
                    return@MenuLayout
                }
            }
            if (onBook && markFile != null) {
                val side = with(LocalDensity.current) { 210.dp.roundToPx() }
                // Con su sombreado y el color del tema.
                //
                // Aqui la marca ocupa el sitio que en las demas filas ocupa el giro de una
                // consola, y al lado hay caratulas a todo color: una silueta plana se lee como
                // un icono y no como la pieza que representa. En la tarjeta de encima del juego
                // es al reves —alli es plana, de un tono— y por eso hay dos maneras de pintar
                // el mismo fichero.
                val tint = look.accent.toArgb()
                val drawn = remember(markFile, side, tint) { SvgMark.tinted(markFile, side, tint) }
                val bmp = remember(drawn) { drawn?.asImageBitmap() }
                // Las lineas de su ficha salen de la marca y no del cuadro donde se pinta: la
                // chapa del retro es estrecha, y las de los lados salian del aire.
                val piece = remember(drawn) { drawn?.let(::inkBounds) ?: WHOLE }
                if (bmp != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(
                            bmp, contentDescription = null, modifier = Modifier.calloutTarget(piece),
                        )
                    }
                    return@MenuLayout
                }
            }
            // Con el id del TEMA entre las claves: cada uno mira primero su carpeta de medios, asi
            // que cambiar de tema cambia el fichero. Sin esto, la busqueda quedaba memoizada por
            // consola y el cambio en vivo movia el color y la letra dejando la imagen del anterior.
            val chosen = sys?.id?.let(RenderChoices::of)
            val img = remember(sys?.id, look.id, chosen) { sys?.let { SystemArt.find(it.id, it.image) } }
            // El giro, salvo en un tema que no quiere movimiento: ahi la consola se queda
            // quieta, como la caratula, que es de lo que va ese tema.
            val quiet = !LocalTheme.current.spins
            val favs = current?.first == FAVORITES_SYSTEM
            val linkOn = LinkSaveCheck.on.value
            val turntable = remember(sys?.id, quiet, look.id, chosen, favs, onLink, linkOn) {
                // Favoritos no es una consola del catalogo: su giro, la estrella, va por su id.
                if (quiet) null
                else if (onLink) LinkArt.spin(linkOn)
                else if (favs) SystemArt.video(FAVORITES_SYSTEM)
                else sys?.let { SystemArt.video(it.id, it.video) }
            }
            // El giro manda sobre la foto: si alguien se molestó en renderizar la consola,
            // es lo que quiere ver.
            if (turntable != null && enabled) {
                ConsoleTurntable(turntable, Modifier.fillMaxSize())
            } else if (look.plates && sys != null) {
                // Con letras y no con un dibujo: ver Theme.plates.
                ConsolePlate(sys.label, sys.maker, sys.year, Modifier.fillMaxSize())
            } else {
                Preview(
                    img.takeIf { !onLink },
                    if (onLink) "Ludolog Link" else current?.let { vm.displayName(sys, it.first) }.orEmpty(),
                    ContentScale.Fit,
                )
            }
        },
    )
}

/* ---------------------------------------------------------------------- nivel 2: juegos */

@Composable
internal fun GamesMenu(
    vm: LibraryViewModel,
    systemId: String,
    onBack: () -> Unit,
    refocus: Any?,
    enabled: Boolean,
    onPlay: (Game) -> Unit,
    onSelectionChange: (Game?) -> Unit,
    onLongPress: () -> Unit,
) {
    val games = vm.gamesOf(systemId)
    val sys = vm.catalog?.byId?.get(systemId)
    val art = vm.art
    var selected by remember(systemId, games) { mutableStateOf(0) }
    val game = games.getOrNull(selected)

    // Los favoritos llevan una estrella pegada a la izquierda (ver Star y MenuEntry), aparte del
    // texto. Y los que sirven para una mision que se sigue (ver Missions), un ojo a la derecha.
    val labels = remember(games, vm.prefs.labelRevision, vm.played, vm.dossierRevision) {
        games.map { g -> vm.displayTitle(g).uppercase() }
    }
    val starred = remember(games, vm.prefs.labelRevision) { games.map { vm.prefs.isFavorite(it) } }
    val marked = remember(games, vm.prefs.labelRevision, vm.played, vm.dossierRevision) {
        val any = vm.prefs.trackedMissions.isNotEmpty()
        games.map { g -> any && vm.missionsFor(g).isNotEmpty() }
    }
    val info = remember(
        game?.path, vm.result, vm.androidGames, vm.prefs.labelRevision, vm.played, vm.prefs.logbook,
        // Una vez, cuando se sabe la carga: si no, la primera tarjeta se quedaria sin su
        // bateria hasta mover el cursor.
        vm.chargeUah != null,
    ) {
        game?.let { vm.gameCard(it) }
    }

    LaunchedEffect(game?.path) { onSelectionChange(game) }

    // La tarjeta viva: alterna lo que sabe el Companion (horas, ultima partida, bateria) con la
    // sinopsis de la ficha, cada pocos segundos, mientras el cursor se queda en el juego. Al
    // moverse vuelve a empezar por las horas, que es lo que se mira al elegir. La sinopsis, con
    // su procedencia en el pie: la licencia de Wikipedia pide decir de donde sale.
    val synopsis by produceState<Pair<String, String>?>(null, game?.path, vm.dossierRevision) {
        value = game?.let { g ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Dossiers.get(g)?.let { d ->
                    d.synopsis?.let { shortSynopsis(it) to if (d["syn.src"] == "gt") "From GameTDB" else "From Wikipedia  ·  CC BY-SA 4.0" }
                }
            }
        }
    }
    var page by remember(game?.path) { mutableStateOf(0) }
    LaunchedEffect(game?.path, synopsis != null, enabled) {
        page = 0
        // Quieta con una ventana encima: nadie la esta leyendo.
        if (synopsis == null || !enabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(CARD_PAGE_MS)
            page = 1 - page
        }
    }
    val story = synopsis?.takeIf { page == 1 }
    LaunchedEffect(story) { vm.cardStory = story }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.cardStory = null } }

    // Lo que se sabe del juego —genero, año, quien lo hizo, lo que pesa— para las lineas del
    // panel. Fuera del hilo de la pantalla: sale de la lista de ES-DE y de medir el fichero. Y
    // solo donde se dibuja: las lineas de los temas de instrumento y el adorno del de borde.
    val chrome = LocalTheme.current.chrome
    val wantsFacts = chrome == Chrome.INSTRUMENT || chrome == Chrome.BORDER
    // Y otra vez cuando cambian las fichas: la primera pasada acaba con la lista ya a la vista.
    val facts by produceState(emptyList<Pair<String, String>>(), game?.path, wantsFacts, vm.dossierRevision) {
        value = if (!wantsFacts || game == null) emptyList()
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GameInfo.facts(game) }
    }

    MenuLayout(
        entries = labels,
        selected = selected,
        onSelect = { selected = it },
        onActivate = { game?.let(onPlay) },
        onBack = onBack,
        onLongPress = onLongPress,
        refocus = refocus,
        enabled = enabled,
        description = story?.first ?: info?.text.orEmpty(),
        // Donde el nombre de la consola ya va encima de la lista, abajo solo la cuenta.
        footer = story?.second
            ?: if (LocalTheme.current.chrome == Chrome.BORDER) gameCount(games.size)
            else "${vm.displayName(sys, systemId)}  ·  ${gameCount(games.size)}",
        callouts = facts,
        showDetails = vm.scene == null,
        title = vm.displayName(sys, systemId),
        listLabel = vm.displayName(sys, systemId),
        stage = vm.prefs.stageDeco,
        marked = marked,
        starred = starred,
        preview = {
            if (vm.scene != null) return@MenuLayout
            val g = game ?: return@MenuLayout
            // `enabled` es falso cuando hay una ventana encima: entonces la TV no se ve y
            // seguir decodificando video es bateria tirada.
            GamePreview(vm, g, art?.find(g), playing = enabled)
        },
    )
}

/* --------------------------------------------------------------------- estructura común */

/**
 * La estructura del diagrama: menú a la izquierda, vista previa a la derecha.
 *
 * Es un solo composable para los dos niveles porque el diagrama dice exactamente eso —
 * al entrar en una consola el menú cambia a los juegos y el resto de la pantalla sigue
 * igual. Cambia la lista, no la pantalla.
 */
@Composable
internal fun MenuLayout(
    entries: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onActivate: () -> Unit,
    description: String,
    footer: String?,
    onBack: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    /**
     * Lo que se cuenta de lo elegido con lineas desde su imagen: rotulo y valor, hasta cuatro.
     *
     * Solo lo dibujan los temas de instrumento, en la caja de la imagen y en lugar de las cruces
     * de encuadre. Ver `CalloutFrame`.
     */
    callouts: List<Pair<String, String>> = emptyList(),
    /**
     * Cambia cuando se cierra una ventana modal.
     *
     * Al abrirse un modal el foco se va a él, y al cerrarse no vuelve solo: la lista se
     * queda sin selección visible y, con mando, sin nada que responda a las direcciones.
     * Así que se le vuelve a pedir.
     */
    refocus: Any? = null,
    /** False mientras haya una ventana encima: el fondo deja de navegarse. */
    enabled: Boolean = true,
    /**
     * Titulo dentro del panel.
     *
     * Solo se ve en los temas con regletas: en los otros la lista va suelta sobre el fondo
     * y un titulo encima le quitaria sitio a la propia lista, que es lo que importa en una
     * pantalla de 456dp de alto.
     */
    title: String = "",
    /**
     * Si la descripcion y el pie van en el panel. Con una sala de fondo van en el dialogo
     * de la sala, escritos a maquina, y el panel se queda para la lista: asi caben el
     * doble de consolas a la vista.
     */
    showDetails: Boolean = true,
    /**
     * Lo que se escribe en la ceja de la caja de la lista, en los temas de instrumento: la
     * consola cuyos juegos se estan viendo. Ahi el titulo no va encima de la lista como en los
     * de regletas, y sin el, dentro de una consola no habia nada que dijera de cual.
     */
    listLabel: String? = null,
    /**
     * El panel con la imagen arriba y el texto debajo, en el tema de borde: ver GameStage. Nulo
     * deja el de siempre, con la imagen a todo el panel y el texto bajo la lista.
     */
    stage: StageDeco? = null,
    /** Las filas que llevan el ojo a la derecha: las que sirven para una mision que se sigue. */
    marked: List<Boolean> = emptyList(),
    /** Las que llevan la estrella a la izquierda: los favoritos. */
    starred: List<Boolean> = emptyList(),
    preview: @Composable () -> Unit,
) {
    // Empieza ya en la fila elegida: si no, al volver a una lista con la seleccion abajo el foco
    // tenia que ir a buscarla, y al traerla a la vista la dejaba pegada al borde de abajo.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)

    // El clic va aqui y no en quien cambia la seleccion, porque la cambian tres cosas: la
    // cruceta, el toque y el foco que vuelve al cerrar un modal. La primera composicion no
    // suena: abrir una lista no es moverse por ella.
    //
    // Y cuando cambia la LISTA tampoco. Al saltar de consola con los gatillos, el cursor
    // vuelve a la primera fila de los juegos nuevos; eso no es un movimiento de nadie y
    // sonaba un clic pegado al sonido de entrar, como un tropiezo.
    val lastRow = remember { intArrayOf(selected) }
    val lastList = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(selected, entries.size, entries.firstOrNull()) {
        val now = "${entries.size}/${entries.firstOrNull()}"
        val sameList = lastList[0] == now
        lastList[0] = now
        if (sameList && lastRow[0] != selected) Sfx.play(Sfx.Cue.MOVE)
        lastRow[0] = selected
    }
    val density = LocalDensity.current
    var viewportPx by remember { mutableStateOf(0) }

    val selectedRequester = rememberFocusRequester()

    /*
     * El foco se pide UNA vez por situación, no en cada recomposición.
     *
     * Pedirlo continuamente lo clava: cada vez que la cruceta intenta moverlo, el elemento
     * seleccionado lo reclama de vuelta y la lista no se mueve. Y pedirlo una sola vez al
     * componer la pantalla tampoco vale, porque una lista perezosa aún no tiene elementos
     * y la petición cae en el vacío. Así que: una petición por situación, reintentada
     * hasta que haya un elemento al que agarrarse.
     */
    var focusWanted by remember { mutableStateOf(true) }
    var listHasFocus by remember { mutableStateOf(false) }
    /*
     * Mientras se recupera el foco, los avisos que llegan de una fila que NO es la elegida
     * se ignoran.
     *
     * Al cerrarse una ventana la lista vuelve a ser navegable y Compose reparte el foco por
     * las filas que la lista perezosa va componiendo. Cada parada llamaba a onSelect, y la
     * selección terminaba cuatro o cinco consolas más abajo de donde estaba: abrir un menú
     * y volver te cambiaba de consola. La ventana dura lo que dure la recuperación —como
     * mucho ochocientos milisegundos— y se cierra en cuanto la fila elegida coge el foco.
     */
    var restoring by remember { mutableStateOf(true) }
    /*
     * La guardia se arma AL COMPONER, no en un efecto.
     *
     * Con un efecto llegaba tarde: los efectos corren al final del fotograma y el paseo del
     * foco ocurre durante el reparto de ese mismo fotograma, cuando las filas se quedan sin
     * ser enfocables una a una. Medido: abriendo el menú de Super Nintendo, la selección de
     * detrás aparecía ocho filas más abajo. Comparar `enabled` con lo que valía la vez
     * anterior es lo único que se entera a tiempo.
     */
    val lastEnabled = remember { BooleanHolder(enabled) }
    if (lastEnabled.value != enabled) {
        lastEnabled.value = enabled
        restoring = true
    }
    /*
     * Y lo mismo cuando cambia la LISTA, no solo al cerrarse una ventana.
     *
     * Al saltar de consola con los hombros la seleccion vuelve a la primera fila de los juegos
     * nuevos, pero el foco se quedaba en la fila que ocupara la misma posicion —las filas se
     * reconocen por su numero—, ahora con otro juego. Bajar desde ahi saltaba de golpe a esa
     * altura, y si la lista nueva era mas corta el foco se perdia con su fila y la cruceta
     * dejaba de responder. Se arma al componer, como la guardia de arriba y por lo mismo, y
     * olvida que la lista tenia el foco: si no, el reintento de abajo se daba por servido a la
     * primera sin haberlo movido.
     */
    val lastEntries = remember { arrayOfNulls<List<String>>(1) }
    if (lastEntries[0] !== entries) {
        if (lastEntries[0] != null) {
            restoring = true
            focusWanted = true
            listHasFocus = false
        }
        lastEntries[0] = entries
    }
    LaunchedEffect(refocus) { focusWanted = true; restoring = true }

    /*
     * Un salto de la cruceta: izquierda y derecha de letra en letra, y arriba y abajo dando la
     * vuelta en los extremos.
     *
     * No mueve el foco a mano: pone la seleccion y pide el foco despues, como al volver de una
     * ventana. La fila de destino puede no existir todavia —la lista es perezosa y el destino
     * esta lejos— y hasta que el desplazamiento la trae no hay a quien darselo. Con la guardia
     * puesta, que el foco pase de camino por otras filas no las elige.
     */
    fun jumpTo(target: Int) {
        if (target == selected || target !in entries.indices) return
        onSelect(target)
        restoring = true
        listHasFocus = false
        focusWanted = true
    }
    LaunchedEffect(focusWanted, entries.size, enabled) {
        // Con la lista apagada la guardia NO se levanta: es justo mientras hay una ventana
        // encima cuando el foco se pasea por las filas que se van quedando sin él.
        if (!focusWanted || !enabled || entries.isEmpty()) return@LaunchedEffect
        repeat(20) {
            runCatching { selectedRequester.requestFocus() }
            if (listHasFocus) { restoring = false; return@LaunchedEffect }
            kotlinx.coroutines.delay(40)
        }
        focusWanted = false
        restoring = false
    }

    /*
     * Tocar una fila la elige y DESPUÉS la abre.
     *
     * Antes el toque llamaba directo a onActivate, que actúa sobre lo que estuviera
     * seleccionado: tocar una consola abría la de antes. Ahora se apunta el índice y se
     * abre cuando la selección ya es esa.
     */
    var pendingTap by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingTap, selected) {
        val p = pendingTap ?: return@LaunchedEffect
        if (p == selected) { pendingTap = null; onActivate() }
    }

    // Mas bajas con la barra que con el marco: la barra se ve igual de bien a 34 que a 46, y
    // doce puntos por fila son tres consolas mas a la vista en una lista de diez.
    // Y con el bloque, 40 y no 46: con la letra a catorce, 46 dejaba las filas con aire de sobra.
    val itemHeight = if (LocalTheme.current.selection == SelectionStyle.BAR) 34.dp else 40.dp
    // Medio hueco arriba y abajo para que el primero y el último también puedan quedar
    // en el centro; sin esto la selección solo se centra a partir del tercer elemento.
    val halfGap = with(density) {
        ((viewportPx / 2) - (itemHeight.toPx() / 2)).coerceAtLeast(0f).toDp()
    }

    // La selección se mantiene centrada CUANDO PUEDE: es lo que hace que una lista larga se
    // lea igual que una corta, porque el ojo siempre mira al mismo sitio.
    //
    // Antes se centraba siempre, metiendo medio hueco de relleno por arriba, y al estar en
    // la primera consola la mitad de arriba del panel se quedaba vacia: la lista parecia de
    // cuatro. Ahora el relleno va solo por abajo y el desplazamiento pide la fila a media
    // altura con un desfase negativo; al principio de la lista eso no puede cumplirse y la
    // lista se queda pegada arriba, llena, y en cuanto hay filas por encima la seleccion
    // baja al centro. Es lo que hacen los menus de consola de toda la vida.
    val halfGapPx = with(density) { halfGap.roundToPx() }
    // La primera vez, de golpe: la lista acaba de aparecer y no hay ningun movimiento que ensenar.
    val placed = remember { BooleanHolder(false) }
    LaunchedEffect(selected, viewportPx) {
        if (viewportPx <= 0) return@LaunchedEffect
        runCatching {
            if (placed.value) listState.animateScrollToItem(selected, -halfGapPx)
            else listState.scrollToItem(selected, -halfGapPx)
        }
        placed.value = true
    }

    val t = LocalTheme.current
    // Las dos formas de caja que cambian el REPARTO del panel, no solo su marco. Ver `Chrome`.
    val rules = t.chrome == Chrome.RULES
    val instrument = t.chrome == Chrome.INSTRUMENT
    // La imagen arriba y el texto debajo, solo en el tema de borde y donde se pida.
    // Casi cuadrada no: la imagen con su texto debajo quedaba diminuta en la franja de arriba. Ahi
    // va la imagen sola, grande, y el texto debajo de la lista, como en las otras listas.
    val staged = stage != null && t.chrome == Chrome.BORDER && showDetails && !squareScreen()
    // Mas estrecha con las cajas de instrumento: con la letra de la lista a catorce caben los
    // mismos nombres en menos ancho, y lo que se ahorra va a las cajas de la imagen y el texto.
    // Ver listWidth: su parte de la pantalla con un minimo legible.
    // La lista, aparte: en una consola de dos pantallas va en la de abajo (ver DualScreen), y la
    // de arriba se queda con lo de la derecha a todo lo ancho.
    val listPane: @Composable (Modifier) -> Unit = { listMod ->
            Column(
                listMod
                    // Con las dos mitades encuadradas, los margenes son los mismos por fuera y el
                    // canal entre ellas mide lo que mide el hueco entre las dos cajas de la
                    // derecha. Si no, se ve enseguida: tres separaciones distintas en la misma
                    // pantalla y ninguna razon para que lo sean.
                    .padding(
                        if (instrument) {
                            PaddingValues(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 20.dp)
                        } else {
                            PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                        }
                    )
            ) {
              Column(
                Modifier.weight(1f).fillMaxWidth()
                    // Sin fondo: la sala sigue viendose entera. El panel son las dos regletas
                    // y lo que va entre ellas, no una caja.
                    //
                    // Salvo con el panel encuadrado, donde la lista es la tercera caja: sin marco
                    // quedaba flotando al lado de dos que si lo tienen, y en una interfaz de
                    // instrumento lo que no tiene marco no esta en ningun sitio. Sin cruces: son
                    // marcas de encuadre de una IMAGEN y aqui no hay ninguna.
                    .then(
                        when (t.chrome) {
                            Chrome.RULES -> Modifier.panelChrome(Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                            Chrome.INSTRUMENT -> Modifier.instrumentPane(ticks = false).browLabel(listLabel)
                                .padding(PANE_PAD)
                            Chrome.BORDER -> Modifier
                        }
                    )
              ) {
                // Encima de la lista, en las regletas su titulo, y en el tema de borde el nombre de la
                // consola que se esta mirando, solo dentro de ella: en la lista de consolas no hace
                // falta decir que son consolas, pero dentro de una no habia nada que dijera de cual.
                val heading = when (t.chrome) {
                    Chrome.RULES -> title
                    Chrome.BORDER -> listLabel.orEmpty()
                    Chrome.INSTRUMENT -> ""
                }
                if (heading.isNotEmpty()) {
                    // Encoge hasta caber antes de cortarse. Con el espaciado del Parlour, «GAME BOY
                    // ADVANCE» salia «GAME BOY ADVAN…» en una consola de pruebas: el nombre de la consola en la
                    // que se esta es justo lo que no se puede leer a medias.
                    Text(
                        if (t.upperTitles) heading.uppercase() else heading,
                        color = MenuInk, fontFamily = MenuDisplay,
                        fontWeight = FontWeight.Bold, letterSpacing = t.titleTracking,
                        textAlign = TextAlign.Center, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                            minFontSize = 11.sp, maxFontSize = 18.sp,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                    )
                    PanelDivider()
                    // Un poco de aire entre la regla y la lista: la lista recorta sus filas en su
                    // borde de arriba, y con la regla justo ahi las letras parecian cortadas
                    // por ella.
                    Spacer(Modifier.height(8.dp))
                }
                // Una consola vacía —la de Android recién estrenada, por ejemplo— no tiene
                // ninguna fila que pueda quedarse el foco, y sin foco la B no llega a la
                // pantalla: se iba al sistema y cerraba la aplicación entera.
                if (entries.isEmpty()) {
                    val anchor = rememberFocusRequester()
                    AutoFocus(anchor, enabled = enabled)
                    Box(Modifier.then(if (onBack != null) Modifier.padBack(onBack) else Modifier)) {
                        FocusAnchor(anchor)
                    }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = halfGap),
                    // Los gatillos NO se manejan aquí: L+R juntos abren las apps y L o R sueltos
                    // cambian de consola, y distinguir las dos cosas exige ver ambas teclas a
                    // la vez. Eso solo se puede decidir en un sitio, y ese sitio es la raíz.
                    modifier = Modifier.weight(1f)
                        .onSizeChanged { viewportPx = it.height }
                        .then(if (onBack != null) Modifier.padBack(onBack) else Modifier)
                        // En el paso previo, antes de que la fila o la lista hagan nada con la tecla:
                        // los saltos y la vuelta los decide la lista entera, no la fila con el foco.
                        .onPreviewKeyEvent { e ->
                            if (!enabled || entries.size < 2) return@onPreviewKeyEvent false
                            val letter = when (e.key) {
                                Key.DirectionRight -> 1
                                Key.DirectionLeft -> -1
                                else -> 0
                            }
                            when {
                                // Se come tambien al soltar: nadie mas tiene nada que hacer con ella.
                                letter != 0 -> {
                                    if (e.type == KeyEventType.KeyDown) jumpTo(letterJump(entries, selected, letter))
                                    true
                                }
                                e.type != KeyEventType.KeyDown -> false
                                e.key == Key.DirectionDown && selected == entries.lastIndex -> { jumpTo(0); true }
                                e.key == Key.DirectionUp && selected == 0 -> { jumpTo(entries.lastIndex); true }
                                else -> false
                            }
                        },
                ) {
                    itemsIndexed(entries) { index, label ->
                        MenuEntry(
                            label = label,
                            marked = marked.getOrElse(index) { false },
                            starred = starred.getOrElse(index) { false },
                            selected = index == selected,
                            // El foco se pide al que esté seleccionado, no siempre al primero:
                            // al volver de un modal hay que recuperarlo donde estaba.
                            requester = if (index == selected) selectedRequester else null,
                            enabled = enabled,
                            height = itemHeight,
                            onFocused = {
                                if (!restoring || index == selected) {
                                    onSelect(index); listHasFocus = true; focusWanted = false
                                    // Solo se baja la guardia con la lista encendida: mientras
                                    // hay una ventana encima, que el foco pase por la fila
                                    // elegida no quiere decir que se haya terminado el paseo.
                                    if (enabled) restoring = false
                                }
                            },
                            onTap = { onSelect(index); pendingTap = index },
                            onActivate = onActivate,
                            onLongPress = onLongPress,
                        )
                    }
                }

                if (showDetails && !instrument && staged) {
                    // El texto va con la imagen, a la derecha; aqui abajo solo la cuenta, pequeña.
                    footer?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
                    }
                } else if (showDetails && !instrument) {
                    PanelDivider(Modifier.padding(vertical = 6.dp))

                    Text(
                        description,
                        color = MenuInk, fontSize = 12.sp, lineHeight = 17.sp,
                        fontFamily = MenuBody,
                        // Casi cuadrada, la lista va debajo del panel y a la descripcion le tocan menos.
                    maxLines = if (squareScreen()) 3 else 6, overflow = TextOverflow.Ellipsis,
                    )
                    footer?.let {
                        Spacer(Modifier.height(8.dp))
                        // En el tema PSX el pie es un rotulo mas, en mayusculas y espaciado: es
                        // lo que hace que «18 GAMES · 18 WITH ART» se lea como parte del panel.
                        Text(
                            if (rules) it.uppercase() else it,
                            color = MenuFaint, fontSize = if (rules) 10.sp else 11.sp,
                            fontFamily = MenuBody,
                            letterSpacing = if (rules) t.captionTracking else 0.sp,
                        )
                    }
                }
              }
            }
    }
    val second = DualScreen.display.value
    if (second != null) {
        OnSecondDisplay(second) {
            Box(Modifier.fillMaxSize().background(MenuGround)) { listPane(Modifier.fillMaxSize()) }
        }
    }
    // Lo de la derecha —la imagen y su texto—, aparte: en una pantalla casi cuadrada va arriba,
    // a todo lo ancho, y la lista debajo.
    val stagePane: @Composable RowScope.(Boolean) -> Unit = { square ->
            if (instrument && showDetails && square) {
                // Casi cuadrada: las dos cajas lado a lado en la franja de arriba. Una sobre otra
                // la del texto se comia el alto y la imagen quedaba en una raya.
                Row(
                    Modifier.weight(1f).fillMaxHeight()
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().instrumentPane(ticks = false).padding(PANE_PAD),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Sin las cifras alrededor: en media franja no caben y se cortaban («R30…»).
                        CalloutFrame(emptyList(), key = entries.getOrNull(selected)) { preview() }
                    }
                    TypedDetails(
                        Details("", description, footer.orEmpty()),
                        Modifier.weight(1f).fillMaxHeight().instrumentPane(ticks = false).padding(PANE_PAD),
                        lines = 3,
                        steady = true,
                    )
                }
            } else if (instrument && showDetails) {
                // Dos cajas: la imagen arriba, con todo el alto que sobre, y su texto debajo.
                //
                // El texto se mide a si mismo y la imagen se queda con el resto, y no al reves.
                // Repartiendo por peso, una consola sin descripcion dejaba media pantalla de caja
                // vacia y una con cuatro lineas se comia el giro.
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .padding(start = 6.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Sin cruces: la imagen, mas pequeña, con lineas hacia lo que se sabe de ella.
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .instrumentPane(ticks = false)
                            .padding(PANE_PAD),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Con los rotulos en la clave: los de un juego llegan un momento despues que
                        // la fila, y asi se dibujan desde el principio en vez de aparecer ya puestos.
                        CalloutFrame(callouts, key = entries.getOrNull(selected) to callouts) { preview() }
                    }

                    // Escrito a maquina, y con el MISMO codigo que lo escribe en la sala y bajo
                    // el telefono del cajon de apps.
                    //
                    // Es el mismo gesto —un aparato que contesta y tarda en contestar— asi que
                    // tenerlo dos veces solo serviria para que un dia fueran a ritmos distintos.
                    // Sin titulo: era el nombre de la fila elegida, en el acento, y la lista de al
                    // lado ya lo dice encendido. Su renglon se lo queda el texto, que va mas grande
                    // (ver TypedDetails): a once puntos era de lo que peor se leia de la pantalla.
                    TypedDetails(
                        Details("", description, footer.orEmpty()),
                        Modifier.fillMaxWidth().instrumentPane(ticks = false).padding(PANE_PAD),
                        lines = 3,
                        // Con los tres renglones reservados: dentro de una caja, el texto no puede decidir
                        // cuanto mide el marco.
                        steady = true,
                    )
                }
            } else if (staged) {
                GameStage(
                    deco = stage,
                    facts = callouts,
                    text = description,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp),
                ) { preview() }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { preview() }
            }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val listW = listWidth(maxWidth, instrument)
    // Casi cuadrada (1:1, como algunas consolas de pantalla cuadrada): lista y panel lado a lado
    // dejaban los dos estrechos y Mainframe cortaba sus valores («R30…»). Ahi, el panel arriba y
    // la lista debajo, los dos a todo lo ancho. No en las regletas: su panel es la sala, que va
    // detras (ver BesideListFrame), y ya se ve bien junto a la lista.
    val square = second == null && !rules && maxWidth / maxHeight < SQUARE_ASPECT
    if (square) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(0.44f)) { stagePane(true) }
            listPane(Modifier.fillMaxWidth().weight(0.56f))
        }
    } else Row(Modifier.fillMaxSize()) {
        if (second == null) listPane(Modifier.width(listW).fillMaxHeight())
        stagePane(false)
    }
    }
}

/**
 * Lo que las dos cajas del panel dejan de aire por dentro.
 *
 * Arriba mas que en los otros tres cantos porque ahi esta la ceja del marco: con el mismo
 * relleno por los cuatro lados, lo de dentro quedaba pegado a ella y parecia colgando.
 */
internal val PANE_PAD = PaddingValues(start = 14.dp, end = 14.dp, top = PANE_BROW + 7.dp, bottom = 12.dp)

/**
 * A que fila lleva un salto de letra: a la primera de la letra siguiente (`step` 1) o de la
 * anterior (-1), dando la vuelta en los extremos.
 *
 * Por tramos de la lista tal como esta, no por abecedario. En los juegos es lo mismo, porque
 * van en orden; las consolas van de mas llena a mas vacia, y ahi saltar por abecedario haria
 * ir el cursor de un extremo a otro sin orden que se entienda, mientras que por tramos se
 * salta lo que empieza igual —de PLAYSTATION a SUPER NINTENDO, pasando de largo PLAYSTATION 2—.
 * Hacia atras va al principio del tramo ANTERIOR, que es lo que se espera de «la letra de
 * antes» aunque se este a mitad de una.
 */
internal fun letterJump(entries: List<String>, from: Int, step: Int): Int {
    if (entries.isEmpty()) return from
    val keys = entries.map(::initialOf)
    val starts = keys.indices.filter { it == 0 || keys[it] != keys[it - 1] }
    val here = starts.indexOfLast { it <= from }.coerceAtLeast(0)
    return starts[((here + step) % starts.size + starts.size) % starts.size]
}

/**
 * La letra por la que se agrupa un rotulo: la primera letra o cifra, sin tilde, y todas las
 * cifras juntas —«1942» y «3D Dot Game Heroes» son del mismo tramo—.
 */
private fun initialOf(label: String): Char {
    val c = label.firstOrNull(Char::isLetterOrDigit) ?: return ' '
    if (c.isDigit()) return '#'
    return java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
        .first().uppercaseChar()
}

/**
 * Una línea del menú.
 *
 * El foco y la selección son lo mismo aquí: mover la cruceta ya cambia lo que se ve a la
 * derecha, sin confirmar nada. Por eso la entrada avisa al enfocarse, no al pulsarse.
 */
@Composable
private fun MenuEntry(
    label: String,
    selected: Boolean,
    requester: FocusRequester?,
    enabled: Boolean,
    height: androidx.compose.ui.unit.Dp,
    onFocused: () -> Unit,
    onTap: () -> Unit,
    onActivate: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    marked: Boolean = false,
    starred: Boolean = false,
) {
    val t = LocalTheme.current
    Box(
        Modifier.fillMaxWidth().height(height)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            // Toque y pulsación larga por gestos, NO con clickable.
            //
            // `clickable` y `combinedClickable` hacen el elemento focusable por su cuenta,
            // y sumado al focusable de padItem quedaban dos nodos de foco por fila: la
            // cruceta se quedaba atrapada entre ambos y la selección no se movía de sitio.
            .pointerInput(onLongPress, onTap) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = onLongPress?.let { { _: Offset -> it() } },
                )
            }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .padItem(
                onActivate = onActivate,
                enabled = enabled,
                scaleWhenFocused = 1f,          // el marco ya señala; escalar movería el texto
                borderColor = Color.Transparent,
                borderWidth = 0.dp,
            )
            .then(
                when {
                    !selected -> Modifier
                    // Barra: fondo rojo casi negro y una tira roja en el borde izquierdo.
                    // Es como marcaban la fila los menus de sistema de la PSX, y se lee a
                    // un brazo de distancia mejor que cualquier contorno.
                    t.selection == SelectionStyle.BAR ->
                        Modifier.selectionBar(t.selectionFill, t.accent, ornament = t.ornament)
                    // INVERT: la fila elegida es un BLOQUE que late, no un marco. Un marco
                    // dice «esto esta rodeado»; un bloque dice «esto esta encendido», que es
                    // lo que dice un cursor de terminal. Y es la misma marca que usan las
                    // listas de las ventanas y la rejilla del cajon, asi que hay UNA forma de
                    // estar elegido en todo el programa y no una por pantalla.
                    t.selection == SelectionStyle.INVERT -> Modifier.pulseFill(MenuInk)
                    else -> Modifier.border(2.dp, MenuLine, RoundedCornerShape(2.dp))
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            // Sobre el bloque encendido se escribe con el fondo: es la misma inversion que
            // hacen las filas de las ventanas.
            color = if (selected && t.selection == SelectionStyle.INVERT) MenuGround
                    else if (selected) MenuInk else MenuDim,
            // A catorce y no a diecisiete con el bloque invertido: la lista del Mainframe
            // era la mas ancha de los tres temas para decir lo mismo, y lo que sobra de ella es
            // sitio para las cajas de la derecha. Ver `itemHeight` y `listFraction`.
            fontSize = if (t.selection == SelectionStyle.BAR) 12.5.sp else 14.sp,
            fontFamily = MenuBody,
            fontWeight = FontWeight.Bold,
            letterSpacing = t.itemTracking,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Con el ojo, sitio a los dos lados para que el titulo siga centrado y no lo pise.
            // Con estrella u ojo, sitio a los dos lados: el titulo sigue centrado y no los pisa.
            modifier = Modifier.padding(horizontal = if (marked || starred) 30.dp else 10.dp).then(
                // La elegida, si no cabe, se desliza despacio en vez de cortarse: con «BLOOD
                // OMEN - LEG…» no se sabia cual de los dos Legacy of Kain era. Un segundo quieta
                // antes de empezar, para que recorrer la lista deprisa no la ponga a correr, y a
                // paso de lectura. Las que caben no se mueven; las demas siguen cortadas. Y en
                // reposo se para (ver Motion): corria a lo que diera la pantalla, para siempre.
                if (selected && !Motion.quiet) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1_000,
                        repeatDelayMillis = 1_500,
                        velocity = 28.dp,
                    )
                } else Modifier
            ),
        )
        // La estrella, pegada al borde izquierdo: delante del texto centrado, cada una caia a una
        // altura distinta segun lo largo del titulo, y la lista se veia desordenada.
        if (starred) {
            Star(
                if (selected && t.selection == SelectionStyle.INVERT) MenuGround else t.accent,
                Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(13.dp),
            )
        }
        if (marked) {
            Eye(
                if (selected && t.selection == SelectionStyle.INVERT) MenuGround else t.accent,
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(width = 15.dp, height = 10.dp),
            )
        }
    }
}

/** Una estrella de cinco puntas, rellena: la de los favoritos. Dibujada, como el ojo. */
@Composable
internal fun Star(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2 + size.height * .04f)
        val outer = size.minDimension / 2
        val inner = outer * .42f
        val p = androidx.compose.ui.graphics.Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = c.x + (r * kotlin.math.cos(a)).toFloat()
            val y = c.y + (r * kotlin.math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawPath(p, color)
    }
}

/**
 * Un ojo: el de las misiones que se siguen. Dibujado, como el trofeo, para que salga igual en
 * las tres letras de los temas. Dos arcos que se juntan en las puntas y la pupila en medio.
 */
@Composable
internal fun Eye(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val lid = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h / 2)
            quadraticTo(w / 2, -h * .45f, w, h / 2)
            quadraticTo(w / 2, h * 1.45f, 0f, h / 2)
            close()
        }
        drawPath(lid, color, style = androidx.compose.ui.graphics.drawscope.Stroke(h * .14f))
        drawCircle(color, h * .26f, androidx.compose.ui.geometry.Offset(w / 2, h / 2))
    }
}

/** Cada cuanto cambia la tarjeta viva entre las horas y la sinopsis. */
private const val CARD_PAGE_MS = 9_000L
