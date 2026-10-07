package com.felp.frontcomp

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Los gatillos leidos como ejes analogicos, para los mandos que solo los mandan asi.
 *
 * Medido en una consola de pruebas: el mando interno se declara como «Xbox Wireless Controller»
 * y entrega los gatillos POR PARTIDA DOBLE, como tecla (ButtonL2/ButtonR2) y como eje
 * (LTRIGGER/RTRIGGER, de cero a uno). Aqui manda la tecla, que es exacta y no tiene umbral;
 * esto es el respaldo para un mando que solo mande el eje, y entonces ningun onKeyEvent lo
 * veria. Los eventos de movimiento del joystick solo llegan a la actividad, no a Compose.
 *
 * El umbral es alto a proposito: un gatillo analogico rara vez descansa exactamente en cero,
 * y medio apretado no es apretado.
 */
internal object Triggers {

    private const val DOWN = 0.6f

    /** Verdadero mientras los dos gatillos estan apretados a la vez. */
    var chord by mutableStateOf(false)
        private set

    /** Se llama desde Activity.onGenericMotionEvent. */
    fun read(e: MotionEvent) {
        if (e.source and android.view.InputDevice.SOURCE_JOYSTICK == 0) return
        // LTRIGGER y BRAKE son el mismo gatillo contado dos veces, y lo mismo GAS y RTRIGGER:
        // se coge el mayor de cada pareja en vez de elegir uno y rezar.
        val l = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        chord = l >= DOWN && r >= DOWN
    }
}

/**
 * El color de una consola.
 *
 * Un color solo significa algo si significa lo mismo dos veces. Si el tono saliera del puesto
 * que ocupa una fila en la lista que se este dibujando, la SNES seria verde en una pantalla y
 * ambar en la siguiente, que es peor que no tener color: se aprende una regla y acto seguido
 * se desmiente. Asi que el indice es SIEMPRE el puesto de la consola en el registro entero,
 * no en lo que se ve.
 *
 * El tono sale de caminar la rueda desde el acento del tema en pasos del angulo aureo. Bajar
 * la opacidad escalon a escalon —que es lo primero que se intenta— se agota a los tres o
 * cuatro: de ahi en adelante todo es una version apagada del mismo color. El angulo aureo no
 * vuelve a pisar un vecindario, asi que no hay dos iguales por muchos que sean y los
 * consecutivos quedan lejos. El brillo alterna ademas, que los separa otra vez para quien no
 * distinga bien los tonos.
 *
 * Y es asi en TODOS los temas, tambien en el de fosforo. Alli todo lo demas sale de un solo
 * color, y estas barras salian igual: cinco escalones de brillo del mismo rojo, y la sexta
 * consola repetia el de la primera. Pero aqui el color no es decoracion sino DATO
 * —dice que consola es cada barra, y lo dice igual en todas las pantallas del cuaderno— y un
 * dato que no se puede leer no esta. Asi que el cuaderno es lo unico de ese tema con color.
 */
internal fun categorical(
    base: Color,
    index: Int,
    light: Boolean = false,
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    // Y el brillo se mide contra el FONDO, no en absoluto.
    //
    // Setenta de saturacion a pleno brillo es un color franco sobre negro y un pastel sobre
    // papel: medido en pantalla, el cian de la Switch y el oliva de Doom se leian mas flojos
    // que el texto apagado de al lado. Sobre claro el color tiene que BAJAR para separarse del
    // fondo, igual que sobre oscuro tiene que subir, asi que son dos escaleras y no una.
    val v = if (light) { if (index % 2 == 0) 0.62f else 0.48f }
            else { if (index % 2 == 0) 1f else 0.80f }
    return Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(
                (hsv[0] + index * 137.508f) % 360f,
                if (light) 0.85f else 0.70f,
                v,
            ),
        ),
    )
}


/**
 * Lo que ocupa el cuaderno en la tarjeta.
 *
 * Los tres ficheros y no solo el principal: SQLite escribe primero en el diario (-wal) y lo
 * vuelca al fichero de vez en cuando, asi que mirando solo el .db la cifra se queda corta
 * justo despues de jugar, que es cuando alguien mira esto.
 */
private fun bookBytes(): Long =
    listOf("", "-wal", "-shm").sumOf { File(Logbook.PATH + it).length() } +
        // Y los de las otras consolas, que se leen con el: las lineas que se cuentan al lado son
        // las de todos, y el tamaño era solo el de este.
        Logbooks.others().sumOf { it.length() }


/**
 * La ficha del cuaderno para su fila en la lista de consolas, con la forma de la de una
 * maquina: lo que ocupa, cuantas lineas lleva, desde cuando escribe y cuando escribio por
 * ultima vez.
 *
 * Las cuatro son del cuaderno como objeto y no de lo jugado: en esa fila habla el aparato que
 * lleva la cuenta, como en un registro. Lo jugado esta dentro, a un boton.
 *
 * Toca disco: fuera del hilo de la pantalla.
 */
internal fun companionFacts(ctx: android.content.Context): List<Pair<String, String>> =
    Logbook(ctx, withOthers = true).use { db ->
        val s = LogStats(db)
        val span = s.span()
        val now = System.currentTimeMillis()
        listOfNotNull(
            "MEMORY" to weight(bookBytes()),
            "LINES" to "%,d".format(s.lines()),
            span?.let { "UPTIME" to days((now - it.first) / 86_400_000L) },
            span?.let { "LAST ENTRY" to ago(now - it.second) },
        )
    }

private fun days(n: Long): String = if (n == 1L) "1 day" else "$n days"

/** Hace cuanto, en la unidad que se lee de un vistazo. */
private fun ago(ms: Long): String {
    val m = ms / 60_000L
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "yesterday"
        else -> "${m / (24 * 60)} days ago"
    }
}

/**
 * Las pestanas. La portada es el personaje (ver CharacterTab); lo que era la portada —el reparto
 * del tiempo— esta en STATISTICS. Las demas, las de RetroCompanion en su orden.
 */
private val DEFAULT_PAGES = listOf(
    "OVERVIEW", "ACHIEVEMENTS", "MISSIONS", "STATISTICS", "GAMES", "SESSIONS", "BATTERY", "THERMAL", "SPEED", "POWER",
)

