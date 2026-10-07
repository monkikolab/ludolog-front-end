package com.felp.frontcomp

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/*
 * The app drawer, opened with L+R.
 *
 * Two tabs, apps and emulators, because they are opened for different reasons: an app to
 * use it, an emulator to change a setting or manage its saves. L and R switch tabs. Start
 * opens the quick menu of the chosen app — move it to the other tab, send it to the
 * Android games console, describe it, hide it, uninstall it — and Select opens the quick
 * menu of the drawer itself, where every app can be sorted at once and the tiles resized.
 * The gear before the title does the same for a finger.
 *
 * The grid takes the left of the window and the right shows something turning, like the
 * console behind the main list: a phone for any app, and for an emulator its medallion, a
 * coin rendered from its icon by the asset pipeline (tools/psx-apps.sh). Under it, what is
 * known about the app, typed out the way the room does it. The icons that pipeline needs
 * are written out here, because adaptive icons are XML and only the device can rasterise
 * them.
 *
 * On these devices the frontend usually replaces the system launcher, so everything else
 * installed has to be reachable from inside it or it is effectively gone.
 *
 * In the PSX theme the tiles are an inventory: square cells with a dark red fall-off and a
 * faint red lattice, the chosen one bracketed in red with a lozenge at each corner, and the
 * icons crushed to a few pixels so a Play Store icon does not look like it came from another
 * decade than the console spinning behind it. Inspired by the inventories of the
 * survival-horror games, not traced from one.
 */

/** Las dos pestañas del cajón. Los juegos de Android no salen aquí: están en su consola. */
private val TABS = listOf(AppSlot.APP, AppSlot.EMULATOR)

/** Lados de casilla, de más por fila a menos. */
private val TILE_SIZES = listOf(64 to "small", 80 to "medium", 104 to "large")

