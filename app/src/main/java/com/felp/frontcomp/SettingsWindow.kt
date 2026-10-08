package com.felp.frontcomp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/*
 * Settings as a window over the library rather than a screen of its own.
 *
 * Keeping the library visible behind it is the point: settings are a detour, not a place,
 * and the dimmed background says so without needing a back button to explain it.
 */

/** Where the window is, since it can drill down into per-system emulator choice. */
private sealed interface Pane {
    data object Root : Pane
    data object Systems : Pane
    data class Emulators(val systemId: String) : Pane
    data object Consoles : Pane
    data class ConsoleEdit(val systemId: String?) : Pane
    data object Appearance : Pane
    data object HiddenApps : Pane
    data object Accent : Pane
    data object ArtSources : Pane
    data object Levels : Pane
    data object Shortcuts : Pane
    data class ShortcutPick(val action: com.felp.frontcomp.Shortcuts.Action) : Pane
    data object About : Pane
    data object RomFolders : Pane
    data object CatalogSource : Pane
}

/**
 * La pestana en la que se quedo, de una vez a la siguiente.
 *
 * Quien abre los ajustes suele volver a lo mismo que tocaba —el sonido, un escaneo—, y abrir
 * siempre en la primera es hacerle cruzar las pestanas cada vez. Vive lo que vive el proceso,
 * que es lo que dura una sesion de uso: no merece un hueco en config.xml.
 */
private var lastTab = 0

@Composable
internal fun SettingsWindow(
    vm: LibraryViewModel,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
) {
    var pane by remember { mutableStateOf<Pane>(Pane.Root) }
    // Cambia al guardar o borrar una consola, para que la lista se vuelva a leer.
    var revision by remember { mutableStateOf(0) }
    // La pestana y la fila de la raiz viven aqui y no dentro de ella: al volver de un submenu la
    // raiz se compone de nuevo, y guardadas dentro se volvia a la primera fila de la primera
    // pestana en vez de a la que se acababa de abrir.
    var tab by remember { mutableStateOf(lastTab) }
    var row by remember { mutableStateOf(0) }

    fun back() {
        pane = when (pane) {
            is Pane.Emulators -> Pane.Systems
            is Pane.ConsoleEdit -> Pane.Consoles
            is Pane.Consoles -> Pane.Root
            is Pane.Appearance -> Pane.Root
            is Pane.HiddenApps -> Pane.Root
            is Pane.Accent -> Pane.Root
            is Pane.ArtSources -> Pane.Root
            is Pane.Levels -> Pane.Root
            is Pane.Shortcuts -> Pane.Root
            is Pane.ShortcutPick -> Pane.Shortcuts
            is Pane.Systems -> Pane.Root
            is Pane.About -> Pane.Root
            is Pane.RomFolders -> Pane.Root
            is Pane.CatalogSource -> Pane.Root
            is Pane.Root -> return onClose()
        }
    }

    ModalWindow(onDismiss = ::back) {
        when (val p = pane) {
            is Pane.Root -> RootPane(
                vm, onClose,
                tab = tab,
                row = row,
                onTab = {
                    tab = it
                    lastTab = it
                    row = 0
                },
                onRow = { row = it },
            ) { id ->
                pane = when (id) {
                    "consoles" -> Pane.Consoles
                    "theme" -> Pane.Appearance
                    "hiddenapps" -> Pane.HiddenApps
                    "accent" -> Pane.Accent
                    "artsources" -> Pane.ArtSources
                    "levels" -> Pane.Levels
                    "shortcuts" -> Pane.Shortcuts
                    "about" -> Pane.About
                    "romfolders" -> Pane.RomFolders
                    "catalogsource" -> Pane.CatalogSource
                    else -> Pane.Systems
                }
            }
            is Pane.Systems -> SystemsPane(vm) { pane = Pane.Emulators(it) }
            is Pane.Emulators -> EmulatorsPane(vm, p.systemId)
            is Pane.Consoles -> ConsoleListPane(vm, revision) { pane = Pane.ConsoleEdit(it) }
            is Pane.ConsoleEdit -> ConsoleEditPane(
                vm, p.systemId,
                onDone = { revision++; pane = Pane.Consoles },
                onToast = onToast,
            )
            is Pane.Appearance -> AppearancePane()
            is Pane.HiddenApps -> HiddenAppsPane(vm)
            is Pane.Accent -> AccentPane()
            is Pane.ArtSources -> ArtSourcesPane(vm)
            is Pane.Levels -> LevelsPane(vm)
            is Pane.Shortcuts -> ShortcutsPane(vm) { pane = Pane.ShortcutPick(it) }
            is Pane.ShortcutPick -> ShortcutPickPane(vm, p.action) { pane = Pane.Shortcuts }
            is Pane.About -> AboutPane(vm)
            is Pane.RomFolders -> RomFoldersPane(vm)
            // Una direccion web o una carpeta del aparato. Vacia vuelve a la de Ludolog.
            is Pane.CatalogSource -> TextPage(
                title = "Catalog source",
                initial = vm.prefs.catalogSource.orEmpty(),
                help = "An address that ends where index.tsv is, or a folder on this device " +
                    "with the catalog files in it. Leave it empty to use Ludolog's own.",
                onCancel = { pane = Pane.Root },
                onDone = { vm.prefs.catalogSource = it; pane = Pane.Root },
            )
        }
    }
}

