package com.felp.frontcomp

import org.json.JSONObject
import java.io.File

/**
 * Ajustes cambiados desde el PC con Ludolog Link, que Ludolog aplica ELLA MISMA.
 *
 * Ludolog no depende de Link para nada: sin Link este archivo no existe y aqui no pasa nada. Link
 * deja los cambios en `<datos>/link/config-update.json` y avisa (LinkBridge, CONFIG_CHANGED); si el
 * aviso no llega —Ludolog cerrada—, se aplican al arrancar (LudologApp). Nunca se escribe el
 * config.xml desde fuera: lo tiene ConfigFile en memoria y lo pisaria. Se aplica a traves de el.
 *
 * Formato: `{"set": {"clave": {"t": "string|int|long|float|boolean|set", "v": valor}}, "remove": ["clave"]}`.
 * Es un CONTRATO con otra app: ver docs/ludolog-link.md antes de cambiarlo.
 */
internal object LinkConfig {

    private fun pendingFile(): File = DataHome.file("link/config-update.json")

    /** Lo que nunca llega de fuera: las credenciales y la identidad de esta consola. */
    private fun allowed(key: String) =
        !key.startsWith(Prefs.SECRET) && key != "log.console.id" && key != "log.console.owner"

    /** Aplica lo pendiente, si lo hay. Devuelve si cambio algo. */
    @Synchronized
    fun applyPending(): Boolean {
        val pending = pendingFile()
        if (!pending.isFile) return false
        // Primero se aparta: si Link deja otro mientras se aplica este, no se pierde.
        val taken = File(pending.parentFile, "config-update.applying")
        if (!pending.renameTo(taken)) return false
        val j = runCatching { JSONObject(taken.readText()) }.getOrNull()
        if (j == null) { taken.delete(); return false }
        val e = ConfigFile.open().edit()
        var changed = false
        j.optJSONObject("set")?.let { set ->
            for (k in set.keys()) {
                if (!allowed(k)) continue
                val o = set.optJSONObject(k) ?: continue
                runCatching {
                    when (o.optString("t")) {
                        "string" -> e.putString(k, o.getString("v"))
                        "int" -> e.putInt(k, o.getInt("v"))
                        "long" -> e.putLong(k, o.getLong("v"))
                        "float" -> e.putFloat(k, o.getDouble("v").toFloat())
                        "boolean" -> e.putBoolean(k, o.getBoolean("v"))
                        "set" -> e.putStringSet(k, o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                        else -> return@runCatching
                    }
                    changed = true
                }
            }
        }
        j.optJSONArray("remove")?.let { r ->
            for (i in 0 until r.length()) r.optString(i).takeIf { it.isNotEmpty() && allowed(it) }?.let { e.remove(it); changed = true }
        }
        e.commit()
        taken.delete()
        return changed
    }
}
