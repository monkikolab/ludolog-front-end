package com.felp.frontcomp

import java.io.File

/**
 * Un respaldo que Ludolog Link devuelve desde el PC, y que Ludolog pone en su sitio ELLA MISMA.
 *
 * Los cuadernos estan abiertos mientras Ludolog vive, y cambiarle a SQLite el fichero por debajo
 * lo corrompe. Por eso Link no toca nada de la carpeta de datos: deja los archivos en
 * `<datos>/link/restore/` con su ruta de siempre y, cuando estan todos, el testigo `.ready`.
 * Ludolog los mueve a su sitio al nacer el proceso (LudologApp), antes de que nadie abra nada;
 * el aviso (LinkBridge, RESTORE) solo pide ese reinicio, que espera a que no haya partida.
 *
 * Sin el testigo no se aplica nada: una subida cortada a medias no deja una mezcla. Los ajustes
 * no van aqui: van por LinkConfig, a traves de ConfigFile.
 * Es un CONTRATO con otra app: ver docs/ludolog-link.md antes de cambiarlo.
 */
internal object LinkRestore {

    private fun dir(): File = DataHome.file("link/restore")
    private fun ready(): File = File(dir(), ".ready")

    /** Hay un respaldo entero esperando a que Ludolog se reinicie. */
    fun pending(): Boolean = ready().isFile

    /** Lo unico que se puede devolver: lo esencial del respaldo, menos config.xml. */
    private fun allowed(rel: String) =
        rel == "systems.toml" || rel == "tvs.toml" ||
            (rel.startsWith("companion/") && rel.endsWith(".db") && rel.count { it == '/' } == 1) ||
            rel.startsWith("dossiers/") || rel.startsWith("systems/")

    /** Pone en su sitio lo que espera, si esta entero. Devuelve si habia algo. */
    @Synchronized
    fun applyPending(): Boolean {
        val d = dir()
        if (!ready().isFile) return false
        val base = DataHome.file(".").canonicalFile
        try {
            d.walkTopDown().filter { it.isFile && it.name != ".ready" }.forEach { f ->
                val rel = f.relativeTo(d).invariantSeparatorsPath
                if (!allowed(rel) || rel.split('/').any { it.isEmpty() || it.startsWith(".") }) return@forEach
                val target = DataHome.file(rel)
                if (!target.canonicalPath.startsWith(base.path + File.separator)) return@forEach
                runCatching { put(f, target, isDb = rel.endsWith(".db")) }
                    .onFailure { android.util.Log.w("Ludolog", "respaldo de Link: $rel: ${it.message}") }
            }
        } finally {
            // Aplicado o no, se va: un respaldo a medio poner no se reintenta en cada arranque.
            d.deleteRecursively()
        }
        return true
    }

    /** [f] en lugar de [target]. El viejo se aparta primero y vuelve si el nuevo no entra. */
    private fun put(f: File, target: File, isDb: Boolean) {
        target.parentFile?.mkdirs()
        // El diario de otro fichero, aplicado a este, lo romperia: fuera los del viejo.
        if (isDb) for (tail in listOf("-journal", "-wal", "-shm")) File(target.path + tail).delete()
        val old = File(target.path + ".link-old")
        old.delete()
        if (target.exists() && !target.renameTo(old)) return
        if (f.renameTo(target) || runCatching { f.copyTo(target, overwrite = true); true }.getOrDefault(false)) old.delete()
        else { target.delete(); old.renameTo(target) }
    }
}
