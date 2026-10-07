package com.felp.frontcomp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * El nombre del programa, dibujado: «ludolog» en trazo fino, con la segunda o hecha un ojo.
 *
 * Dibujado y no escrito con una tipografia. Asi no depende de ninguna licencia de letra —esto se
 * vende— y es el mismo en los tres temas, cada uno con su tinta. El ojo es el rombo de la mesa de
 * la gema del Companion con su pupila: el nombre mira, que es lo que hace el cuaderno.
 *
 * Las medidas son las del boceto de la marca, en su
 * misma rejilla: la linea base en cero, la altura de la x en veintidos, los palos en treinta y
 * cuatro y un trazo de 2,4.
 */
@Composable
internal fun Wordmark(color: Color, modifier: Modifier = Modifier) {
    val letters = remember { wordPath() }
    Canvas(modifier.aspectRatio(WORD_W / WORD_H)) {
        val s = size.width / WORD_W
        withTransform({
            scale(s, s, pivot = Offset.Zero)
            translate(STROKE / 2f, ASCENDER + STROKE / 2f)
        }) {
            drawPath(
                letters, color,
                style = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawOval(color, topLeft = PUPIL.topLeft, size = PUPIL.size)
        }
    }
}

private const val STROKE = 2.4f
private const val ASCENDER = 34f
private const val DESCENDER = 15f
private const val WORD_W = 174f + STROKE
private const val WORD_H = ASCENDER + DESCENDER + STROKE
private val PUPIL = Rect(125f, -13.5f, 133f, -8.5f)

private fun wordPath() = Path().apply {
    // l
    moveTo(0f, -ASCENDER); lineTo(0f, 0f)
    // u: el palo izquierdo baja hasta la mitad, la curva cierra por abajo y el derecho llega al pie.
    moveTo(10f, -22f); lineTo(10f, -11f)
    arcTo(Rect(10f, -22f, 32f, 0f), 180f, -180f, false)
    moveTo(32f, -22f); lineTo(32f, 0f)
    // d
    addOval(Rect(42f, -22f, 64f, 0f))
    moveTo(64f, -ASCENDER); lineTo(64f, 0f)
    // o
    addOval(Rect(74f, -22f, 96f, 0f))
    // l
    moveTo(106f, -ASCENDER); lineTo(106f, 0f)
    // el ojo, en el sitio de la segunda o
    moveTo(116f, -11f); lineTo(129f, -19.5f); lineTo(142f, -11f); lineTo(129f, -2.5f); close()
    // g, con la cola abierta hacia la izquierda
    addOval(Rect(152f, -22f, 174f, 0f))
    moveTo(174f, -22f); lineTo(174f, 4f)
    arcTo(Rect(152f, -7f, 174f, 15f), 0f, 150f, false)
}

/**
 * El emblema del tema: el mismo que firma la tarjeta de encima del juego.
 *
 * El del propio tema si trae uno dibujado —la gema del Gallery, la ficha del de fosforo—, con
 * su sombreado y en su acento; y si no, el que el tema diga de los del programa, que en el Parlour
 * es el libro con el ojo. Ver RecordingCard, que decide igual.
 */
@Composable
internal fun ThemeEmblem(size: Dp, modifier: Modifier = Modifier) {
    val look = LocalTheme.current
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val tint = look.accent.toArgb()
    val bmp = remember(look.id, px, tint) {
        (ThemeFiles.companion(ctx)?.let { SvgMark.tinted(it, px, tint) }
            ?: when (look.emblem) {
                Emblem.RECKONING -> ReckoningMark.bitmap(px, tint)
                Emblem.DIAMOND -> DiamondMark.bitmap(px, tint)
            }).asImageBitmap()
    }
    Image(bmp, contentDescription = null, modifier = modifier.size(size))
}

/**
 * El emblema del tema encima del nombre: la marca entera, como sale en la entrada y en «About».
 *
 * `progress` dibuja debajo una linea que se llena, la de la entrada. Sin ella, la marca sola.
 */
@Composable
internal fun BrandMark(
    modifier: Modifier = Modifier,
    emblem: Dp = 96.dp,
    word: Dp = 200.dp,
    progress: (() -> Float)? = null,
) {
    val ink = MenuInk
    val accent = LocalTheme.current.accent
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ThemeEmblem(emblem)
        androidx.compose.foundation.layout.Spacer(Modifier.height(22.dp))
        Wordmark(ink, Modifier.width(word))
        if (progress != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(26.dp))
            Canvas(Modifier.width(word * 0.6f).height(2.dp)) {
                drawRect(accent.copy(alpha = 0.25f))
                drawRect(accent, size = Size(size.width * progress().coerceIn(0f, 1f), size.height))
            }
        }
    }
}

/**
 * La entrada: la marca del tema un momento, al abrir el programa, y luego se aparta.
 *
 * Solo al ABRIRLO. Volver de un juego no la ensena —la actividad sigue viva— y tampoco si Android
 * la reconstruyo con lo guardado: quien vuelve a lo que estaba haciendo no quiere un saludo.
 *
 * Mientras se ve, las teclas no pasan a la lista de detras (ver MainActivity.dispatchKeyEvent) ni
 * los toques: una A a ciegas abriria una consola que nadie ha visto elegir.
 */
@Composable
internal fun Intro() {
    var shown by rememberSaveable { mutableStateOf(true) }
    if (!shown) return
    val line = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        Boot.introShowing = true
        line.animateTo(1f, tween(INTRO_MS, easing = LinearEasing))
        fade.animateTo(0f, tween(INTRO_FADE_MS))
        Boot.introShowing = false
        shown = false
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Boot.introShowing = false } }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(MenuGround)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        BrandMark(progress = { line.value })
    }
}

/** Lo que el resto del programa necesita saber de la entrada, fuera de Compose. */
internal object Boot {
    @Volatile
    var introShowing = false
}

private const val INTRO_MS = 1_100
private const val INTRO_FADE_MS = 380
