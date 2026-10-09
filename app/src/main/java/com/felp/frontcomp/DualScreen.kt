package com.felp.frontcomp

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * La segunda pantalla de una consola de dos pantallas: ahi va la lista, y la de
 * arriba se queda para la escena —la sala, la consola que gira, la caratula, el video—.
 *
 * Android ve la de abajo como otra pantalla, y una app solo puede dibujar en ella con una
 * `Presentation`: una ventana aparte en esa pantalla. La lista se compone dentro con el MISMO
 * estado que el resto (ver [OnSecondDisplay]), asi que lo elegido abajo es lo que gira arriba.
 *
 * Los botones del mando llegan a la ventana de arriba, que es la que tiene el foco del sistema.
 * MainActivity se los pasa a la de abajo mientras se esta en la lista ([routeToList]); los
 * hombros y gatillos se quedan arriba, donde se leen los acordes (L+R, L2+R2), y con una
 * ventana encima (ajustes, el menu de un juego) todo va arriba como siempre.
 *
 * Sin segunda pantalla —o con la opcion apagada— no pasa nada de esto, y si se desconecta, la
 * lista vuelve a la de arriba sin reiniciar.
 *
 * Con [swapped] es al reves: la lista se queda en la consola y la escena va a la otra pantalla
 * (10-10-2026). Para una tele o un monitor: la imagen en grande y se elige en la mano.
 *
 * La ventana de la otra pantalla es UNA, la de la raiz, y vive mientras haya otra pantalla. Dentro
 * va lo que manda el menu por [stage] —la lista, o su panel con las pantallas cambiadas— y, con
 * ellas cambiadas, la sala.
 *
 * Y con un juego abierto, cada pantalla lo suyo: ver [playing] y DualPlay.kt.
 */
internal object DualScreen {

    /** La pantalla de abajo, si la hay y se usa; nulo si no. Estado de Compose. */
    val display = mutableStateOf<Display?>(null)

    /**
     * La otra pantalla, si la hay, se use o no. Los ajustes de dos pantallas solo salen con ella
     * (pedido del usuario, 10-10-2026): sin otra pantalla no hay nada que elegir.
     */
    val available = mutableStateOf<Display?>(null)

    /**
     * Con un juego abierto y dos pantallas: en cual esta el juego y en cual Ludolog. Mientras dura
     * no hay ventana de Ludolog en la otra pantalla —la tiene el juego, o la segunda de Ludolog—, y
     * la principal se dibuja entera, como con una sola. Ver DualPlay.launchGame.
     */
    val playing = mutableStateOf<DualPlay.Screens?>(null)

    /** Si la lista se queda en esta pantalla y la escena va a la otra. Estado de Compose. */
    val swapped = mutableStateOf(false)

    /**
     * Lo que el menu manda a la otra pantalla: su lista, o con [swapped] su panel (la imagen y su
     * texto), que va encima de la sala. Lo pone el menu en cada composicion; [stageOwner] dice de
     * cual es, para que el que se va no borre el del que llega.
     */
    val stage = mutableStateOf<(@Composable () -> Unit)?>(null)
    var stageOwner: Any? = null

    /** La ventana que dibuja en la otra pantalla, mientras existe. */
    @Volatile var presentation: Presentation? = null

    /** Si los botones del mando van ahora a la lista de abajo. Lo pone la raiz. */
    @Volatile var routeToList = false

    private var watching = false

    /** Mira que pantallas hay, y vuelve a mirar si se conecta o se quita una. */
    fun watch(ctx: Context) {
        val app = ctx.applicationContext
        refresh(app)
        if (watching) return
        watching = true
        val dm = app.getSystemService(DisplayManager::class.java) ?: return
        dm.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = refresh(app)
            override fun onDisplayRemoved(id: Int) = refresh(app)
            override fun onDisplayChanged(id: Int) = Unit
        }, Handler(Looper.getMainLooper()))
    }

    fun refresh(ctx: Context) {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val prefs = Prefs(ctx)
        val other = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (available.value?.displayId != other?.displayId) available.value = other
        // Quitada a mitad de partida: se acaba el reparto, y Android trae lo que habia en ella.
        playing.value?.let { p ->
            if (other == null || other.displayId !in setOf(p.game, p.list)) DualPlay.stop(ctx)
        }
        val second = if (!prefs.secondScreen || playing.value != null) null else other
        if (display.value?.displayId != second?.displayId) display.value = second
        val swap = second != null && prefs.swapScreens
        if (swapped.value != swap) swapped.value = swap
    }

    /** Pasa una tecla a la lista de abajo; falso si no toca o si no la quiso. */
    fun forward(event: KeyEvent): Boolean {
        if (!routeToList || event.keyCode in KEEP_ON_TOP) return false
        val p = presentation?.takeIf { it.isShowing } ?: return false
        return p.window?.decorView?.dispatchKeyEvent(event) == true
    }

    /** Lo que se queda arriba: los acordes de hombros y gatillos, y el volumen. */
    private val KEEP_ON_TOP = setOf(
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    )
}

/**
 * Lo de [content], dibujado en otra pantalla, con el mismo estado que quien lo llama: se compone
 * colgado de su composicion, como un dialogo, asi que lee y cambia las mismas variables. Con la
 * letra del tema (su escala), y la densidad de esa pantalla.
 *
 * [generation]: si cambia, la ventana se hace de nuevo. Al quitar el intercambio, la lista volvia a
 * la ventana que tenia el panel y no recuperaba el foco: el mando no le llegaba (10-10-2026).
 */
@Composable
internal fun OnSecondDisplay(display: Display, generation: Any? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberCompositionContext()
    val current by rememberUpdatedState(content)
    val typeScale = LocalTheme.current.typeScale
    val scale by rememberUpdatedState(typeScale)
    DisposableEffect(display.displayId, generation) {
        val host = (ctx as? android.app.Activity)?.window?.decorView
        val p = Presentation(ctx, display)
        val view = ComposeView(p.context).apply {
            setParentCompositionContext(parent)
            setContent {
                val own = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(own.density, own.fontScale * scale)) {
                    current()
                }
            }
        }
        p.setContentView(view)
        // Lo que Compose busca en el arbol de vistas para vivir: el de la actividad.
        p.window?.decorView?.let { d ->
            host?.findViewTreeLifecycleOwner()?.let { d.setViewTreeLifecycleOwner(it) }
            host?.findViewTreeViewModelStoreOwner()?.let { d.setViewTreeViewModelStoreOwner(it) }
            host?.findViewTreeSavedStateRegistryOwner()?.let { d.setViewTreeSavedStateRegistryOwner(it) }
        }
        p.window?.let { w ->
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        // Solo mientras Ludolog se ve. Una Presentation no se va con su actividad: con un juego
        // lanzado, o con otra app delante, la otra pantalla seguia con la lista de Ludolog,
        // congelada, encima de lo que el juego quisiera poner alli (10-10-2026, en la Odin con un
        // monitor). Se esconde al dejar de verse y vuelve al volver; al añadir el observador
        // llega el estado de ahora, y con el la primera vez que se enseña.
        val lifecycle = (ctx as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { p.show() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { p.hide() }
                else -> Unit
            }
        }
        if (lifecycle != null) lifecycle.addObserver(watcher) else runCatching { p.show() }
        DualScreen.presentation = p
        onDispose {
            lifecycle?.removeObserver(watcher)
            if (DualScreen.presentation === p) DualScreen.presentation = null
            runCatching { p.dismiss() }
        }
    }
}
