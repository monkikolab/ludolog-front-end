package com.felp.frontcomp

import java.io.File

/**
 * La biblioteca que sobrevive a cerrar la aplicacion.
 *
 * Hasta ahora no sobrevivia nada: al abrir el front-end la lista estaba vacia y habia que
 * pulsar Scan a mano, cada vez. No era un problema de velocidad —recorrer las carpetas de
 * ROMs cuesta unas decimas— sino de memoria: el trabajo estaba hecho y se tiraba.
 *
 * Lo que se guarda son las rutas y nada mas. Identificar un juego a partir de su ruta es
 * puro texto y no toca el disco, asi que reconstruir la lista desde aqui es instantaneo, y
 * el formato no se rompe el dia que un juego gane un campo nuevo.
 */
internal object LibraryCache {

    private const val HEADER = "frontcomp-library 1"
    /** Las carpetas con ROMs de consola desconocida se marcan, porque no son juegos. */
    private const val UNKNOWN = "?"

    fun save(file: File, result: ScanResult) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.bufferedWriter().use { out ->
                out.appendLine(HEADER)
                result.unknownFolders.forEach { out.appendLine(UNKNOWN + it) }
                // Todos los discos de un juego de varios, no solo el primero: al leerla se vuelven
                // a juntar (ver Detector.groupDiscs), y sin los otros el juego se quedaba en uno.
                result.games.forEach { g -> g.discs.ifEmpty { listOf(g.path) }.forEach(out::appendLine) }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Lo guardado la ultima vez, o null si no hay nada o el catalogo ya no lo reconoce.
     *
     * Puede estar desfasado: un juego borrado desde el ordenador sigue en la lista hasta
     * que el repaso de fondo termina, unas decimas despues de abrir. Es un precio pequeno
     * por tener la biblioteca en pantalla desde el primer fotograma.
     */
    fun load(file: File, detector: Detector): ScanResult? = runCatching {
        if (!file.isFile) return null
        val lines = file.readLines()
        if (lines.firstOrNull() != HEADER) return null

        val unknown = mutableListOf<String>()
        val games = mutableListOf<Game>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (line.startsWith(UNKNOWN)) unknown += line.drop(1)
            else detector.gameFor(line)?.let(games::add)
        }
        if (games.isEmpty() && unknown.isEmpty()) return null

        ScanResult(
            games = detector.collapseMultiDisc(games).sortedWith(
                compareBy({ it.systemId }, { it.title.lowercase() }),
            ),
            unknownFolders = unknown,
            filesSeen = games.size,
            millis = 0L,
            remembered = true,
        )
    }.getOrNull()
}
