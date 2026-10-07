package com.felp.frontcomp

import java.io.File

/**
 * Los ficheros que son un juego, para borrarlo desde el menu del juego.
 *
 * Un juego de la lista no siempre es un fichero: un .cue nombra sus pistas .bin, un .gdi las
 * suyas, un .ccd lleva al lado su .img y su .sub, un .m3u lista discos que a su vez pueden ser
 * .cue, y uno de varios discos sin lista son varios ficheros (ver Detector.groupDiscs). Borrar
 * solo el que se ve dejaba medio gigabyte de pistas sueltas que ya no salen en ninguna parte.
 *
 * Y nada mas que eso: solo lo que esta en la misma carpeta que el juego y existe. Un .cue que
 * nombrara «../otro.bin» no arrastra un fichero de fuera, y un perfil de DoomForge es el perfil
 * y no su IWAD, que lo comparten los demas perfiles. Las partidas guardadas del emulador viven en
 * sus carpetas y no se tocan.
 *
 * Kotlin puro, para las pruebas.
 */
internal object RomFiles {

    /** Lo que nombra a otros ficheros, y como. */
    private val FILE_LINE = Regex("""(?i)^\s*FILE\s+"([^"]+)"""")      // .cue
    private val GDI_LINE = Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+("[^"]+"|\S+)""")  // .gdi

    /** Los ficheros del juego, el principal primero. */
    fun of(game: Game): List<File> {
        val out = LinkedHashSet<File>()
        val mains = game.discs.ifEmpty { listOf(game.path) }.map(::File)
        for (f in mains) add(f, out, depth = 0)
        return out.filter { it.isFile }
    }

    /** Lo que ocupa todo, en bytes. */
    fun size(files: List<File>): Long = files.sumOf { it.length() }

    /**
     * Borra los ficheros. Devuelve los que no se pudieron borrar (vacio si todo fue bien): uno
     * en una tarjeta de solo lectura o abierto por otra app no para a los demas.
     */
    fun delete(files: List<File>): List<File> = files.filter { it.exists() && !it.delete() }

    private fun add(f: File, out: MutableSet<File>, depth: Int) {
        if (!out.add(f) || depth > 2) return
        val dir = f.parentFile ?: return
        val named: List<String> = when (Catalog.extensionOf(f.name)) {
            "cue" -> lines(f).mapNotNull { FILE_LINE.find(it)?.groupValues?.get(1) }
            "gdi" -> lines(f).drop(1).mapNotNull { GDI_LINE.find(it)?.groupValues?.get(1)?.trim('"') }
            "m3u" -> lines(f).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            // CloneCD: la hoja va con su imagen y su subcanal, con el mismo nombre.
            "ccd" -> listOf("img", "sub").map { f.nameWithoutExtension + "." + it }
            else -> emptyList()
        }
        for (name in named) {
            val g = File(dir, name.replace('\\', '/'))
            // Solo de la misma carpeta (o de una por debajo, como hacen algunas .m3u).
            if (!inside(g, dir) || !g.isFile) continue
            add(g, out, depth + 1)
        }
    }

    private fun inside(f: File, dir: File): Boolean =
        runCatching { f.canonicalPath.startsWith(dir.canonicalPath + File.separator) }.getOrDefault(false)

    private fun lines(f: File): List<String> =
        runCatching { if (f.length() > 256 * 1024) emptyList() else f.readLines() }.getOrDefault(emptyList())
}
