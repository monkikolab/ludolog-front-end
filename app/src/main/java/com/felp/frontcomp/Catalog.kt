package com.felp.frontcomp

import java.io.File

/**
 * One console. Everything the app knows about a system lives here and comes from data,
 * never from code, so a console that does not exist yet can be added by editing a file.
 */
data class SystemDef(
    val id: String,
    val name: String,
    val label: String,
    val raName: String = "",
    val raCore: String = "",
    val accent: Long = 0L,
    val boxAspect: Float = 1f,
    val zipOk: Boolean = true,
    val extensions: Set<String> = emptySet(),
    val aliases: Set<String> = emptySet(),
    /** Una o dos frases sobre la consola, para el panel de informacion. */
    val description: String = "",
    /**
     * Where the console's own picture lives, relative to a media folder or absolute.
     *
     * Part of the system entry on purpose: adding a console and pointing at its artwork
     * should be one edit in one file, not two things to keep in sync.
     */
    val image: String = "",
    /** Giro prerenderizado de la consola. Vacio = se busca systems/<id>.mp4. */
    val video: String = "",
    /**
     * La coleccion de videos de partida en archive.org, por su identificador.
     *
     * Va en el catalogo y no en el codigo a proposito: son colecciones subidas por gente,
     * y el dia que una desaparezca o salga otra mejor se arregla editando una linea del
     * .toml del aparato, sin recompilar nada.
     */
    val videoSnaps: String = "",
    /**
     * Lo que se cuenta de la maquina: rotulo y valor, como mucho cuatro.
     *
     * En el .toml, una lista de textos «ROTULO|valor» —`"CPU|MIPS R3000A 33.9 MHz"`—, para que
     * se escriba y se corrija a mano igual que el resto de la entrada. Son datos del aparato y
     * no del tema: el Mainframe los saca con lineas desde el giro, y otro tema podria
     * ensenarlos de otra manera.
     */
    val specs: List<Pair<String, String>> = emptyList(),
    /**
     * Quien la fabrico y el año en que salio a la venta por primera vez, en cualquier sitio:
     * la Famicom de 1983 y no la NES de 1985. Los pone el tema Gallery bajo el nombre corto,
     * en lugar de un dibujo de la consola. Vacio y cero = no se sabe, y no se escribe.
     */
    val maker: String = "",
    val year: Int = 0,
) {
    /** Every spelling that should resolve to this system, already normalised. */
    fun keys(): Set<String> = buildSet {
        add(key(id))
        add(key(name))
        add(key(label))
        if (raName.isNotEmpty()) add(key(raName))
        aliases.forEach { add(key(it)) }
        remove("")
    }

    companion object {
        /**
         * Folder names arrive in every spelling a frontend ever invented: "SNES",
         * "Super Nintendo", "Nintendo - Super Nintendo Entertainment System", "snes_msu1".
         * Collapsing to letters and digits makes all of them the same lookup key, which is
         * why the alias lists can stay short.
         */
        fun key(s: String): String = buildString(s.length) {
            for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }
    }
}

/**
 * The full system table plus the lookup indexes built from it.
 *
 * Construction is cheap enough to redo whenever the data changes, so callers never mutate
 * a catalog — they build a new one.
 */
