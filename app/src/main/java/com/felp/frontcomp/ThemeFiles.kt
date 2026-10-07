package com.felp.frontcomp

import android.content.Context
import java.io.File

/**
 * Lo que un tema instalado puede sustituir sin recompilar nada.
 *
 * Hasta ahora un tema era media carpeta: las imagenes de la sala, los giros de las consolas
 * y el tvs.toml se copiaban al aparato, pero los sonidos y la lamina de letras viajaban
 * dentro del APK. O sea que cambiar un tema entero exigia compilar, y el instalador tenia
 * que avisar de que la mitad de lo que acababa de copiar no se iba a oir. Un tema que no se
 * puede instalar entero no es un tema, es un parche.
 *
 * Aqui se cierra ese hueco: una carpeta por tema, con el mismo id que usa el programa, y lo
 * que este dentro MANDA sobre lo que trae el APK.
 *
 *     <carpeta de datos>/themes/<id>/sounds/options.ogg
 *     <carpeta de datos>/themes/<id>/font/re-dialog.png
 *
 * Por ID y no una sola carpeta «tema actual»: se pueden tener dos instalados y cambiar de
 * uno a otro desde ajustes, sin volver a copiar nada. El que no tenga carpeta suena con lo
 * del APK, que es exactamente lo que hacia antes.
 *
 * Y fichero a fichero, no todo o nada. Un tema que solo quiera cambiar el clic de los
 * botones pone ese y hereda los demas; no hay que copiar ocho ficheros para tocar uno.
 */
object ThemeFiles {

    /** Donde se instalan: en la carpeta de datos, al lado del tvs.toml comun. */
    private val ROOT: File get() = DataHome.file("themes")

    /**
     * La carpeta del tema puesto.
     *
     * Por el id del ASPECTO y no por el del objeto que se esta pintando: con la luz clara
     * puesta, lo que se dibuja es otra variante y sus ficheros son los mismos. `themeById` ya
     * devuelve el aspecto —la luz es un ajuste aparte— asi que basta con preguntarle a el.
     */
    fun dir(ctx: Context): File = File(ROOT, themeById(Prefs(ctx).themeId).id)

    /**
     * El mismo id, para quien no tiene un Context a mano.
     *
     * Hay dos sitios que necesitan saber que tema esta puesto y no pueden preguntarselo a
     * Prefs: las raices de medios, que se resuelven una vez y se guardan, y el fichero de
     * salas. Los dos son objetos sin Context y llamarlos desde dentro de un composable con
     * uno prestado seria peor, asi que el tema se les DICE una vez, desde donde se sabe.
     *
     * Lo dice `AppTheme`, que es el unico sitio del programa donde vive el tema en vivo.
     */
    @Volatile
    private var current: String? = null

    /**
     * Apunta el tema puesto y tira lo que dependa de el.
     *
     * Las raices de medios estan guardadas —averiguarlas cuesta un listado de /storage y una
     * decena de stat— asi que cambiar de tema sin tirarlas dejaria mirando en la carpeta del
     * anterior hasta el siguiente reescaneo.
     */
    /**
     * Las carpetas de los temas instalados con el id de antes (ver LEGACY_THEME_IDS), al suyo:
     * `themes/gothic` pasa a `themes/parlour`. Sin esto, tras actualizar, el tema puesto no
     * encontraba sus salas, giros ni sonidos y caia a los de serie.
     *
     * Se renombra, no se copia ni se borra: es la misma tarjeta, y renombrar es instantaneo. Si
     * ya hay una carpeta con el nombre nuevo (un tema bajado otra vez) no se toca ninguna de las
     * dos. Se llama al arrancar, antes de que nadie busque un fichero del tema.
     */
    fun renameFolders() {
        if (!DataHome.ready()) return
        for ((old, new) in LEGACY_THEME_IDS) {
            val from = File(ROOT, old ?: continue)
            val to = File(ROOT, new)
            if (from.isDirectory && !to.exists()) runCatching { from.renameTo(to) }
        }
    }

    /** El tema que va dentro del APK. */
    private const val BUILT_IN = "gallery"

