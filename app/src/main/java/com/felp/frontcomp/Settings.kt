package com.felp.frontcomp

import android.content.Context
import java.io.File

/**
 * One line in the settings window.
 *
 * Deliberately small: a title, a sentence explaining it, and what it does. Everything the
 * settings window shows is one of these four shapes, so the window never has to know
 * about any particular setting.
 */
sealed interface SettingItem {
    val title: String
    val description: String
    /** Shown on the right of the row, so the current state reads without selecting it. */
    val value: String

    data class Toggle(
        override val title: String,
        override val description: String,
        val on: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : SettingItem {
        override val value: String get() = if (on) "ON" else "OFF"
    }

    data class Action(
        override val title: String,
        override val description: String,
        override val value: String = "",
        val enabled: Boolean = true,
        /**
         * Si pide una segunda A, lo que dice la fila mientras espera. Para lo que no se deshace:
         * la primera A arma y la segunda hace; moverse de fila lo desarma.
         */
        val confirm: String? = null,
        val run: () -> Unit,
    ) : SettingItem

    data class Info(
        override val title: String,
        override val description: String,
        val lines: List<String>,
    ) : SettingItem {
        override val value: String get() = lines.firstOrNull().orEmpty()
    }

    /**
     * Un nivel en decibelios que se mueve con izquierda y derecha, y A lo hace sonar: la
     * calibracion de cada sonido. Ver SoundGains.
     */
    data class Level(
        override val title: String,
        override val description: String,
        val db: Int,
        val onStep: (Int) -> Unit,
        val onPlay: () -> Unit,
    ) : SettingItem {
        override val value: String get() = when {
            db == 0 -> "0 dB"
            db > 0 -> "+$db dB"
            else -> "−${-db} dB"
        }
    }

    data class Submenu(
        override val title: String,
        override val description: String,
        val id: String,
        override val value: String = "",
    ) : SettingItem
}

/** Una pestana de los ajustes: su nombre y lo que se ajusta en ella. */
data class SettingsTab(val title: String, val items: List<SettingItem>)

/**
 * Builds the settings, tab by tab.
 *
 * Only settings that actually do something are listed. A settings screen full of switches
 * that change nothing is worse than a short one, because it makes the user doubt the ones
 * that do work.
 */
object SettingsModel {

