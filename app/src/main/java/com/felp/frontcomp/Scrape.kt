package com.felp.frontcomp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** What happened to one game during a scrape. */
enum class ScrapeOutcome { FETCHED, ALREADY_HAD, NO_MATCH, NO_SOURCE, FAILED }

data class ScrapeProgress(
    val done: Int,
    val total: Int,
    val current: String,
    val fetched: Int,
)

/**
 * One game that did not get art, and why.
 *
 * El motivo importa más que el hecho. «No emparejó» puede ser que la consola no esté en la
 * fuente, que el nombre del fichero no se parezca a ninguno, o que la descarga se cayera, y
 * cada una se arregla de una manera distinta. Sin decir cuál, el informe solo sirve para
 * saber que algo va mal.
 */
data class ScrapeMiss(
    val systemId: String,
    val title: String,
    val outcome: ScrapeOutcome,
    val detail: String,
)

/** Por que fallo una peticion, en corto y sin la direccion: para el informe. */
internal fun failureOf(t: Throwable): String = when (t) {
    is java.net.UnknownHostException, is java.net.ConnectException -> "no connection"
    is java.net.SocketTimeoutException -> "the server did not answer in time"
    else -> t.message?.takeIf { it.startsWith("HTTP ") } ?: t.javaClass.simpleName
}

data class ScrapeReport(
    val counts: Map<ScrapeOutcome, Int>,
    val misses: List<ScrapeMiss>,
    val millis: Long,
    /** Videos de partida bajados, que se cuentan aparte porque no son arte fijo. */
    val videos: Int = 0,
) {
    val fetched: Int get() = counts[ScrapeOutcome.FETCHED] ?: 0
    val total: Int get() = counts.values.sum()

    /**
     * Una línea con lo que pasó, para el pie de una ventana. Las carátulas solo si se buscó
     * alguna: el informe de un vídeo suelto decía «0 new», como si hubiera fallado algo.
     */
    fun summary(): String = buildList {
        if (total > 0) add("$fetched new")
        if (videos > 0) add(if (videos == 1) "1 video" else "$videos videos")
        counts[ScrapeOutcome.ALREADY_HAD]?.let { add("$it kept") }
        if (misses.isNotEmpty()) add("${misses.size} unresolved")
        add("${millis / 1000}s")
    }.joinToString("  ·  ")
}

/**
 * Where cover art comes from.
 *
 * Kept behind an interface because the source is the part most likely to change: today
 * libretro's set needs no account, but ScreenScraper has richer data for anyone willing
 * to register, and a local pack is just a third implementation.
 */
interface ArtSource {
    val name: String
    /** Null when this source has nothing for the system at all. */
    suspend fun index(system: SystemDef): ThumbIndex?
    suspend fun fetch(system: SystemDef, entry: ThumbEntry): ByteArray?
}

/**
 * libretro's thumbnail collection.
 *
 * It is addressed by No-Intro system name, which is exactly the `raName` the catalog
 * already carries, and it needs no API key — so it works on a fresh install with nothing
 * configured.
 */
