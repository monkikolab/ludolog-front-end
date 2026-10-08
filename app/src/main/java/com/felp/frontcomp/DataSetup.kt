package com.felp.frontcomp

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.composed
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * La bienvenida: lo primero que se ve, y solo la primera vez.
 *
 * Sale EN LUGAR de todo lo demas y no encima: sin carpeta no hay tema, ni biblioteca, ni
 * cuaderno que ensenar, y abrir la pantalla de siempre vacia seria peor que preguntar. Tres
 * pasos, en el orden en que hacen falta:
 *
 *  1. DATA, donde guarda Ludolog lo suyo: temas, arte, ajustes y el cuaderno.
 *  2. ROMS, donde estan los juegos: la carpeta que ya haya, o una nueva con una subcarpeta por
 *     consola y los nombres de ES-DE (ver RomFolders).
 *  3. PERMISSIONS, lo que se concede fuera, en los ajustes de Android: cada fila abre su pagina,
 *     y al volver se mira otra vez (ver [Grants]).
 *
 * Delante de los tres, el permiso de todos los ficheros, que no es opcional: sin el no se ven ni
 * las tarjetas ni lo que hay en ellas. En la lista del final sale ya concedido.
 *
 * Y con una carpeta elegida que no esta —la tarjeta fuera— no es la bienvenida sino avisar de
 * cual falta, y dejar reintentar o elegir otra, en vez de empezar una vacia sin avisar.
 */
@Composable
internal fun DataSetup() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Grants.refresh(ctx) }
    // DataHome no es estado de Compose: este es el que cambia al acabar cada paso.
    var step by remember { mutableStateOf(DataHome.setupStep) }
    // Lo que se hizo con las ROMs, para decirlo en el paso siguiente.
    var romNote by remember { mutableStateOf<String?>(null) }
    val files = Grants.files
    // Con permiso, carpeta y nada pendiente —se devolvio el permiso, o volvio la tarjeta— no
    // queda nada que preguntar.
    LaunchedEffect(files, step) {
        if (files && DataHome.ready() && step == null) restartApp(ctx)
    }
    BackHandler(enabled = step == DataHome.STEP_PERMISSIONS && files && DataHome.ready()) {
        DataHome.setupStep = DataHome.STEP_ROMS
        step = DataHome.STEP_ROMS
    }
    // Y en los tres ultimos, B vuelve al de antes. Sin esto, B llegaba a Android, que cerraba la
    // actividad a mitad de la bienvenida.
    val previous = when (step) {
        DataHome.STEP_EXTRAS -> DataHome.STEP_PERMISSIONS
        DataHome.STEP_VIDEO -> DataHome.STEP_EXTRAS
        DataHome.STEP_COMPANION -> DataHome.STEP_VIDEO
        DataHome.STEP_LINK -> DataHome.STEP_COMPANION
        else -> null
    }
    BackHandler(enabled = previous != null && files && DataHome.ready()) {
        DataHome.setupStep = previous
        step = previous
    }
    Box(Modifier.fillMaxSize().background(MenuGround)) {
        when {
            !files -> Welcome(0) { FilesGate() }
            !DataHome.ready() -> Welcome(1) {
                FolderChoice {
                    // Primera vez: siguen las ROMs. Si era la carpeta de antes que no estaba, ya
                    // esta todo: a arrancar.
                    val next = DataHome.setupStep
                    step = next
                    if (next == null) restartApp(ctx)
                }
            }
            step == DataHome.STEP_ROMS -> Welcome(2) {
                RomsStep { note ->
                    romNote = note
                    DataHome.setupStep = DataHome.STEP_PERMISSIONS
                    step = DataHome.STEP_PERMISSIONS
                }
            }
            step == DataHome.STEP_PERMISSIONS -> Welcome(3) {
                PermissionsStep(romNote) {
                    DataHome.setupStep = DataHome.STEP_EXTRAS
                    step = DataHome.STEP_EXTRAS
                }
            }
            step == DataHome.STEP_EXTRAS -> Welcome(4) {
                ExtrasStep {
                    DataHome.setupStep = DataHome.STEP_VIDEO
                    step = DataHome.STEP_VIDEO
                }
            }
            step == DataHome.STEP_VIDEO -> Welcome(5) {
                VideoStep {
                    DataHome.setupStep = DataHome.STEP_COMPANION
                    step = DataHome.STEP_COMPANION
                }
            }
            step == DataHome.STEP_COMPANION -> Welcome(6) {
                CompanionStep {
                    DataHome.setupStep = DataHome.STEP_LINK
                    step = DataHome.STEP_LINK
                }
            }
            step == DataHome.STEP_LINK -> Welcome(7) {
                LinkStep {
                    // El primer arranque de verdad: repasa la biblioteca y sigue solo con el
                    // catalogo y el scraper, en segundo plano (ver LibraryViewModel.afterScan).
                    DataHome.firstRun = true
                    DataHome.setupStep = null
                    restartApp(ctx)
                }
            }
        }
    }
}