@Composable
private fun ColumnScope.RootPane(
    vm: LibraryViewModel,
    onClose: () -> Unit,
    tab: Int,
    row: Int,
    onTab: (Int) -> Unit,
    onRow: (Int) -> Unit,
    onSubmenu: (String) -> Unit,
) {
    val ctx = LocalContext.current
    // Settings read state that lives outside Compose — preferences, files on disk — so a
    // counter is what tells the list to look again after something changes.
    var revision by remember { mutableStateOf(0) }
    val toBright = LocalBrightSwitch.current
    // La peticion de Android para ser la app de inicio, o sus ajustes de inicio. Al volver se
    // mira otra vez, y con `HomeApp.held` entre las claves la fila dice lo que quedo.
    val home = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { HomeApp.refresh(ctx) }
    val tabs = remember(
        revision, vm.result, vm.art, vm.busy, vm.catalog, HomeApp.held,
        LinkSaveCheck.present.value, LinkInstaller.status.value, LinkInstaller.lastError.value,
    ) {
        SettingsModel.build(
            ctx, vm, onClose, onBright = toBright,
            onHome = { runCatching { home.launch(HomeApp.intent(ctx)) } },
        ) {
            vm.loadCatalog { n -> ctx.assets.open(n).bufferedReader().use { it.readText() } }
        }
    }
    val at = tab.coerceIn(0, tabs.lastIndex)
    val items = tabs[at].items
    val selected = row.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    // Los hombros que se apretaron DENTRO de la ventana. Se cambia de pestana al soltar uno de
    // esos, como en el cajon de apps, y el evento sigue subiendo: la raiz lleva la cuenta de
    // los hombros apretados para su acorde, y comerse el soltar se la dejaria descuadrada.
    val armed = remember { mutableSetOf<Key>() }
    // La accion que espera su segunda A (ver SettingItem.Action.confirm), por su titulo.
    // Cambiar de fila o de pestana la desarma.
    var confirming by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(at, selected) { confirming = null }
    val waiting = (items.getOrNull(selected) as? SettingItem.Action)?.takeIf { it.title == confirming }

    WindowFrame(
        title = "SETTINGS",
        subtitle = "${items.size} options",
        description = if (waiting != null) "A again does it. Moving to another row cancels."
                      else items.getOrNull(selected)?.description.orEmpty(),
        hint = "L R  tab      B  close",
    ) {
        Column(
            Modifier.fillMaxSize().onKeyEvent { e ->
                if (e.key in SHOULDER_KEYS) {
                    // Solo la primera bajada, como en el cajon: ver AppsWindow.
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed += e.key
                    else if (e.type == KeyEventType.KeyUp && armed.remove(e.key)) {
                        Sfx.play(Sfx.Cue.MOVE)
                        onTab((at + (if (e.key in LEFT_SHOULDER) -1 else 1) + tabs.size) % tabs.size)
                    }
                }
                false
            },
        ) {
            // Las mismas pestanas que el cuaderno, con la misma forma en cada tema, y mas
            // apretadas: aqui son siete y tienen que caber en el ancho de la ventana.
            Chips(
                tabs.map { it.title }, at, gap = 8.dp, padX = 6.dp, focusable = false,
                // Son los titulos de lo que hay debajo: en blanco, en negrita y mas grandes que
                // los filtros del cuaderno, para que se lean como titulos y no como filtros.
                fontSize = 11.sp, strong = true, scroll = true, onPick = onTab,
            )
            Spacer(Modifier.height(10.dp))
            // Una lista por pestana y no la misma con otras filas: la lista fija su foco al
            // componerse, y reutilizada dejaba el foco en una fila que la pestana nueva
            // quiza ni tenia, con la cruceta muerta.
            Box(Modifier.weight(1f)) {
                key(at) {
                    ModalRows(
                        count = items.size,
                        selected = selected,
                        onSelect = onRow,
                        onActivate = {
                            when (val item = items.getOrNull(selected)) {
                                is SettingItem.Toggle -> { item.onChange(!item.on); revision++ }
                                is SettingItem.Action -> when {
                                    !item.enabled -> Unit
                                    item.confirm != null && confirming != item.title -> confirming = item.title
                                    else -> { confirming = null; item.run(); revision++ }
                                }
                                is SettingItem.Submenu -> onSubmenu(item.id)
                                is SettingItem.Level -> item.onPlay()
                                else -> Unit
                            }
                        },
                        // Izquierda y derecha, solo en las filas de nivel: las demas siguen igual.
                        onStep = { d ->
                            (items.getOrNull(selected) as? SettingItem.Level)?.let { it.onStep(d); revision++; true } ?: false
                        },
                    ) { index ->
                        val item = items[index]
                        ModalRow(
                            label = item.title,
                            value = (item as? SettingItem.Action)?.takeIf { it.title == confirming }?.confirm
                                ?: item.value,
                            dimmed = item is SettingItem.Action && !item.enabled,
                            chevron = item is SettingItem.Submenu,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SystemsPane(vm: LibraryViewModel, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val rows = remember(vm.result, vm.emulators) { SettingsModel.systemsWithChoices(ctx, vm) }
    var selected by remember { mutableStateOf(0) }

    WindowFrame(
        title = "EMULATOR PER SYSTEM",
        subtitle = "${rows.size} consoles",
        description = rows.getOrNull(selected)?.let { (sys, opts) ->
            "${opts.size} installed for ${sys.name}."
        }.orEmpty(),
        hint = "B  back",
    ) {
        ModalRows(
            count = rows.size,
            selected = selected,
            onSelect = { selected = it },
            onActivate = { rows.getOrNull(selected)?.let { onPick(it.first.id) } },
        ) { index ->
            val (sys, _) = rows[index]
            // Lo mismo que decide el lanzamiento (ver LibraryViewModel.launchName): la elegida
            // por su id o por su paquete, y con el nucleo si es RetroArch.
            val chosen = vm.prefs.emulatorForSystem(sys.id)
            val name = remember(sys.id, chosen, vm.launcher) {
                vm.launchName(ctx.packageManager, sys.id, chosen).orEmpty()
            }
            ModalRow(
                label = sys.name,
                // Con un punto y no entre parentesis: el nombre de RetroArch ya lleva los suyos.
                value = if (chosen == null) "$name · default" else name,
                chevron = true,
            )
        }
    }
}

@Composable
private fun ColumnScope.EmulatorsPane(vm: LibraryViewModel, systemId: String) {
    val ctx = LocalContext.current
    val sys = vm.catalog?.byId?.get(systemId)
    val options = remember(systemId, vm.emulators) {
        SettingsModel.systemsWithChoices(ctx, vm)
            .firstOrNull { it.first.id == systemId }?.second.orEmpty()
    }
    var revision by remember { mutableStateOf(0) }
    val chosen = remember(revision, systemId) { vm.prefs.emulatorForSystem(systemId) }
    // La que abre los juegos: la elegida, tambien si se guardo por su paquete, y sin eleccion la
    // primera de la tabla. Ver LibraryViewModel.launchDef.
    val used = remember(revision, systemId, vm.launcher) {
        vm.launchDef(ctx.packageManager, systemId, chosen)
    }
    fun inUse(def: EmulatorDef) = def.id == used?.id
    var selected by remember(systemId) {
        mutableStateOf(options.indexOfFirst(::inUse).coerceAtLeast(0))
    }

    WindowFrame(
        title = sys?.name?.uppercase() ?: systemId.uppercase(),
        subtitle = "${options.size} installed",
        description = options.getOrNull(selected)?.let { def ->
            "${def.pkg}\n" +
                if (def.isRetroArch) retroCore(vm, systemId, short = false)
                else "Hands over the game as: ${def.hand.name}"
        }.orEmpty(),
        hint = "A  choose      B  back",
    ) {
        ModalRows(
            count = options.size,
            selected = selected,
            onSelect = { selected = it },
            onActivate = {
                options.getOrNull(selected)?.let {
                    vm.prefs.setEmulatorForSystem(systemId, it.id)
                    revision++
                }
            },
        ) { index ->
            val def = options[index]
            val state = if (inUse(def)) "in use" else ""
            val core = if (def.isRetroArch) retroCore(vm, systemId, short = true) else ""
            ModalRow(label = def.label, value = listOf(core, state).filter { it.isNotEmpty() }.joinToString(" · "))
        }
    }
}

@Composable
internal fun GearIcon() {
    val ink = MenuInk
    Canvas(Modifier.size(20.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val tip = size.minDimension * 0.46f
        val root = tip * 0.72f
        val hub = tip * 0.30f
        val teeth = 8
        val step = (2.0 * Math.PI / teeth).toFloat()
        // Medio diente, en radianes. Los dientes ocupan algo menos de la mitad del paso y el
        // resto es el hueco entre ellos: un engranaje con los dientes tan anchos como los
        // huecos vuelve a ser una rueda dentada de dibujo animado.
        val half = step * 0.22f
        val gear = androidx.compose.ui.graphics.Path()
        fun at(r: Float, a: Float) =
            Offset(cx + r * cos(a.toDouble()).toFloat(), cy + r * sin(a.toDouble()).toFloat())
        for (i in 0 until teeth) {
            val a = i * step
            val p0 = at(root, a - half * 1.7f)
            val p1 = at(tip, a - half)
            val p2 = at(tip, a + half)
            val p3 = at(root, a + half * 1.7f)
            if (i == 0) gear.moveTo(p0.x, p0.y) else gear.lineTo(p0.x, p0.y)
            gear.lineTo(p1.x, p1.y)
            gear.lineTo(p2.x, p2.y)
            gear.lineTo(p3.x, p3.y)
        }
        gear.close()
        // Y el agujero del centro, restado.
        //
        // Es lo que separa un engranaje de un sol, y no la longitud de los dientes. Antes esto
        // era un circulo con ocho rayos saliendo: los rayos eran los dientes en la cabeza de
        // quien lo dibujo y un sol en la de cualquier otro. Con la corona maciza y el buje
        // vacio no hay forma de leerlo mal.
        val hole = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), hub))
        }
        gear.op(gear, hole, androidx.compose.ui.graphics.PathOperation.Difference)
        drawPath(gear, ink)
    }
}

/**
 * The look, chosen from the list.
 *
 * Nada cambia hasta la A. Moverse por la lista solo mueve el cursor y cuenta, abajo, como es
 * el aspecto que hay debajo; la A lo pone —reiniciando, para que no quede nada del anterior a
 * medio cambiar— y la B vuelve sin haber tocado nada.
 *
 * Se aplicaba al mover el cursor, para comparar, y la A solo confirmaba. Pero asomarse ya era
 * cambiar: cada fila por la que se pasaba repintaba la pantalla y cambiaba letra, sonidos y
 * ambiente, y el tema que se veia no era el que se tenia. Mirar y elegir son dos gestos, y
 * solo el segundo cambia algo.
 *
 * La luz ya no esta aqui: es una propiedad del aspecto puesto y va en la pestana del tema,
 * donde se llega con una pulsacion. Ver SettingsModel.
 */
@Composable
private fun ColumnScope.AppearancePane() {
    val current = LocalTheme.current
    val here = LocalContext.current
    val dismiss = LocalDismiss.current
    val prefs = Prefs(here)
    var selected by remember { mutableStateOf(AllThemes.indexOfFirst { it.id == current.chosenId }.coerceAtLeast(0)) }

    WindowFrame(
        title = "APPEARANCE",
        subtitle = "${AllThemes.size} looks",
        description = when (AllThemes.getOrNull(selected)?.id) {
            "parlour" -> "PSX-era survival horror: a tracked monospace in capitals, loose " +
                "rules with lozenges, one accent for whatever is chosen, and the room " +
                "behind the list."
            "mainframe" -> "Signalis-like terminal: monochrome with one cold accent and " +
                "a scanline under every rule."
            "gallery" -> "The least of everything: no room and no cabinet. The box art or its " +
                "gameplay, a sans-serif list and one hairline rule."
            else -> "The look of the menus, the type and the ornament. It also decides " +
                "whether there is a room around the list."
        },
        hint = "A  apply      B  back",
    ) {
        ModalRows(
            count = AllThemes.size,
            selected = selected,
            onSelect = { selected = it },
            onActivate = {
                // Se guarda y se reinicia: el proceso nuevo arranca ya con este aspecto, sin
                // haber pintado nunca un fotograma a medio cambiar. Si es el que ya estaba
                // puesto no se reinicia por reiniciar: se cierra la ventana y ya.
                val pick = AllThemes[selected].id
                if (pick == current.chosenId) dismiss?.invoke()
                else {
                    prefs.themeId = pick
                    restartApp(here)
                }
            },
        ) { index ->
            ModalRow(
                label = AllThemes[index].name,
                value = if (AllThemes[index].id == current.chosenId) "in use" else "",
            )
        }
    }
}

/**
 * The apps taken out of the drawer, so hiding one is never a one-way door.
 *
 * Listed with what they are, because the reason to bring one back is usually "that was
 * the emulator, not the tool with the same icon".
 */
@Composable
private fun ColumnScope.HiddenAppsPane(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    val hidden = remember(revision, vm.emulators) {
        AppsRepo.list(ctx, vm.emulators, vm.prefs).filter { it.hidden }
    }
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, (hidden.size - 1).coerceAtLeast(0))

    WindowFrame(
        title = "HIDDEN APPS",
        subtitle = "${hidden.size} hidden",
        description = hidden.getOrNull(sel)?.let {
            "${it.pkg}\nShown again in the drawer, under ${if (it.isEmulator) "emulators" else "apps"}."
        // Start y no «mantener A o pulsar Y», que no hacen nada en el cajon: ver AppsWindow.
        } ?: "Nothing hidden. In the drawer, press Start on an app (or hold it) and choose Hide.",
        hint = "A  show again      B  back",
    ) {
        ModalRows(
            count = hidden.size,
            selected = sel,
            onSelect = { selected = it },
            onActivate = {
                hidden.getOrNull(sel)?.let { vm.prefs.setAppHidden(it.pkg, false); revision++ }
            },
        ) { index ->
            ModalRow(
                label = hidden[index].label,
                value = if (hidden[index].isEmulator) "emulator" else "",
            )
        }
    }
}

/**
 * The accent, chosen from a short list of named colours.
 *
 * It applies as the selection moves, like the theme: a colour cannot be judged from its
 * name, and the window behind is already wearing it. A stays; B keeps the last one too,
 * which is fine, because nothing here is destructive.
 */
@Composable
private fun ColumnScope.AccentPane() {
    val ctx = LocalContext.current
    val switch = LocalAccentSwitch.current
    val theme = LocalTheme.current
    val stored = remember { Prefs(ctx).accent }
    // La lista de ESTE tema; y si lo que hay puesto no esta en ella —un color elegido en la lista
    // de antes, que era una para todos—, arriba, como «Current»: si no, abrir la ventana lo
    // cambiaba al del tema sin haber tocado nada, porque el color se aplica al moverse.
    val presets = remember(theme.id, stored) {
        val own = accentPresets(theme.id)
        val keep = stored?.takeIf { s -> own.none { accentHex(it.second) == s } }
            ?.removePrefix("0x")?.toLongOrNull(16)
        if (keep != null) listOf("Current" to keep) + own else own
    }
    var selected by remember {
        mutableStateOf(presets.indexOfFirst { accentHex(it.second) == stored }.coerceAtLeast(0))
    }
    val themeDefault = remember(theme.id) { themeById(theme.id).accent }

    /*
     * El color se aplica DESPUES del fotograma, no dentro del callback de foco.
     *
     * Cambiar el acento cambia el tema, y cambiar el tema recompone la pantalla entera,
     * incluida esta lista. Hacerlo mientras el foco todavia se esta moviendo dejaba la
     * seleccion cinco o seis filas mas abajo de golpe: cada fila recreada avisaba de que
     * habia recibido el foco y la lista iba encadenando avisos. Con un respiro, el foco
     * termina su viaje primero y la recomposicion llega cuando ya no molesta.
     */
    LaunchedEffect(selected) {
        switch(accentHex(presets[selected].second))
    }

    WindowFrame(
        title = "ACCENT COLOUR",
        subtitle = "${presets.size} colours",
        description = "The one colour of the interface: the cursor, the rules, the titles in the " +
            "room and the frames of the drawer. It applies as you move.",
        hint = "A  keep      B  back",
    ) {
        ModalRows(
            count = presets.size,
            selected = selected,
            onSelect = { selected = it },
            onActivate = { switch(accentHex(presets[selected].second)) },
        ) { index ->
            val (name, argb) = presets[index]
            ModalRow(
                label = name,
                value = if (index == selected) "in use" else "",
                swatch = argb?.let { Color(it.toULong().toLong()) } ?: themeDefault,
            )
        }
    }
}

/**
 * Los atajos del mando: que hace cada boton fuera de una lista.
 *
 * Los de un boton se cambian; los acordes se ensenan y se quedan como estan. Un acorde tiene
 * que ser una pareja que no se pulse por accidente mientras se juega, y dejar elegir cualquier
 * pareja es dejar elegir una que se dispare sola.
 *
 * Los fijos salen igualmente en la lista, en gris. No es relleno: un panel de atajos que solo
 * ensena la mitad de los atajos deja a alguien buscando en otro sitio los que faltan.
 */
@Composable
private fun ColumnScope.ShortcutsPane(vm: LibraryViewModel, onPick: (Shortcuts.Action) -> Unit) {
    var revision by remember { mutableStateOf(0) }
    val actions = Shortcuts.Action.entries
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, actions.lastIndex)
    val here = actions[sel]

    WindowFrame(
        title = "SHORTCUTS",
        subtitle = "${Shortcuts.editable.size} can change",
        description = here.about + if (here.chord != null) "  ·  Fixed: it is a chord." else "",
        hint = "A  change      B  back",
    ) {
        ModalRows(
            count = actions.size,
            selected = sel,
            onSelect = { selected = it },
            onActivate = { if (actions[sel].chord == null) onPick(actions[sel]); revision++ },
        ) { index ->
            val a = actions[index]
            ModalRow(
                label = a.label,
                value = a.chord ?: Shortcuts.button(vm.prefs, a).label,
                dimmed = a.chord != null,
                chevron = a.chord == null,
            )
        }
    }
}

/**
 * Los botones a los que se puede atar un atajo.
 *
 * A y B no estan en la lista y es a proposito: son entrar y volver en todas las listas de la
 * aplicacion, incluida esta. Atarles un atajo no seria configurar nada, seria romper la
 * navegacion desde dentro de la pantalla de configurarla.
 *
 * Un boton que ya tiene otro atajo se ensena diciendo cual. No se prohibe: hay quien quiere
 * las dos cosas en el mismo boton y sabe lo que hace. Pero se dice, porque enterarse de que
 * dos atajos comparten boton apretandolo es la peor forma de enterarse.
 */
@Composable
private fun ColumnScope.ShortcutPickPane(
    vm: LibraryViewModel,
    action: Shortcuts.Action,
    onDone: () -> Unit,
) {
    val buttons = Shortcuts.pickable
    val now = Shortcuts.button(vm.prefs, action)
    var selected by remember { mutableStateOf(buttons.indexOf(now).coerceAtLeast(0)) }
    val sel = selected.coerceIn(0, buttons.lastIndex)

    // Quien mas usa cada boton, para avisar de choques.
    val taken = remember(action) {
        Shortcuts.editable.filter { it != action }
            .associateBy({ Shortcuts.button(vm.prefs, it) }, { it.label })
    }

    WindowFrame(
        title = action.label.uppercase(),
        subtitle = "pick a button",
        description = action.about,
        hint = "A  set      B  back",
    ) {
        ModalRows(
            count = buttons.size,
            selected = sel,
            onSelect = { selected = it },
            onActivate = { Shortcuts.set(vm.prefs, action, buttons[sel]); onDone() },
        ) { index ->
            val b = buttons[index]
            ModalRow(
                label = b.label,
                value = when {
                    b == now -> "in use"
                    taken[b] != null -> "also ${taken[b]}"
                    else -> ""
                },
                dimmed = b != now && taken[b] != null,
            )
        }
    }
}

/**
 * Los niveles de cada sonido del tema puesto: A lo toca, izquierda y derecha lo mueven. Ver
 * SettingsModel.levelItems y SoundGains.
 */
@Composable
private fun ColumnScope.LevelsPane(vm: LibraryViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var revision by remember { mutableStateOf(0) }
    val items = remember(revision) { SettingsModel.levelItems(ctx, vm) }
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, items.lastIndex)

    WindowFrame(
        title = "SOUND LEVELS",
        subtitle = themeById(vm.prefs.themeId).name,
        description = items.getOrNull(sel)?.description.orEmpty(),
        hint = "A  play   ◀ ▶  level      B  back",
    ) {
        ModalRows(
            count = items.size,
            selected = sel,
            onSelect = { selected = it },
            onActivate = { (items.getOrNull(sel) as? SettingItem.Level)?.onPlay?.invoke() },
            onStep = { d ->
                (items.getOrNull(sel) as? SettingItem.Level)?.let { it.onStep(d); revision++; true } ?: false
            },
        ) { index ->
            val item = items[index]
            ModalRow(label = item.title.removePrefix("Level · "), value = item.value)
        }
    }
}

/**
 * Que es esto y de donde sale: la marca del tema, su version y donde guarda sus cosas.
 *
 * La marca es la misma de la entrada, en el emblema de cada tema —el libro del Parlour, la gema
 * del Gallery, la ficha del de fosforo— y con el nombre debajo. Las filas son para leer: no
 * cambian nada, y estan para que el foco tenga donde ponerse y B vuelva un nivel.
 */
@Composable
private fun ColumnScope.AboutPane(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val version = remember { Updates.current(ctx) }
    val latest = Updates.latest.value ?: remember { vm.prefs.updateLatest }
    // Lo que paso al pulsar: una comprobacion en marcha, su resultado, o donde quedo el diagnostico.
    var checking by remember { mutableStateOf(false) }
    var checkNote by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    val newer = latest != null && Updates.newer(latest, version)

    // Fila por fila: rotulo, valor, que hace A, y lo que se lee abajo.
    class Row(val label: String, val value: String, val chevron: Boolean, val about: String, val act: () -> Unit)
    val rows = listOf(
        Row(
            "Version",
            when {
                checking -> "$version · checking…"
                newer -> "$version · $latest available"
                checkNote != null -> "$version · $checkNote"
                else -> version
            },
            chevron = newer,
            about = if (newer) "A opens the release page on GitHub, to download the new version."
                    else "A asks GitHub now whether there is a newer Ludolog.",
        ) {
            if (newer) Updates.open(ctx)
            else if (!checking) {
                checking = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Updates.fetch(vm.prefs) }
                    checkNote = r.fold(
                        { if (Updates.newer(it, version)) null else "up to date" },
                        { "couldn't check: ${it.message ?: "no answer"}" },
                    )
                    checking = false
                }
            }
        },
        Row("Made by", "monkikolab", chevron = false,
            about = "A front end for Android handhelds that keeps a logbook of every session: how " +
                "long, which game, what it cost in battery and heat.") {},
        Row(
            "Export diagnostics",
            when {
                exporting -> "saving…"
                else -> exportNote.orEmpty()
            },
            chevron = false,
            about = "Saves a zip in Download with the version, this device, the settings (without keys " +
                "or passwords) and Ludolog's recent log, to attach to a bug report. Check it before " +
                "sharing: it can contain folder paths.",
        ) {
            if (!exporting) {
                exporting = true
                scope.launch {
                    exportNote = withContext(Dispatchers.IO) {
                        runCatching { Diagnostics.export(ctx, vm.prefs, vm.result) }
                            .fold({ "saved in $it" }, { "failed: ${it.message ?: it.javaClass.simpleName}" })
                    }
                    exporting = false
                }
            }
        },
    )
    var selected by remember { mutableStateOf(0) }

    WindowFrame(
        title = "ABOUT",
        subtitle = "Ludolog",
        description = rows.getOrNull(selected)?.about.orEmpty(),
        hint = "A  choose      B  back",
    ) {
        // En columna: el cuerpo de la ventana apila lo que recibe, y sin esto el banner caia
        // encima de las filas. El banner es el del README: las tres gemas y el nombre.
        Column {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.about_banner),
                contentDescription = "Ludolog",
                modifier = Modifier.fillMaxWidth().height(104.dp).padding(top = 2.dp, bottom = 8.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            ModalRows(
                count = rows.size,
                selected = selected,
                onSelect = { selected = it },
                onActivate = { rows.getOrNull(selected)?.act?.invoke() },
            ) { index ->
                ModalRow(label = rows[index].label, value = rows[index].value, chevron = rows[index].chevron)
            }
        }
    }
}

