package com.felp.frontcomp

import java.io.File

/**
 * Writes a console back out as TOML.
 *
 * Each console the user edits becomes its own file in the systems folder, rather than
 * rewriting the shipped catalog. That way an edit is always reversible by deleting one
 * file, and updating the app never fights with changes made on the device.
 */
object CatalogWriter {

    fun fileFor(systemId: String): File = File(CatalogLoader.userDir, "$systemId.toml")

    fun isUserDefined(systemId: String): Boolean = fileFor(systemId).isFile

    fun save(sys: SystemDef): Result<File> = runCatching {
        val f = fileFor(sys.id)
        f.parentFile?.mkdirs()
        f.writeText(render(sys))
        f
    }

    fun delete(systemId: String): Boolean = runCatching { fileFor(systemId).delete() }
        .getOrDefault(false)

    fun render(sys: SystemDef): String = buildString {
        appendLine("# Editado desde el gestor de consolas de la app.")
        appendLine("# Se puede tocar a mano: se relee con \"Reload catalog\".")
        appendLine()
        appendLine("[[system]]")
        appendLine("id = ${q(sys.id)}")
        appendLine("name = ${q(sys.name)}")
        appendLine("label = ${q(sys.label)}")
        if (sys.raName.isNotEmpty()) {
            appendLine("raName = ${q(sys.raName)}   # nombre No-Intro, lo usa el scraper")
        }
        if (sys.raCore.isNotEmpty()) appendLine("raCore = ${q(sys.raCore)}")
        if (sys.videoSnaps.isNotEmpty()) {
            appendLine("videoSnaps = ${q(sys.videoSnaps)}   # coleccion de videos de partida en archive.org")
        }
        appendLine("zipOk = ${sys.zipOk}")
        if (sys.image.isNotEmpty()) appendLine("image = ${q(sys.image)}")
        if (sys.video.isNotEmpty()) appendLine("video = ${q(sys.video)}")
        if (sys.description.isNotEmpty()) appendLine("description = ${q(sys.description)}")
        if (sys.maker.isNotEmpty()) appendLine("maker = ${q(sys.maker)}")
        if (sys.year != 0) appendLine("year = ${sys.year}")
        if (sys.extensions.isNotEmpty()) {
            appendLine("extensions = [${sys.extensions.sorted().joinToString(", ") { q(it) }}]")
        }
        if (sys.aliases.isNotEmpty()) {
            appendLine("aliases = [${sys.aliases.sorted().joinToString(", ") { q(it) }}]")
        }
        // Todo lo de fabrica, tal cual: la entrada del usuario sustituye entera a la del
        // catalogo, y cada campo que no se escribia aqui se perdia al guardar. Primero fue la
        // ficha tecnica; despues la coleccion de videos, el giro, el fabricante y el año —editar
        // Arcade en una consola de pruebas la dejo sin videos—. CatalogWriterTest comprueba ya todos.
        if (sys.specs.isNotEmpty()) {
            appendLine("specs = [${sys.specs.joinToString(", ") { q(it.first + "|" + it.second) }}]")
        }
    }

    // Y los saltos de linea escapados: la descripcion se escribe en una pagina de varias lineas, y
    // un salto literal partia la cadena en dos renglones que el lector no entiende. El fichero
    // entero se saltaba y la consola perdia todo lo editado.
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    /** Comma or space separated text into a clean extension set. */
    fun parseExtensions(text: String): Set<String> =
        text.split(',', ' ', ';', '\n')
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' } }
            .toSet()
}