/**
 * Lo que se puede bajar: los dos temas que no van en el APK y el catalogo de juegos, cada uno
 * con lo que ocupa. El del catalogo es el de las consolas que hay en la biblioteca, que se
 * cuentan aqui mismo con un repaso rapido de las carpetas de ROMs.
 *
 * Cada fila BAJA al pulsarla, y queda marcada como obtenida. Antes cada fila alternaba entre GET
 * y SKIP y lo que bajaba era un boton de abajo: quien la estreno pulso los GET pensando que eso
 * los bajaba, los paso a SKIP sin saberlo, y la instalacion siguio sin temas ni catalogo. Abajo
 * queda un solo boton: SKIP si no se bajo nada, CONTINUE en cuanto hay algo.
 *
 * Nada es obligatorio. Sin red, o sin nada publicado todavia, cada fila lo dice y se sigue: los
 * temas se pueden bajar despues, y el catalogo se baja solo en cuanto se pueda.
 */
@Composable
private fun ExtrasStep(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }

    var look by remember { mutableStateOf<Look?>(null) }
    // Como esta cada cosa: «get», «working», «done», «have» (ya estaba) o el error.
    val state = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        look = withContext(Dispatchers.IO) {
            // Que consolas hay: un repaso de las carpetas elegidas, sin guardarlo.
            val systems = runCatching {
                val cat = CatalogLoader.load { n -> ctx.assets.open(n).bufferedReader().use { it.readText() } }
                Scanner(Detector(cat), cat).scan(Scanner.roots(prefs)).games.map { it.systemId }.toSet()
            }.getOrDefault(emptySet())
            val themes = ThemeStore.remoteIndex(ThemeStore.SOURCE)
            val catalog = GameDb.remoteIndex(prefs.catalogSource ?: GameDb.SOURCE)
            Look(
                themes.getOrNull()?.filter { it.id in OFFERED }, themes.exceptionOrNull()?.let(::why),
                catalog.getOrNull(), catalog.exceptionOrNull()?.let(::why), systems,
            )
        }
        for (id in OFFERED) state[id] = if (ThemeStore.installed(id)) HAVE else GET
        // El catalogo ya bajado para estas consolas cuenta como obtenido: pasa al repetir la
        // bienvenida desde los ajustes.
        val l = look
        state[CATALOG] = if (l != null && GameDb.available() && !GameDb.missingFor(l.systems)) HAVE else GET
    }

    Ask(
        "DOWNLOADS",
        "Two more looks for Ludolog and its game catalog, from Ludolog's page on GitHub. " +
            "The catalog knows the exact name, genre, year and story of each game without " +
            "searching the internet game by game. Press one to download it.",
    )
    val l = look
    if (l == null) {
        Status("Looking at what there is…", null)
        return
    }

    // Lo pulsado mientras bajaba otra cosa: baja despues, en el orden en que se pulso. Antes se
    // ignoraba sin decir nada, y quien pulso los tres GET seguidos se quedo con uno solo.
    val queue = remember { ArrayDeque<Pair<String, suspend () -> Result<*>>>() }

    /** Baja una cosa; si ya se esta bajando otra, queda en cola. */
    fun get(key: String, work: suspend () -> Result<*>) {
        if (state[key] == DONE || state[key] == HAVE || state[key] == WORKING || state[key] == QUEUED) return
        if (working) {
            state[key] = QUEUED
            queue.addLast(key to work)
            return
        }
        working = true
        scope.launch {
            var next: Pair<String, suspend () -> Result<*>>? = key to work
            while (next != null) {
                val (k, w) = next
                state[k] = WORKING
                val r = withContext(Dispatchers.IO) { w() }
                state[k] = if (r.isSuccess) DONE else FAILED + (r.exceptionOrNull()?.let(::why) ?: "")
                next = queue.removeFirstOrNull()
            }
            working = false
        }
    }

    var first = true
    for (id in OFFERED) {
        val offer = l.themes?.firstOrNull { it.id == id }
        val s = state[id] ?: GET
        DownloadRow(
            "${themeTitle(id)} THEME",
            when {
                s == HAVE -> "Already on this device."
                s.startsWith(FAILED) -> "It did not arrive: ${s.removePrefix(FAILED)}. Press to try again."
                offer != null -> "Its look, sounds and console spins. ${mb(offer.bytes)}."
                else -> "Not available now${l.themesWhy?.let { ": $it" } ?: ""}. It can be downloaded later in Settings, Theme."
            },
            s, available = offer != null, first = first,
        ) { if (offer != null) get(id) { ThemeStore.install(ThemeStore.SOURCE, offer) } }
        first = false
    }
    val published = l.catalog
    val catalogSize = published?.let { GameDb.sizeFor(it, l.systems) }
    // Cuantas consolas cubre el catalogo publicado, no cuantas hay en la biblioteca (pedido del
    // usuario, 07-10-2026): es lo que dice lo que trae. Se bajan las que hay; las demas, al llegar.
    val catalogConsoles = published?.flatMap { it.systems }?.toSet()?.size
    val cs = state[CATALOG] ?: GET
    DownloadRow(
        "GAME CATALOG",
        when {
            cs == HAVE -> "On this device." + (catalogConsoles?.let { " Game info for $it consoles." } ?: "")
            cs.startsWith(FAILED) -> "It did not arrive: ${cs.removePrefix(FAILED)}. Press to try again."
            catalogSize != null && l.systems.isNotEmpty() ->
                "Game info for ${catalogConsoles} consoles. ${mb(catalogSize)}."
            published != null -> "Nothing to download yet: there are no games in your ROM folders."
            else -> "Not available now${l.catalogWhy?.let { ": $it" } ?: ""}. It comes down on its own when it can."
        },
        cs, available = published != null && l.systems.isNotEmpty(), first = false,
    ) {
        get(CATALOG) {
            GameDb.update(prefs.catalogSource ?: GameDb.SOURCE, l.systems).onSuccess { prefs.catalogOn = true }
        }
    }

    Spacer(Modifier.height(10.dp))
    val any = state.values.any { it == DONE || it == HAVE }
    Choice(
        if (any) "CONTINUE" else "SKIP",
        if (working) "Wait for the downloads to finish." else "Everything here can be downloaded later in Settings.",
        first = false, enabled = !working,
    ) {
        // El catalogo, encendido si se bajo o ya estaba. Si estaba publicado y no se quiso,
        // apagado: el scraper busca por el nombre del fichero. Si no estaba publicado, se queda
        // como estaba, encendido, y baja solo en cuanto se pueda.
        when {
            cs == DONE || cs == HAVE -> prefs.catalogOn = true
            published != null -> prefs.catalogOn = false
        }
        onDone()
    }
}

