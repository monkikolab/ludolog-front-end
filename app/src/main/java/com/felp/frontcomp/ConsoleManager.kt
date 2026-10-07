package com.felp.frontcomp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * Console manager: see every console the app knows, edit one, create one, delete one.
 *
 * Edits are written as one TOML file per console in the user's systems folder, never into
 * the shipped catalog. An edit is therefore always undoable by deleting a file, and
 * updating the app never fights with what was changed on the device.
 */

/** What the editor is working on, before it is written out. */
internal data class ConsoleDraft(
    val id: String = "",
    val name: String = "",
    val label: String = "",
    val extensions: String = "",
    val raName: String = "",
    val raCore: String = "",
    val description: String = "",
    val image: String = "",
    val emulatorId: String = "",
    /** La imagen elegida: el nombre de otro render, "" para la suya, nulo si no se toco. */
    val art: String? = null,
) {
    /**
     * La consola que se guarda: lo editado, encima de lo que ya tenia.
     *
     * Encima de la anterior, y no una nueva con cuatro campos traidos a mano. La entrada que se
     * guarda sustituye entera a la del catalogo, asi que lo que el editor no ensena y no se
     * traia se perdia al guardar: la ficha tecnica, el giro, el fabricante, el año y la
     * coleccion de videos —editar Arcade en una consola de pruebas la dejo sin videos—. Asi, un campo nuevo
     * de SystemDef se conserva sin tener que acordarse de el aqui.
     */
    fun toSystemDef(previous: SystemDef?): SystemDef {
        val clean = id.trim().lowercase().filter { it.isLetterOrDigit() }
        return (previous ?: SystemDef(id = clean, name = clean, label = clean)).copy(
            id = clean,
            name = name.trim().ifEmpty { id },
            label = label.trim().ifEmpty { name.trim().ifEmpty { id }.uppercase() }.take(10),
            raName = raName.trim(),
            raCore = RetroCores.clean(raCore),
            extensions = CatalogWriter.parseExtensions(extensions),
            description = description.trim(),
            image = image.trim(),
        )
    }

    companion object {
        fun from(sys: SystemDef, emulatorId: String?) = ConsoleDraft(
            id = sys.id,
            name = sys.name,
            label = sys.label,
            extensions = sys.extensions.sorted().joinToString(", "),
            raName = sys.raName,
            raCore = sys.raCore,
            description = sys.description,
            image = sys.image,
            emulatorId = emulatorId.orEmpty(),
        )
    }
}

/** The list of every console in the catalog. */
@Composable
internal fun ColumnScope.ConsoleListPane(
    vm: LibraryViewModel,
    revision: Int,
    onEdit: (String?) -> Unit,
) {
    val cat = vm.catalog
    val systems = remember(revision, cat) { cat?.systems?.sortedBy { it.name }.orEmpty() }
    var selected by remember { mutableStateOf(0) }

    // "New console" es la primera fila para que crear una no exija recorrer ochenta y nueve.
    val rows = systems.size + 1
    val current = if (selected == 0) null else systems.getOrNull(selected - 1)

    WindowFrame(
        title = "CONSOLES",
        subtitle = "${systems.size} in catalog",
        description = when {
            selected == 0 -> "Create a console: its name, its file formats, which app opens it and its art."
            current == null -> ""
            CatalogWriter.isUserDefined(current.id) ->
                "Edited by you. ${current.extensions.size} formats. Deleting restores the original."
            else -> "From the shipped catalog. ${current.extensions.size} formats. Editing writes your own copy."
        },
        hint = "A  open      B  back",
    ) {
        ModalRows(
            count = rows,
            selected = selected,
            onSelect = { selected = it },
            onActivate = { onEdit(if (selected == 0) null else current?.id) },
        ) { index ->
            if (index == 0) {
                ModalRow(label = "+  New console", value = "", chevron = true)
            } else {
                val sys = systems[index - 1]
                val games = vm.result?.bySystem?.get(sys.id)?.size ?: 0
                ModalRow(
                    label = sys.name,
                    value = buildString {
                        if (games > 0) append("$games games   ")
                        append(sys.extensions.take(3).joinToString(" "))
                        if (CatalogWriter.isUserDefined(sys.id)) append("   ·  edited")
                    },
                    chevron = true,
                )
            }
        }
    }
}

