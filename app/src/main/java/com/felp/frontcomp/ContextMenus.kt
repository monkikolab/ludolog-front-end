package com.felp.frontcomp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * Context menus: hold on a console or a game to act on that one thing.
 *
 * They reuse the modal window rather than being popups of their own. On a pad there is no
 * cursor to anchor a popup to, so "a window about the thing you had selected" is both
 * easier to build and easier to understand than something that floats next to it.
 */

/** One row of a context menu: what it says, what it explains, and what it does. */
internal class Act(
    val label: String,
    val blurb: String,
    val value: String = "",
    val chevron: Boolean = false,
    /** Izquierda o derecha sobre la fila: cambia su valor sin abrir nada. Ver ModalRows. */
    val step: ((Int) -> Unit)? = null,
    /** Lo que se dibuja a la derecha, en lugar del valor escrito. */
    val trailing: (@Composable () -> Unit)? = null,
    val run: () -> Unit,
)

/* ------------------------------------------------------------------------- consola */

@Composable
internal fun SystemContextMenu(
    vm: LibraryViewModel,
    systemId: String,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
) {
    val sys = vm.catalog?.byId?.get(systemId)
    val games = vm.gamesOf(systemId)
    var revision by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var describing by remember { mutableStateOf(false) }
    var pickingEmulator by remember { mutableStateOf(false) }
    var pickingRender by remember { mutableStateOf(false) }
    // Aqui arriba y no junto a las filas: debajo hay returns tempranos, y un remember que
    // quede detras de uno se olvida al volver, asi que el cursor saltaba a la primera fila
    // cada vez que se cerraba el selector de render o el de emulador.
    var selected by remember { mutableStateOf(0) }

    val custom = remember(revision, systemId) { vm.prefs.systemName(systemId) }
    val ownText = remember(revision, systemId) { vm.prefs.description("sys", systemId) }
    val hidden = remember(revision, systemId) { vm.prefs.isSystemHidden(systemId) }
    val missing = remember(revision, games, vm.art) {
        vm.art?.let { a -> games.count { !a.has(it) } } ?: games.size
    }

    if (renaming) {
        TextWindow(
            title = "RENAME CONSOLE",
            initial = custom ?: sys?.name.orEmpty(),
            help = "Only the display name changes. Nothing on disk is renamed.",
            onCancel = { renaming = false },
            onDone = { name ->
                val own = name.takeIf { it != sys?.name }
                vm.prefs.setSystemName(systemId, own)
                LinkEdits.system(vm.getApplication(), systemId, own, "name")
                renaming = false
                revision++
            },
        )
        return
    }
    if (describing) {
        TextWindow(
            title = "DESCRIBE CONSOLE",
            initial = ownText ?: sys?.description.orEmpty(),
            help = "Typed under the console in the room. Empty goes back to the catalog's own text.",
            multiline = true,
            onCancel = { describing = false },
            onDone = { text ->
                val own = text.takeIf { it != sys?.description }
                vm.prefs.setDescription("sys", systemId, own)
                LinkEdits.system(vm.getApplication(), systemId, own)
                describing = false
                revision++
            },
        )
        return
    }
    if (pickingEmulator) {
        SystemEmulatorPicker(vm, systemId) { pickingEmulator = false; revision++ }
        return
    }
    if (pickingRender) {
        RenderPicker(vm, systemId) { pickingRender = false; revision++ }
        return
    }

    val render = RenderChoices.of(systemId)
    val actions = buildList {
        add(Act("Rename", "Give this console your own name. The catalog keeps its own.",
            value = custom.orEmpty()) { renaming = true })
        add(Act("Edit description", "Your own text about this console, instead of the catalog's.",
            value = if (ownText != null) "edited" else "") { describing = true })
        add(Act("Console render", "Which console is drawn for it: its own, or any other the theme has.",
            chevron = true, value = render?.let { renderName(vm, it) }.orEmpty()) { pickingRender = true })
        add(Act("Choose emulator", "Which app opens the games of this console.", chevron = true,
            value = currentLauncherName(vm, systemId, revision)) { pickingEmulator = true })
        add(Act("Fetch missing box art",
            if (missing == 0) "All games here already have box art."
            else "$missing games here have no box art yet.",
            value = if (missing > 0) "$missing" else "") {
            if (missing == 0) onToast("Nothing missing") else vm.scrape(systemId)
            onClose()
        })
        add(Act(if (hidden) "Show in library" else "Hide from library",
            if (hidden) "It is hidden; show it again."
            else "Keep it out of the console list without touching the files.",
            value = if (hidden) "hidden" else "") {
            vm.prefs.setSystemHidden(systemId, !hidden); revision++
        })
        if (custom != null) add(Act("Reset name", "Go back to the name from the catalog.") {
            vm.prefs.setSystemName(systemId, null); LinkEdits.system(vm.getApplication(), systemId, null, "name"); revision++
        })
        if (ownText != null) add(Act("Reset description", "Go back to the catalog's text.") {
            vm.prefs.setDescription("sys", systemId, null); LinkEdits.system(vm.getApplication(), systemId, null); revision++
        })
    }
    val sel = selected.coerceIn(0, actions.lastIndex)

    val title = (custom ?: sys?.name ?: systemId).uppercase()
    val subtitle = gameCount(games.size)
    val wide = headerFraction(title, subtitle, "B  close", min = 0.46f)
    ModalWindow(onDismiss = onClose, widthFraction = wide, heightFraction = 0.70f) {
        WindowFrame(
            title = title,
            subtitle = subtitle,
            description = actions[sel].blurb,
            hint = "B  close",
        ) {
            ActRows(actions, sel) { selected = it }
        }
    }
}

