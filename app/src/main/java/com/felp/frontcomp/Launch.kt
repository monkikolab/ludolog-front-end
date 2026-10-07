package com.felp.frontcomp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * How a game gets handed to the emulator.
 *
 * FILE_INT es para los ficheros que no son el juego sino su numero en una tienda: un `.steam`
 * lleva dentro el id de Steam y nada mas, y el lanzador lo quiere como entero en `extraKey`.
 *
 * STORE_ID es lo mismo pero como texto, y con la clave segun la tienda (`idExtras`): GameHub
 * quiere `steamAppId` para un `.steam` y `localGameId` para un `.gamehub`.
 *
 * TITLE_ARGS es Vita3K, que no abre ficheros: arranca un juego ya instalado por su numero de
 * titulo (PCSx00000), pasado como `-r <titulo>` en el array de textos `extraKey`. El numero se
 * saca del nombre del fichero o de su primera linea, que es lo que lleva un `.psvita` de ES-DE.
 * Con el fichero por URI, Vita3K abria su propia pantalla y ningun juego.
 */
enum class Hand { DATA_URI, EXTRA_PATH, EXTRA_URI, FILE_INT, STORE_ID, TITLE_ARGS }

/**
 * One emulator, as an intent template.
 *
 * Everything an emulator needs is data — package, activity, action, where the ROM goes —
 * so supporting a new one, or a fork of an existing one, is an entry in a file rather
 * than a code change.
 */
data class EmulatorDef(
    val id: String,
    val label: String,
    val pkg: String,
    val activity: String = "",
    val action: String = "",
    val hand: Hand = Hand.DATA_URI,
    val extraKey: String = "",
    /** Con FILE_INT: donde va la tienda, con el nombre que le da el lanzador (STORE_SOURCES). */
    val sourceExtra: String = "",
    /** Con STORE_ID: la clave del id segun la extension del fichero. En el .toml, «ext:clave». */
    val idExtras: Map<String, String> = emptyMap(),
    val mimeType: String = "",
    val altPkgs: List<String> = emptyList(),
    val boolExtras: Map<String, Boolean> = emptyMap(),
    val kind: String = "standalone",
    // RetroArch only: the core is a path to a .so inside the app's own data directory.
    val romExtra: String = "",
    val coreExtra: String = "",
    val configExtra: String = "",
    val corePath: String = "",
    val configPath: String = "",
) {
    val isRetroArch: Boolean get() = kind == "retroarch"

    /** Every package that can serve this entry, the main one first. */
    fun packages(): List<String> = listOf(pkg) + altPkgs
}

class Emulators(
    val defs: List<EmulatorDef>,
    private val bySystem: Map<String, List<String>>,
) {
    val byId: Map<String, EmulatorDef> = defs.associateBy { it.id }

    /** Candidates for a system, in the order they should be preferred. */
    fun forSystem(systemId: String): List<EmulatorDef> =
        bySystem[systemId].orEmpty().mapNotNull(byId::get)

    companion object {
        fun parse(toml: String): Emulators {
            val doc = Toml.parse(toml)
            val defs = doc.all("emulator").mapNotNull { t ->
                val id = t.string("id")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                EmulatorDef(
                    id = id,
                    label = t.string("label") ?: id,
                    pkg = t.string("pkg").orEmpty(),
                    activity = t.string("activity").orEmpty(),
                    action = t.string("action").orEmpty(),
                    hand = runCatching { Hand.valueOf(t.string("hand") ?: "DATA_URI") }
                        .getOrDefault(Hand.DATA_URI),
                    extraKey = t.string("extraKey").orEmpty(),
                    sourceExtra = t.string("sourceExtra").orEmpty(),
                    idExtras = t.strings("idExtras").mapNotNull { s ->
                        val cut = s.indexOf(':')
                        if (cut <= 0) null
                        else s.substring(0, cut).trim().lowercase() to s.substring(cut + 1).trim()
                    }.toMap(),
                    mimeType = t.string("mimeType").orEmpty(),
                    altPkgs = t.strings("altPkgs"),
                    // Los nombres de los que van a verdadero. Estaba documentado y nadie lo
                    // leia: GameHub necesita `autoStartGame` o abre la ficha y no el juego.
                    boolExtras = t.strings("boolExtras").associateWith { true },
                    kind = t.string("kind") ?: "standalone",
                    romExtra = t.string("romExtra").orEmpty(),
                    coreExtra = t.string("coreExtra").orEmpty(),
                    configExtra = t.string("configExtra").orEmpty(),
                    corePath = t.string("corePath").orEmpty(),
                    configPath = t.string("configPath").orEmpty(),
                )
            }
            val map = doc.first("bySystem")?.let { t ->
                t.values.keys.associateWith { key -> t.strings(key) }
            }.orEmpty()
            return Emulators(defs, map)
        }
    }
}

