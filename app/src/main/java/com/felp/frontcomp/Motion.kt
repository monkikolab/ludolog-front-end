package com.felp.frontcomp

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * El ritmo de lo que se mueve solo, y el reposo.
 *
 * La pantalla de algunas consolas va a 120 Hz y no tiene otro modo, y una animacion de Compose pide un
 * fotograma en CADA refresco: quieta en la lista de consolas, Ludolog dibujaba unos 80 por
 * segundo por un latido del cursor que apenas se ve. Android no dibuja si nada cambia, asi que
 * lo que se mueve solo avanza aqui a [fps] fotogramas —30 de serie, o 60 en los ajustes— y en
 * los refrescos de en medio la pantalla repite la imagen sin gastar.
 *
 * Medido en una consola de pruebas: 24 y 30 gastan lo mismo, dentro del ruido, y 30
 * se ve mas suave, asi que 24 no se ofrece. 60 cuesta unos 0,2 W mas.
 * Moverse por las listas, tocar y el video no pasan por aqui: van a lo que de la pantalla.
 *
 * Y en reposo, nada: tras [IDLE_MS] sin tocar ningun boton ni la pantalla, o con Ludolog detras
 * de otra cosa, los relojes se paran y lo que late se queda encendido y quieto. La primera tecla
 * lo despierta. Ver MainActivity, que es quien avisa de cada entrada y de irse y volver.
 */
internal object Motion {
    /** Los ritmos que se pueden elegir en los ajustes (Interface → Animation). */
    val CHOICES = listOf(30, 60)
    const val DEFAULT_FPS = 30

    /** El elegido. Lo pone MainActivity desde Prefs.animationFps. */
    val fps = androidx.compose.runtime.mutableIntStateOf(DEFAULT_FPS)

    /** Sin tocar nada durante esto, reposo. */
    const val IDLE_MS = 15_000L

    /** Sin entrada desde hace [IDLE_MS]: lo que late se queda quieto. */
    val idle = mutableStateOf(false)

    /** Ludolog no esta delante (detras del emulador, pantalla apagada): no se mueve nada. */
    val away = mutableStateOf(false)

    /**
     * Ahorro de bateria, de los ajustes: como el reposo, pero siempre. Nada se mueve solo y no
     * se reproduce video en ningun tema. Lo pone MainActivity desde Prefs.batterySaver.
     */
    val saver = mutableStateOf(false)

    /** Si lo que se mueve solo tiene que estar quieto ahora mismo. */
    val quiet: Boolean get() = idle.value || saver.value

    /** Hubo una tecla o un toque. */
    fun input() {
        if (idle.value) idle.value = false
    }
}

/**
 * Un reloj en segundos que avanza a [fps] mientras [running], y se para con Ludolog fuera de la
 * vista. Parado conserva lo que marcaba y sigue desde ahi, sin saltos. [key] lo pone a cero.
 *
 * Se lee en el dibujado, no en la composicion: asi cada paso vuelve a pintar y no recompone.
 */
@Composable
internal fun rememberMotionClock(key: Any? = Unit, running: Boolean = true, fps: Int? = null): FloatState {
    val clock = remember(key) { mutableFloatStateOf(0f) }
    val away by Motion.away
    val saver by Motion.saver
    val chosen by Motion.fps
    val fps = fps ?: chosen
    LaunchedEffect(key, running, away, saver, fps) {
        if (!running || away || saver) return@LaunchedEffect
        val start = SystemClock.uptimeMillis() - (clock.floatValue * 1000f).toLong()
        val step = 1000L / fps.coerceIn(1, 60)
        while (true) {
            clock.floatValue = (SystemClock.uptimeMillis() - start) / 1000f
            delay(step)
        }
    }
    return clock
}

/**
 * Lo que late: de [low] a 1 y vuelta, en [periodMs]. En reposo o fuera de la vista, quieto en 1,
 * que es lo encendido: un cursor parado tiene que seguir diciendo donde se esta.
 */
@Composable
internal fun rememberPulse(low: Float, periodMs: Int = 1900): State<Float> {
    val idle by Motion.idle
    val clock = rememberMotionClock(running = !idle)
    return remember(low, periodMs) {
        derivedStateOf {
            if (Motion.idle.value || Motion.away.value || Motion.saver.value) 1f
            else {
                // Coseno en vez de la curva de antes (FastOutSlowIn de ida y vuelta): se le
                // parece, sin esquinas en los extremos, y se calcula del reloj sin estado.
                val phase = (clock.floatValue * 1000f / periodMs) % 1f
                low + (1f - low) * (0.5f - 0.5f * kotlin.math.cos(phase * 2f * Math.PI.toFloat()))
            }
        }
    }
}
