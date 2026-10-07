package com.felp.frontcomp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Hace lo mismo con cada cosa, varias a la vez pero no todas a la vez.
 *
 * Los indices por consola se bajaban de uno en uno: doce consolas son doce viajes seguidos,
 * y el listado de PlayStation solo pesa dos megas y medio. Entre ellos no hay ningun orden,
 * asi que esperar a que termine uno para empezar el siguiente era tiempo regalado.
 *
 * El tope de cuatro no es timidez. Son descargas de servidores ajenos que no cobran nada;
 * abrir doce conexiones de golpe a uno de ellos es la forma de que dejen de atender, y en
 * un aparato de mano con una sola antena tampoco irian mas rapido.
 *
 * Medido en la consola, siete consolas y la cache vacia, alternando las pasadas para que no
 * decidiera la red: de una en una 15,0 / 8,3 / 8,2 segundos; de cuatro en cuatro 3,9 / 5,7 /
 * 4,6. Todas las paralelas por debajo de todas las otras.
 */
internal suspend fun <T> eachAtOnce(items: List<T>, limit: Int = 4, work: suspend (T) -> Unit) {
    if (items.size <= 1) {
        items.forEach { work(it) }
        return
    }
    val gate = kotlinx.coroutines.sync.Semaphore(limit)
    kotlinx.coroutines.coroutineScope {
        items.map { item ->
            async { gate.withPermit { work(item) } }
        }.awaitAll()
    }
}

/*
 * De dónde sale una carátula, fuente por fuente.
 *
 * Cada una responde a la misma pregunta —«¿tienes la carátula de este juego?»— y el scraper
 * las prueba en orden hasta que alguna contesta. El orden es por certeza: la que identifica
 * el juego por un nombre de conjunto de volcado no puede equivocarse de juego; la que lo
 * busca por texto sí, y por eso va después.
 *
 * Una fuente que no está configurada dice por qué, y eso acaba en el informe. Callarse es lo
 * que hacía que un juego sin carátula y un juego que nadie buscó se parecieran.
 */
internal interface CoverSource {
    val id: String
    val name: String

    /** Si puede usarse ya, o le faltan credenciales. */
    fun ready(prefs: Prefs): Boolean

    /**
     * Si esta consola es de su terreno.
     *
     * Preguntarle a Steam por un cartucho de Mega Drive no es un fallo que haya que
     * explicar, es una pregunta que nadie hace. Las fuentes que solo saben de una consola
     * dicen que no aqui, y asi ni se prueban ni salen en el informe diciendo obviedades.
     */
    fun applies(sys: SystemDef): Boolean = true

    /** Lo que haya que traerse una vez por consola antes de empezar. */
    suspend fun warm(systems: List<SystemDef>) {}

    /**
     * Los bytes de la carátula, o null si esta fuente no la tiene.
     *
     * `name` es el titulo que el usuario le puso al juego, si lo renombro. Las fuentes que
     * buscan por texto buscan por EL y no por el fichero: quien renombra un juego lo hace casi
     * siempre porque el nombre del fichero no sirve —un volcado mal nombrado, una traduccion,
     * un hack— y es justo el que ya habia fallado. Las que identifican por un numero —Steam,
     * Switch— no lo necesitan: el numero no cambia con el nombre.
     */
    suspend fun cover(sys: SystemDef, game: Game, name: String? = null): ByteArray?

    /**
     * Las carátulas posibles para UN juego que alguien busca a mano, para que elija.
     *
     * Más relajado que [cover], a propósito: allí nadie mira y una carátula equivocada se
     * queda puesta; aquí elige una persona, y una opción de más no pone nada equivocado.
     * Por defecto, la de [cover] si la hay: las fuentes que identifican por un número —Steam,
     * Switch— no tienen alternativas que ofrecer, y la suya es segura.
     */
    suspend fun choices(sys: SystemDef, game: Game, name: String?): List<CoverChoice> =
        cover(sys, game, name)?.let { bytes ->
            listOf(CoverChoice(this.name, name ?: game.title, bytes, { bytes }, exact = true))
        }.orEmpty()

