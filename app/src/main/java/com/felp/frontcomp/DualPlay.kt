package com.felp.frontcomp

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

/**
 * Jugar con dos pantallas (10-10-2026, pedido del usuario).
 *
 * Sin esto, al lanzar un juego la otra pantalla se quedaba sin nada de Ludolog y Android copiaba
 * en ella la de la consola: el juego salia dos veces. Ahora el juego va a la pantalla de la imagen
 * —la de la consola, o la otra con las pantallas cambiadas— y Ludolog a la otra, donde se puede
 * seguir eligiendo y abrir otras apps (un navegador, un video) mientras el juego sigue en la suya.
 *
 * Cuatro piezas:
 * - Debajo del juego, en su pantalla, [GameBaseActivity]: transparente, y cuando el juego se cierra
 *   queda delante. Eso es volver, y cierra la partida como lo hace la principal sin dos pantallas.
 *   Antes la principal se mudaba a esa pantalla, y mudarla a la vez que se abria el juego dejaba el
 *   juego escondido (probado en la Odin: el monitor en negro).
 * - En la otra pantalla, [SideActivity]: Ludolog otra vez, con el mismo modelo, quieta y callada.
 * - El triple toque en una pantalla tactil pasa el mando a la otra pantalla ([Taps]). Tocar una
 *   pantalla ya le da el mando, eso lo hace Android; el triple toque es para cuando la otra no es
 *   tactil, como un monitor.
 *
 * El juego no se pausa solo al perder el mando: eso lo decide cada emulador.
 */
internal object DualPlay {

    /** Donde esta el juego y donde Ludolog. */
    class Screens(val game: Int, val list: Int)

    /** Cuando se lanzo el ultimo juego (elapsedRealtime): ver GameBaseActivity. */
    @Volatile var launchedAt = 0L

    /** La pantalla que tiene el mando ahora, segun lo ultimo que se sabe: ver [Taps]. */
    @Volatile var focused = Display.DEFAULT_DISPLAY

    /** El modelo de la principal, para que la segunda ensene lo mismo. Lo pone la raiz. */
    @Volatile var vm: LibraryViewModel? = null

    /** La segunda, y la de debajo del juego, mientras existen. */
    var side: WeakReference<SideActivity>? = null
    var base: WeakReference<GameBaseActivity>? = null

    /**
     * Lanza el juego: con dos pantallas en uso, en la de la imagen y con Ludolog en la otra; si no,
     * como siempre.
     */
    fun launchGame(ctx: Context, intent: Intent) {
        val other = DualScreen.available.value
        val prefs = Prefs(ctx)
        val now = DualScreen.playing.value
        val p = now ?: if (other != null && prefs.secondScreen) {
            if (prefs.swapScreens) Screens(other.displayId, Display.DEFAULT_DISPLAY)
            else Screens(Display.DEFAULT_DISPLAY, other.displayId)
        } else null
        if (p == null) {
            ctx.startActivity(intent)
            return
        }
        if (now == null) {
            DualScreen.playing.value = p
            DualScreen.refresh(ctx)
            // Ludolog en la otra pantalla, primero: lo ultimo que se abre se queda el mando, y ese
            // tiene que ser el juego.
            ctx.startActivity(Intent(ctx, SideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), at(p.list))
            // Y la de debajo del juego, en la suya.
            ctx.startActivity(Intent(ctx, GameBaseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), at(p.game))
            Taps.show(ctx.applicationContext, p)
        } else {
            // Otro juego elegido en la segunda con uno ya abierto: el de antes termina ahora.
            SessionTracker.backAt = System.currentTimeMillis()
        }
        launchedAt = SystemClock.elapsedRealtime()
        ctx.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), at(p.game))
        focused = p.game
    }

    /**
     * Se acabo el juego: se cierran la segunda y la de debajo, y la otra pantalla vuelve a ser la
     * lista (o la sala).
     */
    fun stop(ctx: Context) {
        if (DualScreen.playing.value == null) return
        DualScreen.playing.value = null
        Taps.hide()
        side?.get()?.finish()
        side = null
        base?.get()?.finish()
        base = null
        DualScreen.refresh(ctx)
    }

    /**
     * El mando a la pantalla [display], sin tapar lo que haya en ella: se abre alli una ventana que
     * no se ve y se cierra en el acto, y lo de debajo —el juego, la segunda de Ludolog, el
     * navegador que se abrio desde ella— se queda con el mando.
     */
    fun focus(ctx: Context, display: Int) {
        focused = display
        runCatching {
            ctx.startActivity(
                Intent(ctx, FocusHopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                at(display),
            )
        }.onFailure { android.util.Log.w("Ludolog", "pasar el mando: ${it.message}") }
    }

    /** La ventana de la tarjeta de la partida, en la pantalla del juego. */
    fun overlayContext(app: Context): Context {
        val game = DualScreen.playing.value?.game ?: return app
        val d = app.getSystemService(DisplayManager::class.java)?.getDisplay(game) ?: return app
        return app.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    /**
     * Si hay que esperar antes de lanzar otro juego: con uno arrancando en la otra pantalla, el aviso;
     * si no, nulo.
     *
     * Con dos pantallas se puede elegir otro juego mientras se juega, y con RetroArch eso es darle
     * un juego nuevo estando abierto: la 1.22.2 se cierra sola (ver Bounce) y, si le llega mientras
     * aun carga el anterior, se cae en ese cierre (probado en la Odin: «Scudo ERROR: misaligned
     * pointer», tres veces seguidas cambiando rapido). Unos segundos entre uno y otro lo evitan.
     */
    fun busy(): String? {
        if (DualScreen.playing.value == null) return null
        return if (SystemClock.elapsedRealtime() - launchedAt < BUSY_MS) "Wait a moment: the last game is still starting." else null
    }

    private const val BUSY_MS = 3_000L

    private fun at(display: Int): Bundle = ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
}

/**
 * El triple toque: tres toques seguidos en una pantalla tactil pasan el mando a la otra.
 *
 * Se cuentan con una ventana de un pixel en cada pantalla que mira los toques de fuera de ella
 * (FLAG_WATCH_OUTSIDE_TOUCH): Android le avisa de cada toque que empieza en esa pantalla, sin donde
 * y sin quitarselo a quien lo recibe. Asi el juego sigue recibiendo sus toques; los tres del gesto
 * tambien le llegan.
 *
 * A donde va el mando: a la pantalla que NO lo tenia al empezar el gesto. Tocar una pantalla ya se
 * lo da (Android), asi que lo que tenia antes del primer toque es lo que cuenta.
 */
internal object Taps {
    private val shown = mutableListOf<View>()

    fun show(app: Context, p: DualPlay.Screens) {
        hide()
        if (!Settings.canDrawOverlays(app)) return
        val dm = app.getSystemService(DisplayManager::class.java) ?: return
        for (id in listOf(p.game, p.list)) {
            val d = dm.getDisplay(id) ?: continue
            runCatching {
                val c = app.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                val v = Watcher(c, id, p)
                c.getSystemService(WindowManager::class.java).addView(v, params())
                shown += v
            }.onFailure { android.util.Log.w("Ludolog", "triple toque: ${it.message}") }
        }
    }

    fun hide() {
        for (v in shown) runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        shown.clear()
    }

    private class Watcher(c: Context, private val display: Int, private val p: DualPlay.Screens) : View(c) {
        private val times = LongArray(TAPS)
        private var count = 0
        /** Quien tenia el mando al primer toque del gesto. */
        private var before = display

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val a = e.actionMasked
            if (a != MotionEvent.ACTION_OUTSIDE && a != MotionEvent.ACTION_DOWN) return false
            val t = SystemClock.uptimeMillis()
            if (count > 0 && t - times[count - 1] > GAP_MS) count = 0
            if (count == 0) before = DualPlay.focused
            times[count++] = t
            // Un toque en esta pantalla ya le da el mando.
            DualPlay.focused = display
            if (count == TAPS) {
                count = 0
                if (times[TAPS - 1] - times[0] <= SPAN_MS) {
                    val to = if (before == p.game) p.list else p.game
                    DualPlay.focus(context.applicationContext, to)
                }
            }
            return false
        }
    }

    private fun params() = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    private const val TAPS = 3
    /** Entre un toque y el siguiente, como mucho. */
    private const val GAP_MS = 350L
    /** Y los tres, como mucho. */
    private const val SPAN_MS = 900L
}

