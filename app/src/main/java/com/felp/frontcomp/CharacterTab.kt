package com.felp.frontcomp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.felp.frontcomp.Metagame.Vertex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * La portada del cuaderno: el personaje. Ver Metagame y docs/metagame.md.
 *
 * Arriba el pentagono, que es a la vez el nivel y el perfil: cuanto mas grande, mas nivel; hacia
 * donde se estira, que se juega. Al lado el nivel, la clase y la barra hasta el siguiente, que
 * brilla mientras dura el descanso. Debajo las ultimas partidas, y la elegida cuenta lo que dio:
 * mientras cuenta, el pentagono, la barra y el nivel suben desde como estaban antes de ella.
 */
@Composable
internal fun CharacterTab(
    b: Book,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
    cover: (String) -> java.io.File?,
    onSession: (LogStats.Entry) -> Unit,
) {
    val c = b.character
    val t = LocalTheme.current
    // La partida elegida abajo y lo que va contado de lo que dio: aqui y no en la lista, porque la
    // barra y el pentagono suben con ese mismo contador (ver Metagame.partial).
    val last = b.recent.take(LAST)
    var selected by remember(last) { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, (last.size - 1).coerceAtLeast(0))
    val chosen = last.getOrNull(sel)?.let { c.gains[it.id] }
    val count = remember { Animatable(0f) }
    // Si la partida cruzo un nivel, mas despacio: que se vea la barra llenarse y volver a empezar.
    val from = remember(c, chosen) { chosen?.let { Metagame.partial(c, it, 0.0).level } ?: c.level }
    LaunchedEffect(sel, chosen) {
        // Con el ahorro de bateria, nada se mueve solo: directo al final.
        if (Motion.saver.value) { count.snapTo(1f); return@LaunchedEffect }
        count.snapTo(0f)
        count.animateTo(1f, tween(if (from < c.level) 2400 else 1400, easing = FastOutSlowInEasing))
    }
    val shown = chosen?.let { Metagame.partial(c, it, count.value.toDouble()) } ?: c
    // El numero, en el color del tema desde que sube hasta que acaba de contar.
    val rising = count.value < 1f && shown.level > from
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pentagon(shown, accent, Modifier.size(214.dp))
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("LV", color = MenuDim, fontSize = 13.sp, fontFamily = MenuDisplay, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.width(8.dp))
                Text(shown.level.toString(), color = if (rising) accent else MenuInk, fontSize = 40.sp, fontFamily = MenuDisplay, maxLines = 1)
                Spacer(Modifier.width(16.dp))
                val cls = Metagame.classOf(c)
                Text(
                    cls?.let { Metagame.className(it, t.id).uppercase() } ?: "—",
                    color = accent, fontSize = 15.sp, fontFamily = MenuDisplay,
                    letterSpacing = t.titleTracking, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            XpBar(shown, accent)
            Spacer(Modifier.height(14.dp))
            // Los cinco, con su nivel: lo mismo que dibuja el pentagono, en numeros.
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (v in Vertex.entries) {
                    Column {
                        Text(v.label, color = v.color, fontSize = 10.sp, fontFamily = MenuBody, letterSpacing = 1.sp)
                        Text(
                            "%.1f".format(shown.vertexLevels[v] ?: 0.0), color = MenuInk,
                            fontSize = 15.sp, fontFamily = MenuBody,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Heading("LAST SESSIONS")
    LastSessions(last, c, sel, { selected = it }, count.value, rank, accent, name, cover, onSession)
}

/**
 * El pentagono: una punta por vertice, y el poligono de lo que lleva cada uno. El borde de fuera
 * es el vertice lleno, asi que el tamaño del poligono es el nivel y su forma, el perfil.
 */
@Composable
internal fun Pentagon(c: Metagame.Character, accent: Color, modifier: Modifier, labels: Boolean = true) = PentagonImpl(c, accent, modifier, labels)

/**
 * El color de cada vertice, el mismo en todos los temas: es un dato, como el color de una consola,
 * y no un adorno. Apagados, para que convivan con el acento de cada tema sin gritar; y lo bastante
 * separados en tono para distinguirlos en un icono de doce puntos.
 */
internal val Vertex.color: Color
    get() = when (this) {
        Vertex.POWER -> Color(0xFFE0654A)
        Vertex.REFLEX -> Color(0xFFE3C04F)
        Vertex.SOUL -> Color(0xFFB08AE6)
        Vertex.NERVE -> Color(0xFF5DBF84)
        Vertex.MIND -> Color(0xFF5AA8E0)
    }

/**
 * El pentagono en pequeño, junto al nombre de un juego: a que vertices va lo que se juega en el.
 * Un punto de color en cada vertice que alimenta, del tamaño de su parte; sin genero —experiencia
 * neutra, un quinto a cada uno—, los cinco iguales y apagados.
 */
@Composable
internal fun MiniPentagon(split: Map<Vertex, Double>?, modifier: Modifier = Modifier.size(13.dp)) {
    if (split == null) return
    val outline = MenuFaint
    val neutral = split.size == Vertex.entries.size
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2 + size.height * .05f)
        val r = size.minDimension / 2 * .78f
        fun at(i: Int): Offset {
            val a = Math.toRadians(-90.0 + i * 72.0)
            return Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
        }
        val ring = Path().apply {
            for (i in 0 until 5) at(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            close()
        }
        drawPath(ring, outline, style = Stroke(1.dp.toPx()))
        Vertex.entries.forEachIndexed { i, v ->
            val w = split[v] ?: return@forEachIndexed
            val dot = if (neutral) 1.3.dp.toPx() else (1.6 + 1.4 * w).dp.toPx()
            drawCircle(if (neutral) outline else v.color, dot, at(i))
        }
    }
}

@Composable
private fun PentagonImpl(c: Metagame.Character, accent: Color, modifier: Modifier, labels: Boolean) {
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontFamily = MenuBody, fontSize = 10.sp, color = MenuDim, letterSpacing = 1.sp)
    val grid = MenuFaint.copy(alpha = .35f)
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2 + 6f)
        // Sin rotulos (en el avatar, que es pequeño) el poligono se lleva todo el sitio.
        val r = size.minDimension / 2 - (if (labels) 30.dp else 6.dp).toPx()
        fun at(i: Int, f: Float): Offset {
            val a = Math.toRadians(-90.0 + i * 72.0)
            return Offset(center.x + (r * f * cos(a)).toFloat(), center.y + (r * f * sin(a)).toFloat())
        }
        fun ring(f: Float) = Path().apply {
            for (i in 0 until 5) at(i, f).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            close()
        }
        for (f in listOf(.25f, .5f, .75f, 1f)) drawPath(ring(f), grid, style = Stroke(1.dp.toPx()))
        for (i in 0 until 5) drawLine(grid, center, at(i, 1f), 1.dp.toPx())
        // Un minimo para que se vea algo desde la primera partida: con dos horas, un vertice va
        // por el cuatro por ciento y el poligono seria un punto.
        val shape = Path().apply {
            Vertex.entries.forEachIndexed { i, v ->
                val f = ((c.vertexLevels[v] ?: 0.0) / Metagame.VERTEX_LEVELS).toFloat().coerceIn(0.04f, 1f)
                at(i, f).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            }
            close()
        }
        drawPath(shape, accent.copy(alpha = .30f))
        drawPath(shape, accent, style = Stroke(2.dp.toPx()))
        if (labels) Vertex.entries.forEachIndexed { i, v ->
            val text = measurer.measure(v.label, label.copy(color = v.color))
            val p = at(i, 1.18f)
            drawText(text, topLeft = Offset(p.x - text.size.width / 2, p.y - text.size.height / 2))
        }
    }
}