class Catalog(
    val systems: List<SystemDef>,
    val zipExtensions: Set<String> = setOf("zip", "7z"),
    val discSystems: Set<String> = emptySet(),
    val regionSuffixes: Set<String> = emptySet(),
    /**
     * Extensions that never identify a system on their own, even when a single system
     * claims them. ".app" is the case that motivated this: only the 3DS lists it, so it
     * won by default and turned every ES-DE Android shortcut — "Google Play Store.app" —
     * into a 3DS game.
     */
    val ambiguousExtensions: Set<String> = setOf("app", "bin", "rom", "dat", "img"),
) {
    val byId: Map<String, SystemDef> = systems.associateBy { it.id }

    /** Normalised spelling -> system. First definition wins, so overlays cannot shadow silently. */
    val byKey: Map<String, SystemDef> = buildMap {
        systems.forEach { sys -> sys.keys().forEach { k -> putIfAbsent(k, sys) } }
    }

    /**
     * Extension -> systems that claim it. Deliberately many-to-many: ".iso" belongs to
     * PS2, GameCube, Wii, Xbox and PSP at once, and only the containing folder can tell
     * them apart.
     */
    val byExtension: Map<String, List<SystemDef>> = buildMap<String, MutableList<SystemDef>> {
        systems.forEach { sys ->
            sys.extensions.forEach { ext ->
                getOrPut(ext.lowercase()) { mutableListOf() }.add(sys)
            }
        }
    }

    fun forFolder(folderName: String): SystemDef? = byKey[SystemDef.key(folderName)]

    /**
     * El id de aqui para una consola nombrada como sea: su propio id, o cualquiera de sus
     * nombres y alias. Nulo si no es ninguna.
     */
    fun canonicalId(name: String): String? = byId[name]?.id ?: forFolder(name)?.id

    /** Extensions this system accepts, including the archive formats when it allows them. */
    fun extensionsOf(sys: SystemDef): Set<String> =
        if (sys.zipOk) sys.extensions + zipExtensions else sys.extensions

    companion object {
        /**
         * Double extensions matter: PICO-8 carts are "name.p8.png", and treating that as a
         * PNG would drop every cartridge on the device.
         */
        fun extensionOf(fileName: String): String {
            val lower = fileName.lowercase()
            val dot = lower.lastIndexOf('.')
            if (dot <= 0) return ""
            val single = lower.substring(dot + 1)
            val prev = lower.lastIndexOf('.', dot - 1)
            if (prev > 0) {
                val double = lower.substring(prev + 1)
                if (double == "p8.png") return double
            }
            return single
        }

        /**
         * Reads a TOML catalog.
         *
         * TOML is the format the user edits: it takes comments, it does not need brackets
         * counted, and a new console is a block pasted at the end.
         */
        //
        // Lo que [defaults] no dice se queda VACIO, y no con unos valores de repuesto: vacio es
        // lo que merge() entiende como «este fichero no lo toca». Con repuesto, cualquier
        // fichero del usuario —y el gestor de consolas escribe uno por consola, sin [defaults]—
        // cambiaba la lista de extensiones ambiguas del catalogo por una de cinco: iso, chd,
        // cue, elf y m3u dejaban de serlo, y un `boot.elf` de Wii salia como juego de
        // Atomiswave. El catalogo que viene con la aplicacion las trae todas escritas.
        fun parse(toml: String): Catalog {
            val doc = Toml.parse(toml)
            val defaults = doc.first("defaults")
            return Catalog(
                systems = doc.all("system").mapNotNull(::readSystem),
                zipExtensions = defaults?.strings("zipExtensions")?.toSet().orEmpty(),
                discSystems = defaults?.strings("discSystems")?.toSet().orEmpty(),
                regionSuffixes = defaults?.strings("regionSuffixes")?.toSet().orEmpty(),
                ambiguousExtensions = defaults?.strings("ambiguousExtensions")
                    ?.map(::cleanExtension)?.toSet().orEmpty(),
            )
        }

        /**
         * Una extension como se compara: en minusculas y sin el punto. ES-DE las escribe con
         * punto —«.gba»—, y copiadas asi en un .toml no casaban con ningun fichero.
         */
        private fun cleanExtension(e: String) = e.trim().removePrefix(".").lowercase()

        private fun readSystem(t: Toml.Table): SystemDef? {
            val id = t.string("id")?.takeIf(String::isNotEmpty) ?: return null
            return SystemDef(
                id = id,
                name = t.string("name") ?: id,
                label = t.string("label") ?: id.uppercase(),
                raName = t.string("raName").orEmpty(),
                raCore = t.string("raCore").orEmpty(),
                accent = t.string("accent")?.removePrefix("0x")?.toLongOrNull(16) ?: 0L,
                boxAspect = t.number("boxAspect")?.toFloat() ?: 1f,
                zipOk = t.bool("zipOk") ?: true,
                extensions = t.strings("extensions").map(::cleanExtension).toSet(),
                aliases = t.strings("aliases").toSet(),
                description = t.string("description").orEmpty(),
                image = t.string("image").orEmpty(),
                videoSnaps = t.string("videoSnaps").orEmpty(),
                video = t.string("video").orEmpty(),
                specs = t.strings("specs").mapNotNull { s ->
                    val cut = s.indexOf('|')
                    if (cut <= 0) null else s.substring(0, cut).trim() to s.substring(cut + 1).trim()
                },
                maker = t.string("maker").orEmpty(),
                year = t.number("year")?.toInt() ?: 0,
            )
        }

        /**
         * Overlay wins per system id, and systems it does not mention are kept. That is what
         * makes a user file additive: dropping in one new console does not cost you the
         * other forty-four, and correcting one extension does not mean copying the table.
         */
        fun merge(base: Catalog, overlay: Catalog): Catalog {
            val merged = LinkedHashMap<String, SystemDef>()
            base.systems.forEach { merged[it.id] = it }
            overlay.systems.forEach { merged[it.id] = it }
            return Catalog(
                systems = merged.values.toList(),
                zipExtensions = overlay.zipExtensions.ifEmpty { base.zipExtensions },
                discSystems = if (overlay.discSystems.isNotEmpty()) overlay.discSystems else base.discSystems,
                regionSuffixes = if (overlay.regionSuffixes.isNotEmpty()) overlay.regionSuffixes else base.regionSuffixes,
                ambiguousExtensions = if (overlay.ambiguousExtensions.isNotEmpty())
                    overlay.ambiguousExtensions else base.ambiguousExtensions,
            )
        }

    }
}

