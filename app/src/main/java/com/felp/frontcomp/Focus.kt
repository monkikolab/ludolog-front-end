package com.felp.frontcomp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gamepad navigation.
 *
 * On a handheld the D-pad is the normal way in and the finger is the exception, so focus
 * is not a nicety layered on top — it is the interaction model. Two consequences drive
 * everything here: there is no hover, so focus itself has to be the visible state and it
 * has to read from arm's length; and every reachable thing must sit on a predictable grid,
 * because directional movement can only go where the layout allows.
 */

/** Buttons that mean "activate". Android maps gamepad A to these before Compose sees it. */
private val CONFIRM_KEYS = setOf(
    Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.ButtonA, Key.Spacebar,
)

/** Buttons that mean "go back". B on a gamepad usually arrives as Back already. */
private val BACK_KEYS = setOf(Key.Back, Key.ButtonB, Key.Escape)

/**
 * Makes an element reachable and operable with the pad.
 *
 * The visual treatment is deliberately strong — a scale bump plus a border — because a
 * thin outline is invisible at the distance these things are held, and because the
 * selected item has to be findable without hunting.
 */
/**
 * Apaga la navegación de todo lo que quede por debajo en el árbol.
 *
 * Es lo que necesita una ventana encima de otra pantalla. Sin esto, con un modal abierto las
 * direcciones siguen llegando a la lista de detrás y el fondo se mueve solo mientras crees
 * que estás navegando el modal: la selección de la pantalla de abajo cambia sin que se vea, y
 * al cerrar el modal apareces en otro sitio.
 *
 * Va por aquí y no como un parámetro más porque lo que hay que apagar no es un elemento sino
 * una pantalla entera: con parámetros habría que enhebrar `enabled` por cada lista, cada fila
 * y cada pestaña hasta el último composable.
 */
val LocalPadEnabled = compositionLocalOf { true }

fun Modifier.padItem(
    onActivate: () -> Unit,
    focusRequester: FocusRequester? = null,
    /**
     * False deja el elemento fuera de la navegación.
     *
     * Es lo que apaga la pantalla de fondo cuando hay una ventana encima: si no, las
     * direcciones siguen llegando a la lista de detrás y se mueve sola mientras crees que
     * estás navegando el modal.
     */
    enabled: Boolean = true,
    // Mas marcado de lo que pediria una pantalla de escritorio: esto se mira a un brazo de
    // distancia y el foco es el unico indicador de donde esta uno.
    scaleWhenFocused: Float = 1.06f,
    // Null significa "el del tema". No se puede poner el tema como valor por defecto porque
    // este se evalua en quien llama, que no siempre es composable; se resuelve dentro.
    borderColor: Color? = null,
    borderWidth: Dp = 3.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) scaleWhenFocused else 1f, label = "focusScale")
    val ring = borderColor ?: LocalTheme.current.accent
    // Y por encima de lo que pida quien llama: con una ventana delante no se navega nada de
    // lo que quede detras, diga lo que diga cada elemento de si mismo.
    val on = enabled && LocalPadEnabled.current

    this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onKeyEvent { event ->
            if (!on || event.key !in CONFIRM_KEYS) return@onKeyEvent false
            // Una tecla que Android se invento a partir de otra no activa nada: se come y ya.
            //
            // Cuando nadie atiende un boton del mando, Android manda su equivalente del mapa de
            // teclas generico. En una consola de pruebas Start y los clics de los sticks son DPAD_CENTER, e Y
            // es SPACE, asi que Start con una ventana abierta pulsaba la fila elegida —sobre
            // «Clear preferences», borraba los ajustes—, Y lanzaba el juego en vez de nada, y un
            // clic de stick sin querer, tambien. Solo activa un A, o un Enter, de verdad. Los
            // campos de texto no pasan por aqui y siguen recibiendo el espacio de Y.
            if (event.nativeKeyEvent.flags and android.view.KeyEvent.FLAG_FALLBACK != 0) {
                return@onKeyEvent true
            }
            // Se actua al SOLTAR: mantener una direccion para recorrer una rejilla dispararia
            // la de debajo en cada repeticion. Pero la bajada tambien se come.
            //
            // Sin eso Android la daba por no atendida y fabricaba su equivalente del mapa de
            // teclas generico —A es DPAD_CENTER—, y esa segunda tecla, al soltarse, activaba otra
            // vez. En una consola de pruebas cada A lanzaba el juego dos veces: el emulador moria con la
            // segunda, el front-end volvia al frente un instante y la partida se daba por
            // terminada, y en los ajustes cada interruptor cambiaba dos veces y se quedaba igual.
            // Lo mismo que ya hacia la raiz con Start. Y una tecla cancelada no cuenta: es la
            // forma en que Android retira una de esas copias.
            if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) onActivate()
            true
        }
        .focusable(enabled = on, interactionSource = interaction)
        .scale(scale)
        .then(if (focused) Modifier.border(borderWidth, ring, shape) else Modifier)
}

/**
 * Handles "back" coming from the pad.
 *
 * Kept separate from the system back handler because B on a gamepad does not always
 * arrive as a system back press, and a frontend that cannot go back with B is unusable.
 */
fun Modifier.padBack(onBack: () -> Unit): Modifier = this.onKeyEvent { event ->
    // Una cancelada no cuenta, como en padItem: es la copia que Android retira. Con B llegando
    // como BUTTON_B, atender su subida hace que el sistema mande su BACK equivalente ya
    // cancelado, y sin mirarlo cada B volvia dos niveles.
    if (event.type == KeyEventType.KeyUp && event.key in BACK_KEYS && !event.nativeKeyEvent.isCanceled) {
        onBack(); true
    } else false
}

/**
 * Puts the focus somewhere sensible when a screen opens.
 *
 * A screen that opens with nothing focused is a dead end on a pad: no direction does
 * anything until the user finds something to touch, which on this hardware may be never.
 *
 * This has to be called from inside the element that will receive the focus, not from the
 * screen that contains it. A lazy grid has not composed its items yet when the screen
 * first runs, so a request made up there lands on a requester that is still attached to
 * nothing and is silently dropped.
 */
@Composable
fun AutoFocus(requester: FocusRequester, enabled: Boolean = true) {
    LaunchedEffect(requester, enabled) {
        if (enabled) runCatching { requester.requestFocus() }
    }
}

/** Convenience for a focusable container that also holds the initial focus target. */
@Composable
fun rememberFocusRequester(): FocusRequester = remember { FocusRequester() }

/** An empty box used to park focus when a screen has nothing else to hold it. */
@Composable
fun FocusAnchor(requester: FocusRequester) {
    Box(Modifier.focusRequester(requester).focusable())
}