    /** Por qué no pudo, para el informe. Null si no tiene nada que decir. */
    fun why(sys: SystemDef): String? = null

    /**
     * Si no pudo preguntar por esta consola: sin red, el servidor no contesto, las claves no
     * valen. Null si pregunto.
     *
     * Aparte de [why], que es que no la tiene: aqui no se sabe, y el arreglo es otro —volver
     * a probar, o revisar las claves—, no renombrar nada ni tocar el catalogo.
     */
    fun trouble(sys: SystemDef): String? = null
}

/** Una carátula posible para un juego, para elegirla a mano. */
internal class CoverChoice(
    /** De qué fuente sale. */
    val source: String,
    /** Cómo se llama allí. */
    val label: String,
    /** La imagen que se enseña para elegir. En libretro, la misma que se guarda. */
    val preview: ByteArray,
    /** La que se guarda si se elige. */
    val full: suspend () -> ByteArray?,
    /** El mismo juego sin duda: el mismo nombre, o identificado por su número. */
    val exact: Boolean,
)

/** Cuántas opciones da como mucho cada fuente: más de nueve carátulas ya no se comparan. */
internal const val CHOICES_PER_SOURCE = 9

/* -------------------------------------------------------------------------- libretro */

/** La colección de libretro, envuelta en la interfaz común. */
internal class LibretroSource(
    private val inner: LibretroThumbnails = LibretroThumbnails(),
) : CoverSource {
    override val id = "free"
    override val name = "libretro-thumbnails"

    private val indexes = HashMap<String, ThumbIndex?>()

    override fun ready(prefs: Prefs) = true

    override suspend fun warm(systems: List<SystemDef>) {
        for (sys in systems) synchronized(indexes) { indexes[sys.id] = null }
        // Varias consolas pueden compartir el nombre de conjunto —las regiones de una misma
        // maquina—, asi que se pide por nombre y no por consola: cada listado, una vez.
        val byName = java.util.concurrent.ConcurrentHashMap<String, ThumbIndex>()
        eachAtOnce(systems.distinctBy { it.raName }) { sys ->
            inner.index(sys)?.let { byName[sys.raName] = it }
        }
        for (sys in systems) synchronized(indexes) { indexes[sys.id] = byName[sys.raName] }
    }

    override suspend fun cover(sys: SystemDef, game: Game, name: String?): ByteArray? {
        // Fuera del hilo de la interfaz, que es donde corre el scraper: leer el indice del disco
        // y, con un nombre escrito a mano, recorrerlo entero por palabras.
        val entry = withContext(Dispatchers.IO) {
            val index = synchronized(indexes) { indexes[sys.id] } ?: inner.index(sys)
                ?: return@withContext null
            if (name != null) {
                // Un nombre escrito a mano casi nunca es el del conjunto al pie de la letra: se
                // prueba exacto y, si no, por palabras. Ver ThumbIndex.near.
                index.best(name, game.region) ?: index.near(name, game.region)
            } else {
                index.best(MatchKey.stem(game.fileName), game.region) ?: index.best(game.title, game.region)
            }
        }
        return entry?.let { inner.fetch(sys, it) }
    }

    /**
     * Las de un juego buscado a mano: todas las regiones del mismo nombre y, detrás, los
     * nombres que más palabras comparten con él. Ver ThumbIndex.around.
     */
    override suspend fun choices(sys: SystemDef, game: Game, name: String?): List<CoverChoice> {
        val index = withContext(Dispatchers.IO) { synchronized(indexes) { indexes[sys.id] } ?: inner.index(sys) }
            ?: return emptyList()
        val asked = if (name != null) listOf(name) else listOf(MatchKey.stem(game.fileName), game.title)
        val found = withContext(Dispatchers.IO) { index.around(asked, CHOICES_PER_SOURCE, game.region) }
        // Se bajan ya, para enseñarlas: la de libretro no tiene version pequeña, asi que la
        // que se enseña es la misma que se guarda y elegirla no cuesta otra descarga.
        val bytes = arrayOfNulls<ByteArray>(found.size)
        eachAtOnce(found.indices.toList()) { i ->
            bytes[i] = runCatching { inner.fetch(sys, found[i].first) }.getOrNull()
        }
        return found.indices.mapNotNull { i ->
            val b = bytes[i] ?: return@mapNotNull null
            val (entry, exact) = found[i]
            CoverChoice(this.name, entry.fileName.substringBeforeLast('.'), b, { b }, exact)
        }
    }

    /** Cuántos nombres tiene esta consola en la colección, para el informe. */
    fun size(sys: SystemDef): Int = synchronized(indexes) { indexes[sys.id] }?.size ?: 0

    override fun trouble(sys: SystemDef): String? =
        inner.failure(sys.raName)?.let { "could not get its list of names ($it)" }

    override fun why(sys: SystemDef): String? = when {
        sys.raName.isEmpty() -> "the catalog has no No-Intro name (raName) for this console"
        synchronized(indexes) { indexes[sys.id] } != null -> null
        // Sin listado porque no se pudo traer, no porque no lo haya: eso lo dice trouble().
        inner.failure(sys.raName) != null -> null
        else -> "$name has nothing for \"${sys.raName}\""
    }
}