/** Una fila del editor: lo que dice, su valor, su ayuda y que hace. */
private class EditRow(
    val label: String,
    val value: String,
    val help: String,
    val chevron: Boolean = false,
    val action: Boolean = false,
    val run: () -> Unit,
)

/**
 * El editor de una consola, con lo esencial: nombre, formatos, con que app se abre (y su nucleo,
 * si es RetroArch) y su imagen.
 *
 * Tenia ocho campos —id, etiqueta corta, nombre No-Intro, ruta de la imagen…— y para crear una
 * consola habia que entenderlos todos. Lo demas sale solo: el id del nombre, la etiqueta corta
 * tambien, y la descripcion se escribe desde el menu de la consola («Edit description»). Lo que
 * ya tenia una consola y aqui no se ve se conserva al guardar (ver ConsoleDraft.toSystemDef).
 */
@Composable
internal fun ColumnScope.ConsoleEditPane(
    vm: LibraryViewModel,
    systemId: String?,
    onDone: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val existing = systemId?.let { vm.catalog?.byId?.get(it) }
    var draft by remember(systemId) {
        mutableStateOf(
            existing?.let { s ->
                // El nucleo que se usa de verdad: el elegido en los menus, si lo hay.
                ConsoleDraft.from(s, vm.prefs.emulatorForSystem(s.id))
                    .copy(raCore = vm.prefs.coreForSystem(s.id) ?: s.raCore)
            } ?: ConsoleDraft()
        )
    }
    var selected by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<String?>(null) }
    // Lo subido: se copia a los temas al guardar, no antes, que el id sale del nombre.
    var upload by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }

    val apps = remember(vm.emulators) { AppsRepo.list(ctx, vm.emulators, vm.prefs) }
    fun isRetroArch(pkg: String) =
        vm.emulators?.defs?.any { it.isRetroArch && (pkg == it.id || pkg in it.packages()) } == true
    val retro = isRetroArch(draft.emulatorId) ||
        (draft.emulatorId.isEmpty() && existing != null &&
            vm.launchDef(ctx.packageManager, existing.id, null)?.isRetroArch == true)
    val appLabel = apps.firstOrNull { it.pkg == draft.emulatorId }?.label
        ?: vm.emulators?.byId?.get(draft.emulatorId)?.label
        ?: if (existing != null) "default for the console" else "not chosen"

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ext = artExtension(ctx, uri)
            if (ext == null) onToast("Choose a PNG, JPG or WebP picture, or an MP4 or WebM video")
            else { upload = uri to ext; draft = draft.copy(art = null) }
        }
        page = null
    }
    val chosenRender = systemId?.let { RenderChoices.of(it) }
    val artLabel = when {
        upload != null -> "new ${upload!!.second}"
        draft.art == "" -> "its own"
        draft.art != null -> renderName(vm, draft.art!!)
        chosenRender != null -> renderName(vm, chosenRender)
        existing != null -> "its own"
        else -> "none"
    }

    when (page) {
        "app" -> {
            ConsoleAppPane(apps) { pkg ->
                draft = draft.copy(emulatorId = pkg)
                page = if (isRetroArch(pkg)) "core" else null
            }
            return
        }
        "core" -> {
            RetroCoreList(
                title = "RETROARCH CORE",
                subtitle = draft.name.ifEmpty { "new console" },
                systemId = systemId ?: consoleId(draft.name, vm),
                catalogCore = existing?.raCore.orEmpty(),
                defaultLabel = if (existing != null) "Catalog default" else "None",
                defaultCore = existing?.raCore.orEmpty(),
                current = draft.raCore.takeIf { it.isNotEmpty() && it != existing?.raCore },
                onPick = { draft = draft.copy(raCore = it ?: existing?.raCore.orEmpty()); page = null },
                onOther = { editing = "core"; page = null },
            )
            return
        }
        "art" -> {
            ConsoleArtPane(
                vm, systemId, current = draft.art, uploaded = upload != null,
                onUpload = { picker.launch(arrayOf("image/*", "video/*")) },
                onPick = { draft = draft.copy(art = it); upload = null; page = null },
            )
            return
        }
    }

    editing?.let { what ->
        // La misma casilla que renombrar un juego: acepta con el boton del teclado.
        TextPage(
            title = when (what) { "name" -> "NAME"; "formats" -> "FILE FORMATS"; else -> "RETROARCH CORE" },
            initial = when (what) { "name" -> draft.name; "formats" -> draft.extensions; else -> draft.raCore },
            help = when (what) {
                "name" -> "How the console is shown in the library."
                "formats" -> "The file extensions of its games, separated by commas, without the dot: sfc, smc, zip."
                else -> CORE_TYPING_HELP
            },
            onCancel = { editing = null },
            onDone = { v ->
                draft = when (what) {
                    "name" -> draft.copy(name = v.trim())
                    "formats" -> draft.copy(extensions = v)
                    else -> draft.copy(raCore = RetroCores.clean(v))
                }
                editing = null
            },
        )
        return
    }

    val canDelete = systemId != null && CatalogWriter.isUserDefined(systemId)
    val rows = buildList {
        add(EditRow("Name", draft.name, "How the console is shown in the library.") { editing = "name" })
        add(EditRow("File formats", draft.extensions,
            "The extensions of its games. Files with others are not listed.") { editing = "formats" })
        add(EditRow("Opens with", appLabel, "Which app runs its games.", chevron = true) { page = "app" })
        if (retro) add(EditRow("RetroArch core",
            draft.raCore.takeIf { it.isNotEmpty() }?.let(RetroCores::name) ?: "none",
            "The core RetroArch opens these games with. Without one it cannot open them.",
            chevron = true) { page = "core" })
        add(EditRow("Art", artLabel,
            "The picture or spin shown for it: one the themes already have, or your own, which is " +
                "added to every installed theme.", chevron = true) { page = "art" })
        add(EditRow("Save", "", if (systemId == null) "Creates the console." else "Saves your changes.",
            action = true) {
            saveConsole(ctx, vm, systemId, existing, draft, upload, onToast, onDone)
        })
        if (canDelete) add(EditRow("Delete my version", "",
            "Deletes your file. The console goes back to the shipped catalog, or disappears.",
            action = true) {
            CatalogWriter.delete(systemId!!)
            vm.reloadCatalog(ctx)
            onToast("Deleted")
            onDone()
        })
    }
    val sel = selected.coerceIn(0, rows.lastIndex)

    WindowFrame(
        title = if (systemId == null) "NEW CONSOLE" else draft.name.uppercase().ifEmpty { "CONSOLE" },
        subtitle = if (systemId == null) "not saved yet" else systemId,
        description = rows[sel].help,
        hint = "A  edit      B  back",
    ) {
        ModalRows(
            count = rows.size,
            selected = sel,
            onSelect = { selected = it },
            onActivate = { rows[sel].run() },
        ) { index ->
            val r = rows[index]
            ModalRow(label = r.label, value = if (r.action) "" else r.value.ifEmpty { "—" }, chevron = r.chevron)
        }
    }
}

