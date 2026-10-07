package com.felp.frontcomp

import android.content.Context
import android.os.Build
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// El modelo de la biblioteca: lo que se ve en la lista y lo que pasa al lanzar. Salio de MainActivity.kt
// el 07-10-2026, tal cual.

class LibraryViewModel(app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {
    val prefs = Prefs(app)

    // Los renders elegidos por consola, antes de que nadie pinte una. Ver RenderChoices.
    init { RenderChoices.load(prefs.systemRenders()) }

    var catalog by mutableStateOf<Catalog?>(null); private set
    var result by mutableStateOf<ScanResult?>(null); private set
    var art by mutableStateOf<ArtIndex?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /**
     * Cuantos trabajos hay en marcha: [busy] es que haya alguno.
     *
     * Con un simple verdadero/falso, el primero que acababa lo apagaba aunque otro siguiera: un
     * repaso de la biblioteca —lo lanza cada cambio en las carpetas de ROMs— terminaba en
     * decimas en mitad de una descarga de arte, y «Fetch artwork» volvia a poder pulsarse con la
     * primera todavia corriendo. Dos pasadas a la vez escriben los mismos ficheros.
     */
    private var jobs = 0

    private fun begin() { jobs++; busy = true }
    private fun end() { jobs = (jobs - 1).coerceAtLeast(0); busy = jobs > 0 }

    /** El ultimo repaso pedido: uno que acabe despues que otro mas nuevo no pisa su resultado. */
    private var scanGen = 0

    var progress by mutableStateOf<ScrapeProgress?>(null); private set
    var report by mutableStateOf<ScrapeReport?>(null); private set

    /** Si el informe del último scrapeo está a la vista. Se abre solo al terminar. */
    var reportOpen by mutableStateOf(false)

    var emulators by mutableStateOf<Emulators?>(null); private set
    /** Los muebles de televisor disponibles, del fichero del usuario mas el dibujado. */
    var tvSkins by mutableStateOf(listOf(TvSkin.Drawn)); private set

    /**
     * La sala de fondo, si hay alguna declarada a pantalla completa.
     *
     * Puede haber varias versiones de la misma sala, y cada una dice de que depende (ver
     * [TvSkin.requires]). Manda la que MAS pide de entre las que se cumplen: con The Reckoning
     * encendido sale la del libro sobre la mesita; con Ludolog Link instalado, la de la radio en
     * el suelo; con las dos cosas, la de las dos. Sin ninguna, la que no pide nada.
     *
     * Una sala con un requisito que aqui no se conoce se queda fuera, y es a proposito: una
     * condicion mal escrita tiene que quitar el decorado y notarse, no colarse siempre.
     */
    val scene: TvSkin? get() {
        // El tema manda primero. Cada uno puede traer su tvs.toml, pero tambien hay uno comun
        // en el aparato, y un tema sin sala propia se encontraria ese de fondo: cambia la letra
        // y el color, y el escenario sigue siendo de otro. Y tiene que decidirse AQUI
        // y no en quien dibuja: media pantalla mira este mismo valor para saber si hay panel
        // derecho o no, y contestando que si en un sitio y que no en otro se queda sin los dos.
        if (!themeById(prefs.themeId).room) return null
        val rooms = tvSkins.filter { it.fullScreen && it.corners.size == 8 }
        val plain = rooms.firstOrNull { it.needs.isEmpty() }
        // Lo que se cumple aqui. Link solo se mira (si esta instalado): Ludolog no le pide nada
        // para esto, solo saca su radio. Ver LinkSaveCheck.present.
        val have = buildSet {
            if (prefs.logbook) add("reckoning")
            if (LinkSaveCheck.present.value) add("link")
        }
        // Y solo si sus imagenes estan de verdad en el aparato.
        //
        // Sin esto, encender The Reckoning en una instalacion a la que todavia no se le han
        // copiado los PNG dejaria la pantalla SIN SALA: la app no dibuja el fondo cuando no
        // encuentra el fichero, asi que un decorado que falta no se ve como un decorado que
        // falta, se ve como que la aplicacion se rompio. Si falta la de las dos cosas se cae a
        // la de una, y si falta esa, a la de siempre, que ya esta ahi.
        return rooms.filter { it.needs.isNotEmpty() && have.containsAll(it.needs) }
            .sortedByDescending { it.needs.size }
            .firstOrNull { it.imageFile() != null } ?: plain
    }

    var launcher by mutableStateOf<Launcher?>(null); private set

    fun loadCatalog(readAsset: (String) -> String) {
        runCatching { catalog = CatalogLoader.load(readAsset) }
            .onFailure { error = "Catalog: ${it.message}" }
        runCatching {
            val e = Emulators.parse(readAsset("emulators.toml"))
            emulators = e
            catalog?.let {
                launcher = Launcher(e, it) { sys, path ->
                    if (path != null) prefs.coreForGame(path) else prefs.coreForSystem(sys)
                }
            }
        }.onFailure { error = "Emulators: ${it.message}" }
        runCatching { tvSkins = TvSkins.load() }
    }

    /**
     * Vuelve a leer las salas. Se llama al cambiar de tema.
     *
     * Hace falta porque el fichero de salas ya no es uno para todo el aparato: cada tema
     * puede traer el suyo, y el que manda es el del tema puesto. Sin esto, cambiar de tema
     * dejaba en pantalla la sala que declaraba el anterior hasta reabrir la aplicacion.
     */
    fun reloadRooms() {
        runCatching { tvSkins = TvSkins.load() }
    }

    /** Donde vive la biblioteca guardada: en la carpeta privada, que nadie mas toca. */
    private val cacheFile = File(app.filesDir, "library.idx")

    /**
     * Lo guardado la ultima vez, puesto en pantalla al instante.
     *
     * Se llama al abrir, antes de recorrer nada. Reconstruir la lista desde las rutas es
     * puro texto, asi que la biblioteca esta ahi desde el primer fotograma en vez de vacia
     * esperando a que alguien pulse un boton.
     */
    fun loadRemembered() {
        val cat = catalog ?: return
        if (result != null) return
        result = LibraryCache.load(cacheFile, Detector(cat))
    }

    /**
     * Recorre las carpetas de ROMs y rehace la biblioteca.
     *
     * `auto` es el repaso que se hace solo al abrir: no molesta con nada y solo sirve para
     * ponerse al dia con lo que haya cambiado desde la ultima vez. El de a mano, ademas,
     * puede encadenar la busqueda de arte si esa opcion esta puesta.
     */
    fun scan(auto: Boolean = false) {
        val cat = catalog ?: return
        begin(); error = null
        val gen = ++scanGen
        // Las carpetas de medios pueden haber cambiado desde la ultima vez, sobre todo si
        // acaba de pasar el scraper. Es el unico momento en que hace falta volver a mirar.
        ArtIndex.invalidateRoots()
        viewModelScope.launch {
            var ok0: Any? = null
            withContext(Dispatchers.IO) {
                runCatching {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val det = Detector(cat)
                    val scan = Scanner(det, cat).scan(Scanner.roots(prefs))
                    // Solo el mas nuevo se guarda: dos cambios seguidos en las carpetas de ROMs
                    // lanzan dos repasos, y el primero puede acabar el ultimo.
                    if (gen == scanGen) LibraryCache.save(cacheFile, scan)
                    val t1 = android.os.SystemClock.elapsedRealtime()
                    val index = ArtIndex.build()
                    android.util.Log.i("Ludolog", "repaso: ${scan.games.size} juegos en ${t1 - t0} ms, arte ${android.os.SystemClock.elapsedRealtime() - t1} ms")
                    scan to index
                }
            }.also { ok0 = it.getOrNull() }.onSuccess { (s, a) -> if (gen == scanGen) { result = s; art = a } }
             .onFailure { error = "Scan: ${it.message}" }
            // Con el resultado de ESTE repaso: con el campo comun, el fallo de otro repaso o del
            // scraper le hacia saltarse las fichas a uno que habia ido bien.
            val ok = ok0 != null
            end()
            if (ok && gen == scanGen) afterScan(auto)
        }
    }

    /**
     * Lo que sigue a un repaso: poner al dia las fichas —leer por dentro lo nuevo y buscarlo en el
     * catalogo— y, si toca, el scraper. En ese orden: con la ficha, el scraper busca la caratula
     * por el nombre exacto del juego y no por el del fichero.
     *
     * El scraper solo sigue solo en el primer arranque, recien acabada la bienvenida, o tras un
     * repaso pedido a mano con la opcion puesta: encadenar descargas en cada arranque seria una
     * sorpresa desagradable. La opcion existia y no la leia nadie.
     */
    private fun afterScan(auto: Boolean) {
        val first = DataHome.firstRun
        if (first) DataHome.firstRun = false
        val scrapeAfter = first || (!auto && prefs.scrapeOnScan)
        // Al relanzar, solo lo nuevo: ver Dossiers.refresh. El repaso a mano lo mira todo.
        readGames(thorough = !auto) { if (scrapeAfter) scrape(background = true) }
    }

    /** Como va la descarga de temas desde los ajustes: «downloading», el error, o nulo. */
    var looksNote by mutableStateOf<String?>(null); private set

    /**
     * Baja los temas que falten, del mismo sitio que el catalogo: lo que la bienvenida ofrece y
     * alguien no bajo entonces. Ver ThemeStore.
     */
    fun downloadLooks() {
        if (looksNote == "downloading") return
        looksNote = "downloading"
        viewModelScope.launch {
            looksNote = withContext(Dispatchers.IO) {
                val offers = ThemeStore.remoteIndex(ThemeStore.SOURCE).getOrElse {
                    return@withContext if (ThemeStore.SOURCE.isBlank()) "not published yet" else "no connection"
                }
                val failed = offers.filter { it.id in ThemeStore.OFFERED && !ThemeStore.installed(it.id) }
                    .filter { ThemeStore.install(ThemeStore.SOURCE, it).isFailure }
                if (failed.isEmpty()) null else "failed: " + failed.joinToString { it.title }
            }
            reloadRooms()
        }
    }

    /** La pasada de fichas en marcha: cuantos van, de cuantos, y cual. */
    var readingGames by mutableStateOf<ScrapeProgress?>(null); private set

    /** Lo que hizo la ultima pasada de fichas, para los ajustes. */
    internal var readReport by mutableStateOf<Dossiers.Report?>(null); private set

    /** Sube cada vez que cambian las fichas: la tarjeta vuelve a leer la suya. */
    var dossierRevision by mutableStateOf(0); private set

    /** Por que no se pudo bajar el catalogo la ultima vez, o nulo. */
    var catalogError by mutableStateOf<String?>(null); private set

    /**
     * Pone al dia las fichas de la biblioteca: ver Dossiers.refresh. Con el catalogo apagado no
     * hace nada: sin catalogo, leer los juegos por dentro no sirve para nada todavia.
     *
     * [force] baja el catalogo aunque se haya mirado hace poco (el boton de los ajustes).
     */
    // Una lectura de fichas a la vez (07-10-2026): un repaso que termina, Link y la fila del catalogo
    // la lanzan sin mirar si ya hay una, y dos a la vez bajaban el mismo paquete al mismo .part.
    private val readingLock = kotlinx.coroutines.sync.Mutex()

    fun readGames(force: Boolean = false, thorough: Boolean = true, then: () -> Unit = {}) {
        // Con los de Android: el catalogo los conoce por su paquete (ver Dossiers.refresh).
        val games = result?.games.orEmpty() + androidGames
        if (!prefs.catalogOn || games.isEmpty()) { then(); return }
        begin()
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { readingLock.withLock {
                runCatching {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    syncCatalog(games.map { it.systemId }.toSet(), force)
                    val t1 = android.os.SystemClock.elapsedRealtime()
                    Dossiers.refresh(games, useCatalog = true, thorough = thorough) { done, total, title ->
                        // Cada diez: con miles de juegos, un cambio de estado por juego es mas
                        // trabajo para la pantalla que para la lectura.
                        if (done % 10 == 0 || done == total - 1) readingGames = ScrapeProgress(done + 1, total, title, 0)
                    }.also { r ->
                        android.util.Log.i("Ludolog", "fichas: ${r.total} juegos, ${r.read} leidos por dentro, " +
                            "catalogo ${t1 - t0} ms, fichas ${android.os.SystemClock.elapsedRealtime() - t1} ms")
                    }
                }.onFailure { catalogError = it.message ?: it.javaClass.simpleName }.getOrNull()
            } }
            readingGames = null
            report?.let { readReport = it }
            dossierRevision++
            end()
            then()
        }
    }

    /**
     * Baja el catalogo de estas consolas si falta alguno de los publicados o hace una semana
     * que no se mira. Sin red, o sin catalogo publicado todavia, se sigue con lo que haya: las
     * fichas ya hechas valen igual.
     */
    private fun syncCatalog(consoles: Set<String>, force: Boolean) {
        // Y los paquetes donde se busca lo que el de una consola no tenga: ver Dossiers.ALSO_LOOK.
        val systems = consoles + consoles.flatMap { Dossiers.ALSO_LOOK[it].orEmpty() }
        val source = prefs.catalogSource ?: GameDb.SOURCE
        if (source.isBlank()) return
        val missing = GameDb.missingFor(systems)
        val stale = System.currentTimeMillis() - prefs.catalogChecked > 7L * 24 * 3600 * 1000
        if (!force && !missing && !stale) return
        GameDb.update(source, systems)
            .onSuccess { catalogError = null; prefs.catalogChecked = System.currentTimeMillis() }
            .onFailure { catalogError = it.message ?: it.javaClass.simpleName }
    }

    /**
     * Lo que falta por traer: la caratula, el video, o los dos.
     *
     * El video cuenta como falta porque en esta interfaz el panel es un televisor, y un
     * juego con caratula y sin video deja media pantalla apagada. Solo cuenta cuando la
     * consola tiene coleccion de videos en el catalogo y quien mira los quiere ver.
     */
    fun missing(systemId: String? = null): List<Game> {
        val all = result?.games.orEmpty()
        val scoped = if (systemId == null) all else all.filter { it.systemId == systemId }
        val a = art ?: return scoped
        val wantVideo = prefs.anyThemePlaysVideo
        return scoped.filter { g ->
            val noCover = !a.has(g)
            val noVideo = wantVideo &&
                catalog?.byId?.get(g.systemId)?.videoSnaps?.isNotEmpty() == true &&
                a.video(g) == null
            noCover || noVideo
        }
    }

    /**
     * Busca lo que falte de arte.
     *
     * En segundo plano no cambia el trabajo, cambia quien manda en la pantalla: no se abre
     * el informe al terminar. Eso es lo unico que interrumpia a quien estaba navegando,
     * porque el avance ya se veia como un contador pequeno en la barra de arriba y nunca
     * tapo nada. El informe no se pierde: queda en «Last fetch result».
     *
     * Sigue atado a la aplicacion. Si el sistema la mata, la descarga se corta, y lo que
     * haya bajado hasta entonces se queda: la siguiente pasada continua por donde iba.
     */
    fun scrape(systemId: String? = null, background: Boolean = false) {
        val cat = catalog ?: return
        val todo = missing(systemId)
        if (todo.isEmpty() || busy) return

        begin(); error = null; report = null
        progress = ScrapeProgress(0, todo.size, "…", 0)
        viewModelScope.launch {
            runCatching {
                val r = ArtScraper(cat, prefs).run(todo, art) { progress = it }
                r to withContext(Dispatchers.IO) { freshIndex() }
            }.onSuccess { (r, a) -> report = r; art = a; reportOpen = !background }
             .onFailure { error = "Scraper: ${it.message}" }
            progress = null
            end()
        }
    }

    /**
     * El indice de arte despues de bajar algo, con las carpetas de medios vueltas a mirar.
     *
     * La primera caratula crea <datos>/media, y las carpetas guardadas (ver
     * ArtIndex.defaultRoots) se miraron antes, sin ella: en una carpeta de datos nueva el
     * informe decia «120 new» y la lista seguia sin ninguna hasta reescanear a mano.
     */
    private fun freshIndex(): ArtIndex {
        ArtIndex.invalidateRoots()
        return ArtIndex.build()
    }

    /** Vuelve a leer el catálogo desde los assets y los archivos del usuario. */
    fun reloadCatalog(ctx: Context) {
        loadCatalog { n -> ctx.assets.open(n).bufferedReader().use { it.readText() } }
    }

    /** Las carátulas encontradas para un juego buscado a mano, mientras alguien elige una. */
    internal var coverHunt by mutableStateOf<CoverHunt?>(null); private set

    /**
     * Lo que se está buscando o guardando para un juego desde su menú, mientras dura. El menú
     * se queda abierto y lo dice en la fila: cerrarlo y dejar solo un contador arriba parecía
     * que no había pasado nada.
     */
    internal var looking by mutableStateOf<Looking?>(null); private set

    /**
     * Busca la carátula de un solo juego, desde su menú contextual.
     *
     * Más suelto que una pasada entera, porque aquí mira alguien. Si sale una sola y es del
     * mismo nombre, se pone sin preguntar; si sale más de una, o una que solo se parece, se
     * enseñan todas y elige él. Ver ThumbIndex.around.
     */
    fun scrapeOne(game: Game) = work(game, ArtAsked.COVER) { scraper ->
        val hunt = scraper.hunt(game)
        val only = hunt.choices.singleOrNull()?.takeIf { it.exact }
        when {
            only != null -> finish(scraper, game, scraper.keep(hunt, only))
            // La ventana de elegir; el paso se cierra al elegir o al cancelar.
            hunt.choices.isNotEmpty() -> coverHunt = hunt
            else -> finish(scraper, game, hunt.miss?.let { m ->
                ScrapeReport(mapOf(m.outcome to 1), listOf(m), hunt.searchMs)
            })
        }
    }

    /** La que se eligió en la ventana. Ver ArtScraper.keep. */
    internal fun pickCover(choice: CoverChoice) {
        val hunt = coverHunt ?: return
        coverHunt = null
        work(hunt.game, ArtAsked.COVER, saving = true, chosen = true) { scraper -> finish(scraper, hunt.game, scraper.keep(hunt, choice)) }
    }

    /**
     * Cerrar la ventana sin elegir: no se guarda nada y lo que tenía se queda. Si tras un
     * cambio de nombre quedaba su vídeo por buscar, se busca igual: son dos cosas distintas.
     */
    fun dropCoverHunt() {
        val hunt = coverHunt ?: return
        coverHunt = null
        if (videoAfter?.path == hunt.game.path) work(hunt.game, ArtAsked.VIDEO, chosen = true) { scraper -> finish(scraper, hunt.game, null) }
    }

    /** Los vídeos encontrados para un juego buscado a mano, mientras alguien elige uno. */
    internal var videoHunt by mutableStateOf<VideoHunt?>(null); private set

    /**
     * Busca el vídeo de partida de un solo juego, desde su menú: igual que la carátula, uno
     * solo del mismo nombre se baja sin preguntar y si no se elige de una lista. De una lista
     * y no de una rejilla: para enseñar cada vídeo habría que bajarlos todos, y pesan megas.
     */
    fun fetchVideo(game: Game) = work(game, ArtAsked.VIDEO) { scraper ->
        val hunt = scraper.videoHunt(game)
        val only = hunt.choices.singleOrNull()?.takeIf { it.second }
        when {
            only != null -> finish(scraper, game, scraper.keepVideo(hunt, only.first))
            hunt.choices.isNotEmpty() -> videoHunt = hunt
            else -> finish(scraper, game, hunt.miss?.let { m -> ScrapeReport(emptyMap(), listOf(m), hunt.searchMs) })
        }
    }

    /** El que se eligió en la lista. Ver ArtScraper.keepVideo. */
    internal fun pickVideo(entry: ThumbEntry) {
        val hunt = videoHunt ?: return
        videoHunt = null
        work(hunt.game, ArtAsked.VIDEO, saving = true, chosen = true) { scraper -> finish(scraper, hunt.game, scraper.keepVideo(hunt, entry)) }
    }

    fun dropVideoHunt() { videoHunt = null }

    /**
     * Tras renombrar un juego se busca con el nombre nuevo lo que le falte, que suele ser justo
     * por lo que se renombra. La carátula si no tiene, como «Fetch box art», con su ventana si
     * hay que elegir. El vídeo solo si el tema los pone y le falta, detrás de la carátula y sin
     * preguntar: el mismo nombre o casi, como en una pasada entera.
     *
     * Devuelve si ha empezado a buscar algo.
     */
    fun afterRename(game: Game): Boolean {
        if (busy || catalog == null) return false
        val needCover = art?.has(game) != true
        val needVideo = prefs.playVideo && art?.video(game) == null
        if (!needCover && !needVideo) return false
        videoAfter = if (needVideo) game else null
        if (needCover) scrapeOne(game) else work(game, ArtAsked.VIDEO) { scraper -> finish(scraper, game, null) }
        return true
    }

    /** El juego cuyo vídeo queda por buscar al cerrar el paso de la carátula. Ver [afterRename]. */
    private var videoAfter: Game? = null

    /**
     * Un paso de buscar arte para un juego: ocupado mientras dura, con su progreso arriba.
     * Si falla, el error se cuenta y lo pendiente se olvida.
     *
     * [chosen]: el paso que sigue a algo elegido en una ventana, que ya se cerro. Ese va aunque haya
     * otra cosa en marcha (un repaso que lanzo Link, por ejemplo): antes volvia sin hacer nada y lo
     * elegido se perdia sin decirlo.
     */
    private fun work(game: Game, asked: ArtAsked, saving: Boolean = false, chosen: Boolean = false, step: suspend (ArtScraper) -> Unit) {
        val cat = catalog ?: return
        if (busy && !chosen) return
        begin(); error = null; report = null
        progress = ScrapeProgress(0, 1, displayTitle(game), 0)
        looking = Looking(game.path, asked, saving)
        viewModelScope.launch {
            runCatching { step(ArtScraper(cat, prefs)) }
                .onFailure { error = "Scraper: ${it.message}"; videoAfter = null }
            progress = null
            looking = null
            end()
        }
    }

    /**
     * Cierra un paso: el vídeo que quedara pendiente, el índice nuevo y el informe. Todo en
     * un informe: tras un cambio de nombre, carátula y vídeo se cuentan juntos.
     */
    private suspend fun finish(scraper: ArtScraper, game: Game, done: ScrapeReport?) {
        var r = done
        if (videoAfter?.path == game.path) {
            videoAfter = null
            // Ahora lo que se busca es el video: que lo diga su fila y no la de la caratula.
            looking = Looking(game.path, ArtAsked.VIDEO, saving = false)
            val t0 = System.currentTimeMillis()
            val miss = scraper.videoAuto(game)
            val base = r ?: ScrapeReport(emptyMap(), emptyList(), 0)
            val took = System.currentTimeMillis() - t0
            r = if (miss == null) base.copy(videos = base.videos + 1, millis = base.millis + took)
                else base.copy(misses = base.misses + miss, millis = base.millis + took)
        }
        art = withContext(Dispatchers.IO) { freshIndex() }
        if (r != null) { report = r; reportOpen = true }
    }

    /** El nombre a mostrar: el que el usuario puso, si lo hay. */
    fun displayName(sys: SystemDef?, systemId: String): String =
        prefs.systemName(systemId) ?: sys?.name ?: if (systemId == FAVORITES_SYSTEM) "Favorites" else systemId

    /**
     * El nombre que se ve: el puesto a mano; si no, el del catalogo cuando identifico el juego
     * con seguridad (por su huella o su nombre exacto, no por parecido), sin sus etiquetas de
     * region; y si no, el que se saca del fichero. Asi «mslug» sale «Metal Slug - Super
     * Vehicle-001» sin tener que renombrarlo.
     */
    fun displayTitle(game: Game): String =
        prefs.gameTitle(game)
            ?: Dossiers.get(game)?.takeIf { it.exact }?.name?.let(GameDb::base)?.takeIf { it.isNotBlank() }
            ?: game.title

    /**
     * El nombre con que lo apunta el cuaderno, y con el que se le buscan las horas: el que se ve,
     * salvo en un perfil de DoomForge, que se apunta como su IWAD (ver Game.playsAs). Asi
     * «DOOM + Brutal» y «DOOM (v1.9)» son en el cuaderno el mismo Doom, cada uno en su fila de la
     * lista.
     */
    fun bookTitle(game: Game): String = game.playsAs ?: displayTitle(game)

    /**
     * El fichero que se abre de un juego: el mismo, o en uno de varios discos, el que se eligio
     * la ultima vez. El juego sigue siendo el primero para todo lo demas —su emulador, su ficha,
     * sus partidas—, asi que las horas de los tres discos son las de un juego.
     */
    fun discFor(game: Game): Game =
        game.discs.getOrNull(prefs.discIndex(game))?.takeIf { it != game.path }?.let { game.copy(path = it) } ?: game

    /** Lanza un juego con el emulador recordado, o el primero instalado. */
    fun play(ctx: Context, game: Game): String? {
        // Un juego de Android no tiene fichero ni emulador: se abre el paquete y ya.
        game.appPackage?.let { pkg ->
            val err = AppsRepo.launch(ctx, AppEntry(game.title, pkg, AppSlot.GAME))
            if (err == null) {
                prefs.recordPlayed(game)
                SessionTracker.started(ctx, game, pkg, bookTitle(game), missionsFor(game).firstOrNull()?.how)
            }
            return err
        }
        // Un acceso anclado desde otra app (GameHub): se abre como lo abriria la pantalla de inicio,
        // sin emulador de por medio. Ver PinShortcut.kt.
        if (Catalog.extensionOf(game.fileName) == PinnedShortcuts.EXT) {
            val err = PinnedShortcuts.launch(ctx, java.io.File(game.path))
            if (err == null) {
                prefs.recordPlayed(game)
                SessionTracker.started(
                    ctx, game, PinnedShortcuts.read(java.io.File(game.path))?.pkg, bookTitle(game),
                    missionsFor(game).firstOrNull()?.how,
                )
            }
            return err
        }
        val l = launcher ?: return "No launcher"
        // Un comprimido en una consola que no los admite (ver SystemDef.zipOk): se lista, porque
        // esta en su carpeta, pero ningun emulador suyo lo abre. Dolphin contestaba «Could not
        // recognize file content://…» con el Paper Mario de GameCube de una consola de pruebas; mejor decir
        // aqui que hay que descomprimirlo.
        catalog?.let { cat ->
            val sys = cat.byId[game.systemId]
            val ext = Catalog.extensionOf(game.fileName)
            if (sys != null && !sys.zipOk && ext in cat.zipExtensions && ext !in sys.extensions) {
                return "${displayName(sys, sys.id)} emulators can't open .$ext files. Extract the game first."
            }
        }
        val options = l.available(ctx.packageManager, game)
        val chosen = prefs.resolveEmulator(game)
        val def = chosenDef(options, chosen)
        // El disco que toca, en uno de varios: es el fichero que recibe el emulador.
        val file = discFor(game)
        // Y si la que toca no sabe abrir ESTE fichero —GameNative con un `.desktop`, o con un
        // `.gamehub` de otra tienda—, la siguiente que si. Se rendia a la primera con «Could not
        // prepare launch» aunque hubiera otro lanzador instalado que lo abria.
        fun next(skip: EmulatorDef?) =
            options.filter { it != skip }.firstNotNullOfOrNull { l.build(ctx, file, it) }
        val intent = when {
            def != null -> l.build(ctx, file, def, chosen.takeIf { it != def.id }) ?: next(def)
            // Un paquete suelto, elegido desde el menu de la consola: la tabla no lo
            // conoce, asi que se le entrega el juego a la manera generica.
            chosen != null && isInstalled(ctx, chosen) -> l.buildForPackage(ctx, file, chosen)
            else -> next(null)
        } ?: return if (options.isEmpty() && chosen == null) "No emulator installed"
                    else "Could not prepare launch"
        // Con Ludolog Link instalado, antes: ¿hay una partida mas nueva de este juego en otro device?
        // Es la unica espera que Ludolog tiene por Link, y solo si Link esta. Ver LinkSaveCheck.
        val pkg = intent.`package` ?: intent.component?.packageName
        if (pkg != null && LinkSaveCheck.available(ctx)) {
            checkSavesThenStart(ctx, game, file, pkg, intent)
            return null
        }
        return start(ctx, game, intent)
    }

    /**
     * La pregunta a Link en curso o su respuesta, si hay que avisar: el juego, lo que se iba a lanzar,
     * y la respuesta (nula mientras se pregunta). Ver LinkSaveCheck.
     */
    class SaveCheck(
        val game: Game, val intent: android.content.Intent, val answer: LinkSaveCheck.Answer?,
        /** El ROM y el emulador, para volver a preguntar tras encender compartir (ver [syncOnThenStart]). */
        val file: Game? = null, val pkg: String? = null,
    )
    var saveCheck by mutableStateOf<SaveCheck?>(null)

    /** Un aviso de abajo que no viene de una accion de la pantalla (lo que contesto Link, un fallo al lanzar). */
    var notice by mutableStateOf<String?>(null)

    private fun checkSavesThenStart(ctx: Context, game: Game, file: Game, pkg: String, intent: android.content.Intent) {
        val pending = SaveCheck(game, intent, null)
        saveCheck = pending
        viewModelScope.launch {
            // Si Link no contesta en unos segundos, se lanza igual: nunca se queda uno sin jugar por el.
            val ask = async(Dispatchers.IO) {
                LinkSaveCheck.ask(ctx, pkg, java.io.File(file.path).name, bookTitle(game))
            }
            val answer = kotlinx.coroutines.withTimeoutOrNull(9_000) { ask.await() }
            if (saveCheck !== pending) return@launch
            when (answer?.result) {
                "stale", "conflict", "off" -> saveCheck = SaveCheck(game, intent, answer, file, pkg)
                else -> {
                    saveCheck = null
                    if (answer?.result == "synced") notice = "Got the latest save from ${answer.peer ?: "another device"}."
                    start(ctx, game, intent)?.let { notice = it }
                }
            }
        }
    }

    /**
     * En el aviso "off": volver a compartir con los devices y preguntar otra vez, que ahora Link
     * trae la partida (o avisa de un conflicto). Lo pide la persona: es su decision, no de Link.
     */
    fun syncOnThenStart(ctx: Context) {
        val c = saveCheck ?: return
        val file = c.file ?: return answerSaveCheck(ctx, play = false)
        val pkg = c.pkg ?: return answerSaveCheck(ctx, play = false)
        val pending = SaveCheck(c.game, c.intent, null)
        saveCheck = pending
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LinkSaveCheck.syncOn(ctx) }
            if (saveCheck === pending) checkSavesThenStart(ctx, c.game, file, pkg, c.intent)
        }
    }

    /** Lo que se dijo en el aviso de Link: lanzar igual (o no). */
    fun answerSaveCheck(ctx: Context, play: Boolean) {
        val c = saveCheck ?: return
        saveCheck = null
        if (play) start(ctx, c.game, c.intent)?.let { notice = it }
    }

    private fun start(ctx: Context, game: Game, intent: android.content.Intent): String? {
        val l = launcher ?: return "No launcher"
        return runCatching {
            ctx.startActivity(intent)
            // Por si RetroArch se cierra al recibirlo, que la 1.22.2 lo hace: ver Bounce.
            if (l.isRetroArch(intent)) Bounce.launched(intent) else Bounce.clear()
            prefs.recordPlayed(game)
            // Con el emulador ya en marcha, no antes: una partida que no llego a abrirse
            // no es una partida.
            SessionTracker.started(
                ctx, game, intent.`package` ?: intent.component?.packageName, bookTitle(game),
                missionsFor(game).firstOrNull()?.how,
            )
            null
        }.getOrElse { "Did not start: ${it.javaClass.simpleName}" }
    }

    /**
     * La de la tabla que se eligio, por su id o por su paquete.
     *
     * Por su paquete tambien: una eleccion guardada como app suelta que la tabla ya conoce para
     * esta consola se lanza como la de la tabla. Asi quedaron elegidos los arcade con RetroArch
     * desde el menu de la consola, sin nucleo, y no arrancaban. Y por cualquiera de sus
     * paquetes, no solo el primero instalado: con RetroArch y su fork a la vez, el fork elegido
     * como app suelta se lanzaba a la manera generica, sin nucleo.
     */
    private fun chosenDef(options: List<EmulatorDef>, chosen: String?): EmulatorDef? =
        chosen?.let { id ->
            options.firstOrNull { it.id == id } ?: options.firstOrNull { id in it.packages() }
        }

    /**
     * La de la tabla con que se abriria un juego de esta consola: lo mismo que decide [play], sin
     * lanzar nada. La elegida si esta instalada, y si no, la primera de la tabla. `chosen` es la
     * eleccion que cuenta: la del juego o la de la consola. Nula si gana una app suelta —elegida
     * y fuera de la tabla— o si no hay nada instalado.
     */
    internal fun launchDef(pm: android.content.pm.PackageManager, systemId: String, chosen: String?): EmulatorDef? {
        val l = launcher ?: return null
        val options = l.availableFor(pm, systemId)
        chosenDef(options, chosen)?.let { return it }
        if (chosen != null && runCatching { pm.getPackageInfo(chosen, 0) }.isSuccess) return null
        return options.firstOrNull()
    }

    /** Lo mismo dicho para enseñarlo: «Snes9x (RetroArch)», o el nombre de la app suelta. */
    internal fun launchName(pm: android.content.pm.PackageManager, systemId: String, chosen: String?): String? {
        val l = launcher ?: return null
        launchDef(pm, systemId, chosen)?.let { return l.labelFor(it, systemId) }
        if (chosen == null) return null
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(chosen, 0)).toString() }.getOrNull()
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /* -------------------------------------------------------- juegos de Android */

    /** Las apps que el usuario mandó a la consola de Android, como entradas de biblioteca. */
    var androidGames by mutableStateOf<List<Game>>(emptyList()); private set

    fun refreshAndroidGames(ctx: Context) {
        androidGames = AppsRepo.list(ctx, emulators, prefs)
            .filter { !it.hidden && it.slot == AppSlot.GAME }
            .map { it.asGame() }
    }

    fun gamesOf(systemId: String): List<Game> = when (systemId) {
        ANDROID_SYSTEM -> androidGames
        FAVORITES_SYSTEM -> favorites()
        else -> result?.bySystem?.get(systemId).orEmpty()
    }

    /** Los juegos marcados como favoritos, de todas las consolas, por nombre. */
    fun favorites(): List<Game> =
        (result?.games.orEmpty() + androidGames).filter { prefs.isFavorite(it) }
            .sortedBy { displayTitle(it).lowercase() }

    /* ------------------------------------------------ lo que sabe el Companion */

    /**
     * Lo que el Companion sabe de cada consola y cada juego, para la tarjeta de la lista.
     *
     * Leido entero de una vez y guardado aqui, no consultado al moverse: la tarjeta cambia con
     * cada fila, y abrir la base en cada paso del cursor seria tocar disco para escribir tres
     * renglones. Nulo mientras se lee o si no se pudo leer, y entonces la tarjeta cuenta lo de
     * siempre.
     */
    internal var played by mutableStateOf<LogStats.Played?>(null); private set

    /**
     * La pagina de sinopsis de la tarjeta viva, mientras toca: su texto y de donde sale. La pone la
     * lista de juegos y la lee tambien el dialogo de la sala, que en el Parlour es donde se escribe
     * la tarjeta. Nula cuando toca la de las horas.
     */
    internal var cardStory by mutableStateOf<Pair<String, String>?>(null)

    /** Lo que trajo la ultima partida, para anunciarlo. Ver news y CelebrationCard. */
    internal var celebration by mutableStateOf<Celebration?>(null)
    private var reading: kotlinx.coroutines.Job? = null

    internal fun readPlayed(ctx: Context) {
        val app = ctx.applicationContext
        // Con el catalogo de esta pantalla, que es con el que se buscan las consolas en el
        // cuaderno (ver LogStats.source). Quien llama vuelve a leer cuando cambia.
        val cat = catalog
        // La biblioteca de ahora, con el nombre que se ve en la lista de cada juego.
        val games = result?.games.orEmpty() + androidGames
        // La lectura anterior se cancela: si acabara despues, dejaria puesto el cuaderno viejo.
        reading?.cancel()
        reading = viewModelScope.launch {
            played = withContext(Dispatchers.IO) {
                runCatching {
                    // Como se llama hoy cada juego, para leer las partidas con ese nombre (ver
                    // GameNames), y apuntado en el cuaderno de esta consola para las otras.
                    val names = GameNames.of(games, ::displayTitle).also { GameNames.current = it }
                    // Y cuando se marco terminado cada juego, para las otras consolas.
                    val done = games.mapNotNull { g -> prefs.completedAt(g)?.let { (g.systemId to fileKey(g.fileName)) to it } }.toMap()
                    runCatching { Logbook(app).use { book -> book.remember(namesToRemember(book, names, cat, done)) } }
                        .onFailure { android.util.Log.w("Ludolog", "nombres: ${it.javaClass.simpleName}") }
                    Logbook(app, withOthers = true).use { db ->
                        val s = LogStats(db, cat, names)
                        val p = s.played(Build.MODEL.orEmpty().ifEmpty { "handheld" })
                        // Sin cancelar a medias: news apunta lo nuevo como visto, y si la lectura se
                        // cancelaba despues —al arrancar se lee dos veces, y la segunda cancela la
                        // primera— lo visto quedaba apuntado y la tarjeta no salia nunca.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            runCatching { news(s, db) }
                                .onFailure { android.util.Log.w("Ludolog", "personaje: ${it.javaClass.simpleName}") }
                                .getOrNull()?.let { c -> kotlinx.coroutines.withContext(Dispatchers.Main) { celebration = c } }
                        }
                        p
                    }
                }.getOrNull()
            }
        }
    }

    /**
     * Lo nuevo desde la ultima vez que se miro: nivel, logros conseguidos, misiones cumplidas y lo
     * que dio la ultima partida. Nulo si no hay nada que contar.
     *
     * La primera vez no se anuncia nada y solo se apunta lo que hay: con el personaje recien
     * estrenado, anunciar quince logros de golpe seria ruido, no una celebracion.
     */
    private suspend fun news(s: LogStats, book: Logbook): Celebration? {
        val plays = s.plays()
        // Sin el catalogo de consolas todavia no se sabe el año de ninguna, y los logros de epocas
        // salian como no conseguidos. Al arrancar se lee dos veces, la primera antes del catalogo:
        // «Modern Times» quedaba fuera de lo visto y la segunda lectura lo anunciaba otra vez, en
        // cada reinicio. Se espera a la lectura con catalogo.
        val cat = catalog ?: return null
        val years = cat.systems.associate { it.id to it.year }
        val completions = s.completions(prefs.completions)
        val missions = Missions.settle(prefs, book, plays, completions, years)
        val done = s.missionsDone()
        val c = Metagame.of(plays.map { it.session }, System.currentTimeMillis(), Missions.bonuses(done))
        val earned = Achievements.of(Achievements.Input(plays, c, completions, done.size, years)).filter { it.earned }
        val lastId = plays.lastOrNull()?.session?.id
        val seenLevel = prefs.seenLevel
        val seenAch = prefs.seenAchievements
        val seenSession = prefs.seenSession
        // Lo visto solo crece: un logro anunciado no se vuelve a anunciar aunque una lectura a
        // medias no lo vea, y el nivel apuntado es el mas alto que se haya visto.
        prefs.seenLevel = maxOf(seenLevel ?: 0, c.level)
        prefs.seenAchievements = seenAch + earned.map { it.a.id }
        prefs.seenSession = lastId
        if (seenLevel == null) return null
        val newAch = earned.filter { it.a.id !in seenAch }.map { it.a.name }
        val gain = if (lastId != null && lastId != seenSession) c.gains[lastId] else null
        if (c.level <= seenLevel && newAch.isEmpty() && missions.isEmpty() && gain == null) return null
        return Celebration(seenLevel, c.level, gain, newAch, missions.map { it.title to it.reward })
    }

    /**
     * Los nombres de hoy de los juegos jugados en esta consola que siguen en la biblioteca: los
     * que se apuntan en su cuaderno (ver Logbook.remember). Los que ya no estan se quedan con el
     * ultimo que se apunto.
     */
    private fun namesToRemember(
        book: Logbook,
        names: GameNames,
        cat: Catalog?,
        done: Map<Pair<String, String>, Long>,
    ): List<Logbook.NameRef> {
        val now = System.currentTimeMillis()
        // Los jugados aqui, y tambien los marcados como terminados aunque nunca se jugaran aqui:
        // «Completed» viaja en esta tabla, y sin fila no llegaba a la otra consola. Y los que ya
        // tenian fila de terminado, para que desmarcarlos llegue tambien.
        val played = book.playedFiles().map { (raw, title) -> (cat?.canonicalId(raw) ?: raw) to fileKey(title) }
        val completed = runCatching { book.completedFiles() }.getOrDefault(emptyList())
        return (played + done.keys + completed).distinct().mapNotNull { (system, file) ->
            names.name(system, file)?.let {
                Logbook.NameRef(
                    system, file, it, names.identity(system, file), now,
                    names.ownGenres(system, file).joinToString("|").ifEmpty { null },
                    done[system to file],
                )
            }
        }.distinctBy { it.system to it.file }
    }

    /**
     * Las misiones que se siguen y que este juego sirve para cumplir: las que marcan su fila en la
     * lista y las que recuerda la tarjeta al lanzarlo. Ver Missions.
     */
    internal fun missionsFor(game: Game): List<Missions.Mission> {
        val tracked = prefs.trackedMissions
        if (tracked.isEmpty()) return emptyList()
        val d = Dossiers.get(game)
        val tally = played?.game(game.systemId, game.fileName, bookTitle(game))
        val c = Missions.Candidate(
            system = game.systemId,
            genres = Genres.canon(d?.genres.orEmpty()),
            consoleYear = catalog?.byId?.get(game.systemId)?.year ?: 0,
            playedMs = tally?.totalMs ?: 0L,
            lastPlayed = tally?.lastAt,
            completed = prefs.completedAt(game) != null,
        )
        return tracked.keys.mapNotNull(Missions::byId).filter { it.eligible(c) }
    }

    /** Correcciones aplicadas desde otras consolas (LinkEdits): nombres, descripciones y fichas se releen. */
    fun linkEdited() {
        prefs.touchLabels()
        dossierRevision++
    }

    /**
     * El genero de un juego puesto a mano, o nulo para volver al del catalogo. Va a su ficha, y de
     * ahi a la referencia del cuaderno, para que las otras consolas lo lean con sus partidas.
     */
    fun setGenre(game: Game, genre: String?) {
        Dossiers.setOwn(game, "my.genre", genre)
        LinkEdits.game(getApplication(), game, "genre", genre)
        dossierRevision++
    }

    /**
     * La carga que le queda a la bateria, en microamperios-hora, para decir cuanto daria de si
     * en el juego elegido. Nulo si el aparato no la dice.
     *
     * Se lee cada minuto y no al mover el cursor: preguntarle a la bateria es una llamada al
     * sistema, y en un minuto no cambia lo bastante como para que la cifra se note.
     */
    internal var chargeUah by mutableStateOf<Long?>(null); private set

    internal suspend fun readCharge(ctx: Context) {
        val app = ctx.applicationContext
        chargeUah = withContext(Dispatchers.IO) { runCatching { Telemetry().chargeMicroAh(app) }.getOrNull() }
    }

    /**
     * Las consolas con algo dentro, la de Android incluida, de más llena a más vacía.
     *
     * Sale de un solo sitio para que la lista, los gatillos y los detalles estén siempre
     * de acuerdo sobre qué consolas hay y en qué orden.
     */
    fun libraryGroups(): List<Pair<String, List<Game>>> {
        val merged = LinkedHashMap<String, List<Game>>(result?.bySystem.orEmpty())
        // Sale siempre, aunque esté vacía: es la única forma de que alguien descubra que
        // puede mandarle apps. Al ordenar por tamaño se queda la última hasta que tenga
        // algo, y quien no la quiera la esconde como cualquier otra.
        merged[ANDROID_SYSTEM] = androidGames
        // Los favoritos, primero y solo si hay alguno: son lo que uno viene a buscar.
        val favs = favorites()
        return (if (favs.isEmpty()) emptyList() else listOf(FAVORITES_SYSTEM to favs)) + merged.entries
            .filterNot { prefs.isSystemHidden(it.key) }
            .sortedByDescending { it.value.size }
            .map { it.key to it.value }
    }
}