/* ------------------------------------------------------------------------------ IGDB */

/**
 * Una base de datos de juegos de verdad: conoce todas las plataformas, guarda los títulos
 * alternativos y es la única de las tres que cataloga romhacks.
 *
 * Con las credenciales del usuario y no con una de la aplicación. Una clave dentro de un APK
 * se lee con unzip y grep, sería un único cupo para todas las instalaciones, y revocable
 * para todas a la vez el día que alguien abusara.
 *
 * Solo se le pregunta por la plataforma de la que salió el fichero. Buscar por nombre a
 * secas es lo que pone la carátula de Super Mario Bros. sobre el 3 sin que nadie lo note; si
 * la respuesta nunca contuvo juegos de otra plataforma, esa equivocación no puede ocurrir.
 *
 * De una en una y con freno: el servicio admite cuatro peticiones por segundo y contesta a
 * una ráfaga con un 429 y nada más.
 */
internal class IgdbSource : CoverSource {
    override val id = "igdb"
    override val name = "IGDB"

    private var token: String? = null
    private var tokenUntil = 0L
    private var clientId = ""
    private var secret = ""

    override fun ready(prefs: Prefs): Boolean {
        clientId = prefs.credential("igdb.id")
        secret = prefs.credential("igdb.secret")
        return clientId.isNotEmpty() && secret.isNotEmpty()
    }

    override fun why(sys: SystemDef): String? =
        if (platformOf(sys.id) == null) "IGDB has no platform mapped for \"${sys.id}\"" else null

    /**
     * Por que no se pudo entrar, si no se pudo: el secreto rechazado, o sin red. Con el
     * testigo sin sacar, cada juego contestaba «no la tengo» y el informe no lo distinguia de
     * una busqueda sin resultado.
     */
    @Volatile private var signIn: String? = null

    /** Lo ultimo que fallo al preguntar, para decirlo: ver [post]. */
    @Volatile private var lastFailure: String? = null

    override fun trouble(sys: SystemDef): String? =
        if (platformOf(sys.id) == null) null else signIn

