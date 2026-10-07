package com.felp.frontcomp

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Los nucleos de RetroArch que tiene sentido ofrecer para una consola.
 *
 * Los que RetroArch tiene descargados no se pueden ver: viven en su carpeta privada
 * (`/data/user/0/<paquete>/cores`). Lo que si se ve es su carpeta de configuracion,
 * `RetroArch/config/`, donde crea una subcarpeta con el nombre de cada nucleo la primera vez
 * que lo usa —«Snes9x», «PCSX-ReARMed»—. Con eso se marcan como «used» los que ya se sabe que
 * estan, y los demas se ofrecen igual: puede que esten y no se hayan usado nunca.
 */
internal object RetroCoreChoices {

    /** Los habituales de cada consola, el mejor para un aparato de mano primero. */
    private val BY_SYSTEM = mapOf(
        "nes" to listOf("fceumm", "nestopia", "mesen"),
        "snes" to listOf("snes9x", "snes9x2010", "snes9x2005", "bsnes", "mesen-s"),
        "satellaview" to listOf("snes9x", "bsnes"),
        "n64" to listOf("mupen64plus_next_gles3", "mupen64plus_next", "parallel_n64"),
        "gb" to listOf("gambatte", "sameboy", "gearboy", "mgba"),
        "gbc" to listOf("gambatte", "sameboy", "gearboy", "mgba"),
        "gba" to listOf("mgba", "gpsp", "vba_next", "vbam"),
        "nds" to listOf("melonds", "melondsds", "desmume"),
        "virtualboy" to listOf("mednafen_vb"),
        "megadrive" to listOf("genesis_plus_gx", "picodrive", "blastem"),
        "mastersystem" to listOf("genesis_plus_gx", "picodrive", "gearsystem", "smsplus"),
        "gamegear" to listOf("genesis_plus_gx", "gearsystem", "smsplus"),
        "sg1000" to listOf("gearsystem", "genesis_plus_gx"),
        "segacd" to listOf("genesis_plus_gx", "picodrive"),
        "sega32x" to listOf("picodrive"),
        "saturn" to listOf("mednafen_saturn", "yabasanshiro", "yabause", "kronos"),
        "dreamcast" to listOf("flycast"),
        "atomiswave" to listOf("flycast"),
        "psx" to listOf("pcsx_rearmed", "swanstation", "mednafen_psx", "mednafen_psx_hw"),
        "psp" to listOf("ppsspp"),
        "pcengine" to listOf("mednafen_pce_fast", "mednafen_pce", "mednafen_supergrafx"),
        "pcenginecd" to listOf("mednafen_pce_fast", "mednafen_pce"),
        "neogeo" to listOf("fbneo", "mame2003_plus", "mame"),
        "neogeocd" to listOf("neocd", "fbneo"),
        "arcade" to listOf("fbneo", "mame2003_plus", "mame2010", "mame"),
        "ngp" to listOf("mednafen_ngp"),
        "ngpc" to listOf("mednafen_ngp"),
        "wonderswan" to listOf("mednafen_wswan"),
        "wonderswancolor" to listOf("mednafen_wswan"),
        "atari2600" to listOf("stella"),
        "lynx" to listOf("handy"),
        "msx" to listOf("fmsx"),
        "dos" to listOf("dosbox_pure"),
        "3ds" to listOf("citra"),
    )

    /**
     * Las opciones para esa consola: la del catalogo, los habituales y, en una consola sin
     * habituales (una creada a mano), los que RetroArch ya uso. Con «used» los que tienen
     * carpeta en `RetroArch/config`.
     */
    fun of(systemId: String, catalogCore: String): List<Pair<String, Boolean>> {
        val used = usedCores()
        val usual = BY_SYSTEM[systemId]
        val list = (listOf(catalogCore) + (usual ?: used.sorted())).filter { it.isNotEmpty() }.distinct()
        return list.map { it to (it in used) }
    }

    /** Los nucleos con carpeta de configuracion, por su fichero: «PCSX-ReARMed» es pcsx_rearmed. */
    private fun usedCores(): Set<String> {
        val dirs = File("/storage/emulated/0/RetroArch/config").listFiles()
            ?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val wanted = dirs.map(::key).toSet()
        return RetroCores.all().filter { key(RetroCores.name(it)) in wanted }.toSet()
    }
}

/**
 * La lista de nucleos, sin ventana: dentro de los ajustes va como una pagina mas (gestor de
 * consolas) y en los menus, dentro de [RetroCorePicker].
 *
 * @param current el elegido a mano, o nulo si se usa el de serie.
 * @param defaultCore el de serie, el que se usa con `current` nulo.
 * @param onPick el elegido, o nulo para volver al de serie.
 * @param onOther escribir uno que no este en la lista.
 */