/** Como se llama un render en la lista: el nombre de su consola, o el del fichero si no es una. */
internal fun renderName(vm: LibraryViewModel, stem: String): String =
    vm.catalog?.byId?.get(stem)?.let { vm.displayName(it, stem) } ?: stem

/**
 * Que render se dibuja para una consola: el suyo, o el de cualquier otra que el tema traiga.
 *
 * Para las que no tienen uno propio —los juegos de PC, una consola añadida a mano— y para quien
 * prefiere otra version de la misma maquina. La lista a la izquierda y, a la derecha, el marcado
 * tal como lo pinta el tema, girando si el tema gira: elegir un render por su nombre, sin verlo,
 * seria elegir a ciegas.
 */
@Composable
private fun RenderPicker(vm: LibraryViewModel, systemId: String, onClose: () -> Unit) {
    val sys = vm.catalog?.byId?.get(systemId)
    val look = LocalTheme.current
    val rows: List<String?> = remember(look.id) {
        listOf<String?>(null) + SystemArt.renders().sortedBy { renderName(vm, it).lowercase() }
    }
    val current = RenderChoices.of(systemId)
    var selected by remember { mutableStateOf(rows.indexOf(current).coerceAtLeast(0)) }
    val sel = selected.coerceIn(0, rows.lastIndex)
    val shown = rows[sel]

    ModalWindow(onDismiss = onClose, widthFraction = 0.80f, heightFraction = 0.84f) {
        WindowFrame(
            title = "CONSOLE RENDER",
            subtitle = vm.displayName(sys, systemId),
            description = if (shown == null) "Its own: the one this theme has for it, if any."
                          else "The one this theme has for ${renderName(vm, shown)}. Each theme shows its own version.",
            hint = "A  choose      B  back",
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.44f).fillMaxHeight()) {
                    ModalRows(
                        count = rows.size,
                        selected = sel,
                        onSelect = { selected = it },
                        onActivate = {
                            val pick = rows[sel]
                            vm.prefs.setSystemRender(systemId, pick)
                            RenderChoices.set(systemId, pick)
                            onClose()
                        },
                    ) { i ->
                        val r = rows[i]
                        ModalRow(label = r?.let { renderName(vm, it) } ?: "Its own", value = if (r == current) "in use" else "")
                    }
                }
                Spacer(Modifier.width(18.dp))
                Box(Modifier.weight(0.56f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    RenderPreview(shown ?: systemId, own = shown == null, sys = sys)
                }
            }
        }
    }
}

/**
 * El genero de un juego, elegido a mano: «Automatic» (el del catalogo) y la lista de nombres
 * propios, del mas concreto al mas general. Se guarda en su ficha y viaja a las otras consolas
 * con el cuaderno (ver LibraryViewModel.setGenre).
 */
@Composable
private fun GenrePicker(vm: LibraryViewModel, game: Game, onClose: () -> Unit) {
    val dossier = remember(game.path, vm.dossierRevision) { Dossiers.get(game) }
    val own = dossier?.ownGenres?.let(Genres::canon)?.firstOrNull()
    val fromCatalog = dossier?.catalogGenres?.let(Genres::canon)?.firstOrNull()
    val rows: List<String?> = remember { listOf<String?>(null) + Genres.all }
    var selected by remember { mutableStateOf(rows.indexOf(own).coerceAtLeast(0)) }
    val sel = selected.coerceIn(0, rows.lastIndex)

    ModalWindow(onDismiss = onClose, widthFraction = 0.56f, heightFraction = 0.84f) {
        WindowFrame(
            title = "GENRE",
            subtitle = vm.displayTitle(game),
            description = if (rows[sel] == null) {
                if (fromCatalog != null) "The catalog's: $fromCatalog." else "The catalog does not know this one."
            } else "Your choice. It stays even if the catalog changes.",
            hint = "A  choose      B  back",
        ) {
            ModalRows(
                count = rows.size,
                selected = sel,
                onSelect = { selected = it },
                onActivate = {
                    vm.setGenre(game, rows[sel])
                    onClose()
                },
            ) { i ->
                val g = rows[i]
                ModalRow(
                    label = g ?: "Automatic",
                    value = when {
                        g == null -> (fromCatalog ?: "unknown") + if (own == null) "  ·  in use" else ""
                        g == own -> "in use"
                        else -> ""
                    },
                )
            }
        }
    }
}