    // En el hilo de E/S, las dos. El scraper corre en el de la interfaz, y ahi Android no deja
    // abrir una conexion: lanza NetworkOnMainThreadException, `post` se la tragaba y la fuente
    // contestaba «no la tengo» a todo sin que nada lo dijera. Steam y Switch ya lo hacian asi.
    override suspend fun cover(sys: SystemDef, game: Game, name: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            val ids = platformOf(sys.id) ?: return@withContext null
            val tok = token() ?: return@withContext null
            // Una busqueda que no llego a contestar no es un «no la tiene»: si ninguna forma de
            // escribirlo encontro nada y alguna se cayo, se lanza, y el scraper lo apunta como
            // fallo (ver ArtScraper.missFor).
            var broke: String? = null
            for (spelling in spellings(game, name)) {
                val games = search(tok, ids, spelling)
                if (games == null) { broke = lastFailure ?: "no answer"; continue }
                val url = pick(games, spelling) ?: continue
                get(url)?.let { return@withContext it }
            }
            broke?.let { throw java.io.IOException(it) }
            null
        }

    /**
     * Las formas de escribir un juego que vale la pena probar, de mejor a peor.
     *
     * El nombre del fichero va primero: es un nombre de conjunto de volcado, y es bajo ese
     * nombre como está catalogado. Después el título ya limpio, y por último el mismo
     * partido en palabras, porque un buscador no separa «PES2013» solo y esa consulta
     * devuelve cero.
     *
     * Si el usuario lo renombró, solo su nombre —y el mismo partido en palabras—: ver `cover`.
     */
    private fun spellings(game: Game, name: String?): List<String> {
        if (name != null) return listOf(name, spaced(name)).distinct().filter { it.length > 1 }
        val raw = game.fileName.substringBeforeLast('.')
        val clean = Regex("""[\(\[][^\)\]]*[\)\]]""").replace(raw, " ")
            .replace(Regex("""\s+"""), " ").trim()
        return listOf(clean, game.title, spaced(clean)).distinct().filter { it.length > 1 }
    }

    private fun spaced(s: String) = Regex("""(?<=\p{L})(?=\p{N})|(?<=\p{N})(?=\p{L})""").replace(s, " ")