    /**
     * Las pestanas, en el orden en que se recorren con los hombros.
     *
     * Eran una sola lista de veinticinco filas en el orden en que se fueron anadiendo: el
     * volumen del panel al lado de las carpetas de ROMs, borrar las preferencias debajo del
     * catalogo, y el tema escondido en un submenu junto con el sonido y el video. Por
     * pestanas cada fila esta donde uno la busca, y lo que se recorre con la cruceta son
     * cinco filas y no veinticinco.
     *
     * Primero lo que mas se toca —como se comporta, como se ve, como suena—, despues la
     * biblioteca y su arte, el cuaderno, y al final los datos, que se miran poco y se borran
     * menos.
     */
    fun build(
        ctx: Context,
        vm: LibraryViewModel,
        onClose: () -> Unit = {},
        onBright: (Boolean) -> Unit = {},
        /** Abre lo de Android para hacer de Ludolog la app de inicio, o dejar de serlo. */
        onHome: () -> Unit = {},
        onReload: () -> Unit,
    ): List<SettingsTab> {
        val prefs = vm.prefs
        val cat = vm.catalog
        val res = vm.result
        val iface = mutableListOf<SettingItem>()
        val theme = mutableListOf<SettingItem>()
        val sound = mutableListOf<SettingItem>()
        val library = mutableListOf<SettingItem>()
        val artwork = mutableListOf<SettingItem>()
        val data = mutableListOf<SettingItem>()

        // Lo que depende del tema va delante en su pestana: en la de la interfaz, el video del
        // panel y la barra antes que los atajos, que se tocan una vez y ya.
        lookItems(ctx, vm, iface, sound, theme, onBright)
        // La calibracion, en su propio submenu: once filas no caben bien en la pestaña.
        sound += SettingItem.Submenu(
            title = "Sound levels",
            description = "Set the level of each sound of this look by ear: move, open, close, " +
                "menu, the Companion, the level-up, achievement and mission tunes, the ambience " +
                "and the video sound.",
            id = "levels",
        )

        // ------------------------------------------------------------------ library
        library += SettingItem.Action(
            title = "Scan now",
            description = "Walk the ROM folders again and rebuild the library. It also runs " +
                "on its own when the app opens, so this is for after adding files.",
            value = res?.let {
                val how = if (it.remembered) "remembered" else "${it.millis} ms"
                "${it.games.size} games  ·  $how"
            } ?: "never",
            enabled = !vm.busy,
            run = vm::scan,
        )

        // ------------------------------------------------------------ game catalog
        // Justo debajo del escaneo, que es lo que lo pone en marcha: ver LibraryViewModel.afterScan.
        library += SettingItem.Toggle(
            title = "Game catalog",
            description = "Reads each game by what is inside the file and looks it up in " +
                "Ludolog's catalog: its exact name, genre, year, developer and story. Off, " +
                "artwork is searched by file name.",
            on = prefs.catalogOn,
            onChange = { prefs.catalogOn = it; if (it) vm.readGames() },
        )

        library += SettingItem.Action(
            title = "Update game catalog",
            description = "Download the catalog for your consoles if there is a newer one, and " +
                "look every game up again. It also checks on its own once a week.",
            value = when {
                vm.readingGames != null -> "reading"
                vm.catalogError != null && GameDb.version() == null -> "failed"
                // Solo el dia: la version lleva tambien la hora, que aqui no dice nada.
                else -> GameDb.version()?.substringBefore('.') ?: "not downloaded"
            },
            enabled = prefs.catalogOn && !vm.busy,
            run = { vm.readGames(force = true) },
        )

        library += SettingItem.Info(
            title = "Identified games",
            description = "How the games in the library were recognised. Exactly means by what " +
                "is inside the file, or by its exact name; the rest only by their title.",
            lines = catalogLines(vm),
        )

        library += SettingItem.Submenu(
            title = "Catalog source",
            description = "Where the catalog comes from. Empty is Ludolog's own. A folder on " +
                "this device works too, to try a new one before it is published.",
            id = "catalogsource",
            value = prefs.catalogSource ?: if (GameDb.SOURCE.isBlank()) "not set" else "Ludolog",
        )

        library += SettingItem.Submenu(
            title = "ROM folders",
            description = "Where games are looked for: found on their own on internal storage " +
                "and on the card, or the folders you pick.",
            id = "romfolders",
            value = prefs.romDirs?.let { if (it.size == 1) it.first() else "${it.size} chosen" }
                ?: "automatic",
        )

        res?.unknownFolders?.takeIf { it.isNotEmpty() }?.let { unknown ->
            library += SettingItem.Info(
                title = "Unrecognised folders",
                description = "They hold ROMs but their name matches no system in the catalog. " +
                    "Add them as an alias or as a new system in your own .toml.",
                lines = unknown.map { it.substringAfterLast('/') }.take(20),
            )
        }

        // Los .toml del usuario que no se pudieron leer. Se saltan enteros para que uno roto no
        // deje la aplicacion sin arrancar, y el mensaje —con su linea— se guardaba y no lo
        // leia nadie: los cambios de ese fichero desaparecian sin decir por que.
        CatalogLoader.lastErrors.takeIf { it.isNotEmpty() }?.let { errors ->
            library += SettingItem.Info(
                title = "Catalog files not read",
                description = "Your own .toml files that have a mistake. Each one was skipped " +
                    "whole: fix the line it names and rescan.",
                lines = errors.take(20),
            )
        }

        // ------------------------------------------------------------------- box art
        // Una vez por pasada: los ajustes se rehacen con cada A, y cada llamada recorre la biblioteca.
        val missing = vm.missing().size
        artwork += SettingItem.Action(
            title = "Fetch artwork",
            description = "Covers and gameplay videos, from every configured source in turn. " +
                "The open ones need no account.",
            value = missing.let { if (it == 0) "complete" else "$it missing" },
            enabled = !vm.busy && missing > 0,
            run = { vm.scrape() },
        )

        // Bajar cincuenta videos lleva un minuto largo, y quedarse mirando un contador no
        // es forma de pasarlo. Esto lo lanza y devuelve la pantalla a quien la estaba
        // usando: el avance se sigue viendo arriba y el informe espera en su fila.
        artwork += SettingItem.Action(
            title = "Fetch in the background",
            description = "The same as above, but it gives the screen back to you and does " +
                "not interrupt with the report when it finishes.",
            value = if (vm.busy) "running" else "",
            enabled = !vm.busy && missing > 0,
            run = { vm.scrape(background = true); onClose() },
        )

        artwork += SettingItem.Toggle(
            title = "Fetch after scanning",
            description = "When a scan finishes, automatically look for the artwork that is missing.",
            on = prefs.scrapeOnScan,
            onChange = { prefs.scrapeOnScan = it },
        )

        // El informe se abre solo al terminar un scrapeo, pero hasta ahora al cerrarlo se
        // perdía. Desde aquí se vuelve a abrir mientras la aplicación siga viva, que es
        // cuando sirve: para mirar con calma por qué un juego concreto no tiene carátula.
        artwork += SettingItem.Action(
            title = "Last fetch result",
            description = "What the last fetch found, and why the rest did not resolve.",
            value = vm.report?.let { "${it.fetched} new  ·  ${it.misses.size} unresolved" }
                ?: "none yet",
            enabled = vm.report != null,
            run = { vm.reportOpen = true },
        )

        artwork += SettingItem.Info(
            title = "Image folders",
            description = "Where box art is read from. Media already downloaded by other frontends is reused.",
            lines = ArtIndex.defaultRoots().map { it.path }.ifEmpty { listOf("none found") } +
                listOf("", "Indexed images: ${vm.art?.fileCount ?: 0}"),
        )

        artwork += SettingItem.Action(
            title = "Clear scraper cache",
            description = "The per-system indexes downloaded from libretro. They are fetched again next time.",
            value = cacheSummary(),
            enabled = !vm.busy && cacheDir().isDirectory,
            run = { cacheDir().deleteRecursively() },
        )

        // Los recortes de archive.org duran unos treinta y seis segundos. Guardar quince es
        // guardar lo que se llega a ver, y la tarjeta lo nota. El sonido se quita siempre,
        // porque el panel reproduce en silencio y ese audio no lo oye nadie.
        artwork += SettingItem.Action(
            title = "Video length",
            description = "How much of each gameplay video is kept. Shorter means smaller " +
                "files, and it applies to what is downloaded from now on.",
            value = prefs.clipSeconds.let { if (it == 0) "full" else "${it}s" },
            run = {
                val at = CLIPS.indexOf(prefs.clipSeconds)
                prefs.clipSeconds = CLIPS[(if (at < 0) 0 else at + 1) % CLIPS.size]
            },
        )


        // Casi la mitad de las colecciones vienen a 640 por 480, que en un panel de mano no
        // se aprovecha. Bajarlas cuesta unos segundos de procesador por video, una sola vez.
        artwork += SettingItem.Action(
            title = "Video quality",
            description = "The size gameplay videos are stored at. Anything taller is scaled " +
                "down when it is downloaded; anything smaller is left alone.",
            value = prefs.videoHeight.let { if (it == 0) "as downloaded" else "${it}p" },
            run = {
                val at = HEIGHTS.indexOf(prefs.videoHeight)
                prefs.videoHeight = HEIGHTS[(if (at < 0) 0 else at + 1) % HEIGHTS.size]
            },
        )

        // El cuaderno se puede apagar entero. Apagado no apunta y no se abre: dejar la
        // pantalla accesible y vacia seria peor que no tenerla. Es lo unico suyo que queda en los
        // ajustes del front-end, porque apagado no hay donde entrar a encenderlo: lo demas —la
        // tarjeta, la partida mas corta, exportar e importar el cuaderno— esta en las opciones del
        // propio Companion, Select o su engranaje (pedido del usuario, 07-10-2026).
        iface += SettingItem.Toggle(
            title = "Companion",
            description = "The record of what you launch from here: how long, what it cost the " +
                "battery, how hot it ran and how it went. L2 + R2 opens it; its options are inside, " +
                "with Select or its gear.",
            on = prefs.logbook,
            onChange = {
                prefs.logbook = it
                if (it) kotlin.concurrent.thread { Logbooks.create() }
            },
        )

        // Los atajos, en su propia pantalla. Son seis y cada uno pide una frase para
        // explicarse: en la lista general serian seis filas que no dicen nada sueltas.
        iface += SettingItem.Submenu(
            title = "Shortcuts",
            description = "What every button on the pad does outside a list: settings, the " +
                "context menu, the app drawer and the Companion.",
            id = "shortcuts",
            value = "${Shortcuts.editable.size} of ${Shortcuts.Action.entries.size}",
        )

        iface += SettingItem.Submenu(
            title = "Hidden apps",
            description = "Apps taken out of the drawer with Hide, in its Start menu. Pick one to show it again.",
            id = "hiddenapps",
            value = prefs.hiddenApps.size.let { if (it == 0) "none" else "$it hidden" },
        )

        // La app de inicio. La ultima de la pestana: se toca una vez en la vida del aparato, y
        // las de arriba mantienen su sitio. Ver HomeApp. No sale si esta app no puede serlo.
        if (HomeApp.possible(ctx)) iface += SettingItem.Action(
            title = "Home app",
            description = if (HomeApp.held) {
                "Ludolog is what the Home button and a restart open. To go back to the previous " +
                    "launcher, pick it in Android's home settings, which open from here."
            } else {
                "Makes Ludolog what the Home button and a restart open, instead of the current " +
                    "launcher. Android asks before changing anything."
            },
            value = if (HomeApp.held) "ON" else "OFF",
            run = onHome,
        )

        artwork += SettingItem.Submenu(
            title = "Art sources",
            description = "Where box art comes from: the open sources need nothing, IGDB takes " +
                "an account you make yourself.",
            id = "artsources",
            value = "${ART_TIERS.count { it.ready(prefs) }} of ${ART_TIERS.size} ready",
        )

        // Lo que la television dejo hecho por el camino: los recortes con su sonido ya
        // separado. Borrarlos no pierde nada que no se pueda rehacer, y es la forma de
        // volver a aplicar un filtro nuevo a todo despues de cambiarlo.
        // Una sola vez: recorre las carpetas de videos de cada consola, y los ajustes se rehacen con cada A.
        val tv = tvMedia()
        artwork += SettingItem.Action(
            title = "Clear TV media",
            description = "Delete the gameplay videos Ludolog downloaded, and the sound it " +
                "separated from every video. They are made again the next time you tune each " +
                "one. Videos from other frontends stay.",
            value = tvMediaSummary(tv),
            // Con una descarga en marcha no: borraba los videos recien bajados y sus .part.
            enabled = !vm.busy && tv.isNotEmpty(),
            // Y se vuelve a mirar la biblioteca. Sin esto el indice sigue creyendo que los
            // videos estan ahi, y la fila de descarga se queda apagada porque no falta nada.
            run = { clearTvMedia(); vm.scan() },
        )

        // ----------------------------------------------------------------- consoles
        library += SettingItem.Submenu(
            title = "Console manager",
            description = "See, edit, create and delete consoles: name, file formats and which app runs them.",
            id = "consoles",
            value = "${cat?.systems?.size ?: 0} consoles",
        )

        // ----------------------------------------------------------------- emulators
        library += SettingItem.Submenu(
            title = "Emulator per system",
            description = "Which one each console uses. Only emulators installed on this device are offered.",
            id = "emulators",
            value = "${vm.emulators?.defs?.size ?: 0} known",
        )

        library += SettingItem.Info(
            title = "Installed emulators",
            description = "The ones from the table that are present on this device.",
            lines = installedEmulators(ctx, vm).ifEmpty { listOf("none") },
        )

        // ------------------------------------------------------------------- catalog
        library += SettingItem.Info(
            title = "System catalog",
            description = "The systems the app can recognise. Extended by editing a file, with no rebuild.",
            lines = listOf(
                "Systems: ${cat?.systems?.size ?: 0}",
                "Spellings: ${cat?.byKey?.size ?: 0}",
                "Extensions: ${cat?.byExtension?.size ?: 0}",
                "",
                "User file:",
                CatalogLoader.userFile.path,
                if (CatalogLoader.userFile.isFile) "  (in use)" else "  (not present)",
            ),
        )

        library += SettingItem.Action(
            title = "Reload catalog",
            description = "Read systems.toml and the files in systems/ again. Useful after editing them from a PC.",
            run = onReload,
        )

        // ---------------------------------------------------------------------- data
        // Donde vive todo. Se elige al abrir el programa por primera vez; aqui solo se ensena,
        // para que quien busque un fichero sepa por donde empezar.
        data += SettingItem.Info(
            title = "Data folder",
            description = "Where themes, scraped art, settings and the Companion's logbook " +
                "are kept. It was chosen the first time the app opened.",
            lines = listOf(DataHome.dir.path),
        )

        // Link, si no esta: lo baja de su pagina en GitHub y abre el instalador de Android. La
        // bienvenida decia que se podia instalar desde aqui, y no habia fila (07-10-2026).
        if (!LinkSaveCheck.present.value) data += SettingItem.Action(
            title = "Install Ludolog Link",
            description = "The app that shares saves, Companion sessions and ROMs with your other " +
                "devices and your PC. Downloaded from its page on GitHub; Android asks before " +
                "installing it." + (LinkInstaller.lastError.value?.let { "\nLast try: $it" } ?: ""),
            value = LinkInstaller.status.value ?: "",
            enabled = LinkInstaller.status.value != LinkInstaller.DOWNLOADING,
            run = { LinkInstaller.getAndInstall(ctx) },
        )

        // Con una segunda A. Borra de todo —nombres, descripciones, emuladores elegidos, atajos,
        // carpetas y las claves de IGDB y ScreenScraper, que hay que volver a escribir a mano—, y
        // con una sola pulsacion bastaba un toque de mas al bajar por la pestana.
        data += SettingItem.Action(
            title = "Clear preferences",
            description = "Leaves the app as freshly installed: names, descriptions, chosen " +
                "emulators, shortcuts, folders and the IGDB and ScreenScraper keys. Touches no " +
                "ROMs, no box art and no logbook.",
            confirm = "press A again",
            run = { prefs.clearAll() },
        )

        // Preguntar a GitHub por versiones nuevas (ver Updates). Junto a About, que es donde se ven.
        data += SettingItem.Toggle(
            title = "Check for updates",
            description = "Once a day, ask GitHub whether there is a newer Ludolog. It only tells you, " +
                "below and in About: nothing is downloaded or installed.",
            on = prefs.updateCheck,
            onChange = { prefs.updateCheck = it },
        )

        // Lo ultimo de todo, al final de la ultima pestana (07-10-2026). Queda junto a «Clear
        // preferences», y no pasa nada: esa pide una segunda A, asi que un toque de mas al bajar
        // hasta aqui no borra nada.
        data += SettingItem.Submenu(
            title = "About",
            description = "The version, and who makes Ludolog.",
            id = "about",
            value = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
                .getOrNull().orEmpty(),
        )

        // El reparto final, por el titulo de cada fila (26-09-2026).
        //
        // La biblioteca tenia trece filas, con el catalogo de juegos mezclado entre las carpetas y
        // los emuladores: el catalogo va a su pestaña. El video de juego va con el tema, que es
        // donde esta lo que se ve. Y lo que ahorra bateria, lo primero de Interface. Lo que no se
        // nombra aqui se queda donde lo puso quien lo construyo: nada se pierde por el camino.
        fun take(list: MutableList<SettingItem>, vararg titles: String): List<SettingItem> =
            titles.mapNotNull { t -> list.firstOrNull { it.title == t }?.also { list.remove(it) } }
        val power = take(iface, "Battery saver", "Animation", "Second screen")
        val video = take(iface, "Gameplay video", "Video delay")
        val catalogRows = take(
            library, "Game catalog", "Update game catalog", "Catalog source", "Identified games",
            "Catalog files not read", "System catalog", "Reload catalog",
        )
        return listOf(
            SettingsTab("Interface", power + iface),
            SettingsTab("Theme", theme + video),
            SettingsTab("Sound", sound),
            SettingsTab("Library", library),
            SettingsTab("Catalog", catalogRows),
            SettingsTab("Artwork", artwork),
            SettingsTab("Data", data),
        ).filter { it.items.isNotEmpty() }
    }