/** Un render como lo pinta el tema: girando si el tema gira y hay giro; si no, su foto. */
@Composable
internal fun RenderPreview(stem: String, own: Boolean, sys: SystemDef?) {
    val look = LocalTheme.current
    val quiet = !look.spins
    // Sin la eleccion guardada (choice = null): aqui se ensena cada uno tal cual, tambien el
    // propio, que es justo lo que la eleccion tapa.
    val video = remember(stem, own, quiet, look.id) {
        when {
            quiet -> null
            own -> SystemArt.video(stem, sys?.video.orEmpty(), choice = null)
            else -> SystemArt.video(stem, choice = null)
        }
    }
    val still = remember(stem, own, look.id) {
        if (own) SystemArt.find(stem, sys?.image.orEmpty(), choice = null) else SystemArt.find(stem, choice = null)
    }
    if (video != null) ConsoleTurntable(video, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
    else Preview(still, "", ContentScale.Fit)
}

/* --------------------------------------------------------------------------- juego */

@Composable
internal fun GameContextMenu(
    vm: LibraryViewModel,
    game: Game,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var describing by remember { mutableStateOf(false) }
    var pickingEmulator by remember { mutableStateOf(false) }
    var showingInfo by remember { mutableStateOf(false) }
    var pickingGenre by remember { mutableStateOf(false) }
    // Arriba, por lo mismo que en el de consola: detras de los returns se olvidaria.
    var selected by remember { mutableStateOf(0) }
    // «Delete game» espera su segunda A; moverse a otra fila la olvida (como Clear preferences).
    var confirmingDelete by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { confirmingDelete = false }

    val custom = remember(revision, game.path) { vm.prefs.gameTitle(game) }
    val ownText = remember(revision, game.path) { vm.prefs.description("game", game.path) }
    val favourite = remember(revision, game.path) { vm.prefs.isFavorite(game) }
    val hasArt = remember(revision, vm.art) { vm.art?.has(game) == true }
    val hasVideo = remember(revision, vm.art) { vm.art?.video(game) != null }
    // Lo que se busca ahora para ESTE juego, si algo, y si hay otra busqueda en marcha.
    val here = vm.looking?.takeIf { it.path == game.path }
    val elsewhere = vm.busy && here == null
    val dots = rememberWorkingDots(here != null)
    val plays = remember(revision, game.path) { vm.prefs.playCount(game) }
    val ownEmulator = remember(revision, game.path) { vm.prefs.emulatorForGame(game) }
    val identified = remember(game.path, vm.dossierRevision) { Dossiers.get(game)?.name != null }
    val launchName = remember(revision, game.path, vm.launcher) {
        vm.launchName(ctx.packageManager, game.systemId, vm.prefs.resolveEmulator(game))
    }

    if (renaming) {
        TextWindow(
            title = "RENAME GAME",
            initial = custom ?: vm.displayTitle(game),
            help = "Only the display name changes. Nothing on disk is renamed. From the fourth " +
                "letter, names from the catalog show up below: pick one to use it.",
            suggest = { q -> GameDb.suggest(game.systemId, q) },
            onCancel = { renaming = false },
            onDone = { name ->
                val before = custom ?: game.title
                val own = name.takeIf { it != game.title }
                vm.prefs.setGameTitle(game, own)
                LinkEdits.game(vm.getApplication(), game, "name", own)
                renaming = false
                revision++
                // Con el nombre nuevo se busca lo que le falte (ver afterRename). El menu se queda:
                // sus filas dicen que se esta buscando, y lo que salga se abre encima.
                if (name.trim() != before) vm.afterRename(game)
            },
        )
        return
    }
    if (describing) {
        // Lo que se ensena para editar cuando no hay texto propio: los datos del fichero, que
        // se escriben solos y cambian —«Played 3 times»—. Aceptarlo tal cual no es escribir una
        // descripcion: se guardaba como propia, se quedaba congelada y la fila decia «edited».
        // Igual que en el menu de la consola, que ya lo comparaba con el texto del catalogo.
        val facts = if (ownText == null) vm.gameDetails(game).text else null
        TextWindow(
            title = "DESCRIBE GAME",
            initial = ownText ?: facts.orEmpty(),
            help = "Typed under the game in the room. Empty goes back to the file's own facts.",
            multiline = true,
            onCancel = { describing = false },
            onDone = { text ->
                val own = text.takeUnless { it.trim() == facts?.trim() }
                vm.prefs.setDescription("game", game.path, own)
                LinkEdits.game(vm.getApplication(), game, "desc", own)
                describing = false
                revision++
            },
        )
        return
    }
    if (showingInfo) {
        GameInfoWindow(vm, game) { showingInfo = false }
        return
    }
    if (pickingEmulator) {
        GameEmulatorPicker(
            vm = vm, game = game,
            onClose = { pickingEmulator = false; revision++ },
        )
        return
    }
    if (pickingGenre) {
        GenrePicker(vm, game) { pickingGenre = false; revision++ }
        return
    }
    // El genero: el puesto a mano o el del catalogo. Ver GenrePicker.
    val dossier = remember(game.path, vm.dossierRevision) { Dossiers.get(game) }
    val ownGenre = dossier?.ownGenres?.let(Genres::canon)?.firstOrNull()
    val catalogGenre = dossier?.catalogGenres?.let(Genres::canon)?.firstOrNull()

    val actions = buildList {
        add(Act("Play", vm.discFor(game).fileName, value = if (plays > 0) "played $plays" else "") {
            vm.play(ctx, game)?.let(onToast); onClose()
        })
        // Un juego de varios discos: UNA fila, con un disco dibujado por cada uno. El que se va a
        // usar, encendido; izquierda y derecha, o tocar uno, lo cambian, y A lo abre. Queda como
        // el de «Play» para la proxima, que es como se sigue una partida que va por el segundo.
        // Una fila y no una por disco: el menu ya es largo y la pantalla, baja.
        if (game.discs.size > 1) {
            val current = vm.prefs.discIndex(game).coerceIn(0, game.discs.lastIndex)
            fun pick(i: Int) { vm.prefs.setDiscIndex(game, i); revision++ }
            add(Act(
                "Disc",
                "Disc ${current + 1} of ${game.discs.size}: ${game.discs[current].substringAfterLast('/')}. " +
                    "Left and right choose another; A starts it.",
                step = { d -> pick((current + d).mod(game.discs.size)) },
                trailing = { DiscIcons(game.discs.size, current, ::pick) },
            ) {
                vm.play(ctx, game)?.let(onToast); onClose()
            })
        }
        // Un juego de Android se abre solo: no hay emulador que elegir ni carátula que
        // buscar, así que esas dos filas no salen.
        // Con que se abre de verdad, y con que nucleo si es RetroArch, aunque no se haya
        // elegido nada para este juego: vacia no decia nada, y es lo primero que se mira cuando
        // un juego no arranca. La descripcion dice de donde sale.
        if (game.appPackage == null) add(Act("Emulator for this game",
            if (ownEmulator == null) "Now it follows the console's choice. Pick one to use it for this game only."
            else "Chosen for this game only. The rest of the console keeps its own.",
            value = launchName.orEmpty(),
            chevron = true) { pickingEmulator = true })
        // Lo que dice su ficha: que juego es de verdad, genero, año, estudio y sinopsis.
        if (game.appPackage == null) add(Act("Game info",
            "What this game is and what is known about it: its exact name, genre, year, " +
                "developer and story, with where each thing comes from.",
            value = if (identified) "" else "unknown",
            chevron = true) { showingInfo = true })
        // Opcional a proposito: casi todos lo traen del catalogo, y a uno sin genero no le pasa
        // nada. Esto es para jugar, no para rellenar fichas.
        add(Act("Genre",
            when {
                ownGenre != null && catalogGenre != null && catalogGenre != ownGenre ->
                    "Chosen by you. The catalog says $catalogGenre."
                ownGenre != null -> "Chosen by you."
                catalogGenre != null -> "What kind of game it is, from the catalog. Choose another if it is wrong."
                else -> "Not known yet. Choose one if you like: it is not needed."
            },
            value = ownGenre ?: catalogGenre ?: "unknown",
            chevron = true) { pickingGenre = true })
        add(Act("Rename", "Give this game your own title. The file on disk is not renamed.",
            value = custom.orEmpty()) { renaming = true })
        add(Act("Edit description", "Your own text about this game, typed under it in the room.",
            value = if (ownText != null) "edited" else "") { describing = true })
        // Mientras se busca, el menu se queda abierto y la fila lo dice; lo que se encuentre se
        // abre encima, y al cerrarlo se vuelve aqui. Cerrarlo al pulsar dejaba solo un contador
        // pequeño arriba, y parecia que no habia pasado nada.
        fun status(kind: ArtAsked, idle: String): String = when {
            here?.kind == kind -> (if (here.saving) "saving" else "searching") + dots
            elsewhere -> "busy"
            else -> idle
        }
        fun blurb(kind: ArtAsked, idle: String, what: String): String = when {
            here?.kind == kind && here.saving -> "Saving the $what you chose."
            here?.kind == kind -> "Looking for its $what online. What turns up opens right here."
            elsewhere -> "Another search is running. Try again when it ends."
            else -> idle
        }
        // Tambien con caratula: puede ser la equivocada, y aqui es donde se cambia. La nueva
        // sustituye a la que tenia; ver ArtScraper.keep.
        if (game.appPackage == null) add(Act("Fetch box art",
            blurb(ArtAsked.COVER,
                if (hasArt) "Look for other box art and choose. The new one replaces it."
                else "Look for its box art online. When more than one could fit, you choose.",
                "box art"),
            value = status(ArtAsked.COVER, if (hasArt) "yes" else "missing")) {
            if (!vm.busy) vm.scrapeOne(game)
        })
        // El video de partida, igual que la caratula. Se ofrece aunque el tema no los ponga,
        // porque alguien lo ha pedido; la frase lo avisa.
        if (game.appPackage == null) add(Act("Fetch video",
            blurb(ArtAsked.VIDEO,
                (if (hasVideo) "Look for another gameplay video and choose. The new one replaces it."
                 else "Look for a gameplay video online. When more than one could fit, you choose.") +
                    (if (vm.prefs.playVideo) "" else " Videos are off in this theme."),
                "video"),
            value = status(ArtAsked.VIDEO, if (hasVideo) "yes" else "missing")) {
            if (!vm.busy) vm.fetchVideo(game)
        })
        // Terminado, a mano: desde fuera del emulador no hay forma de saberlo. Cuenta para los
        // logros y para la mision «Finish Line».
        val done = vm.prefs.completedAt(game) != null
        add(Act("Completed",
            if (done) "Marked as beaten. Press to unmark it."
            else "Beat it? Mark it here: it counts for achievements and missions.",
            value = if (done) "yes" else "no") {
            vm.prefs.setCompleted(game, !done); revision++
        })
        add(Act(if (favourite) "Remove from favourites" else "Add to favourites",
            if (favourite) "It is a favourite." else "Mark it as a favourite.",
            value = if (favourite) "yes" else "") {
            vm.prefs.setFavorite(game, !favourite); revision++
        })
        if (custom != null) add(Act("Reset name", "Go back to the title taken from the file name.") {
            vm.prefs.setGameTitle(game, null); LinkEdits.game(vm.getApplication(), game, "name", null); revision++
        })
        if (ownText != null) add(Act("Reset description", "Go back to the file's own facts.") {
            vm.prefs.setDescription("game", game.path, null); LinkEdits.game(vm.getApplication(), game, "desc", null); revision++
        })
        // Borrar el juego de la tarjeta: la ultima fila, lejos de «Play», y con una segunda A.
        // Se borra lo que es el juego —con sus pistas o sus discos, ver RomFiles— y nada mas: las
        // partidas guardadas viven en el emulador, y la caratula y el cuaderno se quedan.
        if (game.appPackage == null) {
            val files = remember(game.path) { RomFiles.of(game) }
            val size = remember(files) { megabytes(RomFiles.size(files)) }
            val what = if (files.size == 1) "its file" else "its ${files.size} files"
            add(Act("Delete game",
                if (confirmingDelete) "Press A again to delete $what ($size) from the card. This cannot be undone."
                else "Deletes $what from the card, $size. Saves in the emulator, box art and the " +
                    "logbook stay. Asks again before deleting.",
                value = if (confirmingDelete) "press A again" else size) {
                if (!confirmingDelete) { confirmingDelete = true; return@Act }
                confirmingDelete = false
                val failed = RomFiles.delete(files)
                onToast(
                    if (failed.isEmpty()) "Deleted ${vm.displayTitle(game)}."
                    else "Could not delete ${failed.joinToString { it.name }}. The card may be read-only."
                )
                onClose()
                // Fuera de la lista: un repaso, como al cambiar las carpetas de ROMs.
                vm.scan()
            })
        }
    }
    val sel = selected.coerceIn(0, actions.lastIndex)

    val title = (custom ?: game.title).uppercase()
    val subtitle = vm.catalog?.byId?.get(game.systemId)?.name ?: game.systemId
    // Tan ancha como pida el nombre, para que quepa entero en una linea.
    val wide = headerFraction(title, subtitle, "B  close", min = 0.50f)
    ModalWindow(onDismiss = onClose, widthFraction = wide, heightFraction = 0.76f) {
        WindowFrame(
            title = title,
            subtitle = subtitle,
            description = actions[sel].blurb,
            hint = "B  close",
        ) {
            ActRows(actions, sel) { selected = it }
        }
    }
}

/**
 * Con qué se abre esta consola hoy, dicho en corto para la fila del menú: «Snes9x (RetroArch)».
 *
 * También sin elección guardada, que es lo normal: entonces va la primera de la tabla, y la fila
 * vacía no decía con qué ni con qué núcleo, justo lo que se quiere saber cuando un juego no abre.
 */
@Composable
private fun currentLauncherName(vm: LibraryViewModel, systemId: String, revision: Int): String {
    val pm = LocalContext.current.packageManager
    return remember(revision, systemId, vm.launcher) {
        vm.launchName(pm, systemId, vm.prefs.emulatorForSystem(systemId)).orEmpty()
    }
}

/**
 * Lo que se dice de RetroArch en las listas de emuladores: el nucleo, que es lo que cambia de
 * una consola a otra. `short` para la fila, «Snes9x»; si no, la frase, con el nombre que lleva
 * en el gestor de consolas, que es donde se cambia.
 *
 * Corta a proposito: debajo del paquete quedan dos renglones, y con la letra del Parlour la
 * version con el fichero entero se cortaba justo en donde decia donde cambiarlo.
 */
internal fun retroCore(vm: LibraryViewModel, systemId: String, short: Boolean, game: Game? = null): String {
    val core = vm.launcher?.coreFor(systemId, game).orEmpty()
    return when {
        short -> if (core.isEmpty()) "no core" else RetroCores.name(core)
        core.isEmpty() -> "No RetroArch core set, so it cannot open these games. Press A to choose one."
        else -> "RetroArch with the ${RetroCores.name(core)} core ($core). Press A to change the core."
    }
}

/**
 * Which app opens the games of one console.
 *
 * Two lists in one: the emulators the table knows and that are installed, and after them
 * every other installed app. The second half is the point — a new emulator always exists
 * before its entry in the table does, and until now there was no way to point a console at
 * one. What the table does not describe is handed the game the generic way, as a content
 * URI on a VIEW intent, which is what nearly all of them accept.
 *
 * The old "Choose emulator" row did not do this at all: it closed the menu and opened the
 * settings window at its root, which is why neither the pad nor the finger seemed to work.
 */
@Composable
private fun SystemEmulatorPicker(vm: LibraryViewModel, systemId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val sys = vm.catalog?.byId?.get(systemId)
    var revision by remember { mutableStateOf(0) }
    val known = remember(systemId, vm.emulators) {
        vm.emulators?.forSystem(systemId)
            ?.filter { vm.launcher?.installedPackage(ctx.packageManager, it) != null }
            .orEmpty()
    }
    // Sin las que la tabla ya ofrece arriba. RetroArch salia dos veces con el mismo nombre —la
    // de la tabla, que lleva el nucleo, y la app suelta— y la suelta recibia el juego sin saber
    // con que nucleo abrirlo: con los arcade elegidos asi para la consola no arrancaba ninguno.
    val apps = remember(vm.emulators, known) {
        // Todos sus paquetes, no solo el instalado primero: con el fork tambien instalado, salia
        // como app suelta y elegido asi se lanzaba sin nucleo.
        val covered = known.flatMap { it.packages() }.toSet()
        AppsRepo.list(ctx, vm.emulators, vm.prefs)
            .filterNot { it.slot == AppSlot.GAME || it.pkg in covered }
    }
    val chosen = remember(revision, systemId) { vm.prefs.emulatorForSystem(systemId) }
    // Elegir RetroArch abre su nucleo: es lo que de verdad cambia de una consola a otra.
    var pickingCore by remember { mutableStateOf(false) }
    // La de la tabla que esta en uso: la elegida, tambien si se guardo por su paquete, y sin
    // eleccion la primera de la tabla, que es la que abre los juegos. Ver LibraryViewModel.launchDef.
    val used = remember(revision, systemId, vm.launcher) {
        vm.launchDef(ctx.packageManager, systemId, chosen)
    }
    fun inUse(def: EmulatorDef) = def.id == used?.id
    // La posición de partida se calcula al componer, no en un efecto: las filas se quedan
    // el foco en el primer fotograma, y para entonces un efecto todavía no ha corrido.
    var selected by remember(known, apps) {
        val i = known.indexOfFirst { inUse(it) }
        val j = apps.indexOfFirst { it.pkg == chosen }
        mutableStateOf(if (i >= 0) i else if (j >= 0) known.size + j else 0)
    }
    val sel = selected.coerceIn(0, (known.size + apps.size - 1).coerceAtLeast(0))
    if (pickingCore) {
        RetroCorePicker(vm, systemId, game = null) { pickingCore = false; revision++ }
        return
    }

    ModalWindow(onDismiss = onClose, widthFraction = 0.54f, heightFraction = 0.74f) {
        WindowFrame(
            title = vm.displayName(sys, systemId).uppercase(),
            subtitle = "${known.size} known  ·  ${apps.size} apps",
            description = when {
                sel < known.size -> known.getOrNull(sel)?.let {
                    "${it.pkg}\n" +
                        if (it.isRetroArch) retroCore(vm, systemId, short = false)
                        else "Hands the game over as: ${it.hand.name}"
                }.orEmpty()
                else -> apps.getOrNull(sel - known.size)?.let {
                    "${it.pkg}\nNot in the table: the game goes as a content URI on a VIEW intent."
                }.orEmpty()
            },
            hint = "A  choose      B  back",
        ) {
            ModalRows(
                count = known.size + apps.size,
                selected = sel,
                onSelect = { selected = it },
                onActivate = {
                    val id = if (sel < known.size) known[sel].id else apps[sel - known.size].pkg
                    vm.prefs.setEmulatorForSystem(systemId, id)
                    revision++
                    if (sel < known.size && known[sel].isRetroArch) pickingCore = true
                },
            ) { index ->
                if (index < known.size) {
                    val def = known[index]
                    val state = if (inUse(def)) "in use" else "from the table"
                    ModalRow(
                        label = def.label,
                        value = if (def.isRetroArch) "${retroCore(vm, systemId, short = true)} · $state" else state,
                    )
                } else {
                    val app = apps[index - known.size]
                    ModalRow(
                        label = app.label,
                        value = if (app.pkg == chosen) "in use" else "app",
                    )
                }
            }
        }
    }
}

/** The rows of a context menu, from its actions. */
@Composable
internal fun ActRows(actions: List<Act>, selected: Int, onSelect: (Int) -> Unit) {
    ModalRows(
        count = actions.size,
        selected = selected,
        onSelect = onSelect,
        onActivate = { actions.getOrNull(selected)?.run?.invoke() },
        onStep = { d -> actions.getOrNull(selected)?.step?.let { it(d); true } ?: false },
    ) { index ->
        val a = actions[index]
        if (a.trailing == null) ModalRow(label = a.label, value = a.value, chevron = a.chevron)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ModalRow(label = a.label) }
            a.trailing.invoke()
        }
    }
}

