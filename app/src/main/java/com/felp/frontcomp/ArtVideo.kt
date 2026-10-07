package com.felp.frontcomp

import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lo que queda de un video que no se llego a escribir, y como se arregla.
 *
 * Antes el video se trataba escribiendo directamente en su sitio de la tarjeta, y una
 * conversion colgada (ver VideoScale.convert) dejaba ahi un mp4 de 3 232 bytes: la cabecera y
 * nada mas. Visto en una consola de pruebas, tres de golpe. Para todo lo demas contaba como un video puesto:
 * el scraper no volvia a buscarlo, el panel intentaba ponerlo, y TapeQueue, al no sacarle
 * sonido, le ponia la marca de hecho y lo dejaba mudo para siempre. Al lado quedaba el `.part`
 * con la descarga entera, que es un video bueno, con su sonido.
 *
 * Ahora ya no se pueden formar —se trata en la memoria interna y a la tarjeta va el resultado
 * con nombre provisional—, pero los que ya estan hay que arreglarlos.
 */
internal object VideoRemnants {
    /**
     * Menos que esto no es un video. Una cabecera sola ocupa unos 3 KB; el recorte mas corto
     * que se guarda, cinco segundos a lo minimo que da un codificador, pasa de sesenta.
     */
    private const val MIN_BYTES = 32L * 1024

    /** Si ese fichero puede ser un video de verdad. Barato: mira el tamano, no lo abre. */
    fun real(f: File): Boolean = f.isFile && f.length() >= MIN_BYTES

    /** Los tipos de las pistas del fichero, o nulo si no se puede abrir. Caro: lo abre. */
    fun tracks(f: File): List<String>? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            ex.release()
        }
    }.getOrNull()

    /** Si se abre y trae imagen. */
    fun playable(f: File): Boolean = tracks(f)?.any { it.startsWith("video/") } == true

    /**
     * Arregla los videos rotos de la carpeta de medios del scraper.
     *
     * El que tiene su descarga al lado y la descarga se abre, se queda con ella: es el video
     * entero, sin recortar, pero bueno y con sonido. El que no, se quita, y el scraper lo vuelve a
     * buscar en la siguiente pasada. En los dos casos se olvida lo que TapeQueue le hiciera, que
     * era sobre la cabecera. Solo aqui y no en las carpetas de otros programas: estos restos solo
     * los dejaba este.
     */
    fun repair(mediaRoot: File) {
        val dirs = mediaRoot.listFiles()?.mapNotNull { File(it, "videos").takeIf(File::isDirectory) }
        for (dir in dirs.orEmpty()) {
            for (video in dir.listFiles().orEmpty()) {
                if (video.extension.lowercase() != "mp4" || real(video) || !video.isFile) continue
                if (playable(video)) continue
                val part = File(dir, video.name + ".part")
                val good = part.isFile && playable(part)
                val kept = good && (part.renameTo(video) || (video.delete() && part.renameTo(video)))
                if (!kept) {
                    video.delete()
                    // La descarga solo se tira si tampoco sirve. Si servia y lo que fallo fue
                    // cambiarle el nombre, se queda para la proxima vez.
                    if (!good) part.delete()
                }
                TapeQueue.forget(video)
                android.util.Log.i(
                    "Ludolog",
                    "video roto ${dir.parentFile?.name}/${video.name}: " +
                        if (kept) "recuperado de su descarga" else "quitado, se volvera a buscar",
                )
            }
        }
    }
}

/**
 * Los videos de partida, que aqui no son un adorno.
 *
 * El panel de esta interfaz es un televisor, y un televisor apagado es media pantalla
 * vacia. Por eso el video se busca con el mismo empeno que la caratula y no como un extra.
 *
 * Salen de las colecciones que hay subidas a archive.org, que son ficheros mp4 sueltos, sin
 * cuenta, sin clave y con nombres del conjunto No-Intro. Eso ultimo es lo que hace que esto
 * funcione sin escribir nada nuevo: el emparejador de titulos que ya usa la coleccion de
 * libretro vale tal cual, con sus reglas de articulos, numeros romanos y regiones.
 *
 * Que coleccion corresponde a cada consola vive en el catalogo y no aqui. Son subidas de
 * gente, y el dia que una desaparezca o salga otra mejor se arregla con una linea del .toml
 * del aparato en vez de con una version nueva del programa.
 */
