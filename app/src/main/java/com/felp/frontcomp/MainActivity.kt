package com.felp.frontcomp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Layout según el diagrama: barra de estado arriba, lista a la izquierda con la selección
 * enmarcada y su descripción debajo, y el elemento seleccionado en grande a la derecha.
 *
 * Lo que hace que esto funcione con mando es que solo hay UNA columna navegable. La
 * selección se mueve arriba y abajo, y todo lo demás de la pantalla es reflejo de dónde
 * está: nada más compite por el foco.
 */

// Los colores salen del tema activo, no de constantes. Los nombres se mantienen porque son
// los que ya usa toda la pantalla; lo unico que cambia es de donde vienen.
//
// Y DENTRO DE UNA FILA INVERTIDA se dan la vuelta solos.
//
// Hay temas cuya fila elegida es un bloque del color de la tinta con el texto del color del
// fondo, como un terminal. Ahi, todo lo que se escriba con la tinta desaparece. Se podria
// arreglar fila por fila —y son muchas: el nombre de la consola, la cifra de la derecha, el
// pie de cada barra, el rotulo de cada juego— o se puede arreglar donde el color se pide, que
// es aqui. Lo segundo no se olvida en la fila numero once.
//
// `ground` y `line` NO se invierten: son fondos y reglas, y darles la vuelta pintaria el
// bloque encima de si mismo.
private val inverted: Boolean @Composable get() = LocalRowInverted.current

internal val MenuInk: Color
    @Composable get() = if (inverted) LocalTheme.current.ground else LocalTheme.current.ink
internal val MenuDim: Color
    @Composable get() =
        if (inverted) LocalTheme.current.ground.copy(alpha = .72f) else LocalTheme.current.dim
internal val MenuFaint: Color
    @Composable get() =
        if (inverted) LocalTheme.current.ground.copy(alpha = .48f) else LocalTheme.current.faint
internal val MenuGround: Color @Composable get() = LocalTheme.current.ground
/** La regla del tema, y el acento cuando el tema no guarda una. Ver `Theme.line`. */
internal val MenuLine: Color @Composable get() = LocalTheme.current.let { it.line ?: it.accent }

/** La fuente de los textos pequenos de navegacion. */
internal val MenuBody: FontFamily @Composable get() = LocalTheme.current.body

/** La fuente de titulos. Hoy es la misma que la del cuerpo en los tres temas; sigue aparte por si un tema vuelve a querer una distinta. */
internal val MenuDisplay: FontFamily @Composable get() = LocalTheme.current.display

private const val LIST_FRACTION = 0.34f

/**
 * El ancho de la lista: su parte de la pantalla, con un minimo legible y nunca mas de la mitad.
 * El minimo es lo que mide el porcentaje en una consola de pruebas (1920 a 360 dpi, 853 dp): ahi no cambia
 * nada. En 4:3 el 30 % cortaba los nombres («SUPER NINT…»), y en 1:1 casi no se leian.
 */
internal fun listWidth(screen: androidx.compose.ui.unit.Dp, instrument: Boolean): androidx.compose.ui.unit.Dp {
    val fraction = if (instrument) 0.30f else LIST_FRACTION
    val min = if (instrument) 256.dp else 290.dp
    return (screen * fraction).coerceAtLeast(min).coerceAtMost(screen * 0.5f)
}

/**
 * Lo que va a la derecha de la lista en la sala —la consola y su texto—.
 *
 * En una pantalla ancha (16:9 o mas), en un marco que en 16:9 es la pantalla entera y en las mas
 * alargadas se encoge para que su borde de lista caiga donde acaba la lista de verdad: las
 * fracciones de `tvs.toml` siguen valiendo, y en una consola de pruebas no cambia nada.
 *
 * En una mas estrecha (3:2, 4:3, 1:1) eso dejaba la consola diminuta y el texto en una columna
 * que no cabia: la lista se queda con su ancho minimo y a la derecha queda poco. Ahi la consola y
 * el texto usan todo el hueco libre: la consola, lo mas grande que quepa en el centro, y el texto
 * debajo, de lado a lado.
 */
@Composable
internal fun BesideListFrame(skin: TvSkin, content: @Composable (TvSkin) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val list = listWidth(maxWidth, instrument = false)
        if (maxWidth / maxHeight >= WIDE_ASPECT) {
            val frame = (maxWidth - list) / (1f - LIST_FRACTION)
            Box(Modifier.fillMaxHeight().width(frame).align(Alignment.TopEnd)) { content(skin) }
        } else {
            Box(Modifier.fillMaxHeight().width(maxWidth - list).align(Alignment.TopEnd)) {
                content(
                    skin.copy(
                        console = androidx.compose.ui.geometry.Rect(0.06f, 0.20f, 0.94f, 0.64f),
                        caption = androidx.compose.ui.geometry.Rect(0.06f, 0.66f, 0.96f, 0.98f),
                    )
                )
            }
        }
    }
}

/** Desde aqui una pantalla es ancha (16:9 son 1,78): ver BesideListFrame. */
internal const val WIDE_ASPECT = 1.7f

/** Por debajo de esto, casi cuadrada (4:3 son 1,33; algunas consolas, 1,15): ver MenuLayout. */
internal const val SQUARE_ASPECT = 1.25f

/** Si la pantalla de la ventana es casi cuadrada (y la lista no esta en otra pantalla). */
@Composable
internal fun squareScreen(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return DualScreen.display.value == null && c.screenHeightDp > 0 &&
        c.screenWidthDp.toFloat() / c.screenHeightDp < SQUARE_ASPECT
}

// Hombros y gatillos son cosas distintas y NO comparten conjunto.
//
// Los llevaban juntos porque unos mandos emiten L1 y otros L2 para el mismo hombro. Pero
// este aparato emite los cuatro: apretar los dos gatillos disparaba el acorde del cajon de
// apps —que solo pide una tecla de cada lado— y el cuaderno no llegaba a abrirse nunca.
//
// Ojo tambien: el mando entrega los gatillos POR PARTIDA DOBLE, como tecla y como eje
// analogico. Aqui se atiende la tecla; el eje lo lee Triggers para los mandos que solo
// mandan eso.
internal val LEFT_SHOULDER = setOf(Key.ButtonL1, Key.PageUp)
internal val RIGHT_SHOULDER = setOf(Key.ButtonR1, Key.PageDown)
internal val SHOULDER_KEYS = LEFT_SHOULDER + RIGHT_SHOULDER

/** Los gatillos, que abren el cuaderno cuando se aprietan los dos a la vez. */
internal val TRIGGER_KEYS = setOf(Key.ButtonL2, Key.ButtonR2)

/**
 * El cuaderno, cuando se le mira desde la lista de consolas.
 *
 * Un identificador reservado, igual que el de Android: no es una consola de verdad y no puede
 * estar en el catalogo, pero el resto de la pantalla —el giro, el pie de foto— pregunta por un
 * identificador de consola y no tiene por que aprender una excepcion nueva.
 */