@Composable
private fun GameEmulatorPicker(vm: LibraryViewModel, game: Game, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val options = remember(game.path) {
        vm.launcher?.available(ctx.packageManager, game).orEmpty()
    }
    var revision by remember { mutableStateOf(0) }
    // El que se usa de verdad, tambien sin nada elegido para este juego: el de la consola, o el
    // primero de la tabla. Marcando solo la eleccion del juego la lista no tenia ningun «in use»,
    // y parecia que el juego no tenia emulador: con Terranigma se eligio RetroArch a mano, cuando
    // ya iba a abrirse con el.
    val used = remember(revision, game.path, vm.launcher) {
        vm.launchDef(ctx.packageManager, game.systemId, vm.prefs.resolveEmulator(game))
    }
    var selected by remember { mutableStateOf(options.indexOfFirst { it.id == used?.id }.coerceAtLeast(0)) }
    var pickingCore by remember { mutableStateOf(false) }
    if (pickingCore) {
        RetroCorePicker(vm, game.systemId, game) { pickingCore = false; revision++ }
        return
    }

    ModalWindow(onDismiss = onClose, widthFraction = 0.50f, heightFraction = 0.66f) {
        WindowFrame(
            title = "EMULATOR",
            subtitle = "${options.size} installed",
            description = options.getOrNull(selected)?.let { def ->
                if (def.isRetroArch) "${def.pkg}\n${retroCore(vm, game.systemId, short = false, game = game)}"
                else "${def.pkg}\nApplies to this game only."
            }.orEmpty(),
            hint = "A  choose      B  back",
        ) {
            ModalRows(
                count = options.size,
                selected = selected,
                onSelect = { selected = it },
                onActivate = {
                    options.getOrNull(selected)?.let {
                        vm.prefs.setEmulatorForGame(game, it.id)
                        revision++
                        if (it.isRetroArch) pickingCore = true
                    }
                },
            ) { index ->
                val def = options[index]
                val state = if (def.id == used?.id) "in use" else ""
                val core = if (def.isRetroArch) retroCore(vm, game.systemId, short = true, game = game) else ""
                ModalRow(label = def.label, value = listOf(core, state).filter { it.isNotEmpty() }.joinToString(" · "))
            }
        }
    }
}