private const val CATALOG = "catalog"
private const val GET = "get"
private const val WORKING = "working"
private const val QUEUED = "queued"
private const val DONE = "done"
private const val HAVE = "have"
private const val FAILED = "failed:"

/**
 * Una fila de las descargas: que es, lo que ocupa o por que no se puede, y como esta a la
 * derecha. Siempre enfocable, aunque ya este bajada: una fila que deja de serlo con el foco
 * encima lo pierde, y sin foco la cruceta no va a ninguna parte.
 */
@Composable
private fun DownloadRow(
    title: String,
    detail: String,
    state: String,
    available: Boolean,
    first: Boolean,
    onGet: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    if (first) AutoFocus(requester)
    val dots = rememberWorkingDots(state == WORKING)
    val (label, color) = when {
        state == DONE -> "✓ OBTAINED" to MenuInk
        state == HAVE -> "✓ INSTALLED" to MenuInk
        state == WORKING -> "GETTING$dots" to MenuDim
        state == QUEUED -> "NEXT" to MenuDim
        state.startsWith(FAILED) -> "RETRY" to Color(0xFFE0805F)
        !available -> "—" to MenuFaint
        else -> "GET" to MenuInk
    }
    Row(
        Modifier.padding(vertical = 3.dp).widthIn(min = 560.dp, max = 640.dp)
            .padItem(onActivate = onGet, focusRequester = requester)
            .tap { onGet() }
            .padding(horizontal = 22.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MenuInk, fontSize = 14.sp, fontFamily = MenuBody, letterSpacing = 2.sp)
            Text(detail, color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, color = color, fontSize = 12.sp, fontFamily = MenuBody, letterSpacing = 2.sp)
    }
}