/**
 * La barra hasta el siguiente nivel, de las de siempre: se llena de izquierda a derecha. Con
 * descanso en la reserva brilla —un marco que late en el color del tema— y dice cuanto queda de
 * experiencia doble.
 */
@Composable
private fun XpBar(c: Metagame.Character, accent: Color) {
    val deco = LocalTheme.current.ornament
    val rested = c.rested >= 1.0
    // Al ritmo de Motion y solo con descanso: con la transicion infinita, la barra se recomponia
    // en cada refresco de la pantalla, hubiera descanso o no.
    val pulse by if (rested) rememberPulse(low = .35f, periodMs = 2200) else remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxWidth().height(16.dp)
            .then(if (rested) Modifier.border(2.dp, accent.copy(alpha = pulse)) else Modifier.border(1.dp, MenuLine))
            .padding(3.dp),
    ) {
        Box(Modifier.fillMaxWidth(c.progress.toFloat().coerceIn(0f, 1f)).fillMaxHeight().glass(accent, .80f, ornament = deco))
    }
    Spacer(Modifier.height(5.dp))
    // En dos renglones: en un tema de letra ancha, los dos en uno se pisaban.
    Text(
        if (c.level >= 99) "MAX"
        else "%.0f %%".format(c.progress * 100) + (c.toNext?.let { "  ·  ${Metagame.xp(it)} XP to LV ${c.level + 1}" } ?: ""),
        color = MenuDim, fontSize = 11.sp, fontFamily = MenuBody, maxLines = 1,
    )
    if (rested) Text(
        "RESTED  ·  +${Metagame.xp(c.rested)} bonus XP ready",
        color = accent.copy(alpha = .5f + pulse / 2), fontSize = 11.sp, fontFamily = MenuBody, maxLines = 1,
    )
}

