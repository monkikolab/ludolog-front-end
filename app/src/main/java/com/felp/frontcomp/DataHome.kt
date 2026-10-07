package com.felp.frontcomp

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * La carpeta de datos: todo lo que el programa guarda y tiene que sobrevivir a desinstalarlo.
 *
 *     <unidad>/Ludolog/
 *         config.xml      los ajustes
 *         companion/      los cuadernos del Companion: el de esta consola, «HandheldA-c050.db»,
 *                         y los que lleguen de otras (ver Logbooks)
 *         themes/<id>/    los temas instalados
 *         media/          lo escrapeado, y lo comun a todos los temas
 *         cache/          los indices de las fuentes de arte
 *         icons/  systems/  systems.toml  tvs.toml
 *
 * Una sola, y elegida la primera vez que se abre. Antes era `FrontComp` en la memoria interna,
 * escrita a mano en diez sitios, y los ajustes en las preferencias privadas: desinstalar se
 * llevaba la configuracion por delante, y pasar a otro aparato era copiar media carpeta y
 * rehacer el resto a mano. Con todo junto, llevarse la carpeta es llevarse el programa.
 *
 * Fuera se quedan dos cosas, a proposito. Las credenciales de las fuentes de arte: en la
 * tarjeta las leeria cualquier aplicacion con permiso de archivos, y en las preferencias
 * privadas solo esta (ver Prefs). Y donde esta la carpeta, que es lo unico que no puede ir
 * dentro de ella: una linea, tambien en las privadas.
 */
object DataHome {
    /**
     * El nombre de la carpeta, que es el del programa. Lo busca por este mismo nombre
     * `install.sh`, el instalador de temas. La copia de prueba (`fresh`) usa otro, para no
     * encontrarse la carpeta de la instalacion de siempre y darla por suya.
     */
    val NAME: String = BuildConfig.DATA_NAME

    /**
     * Como se llamaba mientras el programa no tuvo nombre. Una carpeta elegida con este nombre
     * se renombra al arrancar: ver [renamed].
     */
    private const val PROVISIONAL = "FrontEnd_prototype"

    /** Los ajustes, dentro de la carpeta. Que exista es lo que dice que la carpeta ya se uso. */
    const val CONFIG = "config.xml"

    /**
     * Donde estuvo todo hasta que la carpeta se pudo elegir. Solo se lee, para copiarlo. La copia
     * de prueba no lo mira: es una primera instalacion, y no hay nada de antes que traer.
     */
    val LEGACY = File(if (BuildConfig.IMPORT_LEGACY) "/storage/emulated/0/FrontComp" else "/nonexistent/FrontComp")

    /**
     * Lo que se trae de la vieja: lo que el programa usa, y nada mas. Alli hay tambien copias
     * de seguridad hechas a mano, que no son del programa y no tiene por que llevarse.
     */
    private val CARRIED = listOf(
        "themes", "media", "cache", "icons", "systems",
        "systems.toml", "tvs.toml", "logbook.db", "logbook.db-journal",
    )

    private const val BOOT = "boot"
    private const val KEY = "data.root"
    private const val SETUP = "setup.step"

    private lateinit var app: Context

    /** El de la aplicacion, para lo que lo pida sin tener uno a mano: ver Logbooks. */
    internal val context: Context get() = app