/**
 * Vuelve a abrir la aplicacion desde cero, matando el proceso.
 *
 * Es la salvaguarda del cambio de tema. Un tema no es solo color y letra: son los sonidos, la
 * letra instalada, los medios, la sala y el fichero de salas, y cada uno de esos vive en un
 * sitio distinto con su propia vida —un SoundPool con el PCM ya descomprimido, un indice de
 * arte construido al arrancar, unas raices de medios guardadas—. Cambiarlos todos en caliente
 * se puede, y se hace, pero basta con que uno se quede atras para que el aparato enseņe medio
 * tema viejo sin decir nada. Ya paso dos veces: los sonidos seguian siendo los del tema con el
 * que se abrio, y los giros salian de la carpeta del vecino.
 *
 * `recreate()` no vale: conserva el ViewModel, que es justo donde vive el indice de arte. Hay
 * que tirar el proceso entero.
 *
 * Y antes, a disco: lo elegido se guarda con `apply`, que escribe en otro hilo, y matar el
 * proceso sin esperar reabriria con el tema anterior.
 */
internal fun restartApp(ctx: Context) {
    Prefs(ctx).flush()
    val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ctx.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

internal const val RECKONING_SYSTEM = "__reckoning"

/** La entrada de Ludolog Link en la lista de consolas, despues del Companion (ver LinkSaveCheck). */
internal const val LINK_SYSTEM = "__link"
internal const val LINK_LABEL = "LUDOLOG LINK"

/**
 * Lo seleccionado en la lista de consolas que SI tiene menu de contexto.
 *
 * El cuaderno no lo tiene, y no es que falte: no hay nombre que cambiar, ni descripcion que
 * escribir, ni emulador que elegir, ni se puede esconder de su propia lista. Un menu entero
 * de opciones que no aplican es peor que no tener menu.
 *
 * Va en una funcion porque hay DOS caminos hasta ese menu —el boton del mando y el toque
 * mantenido— y una regla escrita dos veces acaba siendo dos reglas.
 */
private fun menuTarget(system: String?): String? =
    system?.takeIf { it != RECKONING_SYSTEM && it != LINK_SYSTEM && it != RECENT_SYSTEM }

/** Lo que tiene que quedarse el front-end delante para que cuente como vuelta de un juego. */
private const val RETURN_CONFIRM_MS = 3_000L

/** Cuanto se espera el dibujo pedido al recuperar el foco. Ver MainActivity.watchDrawing. */
private const val DRAW_CHECK_MS = 1_500L

/** Lo que se queda un aviso de abajo: lo justo para leer una linea. */
private const val TOAST_MS = 5_000L

/** Las teclas del volumen, que la entrada deja pasar: ver MainActivity.dispatchKeyEvent. */
private val VOLUME_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_VOLUME_UP,
    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
    android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
)

/**
 * Como se llama el cuaderno de partidas, en todas partes.
 *
 * UNO para todos los temas, y no uno por tema. Llego a haber uno por tema —«The Reckoning» en
 * el Parlour, otro en el de papel— y la idea era que el nombre fuera parte del aspecto. Lo es,
 * pero el precio es peor: quien cambia de tema y se encuentra otro rotulo no piensa «mismo
 * sitio, otra ropa», piensa que es otra herramienta y que lo apuntado se ha quedado en la
 * anterior. Un nombre no es decoracion, es la promesa de que es el mismo sitio.
 *
 * «Companion» y no otra cosa porque es como se llama ya en todo lo demas: CompanionArt, los
 * sonidos companion_open y companion_close, el guion que monta el emblema. Habia dos
 * vocabularios para una sola pantalla y este cierra el que sobraba.
 */
internal const val COMPANION_LABEL = "COMPANION"

/** El mismo, en caja de titulo, para donde no se escribe en mayusculas. */
internal const val COMPANION_NAME = "Companion"

/**
 * Select abre los ajustes; Start abre el menu de lo que este elegido.
 *
 * Los dos se pueden cambiar en ajustes, y por eso salen de Shortcuts en vez de estar escritos
 * aqui: lo que hay en Shortcuts es la intencion —«Select»— y los codigos que valen para ella,
 * porque no hay dos mandos que reporten los botones igual. Los acordes —los dos hombros, los
 * dos gatillos— siguen fijos y siguen aqui.
 */
internal fun settingsKeys(prefs: Prefs) = Shortcuts.keys(prefs, Shortcuts.Action.SETTINGS)

internal fun quickMenuKeys(prefs: Prefs) = Shortcuts.keys(prefs, Shortcuts.Action.QUICK_MENU)

internal fun searchKeys(prefs: Prefs) = Shortcuts.keys(prefs, Shortcuts.Action.SEARCH)

/** El de fabrica, para las pantallas que no tienen las preferencias a mano. */
internal val SETTINGS_KEYS = Shortcuts.Action.SETTINGS.fallback.keys

/** Un booleano recordado que NO es estado de Compose: sirve para comparar con el anterior. */
internal class BooleanHolder(var value: Boolean)