/** Las pestanas en el orden que se les haya dado a mano (ver TabsWindow), con las nuevas al final. */
private var PAGES: List<String> = DEFAULT_PAGES

/** La pestana de las partidas, a la que lleva el enlace de la portada. */
private val SESSIONS_PAGE get() = PAGES.indexOf("SESSIONS")

/**
 * Lo que ofrece medir cada pestana de ranking.
 *
 * Repartido casi igual que en RetroCompanion. Bateria lleva dos reglas de la misma pregunta
 * —tanto por ciento la hora y miliamperios la hora— porque cual sirve depende de si se esta
 * comparando juegos o calculando cuanto queda de tarde. La tercera de alli, por minuto, es la
 * misma cifra dividida entre sesenta y no anadia una pregunta, solo una pestana mas que pasar.
 */
private val METRICS = mapOf(
    "BATTERY" to listOf(
        LogStats.Metric.BATTERY_PCT_H,
        LogStats.Metric.BATTERY_MAH_H,
    ),
    "THERMAL" to listOf(
        LogStats.Metric.TEMP_MEAN,
        LogStats.Metric.TEMP_MAX,
        LogStats.Metric.GPU_TEMP_MEAN,
        LogStats.Metric.GPU_TEMP_MAX,
    ),
    "SPEED" to listOf(
        LogStats.Metric.FPS_MEAN,
        LogStats.Metric.FPS_MIN,
        LogStats.Metric.CPU_MHZ,
        LogStats.Metric.GPU_MHZ,
        LogStats.Metric.CPU_MIN_MHZ,
    ),
    "POWER" to listOf(LogStats.Metric.POWER_MEAN, LogStats.Metric.POWER_MAX),
)

/** Lo que cada una deja fuera, dicho al final de la lista. */
private val NOTES = mapOf(
    "BATTERY" to "Sessions played on the charger are left out: there the counter climbs while you play.",
    "THERMAL" to "The chip has a sensor on each side and they disagree; both are here. Averages are " +
        "weighted by playtime, a peak is the highest single reading.",
    "SPEED" to "Frames are what reached the screen, counted by the display. Averages weighted by " +
        "playtime; a floor is the lowest seen, so the smallest number leads.",
    "POWER" to "What the game takes, from the battery or, on the charger, from what comes in less " +
        "what is stored.",
)

/**
 * Donde esta uno dentro del cuaderno, por encima de la pestana.
 *
 * Una pila y no tres variables sueltas. Con consola, juego y partida encadenados hay que
 * saber de donde se vino: a un juego se puede llegar desde GAMES o desde una consola, y B
 * tiene que devolver al sitio correcto.
 */
private sealed interface View {
    data class Console(val id: String) : View
    /** Un juego es su nombre y su consola: ver LogStats.GAME_KEY. */
    data class Game(val title: String, val system: String) : View
    data class Session(val entry: LogStats.Entry) : View
}

/**
 * El cuaderno, a pantalla completa.
 *
 * No es una ventana encima de la sala sino en lugar de ella: lo que se viene a mirar aqui no
 * se mira de pasada. En la esquina, el aparato con el que se jugo, girando, rendido con el
 * mismo tratamiento que las consolas del menu, y debajo las cifras que resumen el registro
 * entero. Esa columna no cambia al pasar de pestana ni al bajar de nivel: tenerla siempre
 * delante deja comparar lo que se mira ahora con el total sin ir y venir.
 */