@Composable
internal fun AppsWindow(vm: LibraryViewModel, onClose: () -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    // Sube al mover, ocultar, describir o desinstalar: la lista se lee de fuera de Compose.
    var revision by remember { mutableStateOf(0) }
    val all = remember(vm.emulators, revision) { AppsRepo.list(ctx, vm.emulators, vm.prefs) }
    // Ordenar apps cambia también lo que hay en la consola de Android, que se lee en la
    // pantalla de detrás: se avisa al modelo en el mismo sitio en que se toca.
    val changed = { revision++; vm.refreshAndroidGames(ctx) }
    // Los iconos salen a disco en segundo plano, para la cadena de medallones. Una vez:
    // los que ya estan no se vuelven a escribir.
    LaunchedEffect(all) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppsRepo.exportIcons(ctx, all)
        }
    }
    var tab by remember { mutableStateOf(AppSlot.APP) }
    var tile by remember { mutableStateOf(vm.prefs.tileSize) }
    val counts = remember(all) {
        TABS.associateWith { s -> all.count { !it.hidden && it.slot == s } }
    }
    val apps = remember(all, tab) { all.filter { !it.hidden && it.slot == tab } }
    var selected by remember(tab) { mutableStateOf(0) }
    LaunchedEffect(apps.size) { if (selected >= apps.size) selected = (apps.size - 1).coerceAtLeast(0) }
    // El clic va aqui y no en quien cambia la seleccion, porque la cambian tres cosas: la
    // cruceta, el toque y el foco que vuelve al cerrar un modal. La primera composicion no
    // suena: abrir una lista no es moverse por ella.
    val lastRow = remember { intArrayOf(selected) }
    LaunchedEffect(selected) {
        if (lastRow[0] != selected) Sfx.play(Sfx.Cue.MOVE)
        lastRow[0] = selected
    }
    val current = apps.getOrNull(selected)
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var drawerMenu by remember { mutableStateOf(false) }

    // Los dos menus del cajon suenan como los demas: menu al abrirse, cierre al irse.
    val menuWas = remember { booleanArrayOf(false) }
    LaunchedEffect(menuFor != null, drawerMenu) {
        val now = menuFor != null || drawerMenu
        if (menuWas[0] != now) {
            menuWas[0] = now
            Sfx.play(if (now) Sfx.Cue.MENU else Sfx.Cue.CLOSE)
        }
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(selected, tab) { runCatching { gridState.animateScrollToItem(selected) } }

    // El foco va a la celda elegida al abrir, al cambiar de pestaña y al cerrarse el menú
    // contextual. Pedido una vez por situación y reintentado, por lo mismo que en la lista
    // principal: una rejilla perezosa no tiene celdas en el primer fotograma.
    val selectedRequester = rememberFocusRequester()
    var hasFocus by remember { mutableStateOf(false) }
    val menuOpen = menuFor != null || drawerMenu
    // Y al volver a estar a mano: una ventana de fuera —el informe del scraper, que se abre
    // solo al terminar— apaga la rejilla y se lleva el foco, y al cerrarse nadie lo pedia.
    val padOn = LocalPadEnabled.current
    val gridOn = padOn && !menuOpen
    // Leido en el momento del foco, no en el de cuando se compuso cada celda: el salto de foco
    // ocurre mientras se aplican los cambios, celda a celda, y las que aun no se han actualizado
    // llevan la funcion de antes, que creia la rejilla encendida. Ver onFocused abajo.
    val gridLive by rememberUpdatedState(gridOn)
    LaunchedEffect(tab, menuOpen, apps.size, padOn) {
        if (menuOpen || apps.isEmpty() || !padOn) return@LaunchedEffect
        hasFocus = false
        repeat(20) {
            runCatching { selectedRequester.requestFocus() }
            if (hasFocus) return@LaunchedEffect
            kotlinx.coroutines.delay(40)
        }
    }

    // Los gatillos cambian de pestaña, pero solo una pulsación que EMPEZÓ con la ventana
    // abierta: soltar el L+R que la abrió no debe pasar de pestaña. Y no se consumen, para
    // que la raíz siga llevando la cuenta de qué gatillos hay pulsados.
    val armed = remember { mutableSetOf<Key>() }
    fun switchTab(step: Int) {
        tab = TABS[(TABS.indexOf(tab) + step + TABS.size) % TABS.size]
    }

    // Lo que gira a la derecha. El telefono vale para cualquier app; un emulador enseña su
    // medallon si alguien lo rindio, y si no, el telefono tambien.
    //
    // Salvo en un tema sin giros: esta es una cadena de medallones APARTE de la de consolas,
    // con sus propios renders, y apagar aquella no apagaba esta. En su sitio va el icono de la
    // app, grande — que es lo que el medallon representaba.
    val look = LocalTheme.current
    val spin: File? = remember(current?.pkg, tab, look.appSpins, look.id) {
        if (!look.appSpins) null
        else (if (tab == AppSlot.EMULATOR) current?.let { AppArt.video(it.pkg) } else null)
            ?: AppArt.phone()
    }
    // Y lo que se cuenta debajo: el texto del usuario si lo escribio, y si no, lo que el
    // paquete dice de si mismo.
    val details = remember(current?.pkg, revision) {
        current?.let { Details(it.label, vm.prefs.description("app", it.pkg) ?: AppsRepo.describe(it), it.pkg) }
    }

    ModalWindow(onDismiss = onClose, widthFraction = 0.94f, heightFraction = 0.98f) {
        // Con un menu encima, la rejilla sale de la navegacion. Los menus no son otra ventana
        // sino una caja encima de esta, y la cruceta pasaba de sus filas a las celdas de
        // detras: el menu seguia a la vista, pero A abria la app de la celda y B cerraba el
        // cajon entero. Los menus van fuera de esto y siguen navegandose.
        androidx.compose.runtime.CompositionLocalProvider(LocalPadEnabled provides gridOn) {
        Column(
            Modifier.fillMaxSize().onKeyEvent { e ->
                when {
                    e.key in SHOULDER_KEYS -> {
                        // Solo la primera bajada: mantener el L+R que abrio la ventana manda repeticiones,
                        // y una de ellas armaba el hombro y al soltarlo se pasaba de pestana.
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed += e.key
                        else if (e.type == KeyEventType.KeyUp && armed.remove(e.key)) {
                            switchTab(if (e.key in LEFT_SHOULDER) -1 else +1)
                        }
                        false
                    }
                    // Start: lo que se puede hacer con la app elegida.
                    e.type == KeyEventType.KeyUp && e.key in quickMenuKeys(vm.prefs) -> {
                        current?.let { menuFor = it }
                        true
                    }
                    // Select: lo que se puede hacer con el cajón entero. Se consume para
                    // que la raíz no abra además los ajustes de la aplicación.
                    e.type == KeyEventType.KeyUp && e.key in settingsKeys(vm.prefs) -> {
                        drawerMenu = true
                        true
                    }
                    else -> false
                }
            }
        ) {
            WindowFrame(
                title = "APP DRAWER",
                subtitle = "${apps.size} in ${tab.label.lowercase()}",
                // Lo que se cuenta de la app va bajo el giro, escrito a maquina, no al pie.
                description = null,
                hint = "A  open    Start  app    Select  drawer    L/R  tab    B  close",
                leading = { GearButton { drawerMenu = true } },
            ) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.56f).fillMaxHeight()) {
                        TabBar(tab, counts) { tab = it }
                        Spacer(Modifier.height(8.dp))
                        if (apps.isEmpty()) {
                            // Sin celdas no hay donde poner el foco, y sin foco ni B ni L/R
                            // llegan a la ventana: el ancla los recoge.
                            //
                            // Pero no con un menu encima, que es donde esta el foco: vaciar la
                            // pestana desde «Sort apps» creaba el ancla y esta se lo quitaba
                            // al menu. Y otra vez al cerrarse, que el menu se lo lleva.
                            val anchor = rememberFocusRequester()
                            AutoFocus(anchor, enabled = !menuOpen && padOn)
                            FocusAnchor(anchor)
                            Text(
                                if (tab == AppSlot.EMULATOR)
                                    "No emulators here. Open the drawer menu to sort your apps."
                                else "Nothing here.",
                                color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = tile.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
                                AppTile(
                                    app = app,
                                    selected = index == selected,
                                    requester = if (index == selected) selectedRequester else null,
                                    // Solo con la rejilla a mano. Al abrirse un menu la rejilla se apaga, y la
                                    // celda con el foco se lo pasa a la siguiente, que tambien se apaga y lo
                                    // pasa a la otra: cada paso la elegia, y al cerrar el menu el cursor estaba
                                    // once celdas mas alla —visto en una consola de pruebas—, y la siguiente A abria otra app.
                                    onFocused = { if (gridLive) { selected = index; hasFocus = true } },
                                    onActivate = { AppsRepo.launch(ctx, app)?.let(onToast) ?: onClose() },
                                    onLongPress = { menuFor = app },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(0.44f).fillMaxHeight()) {
                        // Cuatro por tres, como se rinde, en lo que quede de alto sobre el
                        // texto.
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            if (spin != null) {
                                ConsoleTurntable(spin, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                            } else {
                                val big = current?.let { a ->
                                    remember(a.pkg) { AppsRepo.icon(ctx, a.pkg) }
                                }
                                if (big != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = big.asImageBitmap(),
                                        contentDescription = null,
                                        colorFilter = phosphorFilter(),
                                        modifier = Modifier.fillMaxWidth(0.52f).aspectRatio(1f),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (details != null) {
                            TypedDetails(details, Modifier.fillMaxWidth().padding(bottom = 4.dp))
                        }
                    }
                }
            }
        }
        }
    }

    menuFor?.let { app ->
        AppContextMenu(
            app = app,
            prefs = vm.prefs,
            onClose = { menuFor = null },
            onChanged = changed,
            onCloseDrawer = onClose,
            onToast = onToast,
        )
    }
    if (drawerMenu) {
        DrawerMenu(
            vm = vm,
            all = all,
            tile = tile,
            onTile = { tile = it; vm.prefs.tileSize = it },
            onChanged = changed,
            onClose = { drawerMenu = false },
        )
    }
}

/** El engranaje que abre el menú del cajón con el dedo, para quien no use el mando. */
@Composable
private fun GearButton(onClick: () -> Unit) {
    val t = LocalTheme.current
    Box(
        Modifier.pointerInput(Unit) { detectTapGestures { onClick() } }.padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (t.pixelIcons) PixelIcon(PixelIcons.gear, t.accent, scale = 2) else GearIcon()
    }
}

/* -------------------------------------------------------------------------- pestañas */

@Composable
private fun TabBar(tab: AppSlot, counts: Map<AppSlot, Int>, onPick: (AppSlot) -> Unit) {
    val t = LocalTheme.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        for (d in TABS) {
            val on = d == tab
            val color = if (on) t.accent else t.faint
            Row(
                // Gestos y no clickable: clickable haria la pestaña focusable y la cruceta
                // se quedaria atrapada en ella al subir desde la rejilla.
                Modifier.pointerInput(d) { detectTapGestures { onPick(d) } }
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // El rombo delante de la pestaña activa, en rojo: es el mismo cursor que
                // marca la fila elegida en las listas, tumbado.
                Canvas(Modifier.size(10.dp)) {
                    lozenge(Offset(size.width / 2f, size.height / 2f), 4.dp.toPx(), color)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${d.label.uppercase()}  ${counts[d] ?: 0}",
                    color = if (on) MenuInk else MenuDim,
                    fontSize = 11.sp, fontFamily = MenuBody,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = t.captionTracking,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------- celdas */

@Composable
private fun AppTile(
    app: AppEntry,
    selected: Boolean,
    requester: FocusRequester?,
    onFocused: () -> Unit,
    onActivate: () -> Unit,
    onLongPress: () -> Unit,
) {
    val ctx = LocalContext.current
    val t = LocalTheme.current
    val inventory = t.chrome == Chrome.RULES
    val icon = remember(app.pkg) { AppsRepo.icon(ctx, app.pkg) }
    val pulse = if (inventory && selected) rememberCursorPulse() else null

    Column(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            // Toque y pulsacion larga por gestos, no con clickable, por lo mismo que en la
            // lista: clickable anade un segundo nodo de foco y la cruceta se atasca.
            .pointerInput(onLongPress, onActivate) {
                detectTapGestures(onTap = { onActivate() }, onLongPress = { onLongPress() })
            }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .padItem(
                onActivate = onActivate,
                focusRequester = requester,
                scaleWhenFocused = 1f,
                borderColor = Color.Transparent,
                borderWidth = 0.dp,
            )
            .then(
                if (inventory) Modifier.inventoryCell(
                    selected = selected, pulse = pulse,
                    top = t.selectionFill, bottom = t.ground,
                    lattice = t.accent, edge = t.dim, chosen = t.accent,
                    ornament = t.ornament,
                )
                // El recuadro marca la seleccion sin mover el icono: en una rejilla,
                // escalar desplaza a los vecinos y cuesta seguir donde esta uno.
                else if (t.selection == SelectionStyle.INVERT) Modifier
                    // Bloque que late, no recuadro: la misma marca que las listas y el menu.
                    .then(if (selected) Modifier.pulseFill(MenuInk) else Modifier)
                // Y en el resto, un recuadro que late como el cursor de las listas: el mismo
                // pulso en todos los temas, aqui sobre el filete.
                else Modifier.then(if (selected) Modifier.pulseFrame(MenuLine) else Modifier)
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                icon == null -> Text(
                    app.label.take(1).uppercase(), color = MenuDim, fontSize = 20.sp,
                    fontFamily = MenuBody,
                )
                inventory -> PixelatedIcon(icon, box = 54.dp)
                else -> Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.size(46.dp),
                )
            }
        }
        Text(
            app.label,
            color = if (selected) MenuInk else MenuDim,
            fontSize = 9.sp, lineHeight = 11.sp,
            fontFamily = MenuBody,
            textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/**
 * The inventory cell of the PSX theme.
 *
 * A fall-off from the selection red at the top to the ground at the bottom, so the cell
 * reads as a recess; over it a lattice of diagonals in the accent, faint enough to be a
 * texture rather than a pattern; a hairline edge; and, when chosen, the accent as a frame
 * with a lozenge at each corner, breathing with the same pulse as the list cursor.
 */
private fun Modifier.inventoryCell(
    selected: Boolean,
    pulse: State<Float>?,
    top: Color,
    bottom: Color,
    lattice: Color,
    edge: Color,
    chosen: Color,
    ornament: Boolean,
): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(0f to top, 1f to bottom))
    // Diagonales en los dos sentidos, cada nueve puntos: forman rombos del tamano de una
    // uña, que es lo que se ve en el fondo de las casillas del juego de referencia.
    val step = 9.dp.toPx()
    val hair = 1f
    val lat = lattice.copy(alpha = .13f)
    clipRect {
        var x = -size.height
        while (x < size.width) {
            drawLine(lat, Offset(x, 0f), Offset(x + size.height, size.height), hair)
            drawLine(lat, Offset(x + size.height, 0f), Offset(x, size.height), hair)
            x += step
        }
    }
    if (selected) {
        val c = chosen.copy(alpha = pulse?.value ?: 1f)
        drawRect(c, style = Stroke(2.dp.toPx()))
        if (ornament) {
            val r = 3.5.dp.toPx()
            for (p in listOf(
                Offset(0f, 0f), Offset(size.width, 0f),
                Offset(0f, size.height), Offset(size.width, size.height),
            )) lozenge(p, r, c)
        }
    } else {
        drawRect(edge.copy(alpha = .35f), style = Stroke(1.dp.toPx()))
    }
}

/**
 * An app icon crushed to a few pixels and blown back up without filtering.
 *
 * Twenty-four pixels a side is enough to tell a browser from a file manager and too few
 * for an icon to look modern. Halved step by step before the last resize, because one
 * bilinear jump from 192 to 24 samples four pixels of every sixty-four and turns fine
 * icons into confetti. Saturation comes down a little, like the gameplay video in the
 * tube: the room is lit by candles and a Play Store green is not.
 */
@Composable
private fun PixelatedIcon(icon: Bitmap, box: Dp) {
    val density = LocalDensity.current
    val small = remember(icon) { pixelate(icon, 24).asImageBitmap() }
    // El icono de la rejilla se tiñe como el grande de al lado, y no de otra manera.
    //
    // Estaba con una saturacion fija del 72 % —bajar el color un poco y ya— mientras el
    // grande del panel pasaba por el fosforo del tema. En un tema de un solo fosforo eso
    // dejaba la rejilla a todo color, con su azul y su naranja, y el mismo icono en grande
    // en rojo dos palmos a la derecha. Donde no hay fosforo se queda como estaba.
    val plain = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.72f) }) }
    val filter = phosphorFilter() ?: plain
    val boxPx = with(density) { box.roundToPx() }
    // Escala entera: con una fraccionaria unos pixeles salen de cuatro y otros de cinco.
    val k = (boxPx / 24).coerceAtLeast(1)
    val d = 24 * k
    Canvas(Modifier.size(with(density) { d.toDp() })) {
        drawImage(
            small,
            dstOffset = IntOffset(((size.width - d) / 2f).toInt(), ((size.height - d) / 2f).toInt()),
            dstSize = IntSize(d, d),
            filterQuality = FilterQuality.None,
            colorFilter = filter,
        )
    }
}

private fun pixelate(src: Bitmap, n: Int): Bitmap {
    var b = src
    while (b.width > n * 2 && b.height > n * 2) {
        b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    }
    return Bitmap.createScaledBitmap(b, n, n, true)
}

/* ------------------------------------------------------------------ menú del cajón */

/**
 * The quick menu of the drawer itself, on Select or on the gear.
 *
 * Two things, and both are about the drawer rather than about one app: sorting every app
 * at once — including the hidden ones, which are otherwise invisible by definition — and
 * how big the tiles are.
 */
@Composable
private fun DrawerMenu(
    vm: LibraryViewModel,
    all: List<AppEntry>,
    tile: Int,
    onTile: (Int) -> Unit,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    var sorting by remember { mutableStateOf(false) }
    // Delante del `return`, por lo mismo que en AppContextMenu: al volver de «Sort apps» el
    // cursor saltaba a la primera fila.
    var selected by remember { mutableStateOf(0) }
    if (sorting) {
        SortAppsPane(vm, all, onChanged) { sorting = false }
        return
    }
    val hidden = all.count { it.hidden }
    val games = all.count { it.slot == AppSlot.GAME }
    val tileName = TILE_SIZES.firstOrNull { it.first == tile }?.second ?: "$tile"

    val actions = listOf(
        Act("Sort apps", "Every installed app, hidden ones included. A moves one between " +
            "apps, emulators, the Android games console and hidden.",
            value = "${all.size}", chevron = true) { sorting = true },
        Act("Tile size", "How big the icons are in the grid.", value = tileName) {
            val i = TILE_SIZES.indexOfFirst { it.first == tile }
            onTile(TILE_SIZES[(i + 1).coerceAtLeast(0) % TILE_SIZES.size].first)
        },
        Act("Hidden", "Apps taken out of the drawer. Sort apps brings one back.",
            value = if (hidden == 0) "none" else "$hidden") { sorting = true },
        Act("Android games", "Apps sent to their own console in the library.",
            value = if (games == 0) "none" else "$games") { sorting = true },
    )
    val sel = selected.coerceIn(0, actions.lastIndex)

    ModalWindow(onDismiss = onClose, widthFraction = 0.50f, heightFraction = 0.62f) {
        WindowFrame(
            title = "APP DRAWER",
            subtitle = "${all.size} installed",
            description = actions[sel].blurb,
            hint = "B  back",
        ) {
            ActRows(actions, sel) { selected = it }
        }
    }
}

/**
 * Every app and where it is filed, with A moving it on to the next place.
 *
 * One screen instead of four, because the question is always the same one — where does
 * this belong — and a list that answers it for every app at once is quicker than opening
 * a menu per app. The order is fixed: apps, emulators, Android games, hidden, and round.
 */
@Composable
private fun SortAppsPane(
    vm: LibraryViewModel,
    all: List<AppEntry>,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, (all.size - 1).coerceAtLeast(0))
    val current = all.getOrNull(sel)

    fun where(a: AppEntry): String = if (a.hidden) "Hidden" else a.slot.label

    ModalWindow(onDismiss = onClose, widthFraction = 0.62f, heightFraction = 0.82f) {
        WindowFrame(
            title = "SORT APPS",
            subtitle = "${all.size} installed",
            description = current?.let {
                "${it.pkg}\nNow in: ${where(it)}.  A moves it on."
            }.orEmpty(),
            hint = "A  move      B  back",
        ) {
            ModalRows(
                count = all.size,
                selected = sel,
                onSelect = { selected = it },
                onActivate = {
                    all.getOrNull(sel)?.let { a ->
                        when {
                            a.hidden -> {
                                vm.prefs.setAppHidden(a.pkg, false)
                                vm.prefs.setAppSlot(a.pkg, AppSlot.APP.key)
                            }
                            a.slot == AppSlot.APP -> vm.prefs.setAppSlot(a.pkg, AppSlot.EMULATOR.key)
                            a.slot == AppSlot.EMULATOR -> vm.prefs.setAppSlot(a.pkg, AppSlot.GAME.key)
                            else -> vm.prefs.setAppHidden(a.pkg, true)
                        }
                        onChanged()
                    }
                },
            ) { index ->
                val a = all[index]
                ModalRow(label = a.label, value = where(a), dimmed = a.hidden)
            }
        }
    }
}