class LibretroThumbnails(
    private val base: String = "https://thumbnails.libretro.com",
    private val folder: String = "Named_Boxarts",
    private val cacheDir: File = DataHome.file("cache"),
) : ArtSource {

    override val name = "libretro-thumbnails"

    /** Systems the remote set does not have, so we stop asking after the first miss. */
    // Concurrente, por lo mismo que el de los videos: warm() lo toca desde varias a la vez.
    private val missing = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * Las que no se pudieron preguntar, con el porque: sin red, o el servidor no contesto.
     *
     * Aparte de [missing]. Tampoco se vuelven a pedir en esta pasada —sin red, cada juego
     * esperaria su tiempo de conexion—, pero de una caida el informe no puede decir que la
     * coleccion no tiene esa consola, ni mandar a renombrar ficheros que estaban bien.
     */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val memo = HashMap<String, ThumbIndex>()

    /** Por que no se pudo traer el listado de esta consola, si no se pudo. */
    fun failure(raName: String): String? = failed[raName]

    // En E/S entero: warm() llega desde el hilo de la interfaz, que es donde corre el
    // scraper, y aqui se lee el listado del disco y se trocea en miles de nombres, cada uno
    // con varias expresiones. Con una biblioteca grande la pantalla se quedaba parada al
    // empezar cada pasada, antes de poder pintar siquiera el «index: …».
    override suspend fun index(system: SystemDef): ThumbIndex? = withContext(Dispatchers.IO) {
        val raName = system.raName
        if (raName.isEmpty() || raName in missing || failed.containsKey(raName)) return@withContext null
        synchronized(memo) { memo[raName] }?.let { return@withContext it }

        // Null es que no se pudo preguntar, y download() ya apunto el porque.
        val names = cached(raName)
            ?: download(raName)?.also { if (it.isNotEmpty()) store(raName, it) }
            ?: return@withContext null
        if (names.isEmpty()) {
            missing += raName
            return@withContext null
        }
        ThumbIndex(names.map(::ThumbEntry))
            .also { synchronized(memo) { memo[raName] = it } }
    }

    override suspend fun fetch(system: SystemDef, entry: ThumbEntry): ByteArray? =
        withContext(Dispatchers.IO) {
            open("$base/${enc(system.raName)}/$folder/${enc(entry.fileName)}")
                ?.use { it.readBytes() }
        }

    /**
     * Parses the directory listing as it arrives instead of buffering it.
     *
     * These listings are big — PlayStation alone is 2.5 MB of HTML for 9000 entries — and
     * holding that as a String costs several times its size in memory on a handheld while
     * the only thing worth keeping is the file names.
     */
    private suspend fun download(raName: String): List<String>? = withContext(Dispatchers.IO) {
        val rx = Regex("""href="([^"]+\.(?:png|jpg))"""", RegexOption.IGNORE_CASE)
        runCatching {
            // Sin carpeta para esta consola: no la tiene, que no es un fallo.
            val stream = open("$base/${enc(raName)}/$folder/") ?: return@runCatching emptyList<String>()
            stream.use { s ->
                buildList {
                    s.bufferedReader().forEachLine { line ->
                        rx.findAll(line).forEach { add(dec(it.groupValues[1])) }
                    }
                }
            }
        }.onFailure { failed[raName] = failureOf(it) }.getOrNull()
    }

    /**
     * The listing barely changes, so it is kept on disk as plain names. That turns every
     * scrape after the first into a local read, which is the difference between minutes
     * and seconds.
     */
    private fun cacheFile(raName: String) = File(cacheDir, SystemDef.key(raName) + ".idx")

    private fun cached(raName: String): List<String>? {
        val f = cacheFile(raName)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching { f.readLines().filter(String::isNotEmpty) }.getOrNull()
    }

    private fun store(raName: String, names: List<String>) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(raName).writeText(names.joinToString("\n"))
        }
    }

    private fun open(url: String): java.io.InputStream? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Ludolog")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        // Null solo si no existe. Cualquier otra respuesta es que no se pudo preguntar, y
        // se lanza para que quien llama lo diga asi y no como «no la tiene».
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect()
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            throw java.io.IOException("HTTP $code")
        }
        val raw = c.inputStream
        return if (c.contentEncoding.equals("gzip", ignoreCase = true)) {
            java.util.zip.GZIPInputStream(raw)
        } else raw
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/**
 * Runs a scrape over a set of games and writes what it finds to disk.
 *
 * Art is written in the same layout the app already reads, so a scraped cover is
 * indistinguishable from one that was already there and nothing has to be re-indexed
 * specially.
 */
/**
 * Runs a scrape over a set of games and writes what it finds to disk.
 *
 * Varias fuentes en cadena, no una. Cada juego se le pide a la primera, y solo si no la
 * tiene se le pide a la siguiente. El orden es el de certeza de identidad: la que reconoce
 * el juego por su nombre de conjunto de volcado no puede equivocarse de juego, y la que lo
 * busca por texto sí, así que va detrás.
 *
 * Art is written in the same layout the app already reads, so a scraped cover is
 * indistinguishable from one that was already there and nothing has to be re-indexed
 * specially.
 */