    /**
     * Gallery, el tema que trae el APK: sus ficheros (el fondo, las marcas del Companion y de
     * Link, las siluetas de las consolas) van en assets/themes/gallery y se copian a
     * themes/gallery, donde se buscan los de cualquier tema (y donde los lee Link). Hasta el
     * 07-10-2026 el APK solo traia sus sonidos, y una instalacion nueva quedaba sin fondo ni
     * marcas: solo se bajan Parlour y Mainframe. Sus sonidos se siguen leyendo de assets/sounds.
     *
     * Solo lo que falta, asi que es casi gratis en cada arranque y no pisa lo que alguien
     * cambio o bajo despues. Cada fichero a un .part y luego a su nombre: uno cortado a la mitad
     * no queda pasando por bueno.
     */
    fun unpackBuiltIn(ctx: Context) {
        if (!DataHome.ready()) return
        fun walk(path: String) {
            val names = runCatching { ctx.assets.list(path) }.getOrNull().orEmpty()
            if (names.isNotEmpty()) { for (n in names) walk("$path/$n"); return }
            val out = File(ROOT, path.removePrefix("themes/"))
            if (out.exists()) return
            runCatching {
                out.parentFile?.mkdirs()
                val part = File(out.path + ".part")
                ctx.assets.open(path).use { i -> part.outputStream().use { i.copyTo(it) } }
                if (!part.renameTo(out)) part.delete()
            }
        }
        walk("themes/$BUILT_IN")
    }

    /**
     * Un dibujo o un giro nuevo de una consola, en todos los temas instalados: se escribe como
     * `media/systems/<id>.<ext>` en cada uno, que es donde cada tema busca los suyos. Asi una
     * consola creada a mano tiene su imagen en cualquier tema que se elija despues, y sale en la
     * lista de renders para otras consolas. Sin temas instalados, en la carpeta comun de medios.
     *
     * Antes se borran los que hubiera de esa consola con otra extension: si no, un .png nuevo
     * quedaba detras del .mp4 viejo, que se busca primero. Devuelve en cuantas carpetas quedo.
     */
    fun addSystemArt(systemId: String, ext: String, write: (File) -> Unit): Int {
        val themes = ROOT.listFiles()?.filter { it.isDirectory }.orEmpty()
        val dirs = themes.map { File(it, "media/systems") }.ifEmpty { listOf(DataHome.file("media/systems")) }
        var n = 0
        for (d in dirs) runCatching {
            d.mkdirs()
            d.listFiles()?.filter { it.nameWithoutExtension == systemId }?.forEach { it.delete() }
            write(File(d, "$systemId.$ext"))
            n++
        }
        ArtIndex.invalidateRoots()
        return n
    }

    fun use(id: String) {
        if (current == id) return
        current = id
        ArtIndex.invalidateRoots()
    }

    /**
     * La carpeta de MEDIOS del tema puesto, si esta instalada.
     *
     * Aqui es donde se separan los temas de verdad. Antes todos los medios iban a una sola
     * carpeta, `FrontComp/media`, y entonces instalar uno pisaba los del anterior: elegir
     * otro tema en ajustes cambiaba la letra y el color, y las consolas, los medallones y la
     * sala seguian siendo del vecino. Con esto, cada tema lleva los suyos y elegirlo en
     * ajustes basta.
     *
     * Devuelve nulo si el tema no la trae, y entonces se mira la comun: un tema que no quiera
     * medios propios no tiene que copiar ninguno, y una instalacion vieja sigue funcionando
     * tal cual hasta que se reinstale.
     */
    fun media(): File? = current?.let { File(ROOT, "$it/media") }?.takeIf { it.isDirectory }

    /** El fichero de salas del tema puesto, si lo trae. Mismo criterio que `media`. */
    fun tvs(): File? = current?.let { File(ROOT, "$it/tvs.toml") }?.takeIf { it.isFile }

    /** Un sonido del tema puesto, o nulo si lo trae el APK. `name` es «options.ogg». */
    fun sound(ctx: Context, name: String): File? = at(ctx, "sounds", name)

    /** La lamina de letras del tema puesto, o nula. */
    fun font(ctx: Context, name: String): File? = at(ctx, "font", name)