/**
 * Las ultimas partidas. La elegida cuenta lo que dio, subiendo de cero: el total, lo que puso el
 * descanso y a que vertices fue. Las demas enseñan su total quieto.
 */
@Composable
private fun LastSessions(
    last: List<LogStats.Entry>,
    c: Metagame.Character,
    sel: Int,
    onSelect: (Int) -> Unit,
    counted: Float,
    rank: List<String>,
    accent: Color,
    name: (String) -> String,
    cover: (String) -> java.io.File?,
    onSession: (LogStats.Entry) -> Unit,
) {
    if (last.isEmpty()) {
        Text("nothing yet", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
        ModalRows(count = 0, selected = 0, onSelect = {}, onActivate = {}) {}
        return
    }
    val stamp = remember { SimpleDateFormat("d MMM  HH:mm", Locale.ENGLISH) }
    val chosen = c.gains[last[sel].id]
    // Lo que dio la elegida, desglosado: ocupa su renglon fijo encima de la lista.
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (chosen != null) {
            val f = counted.toDouble()
            Text("+${Metagame.xp(chosen.total * f)} XP", color = accent, fontSize = 14.sp, fontFamily = MenuDisplay)
            Spacer(Modifier.width(14.dp))
            for ((v, m) in chosen.byVertex.entries.sortedByDescending { it.value }) {
                if (m < 0.5) continue
                Text("${v.label} +${Metagame.xp(m * f)}", color = v.color, fontSize = 11.sp, fontFamily = MenuBody)
                Spacer(Modifier.width(12.dp))
            }
            if (chosen.rested >= 0.5) Text("rested +${Metagame.xp(chosen.rested * f)}", color = accent.copy(alpha = .8f), fontSize = 11.sp, fontFamily = MenuBody)
            if (chosen.tired >= 0.5) {
                Spacer(Modifier.width(12.dp))
                Text("tired −${Metagame.xp(chosen.tired * f)}", color = MenuFaint, fontSize = 11.sp, fontFamily = MenuBody)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    ModalRows(
        count = last.size,
        selected = sel,
        onSelect = onSelect,
        onActivate = { onSession(last[sel]) },
    ) { i ->
        val e = last[i]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(cover(e.title), 26.dp, consoleColour(e.system, rank, accent))
            Spacer(Modifier.width(8.dp))
            GameLine(e.title, e.system, rank, accent, name, Modifier.weight(1f), elsewhere(e))
            Cell(stamp.format(Date(e.startedAt)), SESSION_WHEN, MenuDim)
            Cell(span(e.durationMs), SESSION_PLAYED, MenuInk)
            val g = c.gains[e.id]
            Cell(g?.let { "+" + Metagame.xp(if (i == sel) it.total * counted else it.total) } ?: "—", 76.dp, accent)
        }
    }
}

/** Cuantas partidas se ven en la portada. */
private const val LAST = 8