/** El id de una consola nueva, de su nombre: «Neo Geo CD» es neogeocd; si ya existe, neogeocd2. */
private fun consoleId(name: String, vm: LibraryViewModel): String {
    val base = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "console" }
    val taken = vm.catalog?.byId?.keys.orEmpty()
    if (base !in taken) return base
    return (2..99).map { "$base$it" }.first { it !in taken }
}

private fun saveConsole(
    ctx: android.content.Context,
    vm: LibraryViewModel,
    systemId: String?,
    existing: SystemDef?,
    draft: ConsoleDraft,
    upload: Pair<android.net.Uri, String>?,
    onToast: (String) -> Unit,
    onDone: () -> Unit,
) {
    if (draft.name.isBlank()) { onToast("A name is required"); return }
    val id = systemId ?: consoleId(draft.name, vm)
    // En una consola que ya existe, el nucleo elegido aqui va a los ajustes, como el que se elige
    // desde los menus, y su fichero se queda con el del catalogo; en una nueva, a su fichero.
    val core = draft.raCore
    val def = draft.copy(id = id, raCore = if (existing == null) core else existing.raCore).toSystemDef(existing)
    if (def.extensions.isEmpty()) { onToast("At least one file format is required"); return }
    CatalogWriter.save(def).fold(
        onSuccess = {
            if (draft.emulatorId.isNotEmpty()) vm.prefs.setEmulatorForSystem(id, draft.emulatorId)
            if (existing != null) {
                vm.prefs.setCoreForSystem(id, core.takeIf { c -> c.isNotEmpty() && c != existing.raCore })
            }
            when {
                upload != null -> {
                    val n = ThemeFiles.addSystemArt(id, upload.second) { f ->
                        ctx.contentResolver.openInputStream(upload.first)?.use { input ->
                            f.outputStream().use { input.copyTo(it) }
                        } ?: error("unreadable")
                    }
                    vm.prefs.setSystemRender(id, null)
                    RenderChoices.set(id, null)
                    if (n == 0) onToast("Saved, but the art could not be copied")
                }
                draft.art == "" -> { vm.prefs.setSystemRender(id, null); RenderChoices.set(id, null) }
                draft.art != null -> { vm.prefs.setSystemRender(id, draft.art); RenderChoices.set(id, draft.art) }
            }
            vm.reloadCatalog(ctx)
            onToast("Saved ${it.name}")
            onDone()
        },
        onFailure = { onToast("Could not save: ${it.message}") },
    )
}