/* ------------------------------------------------------------------------ texto */

/**
 * Text entry: a name, or a description.
 *
 * The on-screen keyboard is the only realistic way to type on these devices; the physical
 * controls have no letters. So the field takes focus immediately and the keyboard comes
 * up with it.
 *
 * @param multiline a few lines, for a description; the window grows to fit them.
 */
@Composable
internal fun TextWindow(
    title: String,
    initial: String,
    help: String,
    onCancel: () -> Unit,
    onDone: (String) -> Unit,
    multiline: Boolean = false,
    /**
     * Si es una contraseña o un secreto: se escribe con puntos y el teclado no lo aprende. Sin
     * esto, abrir la casilla lo enseñaba en claro a quien tuviera el aparato en la mano, y un
     * teclado que aprende lo que se escribe podia guardarlo como una palabra mas.
     */
    secret: Boolean = false,
    /** Nombres que proponer mientras se escribe, desde la cuarta letra. Ver TextPage. */
    suggest: ((String) -> List<String>)? = null,
) {
    // 0.62 y no 0.42: con 0.42 en una pantalla de 456dp la ventana media 191dp, y entre el
    // titulo, el campo y la descripcion fija de abajo, a los botones les quedaban cuatro
    // pixeles. Se veian como dos rayitas debajo del campo. No era del tema: ya pasaba.
    ModalWindow(
        onDismiss = onCancel,
        widthFraction = if (multiline) 0.72f else 0.62f,
        heightFraction = if (multiline) 0.86f else 0.78f,
    ) {
        TextPage(title, initial, help, onCancel, onDone, multiline, secret, suggest)
    }
}