@Composable
internal fun StatsWindow(vm: LibraryViewModel, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val t = LocalTheme.current
    // El orden de las pestanas, el que se les dio a mano (ver TabsWindow): las que falten, al
    // final, que es donde aparece una pestana nueva al actualizar.
    var order by remember {
        val saved = vm.prefs.companionTabs.filter { it in DEFAULT_PAGES }
        mutableStateOf(saved + DEFAULT_PAGES.filter { it !in saved })
    }
    PAGES = order
    var page by remember { mutableIntStateOf(0) }
    var stack by remember { mutableStateOf<List<View>>(emptyList()) }
    var grain by remember { mutableStateOf(LogStats.Grain.DAY) }
    // Que se mide y como se agrupa en las cuatro pestanas de ranking. La medida se guarda por
    // pestana —cada una ofrece unas distintas— y el agrupamiento es uno solo para las cuatro:
    // mirar por consola en calor y que velocidad siga por juego seria el tipo de sorpresa que
    // hace desconfiar de una pantalla.
    var metricAt by remember { mutableStateOf(mapOf<String, LogStats.Metric>()) }
    var groupBy by remember { mutableStateOf(LogStats.GroupBy.SYSTEM) }
    var options by remember { mutableStateOf(false) }
    // La lista de pestanas que despliega R2.
    var tabs by remember { mutableStateOf(false) }
    // Borrar es definitivo, asi que hace falta apretar dos veces. Esto guarda el numero de la
    // partida que ya recibio el primer aviso.
    var armed by remember { mutableStateOf<Long?>(null) }
    // Sube al borrar, y con eso se vuelve a leer todo: los totales cambian.
    var revision by remember { mutableIntStateOf(0) }
    val top = stack.lastOrNull()

    // B cierra las opciones, si no quita el ultimo nivel, y con todo vacio cierra.
    //
    // Con nombre y no como lambda suelta del padBack: lo mismo tiene que poder hacerlo el
    // rotulo de la cabecera cuando se toca, y dos copias de esta escalera acabarian diciendo
    // cosas distintas.
    fun back() {
        armed = null
        when {
            tabs -> tabs = false
            options -> options = false
            stack.isNotEmpty() -> stack = stack.dropLast(1)
            else -> onClose()
        }
    }

    // Leer el cuaderno es tocar disco: fuera del hilo de la interfaz, y todo de una pasada.
    // Se abre la base una vez y se cierra una vez, en lugar de una por consulta.
    val book by produceState<Book?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Logbook(ctx, withOthers = true).use { db ->
                    val s = LogStats(db)
                    val tel = Telemetry()
                    val full = tel.fullChargeMicroAh(ctx)
                    val now = tel.chargeMicroAh(ctx)
                    val devs = s.devices()
                    // Las misiones cumplidas se asientan antes que el personaje: su recompensa
                    // es parte de su experiencia.
                    val plays = s.plays()
                    val years = vm.catalog?.systems.orEmpty().associate { it.id to it.year }
                    val completions = s.completions(vm.prefs.completions)
                    Missions.settle(vm.prefs, db, plays, completions, years)
                    val missionsDone = s.missionsDone()
                    val character = Metagame.of(plays.map { it.session }, System.currentTimeMillis(), Missions.bonuses(missionsDone))
                    val ranking = s.ranking()
                    val recent = s.recent()
                    val feeds = (ranking.map { it.title to (it.system ?: "?") } + recent.map { it.title to (it.system ?: "?") })
                        .distinct().associateWith { (title, system) -> Metagame.split(s.genresOf(title, system)) }
                    Book(
                        overview = s.overview(full),
                        bySystem = s.bySystem(),
                        ranking = ranking,
                        recent = recent,
                        feeds = feeds,
                        devices = devs,
                        splits = s.bySystemPerDevice(devs),
                        rates = s.rates(now, android.os.Build.MODEL.orEmpty()),
                        // Lo que dice la bateria AHORA manda sobre lo apuntado: es lo mas nuevo y no
                        // depende de haber jugado desde que se pudo leer. Ver LogStats.health.
                        health = s.health().let { h ->
                            LogStats.Health(
                                fullUah = full ?: h.fullUah,
                                designUah = tel.designChargeMicroAh() ?: h.designUah,
                                cycles = tel.batteryCycles(ctx) ?: h.cycles,
                            )
                        },
                        character = character,
                        achievements = Achievements.Input(plays, character, completions, missionsDone.size, years).let { input ->
                            Achievements.dated(Achievements.of(input), input, missionsDone, Missions::bonuses)
                        },
                        plays = plays,
                        years = years,
                        completions = completions,
                        missionsDone = missionsDone,
                        lines = s.lines(),
                        bytes = bookBytes(),
                        chargeNowUah = now,
                    )
                }
            }.getOrNull()
        }
    }

    // El grafico de la portada se lee aparte del resto. Las pestanas de arriba se aplican al
    // moverse, asi que recorrer los cuatro gruesos con la cruceta pasa por los cuatro; con el
    // grueso dentro del Book, cada paso volvia a leer el cuaderno ENTERO para redibujar un
    // grafico. Aqui es una consulta y ya.
    val buckets by produceState<List<LogStats.Slice>>(emptyList(), revision, grain) {
        value = withContext(Dispatchers.IO) {
            runCatching { Logbook(ctx, withOthers = true).use { LogStats(it).buckets(grain) } }.getOrDefault(emptyList())
        }
    }

    // El cuaderno tiene su propio sonido de entrada y baja el ambiente a la mitad: no es una
    // ventana mas encima de la sala, es otra habitacion de la misma casa. Se ata al ciclo de
    // vida de la pantalla en vez de a los botones que la abren y la cierran porque las salidas
    // son varias —B, el gatillo otra vez, el sistema— y una sola de ellas olvidandose dejaria el
    // ambiente a la mitad para siempre.
    androidx.compose.runtime.DisposableEffect(Unit) {
        Sfx.play(Sfx.Cue.BOOK_OPEN)
        Sfx.book(true)
        onDispose {
            Sfx.book(false)
            Sfx.play(Sfx.Cue.BOOK_CLOSE)
        }
    }

    // Lo que ofrece cada pestana de ranking, y la nota que explica que deja fuera. Es la
    // misma reparticion que en RetroCompanion, para que las dos digan lo mismo.
    val tab = PAGES[page]
    val metrics = METRICS[tab].orEmpty()
    val metric = metricAt[tab] ?: metrics.firstOrNull()

    // El ranking se lee aparte del resto y no dentro del Book: cambia al tocar la medida o el
    // agrupamiento, y volver a leer el cuaderno entero por cambiar de unidad seria una consulta
    // de cada cosa para usar una.
    val ranked by produceState<List<LogStats.Row>?>(null, metric, groupBy, revision) {
        val m = metric
        value = if (m == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { Logbook(ctx, withOthers = true).use { LogStats(it).rank(m, groupBy) } }.getOrNull()
        }
    }

    // La caratula que el scraper ya bajo, buscada por el nombre con el que se apunto la
    // partida. Se arma una vez: un mapa por titulo, en vez de recorrer la biblioteca por fila.
    val covers = remember(vm.result, vm.art, vm.prefs.labelRevision) {
        vm.result?.games.orEmpty()
            .associateBy({ vm.bookTitle(it) }, { vm.art?.find(it) })
    }

    // Los hombros que se apretaron con el cuaderno ya abierto. La pestana cambia al soltar uno
    // de esos, como en el cajon de apps y en los ajustes, y el evento sigue subiendo tanto al
    // pulsar como al soltar: la raiz lleva la cuenta de los hombros apretados para su acorde.
    //
    // Aqui se consumia el soltar y no el pulsar, asi que para la raiz el hombro se quedaba
    // apretado para siempre. Al cerrar el cuaderno, el siguiente L1 suelto en la lista de
    // consolas contaba como L1+R1 y abria el cajon de apps, y dentro de una consola los hombros
    // dejaban de saltar a la siguiente. Con el cuaderno abierto la raiz no hace nada mas con
    // ellos, asi que dejarlos pasar no cuesta nada.
    val shoulders = remember { mutableSetOf<Key>() }

    Box(
        Modifier.fillMaxSize().background(MenuGround)
            .onKeyEvent { e ->
                // R2 solo despliega la lista de pestanas, y se atiende al PULSAR, no al
                // soltar. La ventana se abre con L2+R2, y el soltar de ese acorde llega aqui
                // dentro: atendiendolo al soltar, la lista se abriria sola nada mas entrar.
                if (e.type == KeyEventType.KeyDown && e.key == Key.ButtonR2) {
                    if (stack.isEmpty() && !options) tabs = true
                    return@onKeyEvent true
                }
                // Los hombros van antes del filtro de abajo porque tambien cuenta el pulsar, y
                // salen sin consumir en los dos casos (ver `shoulders`).
                if (e.key in SHOULDER_KEYS) {
                    if (e.type == KeyEventType.KeyDown) shoulders += e.key
                    else if (e.type == KeyEventType.KeyUp && shoulders.remove(e.key)) {
                        // Dentro de la pila los hombros no hacen nada: ahi no hay pestanas.
                        if (stack.isEmpty() && !options) {
                            page = (page + (if (e.key in LEFT_SHOULDER) -1 else 1) + PAGES.size) % PAGES.size
                        }
                    }
                    return@onKeyEvent false
                }
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                when {
                    // Select abre las opciones de ESTA pestana, que es donde uno las busca.
                    e.key in SETTINGS_KEYS -> {
                        options = !options && stack.isEmpty()
                        true
                    }
                    // Y olvida la partida abierta. Dos veces, porque no hay vuelta atras. Solo las
                    // de esta consola: las de otra estan en su cuaderno, que aqui no se escribe.
                    e.key == Key.ButtonY && top is View.Session && Logbook.isOwn(top.entry.id) -> {
                        val id = top.entry.id
                        if (armed == id) {
                            runCatching { Logbook(ctx).use { it.forget(id) } }
                            armed = null
                            stack = stack.dropLast(1)
                            revision++
                        } else {
                            armed = id
                        }
                        true
                    }
                    else -> false
                }
            }
            .padBack(::back),
    ) {
        // El fondo del cuaderno, si hay uno puesto en los medios.
        //
        // Esta pantalla sustituye a la sala entera, y sin nada detras el negro liso la dejaba
        // pareciendo una pantalla de ajustes.
        //
        // Va entero y no atenuado porque la imagen ya viene casi negra, con un grano muy fino
        // encima: lo que aporta es textura, no luz. Bajandola al treinta y cinco por ciento no
        // se distinguia del negro liso, que es tanto como no ponerla.
        // Y el fondo sale de DONDE SALGA EL DEL TEMA, no siempre de media/companion.
        //
        // media/companion es una carpeta compartida por todo el aparato, asi que con el Parlour
        // instalado cualquier tema se encontraba su foto de cuero y madera detras del cuaderno:
        // el tema cambiaba la letra y el color, y el escenario seguia siendo de otro. Un tema
        // sin sala usa aqui el mismo fondo plano que usa detras del menu —que si es suyo— y con
        // el mismo velo, asi que el cuaderno y la lista son el mismo sitio.
        if (t.room) {
            val backdrop = remember(t.id) { CompanionArt.background() }
            if (backdrop != null) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            val own = remember(t.id) { ThemeFiles.backdrop(ctx) }
            if (own != null) Backdrop(own)
        }

        // Las vistas sin lista no tienen nada que pueda tener el foco, y sin foco B se iria al
        // sistema y cerraria la aplicacion entera. El ancla no se ve y solo esta para eso; en
        // las que llevan lista la cede, que alli mandan las filas.
        val hasList = !options && top !is View.Session &&
            (top != null || tab in setOf("GAMES", "SESSIONS", "ACHIEVEMENTS", "MISSIONS"))
        val anchor = rememberFocusRequester()
        // Tambien al cerrarse las pestanas o las opciones: se llevan el foco, y en una pagina
        // sin filas nadie lo recogia. B, los hombros y Select dejaban de llegar a la ventana,
        // y el cuaderno ya no se cerraba.
        AutoFocus(anchor, enabled = !hasList && !tabs && !options)
        FocusAnchor(anchor)

        // Con una ventana delante, todo lo de detras sale de la navegacion. Sin esto las
        // direcciones seguian llegando a la lista del fondo: se movia sola mientras creias
        // estar navegando las opciones, y al cerrarlas aparecias en otra fila.
        androidx.compose.runtime.CompositionLocalProvider(
            // Y con lo que venga de fuera: si hay algo encima del cuaderno entero, tambien apagado.
            LocalPadEnabled provides (LocalPadEnabled.current && !(tabs || options)),
            // Y lo que hace B, para que el rotulo de la cabecera lo haga tambien al tocarlo.
            // Las ventanas de dentro —pestanas, opciones— traen el suyo y pisan a este.
            LocalDismiss provides ::back,
            // Aqui lo elegido no late: ver LocalCalmSelection.
            LocalCalmSelection provides true,
        ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 14.dp)) {
            Header(
                t, page, top, armed != null,
                onOptions = { options = stack.isEmpty() },
                onTabs = { if (stack.isEmpty()) tabs = true },
            )
            Spacer(Modifier.height(4.dp))
            PanelDivider()
            Spacer(Modifier.height(10.dp))

            val b = book
            if (b == null) {
                Text("reading…", color = MenuDim, fontSize = 12.sp, fontFamily = MenuBody)
                return@Column
            }

            // El puesto de cada consola en el registro ENTERO, que es lo que fija su color.
            val rank = remember(b) { b.bySystem.map { it.label } }
            fun name(id: String) = vm.displayName(vm.catalog?.byId?.get(id), id)
            // La de esta biblioteca; si no hay (un juego que solo se jugo en otra consola), la que trajo
            // Ludolog Link al compartir los cuadernos (ver CompanionCovers).
            fun cover(title: String) = covers[title] ?: CompanionCovers.find(title)

            Row(Modifier.fillMaxWidth().weight(1f)) {
                // Con desplazamiento, porque diez cifras no caben siempre.
                //
                // Era un Column fijo y en un tema de letra mas ancha —el Mainframe, con
                // su espaciado— las dos ultimas quedaban cortadas por abajo sin nada que dijera
                // que estaban ahi. Medido: en Parlour entran las diez, ahi entraban ocho y media.
                Column(
                    Modifier.width(206.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    Avatar(b.character)
                    Spacer(Modifier.height(8.dp))
                    Cards(b)
                }
                Spacer(Modifier.width(26.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    when (top) {
                        is View.Session ->
                            Detail(top.entry, rank, t.accent, ::name, armed == top.entry.id)
                        is View.Game -> GameView(top.title, top.system, rank, t.accent, ::cover, ::name) {
                            stack = stack + View.Session(it)
                        }
                        is View.Console -> ConsoleView(top.id, name(top.id), t.accent, ::cover) {
                            stack = stack + View.Game(it, top.id)
                        }
                        null -> when (tab) {
                            "OVERVIEW" -> CharacterTab(b, rank, t.accent, ::name, ::cover) {
                                stack = stack + View.Session(it)
                            }
                            "ACHIEVEMENTS" -> AchievementsTab(b, t.accent)
                            "MISSIONS" -> MissionsTab(b, vm.prefs, t.accent) { revision++ }
                            "STATISTICS" -> OverviewTab(
                                b, buckets, grain, rank, t.accent, ::name, ::cover,
                                onGrain = { grain = it },
                                // Una consola de la portada abre su pantalla, igual que en
                                // las pestanas de ranking: una fila que se puede elegir y no
                                // lleva a ningun sitio es un callejon.
                                onOpen = { stack = stack + View.Console(it) },
                                // Y las ultimas partidas: cada una a su ficha, y su rotulo a la
                                // pestana entera, como las pestanas del mando (L/R, R2).
                                onSession = { stack = stack + View.Session(it) },
                                onAllSessions = { page = SESSIONS_PAGE },
                            )
                            "GAMES" -> GamesTab(b, rank, t.accent, ::cover, ::name, b::feedsOf) {
                                stack = stack + View.Game(it.title, it.system ?: "?")
                            }
                            "SESSIONS" -> SessionsTab(b.recent, rank, t.accent, ::cover, ::name, b::feedsOf) {
                                stack = stack + View.Session(it)
                            }
                            else -> MetricTab(
                                rows = ranked,
                                metrics = metrics,
                                metric = metric ?: metrics.first(),
                                groupBy = groupBy,
                                rank = rank,
                                accent = t.accent,
                                name = ::name,
                                note = NOTES[tab].orEmpty(),
                                // Por juego o por consola, solo esta: ver LogStats.rank.
                                onlyHere = groupBy != LogStats.GroupBy.DEVICE && b.devices.size > 1,
                                onMetric = { metricAt = metricAt + (tab to metrics[it]) },
                                onGroupBy = { groupBy = it },
                                // Se entra en la fila cuando hay donde entrar: un juego y una
                                // consola tienen pantalla propia, un aparato no.
                                onOpen = { row ->
                                    when (groupBy) {
                                        LogStats.GroupBy.GAME ->
                                            stack = stack + View.Game(row.label, row.system ?: "?")
                                        LogStats.GroupBy.SYSTEM ->
                                            stack = stack + View.Console(row.label)
                                        LogStats.GroupBy.DEVICE -> Unit
                                    }
                                },
                                footer = if (tab == "BATTERY") ({ BatteryHealth(b) }) else null,
                            )
                        }
                    }
                }
            }
        }
        }

        // Tambien en sus ventanas, que son del cuaderno.
        androidx.compose.runtime.CompositionLocalProvider(LocalCalmSelection provides true) {
            if (tabs) {
                TabsWindow(
                    page, order,
                    onPick = { page = it; tabs = false },
                    // La que se estaba viendo sigue siendo la misma, este donde este ahora.
                    onReorder = { now ->
                        val showing = order[page]
                        order = now
                        PAGES = now
                        page = now.indexOf(showing).coerceAtLeast(0)
                        vm.prefs.companionTabs = now
                    },
                ) { tabs = false }
            }
            if (options) {
                OptionsWindow(vm.prefs) { options = false }
            }
        }
    }
}