private sealed interface Screen {
    data object Systems : Screen
    /** [focus]: el juego en que empezar, por su ruta (el elegido en la busqueda). */
    data class Games(val systemId: String, val focus: String? = null) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()
        // El tema, antes de pintar nada: las raices de medios se resuelven una vez y se
        // guardan, y la primera resolucion la dispara la primera composicion. Sin esto, el
        // primer fotograma buscaria en la carpeta comun y solo se corregiria al siguiente.
        // Antes que nada del tema: las carpetas con el nombre de antes pasan al de ahora.
        ThemeFiles.renameFolders()
        // Y el Gallery que trae el APK, en su carpeta, si falta algo: ver ThemeFiles.unpackBuiltIn.
        ThemeFiles.unpackBuiltIn(this)
        // La segunda pantalla, si la consola tiene dos: ver DualScreen.
        DualScreen.watch(this)
        ThemeFiles.use(themeById(Prefs(this).themeId).id)
        // El ajuste de volumen de cada sonido, del tema puesto: ver SoundGains.
        SoundGains.init(Prefs(applicationContext))
        // Sin permiso o sin carpeta de datos no hay nada que ensenar: primero eso, y al
        // resolverlo se reinicia el proceso para que todo arranque ya leyendo de la carpeta. Y
        // con la bienvenida a medias, lo que quede de ella: ver DataSetup.
        val ready = DataHome.ready() && DataHome.setupStep == null
        setContent {
            AppTheme {
                if (ready) Root() else DataSetup()
                // Encima de todo, un momento: ver Intro.
                Intro()
                if (BuildConfig.DEV) DevBadge()
            }
        }
        muteSystemClicks()
        window.decorView.viewTreeObserver.addOnDrawListener { lastDraw = android.os.SystemClock.uptimeMillis() }
    }

    /**
     * Ludolog congelado al desbloquear (consola de pruebas, Android 15, 2026-10-07): el ultimo fotograma quieto,
     * sin responder, y la barra de estado encima. La ventana estaba visible, con el foco y la
     * actividad en marcha, pero su vista raiz seguia creyendo la pantalla apagada
     * (`mLastPerformDrawFailedReason=screen_off`) y se saltaba cada dibujo: el aviso de Android de
     * que la pantalla se encendio no le llego (el proceso estaba congelado detras del bloqueo). Eso
     * solo se corrige con una ventana nueva, que lee el estado de la pantalla al crearse.
     *
     * Al recuperar el foco se pide un dibujo; si con la pantalla encendida y sin bloqueo no llega,
     * la actividad se rehace (`recreate`: el modelo y lo guardado con rememberSaveable siguen, la
     * entrada no se repite). Como mucho una vez por minuto.
     */
    private var lastDraw = 0L
    private var drawAsked = 0L
    private var lastRescue = 0L
    private val drawCheck = Runnable { checkDrawing() }

    private fun watchDrawing() {
        drawAsked = android.os.SystemClock.uptimeMillis()
        window.decorView.invalidate()
        confirm.removeCallbacks(drawCheck)
        confirm.postDelayed(drawCheck, DRAW_CHECK_MS)
    }

    private fun checkDrawing() {
        if (isFinishing || isDestroyed || !hasWindowFocus() || lastDraw >= drawAsked) return
        val screenOn = window.decorView.display?.state == android.view.Display.STATE_ON
        val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!screenOn || !interactive || locked) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRescue < 60_000) return
        lastRescue = now
        android.util.Log.w("Ludolog", "la ventana no dibuja con la pantalla encendida: se rehace")
        // Una vuelta de un juego por confirmar se confirma ya, con su hora: al rehacerse se perderia.
        if (confirm.hasCallbacks(returnedForGood)) {
            confirm.removeCallbacks(returnedForGood)
            SessionTracker.returned(this, SessionTracker.backAt)
        }
        recreate()
    }

    /**
     * Sin el clic de navegacion de Android. Al mover el foco con la cruceta, la vista de Compose
     * le pide al sistema su sonido de navegacion, y ese suena por el canal del sistema: el mismo
     * en los tres temas, encima del de cada tema, y sin callarse al bajar el volumen de la
     * consola. Los sonidos de Ludolog son los del tema (Sfx), y esos si siguen el volumen.
     *
     * Se apaga en las vistas de esta ventana y no en el ajuste de Android de «sonidos al tocar»,
     * que es de todo el aparato. La vista de Compose se crea al pegarse a la ventana, asi que se
     * repasa tambien cuando cambia el arbol de vistas.
     */
    private fun muteSystemClicks() {
        fun mute(v: android.view.View) {
            v.isSoundEffectsEnabled = false
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) mute(v.getChildAt(i))
        }
        val root = window.decorView
        mute(root)
        root.post { mute(root) }
        root.viewTreeObserver.addOnGlobalLayoutListener { mute(root) }
    }

    /**
     * Un frontend ocupa la pantalla entera: la hora y la batería las pinta él, con su
     * propio aspecto, y las barras de Android solo restan sitio en una pantalla que ya es
     * baja de por sí.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Hay que repetirlo al recuperar el foco: volver de un emulador, o de los ajustes
        // del sistema, deja las barras otra vez a la vista.
        if (hasFocus) { goImmersive(); watchDrawing() }
    }

    /**
     * El ambiente se para al perder la pantalla y vuelve al recuperarla.
     *
     * Un bucle sonando detras de un emulador seria peor que un fallo: se oiria encima del
     * juego. Y con la pantalla apagada es bateria gastada en algo que nadie oye.
     */
    override fun onPause() {
        super.onPause()
        Sfx.suspendAll()
        PanelPlayers.pauseAll()
        // Fuera de la vista no se mueve nada, y el reposo no cuenta: ver Motion.
        Motion.away.value = true
        confirm.removeCallbacks(restNow)
        confirm.removeCallbacks(restAmbience)
        confirm.removeCallbacks(drawCheck)
        // Se fue antes de confirmar la vuelta: era el emulador volviendo al frente. Salvo que
        // lo que se fuera fuera la pantalla. Salir del juego y apagarla en esos tres segundos
        // dejaba la partida abierta, y al encenderla un rato despues se cerraba en ESE momento,
        // con el rato de pantalla apagada contado como juego. La vuelta ya fue, y fue aqui.
        val pending = confirm.hasCallbacks(returnedForGood)
        confirm.removeCallbacks(returnedForGood)
        val screenOff = getSystemService(android.os.PowerManager::class.java)?.isInteractive == false
        if (pending && screenOff) SessionTracker.returned(this, SessionTracker.backAt)
    }

    /**
     * Aqui solo llega Home: esta actividad es la de inicio, y un intent nuevo es alguien que
     * vuelve a proposito. Eso anula el reintento de RetroArch; ver Bounce.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Bounce.clear()
    }

    override fun onResume() {
        super.onResume()
        // RetroArch acaba de cerrarse solo al recibir el juego: se le vuelve a dar, una vez, y
        // esto no cuenta como volver. Sin sonido ni fin de partida: el front-end se va enseguida.
        Bounce.take()?.let { again ->
            if (runCatching { startActivity(again) }.isSuccess) return
        }
        Sfx.resumeAll()
        PanelPlayers.resumeAll()
        Prefs(this).let { p -> Motion.fps.intValue = p.animationFps; Motion.saver.value = p.batterySaver }
        // Si Ludolog Link esta: la sala saca su radio. Se mira al volver por si se instalo o se quito.
        LinkSaveCheck.refresh(this)
        // Los ficheros del tema se vuelven a buscar: Link o el usuario pueden haber cambiado alguno.
        ThemeFiles.forget()
        Motion.away.value = false
        noteInput()
        // Volver aqui es haber salido del emulador: la partida termino. Pero solo si se queda.
        //
        // Hay emuladores que al arrancar dejan pasar al front-end un instante —el RetroArch que
        // se reinicia, uno que cierra su pantalla de carga y abre la del juego— y contar eso
        // como la vuelta cerraba la partida al segundo; por corta se tiraba, y lo que se jugaba
        // despues no lo apuntaba nadie. Se confirma a los pocos segundos, y la hora que vale es
        // la de ahora, no la de la confirmacion.
        SessionTracker.backAt = System.currentTimeMillis()
        confirm.removeCallbacks(returnedForGood)
        confirm.postDelayed(returnedForGood, RETURN_CONFIRM_MS)
        // Y lo de la app de inicio, que se cambia fuera: ver HomeApp. Y los permisos, igual: la
        // bienvenida manda a los ajustes de Android y al volver tiene que saber que se concedio.
        HomeApp.refresh(this)
        Grants.refresh(this)
    }

    private val confirm = android.os.Handler(android.os.Looper.getMainLooper())

    /** Sin tocar nada desde hace Motion.IDLE_MS: reposo. Ver Motion. */
    private val restNow = Runnable { Motion.idle.value = true }

    /** Y a los minutos, el ambiente se calla en fundido. Ver Sfx.rest. */
    private val restAmbience = Runnable { Sfx.rest() }

    /**
     * Una tecla, un toque o el joystick: fuera el reposo, y se vuelve a contar desde ahora. Un
     * temporizador que se reprograma, no un reloj que pregunta: quieto no cuesta nada.
     */
    private fun noteInput() {
        Motion.input()
        Sfx.wake()
        confirm.removeCallbacks(restNow)
        confirm.postDelayed(restNow, Motion.IDLE_MS)
        confirm.removeCallbacks(restAmbience)
        confirm.postDelayed(restAmbience, AMBIENCE_REST_MS)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        noteInput()
        return super.dispatchTouchEvent(ev)
    }
    private val returnedForGood = Runnable { SessionTracker.returned(this, SessionTracker.backAt) }

    /**
     * Los gatillos del mando, que no llegan como teclas.
     *
     * En este aparato L2 y R2 son ejes analogicos y no botones, asi que un onKeyEvent no ve
     * nada de ellos. Los eventos de movimiento del joystick solo pasan por aqui, de modo que
     * el acorde se lee en la actividad y la interfaz lo observa.
     */
    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        noteInput()
        Triggers.read(event)
        return super.onGenericMotionEvent(event)
    }

    // Con la entrada delante, las teclas no llegan a nada: la lista de detras ya tiene el foco y
    // una A a ciegas abriria una consola sin verla. Aqui y no con el foco de Compose porque
    // quitarle el foco a la lista y devolverselo despues es justo lo que ya ha fallado otras
    // veces. El volumen si pasa: es del aparato, no del programa.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        noteInput()
        return if (Boot.introShowing && event.keyCode !in VOLUME_KEYS) true
        // Con la lista en la pantalla de abajo, sus teclas van alli: ver DualScreen.
        else DualScreen.forward(event) || super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        Sfx.release()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            // Deslizar desde el borde las muestra un momento y vuelven a ocultarse solas,
            // en vez de quedarse fijas y descolocar el layout.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun Root(vm: LibraryViewModel = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasStorage()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Systems) }
    var toast by remember { mutableStateOf<String?>(null) }
    // Y se va solo. Solo lo quitaba el siguiente lanzamiento que saliera bien, asi que un
    // «No emulator installed» de PICO-8 seguia debajo de la lista de Nintendo 64.
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(TOAST_MS)
            toast = null
        }
    }
    // Los ajustes no son un sitio al que se va, sino una ventana que se abre encima: la
    // biblioteca sigue detrás y eso ya dice que esto es un desvío, no un destino.
    var settingsOpen by remember { mutableStateOf(false) }
    // El cuaderno de partidas. Va en lugar de la sala y no encima de ella, asi que
    // cuenta como ventana modal para todo lo demas: mientras esta, nada mas responde.
    var statsOpen by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }
    // Buscar un juego en todas las consolas: ver SearchWindow.
    var searchOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Any?>(null) }   // String (consola) o Game

    // Lo que hay seleccionado ahora mismo, que la raíz necesita para saber sobre qué abrir
    // el menú contextual cuando se pulsa Y.
    var currentSystem by remember { mutableStateOf<String?>(null) }
    var currentGame by remember { mutableStateOf<Game?>(null) }

    val showReport = vm.reportOpen && vm.report != null
    val anyModal = settingsOpen || appsOpen || searchOpen || menuFor != null || showReport || statsOpen ||
        vm.coverHunt != null || vm.videoHunt != null || vm.saveCheck != null
    // La segunda pantalla: con ella la lista va abajo y sus teclas tambien, salvo con una ventana
    // encima. Ver DualScreen.
    val dual = DualScreen.display.value
    SideEffect { DualScreen.routeToList = dual != null && !anyModal }
    // Lo que dice el modelo por su cuenta (Link, un fallo al lanzar despues de preguntar), abajo.
    LaunchedEffect(vm.notice) { vm.notice?.let { toast = it; vm.notice = null } }

    // Los dos gatillos a la vez lo abren. El efecto salta cuando el acorde CAMBIA, no
    // mientras dura, que es lo que evita reabrirlo en cada fotograma con ellos apretados.
    LaunchedEffect(Triggers.chord) {
        if (Triggers.chord && !anyModal && vm.prefs.logbook) statsOpen = true
    }

    // Lo que cuenta la tarjeta de la lista: se lee al arrancar, al quedar escrita una partida y
    // al cerrar el cuaderno, que es donde se borran. Con el Companion apagado no se lee: la
    // tarjeta cuenta entonces lo de siempre. Y otra vez con el catalogo cargado: al arrancar
    // esto va antes, y leido sin el, las partidas de PC apuntadas como «steam» no eran de «pc».
    // Y al cambiar la biblioteca, el nombre de un juego o su ficha (un genero puesto a mano): las
    // partidas se leen con lo de hoy.
    val companion = vm.prefs.logbook
    LaunchedEffect(companion, SessionTracker.written, statsOpen, vm.catalog, vm.result, vm.prefs.labelRevision, vm.dossierRevision) {
        if (companion && !statsOpen) vm.readPlayed(ctx)
    }
    // Y la carga de la bateria, de la que sale cuanto queda de juego: cada minuto.
    LaunchedEffect(companion) {
        if (!companion) return@LaunchedEffect
        while (true) {
            vm.readCharge(ctx)
            kotlinx.coroutines.delay(60_000)
        }
    }

    LaunchedEffect(Unit) {
        vm.loadCatalog { name -> ctx.assets.open(name).bufferedReader().use { it.readText() } }
        vm.refreshAndroidGames(ctx)
        // La biblioteca de la ultima vez aparece ya; el repaso va detras y se pone al dia.
        vm.loadRemembered()
        vm.scan(auto = true)
        // Los intercambios que quedaron a medias: aqui no hay ningun video abierto todavia.
        // Y las partidas que quedaron abiertas porque el sistema mato la aplicacion: se cierran
        // en su ultima medida, ver Logbook.closeUnfinished. Salvo que haya una en marcha: si la
        // pantalla se rehizo con el proceso vivo, su servicio sigue midiendo y la cerrara el. Sin
        // esta condicion, rehacerse la pantalla a mitad de partida la borraba.
        //
        // En otro hilo: es escribir en el cuaderno, y se hacia en el de la pantalla al arrancar
        // (revision del 09-10-2026). Si cerro alguna, las cuentas se vuelven a leer.
        if (!SessionTracker.recording) {
            val least = vm.prefs.minSessionSeconds * 1000L
            val closed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Logbook(ctx).use { it.closeUnfinished(least) } }.getOrDefault(false)
            }
            if (closed) SessionTracker.written++
        }
        TapeQueue.sweep(ArtIndex.defaultRoots())
    }
    // Una vez al dia, si hay version nueva en GitHub: un aviso abajo, una sola vez por version (ver
    // Updates). Despues del arranque, para no competir con el repaso de la biblioteca.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(20_000)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Updates.daily(ctx, vm.prefs) }.getOrNull() }
            ?.let { vm.notice = it }
    }
    // Un acceso recien anclado desde otra app (PinShortcut.kt): un repaso, para que salga ya.
    val pinned = PinnedShortcuts.added.intValue
    LaunchedEffect(pinned) { if (pinned > 0) vm.scan(auto = true) }
    // Y lo mismo cuando Ludolog Link sube, renombra o borra ROMs desde el PC. Ver LinkBridge.
    val linked = LinkBridge.changes.intValue
    LaunchedEffect(linked) { if (linked > 0) vm.scan(auto = true) }
    // Nombres, descripciones o generos corregidos en otra consola (ver LinkEdits): ya aplicados.
    val edited = LinkBridge.edited.intValue
    LaunchedEffect(edited) { if (edited > 0) vm.linkEdited() }
    // Ajustes nuevos llegados del PC (ver LinkConfig): ya estan aplicados; la pantalla se refresca
    // entera como al cambiar de tema. Nunca a mitad de una partida ni con la app detras: se espera
    // a que el tracker la cierre y a que Ludolog vuelva a estar delante.
    val configured = LinkBridge.configChanged.intValue
    LaunchedEffect(configured) {
        if (configured == 0) return@LaunchedEffect
        while (SessionTracker.recording || Motion.away.value) kotlinx.coroutines.delay(1_000)
        restartApp(ctx)
    }
    // Un respaldo devuelto desde el PC que llego con el proceso ya vivo y sin pantalla (ver
    // LinkRestore): espera a este reinicio, con las mismas condiciones.
    LaunchedEffect(Unit) { if (LinkRestore.pending()) LinkBridge.configChanged.intValue++ }

    /*
     * Los sonidos se enganchan aqui, a los cambios de estado, y no en cada sitio que los
     * provoca. Entrar en una consola se hace desde el menu, desde los gatillos y desde el
     * toque, y las tres cosas terminan cambiando `screen`: escuchar el resultado en vez de
     * las tres causas es una linea en lugar de tres, y no se olvida ninguna el dia que
     * aparezca una cuarta.
     */
    LaunchedEffect(Unit) {
        Sfx.uiOn = vm.prefs.uiSound
        Sfx.ambienceOn = vm.prefs.ambienceSound
        Sfx.load(ctx)
        Sfx.ambience(true)
    }

    var lastScreen by remember { mutableStateOf(screen) }
    LaunchedEffect(screen) {
        val before = lastScreen
        lastScreen = screen
        if (before == screen) return@LaunchedEffect
        // Salir es volver a la lista de consolas. Todo lo demas es entrar, incluido saltar
        // de una consola a la siguiente con los gatillos.
        Sfx.play(if (screen is Screen.Systems) Sfx.Cue.CLOSE else Sfx.Cue.OPEN)
    }

    var appsWere by remember { mutableStateOf(appsOpen) }
    LaunchedEffect(appsOpen) {
        if (appsWere == appsOpen) return@LaunchedEffect
        appsWere = appsOpen
        Sfx.play(if (appsOpen) Sfx.Cue.OPEN else Sfx.Cue.CLOSE)
    }

    var menuWas by remember { mutableStateOf(menuFor != null) }
    LaunchedEffect(menuFor != null) {
        val now = menuFor != null
        if (menuWas == now) return@LaunchedEffect
        menuWas = now
        Sfx.play(if (now) Sfx.Cue.MENU else Sfx.Cue.CLOSE)
    }

    var settingsWere by remember { mutableStateOf(settingsOpen) }
    LaunchedEffect(settingsOpen) {
        if (settingsWere == settingsOpen) return@LaunchedEffect
        settingsWere = settingsOpen
        Sfx.play(if (settingsOpen) Sfx.Cue.MENU else Sfx.Cue.CLOSE)
    }

    BackHandler(enabled = anyModal || screen !is Screen.Systems) {
        when {
            vm.reportOpen -> vm.reportOpen = false
            vm.coverHunt != null -> vm.dropCoverHunt()
            vm.videoHunt != null -> vm.dropVideoHunt()
            menuFor != null -> menuFor = null
            appsOpen -> appsOpen = false
            searchOpen -> searchOpen = false
            settingsOpen -> settingsOpen = false
            // Solo llega aqui si el cuaderno se quedo sin foco: con foco, B lo gestiona el
            // mismo nivel a nivel. Sin esta linea cambiaba la pantalla de debajo y el cuaderno
            // seguia abierto encima, sin forma de cerrarlo.
            statsOpen -> statsOpen = false
            else -> screen = Screen.Systems
        }
    }
    // Siendo la app de inicio, B en la lista de consolas no lleva a ninguna parte: esto ES el
    // sitio al que se vuelve. Dejandola pasar, Android mandaba la casa al fondo y la volvia a
    // traer, que se ve como un parpadeo y cuesta la entrada otra vez si el proceso se fue.
    BackHandler(enabled = HomeApp.held && !anyModal && screen is Screen.Systems) {}

    // Orden de consolas, para que los gatillos sepan cuál es la anterior y la siguiente.
    // Con las ocultas tambien: sin ellas en la clave, esconder una consola la dejaba en el
    // salto de los hombros hasta el siguiente repaso de la biblioteca.
    val systemOrder = remember(vm.result, vm.androidGames, vm.prefs.hiddenSystems, vm.prefs.favoriteRevision, vm.played, vm.prefs.logbook) {
        vm.libraryGroups().map { it.first }
    }
    fun jumpSystem(from: String, step: Int) {
        if (systemOrder.isEmpty()) return
        val i = systemOrder.indexOf(from)
        if (i < 0) return
        val next = ((i + step) % systemOrder.size + systemOrder.size) % systemOrder.size
        screen = Screen.Games(systemOrder[next])
    }

    // Los gatillos se resuelven aquí y en ningún otro sitio. L+R a la vez abre las apps;
    // L o R sueltos cambian de consola. Para distinguirlo hay que ver ambas teclas al
    // mismo tiempo, y `combo` recuerda que la pareja ya se usó para que al soltar no se
    // dispare además el salto de consola.
    val held = remember { mutableStateSetOf<Key>() }
    var combo by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(MenuGround)
            .onKeyEvent { e ->
                val shoulder = e.key in SHOULDER_KEYS
                when {
                    // Los gatillos, antes que nada: los dos a la vez abren el cuaderno. Se
                    // atienden siempre, tambien sueltos, para que no caigan en el manejador
                    // de hombros y acaben cambiando de consola.
                    e.key in TRIGGER_KEYS -> {
                        if (e.type == KeyEventType.KeyDown) held += e.key else held -= e.key
                        if (held.containsAll(TRIGGER_KEYS) && !anyModal && vm.prefs.logbook) statsOpen = true
                        true
                    }
                    e.type == KeyEventType.KeyDown && shoulder -> {
                        held += e.key
                        if (held.any { it in LEFT_SHOULDER } && held.any { it in RIGHT_SHOULDER }) {
                            combo = true
                            if (!anyModal) appsOpen = true
                        }
                        true
                    }
                    e.type == KeyEventType.KeyUp && shoulder -> {
                        held -= e.key
                        if (held.none { it in SHOULDER_KEYS }) {
                            val wasCombo = combo
                            combo = false
                            if (!wasCombo && !anyModal) {
                                (screen as? Screen.Games)?.let {
                                    jumpSystem(it.systemId, if (e.key in LEFT_SHOULDER) -1 else +1)
                                }
                            }
                        }
                        true
                    }
                    // Select abre y cierra los ajustes desde cualquier sitio: llegar al
                    // engranaje de la esquina con la cruceta sería un viaje largo.
                    // Y la bajada tambien se come, como con Start: sin ella Android la da por
                    // no atendida y manda su equivalente —Select es MENU en el mapa generico—.
                    e.key in settingsKeys(vm.prefs) -> {
                        if (e.type == KeyEventType.KeyUp) {
                            if (!anyModal) settingsOpen = true
                            else { settingsOpen = false; appsOpen = false; menuFor = null }
                        }
                        true
                    }
                    // Start abre el menú de lo que esté seleccionado, que es el equivalente
                    // con mando a mantener pulsado. Es el boton del atajo, que se cambia en
                    // Ajustes → Interface → Shortcuts; de fabrica, Start.
                    // Y se consume SIEMPRE, tambien cuando no hay menu que abrir y tambien
                    // al pulsar. Sin eso, un Start que nadie atiende se lo queda Android y lo
                    // convierte en el equivalente de la cruceta —asi esta escrito en el mapa
                    // de teclas generico— con lo que sobre el cuaderno, en vez de no hacer
                    // nada, lo abria. Un atajo que no aplica tiene que no hacer nada, no
                    // hacer otra cosa.
                    // Buscar, con su atajo (Y de fabrica). Comida tambien al pulsar, como Start: sin
                    // ella Android la da por no atendida y en algunos mandos manda Atras.
                    e.key in searchKeys(vm.prefs) && !anyModal -> {
                        if (e.type == KeyEventType.KeyUp) searchOpen = true
                        true
                    }
                    e.key in quickMenuKeys(vm.prefs) && !anyModal -> {
                        if (e.type == KeyEventType.KeyUp) menuFor = when (screen) {
                            is Screen.Systems -> menuTarget(currentSystem)
                            is Screen.Games -> currentGame
                        }
                        true
                    }
                    else -> {
                        // Deja rastro de lo que llega sin usar: cada aparato reporta Start
                        // y Select con un código distinto, y no hay forma de saber cuál sin
                        // verlo. `adb logcat -s Ludolog-pad` lo enseña.
                        if (e.type == KeyEventType.KeyUp) {
                            android.util.Log.d(
                                "Ludolog-pad",
                                "tecla sin usar: ${e.key}  code=${e.nativeKeyEvent.keyCode}",
                            )
                        }
                        false
                    }
                }
            }
    ) {
        // La sala va debajo de todo.
        //
        // Se desenfoca mientras se eligen consolas y se aclara al entrar en una: el nivel en
        // el que estás queda dicho con el foco, sin necesitar ni una palabra. Y la televisión
        // de la escena se sintoniza con lo que haya seleccionado, o se queda en estática.
        // Sin giros, si el tema lo pide. Se lee aqui y no dentro de los `remember`, que no son
        // composables y no ven los locales.
        val noSpins = !LocalTheme.current.spins
        // Y el fondo plano, para los temas que no tienen sala. Se mira una vez por tema.
        val themeId = LocalTheme.current.id
        // Al cambiar de tema se vuelven a leer las salas Y los sonidos: los dos salen de la
        // carpeta del tema puesto, y un tema es una carpeta entera.
        //
        // Por la carpeta y no por el aspecto: las dos luces del Gallery son el mismo tema y
        // comparten ficheros, asi que el interruptor de claro y oscuro no tiene por que soltar
        // los reproductores.
        //
        // Y con guarda, no solo por ahorrar trabajo: la clave de un LaunchedEffect salta tambien
        // la primera vez, y rethemear ahi soltaria el ambiente recien arrancado.
        val themeFolder = LocalTheme.current.chosenId
        var lastTheme by remember { mutableStateOf(themeFolder) }
        LaunchedEffect(themeFolder) {
            if (lastTheme == themeFolder) return@LaunchedEffect
            lastTheme = themeFolder
            vm.reloadRooms()
            Sfx.retheme(ctx)
        }
        val backdrop = remember(themeId, granted) { if (granted) ThemeFiles.backdrop(ctx) else null }
        // Se resuelve una vez por cambio y no en cada recomposicion: elegir sala mira si sus
        // ficheros estan en disco, y esto se recompone con cada movimiento del cursor.
        val scene = remember(vm.tvSkins, vm.prefs.logbook, LinkSaveCheck.present.value, LocalTheme.current.room) { vm.scene }
        // Una cosa o la otra, nunca las dos: la sala YA es un fondo.
        if (scene == null && backdrop != null) Backdrop(backdrop)
        if (granted && scene != null) {
            val img = remember(scene.id) { scene.imageFile() }
            val glow = remember(scene.id) { scene.glowFile() }
            val embers = remember(scene.id) { scene.embersFile() }
            val led = remember(scene.id) { scene.ledFile() }
            if (img != null) {
                // Con una ventana encima (ajustes, el menu de un juego), sin video ni sonido, como en
                // los temas de panel: la tele seguia descodificando y sonando debajo, para nadie.
                val playing = (screen as? Screen.Games)?.let { currentGame }?.takeIf { !anyModal }

                // El video del juego, con su espera y su sonido aparte: lo mismo que en el
                // panel de los temas sin sala. Ver `rememberGameplay`.
                val tuned = rememberGameplay(vm, playing)
                val clip = tuned.clip

                // El color con el que la televisión tiñe la habitación.
                //
                // Mientras no hay nada sintonizado es el de la nieve, blanco frío: la tele
                // alumbra el cuarto con ruido. Con un juego puesto lo marca el propio vídeo.
                var videoTint by remember { mutableStateOf(StaticTint) }
                val tint = if (clip == null) StaticTint else videoTint

                // Todo lo de la sala dentro de su marco: a escala entera y sin deformar en
                // cualquier proporcion de pantalla. Ver RoomFrame.
                // Lo que se centra en el hueco de la lista si la pantalla es estrecha: la tele.
                val tvAt = scene.corners.takeIf { it.size == 8 }?.let { (it[0] + it[2] + it[4] + it[6]) / 4f }
                RoomFrame(img, focus = tvAt) {
                    SceneBackground(
                        file = img,
                        glow = glow,
                        embers = embers,
                        tint = tint,
                        blurred = screen is Screen.Systems,
                        // Sin video sintonizado lo que hay en el tubo es una caratula o nieve,
                        // y entonces la luz del cuarto tiembla por su cuenta.
                        still = clip == null,
                        led = led,
                        ledOn = LinkSaveCheck.on.value,
                    )
                    SceneScreen(
                        skin = scene,
                        channel = playing?.path,
                        video = clip,
                        still = playing?.let { g -> vm.art?.find(g) },
                        params = LocalTheme.current.crt,
                        blurred = screen is Screen.Systems,
                        onTint = { videoTint = it },
                        // La tele avisa mientras prepara este canal. Solo cuando de verdad
                        // esta trabajando en ESTE, no en otro que quedara en la cola.
                        loading = tuned.pending?.let { TapeQueue.working == it.name } == true,
                        progress = TapeQueue.progress,
                    )
                }

                    // Y la consola, encima de todo.
                    //
                    // Antes vivía en un panel a la derecha que la sala dejó sin sentido, así que
                    // llevaba un rato renderizada sin que se viera. Va aquí y no dentro del menú
                    // porque el menú se dibuja dentro de una columna con su barra superior, y la
                    // consola tiene que poder ponerse donde haya hueco en la habitación.
                    val sysId = (screen as? Screen.Systems)?.let { currentSystem }
                    val sys = sysId?.let { vm.catalog?.byId?.get(it) }
                    // La consola de Android no tiene giro propio: enseña el teléfono, que es el
                    // mismo que gira en el cajón de apps.
                    val linkOn = LinkSaveCheck.on.value
                    val spin = remember(sysId, sys?.id, noSpins, themeId, sysId?.let(RenderChoices::of), linkOn) {
                        if (noSpins) null
                        else if (sysId == RECKONING_SYSTEM) CompanionArt.spin()
                        else if (sysId == LINK_SYSTEM) LinkArt.spin(linkOn)
                        else if (sysId == ANDROID_SYSTEM) SystemArt.video(ANDROID_SYSTEM, sys?.video.orEmpty())
                            ?: AppArt.phone()
                        // Favoritos no es una consola del catalogo: su giro, la estrella, va por su id.
                        else if (sysId == FAVORITES_SYSTEM) SystemArt.video(FAVORITES_SYSTEM)
                        else sys?.let { SystemArt.video(it.id, it.video) }
                    }
                    // La consola y su texto, junto a la lista y no en la sala: ver BesideListFrame.
                    BesideListFrame(scene) { placed ->
                        SceneConsole(
                            skin = placed,
                            video = spin,
                            visible = screen is Screen.Systems,
                        )
                    }
                    // Y el dialogo con lo que se cuenta de lo elegido, escrito a maquina. Es
                    // lo que antes iba al pie del panel; con la sala, el panel se queda para
                    // la lista y esto se lee debajo de la consola.
                    val bookName = COMPANION_NAME
                    val details = remember(
                        screen, currentSystem, currentGame,
                        vm.result, vm.androidGames, vm.art, vm.prefs.labelRevision, bookName,
                        vm.played, companion, vm.chargeUah != null, vm.cardStory,
                        LinkSaveCheck.on.value, LinkSaveCheck.address.value, LinkSaveCheck.peers.intValue,
                    ) {
                        when (screen) {
                            is Screen.Systems ->
                                if (currentSystem == RECKONING_SYSTEM) reckoningDetails(vm, bookName)
                                else if (currentSystem == LINK_SYSTEM) linkDetails()
                                else currentSystem?.let { vm.systemCard(it) }
                            is Screen.Games -> currentGame?.let { g ->
                                val card = vm.gameCard(g)
                                vm.cardStory?.let { (text, from) -> card.copy(text = text, footer = from) } ?: card
                            }
                        }
                    }
                    BesideListFrame(scene) { placed -> SceneCaption(skin = placed, details = details) }
            }
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(vm, onSettings = { settingsOpen = true }, onSearch = { if (!anyModal) searchOpen = true })
            // Con la lista en la pantalla de abajo, algo de arriba tiene que quedarse el foco: los
            // acordes (L+R, L2+R2) los lee la raiz, y solo le llegan con el foco dentro de ella.
            if (dual != null) {
                val anchor = rememberFocusRequester()
                AutoFocus(anchor, enabled = !anyModal)
                FocusAnchor(anchor)
            }

            if (!granted) {
                PermissionGate { requestStorage(ctx); granted = hasStorage() }
                return@Column
            }

            Box(Modifier.weight(1f)) {
                when (val s = screen) {
                    is Screen.Systems -> SystemsMenu(
                        vm,
                        initial = currentSystem,
                        onOpen = { screen = Screen.Games(it) },
                        onReckoning = { statsOpen = true },
                        refocus = anyModal to dual?.displayId,
                        enabled = !anyModal,
                        onSelectionChange = { currentSystem = it },
                        onLongPress = { sys -> menuTarget(sys)?.let { menuFor = it } },
                    )
                    // Con la pantalla de clave: una busqueda que lleva a la misma consola en que se
                    // estaba empieza en el juego elegido y no donde estaba el cursor.
                    is Screen.Games -> androidx.compose.runtime.key(s) {
                        GamesMenu(
                            vm, s.systemId,
                            onBack = { screen = Screen.Systems },
                            refocus = anyModal to dual?.displayId,
                            enabled = !anyModal,
                            onPlay = { g -> toast = vm.play(ctx, g) },
                            onSelectionChange = { currentGame = it },
                            onLongPress = { g -> menuFor = g },
                            focus = s.focus,
                        )
                    }
                }
            }

            toast?.let {
                Text(
                    it, color = Color(0xFFE0805F), fontSize = 12.sp,
                    fontFamily = MenuBody,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
            // Mientras Ludolog Link mira si hay una partida mas nueva en otro device.
            if (vm.saveCheck?.answer == null && vm.saveCheck != null) Text(
                "Checking saves with Ludolog Link…", color = MenuDim, fontSize = 12.sp, fontFamily = MenuBody,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        if (granted) {
            // El informe y las ventanas de elegir caratula o video van ENCIMA de la ventana que
            // hubiera, sin quitarla. Salen de un menu o de los ajustes, y al cerrarse se vuelve
            // alli tal como estaba —su fila, sus valores ya puestos al dia—: es lo que deja
            // buscar arte sin salir del menu del juego. Quitandola, al volver se abria de nuevo
            // desde la primera fila, y los ajustes desde su primera pestaña.
            val overlay = showReport || vm.coverHunt != null || vm.videoHunt != null
            CompositionLocalProvider(LocalPadEnabled provides !overlay) {
                when {
                    menuFor is String -> SystemContextMenu(
                        vm, menuFor as String,
                        onClose = { menuFor = null },
                        onToast = { toast = it },
                    )
                    menuFor is Game -> GameContextMenu(
                        vm, menuFor as Game,
                        onClose = { menuFor = null },
                        onToast = { toast = it },
                    )
                    appsOpen -> AppsWindow(
                        vm,
                        onClose = { appsOpen = false },
                        onToast = { toast = it },
                    )
                    statsOpen -> StatsWindow(vm) { statsOpen = false }
                    searchOpen -> SearchWindow(
                        vm,
                        onPick = { g ->
                            searchOpen = false
                            // Al volver con B, la lista de consolas queda en la suya.
                            currentSystem = g.systemId
                            screen = Screen.Games(g.systemId, focus = g.path)
                        },
                    ) { searchOpen = false }
                    settingsOpen -> SettingsWindow(vm, onToast = { toast = it }) { settingsOpen = false }
                }
            }
            when {
                // El informe el primero: es lo ultimo que ha pasado y lo que se esta esperando.
                showReport -> ScrapeReportWindow(vm.report!!) { vm.reportOpen = false }
                vm.coverHunt != null -> CoverPickWindow(vm.coverHunt!!, vm::pickCover, vm::dropCoverHunt)
                vm.videoHunt != null -> VideoPickWindow(vm.videoHunt!!, vm::pickVideo, vm::dropVideoHunt)
                // El aviso de Ludolog Link antes de jugar (ver LinkSaveCheck).
                vm.saveCheck?.answer != null -> SaveCheckWindow(vm)
            }
        }

        // Lo que trajo la ultima partida: nivel, logros, misiones. Encima de todo, pero debajo del
        // barrido, que es la pantalla.
        vm.celebration?.let { c -> CelebrationCard(c) { vm.celebration = null } }

        // Y el barrido encima de todo, si el tema es de fosforo.
        //
        // Lo ultimo del arbol a proposito: las lineas tienen que cortar tambien las ventanas,
        // el cuaderno y la barra de estado, porque no son un adorno de cada caja sino la
        // pantalla. Puestas elemento a elemento nunca cuadran —cada caja empieza su ciclo
        // donde empieza la caja, no donde empieza la pantalla— y se nota en cuanto dos se
        // tocan.
        if (LocalTheme.current.phosphor) ScanlineOverlay()
    }
}

/* ------------------------------------------------------------------------- barra superior */

@Composable
internal fun PermissionGate(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("STORAGE PERMISSION REQUIRED", color = MenuInk, fontSize = 14.sp,
             fontFamily = MenuBody, letterSpacing = 2.sp)
        Spacer(Modifier.height(16.dp))
        val requester = remember { FocusRequester() }
        AutoFocus(requester)
        Box(
            Modifier.focusRequester(requester).padItem(onActivate = onGrant)
                .clickable { onGrant() }.padding(horizontal = 22.dp, vertical = 10.dp)
        ) {
            Text("GRANT", color = MenuInk, fontSize = 15.sp,
                 fontFamily = MenuBody, letterSpacing = 2.sp)
        }
    }
}

internal fun hasStorage(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

internal fun requestStorage(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    ctx.startActivity(
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/* ------------------------------------------------------------------ lo que se cuenta */

/**
 * Lo que se cuenta de lo seleccionado.
 *
 * Va al pie del panel cuando no hay sala, y al dialogo de la sala cuando la hay. Sale de un
 * solo sitio para que los dos digan lo mismo: si la descripcion de una consola cambia, no
 * hay una segunda copia que se quede vieja.
 */
internal data class Details(val title: String, val text: String, val footer: String)

/**
 * El aviso de Ludolog Link antes de abrir un juego: su partida mas nueva esta en otro device que no
 * contesta, o cambio en los dos. Lo seguro (no jugar todavia) es lo destacado. Ver LinkSaveCheck.
 */
@Composable
internal fun SaveCheckWindow(vm: LibraryViewModel) {
    val c = vm.saveCheck ?: return
    val a = c.answer ?: return
    val ctx = LocalContext.current
    val clock = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US) }
    val wait = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { wait.requestFocus() } }
    val peer = a.peer ?: "another device"
    val conflict = a.result == "conflict"
    val off = a.result == "off"
    val played = if (a.playedAt > 0) " (${clock.format(java.util.Date(a.playedAt))})" else ""
    ModalWindow(onDismiss = { vm.answerSaveCheck(ctx, play = false) }, widthFraction = 0.56f, heightFraction = 0.5f) {
        WindowFrame(
            title = if (conflict) "Saves to choose" else if (off) "Sharing is off" else "Newer save elsewhere",
            subtitle = c.game.title,
            description = if (conflict)
                "Changed here and on $peer. Choose in Ludolog Link, or play with this one."
            else if (off)
                "Last played on $peer$played. Sharing is off, so that save isn't here."
            else
                "Last played on $peer$played. $peer isn't reachable, so that save isn't here.",
            hint = "B cancel",
        ) {
            Row(Modifier.padding(top = 12.dp)) {
                if (off) PadButton("TURN ON SHARING", { vm.syncOnThenStart(ctx) }, primary = true, focusRequester = wait)
                else PadButton("WAIT", { vm.answerSaveCheck(ctx, play = false) }, primary = true, focusRequester = wait)
                Spacer(Modifier.width(12.dp))
                if (conflict) {
                    PadButton("OPEN LINK", { vm.answerSaveCheck(ctx, play = false); LinkSaveCheck.openLink(ctx) })
                    Spacer(Modifier.width(12.dp))
                }
                PadButton("PLAY ANYWAY", { vm.answerSaveCheck(ctx, play = true) })
            }
        }
    }
}

/** Sin tocar nada durante esto, el ambiente se calla: ver Sfx.rest. */
private const val AMBIENCE_REST_MS = 3 * 60_000L

/**
 * Ludolog Dev: un «DEV» rojo arriba en el centro, encima de todo y en todos los temas, para no
 * confundirla nunca con la oficial que esta al lado (pedido del usuario, 08-10-2026). No se come
 * toques ni teclas: es solo un rotulo.
 */
@androidx.compose.runtime.Composable
private fun DevBadge() {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        androidx.compose.material3.Text(
            "DEV",
            color = androidx.compose.ui.graphics.Color(0xFFFF3B30),
            fontSize = 11.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            letterSpacing = 2.sp,
            // Pegado al borde de arriba: mas abajo tapaba las pistas de la cabecera del Companion.
            modifier = androidx.compose.ui.Modifier.padding(top = 2.dp)
                .background(androidx.compose.ui.graphics.Color(0xD9000000), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}