    /** Duraciones que ofrece la fila de video, en segundos. Cero es el video entero. */
    private val CLIPS = listOf(10, 15, 20, 30, 0)

    /** Alturas que ofrece la fila de calidad, en pixeles. Cero deja la del origen. */
    private val HEIGHTS = listOf(240, 360, 0)

    /** Volumenes que ofrece la fila del panel, en tanto por ciento. */
    private val VOLUMES = listOf(10, 22, 35, 55, 100)

    /** Esperas que ofrece la fila de retardo, en segundos. Cero enciende al momento. */
    private val DELAYS = listOf(0, 1, 2, 3, 5)





    /**
     * Lo que la tarjeta de partida ensena.
     *
     * Las tres cifras son las que RetroCompanion ponia en su aviso, y las tres contestan a
     * algo que se pregunta justo al arrancar: si la carga llega hasta el final, cuantas veces
     * se ha vuelto a esto y cuanto se le lleva echado. Cada una por separado porque la
     * tarjeta esta encima de un juego: lo que no se mira, estorba.
     *
     * El interruptor de arriba apaga la tarjeta entera. Debajo de el las lineas salen
     * apagadas en gris: siguen ahi para que se vea que existen, pero no hacen nada mientras
     * no haya tarjeta donde ponerlas.
     */
    fun overlayItems(prefs: Prefs): List<SettingItem> {
        val on = prefs.overlay
        fun line(title: String, description: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            if (on) SettingItem.Toggle(title, description, get(), set)
            else SettingItem.Action(title, description, value = "—", enabled = false, run = {})

        return listOf(
            SettingItem.Toggle(
                title = "Show the card",
                description = "A card in the corner when a game starts, saying the session is " +
                    "being written down. Off records exactly the same, just without saying so.",
                on = on,
                onChange = { prefs.overlay = it },
            ),
            line(
                "Console",
                "The console's name next to the word recording.",
                { prefs.overlaySystem }, { prefs.overlaySystem = it },
            ),
            line(
                "Battery left",
                "How long the charge you have now would last IN THIS GAME, from its last ten " +
                    "sessions on this device. A game with nothing to go on says nothing.",
                { prefs.overlayBattery }, { prefs.overlayBattery = it },
            ),
            line(
                "Times played",
                "How many sessions this game already has. A game with none says first time.",
                { prefs.overlaySessions }, { prefs.overlaySessions = it },
            ),
            line(
                "Time played",
                "How long you have spent on this game in total.",
                { prefs.overlayPlayed }, { prefs.overlayPlayed = it },
            ),
        )
    }

    /**
     * La calibracion, al final de Sound: una fila por sonido del tema puesto. Izquierda y derecha
     * lo bajan o lo suben de decibelio en decibelio (de 0 hacia abajo, ver SoundGains) y A lo
     * hace sonar. Los cortos suenan tambien al moverlo, para comparar sin pulsar nada mas.
     *
     * Filas sueltas y no una secuencia que los toque todos: para comparar dos sonidos hay que
     * poder oir esos dos, las veces que haga falta.
     */
    fun levelItems(ctx: Context, vm: LibraryViewModel): List<SettingItem> {
        val sound = mutableListOf<SettingItem>()
        val prefs = vm.prefs
        val theme = prefs.themeId
        val labels = mapOf(
            "move" to "Move", "open" to "Open", "close" to "Close", "options" to "Menu",
            "companion_open" to "Companion in", "companion_close" to "Companion out",
            "levelup" to "Level up", "achievement" to "Achievement", "mission" to "Mission",
            "ambience" to "Ambience", "video" to "Video sound",
        )
        val cues = Sfx.Cue.entries.associateBy { it.file.substringBeforeLast('.') }
        val jingles = Jingle.Kind.entries.associateBy { it.file.substringBeforeLast('.') }
        val app = ctx.applicationContext
        fun play(name: String) {
            cues[name]?.let { Sfx.play(it); return }
            jingles[name]?.let { Jingle.play(it, theme, app); return }
            when (name) {
                // Ya suena debajo de esta ventana si esta puesto; si no, se pone.
                "ambience" -> Sfx.ambience(true)
                "video" -> previewVideoSound(vm)
            }
        }
        for (name in SoundGains.NAMES) {
            val db = prefs.soundGain(theme, name)
            sound += SettingItem.Level(
                title = "Level · ${labels[name] ?: name}",
                description = when (name) {
                    "ambience" -> "The loop under everything, as it plays now under this window. " +
                        "Left and right set its level for this look."
                    "video" -> "The sound of the gameplay videos. A plays a few seconds of one. " +
                        "Left and right set its level for this look, on top of Panel volume."
                    else -> "A plays it. Left and right set its level for this look, one decibel " +
                        "at a time, from 0 (as recorded) down to ${SoundGains.MIN_DB}."
                } + when (name) {
                    "ambience" -> " It can go up to +${SoundGains.maxDb(name)} dB."
                    else -> ""
                },
                db = db,
                onStep = { d ->
                    prefs.setSoundGain(theme, name, prefs.soundGain(theme, name) + d)
                    Sfx.refreshLevels(prefs.panelVolume / 100f)
                    // Los cortos, al momento; un jingle entero en cada paso se amontonaria.
                    if (name in cues) play(name)
                },
                onPlay = { play(name) },
            )
        }
        return sound
    }

    private val preview = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Unos segundos del sonido de un video de juego, el primero de la biblioteca que lo tenga ya
     * separado, por el mismo camino que en la lista (Sfx.clip).
     */
    private fun previewVideoSound(vm: LibraryViewModel) {
        val art = vm.art ?: return
        val games = vm.result?.games.orEmpty()
        // Buscarlo mira hasta tres ficheros por juego: fuera del hilo de la pantalla.
        Thread {
            val file = games.asSequence().mapNotNull { art.video(it) }
                .flatMap { v -> sequenceOf(null, SoundFx.TAPE, SoundFx.DIGITAL).mapNotNull { TapeQueue.audioFor(v, it) } }
                .firstOrNull() ?: return@Thread
            preview.post {
                preview.removeCallbacksAndMessages(null)
                Sfx.clip(file, vm.prefs.panelVolume / 100f)
                preview.postDelayed({ Sfx.clip(null, 0f) }, 6_000)
            }
        }.start()
    }

    /**
     * Lo que depende del tema: el video del panel, el sonido y el aspecto.
     *
     * Estuvo en un submenu propio, «Theme», porque en la raiz se mezclaba con cosas sin
     * relacion: el volumen del panel al lado de las carpetas de ROMs. Con pestanas cada fila va
     * a la suya —el video a la interfaz, el sonido al sonido, el aspecto al tema— y el submenu
     * sobraba: era una pestana escondida dentro de una lista.
     */
    private fun lookItems(
        ctx: Context,
        vm: LibraryViewModel,
        iface: MutableList<SettingItem>,
        sound: MutableList<SettingItem>,
        theme: MutableList<SettingItem>,
        /** Cambia la luz en vivo. Lo pasa la ventana, que es la que ve el tema puesto. */
        onBright: (Boolean) -> Unit,
    ) {
        val prefs = vm.prefs
        val look = themeById(prefs.themeId)

        // Todos los temas reproducen los gameplays, asi que las filas del video salen en todos.
        //
        // Hubo temas «quietos» que no los tenian, y en ellos estas cinco filas se escondian:
        // un interruptor que no hace nada es un interruptor que miente. Ahora el video es de
        // todos y lo que cambia es la decision, que es de cada tema: cuanto de esto quiere cada
        // uno se guarda en el suyo (ver Prefs.themed), y la descripcion lo dice.
        fun own(text: String) = "$text Each theme keeps its own."

        iface += SettingItem.Toggle(
            title = "Gameplay video",
            description = own(
                "When you stop on a game, the panel plays its recording. Off in every theme " +
                    "also stops the scraper downloading them.",
            ),
            on = prefs.playVideo,
            onChange = { prefs.playVideo = it },
        )

        // Encender el video en cada juego por el que se pasa es un parpadeo y un reproductor
        // abierto para nada. Con una espera, solo se enciende donde alguien se queda.
        iface += SettingItem.Action(
            title = "Video delay",
            description = own(
                "How long the panel waits before the video starts. It stops the flicker when " +
                    "you run down a long list.",
            ),
            value = if (prefs.videoDelay == 0) "none" else "${prefs.videoDelay}s",
            run = {
                val at = DELAYS.indexOf(prefs.videoDelay)
                prefs.videoDelay = DELAYS[(if (at < 0) 0 else at + 1) % DELAYS.size]
            },
        )


        // A cuantos fotogramas se mueve lo que se mueve solo: el latido del cursor, el cuarto del
        // Parlour, la nieve de la tele. Ver Motion. Moverse por las listas va aparte, a lo que de
        // la pantalla. Mas fotogramas se ven mas suaves y gastan mas bateria.
        // Todo quieto y sin video: lo que mas bateria ahorra en el menu, en todos los temas. Ver
        // Motion.saver. El sonido sigue: no se pidio callarlo.
        iface += SettingItem.Toggle(
            title = "Battery saver",
            // Sin own(): vale para todos los temas, no para el puesto.
            description = "Nothing moves on its own and no video plays, in every look: the cursor, " +
                "the room and the console spins stay still. Sounds stay on.",
            on = prefs.batterySaver,
            onChange = { prefs.batterySaver = it; Motion.saver.value = it },
        )

        // Solo en consolas de dos pantallas: en las demas no hay nada que elegir.
        if (DualScreen.display.value != null || !prefs.secondScreen) iface += SettingItem.Toggle(
            title = "Second screen",
            description = "On a console with two screens, the list goes on the bottom one and the " +
                "top one shows the room, the console and the game. Off keeps everything on the top screen.",
            on = prefs.secondScreen,
            onChange = { prefs.secondScreen = it; DualScreen.refresh(ctx) },
        )

        iface += SettingItem.Action(
            title = "Animation",
            description = "How smoothly the things that move on their own are drawn, in every look: " +
                "the cursor, the room, the TV static. 30 fps saves battery; 60 is smoother and " +
                "costs a little more. Lists always scroll smoothly, and after 15 seconds without " +
                "input everything rests.",
            value = "${prefs.animationFps} fps",
            run = {
                val at = Motion.CHOICES.indexOf(prefs.animationFps)
                prefs.animationFps = Motion.CHOICES[(if (at < 0) 0 else at + 1) % Motion.CHOICES.size]
                Motion.fps.intValue = prefs.animationFps
            },
        )

        // --------------------------------------------------------------- sonido
        sound += SettingItem.Toggle(
            title = "Interface sounds",
            description = own(
                "The click when the cursor moves, and the sounds for going into a console, " +
                    "coming back, and opening a menu.",
            ),
            on = prefs.uiSound,
            onChange = { prefs.uiSound = it; Sfx.uiOn = it },
        )

        // SIEMPRE, tenga sala el tema o no.
        //
        // Estaba detras de `look.room`, y eso escondia el interruptor justo donde mas falta
        // hacia. El Mainframe no tiene sala, asi que la fila desaparecia — pero el fondo
        // SI sonaba, porque Sfx lo toca segun la preferencia, no segun la sala. Un sonido sin
        // interruptor: para callarlo habia que irse al Parlour, apagarlo alli y volver.
        //
        // Un tema que no quiera fondo no se calla escondiendo la fila: pone su ambience.ogg en
        // silencio, que es lo que hace el Gallery. Esconder el mando no apaga el aparato.
        sound += SettingItem.Toggle(
            title = if (look.room) "Room ambience" else "Ambience",
            description = own(
                if (look.room) {
                    "A loop under everything else. It stops on its own while a game or " +
                        "another app is in front, and fades out after three minutes without input."
                } else {
                    "The loop this look plays under everything else. It stops on its own while " +
                        "a game or another app is in front, and fades out after three minutes " +
                        "without input."
                },
            ),
            on = prefs.ambienceSound,
            onChange = {
                prefs.ambienceSound = it
                Sfx.ambienceOn = it
                Sfx.ambience(it)
            },
        )

        // Y despues, lo que suena del video: lo general va antes, que es lo que se busca.
        //
        // Estuvo mudo desde el principio sin que nadie lo decidiera: era el valor por
        // defecto de la funcion que dibuja el tubo. Apagado tampoco se guarda el audio.
        sound += SettingItem.Toggle(
            title = "Video sound",
            description = own(
                "Whether the gameplay video plays its sound. Off in every theme also drops the " +
                    "audio track from what is downloaded, which makes the files smaller.",
            ),
            on = prefs.videoSound,
            onChange = { prefs.videoSound = it },
        )

        // La television de la escena esta al otro lado del cuarto. A volumen entero suena
        // como si la tuvieras en la cara, y con la reverberacion queda a barullo. Y en un
        // panel, bajo el menu, tampoco tiene que taparlo.
        sound += SettingItem.Action(
            title = "Panel volume",
            description = own(
                "How loud the gameplay video is. Low is the point: it plays under the menu, " +
                    "not over it.",
            ),
            value = "${prefs.panelVolume}%",
            run = {
                val at = VOLUMES.indexOf(prefs.panelVolume)
                prefs.panelVolume = VOLUMES[(if (at < 0) 0 else at + 1) % VOLUMES.size]
            },
        )

        // El mismo interruptor en todos los temas, con lo que haga en este: ver Theme.soundFx.
        val digital = look.soundFx == SoundFx.DIGITAL
        sound += SettingItem.Toggle(
            title = if (digital) "Digital filter" else "Tape filter",
            description = own(
                if (digital) {
                    "Plays the sound of each gameplay video with fewer samples and fewer bits, " +
                        "the way a machine would. Off, the video sounds as it came."
                } else {
                    "Narrows the sound of each gameplay video to roughly what a VHS linear " +
                        "track gave, with the echo of a room. Off, the video sounds as it came."
                },
            ),
            on = prefs.tapeSound,
            onChange = { prefs.tapeSound = it },
        )

        iface += SettingItem.Toggle(
            title = "Status bar",
            description = own(
                "The clock, battery, network and headphones across the top, and the rule under " +
                    "them. Off leaves only the gear.",
            ),
            on = prefs.statusBar,
            onChange = { prefs.statusBar = it },
        )
        theme += SettingItem.Submenu(
            title = "Appearance",
            description = "The look of the interface: colours, type and panel ornament.",
            id = "theme",
            value = themeById(prefs.themeId).name,
        )

        // Los temas que no van en el APK, para quien no los bajo en la bienvenida: es el sitio
        // al que ella remite. Se bajan del mismo lanzamiento que el catalogo (ver ThemeStore).
        val missingLooks = ThemeStore.OFFERED.filterNot { ThemeStore.installed(it) }
        theme += SettingItem.Action(
            title = "Download looks",
            description = "The Parlour and Mainframe looks, with their rooms, sounds and console " +
                "spins, from Ludolog's page on GitHub. Pick one in Appearance once it is here.",
            value = vm.looksNote ?: if (missingLooks.isEmpty()) "all here" else "${missingLooks.size} to get",
            enabled = vm.looksNote != "downloading" && missingLooks.isNotEmpty(),
            run = { vm.downloadLooks() },
        )

        // Las opciones del aspecto puesto, debajo de el. Hoy es una: la luz del Gallery.
        //
        // La luz no es otro aspecto —fue una entrada mas de la lista durante dos dias, y
        // mentia: quien elige un aspecto elige uno, no dos veces el mismo con el papel
        // cambiado—, es una propiedad del que este puesto. Estuvo al final de la lista de
        // aspectos, y alli dependia de la fila por la que pasara el cursor: bajando hacia ella
        // se pasaba por otro aspecto y la fila desaparecia, asi que con la cruceta no se
        // llegaba nunca. Aqui sale con el tema que la tiene y no esta en los que tienen una.
        //
        // Aplica al pulsar, sin reiniciar: las dos luces son el mismo tema y comparten
        // carpeta, asi que no hay nada instalado que pueda quedarse atras.
        if (look.bright() != null) theme += SettingItem.Action(
            title = "Light",
            description = own(
                "The same look on paper, for anyone who would rather read off a light screen. " +
                    "Same sounds, same backdrop, the other light.",
            ),
            value = if (prefs.brightMode) "Bright" else "Dark",
            run = { onBright(!prefs.brightMode) },
        )

        theme += SettingItem.Submenu(
            title = "Accent colour",
            description = own("The one colour of the interface: cursor, rules, titles and frames."),
            id = "accent",
            value = accentName(prefs.accent, prefs.themeId),
        )
    }

    private fun tvMediaSummary(files: List<java.io.File>): String {
        val bytes = files.sumOf { it.length() }
        val n = files.count { it.extension.lowercase() in VIDEO_EXTS }
        return when {
            files.isEmpty() -> "none"
            n == 0 -> "sound only · ${bytes / 1024 / 1024} MB"
            else -> "$n videos · ${bytes / 1024 / 1024} MB"
        }
    }

    private fun clearTvMedia() {
        tvMedia().forEach { it.delete() }
    }

    /**
     * Lo que borra «Clear TV media»: lo que hizo este programa, y nada mas.
     *
     * En su propia carpeta de medios, todo: los videos los bajo el y se vuelven a bajar. En las
     * de otros programas —ES-DE, Emulation— solo lo que les puso al lado al sintonizarlos: el
     * sonido separado y sus marcas (ver TapeQueue). Antes se borraban las carpetas `videos`
     * enteras de todas, y con ellas los videos que ES-DE habia sacado de ScreenScraper, que
     * desde aqui no se pueden volver a bajar: en una consola de pruebas eran sesenta y seis.
     */
    private fun tvMedia(): List<File> {
        val own = runCatching { DataHome.file("media").canonicalPath }.getOrNull()
        return videoDirs().flatMap { dir ->
            val mine = own != null &&
                runCatching { dir.canonicalPath }.getOrDefault(dir.path).startsWith("$own/")
            dir.listFiles().orEmpty().filter { f -> f.isFile && (mine || TAPE_MADE.containsMatchIn(f.name)) }
        }
    }

    /** Lo que TapeQueue deja al lado de un video: `x.mp4.m4a`, `x.mp4.split`, `x.mp4.digital.m4a`... */
    private val TAPE_MADE = Regex("""\.(mp4|webm|mkv)\.(m4a|split|tape|tap|clean|digital|clean\.m4a|digital\.m4a)$""", RegexOption.IGNORE_CASE)
    private val VIDEO_EXTS = setOf("mp4", "webm", "mkv")

    private fun videoDirs(): List<File> = ArtIndex.defaultRoots()
        .flatMap { root -> root.listFiles()?.toList().orEmpty() }
        .map { File(it, "videos") }
        .filter(File::isDirectory)

    /**
     * Lo que se sabe de la biblioteca por el catalogo: cuantos juegos tiene, cuantos con
     * seguridad, y de donde sale.
     */
    private fun catalogLines(vm: LibraryViewModel): List<String> {
        val out = mutableListOf<String>()
        vm.readReport?.let { r ->
            out += "${r.found} of ${r.total} games found in the catalog"
            out += "${r.exact} of them exactly"
            if (r.read > 0) out += "${r.read} read from their files this time"
        } ?: out.add(if (vm.prefs.catalogOn) "Not read yet" else "The catalog is off")
        out += ""
        val packs = GameDb.installed().count { it.kind == "games" }
        out += GameDb.version()?.let { "Catalog of ${it.substringBefore('.')}, $packs consoles" } ?: "No catalog downloaded"
        out += "From: " + (vm.prefs.catalogSource ?: GameDb.SOURCE.ifBlank { "not published yet" })
        vm.catalogError?.let { out += "Last try: $it" }
        return out
    }

    private fun cacheDir() = DataHome.file("cache")

    private fun cacheSummary(): String {
        val d = cacheDir()
        if (!d.isDirectory) return "empty"
        val files = d.listFiles().orEmpty()
        return "${files.size} files · ${files.sumOf { it.length() } / 1024} KB"
    }

    private fun installedEmulators(ctx: Context, vm: LibraryViewModel): List<String> {
        val emus = vm.emulators ?: return emptyList()
        val pm = ctx.packageManager
        return emus.defs.mapNotNull { def ->
            def.packages().firstOrNull { p ->
                runCatching { pm.getPackageInfo(p, 0) }.isSuccess
            }?.let { "${def.label}  ($it)" }
        }.sorted()
    }

    /** Systems that have at least one installed emulator, for the per-system submenu. */
    fun systemsWithChoices(
        ctx: Context,
        vm: LibraryViewModel,
    ): List<Pair<SystemDef, List<EmulatorDef>>> {
        val cat = vm.catalog ?: return emptyList()
        val emus = vm.emulators ?: return emptyList()
        val l = vm.launcher ?: return emptyList()
        return vm.result?.bySystem?.keys.orEmpty()
            .mapNotNull { id -> cat.byId[id] }
            .map { sys ->
                sys to emus.forSystem(sys.id)
                    .filter { l.installedPackage(ctx.packageManager, it) != null }
            }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first.name }
    }
}
