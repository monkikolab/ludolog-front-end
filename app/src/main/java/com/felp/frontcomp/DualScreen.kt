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
 */
internal object DualScreen {

    /** La pantalla de abajo, si la hay y se usa; nulo si no. Estado de Compose. */
    val display = mutableStateOf<Display?>(null)

    /** La ventana que dibuja la lista abajo, mientras existe. */
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
        val second = if (!Prefs(ctx).secondScreen) null
            else dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (display.value?.displayId != second?.displayId) display.value = second
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
 */
@Composable
internal fun OnSecondDisplay(display: Display, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberCompositionContext()
    val current by rememberUpdatedState(content)
    val typeScale = LocalTheme.current.typeScale
    val scale by rememberUpdatedState(typeScale)
    DisposableEffect(display.displayId) {
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
        runCatching { p.show() }
        DualScreen.presentation = p
        onDispose {
            if (DualScreen.presentation === p) DualScreen.presentation = null
            runCatching { p.dismiss() }
        }
    }
}