@Composable
internal fun ColumnScope.RetroCoreList(
    title: String,
    subtitle: String,
    systemId: String,
    catalogCore: String,
    defaultLabel: String,
    defaultCore: String,
    current: String?,
    onPick: (String?) -> Unit,
    onOther: () -> Unit,
) {
    val options = remember(systemId, catalogCore) { RetroCoreChoices.of(systemId, catalogCore) }
    val rows = 1 + options.size + 1
    var selected by remember {
        mutableStateOf(
            if (current == null) 0
            else options.indexOfFirst { it.first == current }.let { if (it >= 0) it + 1 else rows - 1 }
        )
    }
    WindowFrame(
        title = title,
        subtitle = subtitle,
        description = when {
            selected == 0 -> if (defaultCore.isEmpty()) "No default core: choose one."
                else "${RetroCores.name(defaultCore)} ($defaultCore)."
            selected <= options.size -> options[selected - 1].let { (core, used) ->
                core + if (used) ", used before in RetroArch." else
                    ". Not used yet: if RetroArch does not have it, download it there first " +
                        "(Main Menu → Online Updater → Core Downloader)."
            }
            else -> "Type the name of a core that is not in the list."
        },
        hint = "A  choose      B  back",
    ) {
        ModalRows(
            count = rows,
            selected = selected,
            onSelect = { selected = it },
            onActivate = {
                when {
                    selected == 0 -> onPick(null)
                    selected <= options.size -> onPick(options[selected - 1].first)
                    else -> onOther()
                }
            },
        ) { index ->
            when {
                index == 0 -> ModalRow(
                    label = defaultLabel,
                    value = (if (defaultCore.isEmpty()) "none" else RetroCores.name(defaultCore)) +
                        if (current == null) "  ·  in use" else "",
                )
                index <= options.size -> {
                    val (core, used) = options[index - 1]
                    ModalRow(
                        label = RetroCores.name(core),
                        value = listOfNotNull("used".takeIf { used }, "in use".takeIf { core == current })
                            .joinToString("  ·  "),
                    )
                }
                else -> ModalRow(
                    label = "Other core…",
                    value = current?.takeIf { c -> options.none { it.first == c } }
                        ?.let { RetroCores.name(it) + "  ·  in use" }.orEmpty(),
                    chevron = true,
                )
            }
        }
    }
}

/** La ayuda de la casilla para escribir un nucleo a mano. */
internal const val CORE_TYPING_HELP =
    "As its file is named: snes9x for snes9x_libretro_android.so. It must be downloaded in " +
        "RetroArch (Main Menu → Online Updater → Core Downloader)."

/**
 * El nucleo con que RetroArch abre una consola, o un juego si se da [game]. Se abre al elegir
 * RetroArch en los selectores de emulador, que es donde se busca: antes solo se cambiaba
 * escribiendo el nombre del fichero en el gestor de consolas.
 *
 * Arriba, volver al de serie: en una consola, el del catalogo; en un juego, el de su consola.
 */
@Composable
internal fun RetroCorePicker(vm: LibraryViewModel, systemId: String, game: Game?, onClose: () -> Unit) {
    val prefs = vm.prefs
    val catalogCore = vm.launcher?.catalogCore(systemId).orEmpty()
    val current = if (game != null) prefs.coreForGame(game.path) else prefs.coreForSystem(systemId)
    var typing by remember { mutableStateOf(false) }
    fun pick(core: String?) {
        if (game != null) prefs.setCoreForGame(game.path, core) else prefs.setCoreForSystem(systemId, core)
        onClose()
    }
    if (typing) {
        TextWindow(
            title = "RETROARCH CORE",
            initial = current.orEmpty(),
            help = CORE_TYPING_HELP,
            onCancel = { typing = false },
            onDone = { typed -> pick(RetroCores.clean(typed).ifEmpty { null }) },
        )
        return
    }
    ModalWindow(onDismiss = onClose, widthFraction = 0.50f, heightFraction = 0.74f) {
        RetroCoreList(
            title = "RETROARCH CORE",
            subtitle = if (game != null) vm.displayTitle(game) else vm.displayName(vm.catalog?.byId?.get(systemId), systemId),
            systemId = systemId,
            catalogCore = catalogCore,
            defaultLabel = if (game != null) "Same as the console" else "Catalog default",
            defaultCore = if (game != null) prefs.coreForSystem(systemId) ?: catalogCore else catalogCore,
            current = current,
            onPick = ::pick,
            onOther = { typing = true },
        )
    }
}