/** Lo que se sabe de las descargas: que hay publicado, por que no, y que consolas hay. */
private class Look(
    val themes: List<ThemeStore.Offer>?,
    val themesWhy: String?,
    val catalog: List<GameDb.Entry>?,
    val catalogWhy: String?,
    val systems: Set<String>,
)

/** Los temas que se ofrecen en la bienvenida: ver ThemeStore.OFFERED. */
private val OFFERED get() = ThemeStore.OFFERED

private fun themeTitle(id: String) = when (id) {
    "parlour" -> "PARLOUR"
    "mainframe" -> "MAINFRAME"
    else -> id.uppercase()
}

private fun why(t: Throwable): String = when (t) {
    is java.net.UnknownHostException -> "no internet connection"
    is IllegalArgumentException -> "not published yet"
    else -> t.message ?: t.javaClass.simpleName
}

private fun mb(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%d KB".format(maxOf(1L, bytes / 1024))
}

/**
 * El video de partida en los temas, o solo caratulas. Se pone en todos los temas a la vez; luego
 * cada uno lo cambia en sus ajustes.
 */
@Composable
private fun VideoStep(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    Ask(
        "GAMEPLAY VIDEOS?",
        "When you stop on a game, the panel can play a short recording of it. They are " +
            "downloaded with the covers, a few megabytes each. Without them you see the covers " +
            "only. Each theme can change this later in its settings.",
    )
    Choice("PLAY VIDEOS", "Covers and gameplay videos.", first = true, enabled = true) {
        prefs.setVideoEverywhere(true); onDone()
    }
    Choice("COVERS ONLY", "Nothing plays, and no videos are downloaded.", first = false, enabled = true) {
        prefs.setVideoEverywhere(false); onDone()
    }
}

/**
 * El Companion: que es, con una captura si el APK la trae, y si se quiere. Es lo que mas dice de
 * Ludolog y lo que menos se ve desde fuera, asi que aqui se cuenta antes de preguntar.
 */
@Composable
private fun CompanionStep(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    Ask(
        "THE COMPANION",
        "A logbook of everything you play from here: each session, how long it lasted, how much " +
            "battery it took and how hot the console ran. From it come your play times and how " +
            "long the charge will last in each game. It stays in Ludolog's folder, in a file " +
            "named after this console. L2 + R2 opens it.",
    )
    val shot = remember {
        runCatching { ctx.assets.open("welcome/companion.png").use { android.graphics.BitmapFactory.decodeStream(it) } }
            .getOrNull()?.asImageBitmap()
    }
    if (shot != null) {
        androidx.compose.foundation.Image(
            shot, contentDescription = null,
            modifier = Modifier.widthIn(max = 420.dp).padding(bottom = 14.dp),
        )
    }
    Choice("USE THE COMPANION", "It can be turned off later in Settings, Companion.", first = true, enabled = true) {
        // Su fichero ya, con el nombre de esta consola: ver Logbooks.create.
        prefs.logbook = true
        kotlin.concurrent.thread { Logbooks.create() }
        onDone()
    }
    Choice("NOT NOW", "Nothing is written down. It can be turned on later.", first = false, enabled = true) {
        prefs.logbook = false; onDone()
    }
}

/**
 * Ludolog Link, la app que acompana a Ludolog: si ya esta, se dice y se sigue; si no, se instala de
 * un APK del aparato o se baja de su pagina en GitHub (ver LinkInstaller). Opcional, como todo lo
 * de despues de los permisos. Quien bajo solo Ludolog no tiene el APK: antes este paso le decia
 * «No Ludolog Link APK found» y nada mas.
 *
 * Al volver del instalador de Android, MainActivity.onResume actualiza LinkSaveCheck.present y la
 * fila pasa sola a instalado.
 */
