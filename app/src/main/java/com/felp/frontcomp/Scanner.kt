package com.felp.frontcomp

import java.io.File

data class ScanResult(
    val games: List<Game>,
    val unknownFolders: List<String>,
    val filesSeen: Int,
    val millis: Long,
    /** Si viene de lo guardado en vez de un recorrido recien hecho. */
    val remembered: Boolean = false,
) {
    val bySystem: Map<String, List<Game>> get() = games.groupBy { it.systemId }
}

/**
 * Walks ROM roots and identifies what it finds.
 *
 * Depth is capped because ROM trees are shallow by nature but sit next to things that are
 * not — a save-state folder or an extracted archive can nest forever, and following those
 * turns a two-second scan into a minute of stat() calls.
 */
class Scanner(private val detector: Detector, private val catalog: Catalog) {

    /** Folders that never hold ROMs but frequently sit inside ROM trees. */
    //
    // Normalizados como se comparan, con SystemDef.key: escrito «downloaded_media», con su
    // guion bajo, no casaba nunca con la carpeta de ES-DE, y el arte que guarda con nombre
    // de cartucho de PICO-8 —«x.p8.png»— salia en la lista como juegos.
    private val skipped = setOf(
        "media", "downloaded_media", "images", "covers", "boxart", "screenshots",
        "manuals", "videos", "gamelists", "cheats", "saves", "states", "bios",
        "system", "cache", "temp", "tmp", "emulators",
    ).map(SystemDef::key).toSet()

    fun scan(roots: List<File>, maxDepth: Int = 6): ScanResult {
        val started = System.currentTimeMillis()
        val games = mutableListOf<Game>()
        val unknown = linkedSetOf<String>()
        var seen = 0

        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            val entries = dir.listFiles() ?: return

            // A folder that names a console tells us what its files are; one that does not
            // is still worth reporting, because it is usually a console we have no entry for.
            //
            // Mirando toda la ruta y no solo su nombre, como se identifican los ficheros: la
            // carpeta de un juego dentro de la de su consola —«psx/FF7 (USA)»— se daba por una
            // consola desconocida, y los ajustes pedian darla de alta como alias.
            val sys = detector.systemForPath(dir.path)
            val hasRoms = sys == null && entries.any { it.isFile && detector.systemForFile(it.path) != null }
            if (hasRoms) unknown.add(dir.path)

            for (e in entries) {
                val name = e.name
                if (name.startsWith('.')) continue
                if (e.isDirectory) {
                    if (SystemDef.key(name) in skipped) continue
                    walk(e, depth + 1)
                } else {
                    seen++
                    detector.gameFor(e.path)?.let(games::add)
                }
            }
        }

        roots.filter { it.isDirectory }.forEach { walk(it, 0) }

        return ScanResult(
            games = detector.collapseMultiDisc(games).sortedWith(
                compareBy({ it.systemId }, { it.title.lowercase() })
            ),
            unknownFolders = unknown.toList(),
            filesSeen = seen,
            millis = System.currentTimeMillis() - started,
        )
    }

    companion object {
        /**
         * Where ROMs usually live.
         *
         * Removable storage is checked as well as the built-in one, and on these handhelds
         * it is usually the only place that matters: the card is where a library of tens of
         * gigabytes actually fits, and internal /ROMs is often an empty leftover.
         */
        fun defaultRoots(): List<File> {
            // Las unidades, segun Android: ver DataHome.volumes.
            val volumes = DataHome.volumes()
            // SD cards are formatted exFAT/FAT, which is case-insensitive: "ROMs", "Roms"
            // and "roms" are one folder wearing three names, and every one of them exists
            // as far as isDirectory() is concerned. Without this the same library gets
            // walked once per spelling and every game is counted three times.
            val seen = HashSet<String>()
            val roots = volumes.flatMap { vol -> RomFolders.NAMES.map { File(vol, it) } }
                .filter { it.isDirectory }
                .filter { seen.add(canonicalKey(it)) }

            return roots.ifEmpty { volumes.filter { it.isDirectory } }
        }

        /**
         * Donde buscar: las carpetas elegidas a mano si las hay, y si no las de siempre.
         *
         * Una elegida que ya no existe —la tarjeta sacada— no cuenta, pero sigue elegida: al
         * volver la tarjeta, vuelve la biblioteca.
         */
        fun roots(prefs: Prefs): List<File> =
            outermost(prefs.romDirs?.map(::File)?.filter { it.isDirectory } ?: defaultRoots())

        /**
         * Sin repetidas y sin las que caen dentro de otra.
         *
         * Las elegidas a mano no pasaban por ningun filtro: con la de ROMs de la tarjeta puesta
         * y la raiz de la misma tarjeta añadida despues, cada juego se leia por los dos caminos y
         * salia dos veces en la lista.
         */
        internal fun outermost(roots: List<File>): List<File> {
            val keyed = roots.map { it to canonicalKey(it).trimEnd('/') + "/" }.distinctBy { it.second }
            return keyed
                .filter { (_, k) -> keyed.none { (_, other) -> other != k && k.startsWith(other) } }
                .map { it.first }
        }

        private fun canonicalKey(f: File): String =
            runCatching { f.canonicalPath }.getOrDefault(f.absolutePath).lowercase()
    }
}