/**
 * Los ajustes del cuaderno, que es para lo que queda Select aqui dentro.
 *
 * Lo que se mide y como se agrupa se decidia aqui y se mudo a las pestanas de la propia
 * pantalla, donde se ve lo que hacen. Select queda entonces libre para lo que no se puede
 * poner en una pestana: lo que vale para el cuaderno entero y no para la vista de hoy.
 *
 * Son los mismos valores que la pantalla de ajustes del front-end, no una copia. Quien esta
 * mirando el cuaderno y quiere callar la musica no deberia tener que salir, cruzar la sala y
 * entrar en Ajustes para encontrar la misma casilla.
 */
@Composable
private fun OptionsWindow(prefs: Prefs, onClose: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val items = remember(revision) { logbookOptions(prefs) }
    var selected by remember { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, items.lastIndex)

    // Mas ancha de lo que pide la lista: el titulo entero cabe en una linea, y partido en
    // dos —«THE» arriba y «RECKONING» debajo— la ventana empezaba con un tropiezo.
    ModalWindow(onDismiss = onClose, widthFraction = 0.60f, heightFraction = 0.78f) {
        WindowFrame(
            // El nombre sale de la constante y no escrito a mano: es lo que quedaba del nombre
            // viejo, y con dos sitios distintos el menu decia «Companion» y sus opciones «The
            // Reckoning», como si fueran dos herramientas.
            title = COMPANION_NAME.uppercase(),
            subtitle = "options",
            description = items.getOrNull(sel)?.description.orEmpty(),
            hint = "A  change      B  back",
        ) {
            ModalRows(
                count = items.size,
                selected = sel,
                onSelect = { selected = it },
                onActivate = {
                    when (val item = items.getOrNull(sel)) {
                        is SettingItem.Toggle -> { item.onChange(!item.on); revision++ }
                        is SettingItem.Action -> { item.run(); revision++ }
                        else -> Unit
                    }
                },
            ) { index ->
                ModalRow(label = items[index].title, value = items[index].value)
            }
        }
    }
}

