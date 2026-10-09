package com.felp.frontcomp

import java.io.File

/**
 * Las caratulas pequeñas que deja Ludolog Link para los juegos que solo se jugaron en OTRA consola.
 *
 * El Companion busca la caratula de cada juego entre los de esta biblioteca; uno de otra consola no
 * esta aqui y salia con un cuadro de color. Link, al compartir los cuadernos, trae una miniatura de
 * esos a `<datos>/companion/covers/<clave>.jpg`, y aqui solo se lee. Sin Link la carpeta no existe y
 * no cambia nada. La clave es el nombre del juego, solo letras y numeros en minusculas: la misma
 * regla que en Link (CompanionCovers.key). Ver docs/ludolog-link.md.
 */
internal object CompanionCovers {
    private const val DIR = "companion/covers"

    /** La lista de la carpeta, y de cuando es: se vuelve a leer solo si cambio (Link dejo alguna). */
    @Volatile private var cache: Pair<Long, Map<String, File>>? = null

    fun key(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

    /** La miniatura de ese juego, o nula. */
    fun find(title: String): File? {
        // Se pregunta por cada fila que se dibuja, en el hilo de la pantalla, y mirar la fecha de la
        // carpeta es ir a la SD: en la RP5, recorrer las partidas del Companion se trababa en cuatro
        // de cada diez cuadros (08-10-2026). La carpeta se mira como mucho cada [RECHECK_MS]; lo que
        // Link deje mientras tanto aparece en la siguiente.
        val now = System.nanoTime() / 1_000_000
        val known = cache
        if (known != null && now - checkedAt < RECHECK_MS) return known.second[key(title)]
        checkedAt = now
        val dir = DataHome.file(DIR)
        val stamp = dir.lastModified()
        val map = known?.takeIf { it.first == stamp }?.second
            ?: (if (stamp == 0L) emptyMap()
                else dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") && !f.name.startsWith(".") }.orEmpty()
                    .associateBy { it.nameWithoutExtension })
                .also { cache = stamp to it }
        return map[key(title)]
    }

    /** Cuando se miro la carpeta por ultima vez, y cada cuanto se vuelve a mirar. */
    @Volatile private var checkedAt = 0L
    private const val RECHECK_MS = 3_000L
}
