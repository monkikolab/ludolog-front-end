package com.felp.frontcomp

import android.content.Context
import java.io.File

/**
 * Los cuadernos del Companion, uno por consola, en `<datos>/companion/`.
 *
 * Cada consola escribe solo el suyo, que se llama como ella mas un ID de cuatro letras sacado al
 * azar la primera vez: «HandheldA-c050.db», igual que en RetroCompanion. Dos consolas del mismo
 * modelo se llaman igual, y el ID es lo que separa sus ficheros en una misma carpeta. Los que
 * lleguen de otras consolas se quedan al lado, tal cual, y nadie los escribe: compartir el
 * registro es compartir esta carpeta, sin fundir nada.
 *
 * Hasta el 26-09-2026 era un solo `logbook.db` en la raiz de la carpeta de datos. El primero que
 * pide el suyo lo muda aqui con su nombre nuevo: ver [settle].
 */
internal object Logbooks {
    const val DIR = "companion"

    /** Como se llamaba cuando era uno solo. */
    const val OLD = "logbook.db"

    val dir: File get() = DataHome.file(DIR)

    @Volatile private var cached: File? = null
    @Volatile private var cachedFor: File? = null

    /**
     * El de esta consola. La primera vez saca el ID y muda el cuaderno de antes, asi que tiene
     * que pasar antes de abrir ninguno: por eso todo el que abre el cuaderno pide aqui la ruta.
     */
    @Synchronized
    fun own(): File {
        val home = DataHome.dir
        if (cachedFor == home) cached?.let { return it }
        val ctx = DataHome.context
        val prefs = Prefs(ctx)
        val model = model()
        val me = owner(ctx)
        // El ID de la carpeta, si se saco en este aparato. Una carpeta copiada de otra consola
        // trae el de aquella, y usarlo seria escribir en su cuaderno: esta saca uno propio, y el
        // de la otra se queda al lado como lo que es.
        val id = prefs.consoleId?.takeIf { prefs.consoleOwner == me } ?: newId(model).also {
            prefs.consoleId = it
            prefs.consoleOwner = me
            android.util.Log.i("Ludolog", "cuaderno: esta consola es ${fileName(model, it)}")
        }
        val file = settle(File(home, OLD), File(File(home, DIR), fileName(model, id)))
        cached = file
        cachedFor = home
        return file
    }

    /**
     * Lo crea ya, con sus tablas: al aceptar el Companion en la bienvenida o encenderlo en los
     * ajustes. Si no, no existia hasta la primera partida, y la carpeta no decia de quien era.
     */
    fun create() {
        runCatching { Logbook(DataHome.context).use { it.writableDatabase } }
            .onFailure { android.util.Log.w("Ludolog", "cuaderno: no se pudo crear (${it.javaClass.simpleName})") }
    }

