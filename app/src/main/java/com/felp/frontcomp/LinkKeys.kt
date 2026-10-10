package com.felp.frontcomp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Las claves de las fuentes de arte (IGDB) para Ludolog Link (10-10-2026): con escribirlas en un
 * aparato basta, y Link las pasa al PC y a las consolas emparejadas. Ver Prefs.sharedCredentials.
 *
 * Solo para Link: el permiso de firma del manifiesto NO cubre `call()` (Android solo lo mira en
 * query, insert y demas), asi que se comprueba aqui, y ademas que quien llama sea su paquete.
 * Cualquier otra app recibe un SecurityException.
 *
 * - `get`: las claves y cuando se puso cada una (`keys`, y `v.<clave>` y `t.<clave>` de cada una).
 * - `put`: las que llegan de otro aparato, en la misma forma. Cada una se toma solo si es mas
 *   nueva; devuelve cuantas cambio (`changed`).
 *
 * Es un CONTRATO con otra app: ver docs/ludolog-link.md antes de cambiarlo.
 */
class LinkKeys : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED ||
            callingPackage != BuildConfig.LINK_PACKAGE
        ) throw SecurityException("only Ludolog Link")
        val secrets = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return when (method) {
            "get" -> Bundle().apply {
                val all = Prefs.sharedCredentials(secrets)
                putStringArray("keys", all.keys.toTypedArray())
                for ((k, e) in all) { putString("v.$k", e.first); putLong("t.$k", e.second) }
            }
            "put" -> {
                val b = extras ?: Bundle()
                val incoming = b.getStringArray("keys").orEmpty().associateWith { k ->
                    b.getString("v.$k").orEmpty() to b.getLong("t.$k")
                }
                val n = Prefs.importCredentials(secrets, incoming)
                // Sin los valores: solo cuantas.
                if (n > 0) android.util.Log.i("Ludolog", "art source keys from Ludolog Link: $n")
                Bundle().apply { putInt("changed", n) }
            }
            else -> null
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object {
        const val PERMISSION = BuildConfig.APPLICATION_ID + ".permission.LINK"
    }
}