/* -------------------------------------------------------------------- menú de una app */

/**
 * What can be done to one app: open it, file it somewhere else, write what it is, take it
 * out of the front end, or hand it to Android's uninstaller. The last one closes the
 * drawer, because the list would still show the app until it is opened again.
 */
@Composable
private fun AppContextMenu(
    app: AppEntry,
    prefs: Prefs,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onCloseDrawer: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var describing by remember { mutableStateOf(false) }
    // Antes de volver por la descripcion y no despues: recordado detras del `return`, se
    // olvidaba mientras se escribia y al cancelar el cursor volvia a «Open», y la siguiente A
    // abria la app. Ver SystemContextMenu.
    var selected by remember { mutableStateOf(0) }
    val ownText = prefs.description("app", app.pkg)

    if (describing) {
        TextWindow(
            title = "DESCRIBE APP",
            initial = ownText ?: AppsRepo.describe(app),
            help = "Typed under the phone or the medallion. Empty goes back to what the package says.",
            multiline = true,
            onCancel = { describing = false },
            onDone = { text ->
                prefs.setDescription("app", app.pkg, text.takeIf { it != AppsRepo.describe(app) })
                describing = false
                onChanged()
                onClose()
            },
        )
        return
    }

    fun moveTo(slot: AppSlot) {
        prefs.setAppSlot(app.pkg, slot.key)
        onChanged()
        onClose()
    }

    val actions = buildList {
        add(Act("Open", app.pkg) { AppsRepo.launch(ctx, app)?.let(onToast) ?: onCloseDrawer() })
        for (slot in AppSlot.entries) {
            if (slot == app.slot) continue
            val blurb = when (slot) {
                AppSlot.GAME -> "Send it to the Android games console, in the library with the ROMs."
                else -> "Show it under the ${slot.label.lowercase()} tab instead."
            }
            add(Act(if (slot == AppSlot.GAME) "Send to Android games" else "Move to ${slot.label.lowercase()}", blurb) {
                moveTo(slot)
            })
        }
        add(Act("Edit description", "Your own text about this app, instead of what the package says.",
            value = if (ownText != null) "edited" else "") { describing = true })
        add(Act("Hide from the front end", "Take it out of the drawer. The drawer menu brings it back.") {
            prefs.setAppHidden(app.pkg, true); onChanged(); onClose()
        })
        add(Act("Uninstall", "Hands it to Android, which asks before removing anything.", chevron = true) {
            AppsRepo.uninstall(ctx, app)?.let(onToast) ?: onCloseDrawer()
        })
        if (ownText != null) add(Act("Reset description", "Go back to what the package says.") {
            prefs.setDescription("app", app.pkg, null); onChanged(); onClose()
        })
    }
    val sel = selected.coerceIn(0, actions.lastIndex)

    ModalWindow(onDismiss = onClose, widthFraction = 0.50f, heightFraction = 0.72f) {
        WindowFrame(
            title = app.label.uppercase(),
            subtitle = app.slot.label.lowercase().trimEnd('s'),
            description = actions[sel].blurb,
            hint = "B  back",
        ) {
            ActRows(actions, sel) { selected = it }
        }
    }
}