internal class VideoSnaps(
    private val cacheDir: File = DataHome.file("cache"),
    /** Segundos a guardar de cada video. Cero guarda entero; el sonido se quita siempre. */
    private val clipSeconds: Int = 0,
    /** Si se conserva la pista de sonido. */
    private val keepAudio: Boolean = true,
    /** Altura maxima en pixeles. Cero deja la del origen. */
    private val maxHeight: Int = 0,
) {
    /** Lo que una coleccion tiene, ya indexado por titulo. */
    class Listing(names: List<String>) {
        // Algunas colecciones guardan los videos dentro de carpetas. Para emparejar vale el
        // nombre a secas; la ruta entera hace falta despues, para pedir el fichero.
        private val byName: Map<String, String> = names.associateBy { it.substringAfterLast('/') }
        val index = ThumbIndex(byName.keys.map(::ThumbEntry))
        val size: Int get() = index.size
        fun pathOf(entry: ThumbEntry): String? = byName[entry.fileName]
    }

    private val memo = HashMap<String, Listing>()
    // Concurrente: desde que los indices se piden en paralelo, varias corrutinas apuntan
    // aqui a la vez, y un HashSet a pelo no lo aguanta.
    private val empty = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * Las colecciones que no se pudieron preguntar, con el porque. Aparte de [empty]: ver
     * LibretroThumbnails.failed.
     */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Como acabo [fetchTo]. */
    enum class Got {
        SAVED,
        /** Ninguno se parece, o no hay listado: [why] y [trouble] dicen por que. */
        NO_MATCH,
        /** Habia uno y no llego. */
        FAILED,
    }

    /** Los indices de todas las consolas de una pasada, que es lo lento en frio. */
    suspend fun warm(systems: List<SystemDef>) {
        // Por coleccion, no por consola: varias consolas apuntan a la misma, y en paralelo
        // dos que compartan coleccion se la bajarian dos veces.
        eachAtOnce(systems.distinctBy { it.videoSnaps }) { listing(it) }
    }

    fun size(sys: SystemDef): Int = synchronized(memo) { memo[sys.videoSnaps] }?.size ?: 0

    fun why(sys: SystemDef): String? = when {
        sys.videoSnaps.isEmpty() -> "the catalog names no video collection for this console"
        sys.videoSnaps in empty -> "archive.org has nothing at \"${sys.videoSnaps}\""
        else -> null
    }

    /** Si no se pudo traer el listado de su coleccion: ver CoverSource.trouble. */
    fun trouble(sys: SystemDef): String? =
        failed[sys.videoSnaps]?.let { "could not get archive.org's list for \"${sys.videoSnaps}\" ($it)" }

    // En E/S, por lo mismo que LibretroThumbnails.index: warm() llega desde el hilo de la
    // interfaz, y leer y trocear un listado ahi paraba la pantalla.
    suspend fun listing(sys: SystemDef): Listing? = withContext(Dispatchers.IO) {
        val id = sys.videoSnaps
        if (id.isEmpty() || id in empty || failed.containsKey(id)) return@withContext null
        synchronized(memo) { memo[id] }?.let { return@withContext it }

        // Null es que no se pudo preguntar, y download() ya apunto el porque.
        val names = cached(id)
            ?: download(id)?.also { if (it.isNotEmpty()) store(id, it) }
            ?: return@withContext null
        if (names.isEmpty()) {
            empty += id
            return@withContext null
        }
        Listing(names).also { synchronized(memo) { memo[id] = it } }
    }

    /**
     * Baja el video al fichero destino, a trozos y no a memoria.
     *
     * Uno de estos pesa varios megabytes, a veces mas de diez. Leerlo entero a un array
     * antes de escribirlo es la forma de quedarse sin memoria en un aparato de mano justo
     * cuando esta bajando cincuenta seguidos.
     */
    suspend fun fetchTo(sys: SystemDef, game: Game, dest: File, name: String? = null): Got =
        withContext(Dispatchers.IO) {
            val listing = listing(sys) ?: return@withContext Got.NO_MATCH
            // Con el nombre que le puso el usuario primero, exacto y si no por palabras, igual
            // que las caratulas: quien renombra un juego lo hace porque el del fichero no sirve.
            val entry = name?.let { listing.index.best(it, game.region) ?: listing.index.near(it, game.region) }
                ?: listing.index.best(MatchKey.stem(game.fileName), game.region)
                ?: listing.index.best(game.title, game.region)
                ?: return@withContext Got.NO_MATCH
            val path = listing.pathOf(entry) ?: return@withContext Got.NO_MATCH
            // Encontrado y no llegado es otra cosa que no encontrado: el informe no puede
            // mandar a renombrar un fichero cuyo video estaba ahi.
            if (stream("$BASE/${enc(sys.videoSnaps)}/${encPath(path)}", dest)) Got.SAVED else Got.FAILED
        }

    /**
     * Los videos que podria tener UN juego buscado a mano, para que elija una persona: los del
     * mismo nombre y, detras, los parecidos. Ver ThumbIndex.around.
     */
    suspend fun choices(sys: SystemDef, game: Game, name: String?, limit: Int): List<Pair<ThumbEntry, Boolean>> =
        withContext(Dispatchers.IO) {
            val listing = listing(sys) ?: return@withContext emptyList()
            val asked = if (name != null) listOf(name) else listOf(MatchKey.stem(game.fileName), game.title)
            listing.index.around(asked, limit, game.region)
        }

    /** Baja el video de una entrada ya elegida, como `fetchTo`. */
    suspend fun fetchEntry(sys: SystemDef, entry: ThumbEntry, dest: File): Boolean =
        withContext(Dispatchers.IO) {
            val listing = listing(sys) ?: return@withContext false
            val path = listing.pathOf(entry) ?: return@withContext false
            stream("$BASE/${enc(sys.videoSnaps)}/${encPath(path)}", dest)
        }

    private fun stream(url: String, dest: File): Boolean {
        // Todo el trabajo en la memoria interna, y a la tarjeta solo el resultado: ver
        // DataHome.work. Los de paso se borran pase lo que pase.
        //
        // Reescalado y recorte, cada uno en su fichero: una conversion que se pasa del tope se
        // deja atras sin poder pararla (ver VideoScale.convert), y si despertara tarde no debe
        // escribir encima del recorte que se hizo en su lugar.
        val work = DataHome.work()
        val tmp = File.createTempFile("video", ".part", work)
        val scaledOut = File(work, tmp.nameWithoutExtension + ".scaled.mp4")
        val trimmedOut = File(work, tmp.nameWithoutExtension + ".trimmed.mp4")
        val t0 = android.os.SystemClock.elapsedRealtime()
        return runCatching {
            val (body, type) = open(url) ?: return@runCatching false
            // Que el tipo declarado sea video es lo que distingue el fichero de una pagina de
            // aviso, que tambien llega con un 200 por delante.
            if (type?.startsWith("video/") != true) {
                body.close()
                return@runCatching false
            }
            body.use { input -> tmp.outputStream().use { o -> copyInTime(input, o) } }
            if (tmp.length() < 100_000) return@runCatching false
            val t1 = android.os.SystemClock.elapsedRealtime()
            // Lo que se guarda no es lo que llego. Primero se intenta bajar la resolucion, que
            // solo vale la pena cuando el origen se pasa; si no hace falta o el aparato no
            // puede, queda el recorte, que no recodifica nada; y si tampoco, el original entero.
            val format = if (maxHeight <= 0) null else VideoScale.videoFormat(tmp)
            val bitrate = maxHeight * maxHeight * 7          // 240p -> ~400 kbps, 360p -> ~900
            val scaled = format != null &&
                VideoScale.worthIt(format, maxHeight, bitrate, VideoScale.fileBitrate(tmp, format)) &&
                VideoScale.convert(tmp, scaledOut, clipSeconds, maxHeight, bitrate, keepAudio)
            val (result, how) = when {
                scaled -> scaledOut to "reescalado"
                VideoTrim.shrink(tmp, trimmedOut, clipSeconds, keepAudio) -> trimmedOut to "recortado"
                else -> tmp to "entero"
            }
            val t2 = android.os.SystemClock.elapsedRealtime()
            // Y a la tarjeta, de una copia. Con nombre provisional: una copia cortada no debe
            // dejar un mp4 a medias, porque el indice lo contaria como video puesto y nadie
            // volveria a buscarlo.
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            result.inputStream().use { i -> part.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
            // Lo que TapeQueue le hubiera hecho al de antes con este mismo nombre ya no vale: sin
            // esto el nuevo sonaria con el sonido del viejo, o seguiria mudo si el viejo era un
            // resto roto (ver VideoRemnants). Y quien lo este ensenando, que se entere.
            TapeQueue.forget(dest)
            ArtRevisions.bump(dest)
            android.util.Log.i(
                "Ludolog",
                "video ${dest.name}: bajada ${tmp.length() / 1024} KB en ${t1 - t0} ms, " +
                    "$how en ${t2 - t1} ms, a la tarjeta en ${android.os.SystemClock.elapsedRealtime() - t2} ms",
            )
            true
        }.onFailure { android.util.Log.w("Ludolog", "video ${dest.name}: $it") }
            .getOrDefault(false)
            .also { tmp.delete(); scaledOut.delete(); trimmedOut.delete() }
    }

    /**
     * Copia con un tope de tiempo total.
     *
     * El de lectura del conector salta si deja de llegar nada, pero no si llega a goteo: a cinco
     * kilobytes por segundo, un video de quince megas tiene a un hilo ocupado casi una hora, y
     * con cuatro hilos bastan cuatro de esos para que la pasada entera parezca colgada. Pasado el
     * tope se deja ese video para otra vez y se sigue.
     */
    private fun copyInTime(input: InputStream, out: java.io.OutputStream) {
        val deadline = android.os.SystemClock.elapsedRealtime() + DOWNLOAD_LIMIT_MS
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            out.write(buf, 0, n)
            if (android.os.SystemClock.elapsedRealtime() > deadline) {
                error("more than ${DOWNLOAD_LIMIT_MS / 1000} s downloading")
            }
        }
    }

    /**
     * El listado de una coleccion, leido segun llega.
     *
     * archive.org publica un XML con una linea por fichero, asi que se recorre sin tener
     * nunca el listado entero en memoria. De ahi solo interesan los mp4 originales: cada
     * item lleva ademas las copias que archive.org genera por su cuenta, y bajar una de
     * esas es bajar el mismo video peor.
     */
    private suspend fun download(id: String): List<String>? = withContext(Dispatchers.IO) {
        val rx = Regex("""<file name="([^"]+)" source="original"""")
        runCatching {
            // Sin esa coleccion: no la hay, que no es un fallo.
            val (body, _) = open("$BASE/${enc(id)}/${enc(id)}_files.xml")
                ?: return@runCatching emptyList<String>()
            body.use { stream ->
                buildList {
                    stream.bufferedReader().forEachLine { line ->
                        val raw = rx.find(line)?.groupValues?.get(1) ?: return@forEachLine
                        val name = unescape(raw)
                        if (name.endsWith(".mp4", true)) add(name)
                    }
                }
            }
        }.onFailure { failed[id] = failureOf(it) }.getOrNull()
    }

    /** El listado apenas cambia, asi que se guarda y la segunda pasada ya es local. */
    private fun cacheFile(id: String) = File(cacheDir, "snaps-" + SystemDef.key(id) + ".idx")

    private fun cached(id: String): List<String>? {
        val f = cacheFile(id)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching { f.readLines().filter(String::isNotEmpty) }.getOrNull()
    }

    private fun store(id: String, names: List<String>) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(id).writeText(names.joinToString("\n"))
        }
    }

    /**
     * Abre una direccion y devuelve el flujo con el tipo declarado, o null si no existe.
     * Cualquier otra respuesta se lanza: ver LibretroThumbnails.open.
     */
    private fun open(url: String): Pair<InputStream, String?>? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Ludolog")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect()
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            throw java.io.IOException("HTTP $code")
        }
        val raw = c.inputStream
        val body = if (c.contentEncoding.equals("gzip", ignoreCase = true)) {
            java.util.zip.GZIPInputStream(raw)
        } else raw
        return body to c.contentType
    }

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Las barras de una ruta se dejan; lo demas se escapa trozo a trozo. */
    private fun encPath(p: String) = p.split('/').joinToString("/", transform = ::enc)

    private companion object {
        const val BASE = "https://archive.org/download"

        /** Lo mas que se espera por un video: medido desde casa, uno tarda unos tres segundos. */
        const val DOWNLOAD_LIMIT_MS = 120_000L
    }
}
