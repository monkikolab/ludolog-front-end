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
        val dir = DataHome.file(DIR)
        val stamp = dir.lastModified()
        if (stamp == 0L) return null
        val map = cache?.takeIf { it.first == stamp }?.second
            ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") && !f.name.startsWith(".") }.orEmpty()
                .associateBy { it.nameWithoutExtension }.also { cache = stamp to it }
        return map[key(title)]
    }
}
