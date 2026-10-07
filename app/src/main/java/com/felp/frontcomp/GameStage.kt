package com.felp.frontcomp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * El panel de un juego en el tema de borde: la caratula o el video mas pequeño, arriba, con un
 * adorno que se ajusta a la imagen, y debajo lo que se cuenta del juego.
 *
 * Se pidio porque la imagen ocupaba el panel entero y la descripcion iba debajo de la lista,
 * robandole alto. Ahora la lista baja hasta abajo y el texto va con la imagen de la que habla.
 *
 * Lo que se enseña es O la caratula O el video, y tienen formas distintas: una portada es mas
 * alta que ancha y un video al reves. Por eso el adorno no tiene un sitio fijo: se dibuja
 * alrededor del rectangulo que ocupa la imagen de verdad, el mismo que ya usan las lineas del
 * Mainframe (ver CalloutTarget), y sigue a la imagen cuando pasa de portada a video.
 */

/** Los adornos que se estan probando alrededor de la imagen. Ver Prefs.stageDeco. */
internal enum class StageDeco { MARKS, YEAR, SHEET }

/** Lo que ocupa la imagen de su hueco, a lo ancho y a lo alto. */
private const val MEDIA_WIDTH = 0.72f
private const val MEDIA_HEIGHT = 0.66f

@Composable
internal fun GameStage(
    deco: StageDeco,
    facts: List<Pair<String, String>>,
    text: String,
    modifier: Modifier = Modifier,
    media: @Composable () -> Unit,
) {
    val target = remember { CalloutTarget() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val line = MenuLine
    val faint = MenuFaint
    val measurer = rememberTextMeasurer()
    val year = facts.firstOrNull { it.first == "YEAR" }?.second
    val base = LocalTextStyle.current
    val body = MenuBody
    val yearStyle = remember(base, body) {
        base.merge(TextStyle(fontFamily = body, fontSize = 150.sp, fontWeight = FontWeight.Light, letterSpacing = 0.sp))
    }

    Column(modifier) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                .then(
                    when (deco) {
                        // Detras de la imagen: se asoma por donde la imagen no llega, por los
                        // lados de una portada o por arriba y abajo de un video.
                        StageDeco.YEAR -> Modifier.drawBehind {
                            val r = target.bounds?.translate(-origin) ?: return@drawBehind
                            val y = year ?: return@drawBehind
                            val layout = measurer.measure(y, yearStyle)
                            val at = Offset(
                                r.center.x - layout.size.width / 2f,
                                r.center.y - layout.size.height / 2f,
                            )
                            drawText(layout, color = line, topLeft = at, drawStyle = Stroke(width = 1.dp.toPx()))
                        }
                        // Encima, fuera de la imagen: las marcas de corte de una lamina.
                        StageDeco.MARKS -> Modifier.drawWithContent {
                            drawContent()
                            val r = target.bounds?.translate(-origin) ?: return@drawWithContent
                            cropMarks(r, faint)
                        }
                        StageDeco.SHEET -> Modifier
                    }
                )
                // Aire para las marcas: salen de la imagen hacia fuera.
                .padding(horizontal = 30.dp, vertical = 22.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            // Y la imagen en una parte del hueco, no en todo: a hueco entero seguia siendo
            // demasiado grande, y alrededor es donde se lee la lamina.
            Box(
                Modifier.fillMaxWidth(MEDIA_WIDTH).fillMaxHeight(MEDIA_HEIGHT),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CompositionLocalProvider(LocalCalloutTarget provides target) { media() }
            }
        }
        if (deco == StageDeco.SHEET && facts.isNotEmpty()) {
            HairLine()
            FactSheet(facts)
        }
        HairLine()
        Spacer(Modifier.height(10.dp))
        Text(
            text, color = MenuInk, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = MenuBody,
            maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(70.dp),
        )
    }
}