/**
 * Ludolog en la otra pantalla mientras se juega: ver DualPlay. La misma raiz con el mismo modelo,
 * pero sin lo que hace la principal al arrancar ni al volver, quieta (nada se mueve ni suena: el
 * juego esta en la otra) y sin salirse con Atras, que dejaria esa pantalla copiando la del juego.
 */
class SideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = DualPlay.vm
        if (vm == null || DualScreen.playing.value == null) { finish(); return }
        DualPlay.side = WeakReference(this)
        immersive()
        onBackPressedDispatcher.addCallback(this) { }
        setContent {
            AppTheme {
                Root(vm, side = true)
                if (BuildConfig.DEV) DevBadge()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Quieta: sin videos ni nada que se mueva solo mientras hay un juego en la otra.
        Motion.saver.value = true
        Motion.away.value = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { immersive(); display?.displayId?.let { DualPlay.focused = it } }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        Triggers.read(event)
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (DualPlay.side?.get() === this) DualPlay.side = null
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * Debajo del juego, en su pantalla: ver DualPlay. Cuando vuelve a estar delante es que el juego se
 * cerro, y hace lo que la principal hace al volver de un juego (MainActivity.onResume): el
 * RetroArch que se cierra solo al recibir el juego se vuelve a lanzar una vez, y si no, la partida
 * termina a los pocos segundos, si no vuelve a taparla nada.
 */
class GameBaseActivity : Activity() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Si ya estuvo delante alguna vez: ver onResume. */
    private var resumedBefore = false
    private val back = Runnable {
        SessionTracker.returned(this, SessionTracker.backAt)
        DualPlay.stop(this)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DualScreen.playing.value == null) { finish(); return }
        DualPlay.base = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        // Delante justo al crearse, antes de que el juego la tape, no es volver. No se puede saber
        // por si ya la tapo: Android no la crea mientras el juego esta encima, solo al volver a
        // verse (probado en la Odin), asi que se mira cuanto hace que se lanzo. Solo esa primera
        // vez: despues, estar delante es siempre que el juego se fue, aunque sea enseguida —el
        // RetroArch que se cierra al recibir otro juego lo hace en menos de eso, y sin el
        // reintento la pantalla se quedaba con esta encima—.
        val first = !resumedBefore
        resumedBefore = true
        if (first && SystemClock.elapsedRealtime() - DualPlay.launchedAt < SETTLE_MS) return
        Bounce.take()?.let { again -> if (runCatching { startActivity(again) }.isSuccess) return }
        SessionTracker.backAt = System.currentTimeMillis()
        main.removeCallbacks(back)
        main.postDelayed(back, RETURN_CONFIRM_MS)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(back)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(back)
        if (DualPlay.base?.get() === this) DualPlay.base = null
    }

    private companion object {
        /** Lo que tarda el juego en taparla, con margen. */
        const val SETTLE_MS = 400L
    }
}

/** Ver DualPlay.focus: se abre y se cierra, y la pantalla en que se abrio se queda el mando. */
class FocusHopActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