@Composable
private fun LinkStep(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = LinkSaveCheck.present.value
    // El APK encontrado o elegido; nulo mientras se busca o si no hay. `looked`: ya se busco.
    var found by remember { mutableStateOf<LinkInstaller.Found?>(null) }
    var looked by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    // Lo publicado en GitHub, si no hay un APK aqui; y como va su descarga (GET, WORKING o FAILED).
    var offer by remember { mutableStateOf<LinkInstaller.Offer?>(null) }
    var offerWhy by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(GET) }
    LaunchedEffect(Unit) {
        found = withContext(Dispatchers.IO) { LinkInstaller.findLocal(ctx) }
        if (found == null) {
            val r = withContext(Dispatchers.IO) { LinkInstaller.latest() }
            offer = r.getOrNull()
            offerWhy = r.exceptionOrNull()?.let(::why)
        }
        looked = true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { LinkInstaller.fromPicked(ctx, uri)?.let { LinkInstaller.inspect(ctx, it) } }
            if (f == null) failed = "That file is not Ludolog Link." else { found = f; failed = null }
        }
    }
    // El APK que espera el permiso de instalar apps: al volver con el concedido, se instala solo.
    // Antes la fila seguia diciendo «Allow Ludolog to install apps» hasta pulsarla otra vez.
    var pending by remember { mutableStateOf<LinkInstaller.Found?>(null) }
    fun install(f: LinkInstaller.Found) {
        if (f.problem != null) return
        // Android pide antes dejar a Ludolog instalar apps.
        if (!LinkInstaller.allowed(ctx)) {
            pending = f
            runCatching { ctx.startActivity(LinkInstaller.allowSettings(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) { LinkInstaller.install(ctx, f.file) }
            failed = r.exceptionOrNull()?.let { "It could not be opened: ${why(it)}" }
        }
    }
    LaunchedEffect(Grants.installs) {
        if (Grants.installs) pending?.let { pending = null; install(it) }
    }
    Ask(
        "LUDOLOG LINK",
        "Syncs saves, Companion sessions and ROMs with your other devices and your PC.",
    )
    val f = found
    when {
        installed -> Choice("CONTINUE", "Installed. It's at the end of the console list.", first = true, enabled = true) { onDone() }
        !looked -> Status("Looking for Ludolog Link…", null)
        else -> {
            if (f != null) {
                // El bajado de GitHub se dice asi, no por el nombre del fichero de trabajo.
                val what = if (offer != null && f.file.name == "github-link.apk") "Ludolog Link ${f.version}, from GitHub"
                    else "${f.file.name}, version ${f.version}"
                DownloadRow(
                    "INSTALL LUDOLOG LINK",
                    when {
                        f.problem != null -> "${f.file.name}: ${f.problem}."
                        !Grants.installs -> "$what. Android asks first to let Ludolog install apps: allow it and come back."
                        else -> "$what. ${size(f.file.length())}."
                    },
                    GET, available = f.problem == null, first = true,
                ) { install(f) }
            }
            if (f == null) {
                val o = offer
                DownloadRow(
                    "GET LUDOLOG LINK",
                    when {
                        fetching.startsWith(FAILED) -> "It did not arrive: ${fetching.removePrefix(FAILED)}. Press to try again."
                        o != null -> "Version ${o.version}, from its page on GitHub. ${size(o.bytes)}."
                        else -> "Not available now${offerWhy?.let { ": $it" } ?: ""}. It's at github.com/monkikolab/ludolog-link."
                    },
                    fetching, available = o != null, first = true,
                ) {
                    if (o == null || fetching == WORKING) return@DownloadRow
                    fetching = WORKING
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { LinkInstaller.download(ctx, o) }
                        fetching = r.exceptionOrNull()?.let { FAILED + why(it) } ?: GET
                        // Bajado: pasa a ser el APK de la fila de instalar, y se instala ya.
                        r.getOrNull()?.let { found = it; failed = null; install(it) }
                    }
                }
            }
            DownloadRow(
                "CHOOSE THE FILE",
                if (f == null) "Or pick an APK you already have." else "Another APK.",
                GET, available = true, first = false,
            ) { runCatching { picker.launch(arrayOf("application/vnd.android.package-archive")) } }
            Spacer(Modifier.height(10.dp))
            Choice("SKIP", "You can install it later in Settings, Data.", first = false, enabled = true) { onDone() }
        }
    }
    Status(null, failed)
}