/**
 * Donde se buscan los juegos: solas, o las carpetas que se elijan.
 *
 * La primera fila es el modo automatico —ROMs, Roms, roms o Emulation/roms en cada unidad—. Debajo
 * van las carpetas que hay, las que encontro solo y las elegidas, cada una con si se usa; tocar una
 * pasa a elegirlas a mano partiendo de lo que se estaba usando, asi que nada desaparece por
 * sorpresa. Y al final, añadir otra con el selector de carpetas de Android, que es el que sabe
 * llegar a cualquier sitio de cualquier unidad.
 *
 * Cada cambio vuelve a leer la biblioteca: una carpeta elegida y no leida seria mentir en la lista.
 */
@Composable
private fun ColumnScope.RomFoldersPane(vm: LibraryViewModel) {
    val prefs = vm.prefs
    var revision by remember { mutableStateOf(0) }
    val chosen = remember(revision) { prefs.romDirs }
    val found = remember { Scanner.defaultRoots().map { it.path } }
    val folders = remember(revision) { (found + chosen.orEmpty()).distinct() }
    var selected by remember { mutableStateOf(0) }

    fun apply(next: Set<String>?) {
        prefs.romDirs = next
        revision++
        vm.scan()
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val path = uri?.let(::treePath) ?: return@rememberLauncherForActivityResult
        apply((prefs.romDirs ?: found.toSet()) + path)
    }

    val add = folders.size + 1
    WindowFrame(
        title = "ROM FOLDERS",
        subtitle = if (chosen == null) "automatic" else "${chosen.size} chosen",
        description = when {
            selected == 0 -> "Looks for ROMs, Roms, roms or Emulation/roms on internal storage and " +
                "on every card. Off, only the folders marked ON below are read."
            selected < add -> "ON reads this folder, OFF leaves it out. The library is read again."
            else -> "Opens Android's folder picker. The folder you pick is added and read."
        },
        hint = "A  change      B  back",
    ) {
        ModalRows(
            count = folders.size + 2,
            selected = selected,
            onSelect = { selected = it },
            onActivate = {
                when {
                    selected == 0 -> apply(if (chosen == null) found.toSet() else null)
                    selected < add -> {
                        val p = folders[selected - 1]
                        val now = chosen ?: found.toSet()
                        apply(if (p in now) now - p else now + p)
                    }
                    else -> runCatching { picker.launch(null) }
                }
            },
        ) { index ->
            when {
                index == 0 -> ModalRow(label = "Automatic", value = if (chosen == null) "ON" else "OFF")
                index < add -> {
                    val p = folders[index - 1]
                    val on = chosen?.contains(p) ?: (p in found)
                    ModalRow(label = p, value = if (on) "ON" else "OFF", dimmed = chosen == null)
                }
                else -> ModalRow(label = "Add a folder…", chevron = true)
            }
        }
    }
}

/**
 * La ruta de una carpeta elegida con el selector de Android.
 *
 * El selector no da rutas sino un identificador: «primary:ROMs» es la memoria interna y
 * «XXXX-XXXX:ROMs» una tarjeta por su numero de serie, que es tambien su nombre bajo /storage.
 * Con el permiso de todos los ficheros que ya tiene el programa, basta con la ruta.
 */
internal fun treePath(uri: android.net.Uri): String? {
    val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return null
    val volume = id.substringBefore(':')
    val rel = id.substringAfter(':', "").trim('/')
    val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
    return if (rel.isEmpty()) base else "$base/$rel"
}
