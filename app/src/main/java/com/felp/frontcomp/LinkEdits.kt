package com.felp.frontcomp

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import java.io.File

/**
 * Las correcciones que viajan entre devices con Ludolog Link: el nombre, la descripcion y el genero
 * de un juego, y el nombre y la descripcion de una consola.
 *
 * Al corregir algo aqui se apunta en `<datos>/link/edits.tsv`, con su hora, y se avisa a Link
 * (EDITS_CHANGED). Link lo lleva a las otras consolas y al PC; de dos ediciones del mismo campo
 * gana la ultima. Lo que llega de otras lo deja Link en `<datos>/link/edits-in.json` y avisa
 * (EDITS_IN): se aplica aqui sin volver a apuntarlo. Sin Link, unas lineas que nadie lee, y como
 * mucho [KEEP]. Ver docs/ludolog-link.md.
 *
 * Linea: `instante <tab> game|sys <tab> consola <tab> ruta <tab> claves <tab> campo <tab> valor`.
 * La ruta, vacia en una consola. Las claves, las del fichero (su ficha, Dossiers): con ellas Link
 * reconoce el mismo juego en otra consola aunque se llame distinto. Campo: name, desc o genre.
 * Valor vacio: se quito, vuelve al del catalogo.
 */
internal object LinkEdits {
    private const val KEEP = 2000
    private const val LINK_PACKAGE = BuildConfig.LINK_PACKAGE
    private const val PERMISSION = BuildConfig.APPLICATION_ID + ".permission.LINK"
    const val ACTION_EDITS_CHANGED = "com.felp.frontcomp.link.EDITS_CHANGED"

    /** Una correccion de un juego hecha aqui. */
    fun game(ctx: Context, game: Game, field: String, value: String?) {
        val keys = runCatching { Dossiers.of(game.systemId)[game.path]?.keys.orEmpty().joinToString(" ") }.getOrDefault("")
        write(ctx, listOf(game.systemId, game.path, keys, field, value.orEmpty()), "game")
    }

    /** La descripcion de una consola ([field] `desc`) o su nombre (`name`), hechos aqui. */
    fun system(ctx: Context, systemId: String, value: String?, field: String = "desc") =
        write(ctx, listOf(systemId, "", "", field, value.orEmpty()), "sys")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun write(ctx: Context, parts: List<String>, kind: String) {
        runCatching {
            val log = DataHome.file("link/edits.tsv")
            log.parentFile?.mkdirs()
            log.appendText((listOf(System.currentTimeMillis().toString(), kind) + parts.map(::clean)).joinToString("\t") + "\n")
            // Recortar de vez en cuando, no en cada linea.
            if (log.length() > 400_000) {
                val lines = log.readLines().takeLast(KEEP)
                val tmp = File(log.path + ".part")
                tmp.writeText(lines.joinToString("\n", postfix = "\n"))
                if (!tmp.renameTo(log)) { log.delete(); tmp.renameTo(log) }
            }
        }
        runCatching { ctx.sendBroadcast(Intent(ACTION_EDITS_CHANGED).setPackage(LINK_PACKAGE), PERMISSION) }
    }

    /**
     * Lo que dejo Link de otras consolas (EDITS_IN), ya con la ruta del juego aqui: se aplica sin
     * apuntarlo de nuevo. Fuera del hilo de la interfaz. Verdadero si cambio algo.
     */
    fun applyIncoming(ctx: Context): Boolean {
        val f = DataHome.file("link/edits-in.json")
        if (!f.isFile) return false
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull()
        f.delete()
        if (arr == null) return false
        val prefs = Prefs(ctx)
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val system = o.optString("system")
            val path = o.optString("path")
            val value = o.optString("value").replace(' ', '\n').trim().ifEmpty { null }
            val done = runCatching {
                when (o.optString("kind") to o.optString("field")) {
                    "game" to "name" -> prefs.setGameTitleAt(path, value)
                    "game" to "desc" -> prefs.setDescription("game", path, value)
                    "game" to "genre" -> Dossiers.setOwn(system, path, "my.genre", value)
                    "sys" to "desc" -> prefs.setDescription("sys", system, value)
                    "sys" to "name" -> prefs.setSystemName(system, value)
                    else -> return@runCatching false
                }
                true
            }.getOrDefault(false)
            changed = changed || done
        }
        return changed
    }
}
