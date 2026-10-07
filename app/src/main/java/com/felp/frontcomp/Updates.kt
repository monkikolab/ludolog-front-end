package com.felp.frontcomp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import java.net.HttpURLConnection
import java.net.URL

/**
 * Si hay una version nueva de Ludolog publicada en GitHub (07-10-2026).
 *
 * Ludolog se instala fuera de la tienda, asi que nadie avisa de las versiones nuevas. Como mucho una
 * vez al dia, al arrancar, se mira el ultimo release del repositorio; si es mas nuevo que esta copia,
 * se avisa una vez abajo y lo dice About. Solo avisa: no baja ni instala nada. Lo unico que sale es
 * la peticion a GitHub, y se apaga en Settings → Data → Check for updates.
 *
 * Ludolog Link hace lo mismo con su repositorio (ReleaseCheck, en su kit): son dos programas y no
 * comparten codigo de Android.
 */
internal object Updates {
    const val PAGE = "https://github.com/monkikolab/ludolog-front-end/releases/latest"
    private const val API = "https://api.github.com/repos/monkikolab/ludolog-front-end/releases/latest"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** La ultima version publicada que se conoce, o null si todavia no se sabe. */
    val latest = mutableStateOf<String?>(null)

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    /** a es mas nueva que b, numero a numero: «0.10.0» va despues de «0.9.3». */
    fun newer(a: String, b: String): Boolean {
        val x = nums(a)
        val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun nums(v: String) =
        v.trim().removePrefix("v").split('.', '-').mapNotNull { p -> p.takeWhile(Char::isDigit).toIntOrNull() }

    /**
     * La version del ultimo release, preguntando a GitHub, y guardada como la ultima conocida.
     * Bloquea: fuera del hilo de la pantalla. Sin red, o sin nada publicado (404), falla.
     */
    fun fetch(prefs: Prefs): Result<String> = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Ludolog")
        try {
            when (c.responseCode) {
                200 -> {}
                404 -> error("nothing published yet")
                else -> error("GitHub answered ${c.responseCode}")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.removePrefix("v")
                ?: error("no version in GitHub's answer")
        } finally {
            c.disconnect()
        }
    }.onSuccess {
        prefs.updateLatest = it
        latest.value = it
    }.also { prefs.updateCheckedAt = System.currentTimeMillis() }

    /**
     * Al arrancar: pregunta como mucho una vez al dia, y solo si esta encendido. Devuelve el aviso
     * que hay que ensenar abajo, una sola vez por version, o null. Bloquea.
     */
    fun daily(ctx: Context, prefs: Prefs): String? {
        if (!prefs.updateCheck) return null
        latest.value = prefs.updateLatest
        if (System.currentTimeMillis() - prefs.updateCheckedAt >= DAY_MS) fetch(prefs)
        val v = prefs.updateLatest ?: return null
        if (!newer(v, current(ctx)) || prefs.updateNotified == v) return null
        prefs.updateNotified = v
        return "Ludolog $v is available: Settings → Data → About."
    }

    /** La pagina del ultimo release, en el navegador. */
    fun open(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