/**
 * Lo que se puede decidir desde dentro del cuaderno.
 */
private fun logbookOptions(prefs: Prefs): List<SettingItem> = listOf(
    SettingItem.Toggle(
        title = "Session card",
        description = "The card that appears over the emulator when a game starts. Off records " +
            "exactly the same, just without saying so.",
        on = prefs.overlay,
        onChange = { prefs.overlay = it },
    ),
    SettingItem.Action(
        title = "Shortest session",
        description = "Games closed sooner than this are not written down. Opening one by " +
            "mistake is not playing, and loading is the hottest, priciest part of a game.",
        value = prefs.minSessionSeconds.let {
            when {
                it == 0 -> "keep all"
                it < 60 -> "${it}s"
                else -> "${it / 60} min"
            }
        },
        run = {
            val steps = listOf(60, 120, 300, 0, 30)
            val at = steps.indexOf(prefs.minSessionSeconds)
            prefs.minSessionSeconds = steps[(if (at < 0) 0 else at + 1) % steps.size]
        },
    ),
    // Con que pulso se mide y con que grueso se guarda. Las dos juntas y en este orden porque
    // son una sola decision con dos mitades: la primera fija lo fino que se ve y la segunda,
    // lo que ocupa. Sueltas no se entiende ninguna de las dos.
    //
    // Y aqui dentro y no en los ajustes del front-end porque es donde se ven sus consecuencias:
    // las lineas y el tamano estan en la portada, a un botón de distancia.
    SettingItem.Action(
        title = "Sampling interval",
        description = "How often the sensors are read while you play. Finer costs wakeups and " +
            "buys detail: a ten-second CPU reading has already flattened every spike inside it.",
        value = prefs.sampleMs.let { if (it < 1000) "%.1f s".format(it / 1000f) else "${it / 1000} s" },
        run = {
            val steps = listOf(1_000L, 2_000L, 5_000L, 500L)
            val at = steps.indexOf(prefs.sampleMs)
            prefs.sampleMs = steps[(if (at < 0) 0 else at + 1) % steps.size]
        },
    ),
    SettingItem.Action(
        title = "Stored every",
        description = "How much of that reading is collapsed into one saved row, as mean and " +
            "peak. It decides how fast the record grows: an hour of play is 360 rows at ten " +
            "seconds and 36 at one hundred.",
        value = prefs.bucketMs.let { if (it < 60_000) "${it / 1000} s" else "${it / 60_000} min" },
        run = {
            val steps = listOf(10_000L, 30_000L, 60_000L, 300_000L, 5_000L)
            val at = steps.indexOf(prefs.bucketMs)
            prefs.bucketMs = steps[(if (at < 0) 0 else at + 1) % steps.size]
        },
    ),
    SettingItem.Toggle(
        title = "Sound effects",
        description = "The clicks of moving through lists and opening things. Shared with the " +
            "rest of the front-end.",
        on = prefs.uiSound,
        onChange = { prefs.uiSound = it; Sfx.uiOn = it },
    ),
)


