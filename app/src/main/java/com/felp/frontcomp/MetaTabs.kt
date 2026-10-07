package com.felp.frontcomp

import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Las pestañas del metagame que no son la portada: logros y misiones. Ver Achievements y Missions.
 */

/**
 * Los logros: los conseguidos primero, y dentro de cada grupo por lo cerca que estan. Cada fila
 * dice como se consigue y lleva su barra.
 */
@Composable
internal fun AchievementsTab(b: Book, accent: Color) {
    val rows = remember(b) {
        b.achievements.sortedWith(compareByDescending<Achievements.State> { it.earned }.thenByDescending { it.progress })
    }
    val earned = rows.count { it.earned }
    val stamp = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH) }
    // El titulo ocupa todo el ancho (lleva su regla): la cuenta va encima, en la esquina.
    Box(Modifier.fillMaxWidth()) {
        Heading("ACHIEVEMENTS")
        Text("$earned / ${rows.size}", color = accent, fontSize = 14.sp, fontFamily = MenuDisplay, modifier = Modifier.align(Alignment.TopEnd))
    }
    var selected by remember(b) { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, rows.lastIndex)
    ModalRows(count = rows.size, selected = sel, onSelect = { selected = it }, onActivate = {}) { i ->
        val s = rows[i]
        Row(verticalAlignment = Alignment.CenterVertically) {
            // La marca: un trofeo, del acento si esta conseguido y apagado si no.
            Trophy(if (s.earned) accent else MenuFaint.copy(alpha = .55f), Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.a.name, color = if (s.earned) MenuInk else MenuDim, fontSize = 12.sp, fontFamily = MenuBody,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${s.a.group.label}  ·  ${s.a.how}", color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (s.earned) {
                // Con la fecha en que se consiguio.
                Column(Modifier.width(120.dp)) {
                    Text("EARNED", color = accent, fontSize = 10.sp, fontFamily = MenuBody, letterSpacing = 1.sp)
                    s.earnedAt?.let {
                        Text(stamp.format(java.util.Date(it)), color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody)
                    }
                }
            } else {
                Column(Modifier.width(120.dp)) {
                    ProgressLine(s.progress, accent)
                    Text("%.0f %%".format(s.progress * 100), color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody)
                }
            }
        }
    }
}

/** Una barra fina de progreso, del mismo cristal que las del cuaderno. */
@Composable
private fun ProgressLine(f: Double, accent: Color) {
    val deco = LocalTheme.current.ornament
    Box(Modifier.fillMaxWidth().height(8.dp).border(1.dp, MenuLine).padding(1.dp)) {
        if (f > 0.0) Box(Modifier.fillMaxWidth(f.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().glass(accent, .75f, ornament = deco))
    }
}

/**
 * Las misiones. La elegida dice como se cumple; A la empieza o la deja. Mientras se sigue, lleva
 * su barra desde que se empezo, y los juegos que sirven llevan su marca en la lista.
 */
@Composable
internal fun MissionsTab(b: Book, prefs: Prefs, accent: Color, onChange: () -> Unit) {
    val tracked = prefs.trackedMissions
    val done = b.missionsDone.groupingBy { it.first }.eachCount()
    val completions = b.completions
    val rows = Missions.ALL
    var selected by remember { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, rows.lastIndex)
    Box(Modifier.fillMaxWidth()) {
        Heading("MISSIONS")
        Text("${tracked.size} tracked  ·  ${done.values.sum()} done", color = accent, fontSize = 12.sp, fontFamily = MenuBody, maxLines = 1, modifier = Modifier.align(Alignment.TopEnd))
    }
    // Lo que pide la elegida, en su renglon fijo: es lo mismo que recuerda la tarjeta al jugar.
    val m = rows[sel]
    Text(
        m.how, color = MenuInk, fontSize = 12.sp, fontFamily = MenuBody, maxLines = 2,
        modifier = Modifier.fillMaxWidth().height(38.dp),
    )
    Text(
        (if (m.id in tracked) "A  stop tracking" else "A  track it: the games that fit get a mark in the list") +
            "      Reward  +${Metagame.xp(m.reward.toDouble())} XP" + (m.vertex?.let { " to ${it.label}" } ?: ""),
        color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody, maxLines = 1,
    )
    Spacer(Modifier.height(8.dp))
    ModalRows(
        count = rows.size,
        selected = sel,
        onSelect = { selected = it },
        onActivate = {
            val now = prefs.trackedMissions
            prefs.trackedMissions = if (m.id in now) now - m.id else now + (m.id to System.currentTimeMillis())
            onChange()
        },
    ) { i ->
        val x = rows[i]
        val at = tracked[x.id]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (at != null) "◆" else "◇", color = if (at != null) accent else MenuFaint,
                fontSize = 14.sp, fontFamily = MenuBody, modifier = Modifier.width(26.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(x.title, color = MenuInk, fontSize = 12.sp, fontFamily = MenuBody, maxLines = 1)
                Text(
                    "+${Metagame.xp(x.reward.toDouble())} XP" + (x.vertex?.let { "  ${it.label}" } ?: "  all five") +
                        (done[x.id]?.let { "  ·  done ×$it" } ?: ""),
                    color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody, maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.width(120.dp)) {
                if (at != null) {
                    val p = Missions.progress(x, b.plays, completions, at, b.years)
                    ProgressLine(p, accent)
                    Text("tracking  ·  %.0f %%".format(p * 100), color = MenuFaint, fontSize = 10.sp, fontFamily = MenuBody)
                }
            }
        }
    }
}

/**
 * Un trofeo, dibujado: copa con dos asas, pie y peana escalonada. Geometria y no un glifo, para
 * que salga igual en las tres letras de los temas y a cualquier tamaño.
 */
@Composable
internal fun Trophy(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun x(f: Float) = w * f
        fun y(f: Float) = h * f
        // La copa: borde recto arriba y fondo redondeado.
        val cup = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(.24f), y(.04f))
            lineTo(x(.76f), y(.04f))
            lineTo(x(.76f), y(.30f))
            cubicTo(x(.76f), y(.52f), x(.62f), y(.60f), x(.50f), y(.60f))
            cubicTo(x(.38f), y(.60f), x(.24f), y(.52f), x(.24f), y(.30f))
            close()
        }
        drawPath(cup, color)
        // Las asas: dos arcos a los lados.
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w * .085f)
        drawArc(color, 90f, 180f, false, androidx.compose.ui.geometry.Offset(x(.06f), y(.10f)), androidx.compose.ui.geometry.Size(x(.30f), y(.26f)), style = stroke)
        drawArc(color, -90f, 180f, false, androidx.compose.ui.geometry.Offset(x(.64f), y(.10f)), androidx.compose.ui.geometry.Size(x(.30f), y(.26f)), style = stroke)
        // El pie, y la peana en dos escalones.
        drawRect(color, androidx.compose.ui.geometry.Offset(x(.44f), y(.58f)), androidx.compose.ui.geometry.Size(x(.12f), y(.16f)))
        drawRect(color, androidx.compose.ui.geometry.Offset(x(.34f), y(.73f)), androidx.compose.ui.geometry.Size(x(.32f), y(.10f)))
        drawRect(color, androidx.compose.ui.geometry.Offset(x(.24f), y(.83f)), androidx.compose.ui.geometry.Size(x(.52f), y(.13f)))
    }
}
