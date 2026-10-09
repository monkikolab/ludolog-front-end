package com.felp.frontcomp

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Los temas que se bajan: el Parlour y el Mainframe viven fuera del APK, en el mismo
 * lanzamiento que el catalogo de juegos (un solo repositorio, ver GameDb.SOURCE), y se ofrecen en
 * la bienvenida con lo que ocupa cada uno.
 *
 * Un tema publicado es un zip con la misma forma que su carpeta (ver ThemeFiles y
 * ludolog-assets), y un indice, `themes.tsv`, que dice cuales hay (`index.tsv` es el del
 * catalogo, en el mismo sitio):
 *
 *     #ludolog-themes  1  <fecha>
 *     file  theme  title  bytes  sha1
 *     parlour.zip  parlour  Parlour  7100000  <sha1>
 *
 * Instalar es bajarlo, comprobar su huella y descomprimirlo en `<datos>/themes/<id>.part`, que
 * se renombra a `<id>` solo al acabar. Un tema que ya esta no se toca: puede ser uno puesto a
 * mano con install.sh, y pisarlo seria perder lo que se le cambio.
 */
internal object ThemeStore {

    /** De donde se bajan: el mismo lanzamiento que el catalogo. */
    val SOURCE: String get() = GameDb.SOURCE

    /** Los que se ofrecen para bajar; el Gallery va dentro del APK. */
    val OFFERED = listOf("parlour", "mainframe")

    data class Offer(val id: String, val title: String, val file: String, val bytes: Long, val sha1: String)

    fun parseIndex(text: String): List<Offer> {
        val lines = text.lines()
        if (lines.firstOrNull()?.startsWith("#ludolog-themes") != true) return emptyList()
        val cols = lines.getOrNull(1)?.split('\t')?.withIndex()?.associate { (i, c) -> c to i } ?: return emptyList()
        fun List<String>.col(name: String) = cols[name]?.let { i -> getOrNull(i) }.orEmpty()
        return lines.drop(2).filter(String::isNotBlank).map { l ->
            val f = l.split('\t')
            Offer(f.col("theme"), f.col("title"), f.col("file"), f.col("bytes").toLongOrNull() ?: 0L, f.col("sha1"))
        }.filter { it.id.matches(ID) && it.file.isNotEmpty() && '/' !in it.file }
    }

    /** Un id de tema es una palabra: nada de rutas. */
    private val ID = Regex("[a-z0-9_-]+")

    fun remoteIndex(source: String): Result<List<Offer>> = runCatching {
        require(source.isNotBlank()) { "no theme source set" }
        parseIndex(String(GameDb.get(source.trimEnd('/') + "/themes.tsv"), Charsets.UTF_8))
            .ifEmpty { throw IOException("the theme index is empty") }
    }

    /** Donde van: al lado de los que se ponen con install.sh. */
    val dir: File get() = DataHome.file("themes")

    /** Si ya hay una carpeta con ese tema, venga de donde venga. */
    fun installed(id: String, root: File = dir): Boolean =
        File(root, id).listFiles()?.isNotEmpty() == true

    /**
     * Baja e instala un tema. Si ya esta, no hace nada.
     *
     * Descomprime a una carpeta aparte y la renombra al final: cortado a mitad, lo que queda es
     * un `.part` que el siguiente intento vacia, nunca un tema a medias que parezca entero. Y
     * cada nombre del zip se comprueba: uno que se saliera de la carpeta («../») no se escribe.
     */
    fun install(source: String, offer: Offer, root: File = dir, progress: (String) -> Unit = {}): Result<Unit> = runCatching {
        ThemeFiles.forget()
        if (installed(offer.id, root)) return@runCatching
        progress("Downloading ${offer.title}…")
        val zip = GameDb.get(source.trimEnd('/') + "/" + offer.file)
        if (GameDb.sha1(zip) != offer.sha1) throw IOException("${offer.file} arrived damaged")
        progress("Installing ${offer.title}…")
        val part = File(root, "${offer.id}.part")
        part.deleteRecursively()
        part.mkdirs()
        val top = part.canonicalPath + File.separator
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(part, e.name)
                if (!out.canonicalPath.startsWith(top)) throw IOException("bad path in ${offer.file}: ${e.name}")
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
        val to = File(root, offer.id)
        if (!part.renameTo(to)) throw IOException("could not put ${offer.id} in place")
    }
}
