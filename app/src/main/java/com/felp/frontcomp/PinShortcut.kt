package com.felp.frontcomp

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

/**
 * Los accesos directos que las apps dejan en la pantalla de inicio, cuando esa pantalla es Ludolog.
 *
 * GameHub, para sacar un juego a la pantalla de inicio, le pide a Android un acceso «anclado», y
 * Android se lo pasa a la app de inicio. Ludolog no sabia recibirlos, asi que GameHub contestaba
 * que faltaba un permiso para crear accesos directos —un permiso que no existe en ningun ajuste:
 * lo que le faltaba era alguien que dijera que si—.
 *
 * Ahora se aceptan y cada uno queda como un fichero `.shortcut` en la carpeta de ROMs de Steam
 * (la de PC), donde sale en la lista como cualquier juego: con su nombre, su caratula por el
 * scraper y sus horas en el cuaderno. El fichero dice de que app es y cual de sus accesos:
 *
 *     package=gamehub.lite
 *     id=<el del acceso>
 *     label=Hollow Knight
 *
 * Y se abre como lo abriria la pantalla de inicio, con LauncherApps: la app lo arranca a su manera
 * (GameHub, con su juego y su configuracion), sin que haga falta saber que lleva dentro. Eso solo
 * lo puede hacer la app de inicio, asi que en un aparato donde Ludolog no lo es, se dice.
 */
internal object PinnedShortcuts {

    const val EXT = "shortcut"

    /** Cuenta los aceptados; la pantalla principal lo mira para repasar la biblioteca al volver. */
    val added = mutableIntStateOf(0)

    data class Entry(val pkg: String, val id: String, val label: String)

    fun read(file: File): Entry? = runCatching {
        val kv = file.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        Entry(kv["package"] ?: return null, kv["id"] ?: return null, kv["label"].orEmpty())
    }.getOrNull()

    fun write(dir: File, e: Entry): File {
        // El nombre del fichero es el titulo en la lista: sin lo que un sistema de ficheros no
        // admite, y sin pisar otro del mismo nombre.
        val base = e.label.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim()
            .ifEmpty { e.id }.take(80)
        var f = File(dir, "$base.$EXT")
        var n = 2
        while (f.exists() && read(f)?.let { it.pkg == e.pkg && it.id == e.id } != true) f = File(dir, "$base ($n).$EXT").also { n++ }
        dir.mkdirs()
        f.writeText("package=${e.pkg}\nid=${e.id}\nlabel=${e.label}\n")
        return f
    }

    /**
     * La carpeta donde van: la de Steam de las carpetas de ROMs, o cualquier otra de PC; si no hay
     * ninguna, `steam` en la primera.
     */
    fun folder(prefs: Prefs, catalog: Catalog?): File? {
        val roots = Scanner.roots(prefs)
        val kids = roots.flatMap { r -> r.listFiles()?.filter { it.isDirectory }.orEmpty() }
        return kids.firstOrNull { it.name.equals("steam", true) }
            ?: kids.firstOrNull { d -> catalog?.forFolder(d.name)?.id == "pc" }
            ?: roots.firstOrNull()?.let { File(it, "steam") }
    }

    /** Lo abre; devuelve un error para el usuario, o nulo si arranco. */
    fun launch(ctx: Context, file: File): String? {
        val e = read(file) ?: return "This shortcut file is damaged."
        val apps = ctx.getSystemService(LauncherApps::class.java)
        if (!apps.hasShortcutHostPermission()) return "Shortcuts open only while Ludolog is the home app."
        return runCatching { apps.startShortcut(e.pkg, e.id, null, null, Process.myUserHandle()); null }
            .getOrElse { "The app no longer has this shortcut. Create it again from the app." }
    }
}

/**
 * Lo que Android abre cuando una app pide anclar un acceso y Ludolog es la de inicio. Sin pantalla:
 * acepta, guarda el fichero, lo dice en un aviso y se va.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val msg = runCatching {
            val apps = getSystemService(LauncherApps::class.java)
            val req = apps.getPinItemRequest(intent) ?: return@runCatching null
            if (req.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) return@runCatching null
            val info = req.shortcutInfo ?: return@runCatching null
            val prefs = Prefs(this)
            val catalog = CatalogLoader.current
            val dir = PinnedShortcuts.folder(prefs, catalog) ?: return@runCatching "No ROM folder to save the shortcut in."
            val label = (info.longLabel ?: info.shortLabel)?.toString().orEmpty()
            // Primero se acepta: si la app lo retira, no queda un fichero que no abre nada.
            if (!req.accept()) return@runCatching "The app withdrew the shortcut."
            val file = PinnedShortcuts.write(dir, PinnedShortcuts.Entry(info.`package`, info.id, label))
            PinnedShortcuts.added.intValue++
            "Added to ${dir.name}: ${file.nameWithoutExtension}"
        }.getOrElse { "Could not add the shortcut: ${it.javaClass.simpleName}" }
        msg?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        finish()
    }
}