/**
 * Lo que se concede fuera del programa, en los ajustes de Android.
 *
 * Estado de Compose, y mirado otra vez cada vez que la actividad vuelve a primer plano (ver
 * MainActivity.onResume): la persona va a los ajustes, concede o no, vuelve, y la lista ya
 * dice lo que hay sin que nadie tenga que preguntar.
 */
internal object Grants {
    var files by mutableStateOf(hasStorage()); private set
    var notifications by mutableStateOf(false); private set
    var overlay by mutableStateOf(false); private set
    /** Instalar apps (Ludolog Link desde la bienvenida): ver LinkInstaller.allowed. */
    var installs by mutableStateOf(false); private set

    fun refresh(ctx: Context) {
        files = hasStorage()
        notifications = runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
            .getOrDefault(false)
        overlay = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
        installs = LinkInstaller.allowed(ctx)
    }

    fun notificationSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun overlaySettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun filesSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
}

/** El marco de cada paso: el nombre, los tres pasos con el de ahora marcado, y el paso. */
@Composable
private fun Welcome(step: Int, content: @Composable () -> Unit) {
    // Centrado por la caja de fuera y con desplazamiento por dentro: centrado si cabe, y si no
    // cabe —una pantalla baja, la lista de unidades larga— se baja con la cruceta.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WelcomeColumn(step, content)
    }
}