    @Volatile
    private var root: File? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        root = boot().getString(KEY, null)?.let(::File)?.let(::renamed)
    }

    /**
     * La carpeta del nombre provisional, con el nombre del programa: se renombra en su sitio.
     *
     * Renombrar y no copiar: en la misma tarjeta es un cambio de nombre, instantaneo, y no hay un
     * momento con dos copias ni uno sin ninguna. Antes que nada la abra —esto corre al nacer el
     * proceso—, porque renombrar una carpeta con ficheros abiertos dentro puede fallar. Y si no se
     * puede —sin permiso aun, o ya hay una carpeta con el nombre nuevo al lado—, se sigue usando
     * la de siempre: el nombre es lo de menos, lo de dentro es lo que no se puede perder.
     */
    private fun renamed(old: File): File {
        if (old.name != PROVISIONAL || !old.isDirectory) return old
        val new = File(old.parentFile, NAME)
        if (new.exists() || !hasStorage()) return old
        if (!old.renameTo(new)) return old
        boot().edit().putString(KEY, new.path).commit()
        android.util.Log.i("Ludolog", "datos: ${old.path} -> ${new.path}")
        return new
    }

    private fun boot() = app.getSharedPreferences(BOOT, Context.MODE_PRIVATE)

    /** La elegida, este a mano o no. Null hasta que se elija. */
    val chosen: File? get() = root

    /** Si se puede arrancar: con permiso, con carpeta elegida y con esa carpeta a mano. */
    fun ready(): Boolean = hasStorage() && root?.isDirectory == true

    /**
     * El paso de la bienvenida que queda por hacer despues de la carpeta de datos —«roms» o
     * «permissions»—, o nulo si no queda ninguno. Ver DataSetup.
     *
     * Se apunta aqui, en las privadas y con commit, para que un cierre a mitad no se la salte:
     * al volver se sigue donde se estaba. Y solo lo pone una primera vez de verdad (ver
     * [adopt]): quien ya tenia el programa andando no ve la bienvenida por actualizarlo.
     */
    var setupStep: String?
        get() = boot().getString(SETUP, null)
        set(v) {
            boot().edit().apply { if (v == null) remove(SETUP) else putString(SETUP, v) }.commit()
        }

    /**
     * De donde cuelga todo.
     *
     * Antes de elegir, una carpeta privada que no ve nadie: si algo escribiera antes de
     * tiempo, que no deje una carpeta a medias en la tarjeta de la persona.
     */
    val dir: File get() = root ?: File(app.filesDir, "unset")

    fun file(name: String): File = File(dir, name)

    /**
     * Las unidades montadas: la memoria interna y cada tarjeta, la interna primero.
     *
     * Se le preguntan a Android. Mirar /storage a pelo era lo que hacian el escaner, los medios y
     * las listas de ES-DE, y en Android 15 una app ya no puede listar esa carpeta: en una consola de pruebas la
     * tarjeta no aparecia nunca y la biblioteca se quedaba en lo que hubiera en la memoria interna
     * —dos juegos de PICO-8—. Lo de antes se queda detras, por si algun aparato no las declara.
     */
    fun volumes(): List<File> {
        val declared = runCatching {
            app.getSystemService(StorageManager::class.java).storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { it.directory }
        }.getOrDefault(emptyList())
        val listed = File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name !in setOf("emulated", "self") }.orEmpty()
        val seen = HashSet<String>()
        return (listOf(File("/storage/emulated/0")) + declared + listed)
            .filter { seen.add(runCatching { it.canonicalPath }.getOrDefault(it.path).lowercase()) }
    }

    /**
     * Donde trabajar con ficheros de paso: la memoria interna del programa, no la tarjeta.
     *
     * Volver a codificar un video escribe miles de trozos pequeños, y en la tarjeta —detras del
     * sistema de ficheros de Android— cada uno puede tardar: medido con MediaMuxer, el mismo
     * video tardaba 57 ms unas veces y 90 s otras. Aqui se hace el trabajo y a la tarjeta va
     * solo el resultado, de una copia seguida.
     */
    fun work(): File = File(app.cacheDir, "work").also { it.mkdirs() }

    /** Un sitio donde puede ir la carpeta: una unidad montada, con la carpeta ya en su ruta. */
    data class Place(val label: String, val dir: File, val freeBytes: Long)

    /**
     * Las unidades montadas, la tarjeta primero.
     *
     * Primero porque es la que se propone: en estas consolas la tarjeta es donde cabe una
     * biblioteca, y lo que el programa guarda —caratulas, videos, temas— crece con ella.
     */
    fun places(): List<Place> =
        app.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED && it.directory != null }
            .sortedBy { !it.isRemovable }
            .map { v ->
                val volume = v.directory!!
                Place(
                    label = if (v.isRemovable) "SD CARD" else "INTERNAL STORAGE",
                    dir = File(volume, NAME),
                    freeBytes = volume.usableSpace,
                )
            }

    /**
     * Se queda con esa carpeta, trayendo lo de `FrontComp` si la carpeta empieza de cero.
     *
     * COPIA y no mueve: lo viejo se queda donde estaba hasta que alguien lo borre sabiendo que
     * ya esta aqui. Y no pisa nada. Una carpeta con sus ajustes dentro ya es de alguien —se
     * reinstalo el programa, o se trajo de otro aparato— y se usa tal cual, sin copiar.
     *
     * Cada fichero va primero a un `.part` y se renombra al terminar. Si la copia se corta —la
     * tarjeta llena, la bateria— el siguiente intento no confunde un fichero a medias con uno
     * ya copiado.
     *
     * La eleccion se apunta lo ULTIMO y con commit: hasta entonces, cortar a medias es volver a
     * empezar, no quedarse con una carpeta a medio llenar dando por hecho que esta completa.
     */
    fun adopt(target: File, onProgress: (done: Int, total: Int) -> Unit): Result<Unit> =
        runCatching {
            // Primera vez: quedan los otros dos pasos de la bienvenida. Con una carpeta ya
            // elegida que no estaba —la tarjeta fuera— no: eso es volver a encontrarla.
            val first = root == null
            if (!target.isDirectory && !target.mkdirs()) error("Cannot create ${target.path}")
            val config = File(target, CONFIG)
            if (!config.exists()) {
                if (LEGACY.isDirectory) {
                    val files = CARRIED.map { File(LEGACY, it) }
                        .filter { it.exists() }
                        .flatMap { top -> top.walkTopDown().filter { it.isFile }.toList() }
                    files.forEachIndexed { i, from ->
                        val to = File(target, from.relativeTo(LEGACY).path)
                        if (!to.exists()) {
                            to.parentFile?.mkdirs()
                            val part = File(to.path + ".part")
                            from.copyTo(part, overwrite = true)
                            if (!part.renameTo(to)) error("Cannot write ${to.path}")
                        }
                        onProgress(i + 1, files.size)
                    }
                }
                ConfigFile.seed(config, app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE))
            }
            if (first) setupStep = STEP_ROMS
            if (!boot().edit().putString(KEY, target.path).commit()) error("Cannot save the choice")
            root = target
            // Los ajustes, ya del fichero de esta carpeta: ver ConfigFile.reopen.
            ConfigFile.reopen()
        }

    /**
     * Si el proximo arranque es el primero de verdad, recien acabada la bienvenida: entonces el
     * repaso de la biblioteca sigue solo con el catalogo y el scraper, en segundo plano. Se pone
     * al terminar la bienvenida y se quita al empezar esa primera pasada.
     */
    var firstRun: Boolean
        get() = boot().getBoolean(FIRST_RUN, false)
        set(v) { boot().edit().putBoolean(FIRST_RUN, v).commit() }

    private const val FIRST_RUN = "first.run"

    const val STEP_ROMS = "roms"
    const val STEP_PERMISSIONS = "permissions"
    const val STEP_EXTRAS = "extras"
    const val STEP_VIDEO = "video"
    const val STEP_COMPANION = "companion"
    const val STEP_LINK = "link"
}