/**
 * Una consola dicha con letras, para los temas que no la dibujan (ver Theme.plates): su nombre
 * corto en grande y, debajo, quien la hizo y el año. Con las mismas marcas de corte que la
 * caratula de un juego, para que las dos pantallas sean de la misma lamina.
 *
 * El nombre se ajusta al hueco: «PS2» y «ANDROID» no caben al mismo tamaño, y uno cortado con
 * puntos dejaria de ser un nombre.
 */
@Composable
internal fun ConsolePlate(label: String, maker: String, year: Int, modifier: Modifier = Modifier) {
    val base = LocalTextStyle.current
    val display = MenuDisplay
    val body = MenuBody
    val ink = MenuInk
    val faint = MenuFaint
    val dim = MenuDim
    val tracking = LocalTheme.current.titleTracking
    val measurer = rememberTextMeasurer()
    val caption = listOfNotNull(maker.takeIf { it.isNotEmpty() }?.uppercase(), year.takeIf { it > 0 }?.toString())
        .joinToString("  ·  ")
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        val room = constraints.maxWidth * 0.78f
        // A que tamaño cabe el nombre: se mide a cien y se escala, con un tope para que uno de
        // dos letras no se coma la pantalla.
        val size = remember(label, room, base, display) {
            val probe = measurer.measure(
                label, base.merge(TextStyle(fontFamily = display, fontSize = 100.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp)),
                maxLines = 1, softWrap = false,
            ).size.width.coerceAtLeast(1)
            (100f * room / probe).coerceAtMost(96f)
        }
        var block by remember { mutableStateOf<Rect?>(null) }
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                .drawWithContent {
                    drawContent()
                    val r = block?.translate(-origin) ?: return@drawWithContent
                    // Un poco mas de aire que alrededor de una imagen: las letras no tienen canto.
                    cropMarks(r.inflate(14.dp.toPx()), faint)
                },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                modifier = Modifier.onGloballyPositioned { block = it.boundsInRoot() },
            ) {
                Text(
                    label, color = ink, maxLines = 1, softWrap = false,
                    style = base.merge(
                        TextStyle(fontFamily = display, fontSize = size.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp),
                    ),
                )
                if (caption.isNotEmpty()) {
                    Box(Modifier.padding(vertical = 10.dp).width(56.dp).height(1.dp).drawBehind { drawRect(faint) })
                    Text(
                        caption, color = dim, fontSize = 12.sp, fontFamily = body,
                        letterSpacing = tracking, maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Las marcas de corte: en cada esquina, dos trazos cortos que siguen los cantos de la imagen por
 * fuera, separados de ella. Es como se señala en una lamina donde acaba lo impreso.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.cropMarks(r: Rect, color: androidx.compose.ui.graphics.Color) {
    val gap = 6.dp.toPx()
    val len = 14.dp.toPx()
    val w = 1.dp.toPx()
    fun h(x0: Float, x1: Float, y: Float) = drawLine(color, Offset(x0, y), Offset(x1, y), w)
    fun v(x: Float, y0: Float, y1: Float) = drawLine(color, Offset(x, y0), Offset(x, y1), w)
    // Arriba a la izquierda, arriba a la derecha, abajo a la izquierda, abajo a la derecha.
    h(r.left - gap - len, r.left - gap, r.top); v(r.left, r.top - gap - len, r.top - gap)
    h(r.right + gap, r.right + gap + len, r.top); v(r.right, r.top - gap - len, r.top - gap)
    h(r.left - gap - len, r.left - gap, r.bottom); v(r.left, r.bottom + gap, r.bottom + gap + len)
    h(r.right + gap, r.right + gap + len, r.bottom); v(r.right, r.bottom + gap, r.bottom + gap + len)
}

/** La ficha: cada dato con su rotulo pequeño encima, en columnas iguales. */
@Composable
private fun FactSheet(facts: List<Pair<String, String>>) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((label, value) in facts.take(4)) {
            Column(Modifier.weight(1f)) {
                Text(
                    label, color = MenuFaint, fontSize = 9.sp, fontFamily = MenuBody,
                    letterSpacing = 1.sp, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value, color = MenuInk, fontSize = 11.5.sp, fontFamily = MenuBody,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