@Composable
private fun WelcomeColumn(step: Int, content: @Composable () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Wordmark(MenuInk, Modifier.width(150.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf("DATA", "ROMS", "PERMISSIONS", "DOWNLOADS", "VIDEO", "COMPANION", "LINK").forEachIndexed { i, name ->
                val here = i + 1 == step
                Text(
                    "${i + 1}  $name",
                    color = if (here) MenuInk else MenuFaint, fontSize = 11.sp,
                    fontFamily = MenuBody, letterSpacing = 2.sp,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        content()
    }
}

/** Lo que dice cada paso arriba: una pregunta y una frase que la explica. */
@Composable
private fun Ask(title: String, detail: String) {
    Text(title, color = MenuInk, fontSize = 14.sp, fontFamily = MenuBody, letterSpacing = 2.sp,
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(detail, color = MenuDim, fontSize = 12.sp, fontFamily = MenuBody, textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun FilesGate() {
    val ctx = LocalContext.current
    Ask(
        "LUDOLOG NEEDS TO SEE YOUR FILES",
        "To find your games on every card and to keep its own folder next to them. " +
            "Android asks for this on its own page: allow it there and come back.",
    )
    Choice("ALLOW FILE ACCESS", "", first = true, enabled = true) { requestStorage(ctx) }
}

@Composable
private fun FolderChoice(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val places = remember { DataHome.places() }
    val missing = DataHome.chosen
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val busy = status != null

    fun pick(p: DataHome.Place) {
        if (busy) return
        failed = null
        status = "Preparing ${p.dir.path}…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                DataHome.adopt(p.dir) { done, total -> status = "Copying $done of $total files…" }
            }
            r.onSuccess { onDone() }
                .onFailure {
                    status = null
                    failed = it.message ?: it.javaClass.simpleName
                }
        }
    }

    if (missing != null) {
        Ask("DATA FOLDER NOT FOUND", "${missing.path}\nInsert the card it was on, or choose another place.")
        Choice("TRY AGAIN", "", first = true, enabled = !busy) {
            if (DataHome.ready()) onDone() else failed = "Still not there."
        }
    } else {
        Ask(
            "WHERE SHOULD LUDOLOG KEEP ITS DATA?",
            "Themes, scraped art, settings and the Companion's logbook, all in one folder called " +
                "${DataHome.NAME}. Take the folder to another device and everything comes with it.",
        )
    }
    places.forEachIndexed { i, p ->
        Choice(
            p.label, "${p.dir.path}  ·  ${size(p.freeBytes)} free",
            first = missing == null && i == 0, enabled = !busy,
        ) { pick(p) }
    }
    // Lo que ya habia no se pierde ni se mueve: se dice aqui para que nadie lo tema.
    if (DataHome.LEGACY.isDirectory) {
        Spacer(Modifier.height(10.dp))
        Text(
            "What is in ${DataHome.LEGACY.path} is copied there. Nothing is deleted.",
            color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody,
        )
    }
    Status(status, failed)
}

/** Una unidad y la carpeta de ROMs que tiene, si tiene. */
private class RomDrive(val label: String, val volume: File, val roms: File?, val consoles: Int)

@Composable
private fun RomsStep(onDone: (note: String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val catalog = remember {
        runCatching { CatalogLoader.load { n -> ctx.assets.open(n).bufferedReader().use { it.readText() } } }.getOrNull()
    }
    var revision by remember { mutableStateOf(0) }
    val drives = remember(revision) {
        DataHome.places().mapNotNull { p ->
            val volume = p.dir.parentFile ?: return@mapNotNull null
            val roms = RomFolders.existingOn(volume)
            RomDrive(p.label, volume, roms, if (roms != null && catalog != null) RomFolders.consoles(roms, catalog) else 0)
        }
    }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    // Lo ultimo que se creo, para decirlo debajo de la lista.
    var created by remember { mutableStateOf<String?>(null) }
    val busy = status != null

    /** Hace las carpetas de consola en [root] y devuelve lo que paso, para decirlo. */
    fun build(root: File, then: (String) -> Unit) {
        val cat = catalog ?: return
        failed = null
        status = "Creating console folders in ${root.path}…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { RomFolders.create(root, cat) } }
            status = null
            r.onSuccess { made -> then("$made console folders created in ${root.path}") }
                .onFailure { failed = it.message ?: it.javaClass.simpleName }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let(::treePath) ?: return@rememberLauncherForActivityResult
        val dir = File(path)
        val cat = catalog
        // Una carpeta con consolas dentro se usa tal cual; una vacia, o nueva, se llena.
        if (cat != null && RomFolders.consoles(dir, cat) == 0) {
            build(dir) { note -> prefs.romDirs = setOf(path); onDone(note) }
        } else {
            prefs.romDirs = setOf(path)
            onDone("Reading ROMs from $path")
        }
    }

    val found = drives.filter { it.roms != null }
    Ask(
        if (found.isEmpty()) "WHERE WILL YOUR GAMES GO?" else "WHERE ARE YOUR GAMES?",
        if (found.isEmpty()) "No ROMs folder yet. Ludolog can make one with a folder for each console, " +
            "named the way ES-DE names them, so the same card works in either."
        else "One folder per console inside, the way ES-DE lays them out. More can be added later " +
            "in Settings, Library, ROM folders.",
    )
    var first = true
    found.forEach { d ->
        Choice(
            "USE ${d.label}", "${d.roms!!.path}  ·  ${d.consoles} console ${if (d.consoles == 1) "folder" else "folders"}",
            first = first, enabled = !busy,
        ) {
            // Con una sola, lo de siempre: se busca sola, y seguira encontrandola. Con mas de
            // una, elegir una es decir que las otras no.
            if (found.size == 1) prefs.romDirs = null else prefs.romDirs = setOf(d.roms.path)
            onDone("Reading ROMs from ${d.roms.path}")
        }
        first = false
    }
    if (found.size > 1) {
        Choice("USE ALL OF THEM", found.joinToString("  ·  ") { it.roms!!.path }, first = false, enabled = !busy) {
            prefs.romDirs = null
            onDone("Reading ROMs from every drive")
        }
    }
    drives.filter { it.roms == null }.forEach { d ->
        val root = File(d.volume, "ROMs")
        Choice(
            "CREATE ROMS ON ${d.label}", "${root.path}, with a folder for each console",
            first = first, enabled = !busy && catalog != null,
        ) {
            build(root) { note ->
                // Se queda en este paso, ya con la carpeta en la lista, para que se vea hecha.
                revision++
                created = note
            }
        }
        first = false
    }
    Choice(
        "CHOOSE ANOTHER FOLDER…", "Android's folder picker. An empty folder gets one folder per console.",
        first = first, enabled = !busy,
    ) { runCatching { picker.launch(null) } }
    Choice("SKIP", "Look for ROMs, Roms or roms on every drive, as usual.", first = false, enabled = !busy) {
        onDone(null)
    }
    Status(status ?: created, failed)
}

@Composable
private fun PermissionsStep(romNote: String?, onStart: () -> Unit) {
    val ctx = LocalContext.current
    // La de la app de inicio se pide con un dialogo de Android que devuelve respuesta.
    val home = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        HomeApp.refresh(ctx)
    }
    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Ask(
        "PERMISSIONS",
        "Each one opens its page in Android's settings. Come back and it shows here. " +
            "Only file access is needed; the rest make things nicer.",
    )
    Permission(
        "FILE ACCESS", "Find your games and keep Ludolog's folder. Needed.",
        granted = Grants.files, first = false,
    ) { open(Grants.filesSettings(ctx)) }
    Permission(
        "NOTIFICATIONS", "A quiet notice while a game is being written down.",
        granted = Grants.notifications, first = true,
    ) { open(Grants.notificationSettings(ctx)) }
    Permission(
        "DISPLAY OVER OTHER APPS", "The session card over the emulator. Android opens its list: pick Ludolog there.",
        granted = Grants.overlay, first = false,
    ) { open(Grants.overlaySettings(ctx)) }
    // Solo si esta app puede serlo: la copia de prueba no, y la fila no hacia nada.
    if (remember { HomeApp.possible(ctx) }) Permission(
        "HOME APP", "Ludolog opens with the Home button. Optional, and Android asks first.",
        granted = HomeApp.held, first = false, grantedLabel = "YES", deniedLabel = "NO",
    ) { runCatching { home.launch(HomeApp.intent(ctx)) } }
    Spacer(Modifier.height(10.dp))
    Choice("CONTINUE", romNote.orEmpty(), first = false, enabled = true) { onStart() }
}

/** Una fila de la lista de permisos: que es, para que, y si esta. */
@Composable
private fun Permission(
    title: String,
    detail: String,
    granted: Boolean,
    first: Boolean,
    grantedLabel: String = "ALLOWED",
    deniedLabel: String = "NOT ALLOWED",
    onOpen: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    if (first) AutoFocus(requester)
    Row(
        Modifier.padding(vertical = 3.dp).widthIn(min = 560.dp, max = 640.dp)
            .padItem(onActivate = onOpen, focusRequester = requester)
            .tap { onOpen() }
            .padding(horizontal = 22.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MenuInk, fontSize = 14.sp, fontFamily = MenuBody, letterSpacing = 2.sp)
            Text(detail, color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            if (granted) grantedLabel else deniedLabel,
            color = if (granted) MenuInk else Color(0xFFE0805F), fontSize = 12.sp,
            fontFamily = MenuBody, letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun Status(status: String?, failed: String?) {
    status?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MenuInk, fontSize = 12.sp, fontFamily = MenuBody, textAlign = TextAlign.Center)
    }
    failed?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = Color(0xFFE0805F), fontSize = 12.sp, fontFamily = MenuBody, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Choice(
    title: String,
    detail: String,
    first: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    if (first) AutoFocus(requester)
    Column(
        Modifier.padding(vertical = 3.dp).widthIn(min = 520.dp)
            .padItem(onActivate = onPick, focusRequester = requester, enabled = enabled)
            .tap(enabled) { onPick() }
            .padding(horizontal = 22.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = MenuInk, fontSize = 15.sp, fontFamily = MenuBody, letterSpacing = 2.sp)
        if (detail.isNotEmpty()) {
            Text(detail, color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody, textAlign = TextAlign.Center)
        }
    }
}

private fun size(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.0f GB".format(bytes / (1L shl 30).toDouble())
    else "%.0f MB".format(bytes / (1L shl 20).toDouble())

/**
 * Tocar sin hacer el elemento enfocable. `clickable` lo hace por su cuenta, y sumado al de
 * padItem quedaban dos nodos de foco por fila: al volver del instalador de Android el foco caia en
 * el de clickable, que no dibuja nada, y CONTINUE respondia a A sin verse marcado (07-10-2026). Es
 * lo mismo que ya hacian las filas del menu (ver MenuRow).
 */
private fun Modifier.tap(enabled: Boolean = true, onTap: () -> Unit): Modifier = composed {
    val latest = rememberUpdatedState(onTap)
    if (!enabled) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { latest.value() } }
}
