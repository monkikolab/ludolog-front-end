package com.felp.frontcomp

/** A ROM that has been identified: which console it belongs to and what it is called. */
data class Game(
    val path: String,
    val systemId: String,
    val title: String,
    val region: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * Los discos de un juego de varios sin .m3u, en orden: el primero es [path]. Vacio en uno de
     * un solo disco. Ver Detector.groupDiscs.
     */
    val discs: List<String> = emptyList(),
    /**
     * El juego que es para el cuaderno, cuando no es el de la lista: un perfil de DoomForge sale
     * con su nombre («DOOM + Brutal»), pero lo que se juega es su IWAD, y las horas son de ese.
     * Nulo en todo lo demas. Ver Detector.doomProfiles y LibraryViewModel.bookTitle.
     */
    val playsAs: String? = null,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** What a file name says once the bracketed metadata is separated from the title. */
data class NameInfo(
    val title: String,
    val region: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * Turns paths into games. Pure Kotlin on purpose: no Android types here, so the whole
 * identification path runs under plain JVM tests.
 */
class Detector(private val catalog: Catalog) {

    // Los patrones, compilados una sola vez.
    //
    // describe() corre una vez por cada fichero identificado, y otra vez por cada juego al
    // releer la biblioteca guardada al arrancar. Compilarlos dentro salia a dos expresiones
    // por fichero, en el camino de arranque.
    companion object {
        private val BRACKET = Regex("""[\(\[]([^\)\]]*)[\)\]]""")
        private val SPACES = Regex("""\s+""")
        private val DISC_TAIL = Regex("""\s*\(?disc\s*\d+\)?\s*$""")
        private val DEVICE_TAG = Regex("""_(?:RP\d+|Odyn\d*|Odin\d*|Thor)\s*$""", RegexOption.IGNORE_CASE)
        private val DANGLING_PLUS = Regex("""\s+\+$""")
        private val TRACK_TAIL = Regex("""\s*\(track\s*\d+\)\s*$""", RegexOption.IGNORE_CASE)

        /** Lo que describe un disco y nombra sus pistas. */
        private val SHEETS = setOf("cue", "ccd", "gdi", "toc")

        /** Las pistas que esos nombran. */
        private val TRACKS = setOf("bin", "img")

        /** «Disc 2», «Disk 2», «Disc 2 of 3». */
        private val DISC_TAG = Regex("""(?i)dis[ck]\s*(\d+)(?:\s*of\s*\d+)?""")
        private val IWAD_FIELD = Regex(""""iwad"\s*:\s*"([^"]*)"""")

        /** El juego de cada IWAD conocido, por su nombre de fichero sin extension ni version. */
        private val IWADS = mapOf(
            "doom" to "Doom", "doom1" to "Doom", "doomu" to "The Ultimate Doom",
            "doom2" to "Doom II: Hell on Earth", "doom2f" to "Doom II: Hell on Earth",
            "plutonia" to "Final Doom: The Plutonia Experiment", "tnt" to "Final Doom: TNT: Evilution",
            "doom64" to "Doom 64", "heretic" to "Heretic", "heretic1" to "Heretic",
            "hexen" to "Hexen: Beyond Heretic", "strife1" to "Strife", "chex" to "Chex Quest",
            "freedoom1" to "Freedoom: Phase 1", "freedoom2" to "Freedoom: Phase 2",
        )
    }

    /**
     * Las pistas de un disco en cue+bin, fuera: el juego es el .cue que las nombra.
     *
     * PlayStation admite «bin» porque hay juegos que son un .bin suelto, y entonces cada pista
     * de un volcado de Redump salia como un juego: «Tomb Raider (USA)» cincuenta y ocho veces,
     * cincuenta y siete de ellas pistas de audio que no arrancan. Se quita la pista cuyo nombre,
     * sin el «(Track NN)», es el de una hoja de la misma carpeta; un .bin sin hoja se queda.
     */
    private fun dropTracks(games: List<Game>): List<Game> {
        fun stemOf(g: Game) = g.path.substringBeforeLast('/') + "/" +
            g.fileName.substringBeforeLast('.').lowercase()
        val sheets = games.filter { Catalog.extensionOf(it.fileName) in SHEETS }.map(::stemOf).toSet()
        if (sheets.isEmpty()) return games
        return games.filter { g ->
            Catalog.extensionOf(g.fileName) !in TRACKS || stemOf(g).replace(TRACK_TAIL, "") !in sheets
        }
    }

    /**
     * Los juegos de varios discos sin .m3u, una entrada por juego.
     *
     * Sin lista, cada disco salia como un juego: Final Fantasy VII tres veces, y sus horas
     * repartidas entre las tres. Se juntan los ficheros de la misma carpeta, consola, titulo y
     * region que llevan «(Disc N)», y solo si cada uno trae un numero distinto: dos juegos que
     * se llamen igual sin marca de disco no se tocan.
     *
     * Sin escribir un .m3u: DuckStation o ARMSX2 abren los ficheros con el permiso que les da
     * Android, y una lista guardada fuera de la carpeta de ROMs no siempre la podrian leer. Asi
     * que se lanza un disco suelto, que abre cualquier emulador: el ultimo que se uso, y se
     * cambia desde el menu del juego (ver LibraryViewModel.discFor).
     */
    private fun groupDiscs(games: List<Game>): List<Game> {
        fun discOf(g: Game) = g.tags.firstNotNullOfOrNull { DISC_TAG.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
        val withDisc = games.filter { discOf(it) != null }
        if (withDisc.size < 2) return games
        val sets = withDisc.groupBy { listOf(it.path.substringBeforeLast('/'), it.systemId, it.title.lowercase(), it.region.orEmpty()) }
            .values.filter { set -> set.size >= 2 && set.map(::discOf).distinct().size == set.size }
        if (sets.isEmpty()) return games
        val grouped = sets.flatten().map { it.path }.toSet()
        val merged = sets.map { set ->
            val ordered = set.sortedBy { discOf(it) }
            ordered.first().copy(
                tags = ordered.first().tags.filter { DISC_TAG.matchEntire(it.trim()) == null },
                discs = ordered.map { it.path },
            )
        }
        return games.filter { it.path !in grouped } + merged
    }

    /**
     * Los perfiles de DoomForge: cada uno en la lista, y en el cuaderno, su IWAD.
     *
     * Cada perfil es una combinacion de IWAD y mods con el nombre que se le quiera dar —«DOOM
     * (v1.9) + Brutal EOA ts», «DOOM (v1.9)»—, y en la lista sale cada uno, porque es lo que se
     * elige al lanzar. Pero lo que se juega es el IWAD; los mods son como se juega. Asi que cada
     * perfil lleva en [Game.playsAs] el nombre del juego de su IWAD, y con ese se apuntan sus
     * partidas: el cuaderno ve un Doom con todas sus horas, no tres juegos a medias.
     *
     * Antes se juntaban en la lista, uno por IWAD, lanzando el jugado mas tarde; asi no se podia
     * elegir con que mods jugar.
     *
     * El IWAD se lee del propio perfil, que es JSON con `"iwad": "<ruta>"`. Un perfil que no se
     * pueda leer o no lo diga se queda como estaba.
     */
    private fun doomProfiles(games: List<Game>): List<Game> = games.map { g ->
        if (Catalog.extensionOf(g.fileName) != "doomforge") return@map g
        val text = runCatching { java.io.File(g.path).readText() }.getOrNull() ?: return@map g
        val iwad = IWAD_FIELD.find(text)?.groupValues?.get(1)?.replace("\\/", "/")
            ?.substringAfterLast('/')?.substringBeforeLast('.')
            ?.replace(BRACKET, "")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return@map g
        g.copy(playsAs = IWADS[iwad] ?: iwad.uppercase())
    }

    /**
     * Which console a folder belongs to, walking up until something matches.
     *
     * Walking up is what makes nested layouts work — "ROMs/Nintendo/SNES/Hacks" resolves
     * through "SNES" — and it is also why the deepest match wins: the innermost folder is
     * the most specific claim about what is inside.
     */
    fun systemForPath(path: String): SystemDef? {
        val parts = path.trim('/').split('/')
        for (i in parts.indices.reversed()) {
            catalog.forFolder(parts[i])?.let { return it }
        }
        return null
    }

    /**
     * Which console a file belongs to.
     *
     * The folder is asked first and the extension only has to confirm it, because folders
     * carry intent and extensions do not: ".iso" alone cannot separate a GameCube dump
     * from a PS2 one. When the folder says nothing, an extension claimed by exactly one
     * system is still a safe answer; an ambiguous one is left undecided rather than guessed.
     */
    fun systemForFile(path: String): SystemDef? {
        val ext = Catalog.extensionOf(path.substringAfterLast('/'))
        if (ext.isEmpty()) return null

        val fromFolder = systemForPath(path.substringBeforeLast('/', ""))
        if (fromFolder != null && ext in catalog.extensionsOf(fromFolder)) return fromFolder

        // Some extensions are too generic to stand alone even when one system claims them:
        // ".app" belongs to the 3DS in the table, but on an Android handheld it is far
        // more likely to be a launcher shortcut sitting in a folder we could not name.
        if (ext in catalog.ambiguousExtensions) return null

        val claimants = catalog.byExtension[ext].orEmpty()
        if (claimants.size == 1) return claimants[0]

        // An archive inside a folder we could not name tells us nothing at all.
        if (fromFolder != null && ext in catalog.zipExtensions) return fromFolder
        return null
    }

    /**
     * "Chrono Trigger (USA) [!].sfc" -> title "Chrono Trigger", region "us", tags [!].
     *
     * Everything in brackets is treated as metadata rather than name, which is the
     * convention every ROM set follows, and separators used in place of spaces are put
     * back so that scraper lookups and alphabetical sorting both behave.
     */
    fun describe(path: String): NameInfo {
        val raw = path.substringAfterLast('/')
        val ext = Catalog.extensionOf(raw)
        var stem = if (ext.isEmpty()) raw else raw.dropLast(ext.length + 1)

        val tags = mutableListOf<String>()
        BRACKET.findAll(stem).forEach { m ->
            m.groupValues[1].split(',').forEach { piece ->
                piece.trim().takeIf(String::isNotEmpty)?.let(tags::add)
            }
        }
        stem = BRACKET.replace(stem, " ")

        // La etiqueta del aparato que se pone a mano al final de una copia —un sufijo corto, ver DEVICE_TAG—
        // para tener una por consola en la misma tarjeta. No es del nombre: en la lista salia
        // «GOD OF WAR III» con la etiqueta pegada detras. Solo con guion bajo delante, que es como se pone; una cifra
        // al final de un titulo de verdad va con espacio.
        stem = DEVICE_TAG.replace(stem, "")

        // Only separators that stand in for spaces are collapsed; a hyphen between words
        // is part of plenty of real titles. Y los puntos, solo en un nombre sin espacios, que
        // es donde hacen de espacio: en «Super Mario Bros.» o «Dr. Mario» son del titulo.
        stem = stem.replace('_', ' ')
        if (' ' !in stem.trim()) stem = stem.replace('.', ' ')
        stem = stem.replace(SPACES, " ").trim().trim('-', ' ')
        // Y un «+» colgando, lo que queda entre dos corchetes quitados: «Blasphemous [id]+[v1]».
        stem = DANGLING_PLUS.replace(stem, "")

        val region = tags.firstNotNullOfOrNull { t ->
            SystemDef.key(t).takeIf { it in catalog.regionSuffixes }
        }
        return NameInfo(title = stem, region = region, tags = tags)
    }

    fun gameFor(path: String): Game? {
        val sys = systemForFile(path) ?: return null
        val info = describe(path)
        if (info.title.isEmpty()) return null
        return Game(
            path = path,
            systemId = sys.id,
            title = info.title,
            region = info.region,
            tags = info.tags,
        )
    }

    /**
     * Multi-disc sets: when an .m3u is present it is the entry point and the individual
     * discs beside it are noise, so they get dropped from the listing.
     *
     * Sets without an .m3u are deliberately left alone for now, so a three-disc game shows
     * up three times — grouping them by title alone would risk merging games that only
     * look alike.
     */
    fun collapseMultiDisc(all: List<Game>): List<Game> {
        val games = doomProfiles(dropTracks(all))
        val playlistStems = games
            .filter { Catalog.extensionOf(it.fileName) == "m3u" }
            .map { it.path.substringBeforeLast('/') + "/" + it.title.lowercase() }
            .toSet()
        if (playlistStems.isEmpty()) return groupDiscs(games)
        return groupDiscs(games).filter { g ->
            if (Catalog.extensionOf(g.fileName) == "m3u") return@filter true
            val stem = g.path.substringBeforeLast('/') + "/" +
                g.title.lowercase().replace(DISC_TAIL, "").trim()
            stem !in playlistStems
        }
    }
}