    /**
     * Los de las otras consolas: los `.db` de la carpeta que no son el de esta. Los que empiezan
     * por punto son de alguien a medio escribir, y se dejan.
     */
    fun others(): List<File> {
        val mine = own()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".db") && !f.name.startsWith(".") && f != mine }
            ?.sortedBy { it.name }.orEmpty()
    }

    /**
     * El de otra consola, listo para leerlo: una copia en la cache de la aplicacion, que se
     * rehace cuando el suyo cambia.
     *
     * Leer el suyo tal cual es arriesgado para el. SQLite, al abrir un fichero con una escritura
     * a medias, la deshace escribiendo; y ese fichero es de otra consola, que es la unica que
     * lo escribe. Ademas puede estar llegando en ese momento por la sincronizacion. La copia
     * cuesta nada: son unos cientos de KB.
     */
    fun readable(other: File): File {
        val copy = File(File(DataHome.context.cacheDir, DIR), other.name)
        if (copy.length() == other.length() && copy.lastModified() == other.lastModified()) return copy
        copy.parentFile?.mkdirs()
        val part = File(copy.path + ".part")
        other.copyTo(part, overwrite = true)
        if (!part.renameTo(copy)) { part.copyTo(copy, overwrite = true); part.delete() }
        copy.setLastModified(other.lastModified())
        return copy
    }

    /** Como se llama esta consola: su modelo, lo mismo que se apunta en cada partida. */
    fun model(): String = android.os.Build.MODEL.orEmpty().ifEmpty { "handheld" }

    /**
     * El nombre del fichero de una consola, como en RetroCompanion: el modelo sin nada que no
     * valga en un nombre de fichero, un guion y el ID. «Handheld Model 5-dbed.db».
     */
    fun fileName(model: String, id: String): String =
        model.replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "console" } + "-$id.db"

    /**
     * Un ID nuevo: cuatro letras de un UUID, como en RetroCompanion. Que no sea el de un fichero
     * que ya este en la carpeta, llegado de otra consola del mismo modelo.
     */
    fun newId(model: String, taken: (String) -> Boolean = { File(dir, fileName(model, it)).exists() }): String {
        while (true) {
            val id = java.util.UUID.randomUUID().toString().take(4)
            if (!taken(id)) return id
        }
    }

    /**
     * La huella de este aparato: su ANDROID_ID resumido en ocho letras. No sale de la carpeta de
     * datos ni sirve para reconocer a nadie; solo dice si el ID guardado se saco aqui.
     *
     * Android lo da distinto para cada clave de firma: pasar de la version de prueba a la
     * firmada le da a la consola un ID nuevo, y su cuaderno de antes queda al lado, como el de
     * otra consola. Se lee igual; solo cambia de nombre lo que se apunte desde entonces.
     */
    fun owner(ctx: Context): String {
        val raw = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * Donde esta el cuaderno de esta consola, mudando el de antes si hace falta.
     *
     * Renombrar y no copiar: en la misma tarjeta es instantaneo, y no hay un momento con dos
     * copias ni con ninguna. Solo con los diarios vacios, que es como SQLite los deja al acabar
     * cada escritura. Con algo dentro, una escritura quedo a medias, y solo SQLite sabe
     * deshacerla, al abrir el fichero donde esta: se usa el de siempre, y se muda en el arranque
     * siguiente. Si no se puede renombrar, tambien el de siempre: el nombre es lo de menos, lo de
     * dentro es lo que no se puede perder.
     */
    fun settle(old: File, wanted: File): File {
        if (!old.isFile) return wanted
        // Ya tiene el suyo: el de antes se deja como esta, que alguien lo habra traido a mano.
        if (wanted.exists()) return wanted
        val sides = SIDES.map { File(old.path + it) }
        if (sides.any { it.length() > 0 }) return old
        wanted.parentFile?.mkdirs()
        if (!old.renameTo(wanted)) return old
        for (s in sides) if (s.exists()) s.renameTo(File(wanted.path + s.name.removePrefix(old.name)))
        return wanted
    }

    // ------------------------------------------------------------------ importar

    /** El ID en el nombre de un cuaderno: «c050» en «Retroid Pocket 5-c050.db». */
    private val ID_IN_NAME = Regex("""-([A-Za-z0-9]{4})(?: \(\d+\))?\.db$""")

    /** Lo que quedo al importar: el nombre del cuaderno en la carpeta y cuantas partidas trae. */
    class Imported(val name: String, val sessions: Int)

    /**
     * Hace del cuaderno [name], leido de [input], el de esta consola (opciones del Companion → Import
     * logbook; idea del usuario, 07-10-2026): quien elige un cuaderno lo da por suyo, y la consola
     * hereda su ID. Para cuando cambia la huella de [owner] —otra clave de firma, o el aparato
     * restablecido—, que abre un cuaderno nuevo y deja el de siempre como si fuera de otra consola;
     * y para seguir en una consola nueva el cuaderno de la anterior.
     *
     * Se copia a la carpeta con el modelo de esta consola y ese ID; si ya esta alli igual, no se
     * copia. El que se usaba hasta ahora se borra si no tiene ninguna partida, y si tiene se queda al
     * lado, como el de otra consola. Nunca se pisa un cuaderno distinto. Devuelve el nombre con que
     * queda. Bloquea; despues hay que reabrir el programa (restartApp).
     */
    @Synchronized
    fun adopt(input: java.io.InputStream, name: String): Result<Imported> = runCatching {
        // Al lado de cada cuaderno puede haber su fichero de paso de SQLite, vacio, con casi el mismo
        // nombre; en un selector de ficheros se confunden. Se dice cual hay que elegir.
        if (Regex(""".db-(journal|wal|shm)$""").containsMatchIn(name))
            error("that is the logbook's scratch file, not the logbook: pick ${name.substringBefore(".db-")}.db, next to it")
        val id = ID_IN_NAME.find(name)?.groupValues?.get(1)?.lowercase()
            ?: error("its name doesn't say which console it is, like «Retroid Pocket 5-c050.db»")
        val tmp = File(DataHome.work(), "import-logbook.db")
        try {
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val count = sessions(tmp) ?: error("it is not a Companion logbook")
            val current = own()
            val dest = File(dir, fileName(model(), id))
            when {
                // Ya es el suyo: igual no hace falta nada; distinto seria pisar lo apuntado aqui.
                dest == current -> if (!same(dest, tmp)) error("this console already writes in ${dest.name}")
                dest.exists() -> if (!same(dest, tmp)) error("${dest.name} is already in the folder, with other sessions")
                else -> { dir.mkdirs(); tmp.copyTo(dest) }
            }
            // El de hasta ahora: sin partidas no dice nada, y se quita; con partidas se queda al lado.
            if (current != dest && current.isFile && sessions(current) == 0) {
                (listOf("") + SIDES).forEach { File(current.path + it).delete() }
            }
            val prefs = Prefs(DataHome.context)
            prefs.consoleId = id
            prefs.consoleOwner = owner(DataHome.context)
            cached = null
            cachedFor = null
            Imported(dest.name, count)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Una copia del cuaderno de esta consola en [to], el sitio que se eligio en el selector de
     * Android (abre en Download; pedido del usuario, 07-10-2026: antes iba siempre ahi). Con su
     * nombre y por tanto su ID: para guardarla o llevarla a otro aparato, y volver a traerla con
     * [adopt]. Desde las opciones del Companion no hay partida en curso, asi que el fichero esta
     * entero. Devuelve el nombre con que quedo. Bloquea.
     */
    fun export(ctx: Context, to: android.net.Uri): Result<String> = runCatching {
        val f = own()
        if (!f.isFile) error("there is no logbook yet")
        val r = ctx.contentResolver
        r.openOutputStream(to, "wt").use { out -> f.inputStream().use { it.copyTo(out ?: error("that place can't be written")) } }
        // El nombre con que quedo: el selector puede haberle sumado « (1)» si ya habia uno igual.
        r.query(to, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: f.name
    }

    /** Cuantas partidas tiene un cuaderno, o nulo si no es uno. */
    private fun sessions(f: File): Int? = runCatching {
        android.database.sqlite.SQLiteDatabase.openDatabase(f.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM sessions", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
    }.getOrNull()

    private fun same(a: File, b: File): Boolean =
        a.length() == b.length() && GameDb.sha1(a.readBytes()) == GameDb.sha1(b.readBytes())

    private val SIDES = listOf("-journal", "-wal", "-shm")
    private val UNSAFE = Regex("""[^A-Za-z0-9 _.-]""")
}
