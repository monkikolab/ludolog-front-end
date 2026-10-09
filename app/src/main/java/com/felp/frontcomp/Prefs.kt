package com.felp.frontcomp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Everything the app remembers between runs.
 *
 * Keys are namespaced by what they scope to, because almost every setting here exists at
 * more than one level: an emulator can be chosen for a whole system or for one game, and
 * the game-level choice has to win without erasing the system-level one.
 *
 * Todo vive en `config.xml`, dentro de la carpeta de datos (ver DataHome y ConfigFile), salvo
 * las credenciales de las fuentes de arte, que se quedan en las preferencias privadas: ver
 * `credential`.
 */
class Prefs(
    private val sp: SharedPreferences,
    private val secrets: SharedPreferences = sp,
) {

    constructor(ctx: Context) : this(
        ConfigFile.open(),
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE),
    )

    // Antes que ninguna propiedad lea su valor: la barra de estado, por ejemplo, lo lee al
    // construirse, y tiene que encontrar ya la clave de su tema.
    init {
        renameThemes()
        splitByTheme()
        sealCredentials()
    }

    // ---------------------------------------------------------------- por tema

    /**
     * La clave de un ajuste en el tema puesto: `look.video.parlour`, `look.accent.gallery`...
     *
     * Lo que decide como se ve y como suena el programa es de cada tema y no de todos: el
     * video en uno y no en otro, un acento para cada uno. Era uno para todos, y tocar el sonido
     * en un tema lo cambiaba tambien en los otros dos.
     *
     * Por el id del ASPECTO: las dos luces del Gallery son el mismo tema y comparten sus
     * ajustes, igual que comparten carpeta.
     */
    private fun themed(key: String) = "$key.${themeById(themeId).id}"

    /**
     * Los temas cambiaron de id (05-10-2026: gothic → parlour, retrofuture → mainframe,
     * minimal → gallery): lo guardado con el viejo pasa al nuevo, una vez.
     *
     * Sin esto, al actualizar se perdia todo lo de cada tema —el acento, el video, los niveles
     * de cada sonido (`snd.<tema>.<sonido>`), la luz clara— y el tema puesto volvia al de
     * serie. Se copia y no se mueve, como en splitByTheme: lo viejo se queda donde estaba, sin
     * que lo lea nadie, por si se vuelve a una version anterior. Y lo que ya tenga valor con el
     * nombre nuevo no se toca.
     */
    private fun renameThemes() {
        if (sp.getInt(RENAMED, 0) >= 1) return
        val all = sp.all
        val e = sp.edit()
        for ((key, v) in all) {
            val parts = key.split('.')
            if (parts.none { it in LEGACY_THEME_IDS }) continue
            val k = parts.joinToString(".") { LEGACY_THEME_IDS[it] ?: it }
            if (k in all) continue
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
            }
        }
        (all["look.theme"] as? String)?.let { old -> LEGACY_THEME_IDS[old]?.let { e.putString("look.theme", it) } }
        e.putInt(RENAMED, 1).apply()
    }

    /**
     * La primera vez, lo que era de todos pasa a cada tema con el mismo valor.
     *
     * Asi al actualizar no cambia nada: cada tema arranca con lo que habia, y desde ahi cada
     * uno guarda lo suyo. La clave comun se queda donde estaba, sin que la lea nadie mas, por
     * si se vuelve a una version anterior.
     */
    private fun splitByTheme() {
        if (sp.getInt(SPLIT, 0) >= 1) return
        val all = sp.all
        val e = sp.edit()
        for (id in AllThemes.map { it.id }) for (key in THEMED) {
            val v = all[key] ?: continue
            val k = "$key.$id"
            if (k in all) continue
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.putInt(SPLIT, 1).apply()
    }

    /**
     * Si ALGUN tema quiere video. Los ficheros son de todos: el scraper los baja mientras uno
     * los quiera, o al cambiar a ese tema faltarian los de todo lo escrapeado entre medias.
     */
    val anyThemePlaysVideo: Boolean
        get() = AllThemes.any { sp.getBoolean("look.video.${it.id}", true) }

    /** Si algun tema quiere oir los videos: mientras uno quiera, su pista de sonido se guarda. */
    val anyThemeHearsVideo: Boolean
        get() = AllThemes.any { sp.getBoolean("look.sound.${it.id}", true) }

    // ---------------------------------------------------------------- emuladores

    /** Emulator chosen for a whole system, or null to follow the table's own order. */
    fun emulatorForSystem(systemId: String): String? =
        sp.getString("emu.sys.$systemId", null)

    fun setEmulatorForSystem(systemId: String, emulatorId: String?) =
        put("emu.sys.$systemId", emulatorId)

    /**
     * Emulator chosen for one game.
     *
     * Keyed by path: two games can share a title across systems, and a rename should look
     * like a different entry rather than silently inherit someone else's setting.
     */
    fun emulatorForGame(game: Game): String? = sp.getString("emu.game.${game.path}", null)

    fun setEmulatorForGame(game: Game, emulatorId: String?) =
        put("emu.game.${game.path}", emulatorId)

    /**
     * The emulator to use, most specific setting first.
     *
     * Returns null when nothing was ever chosen, which means "use the first installed
     * candidate from the table" — the caller decides, because only it knows what is
     * installed.
     */
    /**
     * El nucleo de RetroArch elegido para una consola o para un juego, como se llama su fichero
     * («snes9x»), o nulo para el del catalogo. El del juego manda sobre el de la consola.
     */
    fun coreForSystem(systemId: String): String? = sp.getString("core.sys.$systemId", null)

    fun setCoreForSystem(systemId: String, core: String?) = put("core.sys.$systemId", core)

    fun coreForGame(path: String): String? = sp.getString("core.game.$path", null)

    fun setCoreForGame(path: String, core: String?) = put("core.game.$path", core)

    fun resolveEmulator(game: Game): String? =
        emulatorForGame(game) ?: emulatorForSystem(game.systemId)

    // ---------------------------------------------------------------- biblioteca

    var lastPlayedPath: String?
        get() = sp.getString("library.lastPlayed", null)
        set(v) = put("library.lastPlayed", v)

    /**
     * Fuerza a disco lo que este pendiente de escribir.
     *
     * Todo lo demas guarda con `apply`, que cambia la memoria al momento y escribe despues, en
     * otro hilo. Eso vale siempre menos en un caso: cuando lo siguiente que va a pasar es que el
     * proceso muera. Al reiniciar la aplicacion a proposito —la salvaguarda del cambio de tema—
     * matar el proceso justo despues de elegir se llevaria la eleccion por delante y volveria a
     * abrir con el tema anterior, que es peor que el fallo del que protege.
     *
     * Un `commit` vacio basta: escribe el estado entero que hay en memoria, incluido lo que
     * dejaran los `apply` anteriores, y no vuelve hasta que esta en disco.
     */
    fun flush() {
        sp.edit().commit()
        secrets.edit().commit()
    }

    fun recordPlayed(game: Game) {
        lastPlayedPath = game.path
        sp.edit()
            .putLong("play.at.${game.path}", System.currentTimeMillis())
            .putInt("play.count.${game.path}", playCount(game) + 1)
            .apply()
    }

    fun playCount(game: Game): Int = sp.getInt("play.count.${game.path}", 0)

    /**
     * Un ROM renombrado desde Ludolog Link: lo de ese juego pasa a la ruta nueva.
     *
     * Si se añade una clave que cuelgue de `game.path`, va en [GAME_KEYS] y en
     * docs/ludolog-link.md: si no, un renombrado desde el PC la deja huerfana. Ver LinkBridge.
     * Con commit y no apply: lo llama un receptor que puede quedarse sin proceso al acabar.
     */
    fun moveGame(from: String, to: String) {
        val all = sp.all
        val e = sp.edit()
        for (prefix in GAME_KEYS) {
            val v = all["$prefix$from"] ?: continue
            e.remove("$prefix$from")
            when (v) {
                is Boolean -> e.putBoolean("$prefix$to", v)
                is Int -> e.putInt("$prefix$to", v)
                is Long -> e.putLong("$prefix$to", v)
                is Float -> e.putFloat("$prefix$to", v)
                is String -> e.putString("$prefix$to", v)
            }
        }
        if (all["library.lastPlayed"] == from) e.putString("library.lastPlayed", to)
        e.commit()
    }

    // ---------------------------------------------------------------- renombrados

    /**
     * Custom display names.
     *
     * Kept apart from the catalog on purpose: the catalog is shared data that gets
     * replaced when it is extended, and a rename is personal. Overwriting one with the
     * other would lose whichever the user cared about more.
     */
    fun systemName(systemId: String): String? = sp.getString("name.sys.$systemId", null)

    fun setSystemName(systemId: String, name: String?) =
        put("name.sys.$systemId", name?.trim()?.takeIf(String::isNotEmpty))

    /** El render elegido para cada consola que no usa el suyo. Ver RenderChoices. */
    fun systemRenders(): Map<String, String> = sp.all.mapNotNull { (k, v) ->
        if (k.startsWith(RENDER) && v is String) k.removePrefix(RENDER) to v else null
    }.toMap()

    fun setSystemRender(systemId: String, render: String?) = put(RENDER + systemId, render)

    fun gameTitle(game: Game): String? = sp.getString("name.game.${game.path}", null)

    fun setGameTitle(game: Game, title: String?) = setGameTitleAt(game.path, title)

    /** Lo mismo por la ruta: lo que llega de otra consola por Ludolog Link (ver LinkEdits). */
    fun setGameTitleAt(path: String, title: String?) =
        put("name.game.$path", title?.trim()?.takeIf(String::isNotEmpty))

    // ---------------------------------------------------------------- ocultos

    val hiddenSystems: Set<String>
        get() = sp.getStringSet("library.hidden", emptySet()).orEmpty()

    fun setSystemHidden(systemId: String, hidden: Boolean) {
        val next = hiddenSystems.toMutableSet()
        if (hidden) next += systemId else next -= systemId
        sp.edit().putStringSet("library.hidden", next).apply()
    }

    fun isSystemHidden(systemId: String) = systemId in hiddenSystems

    // ---------------------------------------------------------------- discos

    /** El disco con que se abre un juego de varios (0 es el primero): el ultimo que se eligio. */
    fun discIndex(game: Game): Int = sp.getInt("disc.${game.path}", 0)

    fun setDiscIndex(game: Game, index: Int) {
        if (index <= 0) sp.edit().remove("disc.${game.path}").apply()
        else sp.edit().putInt("disc.${game.path}", index).apply()
    }

    // ---------------------------------------------------------------- favoritos

    fun isFavorite(game: Game): Boolean = sp.getBoolean("fav.${game.path}", false)

    fun setFavorite(game: Game, on: Boolean) {
        if (on) sp.edit().putBoolean("fav.${game.path}", true).apply()
        else sp.edit().remove("fav.${game.path}").apply()   // ausente ocupa menos que false
        // La lista de consolas (la entrada de favoritos) y las etiquetas (la estrella) se
        // recalculan con esto.
        favoriteRevision++
        labelRevision++
    }

    var favoriteRevision by mutableIntStateOf(0)
        private set

    // ---------------------------------------------------------------- metagame

    /**
     * Si el juego esta terminado. A mano: no hay forma de saberlo desde fuera del emulador.
     * Guarda cuando se marco, que es cuando cuenta para los logros.
     */
    fun completedAt(game: Game): Long? = sp.getLong("done.${game.path}", 0L).takeIf { it > 0 }

    fun setCompleted(game: Game, on: Boolean) {
        if (on) sp.edit().putLong("done.${game.path}", System.currentTimeMillis()).apply()
        else sp.edit().remove("done.${game.path}").apply()
        labelRevision++
    }

    /** Cuando se marco cada juego terminado, de toda la biblioteca. */
    val completions: List<Long>
        get() = sp.all.filterKeys { it.startsWith("done.") }.values.mapNotNull { (it as? Long)?.takeIf { v -> v > 0 } }

    /** Las misiones que se siguen, con cuando se empezaron: «id=hora,id=hora». Ver Missions. */
    var trackedMissions: Map<String, Long>
        get() = parseStamps(sp.getString("meta.missions", null))
        set(v) { sp.edit().putString("meta.missions", v.entries.joinToString(",") { "${it.key}=${it.value}" }).apply(); labelRevision++ }

    /** Las misiones cumplidas, con cuando: cada una puede repetirse, asi que es una lista. */
    var doneMissions: List<Pair<String, Long>>
        get() = sp.getString("meta.missions.done", null).orEmpty().split(',').mapNotNull { e ->
            val (id, at) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            at.toLongOrNull()?.let { id to it }
        }
        set(v) { sp.edit().putString("meta.missions.done", v.joinToString(",") { "${it.first}=${it.second}" }).apply() }

    private fun parseStamps(s: String?): Map<String, Long> = s.orEmpty().split(',').mapNotNull { e ->
        val (id, at) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        at.toLongOrNull()?.let { id to it }
    }.toMap()

    /**
     * Lo ultimo que se anuncio del personaje, para anunciar solo lo nuevo: el nivel, los logros
     * conseguidos y la ultima partida. De cada consola: cada una anuncia lo suyo una vez.
     */
    var seenLevel: Int?
        get() = sp.getInt("meta.seen.level", -1).takeIf { it >= 0 }
        set(v) = sp.edit().putInt("meta.seen.level", v ?: -1).apply()

    var seenAchievements: Set<String>
        get() = sp.getString("meta.seen.ach", null)?.split(',')?.filter(String::isNotEmpty)?.toSet().orEmpty()
        set(v) = sp.edit().putString("meta.seen.ach", v.sorted().joinToString(",")).apply()

    var seenSession: Long?
        get() = sp.getLong("meta.seen.session", -1L).takeIf { it >= 0 }
        set(v) = sp.edit().putLong("meta.seen.session", v ?: -1L).apply()

    /** El orden de las pestañas del Companion, si se cambio a mano. Ver StatsWindow. */
    var companionTabs: List<String>
        get() = sp.getString("log.tabs", null)?.split(',')?.filter(String::isNotEmpty).orEmpty()
        set(v) = sp.edit().putString("log.tabs", v.joinToString(",")).apply()

    // ---------------------------------------------------------------- scraper

    var scrapeOnScan: Boolean
        get() = sp.getBoolean("scrape.onScan", false)
        set(v) = sp.edit().putBoolean("scrape.onScan", v).apply()

    // ---------------------------------------------------------------- catalogo

    /**
     * Si se usa el catalogo propio de juegos (GameDb): se decide en la bienvenida y se cambia en
     * los ajustes. Encendido, cada juego se lee por dentro y se busca en el catalogo antes que
     * las caratulas; apagado, el scraper busca por el nombre del fichero, como siempre.
     */
    var catalogOn: Boolean
        get() = sp.getBoolean("catalog.on", true)
        set(v) = sp.edit().putBoolean("catalog.on", v).apply()

    /** Mirar una vez al dia si hay version nueva en GitHub (ver Updates). */
    var updateCheck: Boolean
        get() = sp.getBoolean("update.check", true)
        set(v) = sp.edit().putBoolean("update.check", v).apply()

    /** Cuando se pregunto a GitHub por ultima vez, haya contestado o no. */
    var updateCheckedAt: Long
        get() = sp.getLong("update.at", 0L)
        set(v) = sp.edit().putLong("update.at", v).apply()

    /** La ultima version publicada que se conoce. */
    var updateLatest: String?
        get() = sp.getString("update.latest", null)
        set(v) = sp.edit().putString("update.latest", v).apply()

    /** La version de la que ya se aviso abajo: se avisa una sola vez de cada una. */
    var updateNotified: String?
        get() = sp.getString("update.notified", null)
        set(v) = sp.edit().putString("update.notified", v).apply()

    /**
     * De donde se baja el catalogo, si no es el de siempre (GameDb.SOURCE): una direccion web o
     * una carpeta del aparato. Para probar uno propio antes de publicarlo.
     */
    var catalogSource: String?
        get() = sp.getString("catalog.source", null)?.takeIf(String::isNotBlank)
        set(v) = put("catalog.source", v?.trim()?.takeIf(String::isNotEmpty))

    /** Cuando se miro por ultima vez si habia catalogo nuevo, en milisegundos. */
    var catalogChecked: Long
        get() = sp.getLong("catalog.checked", 0L)
        set(v) = sp.edit().putLong("catalog.checked", v).apply()

    // ---------------------------------------------------------------- aspecto

    /** Si el panel se convierte en una TV al pararse sobre un juego. */
    var playVideo: Boolean
        get() = sp.getBoolean(themed("look.video"), true)
        set(v) = sp.edit().putBoolean(themed("look.video"), v).apply()

    /**
     * El video en todos los temas a la vez: lo que se contesta en la bienvenida. Despues cada
     * tema lo cambia en lo suyo, y apagado en todos el scraper tampoco los baja.
     */
    fun setVideoEverywhere(on: Boolean) {
        val e = sp.edit()
        AllThemes.forEach { e.putBoolean("look.video.${it.id}", on) }
        e.apply()
    }

    /**
     * El adorno del panel de un juego en el tema de borde, mientras se elige cual se queda.
     * Sin fila en los ajustes: se prueba escribiendo la clave en `config.xml` (marks, year o
     * sheet). Ver GameStage.
     */
    internal val stageDeco: StageDeco
        get() = when (sp.getString("dev.stage.deco", null)) {
            "year" -> StageDeco.YEAR
            "sheet" -> StageDeco.SHEET
            else -> StageDeco.MARKS
        }


    /**
     * Segundos que se guardan de cada video de partida. Cero es entero.
     *
     * Los recortes que hay por ahi duran unos treinta y seis segundos, y el panel solo
     * ensena el video mientras alguien esta parado sobre el juego. Guardar el resto es
     * llenar la tarjeta con algo que nadie llega a ver.
     */

    /**
     * Si el televisor del panel suena.
     *
     * Estaba mudo desde el principio y nadie lo habia decidido, era el valor por defecto de
     * la funcion que lo dibuja. Con esto en no, ademas, la pista de sonido ni se guarda: no
     * tiene sentido llenar la tarjeta con un audio que nunca va a salir por el altavoz.
     */

    /** Si suenan los clics y los cambios de pantalla. */
    var uiSound: Boolean
        get() = sp.getBoolean(themed("look.uisound"), true)
        set(v) = sp.edit().putBoolean(themed("look.uisound"), v).apply()

    /**
     * Si la barra de arriba ensena el estado del aparato, o solo el engranaje.
     *
     * En no se va tambien la linea que la separa de la lista. Es la unica franja de la
     * pantalla que no es de la sala, y quien quiera la habitacion entera y limpia la quita.
     *
     * Respaldada por estado de Compose y no solo por el fichero: la barra esta DEBAJO de la
     * ventana de ajustes, asi que el cambio se ve mientras se toca. Leyendola del fichero se
     * quedaba igual hasta cerrar la ventana, y parecia que el interruptor no hacia nada.
     */
    private var statusBarState by mutableStateOf(sp.getBoolean(themed("look.statusbar"), true))
    var statusBar: Boolean
        get() = statusBarState
        set(v) {
            statusBarState = v
            sp.edit().putBoolean(themed("look.statusbar"), v).apply()
        }


    /**
     * Si se lleva el cuaderno de partidas.
     *
     * En no, ni se apunta ni se puede abrir: sin esto, apagarlo dejaria una pantalla que se
     * abre con los gatillos y no ensena nada, que es peor que no tenerla. Lo ya apuntado se
     * queda donde esta —el fichero es de quien juega— y vuelve a aparecer al encenderlo.
     */
    var logbook: Boolean
        get() = sp.getBoolean("log.enabled", true)
        set(v) = sp.edit().putBoolean("log.enabled", v).apply()

    /**
     * La tarjeta que asoma encima del emulador al empezar a jugar, y lo que pone.
     *
     * Cada linea por separado y no una sola llave para todas: encima de un juego la tarjeta
     * roba sitio, y lo que a uno le interesa saber al arrancar —cuanta bateria le queda para
     * ESTE juego— al siguiente le sobra. Apagandolas todas queda el titulo, que es lo que
     * dice que se esta apuntando; apagando la tarjeta entera no sale nada y se apunta igual.
     */

    /**
     * Si suena la pieza del cuaderno mientras esta abierto.
     *
     * Aparte del ambiente de la sala y no colgando de el: son dos fondos distintos y querer
     * uno no dice nada de querer el otro. Alguien puede tener la sala en silencio y esto
     * encendido, o al reves, que es lo que pasa cuando se entra aqui a mirar una cifra
     * mientras suena otra cosa en el aparato.
     */
    /**
     * Si el tema puesto se ve en su luz clara.
     *
     * Guardado aparte del tema y no como otro tema: es la misma eleccion de aspecto vista con
     * otra luz, y quien la cambia no quiere cambiar de aspecto. Un tema que no tenga version
     * clara ignora esto.
     *
     * Arranca encendido para quien tuviera puesto el claro cuando era un tema con id propio:
     * si no, al actualizar se le apagaba la pantalla sin haber tocado nada.
     */
    var brightMode: Boolean
        get() = sp.getBoolean(themed("theme.bright"), sp.getString("look.theme", null) == "gallery-light")
        set(v) = sp.edit().putBoolean(themed("theme.bright"), v).apply()


    // ---------------------------------------------------------------- como se mide

    /**
     * Cada cuanto se leen los sensores, en milisegundos.
     *
     * Medir fino y guardar grueso cuesta mas despertares, no menos; lo que compra es
     * fidelidad. La carga del procesador es la diferencia entre dos lecturas de /proc/stat, y
     * una diferencia tomada cada diez segundos ya se ha comido todos los picos de dentro. A un
     * segundo el aparato esta despierto igual —pantalla encendida y el chip cargado por el
     * juego—, asi que ese coste es ruido dentro del ruido.
     */
    var sampleMs: Long
        get() = sp.getLong("log.sample.ms", 1_000L)
        set(v) = sp.edit().putLong("log.sample.ms", v).apply()

    /**
     * Cuanta de esa medida se resume en UNA fila guardada.
     *
     * Es lo que decide cuanto crece el cuaderno: una partida de una hora son trescientas
     * sesenta filas a diez segundos y treinta y seis a cien. Las cifras de la partida —media,
     * pico, minimo— no cambian con esto, porque salen de todas las lecturas; lo que cambia es
     * el detalle de la curva que se puede mirar despues.
     */
    /** El ajuste en dB de un sonido en un tema, de 0 hacia abajo. Ver SoundGains. */
    fun soundGain(theme: String, name: String): Int = sp.getInt("snd.$theme.$name", 0)

    fun setSoundGain(theme: String, name: String, db: Int) {
        val v = db.coerceIn(SoundGains.MIN_DB, SoundGains.maxDb(name))
        if (v == 0) sp.edit().remove("snd.$theme.$name").apply() else sp.edit().putInt("snd.$theme.$name", v).apply()
    }

    /** Ahorro de bateria: nada se mueve solo y no hay video en ningun tema. Ver Motion.saver. */
    var batterySaver: Boolean
        get() = sp.getBoolean("look.saver", false)
        set(v) = sp.edit().putBoolean("look.saver", v).apply()

    /** La lista en la segunda pantalla, si la consola tiene dos. Ver DualScreen. */
    var secondScreen: Boolean
        get() = sp.getBoolean("look.second", true)
        set(v) = sp.edit().putBoolean("look.second", v).apply()

    /** Con dos pantallas, la lista en esta y la escena en la otra. Ver DualScreen.swapped. */
    var swapScreens: Boolean
        get() = sp.getBoolean("look.second.swap", false)
        set(v) = sp.edit().putBoolean("look.second.swap", v).apply()

    /** A cuantos fotogramas por segundo se mueve lo que se mueve solo. Ver Motion. */
    var animationFps: Int
        get() = sp.getInt("look.fps", Motion.DEFAULT_FPS).takeIf { it in Motion.CHOICES } ?: Motion.DEFAULT_FPS
        set(v) = sp.edit().putInt("look.fps", v).apply()

    var bucketMs: Long
        get() = sp.getLong("log.bucket.ms", 10_000L)
        set(v) = sp.edit().putLong("log.bucket.ms", v).apply()

    // ---------------------------------------------------------------- atajos del mando

    /**
     * El boton atado a un atajo, por su nombre. Nulo es «el de fabrica».
     *
     * Se guarda el NOMBRE del boton y no su codigo. Un mando reporta Select como ButtonSelect
     * y otro como Menu, y guardando el codigo que llego se ataria el atajo a un mando
     * concreto: al cambiar de aparato dejaria de funcionar sin que nadie hubiera tocado nada.
     * Guardando «SELECT» se guarda la intencion, y los codigos se resuelven al leerla.
     */
    fun shortcut(key: String): String? = sp.getString(key, null)

    fun setShortcut(key: String, button: String?) = put(key, button)
    var overlay: Boolean
        get() = sp.getBoolean("log.card", true)
        set(v) = sp.edit().putBoolean("log.card", v).apply()

    /** La consola, al lado de "recording". */
    var overlaySystem: Boolean
        get() = sp.getBoolean("log.card.system", true)
        set(v) = sp.edit().putBoolean("log.card.system", v).apply()

    /** Lo que la carga de ahora daria de si EN ESTE JUEGO, en horas. */
    var overlayBattery: Boolean
        get() = sp.getBoolean("log.card.battery", true)
        set(v) = sp.edit().putBoolean("log.card.battery", v).apply()

    /** Cuantas veces se ha jugado a este juego. */
    var overlaySessions: Boolean
        get() = sp.getBoolean("log.card.sessions", true)
        set(v) = sp.edit().putBoolean("log.card.sessions", v).apply()

    /** Y cuanto tiempo en total. */
    var overlayPlayed: Boolean
        get() = sp.getBoolean("log.card.played", true)
        set(v) = sp.edit().putBoolean("log.card.played", v).apply()

    /**
     * Lo que tiene que durar una partida para que se apunte, en segundos. Cero = apuntarlo todo.
     *
     * Un juego que se abre por error, o que se cierra nada mas cargar, no es una partida, y un
     * punado de esas arrastra todas las medias hacia su propio arranque: cargar es la parte
     * mas caliente y mas cara de un juego, y la menos parecida a jugarlo. Medido aqui mismo,
     * dos partidas de prueba de menos de un minuto daban casi 500 mAh/h, que para un aparato
     * de mano es una barbaridad; era casi todo pantalla de carga.
     *
     * RetroCompanion usa dos minutos. Aqui se arranca en cinco (pedido del usuario, 07-10-2026;
     * era uno): lo que dura de verdad una partida, y se cambia en las opciones del Companion.
     */
    var minSessionSeconds: Int
        get() = sp.getInt("log.minsession", 300)
        set(v) = sp.edit().putInt("log.minsession", v).apply()

    /**
     * El ID de esta consola en los cuadernos del Companion: «c050» en «HandheldA-c050.db». Ver
     * Logbooks. En la carpeta de datos y no en las privadas, para que reinstalar no le cambie el
     * nombre al cuaderno. Con commit, porque el nombre del fichero depende de el.
     */
    var consoleId: String?
        get() = sp.getString("log.console.id", null)
        set(v) { sp.edit().putString("log.console.id", v).commit() }

    /** De que aparato es ese ID, en huella: ver Logbooks.owner. */
    var consoleOwner: String?
        get() = sp.getString("log.console.owner", null)
        set(v) { sp.edit().putString("log.console.owner", v).commit() }

    /** Si suena el ambiente de la sala, en bucle y por debajo de todo. */
    var ambienceSound: Boolean
        get() = sp.getBoolean(themed("look.ambience"), true)
        set(v) = sp.edit().putBoolean(themed("look.ambience"), v).apply()

    /**
     * Si el televisor del panel suena.
     *
     * Estaba mudo desde el principio y nadie lo habia decidido, era el valor por defecto de
     * la funcion que lo dibuja. Con esto en no, ademas, la pista de sonido ni se guarda: no
     * tiene sentido llenar la tarjeta con un audio que nunca va a salir por el altavoz.
     */
    var videoSound: Boolean
        get() = sp.getBoolean(themed("look.sound"), true)
        set(v) = sp.edit().putBoolean(themed("look.sound"), v).apply()

    /**
     * Volumen del panel, en tanto por ciento.
     *
     * Bajo a proposito: en la escena la television esta al otro lado de la habitacion, y a
     * volumen entero suena como si la tuvieras en la cara. Junto con la reverberacion es lo
     * que la coloca a distancia.
     *
     * Y seis decibelios por debajo de donde empezo, en dos tandas. Con el ambiente, los
     * clics y el panel
     * sonando a la vez, el aparato a tope empezaba a recortar; el margen tiene que salir de
     * alguna parte, y sale de lo que menos falta hace oir claro.
     */

    /**
     * Segundos que espera el panel antes de encender el video.
     *
     * Bajando por una lista se pasa por muchos juegos, y encender el tubo en cada uno es un
     * parpadeo constante y un reproductor que se abre y se cierra sin que nadie llegue a
     * ver nada. Con una espera, solo se enciende el canal en el que alguien se queda.
     */
    var videoDelay: Int
        get() = sp.getInt(themed("look.vdelay"), 1)
        set(v) = sp.edit().putInt(themed("look.vdelay"), v).apply()
    var panelVolume: Int
        get() = sp.getInt(themed("look.panelvol"), 22)
        set(v) = sp.edit().putInt(themed("look.panelvol"), v).apply()

    /**
     * Si el sonido del panel pasa por la cadena de cinta.
     *
     * Ecualizador, compresor y reverberacion de sala, los tres del sistema. No cuesta nada
     * medible: viven en el servidor de audio, no en este programa.
     */
    var tapeSound: Boolean
        get() = sp.getBoolean(themed("look.tape"), true)
        set(v) = sp.edit().putBoolean(themed("look.tape"), v).apply()

    /**
     * Altura maxima de un video guardado, en pixeles. Cero deja la del origen.
     *
     * Las colecciones vienen como las subio cada cual y casi la mitad estan a 640 por 480.
     * El panel es un cuadro pequeno dentro de una pantalla de mano, asi que esa altura son
     * bytes que nadie ve. Bajarla cuesta unos segundos de procesador por video.
     */
    var videoHeight: Int
        get() = sp.getInt("look.vheight", 240)
        set(v) = sp.edit().putInt("look.vheight", v).apply()
    var clipSeconds: Int
        get() = sp.getInt("look.clip", 15)
        set(v) = sp.edit().putInt("look.clip", v).apply()

    /** Que tema esta puesto. El id, no el objeto: los temas se compilan, la eleccion no. */
    var themeId: String
        get() = sp.getString("look.theme", GalleryTheme.id) ?: GalleryTheme.id
        set(v) = put("look.theme", v)

    // ---------------------------------------------------------------- apps

    /** Apps taken out of the drawer. Still installed, still pickable as launchers. */
    /**
     * Las carpetas de ROMs elegidas a mano, o nulo para buscarlas solas.
     *
     * Nulo es lo de siempre: ROMs, Roms, roms o Emulation/roms en cada unidad. Elegidas, se mira
     * SOLO en esas: quien tiene la biblioteca en una carpeta con otro nombre no tiene por que
     * renombrarla, y quien tiene dos copias en dos unidades se queda con la que quiera. Que la
     * clave este es lo que dice que se eligio, aunque sea ninguna.
     */
    var romDirs: Set<String>?
        get() = if (sp.contains(ROM_DIRS)) sp.getStringSet(ROM_DIRS, emptySet()).orEmpty() else null
        set(v) = sp.edit().apply { if (v == null) remove(ROM_DIRS) else putStringSet(ROM_DIRS, v) }.apply()

    val hiddenApps: Set<String>
        get() = sp.getStringSet("apps.hidden", emptySet()).orEmpty()

    fun setAppHidden(pkg: String, hidden: Boolean) {
        val next = hiddenApps.toMutableSet()
        if (hidden) next += pkg else next -= pkg
        sp.edit().putStringSet("apps.hidden", next).apply()
    }

    fun isAppHidden(pkg: String) = pkg in hiddenApps

    /**
     * Where the user filed an app: "app", "emu" or "game".
     *
     * Null means "whatever the emulator table says", which is right for almost all of
     * them; the override exists for the emulator the table does not know yet, for the
     * tool someone would rather keep next to the games, and for the Android game that
     * should show up in the library as if it were a ROM.
     */
    fun appSlot(pkg: String): String? = sp.getString("apps.slot.$pkg", null)
        // La clave vieja era un booleano "es emulador". Se sigue leyendo para que nadie
        // pierda lo que ya habia ordenado a mano.
        ?: if (sp.contains("apps.emu.$pkg")) {
            if (sp.getBoolean("apps.emu.$pkg", false)) "emu" else "app"
        } else null

    fun setAppSlot(pkg: String, slot: String?) {
        sp.edit().apply {
            remove("apps.emu.$pkg")
            if (slot == null) remove("apps.slot.$pkg") else putString("apps.slot.$pkg", slot)
        }.apply()
    }

    /** Lado minimo de una casilla del cajon, en puntos. Lo elige el menu del cajon. */
    var tileSize: Int
        get() = sp.getInt("apps.tile", 80)
        set(v) = sp.edit().putInt("apps.tile", v).apply()

    // ---------------------------------------------------------------- fuentes de arte

    /**
     * Las credenciales de cada fuente, que son del usuario y solo suyas.
     *
     * No se compila ninguna clave en la aplicación: una clave dentro de un APK no es una
     * clave, se lee con unzip y grep. Además sería un único cupo compartido por todas las
     * instalaciones, y revocable para todos a la vez el día que alguien abusara. La que
     * hace el usuario es suya: su cupo, la revoca él, y no sale de su aparato.
     *
     * Y en las preferencias PRIVADAS, no en `config.xml`: la carpeta de datos puede estar en la
     * tarjeta, y alli la leeria cualquier aplicacion con permiso de archivos. Son las mismas
     * preferencias donde vivia todo antes, asi que las credenciales ya guardadas siguen donde
     * estaban, sin mudanza.
     *
     * Y cifradas, con una clave del almacen de Android que no sale del aparato: ver Vault. Las
     * que una version anterior dejo en claro se cifran la primera vez que se leen.
     */
    fun credential(key: String): String = opened.getOrPut(key) { readCredential(key) }

    /**
     * Lo ya descifrado. El almacen de claves es un servicio aparte y cada consulta cuesta
     * milisegundos, y la ventana de fuentes pregunta por las credenciales al repintarse, que es
     * a cada movimiento del cursor. En claro solo en memoria, que es donde tiene que estar para
     * usarse.
     */
    private val opened = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Las credenciales que una version anterior dejo en claro, cifradas de una vez al arrancar.
     * Todas y no solo las que se lean: la de una fuente que ya no existe no se leeria nunca y se
     * quedaria en claro para siempre.
     */
    private fun sealCredentials() {
        val plain = secrets.all.filter { (k, v) -> k.startsWith(SECRET) && v is String && !Vault.isSealed(v) }
        if (plain.isEmpty()) return
        runCatching {
            val e = secrets.edit()
            for ((k, v) in plain) e.putString(k, Vault.seal(v as String))
            e.apply()
        }.onFailure { android.util.Log.w("Ludolog", "credentials not sealed: ${it.javaClass.simpleName}") }
    }

    private fun readCredential(key: String): String {
        val stored = secrets.getString(SECRET + key, null) ?: return ""
        if (Vault.isSealed(stored)) return Vault.open(stored).orEmpty()
        runCatching { secrets.edit().putString(SECRET + key, Vault.seal(stored)).apply() }
            .onFailure { android.util.Log.w("Ludolog", "credential not sealed: ${it.javaClass.simpleName}") }
        return stored
    }

    fun setCredential(key: String, value: String) {
        val v = value.trim()
        secrets.edit().apply {
            // Sin cifrar no se guarda: una credencial en claro es justo lo que esto evita, y
            // volver a escribirla es menos malo que dejarla asi.
            if (v.isEmpty()) remove(SECRET + key)
            else runCatching { Vault.seal(v) }.getOrNull()?.let { putString(SECRET + key, it) }
        }.apply()
        opened.remove(key)
        labelRevision++
    }

    /** Si una fuente está lista para usarse: todas sus casillas rellenas. */
    fun sourceReady(vararg keys: String): Boolean = keys.all { credential(it).isNotEmpty() }

    // ---------------------------------------------------------------- descripciones

    /**
     * The user's own description of a thing, over whatever the catalog or the package
     * says. `kind` is sys, game or app; `key` the id, the path or the package.
     */
    fun description(kind: String, key: String): String? = sp.getString("desc.$kind.$key", null)

    fun setDescription(kind: String, key: String, text: String?) =
        put("desc.$kind.$key", text?.trim()?.takeIf(String::isNotEmpty))

    // ---------------------------------------------------------------- acento

    /** The accent colour chosen over the theme's own, as 0xAARRGGBB text, or null. */
    var accent: String?
        get() = sp.getString(themed("look.accent"), null)
        set(v) = put(themed("look.accent"), v)

    // ---------------------------------------------------------------- diagnóstico

    /** Everything stored, for the settings screen and for support. */
    fun dump(): Map<String, Any?> = sp.all.toSortedMap()

    fun clearAll() {
        sp.edit().clear().apply()
        secrets.edit().clear().apply()
        // Y las ya descifradas en memoria: sin esto las claves seguian usandose, y saliendo
        // como puestas, hasta que se cerrara el proceso.
        opened.clear()
        labelRevision++
    }

    /**
     * Cuenta de cambios en los rotulos, para que la interfaz pueda guardar lo que dibuja.
     *
     * Las preferencias no avisan a Compose cuando cambian. Las listas de consolas y de
     * juegos se rehacian enteras en CADA recomposicion -una consulta aqui y una cadena
     * nueva por fila, en cada movimiento del cursor- y el renombrado se veia de rebote,
     * porque no habia nada guardado que pudiera quedarse viejo. Guardandolo hace falta
     * avisar a proposito, y se avisa desde put(), que es por donde pasan los nombres y las
     * descripciones sin excepcion.
     */
    var labelRevision by mutableIntStateOf(0)
        private set

    /** Los nombres o descripciones cambiaron por fuera (otra instancia, ver LinkEdits): releerlos. */
    fun touchLabels() { labelRevision++ }

    private fun put(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        labelRevision++
    }

    companion object {
        /**
         * Las preferencias privadas: hoy solo las credenciales, antes todo.
         *
         * Con el nombre de antes a proposito: es el del fichero, y con otro se empezaria por uno
         * vacio y habria que volver a escribir todas las claves.
         */
        const val NAME = "frontcomp"

        /** El prefijo de las credenciales. Lo que empieza asi no sale nunca a `config.xml`. */
        const val SECRET = "art."
        private const val RENDER = "render.sys."

        /** Los ajustes que son de cada tema. Lo demas —biblioteca, arte, cuaderno— es de todos. */
        private val THEMED = listOf(
            "look.video", "look.vdelay", "look.sound", "look.panelvol", "look.tape",
            "look.uisound", "look.ambience", "look.statusbar", "look.accent", "theme.bright",
        )

        /** Hecha la mudanza de lo comun a cada tema. Un numero, por si hay otra algun dia. */
        private const val SPLIT = "look.split"
        private const val RENAMED = "look.renamed"
        private const val ROM_DIRS = "library.romdirs"

        /**
         * Las claves que cuelgan de la ruta de un juego, sin la ruta. Las mueve [moveGame] cuando
         * Ludolog Link renombra un ROM; una clave por juego nueva va aqui y en docs/ludolog-link.md.
         */
        val GAME_KEYS = listOf(
            "play.at.", "play.count.", "fav.", "done.", "disc.", "emu.game.", "core.game.", "name.game.", "desc.game.",
        )
    }
}