/**
 * Where the catalog comes from at runtime: the shipped table, then the user's file on top.
 *
 * The user file is plain JSON on shared storage precisely so it can be edited from the
 * device, from a PC over USB, or replaced wholesale by a pack someone else wrote.
 */
object CatalogLoader {
    const val ASSET = "systems.toml"

    /** A single file with everything, for people who like one file. */
    val userFile: File get() = DataHome.file("systems.toml")

    /**
     * A folder of one-console-per-file, for people who like to drop in a console and
     * delete it later without editing anything.
     */
    val userDir: File get() = DataHome.file("systems")

    /** What went wrong in the user's files, so the settings window can show it. */
    var lastErrors: List<String> = emptyList(); private set

    fun load(readAsset: (String) -> String): Catalog {
        var catalog = Catalog.parse(readAsset(ASSET))
        val errors = mutableListOf<String>()

        // El archivo suelto primero y luego la carpeta, para que un .toml por consola
        // pueda corregir lo que diga el archivo grande.
        val sources = buildList {
            userFile.takeIf { it.isFile }?.let { add(it) }
            userDir.listFiles()?.filter { it.isFile && it.extension.equals("toml", true) }
                ?.sortedBy { it.name }?.let { addAll(it) }
        }

        for (f in sources) {
            runCatching { catalog = Catalog.merge(catalog, Catalog.parse(f.readText())) }
                .onFailure {
                    // Un archivo roto se salta y se informa: perder una consola es mucho
                    // mejor que no arrancar, y en silencio no habría forma de saberlo.
                    errors += "${f.name}: ${it.message}"
                }
        }
        lastErrors = errors
        current = catalog
        return catalog
    }

    /**
     * El ultimo cargado, para quien no tiene la pantalla a mano: el cuaderno se lee tambien
     * desde el servicio que mide las partidas. Nulo si aun no se ha cargado ninguno.
     */
    @Volatile var current: Catalog? = null; private set
}