/** La extension con que se guarda lo subido, por su tipo; nula si no es una que se lea. */
private fun artExtension(ctx: android.content.Context, uri: android.net.Uri): String? {
    val byType = when (ctx.contentResolver.getType(uri)) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        else -> null
    }
    return byType ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it in setOf("png", "jpg", "jpeg", "webp", "mp4", "webm", "mkv") }
}

/**
 * La imagen de la consola: subir una, la suya, o la de cualquier otra que traigan los temas,
 * con la vista previa al lado, como en «Console render».
 */
@Composable
private fun ColumnScope.ConsoleArtPane(
    vm: LibraryViewModel,
    systemId: String?,
    current: String?,
    uploaded: Boolean,
    onUpload: () -> Unit,
    onPick: (String) -> Unit,
) {
    val look = LocalTheme.current
    val renders = remember(look.id) { SystemArt.renders().sortedBy { renderName(vm, it).lowercase() } }
    // Filas: subir, la suya (si la consola ya existe) y los renders de los temas.
    val own = systemId != null
    val head = if (own) 2 else 1
    val count = head + renders.size
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, count - 1)
    val shown: String? = when {
        sel == 0 -> null
        own && sel == 1 -> systemId
        else -> renders[sel - head]
    }
    WindowFrame(
        title = "ART",
        subtitle = "${renders.size} in the themes",
        description = when {
            sel == 0 -> "Pick a picture (PNG, JPG, WebP) or a video (MP4, WebM) from the device. " +
                "It is copied into every installed theme when you save."
            own && sel == 1 -> "Its own: the one each theme has for it, if any."
            else -> "The one the themes have for ${renderName(vm, renders[sel - head])}."
        },
        hint = "A  choose      B  back",
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.44f).fillMaxHeight()) {
                ModalRows(
                    count = count,
                    selected = sel,
                    onSelect = { selected = it },
                    onActivate = {
                        when {
                            sel == 0 -> onUpload()
                            own && sel == 1 -> onPick("")
                            else -> onPick(renders[sel - head])
                        }
                    },
                ) { i ->
                    when {
                        i == 0 -> ModalRow(label = "Upload picture or video…", value = if (uploaded) "chosen" else "", chevron = true)
                        own && i == 1 -> ModalRow(label = "Its own", value = if (current == "") "in use" else "")
                        else -> renders[i - head].let { r ->
                            ModalRow(label = renderName(vm, r), value = if (r == current) "in use" else "")
                        }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(0.56f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (shown != null) {
                    RenderPreview(shown, own = shown == systemId, sys = systemId?.let { vm.catalog?.byId?.get(it) })
                }
            }
        }
    }
}

/** Con que app se abren sus juegos: las instaladas, los emuladores primero. */
@Composable
private fun ColumnScope.ConsoleAppPane(apps: List<AppEntry>, onPick: (String) -> Unit) {
    val sorted = remember(apps) { apps.sortedByDescending { it.isEmulator } }
    var selected by remember { mutableStateOf(0) }
    WindowFrame(
        title = "OPENS WITH",
        subtitle = "${apps.size} installed",
        description = sorted.getOrNull(selected)?.let {
            it.pkg + if ("retroarch" in it.pkg) "\nNext you choose its core." else ""
        }.orEmpty(),
        hint = "A  choose      B  back",
    ) {
        ModalRows(
            count = sorted.size,
            selected = selected,
            onSelect = { selected = it },
            onActivate = { sorted.getOrNull(selected)?.let { onPick(it.pkg) } },
        ) { index ->
            ModalRow(
                label = sorted[index].label,
                value = if (sorted[index].isEmulator) "emulator" else "",
            )
        }
    }
}