    /**
     * Las dos tipografias del tema, si las trae: la de los rotulos y la del texto.
     *
     * Por nombre fijo —`display.ttf` y `body.ttf`— y no por «el primer ttf que haya». Una
     * familia descargada trae catorce ficheros entre pesos y cursivas, y elegir el primero por
     * orden alfabetico da la fina en cursiva. Quien monta el tema decide cual es cual
     * renombrando dos ficheros, que es mas facil de explicar que cualquier regla que me
     * invente aqui.
     *
     * Sin ellas, el tema usa la del sistema, que es lo que hacia antes.
     */
    fun display(ctx: Context): File? = face(ctx, "display")

    fun body(ctx: Context): File? = face(ctx, "body")

    private fun face(ctx: Context, name: String): File? =
        listOf("ttf", "otf").firstNotNullOfOrNull { at(ctx, "font", "$name.$it") }

    /**
     * La marca del tema para la tarjeta que asoma encima del juego: un SVG.
     *
     * Va en la carpeta del TEMA y no en media/companion, que es donde estaba. Esa carpeta la
     * comparte todo el aparato, asi que un dibujo dejado alli lo encuentra cualquier tema: es
     * el mismo enredo que hacia que la foto de cuero y madera del Parlour saliera detras del
     * cuaderno de todos. Aqui, cada tema con lo suyo.
     *
     * Sin fichero no pasa nada: la tarjeta usa la marca que trae el programa.
     */
    fun mark(ctx: Context): File? = File(dir(ctx), "mark.svg").takeIf { it.isFile && it.length() > 0L }

    /**
     * La marca del COMPANION: la suya si el tema la trae, y si no la del tema.
     *
     * Son dos cosas distintas y por eso son dos ficheros. `mark.svg` firma el tema —la tarjeta
     * de una partida, el pie de una ventana— y `companion.svg` firma el cuaderno. Mientras el
     * cuaderno no tuvo la suya se veia la del tema en su fila, y el resultado era que el mismo
     * dibujo salia dos veces en la misma pantalla diciendo dos cosas.
     *
     * Con respaldo y no obligatoria: un tema que no quiera distinguirlos no tiene que hacer
     * nada, y sigue firmando las dos cosas con la misma marca, que es lo que hacian los tres
     * hasta ahora.
     */
    fun companion(ctx: Context): File? =
        File(dir(ctx), "companion.svg").takeIf { it.isFile && it.length() > 0L } ?: mark(ctx)

    /**
     * La marca de Ludolog Link, para su fila en la lista de consolas en un tema sin sala (en
     * Parlour gira la radio). Sin respaldo en la del tema: Link no es el tema, y sin la suya la
     * fila dice su nombre. Las de Gallery y Mainframe las genera tools/link-marks.py, en las fuentes de los temas.
     */
    fun link(ctx: Context): File? = File(dir(ctx), "link.svg").takeIf { it.isFile && it.length() > 0L }
    /**
     * El fondo plano del tema: una imagen, o un video corto en bucle.
     *
     * Es la alternativa a la sala. Una sala es un decorado con television dentro, con sus
     * tres pases de luz y sus cuatro esquinas proyectadas; un fondo es una imagen detras del
     * menu y nada mas. Hay temas que quieren lo segundo, y obligarles a describir una
     * habitacion para poner un papel pintado seria pedirles que mientan.
     *
     * El video va en BUCLE y no reacciona a nada: ni se tine con lo que se este viendo, ni
     * cambia con la seleccion, ni tiene television que sintonizar. Esa quietud es el asunto.
     *
     * El orden importa: si estan los dos, manda el video. Quien deja un mp4 al lado de un jpg
     * casi siempre acaba de anadir el mp4.
     */
    fun backdrop(ctx: Context): File? {
        val d = dir(ctx)
        return listOf("backdrop.mp4", "backdrop.jpg", "backdrop.png")
            .map { File(d, it) }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    /**
     * Vacio no cuenta como puesto.
     *
     * Un fichero de cero bytes es lo que deja una copia a medias o un hueco reservado para
     * rellenar mas tarde, y las dos cosas pasan: el esqueleto de un tema nuevo se prepara con
     * los nombres puestos y los contenidos por hacer. Cargandolo, SoundPool falla en silencio
     * y el sonido desaparece sin decir por que; ignorandolo, suena el del APK hasta que haya
     * algo de verdad ahi.
     */
    private fun at(ctx: Context, sub: String, name: String): File? =
        File(dir(ctx), "$sub/${name.substringAfterLast('/')}")
            .takeIf { it.isFile && it.length() > 0L }
}