/**
 * Todas las pestanas de un vistazo, que es lo que R2 despliega.
 *
 * Con siete, pasar de POWER a OVERVIEW con los hombros son seis pulsaciones y hay que saberse
 * el orden. Aqui estan las siete a la vez y se salta a cualquiera, que es para lo que sirve un
 * menu rapido en un mando.
 */
@Composable
private fun TabsWindow(
    page: Int,
    order: List<String>,
    onPick: (Int) -> Unit,
    onReorder: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    // Alta de mas a proposito: caben enteras y no hay que bajar para ver la ultima. Un menu rapido
    // que obliga a desplazarse deja de ser rapido.
    ModalWindow(onDismiss = onClose, widthFraction = 0.40f, heightFraction = 0.92f) {
        WindowFrame(
            title = "TABS",
            subtitle = "${order.size} of them",
            description = "Drag ⇅ to put them in your order.",
            hint = "A  go      B  back",
        ) {
            var selected by remember { mutableIntStateOf(page) }
            val sel = selected.coerceIn(0, order.lastIndex)
            // Lo que se arrastra se sigue por su NOMBRE y no por su fila: al cambiar de sitio, la
            // fila que recibe el dedo pasa a enseñar otra pestaña, pero el gesto sigue siendo el
            // de la que se agarro.
            var dragging by remember { mutableStateOf<String?>(null) }
            var travel by remember { mutableStateOf(0f) }
            var rowHeight by remember { mutableIntStateOf(1) }
            val current by androidx.compose.runtime.rememberUpdatedState(order)
            ModalRows(
                count = order.size,
                selected = sel,
                onSelect = { selected = it },
                onActivate = { onPick(sel) },
            ) { index ->
                val here by androidx.compose.runtime.rememberUpdatedState(index)
                Row(
                    Modifier.onSizeChanged { rowHeight = it.height.coerceAtLeast(1) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        ModalRow(label = order[index], value = if (index == page) "showing" else "")
                    }
                    Text(
                        "⇅", color = if (dragging == order[index]) MenuInk else MenuFaint,
                        fontSize = 16.sp, fontFamily = MenuBody,
                        modifier = Modifier.padding(horizontal = 10.dp).pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = current[here]; travel = 0f },
                                onDragEnd = { dragging = null },
                                onDragCancel = { dragging = null },
                            ) { change, dy ->
                                change.consume()
                                val name = dragging ?: return@detectVerticalDragGestures
                                travel += dy
                                val list = current.toMutableList()
                                val at = list.indexOf(name)
                                val to = when {
                                    travel > rowHeight && at < list.lastIndex -> at + 1
                                    travel < -rowHeight && at > 0 -> at - 1
                                    else -> return@detectVerticalDragGestures
                                }
                                travel -= (to - at) * rowHeight
                                list.add(to, list.removeAt(at))
                                onReorder(list)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * La barra de arriba: de que sala es esto, donde estas y que hace cada boton.
 *
 * El nombre a la izquierda y la pestana a la derecha, en las dos esquinas. La pestana es lo
 * unico que cambia al moverse, asi que ponerla al final de la linea —y no pegada al nombre—
 * la convierte en la marca de pagina del cuaderno en vez de en un subtitulo.
 *
 * El engranaje delante del nombre es la version que se ve de lo que hace Select: sin el, las
 * opciones de cada pestana serian un atajo que hay que saberse.
 */
@Composable
private fun Header(
    t: Theme,
    page: Int,
    top: View?,
    armed: Boolean,
    onOptions: () -> Unit,
    onTabs: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        // Fila propia para el engranaje y el nombre: la de fuera alinea por abajo, que es lo
        // que quieren las pistas y la pestana, y ahi el engranaje caia a los pies de la
        // palabra. Dentro de esta se centra contra ella, que es donde tiene que estar.
        //
        // Y el bloque entero abre las opciones al tocarlo. El engranaje era solo el dibujo de
        // lo que hace Select, y lo primero que hace cualquiera con un engranaje en pantalla es
        // tocarlo: un dibujo de un boton que no es un boton se lee como una averia.
        Row(
            Modifier.pointerInput(Unit) { detectTapGestures { Sfx.play(Sfx.Cue.MENU); onOptions() } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelIcon(PixelIcons.gear, t.accent, scale = 2)
            Spacer(Modifier.width(8.dp))
            Text(
                COMPANION_LABEL,
                color = t.accent, fontSize = 13.sp, fontFamily = MenuDisplay,
                fontWeight = FontWeight.Bold, letterSpacing = t.titleTracking,
            )
        }
        Spacer(Modifier.weight(1f))
        Hint(
            when {
                // El aviso ocupa el sitio de la pista, que es donde se esta mirando.
                armed -> "Y again to forget it"
                top is View.Session && Logbook.isOwn(top.entry.id) -> "Y  forget      B  back"
                top is View.Session -> "B  back"
                top != null -> "A  open      B  back"
                else -> "L R  tab     R2  tabs     A  open     Select  options     B  close"
            },
            color = if (armed) t.accent else MenuFaint,
        )
        Spacer(Modifier.width(18.dp))
        // Y el nombre de la pestana la despliega al tocarlo, por lo mismo que el engranaje.
        //
        // Sin esto, con el dedo NO habia forma de cambiar de pestana: L, R y R2 son del mando
        // y no hay nada mas. Se toca lo que dice donde estas para que te diga donde mas puedes
        // estar, que es lo que hace cualquier pestana. Dentro de una consola o de una partida
        // no despliega nada: ahi el rotulo dice en que nivel estas, no que pestana miras.
        Text(
            when (top) {
                is View.Session -> "SESSION"
                is View.Game -> "GAME"
                is View.Console -> "CONSOLE"
                null -> PAGES[page]
            },
            color = t.accent, fontSize = 13.sp, fontFamily = MenuDisplay,
            fontWeight = FontWeight.Bold, letterSpacing = t.titleTracking,
            modifier = if (top != null) Modifier else Modifier.pointerInput(Unit) {
                detectTapGestures { Sfx.play(Sfx.Cue.MENU); onTabs() }
            },
        )
    }
}


/**
 * El aparato, girando. Su nombre va abajo, con las cifras: ver Cards.
 *
 * Sale del mismo molde que las consolas del menu —mismo render, mismo tamano de pixel— para
 * que sea evidente que es una pieza de la misma coleccion y no una foto pegada.
 *
 * Un tema puede poner aqui el avatar del propio cuaderno en vez del aparato: el de
 * `media/companion/avatar.mp4`, que en el Mainframe es un ojo en caracteres. Donde lo
 * hay, manda.
 */
@Composable
private fun Avatar(character: Metagame.Character) {
    val t = LocalTheme.current
    // Tocado con el dedo, gira sobre si mismo y por detras esta el personaje: el pentagono y el
    // nivel, para verlos desde cualquier pestaña. Solo al tacto: con el mando, la portada ya es
    // el personaje, y el avatar no tiene foco.
    var flipped by remember { mutableStateOf(false) }
    val angle by androidx.compose.animation.core.animateFloatAsState(
        if (flipped) 180f else 0f, androidx.compose.animation.core.tween(520), label = "avatar",
    )
    // El giro del aparato es MOVIMIENTO, y hay temas cuyo asunto es que no lo haya. Alli se
    // busca una imagen quieta con el mismo nombre, y si no la hay se deja el hueco: el sitio
    // reservado dice que ahi va algo mejor que lo que hay, que es la verdad.
    //
    // Con el id del tema entre las claves: el avatar del cuaderno es de un tema y no de otro.
    val spin = remember(t.id, t.spins) {
        if (t.spins) CompanionArt.avatar(MOTION) ?: deviceArt(MOTION) else null
    }
    val own = remember(t.id, t.spins) { if (t.spins) null else CompanionArt.avatar(QUIET) }
    // Sin imagen propia, el emblema del cuaderno en este tema —la gema con el ojo del
    // Gallery—, antes que la foto del aparato: el hueco es del cuaderno, y el emblema es su
    // cara. Es el mismo dibujo que la fila del Companion en la lista de consolas.
    val here = androidx.compose.ui.platform.LocalContext.current
    val emblem = remember(t.id, t.spins) { if (t.spins || own != null) null else ThemeFiles.companion(here) }
    val still = remember(t.id, t.spins) {
        if (t.spins || own != null || emblem != null) own else deviceArt(QUIET)
    }
    Column {
        // Sin el nombre del aparato encima: va con las cifras, como «Device» (ver Cards). Como
        // titulo se leia como el de la pantalla entera, y es un dato mas del registro.
        //
        // Alto fijo y no cuatro tercios: con la proporcion, el panel medía ciento cincuenta y
        // cuatro puntos y las dos ultimas cifras se salian por abajo. Lo que vaya dentro no se
        // estira —trae su propia proporcion y se centra—, asi que bajar el panel le deja
        // franjas a los lados y nada mas.
        Box(
            Modifier.fillMaxWidth().height(116.dp)
                .graphicsLayer { rotationY = angle; cameraDistance = 14f * density }
                .pointerInput(Unit) { detectTapGestures { flipped = !flipped } }
                .then(
                // El marco del tema: el panel labrado en el Parlour, una regla de un pelo en
                // los que no decoran. Es el mismo encuadre que ciñe las caratulas.
                if (t.ornament) Modifier.panelChrome(MenuGround)
                else Modifier.border(1.dp, MenuLine)
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (angle > 90f) {
                // La cara de atras, girada otra media vuelta para que no se lea al reves.
                Row(
                    Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pentagon(character, t.accent, Modifier.size(96.dp), labels = false)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("LV ${character.level}", color = MenuInk, fontSize = 20.sp, fontFamily = MenuDisplay, maxLines = 1)
                        Metagame.classOf(character)?.let {
                            Text(Metagame.className(it, t.id).uppercase(), color = t.accent, fontSize = 10.sp,
                                fontFamily = MenuBody, maxLines = 2)
                        }
                    }
                }
                return@Box
            }
            // Lo de dentro, con sitio a los lados para las barras de las unidades.
            Box(
                Modifier.fillMaxSize().padding(horizontal = GAUGE_ROOM),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    spin != null -> ConsoleTurntable(spin, Modifier.fillMaxSize().padding(5.dp))
                    still != null -> AsyncImage(
                        model = still,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = phosphorFilter(),
                        modifier = Modifier.fillMaxSize().padding(7.dp),
                    )
                    emblem != null -> {
                        val side = with(androidx.compose.ui.platform.LocalDensity.current) { 100.dp.roundToPx() }
                        val tint = t.accent.toArgb()
                        val bmp = remember(emblem, side, tint) {
                            SvgMark.tinted(emblem, side, tint)?.asImageBitmap()
                        }
                        if (bmp != null) androidx.compose.foundation.Image(bmp, contentDescription = null)
                    }
                    // Y si no hay nada, el hueco DICE QUE FICHERO QUIERE.
                    //
                    // «no avatar» informa de que falta algo y deja a quien lo lee buscando donde
                    // se pone; con el nombre del fichero delante, la respuesta esta en la propia
                    // pantalla y no en un README que hay que ir a abrir.
                    else -> Text(
                        "devices/${deviceSlug()}.png",
                        color = MenuFaint, fontSize = 9.sp, fontFamily = MenuBody,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StorageGauges(Modifier.matchParentSize())
        }
    }
}

/** Lo que se aparta a cada lado del avatar para que las barras no le pisen. */
private val GAUGE_ROOM = 24.dp

/** Una unidad de almacenamiento: su nombre corto, lo libre y el total, en bytes. */
private class Drive(val name: String, val free: Long, val total: Long)

/**
 * La memoria interna y la tarjeta, si la hay. Nada mas: son las dos donde vive lo que este
 * programa guarda, y un pendrive enchufado de paso no es del aparato.
 */
private fun drives(ctx: android.content.Context): List<Drive> {
    fun measure(name: String, dir: File?): Drive? = dir?.let {
        runCatching { android.os.StatFs(it.path) }.getOrNull()
            ?.takeIf { s -> s.totalBytes > 0L }
            ?.let { s -> Drive(name, s.availableBytes, s.totalBytes) }
    }
    val card = ctx.getSystemService(android.os.storage.StorageManager::class.java)
        ?.storageVolumes.orEmpty()
        .firstOrNull { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
        ?.directory
    return listOfNotNull(
        measure("INT", android.os.Environment.getDataDirectory()),
        measure("SD", card),
    )
}

/**
 * Lo que queda libre en cada unidad, en dos barras de pie a los lados del avatar: la memoria
 * interna a la izquierda y la tarjeta a la derecha.
 *
 * Llenas de lo LIBRE, como una bateria, y con el mismo numero encima: el cuaderno ya cuenta en
 * cargas y vatios, y aqui se lee igual —llena es que sobra, vacia es que falta—. Sin tarjeta
 * queda una sola barra y el otro lado vacio, que es lo que hay.
 *
 * Al abrir el cuaderno y no en vivo. Lo que ocupa una biblioteca no cambia mientras se mira, y
 * medir no cuesta nada, pero si hay que hacerlo fuera del hilo de la pantalla: una tarjeta
 * lenta puede tardar en contestar.
 */
@Composable
private fun StorageGauges(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val drives by produceState<List<Drive>>(emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching { drives(ctx) }.getOrDefault(emptyList()) }
    }
    Box(modifier) {
        drives.getOrNull(0)?.let { Gauge(it, Modifier.align(Alignment.CenterStart)) }
        drives.getOrNull(1)?.let { Gauge(it, Modifier.align(Alignment.CenterEnd)) }
    }
}

@Composable
private fun Gauge(drive: Drive, modifier: Modifier) {
    val accent = LocalTheme.current.accent
    // El canal, del mismo acento muy apagado y no de la regleta: en el de fosforo las reglas
    // son del mismo ambar que el acento, y la barra se leia llena entera.
    val track = accent.copy(alpha = 0.25f)
    val share = (drive.free.toFloat() / drive.total).coerceIn(0f, 1f)
    Column(
        modifier.fillMaxHeight().width(GAUGE_ROOM).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${Math.round(share * 100)}%", color = MenuDim, fontSize = 9.sp,
            fontFamily = MenuBody, maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        androidx.compose.foundation.Canvas(Modifier.width(4.dp).weight(1f)) {
            drawRect(track)
            val h = size.height * share
            drawRect(accent, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                size = androidx.compose.ui.geometry.Size(size.width, h))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            drive.name, color = MenuFaint, fontSize = 9.sp, fontFamily = MenuBody,
            maxLines = 1,
        )
    }
}

/** Lo que se acepta como avatar: un giro, o una imagen quieta. */
private val MOTION = listOf("mp4")
private val QUIET = listOf("png", "jpg", "jpeg", "webp")

/** El nombre del aparato tal y como se busca en el disco: minusculas y sin espacios. */
private fun deviceSlug(): String =
    android.os.Build.MODEL.orEmpty().lowercase().replace(" ", "")

/**
 * El avatar que le toca a ESTE aparato, entre las extensiones que se le pidan.
 *
 * Se busca por el nombre que el propio aparato da de si mismo contra los ficheros que haya en
 * <medios>/devices. Asi un aparato nuevo solo necesita que alguien deje el suyo ahi con un
 * nombre que se le parezca, sin tocar codigo.
 */
private fun deviceArt(kinds: List<String>): File? {
    val model = deviceSlug()
    val dirs = ArtIndex.defaultRoots().map { File(it, "devices") }.filter { it.isDirectory }
    val all = dirs.flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { it.extension.lowercase() in kinds }
    if (all.isEmpty()) return null
    return all.firstOrNull { model.contains(it.nameWithoutExtension.lowercase()) }
}