internal class ArtScraper(
    private val catalog: Catalog,
    private val prefs: Prefs? = null,
    private val sources: List<CoverSource> = listOf(
        LibretroSource(), GameTdbSource(), SteamSource(), IgdbSource(),
    ),
    private val mediaRoot: File = DataHome.file("media"),
    private val parallel: Int = 4,
    private val snaps: VideoSnaps = VideoSnaps(
        clipSeconds = prefs?.clipSeconds ?: 0,
        keepAudio = prefs?.anyThemeHearsVideo ?: false,
        maxHeight = prefs?.videoHeight ?: 0,
    ),
) {
    /**
     * El nombre que el usuario le puso al juego, si lo renombro. Con el se busca, y no con el
     * del fichero: ver `CoverSource.cover`. La caratula se guarda igual con el nombre del
     * fichero, que es por el que se encuentra despues.
     */
    private fun named(game: Game): String? = prefs?.gameTitle(game)

    /** Como se llama el juego en el progreso y en el informe: como en la lista. */
    private fun shown(game: Game): String = named(game) ?: game.title

    /**
     * Los nombres con que buscar el arte de un juego, del mas seguro al menos: el juego tal como
     * lo conoce el catalogo, si su ficha lo sabe con seguridad; los nombres de sus versiones de
     * otras regiones; su titulo en Wikidata; y al final el del fichero, como siempre.
     *
     * El exacto es el nombre de las caratulas de libretro y el de los videos de archive.org: asi
     * se encuentra a la primera y es la de esa version. Los otros tapan los huecos de la
     * coleccion: la española de Pokémon Esmeralda no tiene caratula con su nombre y la americana
     * si; «Biohazard 2 - Dual Shock Ver.» tiene su video como «Resident Evil 2».
     *
     * En arcade solo el exacto: los nombres de otro romset son otro montaje, y a veces otro juego.
     * El arte se guarda igual con el nombre del fichero (ver [destinationFor]), que es por el que
     * se encuentra despues.
     */
    private fun candidates(game: Game): List<Game> {
        val d = Dossiers.get(game) ?: return listOf(game)
        val names = buildList {
            if (d.exact) d.name?.let(::add)
            if (d["how"] != "romset") addAll(d.otherNames)
        }
        return names.distinct().map { renamed(game, it) } + game
    }

    /** El mismo juego con otro nombre de fichero, para buscarlo por ese nombre. */
    private fun renamed(game: Game, name: String): Game {
        // Lo que libretro cambia en los nombres de sus miniaturas: «Metal Slug 2 - Super
        // Vehicle-001/II» se guarda como «…001_II».
        val stem = name.replace(THUMB_UNSAFE, "_")
        val ext = game.fileName.substringAfterLast('.', "")
        val info = Detector(catalog).describe(if (ext.isEmpty()) stem else "$stem.$ext")
        return game.copy(
            path = game.path.substringBeforeLast('/') + "/" + stem + (if (ext.isEmpty()) "" else ".$ext"),
            title = info.title,
            region = info.region ?: game.region,
            tags = info.tags,
        )
    }

    /**
     * La consola donde buscar: la de verdad, si la ficha dice que el juego es de otra que la de
     * su carpeta. Los Earthworm Jim de la carpeta de 32X tienen sus caratulas y videos entre los
     * de Mega Drive.
     */
    private fun lookIn(game: Game, sys: SystemDef): SystemDef =
        Dossiers.get(game)?.realSystem?.let { catalog.byId[it] } ?: sys

    fun destinationFor(game: Game): File =
        File(mediaRoot, "${game.systemId}/${ArtKind.COVER.folder}/${game.fileName.substringBeforeLast('.')}.png")

    /**
     * Donde va el video de partida de un juego.
     *
     * En la carpeta que ya leen tanto este front-end como ES-DE y Daijisho, y con el nombre
     * del fichero de la ROM. Asi un video bajado aqui y uno bajado por otro programa son
     * indistinguibles, que es lo que evita tener dos copias de lo mismo.
     */
    fun videoFor(game: Game): File =
        File(mediaRoot, "${game.systemId}/videos/${game.fileName.substringBeforeLast('.')}.mp4")

    /** Si toca bajar videos: mientras algun tema los quiera. Apagados en todos, no se gasta tarjeta. */
    private val wantVideos: Boolean get() = prefs?.anyThemePlaysVideo ?: false

    suspend fun run(
        games: List<Game>,
        existing: ArtIndex?,
        onProgress: (ScrapeProgress) -> Unit = {},
    ): ScrapeReport = coroutineScope {
        val started = System.currentTimeMillis()
        val counts = java.util.concurrent.ConcurrentHashMap<ScrapeOutcome, Int>()
        val misses = java.util.Collections.synchronizedList(mutableListOf<ScrapeMiss>())
        val gate = Semaphore(parallel)
        var done = 0
        var fetched = 0
        val videoHits = java.util.concurrent.atomic.AtomicInteger()

        fun record(outcome: ScrapeOutcome) = counts.merge(outcome, 1, Int::plus)

        val live = live()
        val bySystem = games.groupBy { it.systemId }
        val systems = bySystem.keys.mapNotNull { catalog.byId[it] }

        val idxT0 = android.os.SystemClock.elapsedRealtime()
        // Lo que haya que traerse una vez por consola: los índices de libretro son lo lento
        // de una pasada en frío, y en paralelo tardan lo que el más lento en vez de la suma.
        live.map { s ->
            async { onProgress(ScrapeProgress(done, games.size, "index: ${s.name}", fetched)); s.warm(systems) }
        }.plus(
            // El indice de videos se trae en la misma tanda que los demas: es otra descarga
            // por consola, y en paralelo no cuesta nada mas que la mas lenta de todas.
            if (!wantVideos) emptyList() else listOf(
                async { onProgress(ScrapeProgress(done, games.size, "index: videos", fetched)); snaps.warm(systems) },
            )
        ).awaitAll()
        android.util.Log.i("Ludolog", "scrape indices: ${systems.size} consolas en ${android.os.SystemClock.elapsedRealtime() - idxT0}ms")

        // Primero las caratulas de todos, y despues los videos.
        //
        // Iban juntas, juego a juego y el video delante: una caratula son unos cientos de KB y
        // un video varios megas y luego volver a codificarlo, asi que cada caratula esperaba
        // detras de su video y el contador no se movia hasta que acababan los dos. Con cuatro
        // videos lentos a la vez, la pasada entera parecia colgada. Asi las caratulas salen
        // enseguida, y los videos tienen su propio contador.
        val covered = java.util.concurrent.ConcurrentHashMap<String, ScrapeOutcome>()
        for ((systemId, list) in bySystem) {
            val sys = catalog.byId[systemId]
            if (sys == null) {
                list.forEach { g ->
                    record(ScrapeOutcome.NO_SOURCE)
                    misses += ScrapeMiss(systemId, shown(g), ScrapeOutcome.NO_SOURCE,
                        "the console \"$systemId\" is not in the catalog")
                    done++
                }
                continue
            }
            list.map { game ->
                async {
                    gate.withPermit {
                        val outcome = coverOne(sys, game, live, existing, misses)
                        covered[game.path] = outcome
                        synchronized(counts) {
                            done++
                            if (outcome == ScrapeOutcome.FETCHED) fetched++
                        }
                        record(outcome)
                        onProgress(ScrapeProgress(done, games.size, shown(game), fetched))
                    }
                }
            }.awaitAll()
        }

        if (wantVideos) {
            // Mirar si ya hay video es ir a la tarjeta, juego por juego: fuera del hilo de la pantalla,
            // que es donde corre la pasada (revision del 09-10-2026).
            val pending = withContext(Dispatchers.IO) { games.filter { catalog.byId[it.systemId] != null && !hasVideo(it, existing) } }
            var tried = 0
            pending.map { game ->
                async {
                    gate.withPermit {
                        val sys = lookIn(game, catalog.byId.getValue(game.systemId))
                        onProgress(ScrapeProgress(tried, pending.size, "video: ${shown(game)}", fetched))
                        // Lo que se escape de aqui tiraria la pasada entera, informe incluido.
                        // Con los nombres de [candidates], en orden; el siguiente solo si el
                        // anterior no encontro ninguno (si encontro uno y no llego, no se prueba otro).
                        val got = runCatching {
                            var last = VideoSnaps.Got.NO_MATCH
                            for (g in candidates(game)) {
                                last = snaps.fetchTo(sys, g, videoFor(game), named(game))
                                if (last != VideoSnaps.Got.NO_MATCH) break
                            }
                            last
                        }.getOrDefault(VideoSnaps.Got.FAILED)
                        val ok = got == VideoSnaps.Got.SAVED
                        if (ok) videoHits.incrementAndGet()
                        // Caratula puesta y television apagada. Sin esta linea eso no aparece en
                        // ninguna parte, porque el informe solo hablaba de caratulas y el juego ya
                        // no era un hueco.
                        val cover = covered[game.path]
                        if (!ok && (cover == ScrapeOutcome.FETCHED || cover == ScrapeOutcome.ALREADY_HAD)) {
                            val broke = snaps.trouble(sys)
                                ?: if (got == VideoSnaps.Got.FAILED) "the gameplay video matched but did not arrive" else null
                            misses += if (broke != null) {
                                ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.FAILED, broke)
                            } else ScrapeMiss(
                                game.systemId, shown(game), ScrapeOutcome.NO_MATCH,
                                snaps.why(sys) ?: "the cover is there, but no gameplay video matched " +
                                    "\"${MatchKey.of(MatchKey.stem(game.fileName))}\" among ${snaps.size(sys)} in the collection",
                            )
                        }
                        synchronized(counts) { tried++ }
                        onProgress(ScrapeProgress(tried, pending.size, "video: ${shown(game)}", fetched))
                    }
                }
            }.awaitAll()
        }

        ScrapeReport(
            counts = counts.toMap(),
            misses = misses.sortedWith(compareBy({ it.systemId }, { it.title })),
            millis = System.currentTimeMillis() - started,
            videos = videoHits.get(),
        )
    }

    /** Un resto roto no cuenta como video: ver VideoRemnants. */
    private fun hasVideo(game: Game, existing: ArtIndex?): Boolean =
        VideoRemnants.real(videoFor(game)) || existing?.video(game)?.let(VideoRemnants::real) == true

    private suspend fun coverOne(
        sys: SystemDef,
        game: Game,
        live: List<CoverSource>,
        existing: ArtIndex?,
        misses: MutableList<ScrapeMiss>,
    ): ScrapeOutcome {
        val dest = destinationFor(game)
        if (withContext(Dispatchers.IO) { dest.isFile } || existing?.has(game) == true) return ScrapeOutcome.ALREADY_HAD

        // Donde buscar: la consola de verdad, si la ficha dice otra que la de la carpeta.
        val look = lookIn(game, sys)

        // Solo las que saben de esta consola. Preguntarle a Steam por un cartucho no es un
        // fallo, es una pregunta que nadie hace, y salia en el informe como si lo fuera.
        val usable = live.filter { it.applies(look) }
        if (usable.isEmpty()) {
            misses += ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.NO_SOURCE,
                "no configured source covers \"${look.id}\"")
            return ScrapeOutcome.NO_SOURCE
        }

        // Primero con los nombres que da el catalogo, y despues con el del fichero: el scraper
        // busca por nombre solo lo que el catalogo no resuelve. Ver candidates.
        val tries = candidates(game)

        // Lo que se cayo al preguntar, que no es lo mismo que no tenerla: ver missFor.
        val errors = mutableListOf<String>()
        for (source in usable) {
            val bytes = tries.firstNotNullOfOrNull { g ->
                runCatching { source.cover(look, g, named(game)) }
                    .onFailure { errors += "${source.name}: ${failureOf(it)}" }
                    .getOrNull()
            } ?: continue
            return withContext(Dispatchers.IO) {
                runCatching {
                    dest.parentFile?.mkdirs()
                    // A temp name first: an interrupted download must never leave a
                    // half-written PNG that the index would then treat as real art.
                    val tmp = File(dest.parentFile, dest.name + ".part")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                    ScrapeOutcome.FETCHED
                }.getOrElse {
                    misses += ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.FAILED,
                        "could not write to ${dest.parent}: ${it.javaClass.simpleName}")
                    ScrapeOutcome.FAILED
                }
            }
        }

        return missFor(look, game, usable, errors).also { misses += it }.outcome
    }

    /**
     * Nadie la tuvo. Lo que se apunta es lo que CADA fuente dijo, porque el arreglo depende de
     * cuál falló: una consola sin nombre No-Intro se arregla en el catálogo, y un nombre que
     * no se parece a ninguno se arregla renombrando el fichero.
     *
     * Y la que no pudo preguntar —sin red, el servidor caido, las claves de IGDB rechazadas—
     * lo dice primero y el juego queda como FAILED. Se contaba como que no la tenia: sin red,
     * el informe mandaba a renombrar ficheros que ya tenian su nombre No-Intro.
     */
    private fun missFor(
        sys: SystemDef,
        game: Game,
        usable: List<CoverSource>,
        errors: List<String> = emptyList(),
    ): ScrapeMiss {
        val broke = usable.mapNotNull { s -> s.trouble(sys)?.let { "${s.name}: $it" } } + errors
        val said = usable.mapNotNull { s -> s.why(sys)?.let { "${s.name}: $it" } }
        val tried = usable.joinToString(", ") { it.name }
        val libretro = usable.filterIsInstance<LibretroSource>().firstOrNull()
        val detail = when {
            broke.isNotEmpty() || said.isNotEmpty() -> (broke + said).joinToString("\n")
            libretro != null && libretro.size(sys) > 0 ->
                "looked for \"${named(game) ?: game.fileName.substringBeforeLast('.')}\" among " +
                    "${libretro.size(sys)} names; tried $tried"
            else -> "no source had it; tried $tried"
        }
        val outcome = when {
            broke.isNotEmpty() -> ScrapeOutcome.FAILED
            said.size == usable.size -> ScrapeOutcome.NO_SOURCE
            else -> ScrapeOutcome.NO_MATCH
        }
        return ScrapeMiss(game.systemId, shown(game), outcome, detail)
    }

    /**
     * Solo las fuentes configuradas. Una sin credenciales no se prueba y se dice por qué en el
     * informe, en vez de fallar en silencio como si no tuviera la carátula.
     */
    private fun live(): List<CoverSource> =
        sources.filter { s -> prefs?.let { s.ready(it) } ?: (s is LibretroSource) }

    /**
     * Las carátulas que podría tener UN juego, de todas las fuentes que saben de su consola,
     * para que elija una persona: ver `CoverSource.choices`. Las del mismo nombre primero,
     * vengan de la fuente que vengan. Sin ninguna, el porqué, igual que en el informe.
     */
    suspend fun hunt(game: Game): CoverHunt = coroutineScope {
        val started = System.currentTimeMillis()
        fun none(miss: ScrapeMiss) =
            CoverHunt(game, shown(game), emptyList(), miss, System.currentTimeMillis() - started)
        val sys = catalog.byId[game.systemId] ?: return@coroutineScope none(
            ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.NO_SOURCE,
                "the console \"${game.systemId}\" is not in the catalog"),
        )
        val usable = live().filter { it.applies(sys) }
        if (usable.isEmpty()) return@coroutineScope none(
            ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.NO_SOURCE,
                "no configured source covers \"${sys.id}\""),
        )
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val found = usable.map { s ->
            // En E/S: el indice de libretro se lee del disco y se trocea en miles de nombres,
            // y hunt se llama desde el hilo de la interfaz.
            async(Dispatchers.IO) {
                runCatching { s.warm(listOf(sys)); s.choices(sys, game, named(game)) }
                    .onFailure {
                        android.util.Log.w("Ludolog", "choices ${s.name}: ${it.javaClass.simpleName}")
                        errors += "${s.name}: ${failureOf(it)}"
                    }
                    .onSuccess { android.util.Log.i("Ludolog", "choices ${s.name}: ${it.size}") }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten().sortedByDescending { it.exact }
        if (found.isEmpty()) none(missFor(sys, game, usable, errors.toList()))
        else CoverHunt(game, shown(game), found, null, System.currentTimeMillis() - started)
    }

    /**
     * Guarda la carátula elegida.
     *
     * Solo la carátula: el vídeo tiene su propia fila en el menú, y buscarlo aquí a escondidas
     * hacía esperar al informe lo que tardara en bajar un vídeo que nadie había pedido.
     *
     * La que hubiera se quita: solo se guarda el arte que se usa. Se apartaba a una carpeta por
     * si se elegia mal, y esa carpeta solo crecia con arte que nadie iba a mirar; volver a
     * buscar la de antes cuesta lo mismo que buscarla la primera vez. Ver [dropOld].
     */
    suspend fun keep(hunt: CoverHunt, choice: CoverChoice): ScrapeReport {
        val t0 = System.currentTimeMillis()
        // De IGDB se enseñó la pequeña y la grande se baja ahora; si no llega, se guarda la
        // pequeña, que es la que se vio al elegir.
        val bytes = withContext(Dispatchers.IO) { choice.full() } ?: choice.preview
        val game = hunt.game
        val misses = mutableListOf<ScrapeMiss>()
        val dest = destinationFor(game)
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                tmp.writeBytes(bytes)
                dropOld(coverFiles(dest), keep = dest)
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                // Mismo nombre, otro dibujo: sin esto se seguiria viendo la de antes.
                ArtRevisions.bump(dest)
                ScrapeOutcome.FETCHED
            }.getOrElse {
                misses += ScrapeMiss(game.systemId, hunt.shown, ScrapeOutcome.FAILED,
                    "could not write to ${dest.parent}: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
                ScrapeOutcome.FAILED
            }
        }
        return ScrapeReport(
            counts = mapOf(outcome to 1),
            misses = misses,
            // La busqueda y lo de ahora, no el rato que se estuvo mirando la rejilla.
            millis = hunt.searchMs + (System.currentTimeMillis() - t0),
        )
    }

    /**
     * Los videos que podria tener UN juego, para elegir: ver VideoSnaps.choices. Sin ninguno,
     * el porque, como en el informe.
     */
    suspend fun videoHunt(game: Game): VideoHunt = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        fun done(found: List<Pair<ThumbEntry, Boolean>>, miss: ScrapeMiss?) =
            VideoHunt(game, shown(game), found, miss, System.currentTimeMillis() - started)
        val sys = catalog.byId[game.systemId] ?: return@withContext done(
            emptyList(),
            ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.NO_SOURCE,
                "the console \"${game.systemId}\" is not in the catalog"),
        )
        val found = runCatching { snaps.choices(sys, game, named(game), CHOICES_PER_SOURCE) }
            .onFailure { android.util.Log.w("Ludolog", "video choices: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        if (found.isNotEmpty()) done(found, null) else done(emptyList(), videoMiss(sys, game))
    }

    private fun videoMiss(sys: SystemDef, game: Game): ScrapeMiss {
        snaps.trouble(sys)?.let { return ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.FAILED, it) }
        val why = snaps.why(sys)
        return ScrapeMiss(
            game.systemId, shown(game), if (why != null) ScrapeOutcome.NO_SOURCE else ScrapeOutcome.NO_MATCH,
            why ?: "no gameplay video looks like \"${named(game) ?: game.fileName.substringBeforeLast('.')}\" " +
                "among ${snaps.size(sys)} in the collection",
        )
    }

    /**
     * Baja el video elegido.
     *
     * Primero a un nombre aparte, y solo si llega se quita el que habia: una descarga que se
     * corta no puede dejar al juego sin ninguno. El de antes se va con su sonido; ver
     * [videoFiles].
     */
    suspend fun keepVideo(hunt: VideoHunt, entry: ThumbEntry): ScrapeReport {
        val t0 = System.currentTimeMillis()
        val game = hunt.game
        val sys = catalog.byId[game.systemId]
        val dest = videoFor(game)
        val incoming = File(dest.parentFile, dest.name + ".incoming")
        val got = sys != null && runCatching { snaps.fetchEntry(sys, entry, incoming) }.getOrDefault(false)
        val misses = mutableListOf<ScrapeMiss>()
        val ok = withContext(Dispatchers.IO) {
            if (!got) {
                incoming.delete()
                misses += ScrapeMiss(game.systemId, hunt.shown, ScrapeOutcome.FAILED,
                    "\"${entry.fileName}\" matched but did not arrive")
                return@withContext false
            }
            runCatching {
                dropOld(videoFiles(dest), keep = dest)
                if (!incoming.renameTo(dest)) { incoming.copyTo(dest, overwrite = true); incoming.delete() }
                ArtRevisions.bump(dest)
                true
            }.getOrElse {
                incoming.delete()
                misses += ScrapeMiss(game.systemId, hunt.shown, ScrapeOutcome.FAILED,
                    "could not write to ${dest.parent}: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
                false
            }
        }
        return ScrapeReport(
            counts = emptyMap(),
            misses = misses,
            millis = hunt.searchMs + (System.currentTimeMillis() - t0),
            videos = if (ok) 1 else 0,
        )
    }

    /**
     * El video de un juego sin preguntar, para cuando se renombra: el mismo nombre o casi, como
     * en una pasada entera, y nada mas. Null si llego; si no, el porque.
     */
    suspend fun videoAuto(game: Game): ScrapeMiss? {
        val sys = catalog.byId[game.systemId] ?: return ScrapeMiss(game.systemId, shown(game),
            ScrapeOutcome.NO_SOURCE, "the console \"${game.systemId}\" is not in the catalog")
        val dest = videoFor(game)
        if (VideoRemnants.real(dest)) return null
        val got = runCatching { snaps.fetchTo(sys, game, dest, named(game)) }.getOrDefault(VideoSnaps.Got.FAILED)
        return when (got) {
            VideoSnaps.Got.SAVED -> null
            VideoSnaps.Got.FAILED -> ScrapeMiss(game.systemId, shown(game), ScrapeOutcome.FAILED,
                "the gameplay video matched but did not arrive")
            VideoSnaps.Got.NO_MATCH -> videoMiss(sys, game)
        }
    }

    /** La caratula de un juego con cualquier extension: todas tienen la misma clave en el indice. */
    private fun coverFiles(dest: File): List<File> {
        val stem = dest.name.substringBeforeLast('.')
        return listOf("png", "jpg", "jpeg", "webp").map { File(dest.parentFile, "$stem.$it") }
    }

    /**
     * El video de un juego y lo que se le hizo al sintonizarlo: el sonido separado y la marca de
     * que ya lo esta (ver TapeQueue). Si se quedaran, el nuevo sonaria con el audio del viejo.
     */
    private fun videoFiles(dest: File): List<File> {
        val stem = dest.name.substringBeforeLast('.')
        return listOf("mp4", "webm", "mkv").flatMap { ext ->
            val v = File(dest.parentFile, "$stem.$ext")
            listOf(v) + listOf("m4a", "split", "tape", "clean.m4a", "clean", "digital.m4a", "digital").map { File(v.parentFile, "${v.name}.$it") }
        }
    }

    /**
     * Quita lo que tuviera el juego en la carpeta del scraper, menos [keep]: solo se guarda el
     * arte que se usa, sin copias de lo que habia. Con cualquier extension —una .jpg vieja al lado
     * de la .png nueva tiene la misma clave en el indice, y cual de las dos saliera dependeria
     * del orden en que el sistema listara la carpeta— y, en los videos, con su sonido separado.
     *
     * Se llama con lo nuevo ya escrito al lado y justo antes de ponerlo en su sitio: si bajarlo o
     * escribirlo falla, el juego se queda con lo que tenia en vez de sin nada.
     */
    private fun dropOld(files: List<File>, keep: File) {
        for (old in files) if (old.isFile && old != keep) old.delete()
    }
}