    private fun searchable(s: String) = s
        .replace(Regex("""['’]"""), "")
        .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun search(tok: String, ids: List<Int>, spelling: String): JSONArray? {
        val body = "search \"${searchable(spelling)}\"; " +
            "fields name,alternative_names.name,cover.image_id,first_release_date; " +
            "where platforms = (${ids.joinToString(",")}); limit 30;"
        val text = post(
            "https://api.igdb.com/v4/games", body,
            mapOf("Client-ID" to clientId, "Authorization" to "Bearer $tok"),
        ) ?: return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    /**
     * Cuál de las respuestas es el juego pedido, si alguna.
     *
     * Todas están ya en la plataforma correcta; lo que queda es si el nombre es el mismo
     * título. Primero las mismas palabras exactas; después, que el nombre local empiece por
     * el suyo y siga —eso es el «Ultimate Edition» que una tienda pone y una base de datos
     * no—. Nada más: una tercera regla más laxa es la que empieza a colocar carátulas
     * equivocadas, y una carátula equivocada es peor que un hueco.
     */
    private fun pick(games: JSONArray, spelling: String): String? {
        val mine = MatchKey.of(spelling)
        // Solo las que tienen caratula. Si la primera del mismo nombre no tenia —un duplicado,
        // un recopilatorio, un port sin ficha completa—, se devolvia su nada y no se miraban
        // las siguientes, que podian ser el juego de verdad con la suya.
        val all = (0 until games.length()).mapNotNull { games.optJSONObject(it) }
            .filter { coverId(it) != null }
        all.firstOrNull { g -> names(g).any { MatchKey.of(it) == mine } }?.let { return image(it) }
        all.firstOrNull { g ->
            names(g).any { val t = MatchKey.of(it); t.length > 8 && mine.startsWith(t) }
        }?.let { return image(it) }
        return null
    }

    /**
     * Las de un juego buscado a mano: lo que conteste el buscador en su plataforma y tenga
     * carátula, sin la criba de [pick] —el mismo nombre primero y el resto en el orden en que
     * las da IGDB, que ya es por parecido—. Se enseñan en pequeño y se baja la grande solo de
     * la que se elija.
     */
    override suspend fun choices(sys: SystemDef, game: Game, name: String?): List<CoverChoice> =
        withContext(Dispatchers.IO) { choicesHere(sys, game, name) }

    private suspend fun choicesHere(sys: SystemDef, game: Game, name: String?): List<CoverChoice> {
        val ids = platformOf(sys.id) ?: return emptyList()
        val tok = token() ?: return emptyList()
        val seen = HashSet<String>()
        val found = mutableListOf<Pair<JSONObject, Boolean>>()
        for (spelling in spellings(game, name)) {
            val games = search(tok, ids, spelling) ?: continue
            val mine = MatchKey.of(spelling)
            for (i in 0 until games.length()) {
                val g = games.optJSONObject(i) ?: continue
                val id = coverId(g) ?: continue
                if (seen.add(id)) found += g to names(g).any { MatchKey.of(it) == mine }
            }
            if (found.size >= CHOICES_PER_SOURCE) break
        }
        val best = found.sortedByDescending { it.second }.take(CHOICES_PER_SOURCE)
        val previews = arrayOfNulls<ByteArray>(best.size)
        eachAtOnce(best.indices.toList()) { i ->
            previews[i] = coverId(best[i].first)?.let { get(coverUrl(it, "t_cover_big")) }
        }
        return best.indices.mapNotNull { i ->
            val p = previews[i] ?: return@mapNotNull null
            val (g, exact) = best[i]
            val id = coverId(g) ?: return@mapNotNull null
            val year = g.optLong("first_release_date", 0L).takeIf { it > 0 }
                ?.let { "  ·  " + java.util.Calendar.getInstance().apply { timeInMillis = it * 1000 }.get(java.util.Calendar.YEAR) }
                .orEmpty()
            val full = suspend { withContext(Dispatchers.IO) { get(coverUrl(id, "t_cover_big_2x")) } }
            CoverChoice(this.name, g.optString("name") + year, p, full, exact)
        }
    }

    /** El nombre de una respuesta y sus títulos alternativos. */
    private fun names(g: JSONObject): List<String> = buildList {
        g.optString("name").takeIf { it.isNotEmpty() }?.let { add(it) }
        g.optJSONArray("alternative_names")?.let { alt ->
            for (i in 0 until alt.length()) {
                alt.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    }

    private fun coverId(g: JSONObject): String? =
        g.optJSONObject("cover")?.optString("image_id")?.takeIf { it.isNotEmpty() }

    private fun image(g: JSONObject): String? = coverId(g)?.let { coverUrl(it, "t_cover_big_2x") }

    private fun coverUrl(id: String, size: String) = "https://images.igdb.com/igdb/image/upload/$size/$id.jpg"

    /**
     * Un testigo, acuñado con las credenciales y guardado hasta poco antes de caducar.
     * Twitch los da para unos dos meses, así que esto se pide seis veces al año.
     */
    private fun token(): String? {
        token?.takeIf { System.currentTimeMillis() < tokenUntil }?.let { return it }
        // El secreto en el cuerpo y no en la direccion: una direccion acaba en registros —de un
        // proxy, de una herramienta de depuracion, de este mismo programa— y el cuerpo no.
        lastFailure = null
        val text = post(
            "https://id.twitch.tv/oauth2/token",
            "client_id=${enc(clientId)}&client_secret=${enc(secret)}&grant_type=client_credentials",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        val t = text
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { obj -> obj.optString("access_token").takeIf { it.isNotEmpty() }?.let { it to obj } }
        if (t == null) {
            // Un 400 o un 403 es la pareja de claves que no vale; lo demas, la red.
            val why = lastFailure ?: "no answer"
            signIn = if (why == "HTTP 400" || why == "HTTP 401" || why == "HTTP 403") {
                "Twitch rejected the Client ID or secret ($why)"
            } else "could not sign in to Twitch ($why)"
            return null
        }
        val (tok, obj) = t
        signIn = null
        token = tok
        tokenUntil = System.currentTimeMillis() + (obj.optLong("expires_in", 3600L) - 600L) * 1000L
        return tok
    }

    private var lastAt = 0L

    /** Una petición cada 300 ms como mucho, vengan de donde vengan. */
    @Synchronized
    private fun pace() {
        val wait = lastAt + 300L - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAt = System.currentTimeMillis()
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String? {
        repeat(2) {
            pace()
            val r = runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    doOutput = true
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val stream = if (code == 200) c.inputStream else c.errorStream
                code to (stream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: "")
            }.onFailure { log(url, it.javaClass.simpleName); lastFailure = failureOf(it) }
                .getOrNull() ?: return null
            when (r.first) {
                200 -> return r.second
                429 -> { Thread.sleep(1_200L); lastFailure = "HTTP 429" }
                else -> { log(url, "HTTP ${r.first}"); lastFailure = "HTTP ${r.first}"; return null }
            }
        }
        return null
    }

    private fun get(url: String): ByteArray? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Ludolog")
        }
        if (c.responseCode != HttpURLConnection.HTTP_OK) null
        else c.inputStream.use { it.readBytes() }.takeIf { it.size > 500 }
    }.getOrNull()

    /**
     * Por qué no contestó, en el registro: callarse es lo que escondió que esta fuente no
     * funcionaba en ningún caso. Sin la consulta de la dirección, porque en la del testigo va
     * el secreto.
     */
    private fun log(url: String, what: String) {
        val u = runCatching { URL(url) }.getOrNull()
        android.util.Log.w("Ludolog", "IGDB ${u?.host}${u?.path}: $what")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Cómo numera IGDB cada plataforma.
     *
     * Los números van en la consulta, y son lo que deja fuera de la respuesta a cualquier
     * otra plataforma. Los ids de consola son los del catálogo de esta aplicación.
     */
    private fun platformOf(systemId: String): List<Int>? = PLATFORMS[systemId.lowercase()]

    private companion object {
        val PLATFORMS: Map<String, List<Int>> = mapOf(
            "nes" to listOf(18, 99), "famicom" to listOf(18, 99), "fds" to listOf(51),
            "snes" to listOf(19, 58), "sfc" to listOf(19, 58),
            "n64" to listOf(4), "gb" to listOf(33), "gbc" to listOf(22, 33),
            "gba" to listOf(24), "nds" to listOf(20, 159), "3ds" to listOf(37, 137),
            "gamecube" to listOf(21), "wii" to listOf(5), "wiiu" to listOf(41),
            "switch" to listOf(130), "virtualboy" to listOf(87),
            "psx" to listOf(7), "ps2" to listOf(8), "ps3" to listOf(9), "ps4" to listOf(48),
            "psp" to listOf(38), "psvita" to listOf(46),
            "megadrive" to listOf(29), "genesis" to listOf(29),
            "sega32x" to listOf(30, 29), "segacd" to listOf(78, 29),
            "mastersystem" to listOf(64), "gamegear" to listOf(35),
            "saturn" to listOf(32), "dreamcast" to listOf(23),
            "pc" to listOf(6, 13), "dos" to listOf(13), "steam" to listOf(6),
            "android" to listOf(34),
            "arcade" to listOf(52, 79, 80), "neogeo" to listOf(80, 79, 52),
            "atari2600" to listOf(59), "atari5200" to listOf(66), "atari7800" to listOf(60),
            // Con los ids del catalogo: como «atarilynx», «atarijaguar» y «amstradcpc» no
            // casaban nunca, y esas tres salian siempre sin plataforma.
            "lynx" to listOf(61), "jaguar" to listOf(62),
            "pcengine" to listOf(86), "wonderswan" to listOf(57),
            "xbox" to listOf(11), "xbox360" to listOf(12), "3do" to listOf(50),
            "msx" to listOf(27), "amiga" to listOf(16), "c64" to listOf(15),
            "zxspectrum" to listOf(26), "cpc" to listOf(25),
            "colecovision" to listOf(68), "intellivision" to listOf(67),
            "pico8" to listOf(6),
        )
    }
}