/**
 * Lo de dentro de [TextWindow], para poder ponerlo tambien como pagina de otra ventana.
 *
 * Dentro de los ajustes una ventana de texto salia anidada, una ventana dentro de otra, y a esa
 * escala no cabia: el titulo cortado y los botones aplastados hasta no enseñar el rotulo. Ahi
 * se usa como una pagina mas de los ajustes, con todo su tamaño, igual que el tutorial de IGDB.
 */
@Composable
internal fun ColumnScope.TextPage(
    title: String,
    initial: String,
    help: String,
    onCancel: () -> Unit,
    onDone: (String) -> Unit,
    multiline: Boolean = false,
    secret: Boolean = false,
    suggest: ((String) -> List<String>)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    // Lo que propone el catalogo para lo escrito: desde la cuarta letra, fuera del hilo de la
    // pantalla y con una pausa, para no buscar en cada tecla. El primer paquete que se abre tarda
    // un poco; los siguientes ya estan leidos.
    var offers by remember { mutableStateOf(emptyList<String>()) }
    if (suggest != null) LaunchedEffect(text) {
        val q = text.trim()
        if (q.length < 4) { offers = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        offers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { suggest(q) }.getOrDefault(emptyList())
        }.filter { !it.equals(q, ignoreCase = true) }
    }
    WindowFrame(
        title = title,
        subtitle = "",
        description = help,
        hint = "B  cancel",
    ) {
        Column {
            Spacer(Modifier.height(10.dp))
            NativeField(
                initial = initial,
                onValue = { text = it },
                onImeDone = { onDone(it) },
                multiline = multiline,
                secret = secret,
                modifier = Modifier.fillMaxWidth()
                    .then(if (multiline) Modifier.heightIn(min = 72.dp) else Modifier)
                    .border(1.dp, MenuLine, RoundedCornerShape(2.dp))
                    .background(MenuGround)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row {
                PadButton("SAVE", { onDone(text) }, primary = true)
                Spacer(Modifier.width(12.dp))
                PadButton("CANCEL", onCancel)
            }
            // Las propuestas, debajo de los botones: elegir una la guarda tal cual.
            if (offers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("FROM THE CATALOG", color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                for (o in offers) {
                    PadButton(o, { onDone(o) })
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/**
 * El campo de texto, en Android de toda la vida y no en Compose.
 *
 * En horizontal, el teclado de este aparato abre su propio editor a pantalla completa —el
 * «extract mode»— que tapa la ventana entera: se escribía a ciegas, el Enter no confirmaba y
 * al botón de guardar no se llegaba. Eso se apaga con una bandera del EditorInfo que Compose
 * no expone por ninguna parte, así que el campo es un EditText de verdad con
 * IME_FLAG_NO_EXTRACT_UI puesto. Lo demás —color, fuente, tamaño— se iguala a mano para que
 * no se note de dónde viene.
 */
@Composable
private fun NativeField(
    initial: String,
    onValue: (String) -> Unit,
    /** El tic del teclado confirma: el botón de guardar queda debajo del teclado. */
    onImeDone: (String) -> Unit,
    multiline: Boolean,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = MenuInk.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.EditText(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextColor(ink)
                textSize = 16f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, 0, 0)
                isSingleLine = !multiline
                if (multiline) setLines(3)
                if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = (if (secret) android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0) or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setText(initial)
                setSelection(text.length)
                // El Enter del mando llega como tecla, no como acción del teclado, así que
                // se recogen las dos: con el botón de guardar tapado por el teclado, esta es
                // la única forma de confirmar sin soltar el aparato.
                setOnKeyListener { v, code, ev ->
                    if (code == android.view.KeyEvent.KEYCODE_ENTER &&
                        ev.action == android.view.KeyEvent.ACTION_UP
                    ) {
                        onImeDone((v as android.widget.EditText).text.toString()); true
                    } else false
                }
                setOnEditorActionListener { v, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        onImeDone(v.text.toString()); true
                    } else false
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        onValue(s?.toString().orEmpty())
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                isFocusableInTouchMode = true
                post {
                    requestFocus()
                    ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        },
    )
}

/**
 * Tres puntos que van y vienen mientras algo trabaja, para que una fila que espera se vea
 * viva. Rellenos con espacios duros hasta tres, para que el texto no baile al crecer.
 */
@Composable
internal fun rememberWorkingDots(active: Boolean): String {
    var n by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(active) {
        n = 0
        while (active) {
            kotlinx.coroutines.delay(350)
            n = (n + 1) % 4
        }
    }
    return ".".repeat(n) + " ".repeat(3 - n)
}

/**
 * Un disco por cada uno del juego: el que se va a usar en tinta, los demas apagados. Se tocan.
 * Dibujados, como el trofeo y el ojo, para que salgan igual en los tres temas.
 */
@Composable
private fun DiscIcons(count: Int, current: Int, onPick: (Int) -> Unit) {
    val on = MenuInk
    val off = MenuFaint.copy(alpha = .6f)
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            androidx.compose.foundation.Canvas(
                Modifier.size(18.dp).pointerInput(i) { detectTapGestures { onPick(i) } },
            ) {
                val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2
                val color = if (i == current) on else off
                drawCircle(color, r, c)
                // El agujero y el anillo de alrededor: lo que hace que un circulo se lea como disco.
                drawCircle(androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f), r * .34f, c)
                drawCircle(color, r * .16f, c)
            }
        }
    }
}

/** Lo que ocupa, en corto: «640 MB», «1.2 GB», «85 KB». */
internal fun megabytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${(bytes shr 10).coerceAtLeast(1)} KB"
}