/** Lo que se encontró para un juego buscado a mano, mientras alguien elige. */
internal class CoverHunt(
    val game: Game,
    /** Cómo se llama en la lista, que es como se busca y como se dice en la ventana. */
    val shown: String,
    val choices: List<CoverChoice>,
    /** Por qué no hay ninguna, para el informe. */
    val miss: ScrapeMiss?,
    /** Lo que tardo en encontrarlas, para el informe. */
    val searchMs: Long,
)

/** Los videos encontrados para un juego buscado a mano, mientras alguien elige. */
internal class VideoHunt(
    val game: Game,
    /** Cómo se llama en la lista. */
    val shown: String,
    /** Cada uno con si es el mismo nombre. */
    val choices: List<Pair<ThumbEntry, Boolean>>,
    /** Por qué no hay ninguno, para el informe. */
    val miss: ScrapeMiss?,
    val searchMs: Long,
)

/** Qué se busca de un juego desde su menú. */
internal enum class ArtAsked { COVER, VIDEO }

/** Lo que se está buscando, o guardando ya elegido, para un juego: ver LibraryViewModel.looking. */
internal data class Looking(val path: String, val kind: ArtAsked, val saving: Boolean)

/** Los caracteres que libretro cambia por «_» en los nombres de sus miniaturas. */
private val THUMB_UNSAFE = Regex("""[&*/:`<>?\\|"]""")