/**
 * Turns a game plus an emulator into something Android will actually start.
 */
class Launcher(
    private val emulators: Emulators,
    private val catalog: Catalog,
    /**
     * El nucleo elegido a mano para un juego (con su ruta) o para la consola entera (ruta nula),
     * o nulo para el del catalogo. Ver Prefs.coreForGame y RetroCorePicker.
     */
    private val chosenCore: (systemId: String, gamePath: String?) -> String? = { _, _ -> null },
) {
    /**
     * The installed package for this entry, or null.
     *
     * Forks matter here: MMJR and Ishiiruka are Dolphin as far as the intent is concerned
     * but ship under their own package names, so one entry has to be able to resolve to
     * whichever of them the user actually has.
     */
    fun installedPackage(pm: PackageManager, def: EmulatorDef): String? =
        def.packages().firstOrNull { p ->
            runCatching { pm.getPackageInfo(p, 0) }.isSuccess
        }

    /** Emulators for this game that are actually on the device. */
    fun available(pm: PackageManager, game: Game): List<EmulatorDef> = availableFor(pm, game.systemId)

    /** Lo mismo para una consola entera, sin juego delante: para decir con que se abre. */
    fun availableFor(pm: PackageManager, systemId: String): List<EmulatorDef> {
        val listed = emulators.forSystem(systemId)
        // RetroArch abre cualquier consola que diga con que nucleo, este o no en la tabla de
        // emuladores: una consola creada en el gestor no sale en ella, y elegida RetroArch se
        // lanzaba como app suelta, sin nucleo, y no abria nada.
        val retro = if (listed.none { it.isRetroArch } && coreFor(systemId).isNotEmpty())
            emulators.defs.filter { it.isRetroArch } else emptyList()
        return (listed + retro).filter { installedPackage(pm, it) != null }
    }

    /**
     * El nucleo con que RetroArch abre los juegos de esta consola, como se llama su fichero:
     * «snes9x» por `snes9x_libretro_android.so`. Vacio si la consola no dice ninguno.
     */
    fun coreFor(systemId: String, game: Game? = null): String =
        game?.let { chosenCore(systemId, it.path) }
            ?: chosenCore(systemId, null)
            ?: catalog.byId[systemId]?.raCore.orEmpty()

    /** El del catalogo, sin lo elegido: para decir «el de serie» en el selector. */
    fun catalogCore(systemId: String): String = catalog.byId[systemId]?.raCore.orEmpty()

    /**
     * El nombre de una entrada para enseñarlo, con el nucleo si es RetroArch: «Snes9x
     * (RetroArch)». RetroArch a secas no decia nada, porque el mismo programa abre cada consola
     * con un nucleo distinto, y es el nucleo el que se cambia cuando un juego no va.
     *
     * El nucleo delante: donde no cabe entero, la fila corta por el final, y con el Parlour en
     * «Emulator for this game» quedaba «RetroArch · FinalBurn …», justo sin lo que se buscaba.
     */
    fun labelFor(def: EmulatorDef, systemId: String): String {
        if (!def.isRetroArch) return def.label
        val core = coreFor(systemId)
        return if (core.isEmpty()) "${def.label}, no core" else "${RetroCores.name(core)} (${def.label})"
    }

    /** Si lo que se lanza es RetroArch, por el paquete al que va: ver [Bounce]. */
    fun isRetroArch(intent: Intent): Boolean {
        val pkg = intent.component?.packageName ?: intent.`package` ?: return false
        return emulators.defs.any { it.isRetroArch && pkg in it.packages() }
    }

    /**
     * A ROM handed to an app the table knows nothing about.
     *
     * The console's quick menu can point a system at any installed app, not only at the
     * emulators in the table, because a new emulator always appears before its entry does.
     * With nothing to go on, the best guess is the one nearly every Android emulator
     * accepts: VIEW on a content:// URI it is granted access to.
     */
    fun buildForPackage(ctx: Context, game: Game, pkg: String): Intent? {
        val file = File(game.path)
        if (!file.isFile) return null
        val uri = shareUri(ctx, file)
        ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent(Intent.ACTION_VIEW).apply {
            `package` = pkg
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * `pkg`, si se eligio uno concreto de los de la entrada: con RetroArch y su fork instalados
     * a la vez, el elegido puede ser el segundo, y se le entrega a ese y no al primero.
     */
    fun build(ctx: Context, game: Game, def: EmulatorDef, pkg: String? = null): Intent? {
        val pkg = pkg?.takeIf { p ->
            p in def.packages() && runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess
        } ?: installedPackage(ctx.packageManager, def) ?: return null
        val file = File(game.path)
        if (!file.isFile) return null

        val intent = Intent().apply {
            if (def.activity.isNotEmpty()) component = ComponentName(pkg, def.activity)
            else `package` = pkg
            if (def.action.isNotEmpty()) action = def.action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (def.isRetroArch) {
            val core = coreFor(game.systemId, game)
            if (core.isEmpty()) return null
            // Una tarea nueva cada vez, como lo lanzan ES-DE y Daijisho. Con la de antes viva,
            // RetroArch 1.22.2 recibia el juego en onNewIntent y, si era otro, se cerraba sin
            // abrirlo (su #18587, arreglado solo en las nightly): habia que pulsar dos veces.
            // Cerrando la tarea anterior, el juego llega siempre a una pantalla recien creada.
            // Y el `sh /sdcard/switch` de su onStart, que a veces dejaba la pantalla en negro,
            // corre en una actividad nueva y no al volver a una que ya tenia un juego cargado.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra(def.romExtra, file.absolutePath)
            intent.putExtra(def.coreExtra, def.corePath.replace("{pkg}", pkg).replace("{core}", core))
            if (def.configExtra.isNotEmpty()) {
                intent.putExtra(def.configExtra, def.configPath.replace("{pkg}", pkg))
            }
            return intent
        }

        when (def.hand) {
            Hand.EXTRA_PATH -> intent.putExtra(def.extraKey, file.absolutePath)
            Hand.DATA_URI -> {
                val uri = shareUri(ctx, file)
                if (def.mimeType.isNotEmpty()) intent.setDataAndType(uri, def.mimeType)
                else intent.data = uri
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Hand.EXTRA_URI -> {
                val uri = shareUri(ctx, file)
                intent.putExtra(def.extraKey, uri.toString())
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Hand.FILE_INT -> {
                // Sin un numero dentro no hay a que jugar: mejor no lanzar nada que abrir el
                // lanzador en su biblioteca, que es lo que pasaba entregandole el fichero.
                val id = storeId(file) ?: return null
                intent.putExtra(def.extraKey, id)
                if (def.sourceExtra.isNotEmpty()) {
                    // La tienda con el nombre que usa el lanzador. Iba la extension en
                    // mayusculas, y un `.pcgame` llegaba como «PCGAME», que no es ninguna: el
                    // lanzador lo tomaba por Steam y buscaba el juego 3 de Steam. Un fichero
                    // de una tienda que no conoce no es suyo: nulo, y va al siguiente.
                    val source = STORE_SOURCES[file.extension.lowercase(java.util.Locale.ROOT)]
                        ?: return null
                    intent.putExtra(def.sourceExtra, source)
                }
            }
            Hand.STORE_ID -> {
                // Una tienda sin clave en la entrada no es de este lanzador: al siguiente.
                val key = def.idExtras[file.extension.lowercase(java.util.Locale.ROOT)] ?: return null
                val id = storeNumber(file) ?: return null
                intent.putExtra(key, id.toString())
            }
            Hand.TITLE_ARGS -> {
                val title = vitaTitle(file) ?: return null
                intent.putExtra(def.extraKey, arrayOf("-r", title))
            }
        }
        def.boolExtras.forEach { (k, v) -> intent.putExtra(k, v) }
        return intent
    }

    /**
     * A content:// URI the other app is allowed to read.
     *
     * Handing over a file:// URI throws on anything modern, and these ROMs live on shared
     * storage or a removable card, so the provider is configured over the storage roots
     * rather than the app's own directory.
     */
    private fun shareUri(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)

    companion object {
        /**
         * Cada fichero de tienda con el nombre que le da GameNative a su tienda (su `GameSource`),
         * el unico lanzador que la pide. Visto en su codigo y en los perfiles de JUMP y Daijisho.
         */
        val STORE_SOURCES = mapOf(
            "steam" to "STEAM",
            "epic" to "EPIC",
            "gog" to "GOG",
            "amazon" to "AMAZON",
            "pcgame" to "CUSTOM_GAME",
        )

        /**
         * El numero que lleva dentro un fichero de tienda —la primera linea, y nada mas que
         * cifras—, o nulo si no es eso. Unos pocos bytes: leerlos al lanzar no se nota.
         */
        fun storeId(file: File): Int? = storeNumber(file)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

        /** Lo mismo sin el tope de un entero, para quien lo quiere como texto. */
        fun storeNumber(file: File): Long? = firstLine(file)?.trim()?.toLongOrNull()?.takeIf { it > 0 }

        private val VITA_TITLE = Regex("""PCS[A-Z]\d{5}""")

        /**
         * El numero de titulo de un juego de Vita: en el nombre del fichero —«Persona 4 Golden
         * (PCSE00120).vpk»— o en su primera linea, que es lo que guarda un `.psvita`.
         */
        fun vitaTitle(file: File): String? =
            VITA_TITLE.find(file.name.uppercase(java.util.Locale.ROOT))?.value
                ?: firstLine(file)?.let { VITA_TITLE.find(it.uppercase(java.util.Locale.ROOT))?.value }

        /**
         * La primera linea de un fichero pequeno. Solo pequeno: los de tienda son unos bytes, y
         * de un .vpk de gigas no se lee nada buscando un salto de linea en binario.
         */
        private fun firstLine(file: File): String? = runCatching {
            if (file.length() > 4096) null else file.bufferedReader().use { it.readLine() }
        }.getOrNull()
    }
}

/**
 * Como llama RetroArch a cada nucleo en sus menus, por el nombre de su fichero.
 *
 * En el catalogo y en la ruta va «snes9x»; en RetroArch —su historial, sus ajustes por nucleo—
 * se lee «Snes9x», y es ese el que se reconoce. Estan los del catalogo y los que mas se ponen en
 * su lugar; uno que no este sale con el nombre de su fichero, que tambien se entiende.
 */
internal object RetroCores {
    private val NAMES = mapOf(
        "81" to "EightyOne",
        "a5200" to "a5200",
        "arduous" to "Arduous",
        "b2" to "b2",
        "blastem" to "BlastEm",
        "bsnes" to "bsnes",
        "bsnes_hd_beta" to "bsnes-hd beta",
        "citra" to "Citra",
        "crocods" to "CrocoDS",
        "desmume" to "DeSmuME",
        "dosbox_pure" to "DOSBox Pure",
        "easyrpg" to "EasyRPG Player",
        "fbneo" to "FinalBurn Neo",
        "fceumm" to "FCEUmm",
        "flycast" to "Flycast",
        "fmsx" to "fMSX",
        "freechaf" to "FreeChaF",
        "freeintv" to "FreeIntv",
        "fuse" to "Fuse",
        "gambatte" to "Gambatte",
        "gearboy" to "Gearboy",
        "gearcoleco" to "Gearcoleco",
        "gearsystem" to "Gearsystem",
        "genesis_plus_gx" to "Genesis Plus GX",
        "gpsp" to "gpSP",
        "handy" to "Handy",
        "hatari" to "Hatari",
        "jaxe" to "JAXE",
        "kronos" to "Kronos",
        "lowresnx" to "LowRes NX",
        "mame" to "MAME",
        "mame2003_plus" to "MAME 2003-Plus",
        "mame2010" to "MAME 2010",
        "mednafen_ngp" to "Beetle NeoPop",
        "mednafen_pce" to "Beetle PCE",
        "mednafen_pce_fast" to "Beetle PCE Fast",
        "mednafen_pcfx" to "Beetle PC-FX",
        "mednafen_psx" to "Beetle PSX",
        "mednafen_psx_hw" to "Beetle PSX HW",
        "mednafen_saturn" to "Beetle Saturn",
        "mednafen_supergrafx" to "Beetle SuperGrafx",
        "mednafen_vb" to "Beetle VB",
        "mednafen_wswan" to "Beetle WonderSwan",
        "melonds" to "melonDS",
        "melondsds" to "melonDS DS",
        "mesen" to "Mesen",
        "mesen-s" to "Mesen-S",
        "mgba" to "mGBA",
        "mu" to "Mu",
        "mupen64plus_next" to "Mupen64Plus-Next",
        "mupen64plus_next_gles3" to "Mupen64Plus-Next",
        "neocd" to "NeoCD",
        "nestopia" to "Nestopia",
        "np2kai" to "Neko Project II kai",
        "o2em" to "O2EM",
        "opera" to "Opera",
        "parallel_n64" to "ParaLLEl N64",
        "pcsx_rearmed" to "PCSX ReARMed",
        "picodrive" to "PicoDrive",
        "pokemini" to "PokeMini",
        "potator" to "Potator",
        "ppsspp" to "PPSSPP",
        "prboom" to "PrBoom",
        "prosystem" to "ProSystem",
        "puae" to "PUAE",
        "px68k" to "PX68k",
        "quasi88" to "QUASI88",
        "same_cdi" to "SAME CDi",
        "sameboy" to "SameBoy",
        "sameduck" to "SameDuck",
        "scummvm" to "ScummVM",
        "smsplus" to "SMS Plus GX",
        "snes9x" to "Snes9x",
        "snes9x2005" to "Snes9x 2005",
        "snes9x2010" to "Snes9x 2010",
        "squirreljme" to "SquirrelJME",
        "stella" to "Stella",
        "swanstation" to "SwanStation",
        "tic80" to "TIC-80",
        "tyrquake" to "TyrQuake",
        "uzem" to "Uzem",
        "vba_next" to "VBA Next",
        "vbam" to "VBA-M",
        "vecx" to "vecx",
        "vice_x64sc" to "VICE x64sc",
        "vice_xpet" to "VICE xpet",
        "vice_xplus4" to "VICE xplus4",
        "vice_xvic" to "VICE xvic",
        "virtualjaguar" to "Virtual Jaguar",
        "vitaquake2" to "vitaQuake 2",
        "wasm4" to "WASM-4",
        "x1" to "X Millennium",
        "yabasanshiro" to "YabaSanshiro",
        "yabause" to "Yabause",
    )

    fun name(core: String): String = NAMES[core] ?: core

    /** Todos los que tienen nombre conocido, por su fichero. */
    fun all(): Set<String> = NAMES.keys

    /**
     * Un nucleo escrito a mano, como lo quiere la ruta: sin el final del fichero.
     *
     * Lo natural es copiar el nombre que se ve en la carpeta de RetroArch,
     * «snes9x_libretro_android.so», o la ruta entera, y la ruta ya le pone ese final: quedaba
     * «snes9x_libretro_android.so_libretro_android.so» y RetroArch no abria nada.
     */
    fun clean(typed: String): String =
        typed.trim().substringAfterLast('/').lowercase(java.util.Locale.ROOT)
            .removeSuffix(".so").removeSuffix("_android").removeSuffix("_libretro")
}

/**
 * Un segundo intento, uno solo, cuando RetroArch se cierra al recibir el juego.
 *
 * RetroArch 1.22.2 —la ultima estable, de noviembre de 2025— hace eso si ya estaba abierto con
 * otro juego: en vez de cargar el nuevo se cierra (finish y System.exit), contando con que el
 * lanzador lo vuelva a abrir. Habia que pulsar dos veces, y parecia que RetroArch arrancaba
 * cuando queria. Lo arreglaron en enero de 2026 (su #18587), pero solo esta en las nightly.
 *
 * Desde aqui se reconoce porque el front-end vuelve al frente enseguida sin que nadie lo pida.
 * Pulsar Home llega como un intent nuevo (ver MainActivity.onNewIntent) y eso anula el
 * reintento, asi que salir a proposito no relanza nada. Con una nightly no deberia pasar, y si
 * pasara, el segundo intento lleva el mismo juego, que RetroArch acepta sin cerrarse.
 */
internal object Bounce {
    /** Lo que puede tardar en volver un RetroArch que se cierra solo, con margen. */
    private const val WINDOW_MS = 2_000L

    private var again: Intent? = null
    private var at = 0L

    fun launched(intent: Intent) {
        again = Intent(intent)
        at = android.os.SystemClock.elapsedRealtime()
    }

    fun clear() {
        again = null
    }

    /** El juego que hay que volver a dar, si toca. Se gasta al pedirlo: el reintento es uno. */
    fun take(): Intent? {
        val i = again ?: return null
        again = null
        return i.takeIf { android.os.SystemClock.elapsedRealtime() - at <= WINDOW_MS }
    }
}
