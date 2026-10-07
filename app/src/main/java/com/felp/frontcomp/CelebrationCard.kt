package com.felp.frontcomp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lo que trajo la ultima partida, para anunciarlo. Ver LibraryViewModel.news. */
internal data class Celebration(
    val fromLevel: Int,
    val level: Int,
    /** Lo que dio la ultima partida, si hay una nueva. */
    val gain: Metagame.Gain?,
    /** Los logros recien conseguidos, por su nombre. */
    val achievements: List<String>,
    /** Las misiones recien cumplidas, con su recompensa. */
    val missions: List<Pair<String, Int>>,
)

/**
 * El anuncio al volver de una partida: arriba, unos segundos, y se va solo. Lo mas grande primero
 * —subir de nivel—, y debajo lo que dio la partida, los logros y las misiones. Con su jingle, el
 * del mas importante de lo que trae. Se quita tambien tocandolo.
 *
 * Cada tema la viste con lo que ya usa en el resto de la pantalla, para que no parezca pegada
 * encima: el Parlour, las regletas con rombos del panel de consolas; el Mainframe, una caja de
 * instrumento con el titulo en la ceja, como las del panel; el Gallery, sin caja, con las
 * marcas de corte de las caratulas y el titulo fino con su raya corta.
 */
@Composable
internal fun CelebrationCard(c: Celebration, onDone: () -> Unit) {
    val t = LocalTheme.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shown = remember(c) { Animatable(0f) }
    LaunchedEffect(c) {
        // Solo lo que dio la partida, sin nada mas: sin jingle y menos rato.
        val kind = when {
            c.level > c.fromLevel -> Jingle.Kind.LEVEL_UP
            c.missions.isNotEmpty() -> Jingle.Kind.MISSION
            c.achievements.isNotEmpty() -> Jingle.Kind.ACHIEVEMENT
            else -> null
        }
        kind?.let { Jingle.play(it, t.id, ctx) }
        shown.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        delay(if (kind == null) 4000 else 6500)
        shown.animateTo(0f, tween(350))
        onDone()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val heading = when {
        c.level > c.fromLevel -> "LEVEL UP"
        c.missions.isNotEmpty() -> "MISSION COMPLETE"
        c.achievements.isNotEmpty() -> "ACHIEVEMENT"
        else -> "SESSION"
    }
    val shownHeading = if (t.upperTitles) heading else heading.lowercase().replaceFirstChar { it.uppercase() }
    val ground = MenuGround.copy(alpha = .96f)
    val faint = MenuFaint
    val frame = when (t.chrome) {
        Chrome.RULES -> Modifier.panelChrome(ground).padding(horizontal = 22.dp, vertical = 16.dp)
        Chrome.INSTRUMENT -> Modifier.background(ground).instrumentPane(ticks = false).browLabel(shownHeading)
            .padding(PANE_PAD).padding(horizontal = 8.dp, vertical = 4.dp)
        Chrome.BORDER -> Modifier.background(ground)
            .drawBehind { cropMarks(Rect(Offset.Zero, size), faint) }
            .padding(horizontal = 28.dp, vertical = 22.dp)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 64.dp)
                .graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * -40.dp.toPx() }
                .widthIn(min = 420.dp, max = 620.dp)
                .pointerInput(c) { detectTapGestures { scope.launch { shown.animateTo(0f, tween(250)); onDone() } } }
                .then(frame),
            // Las cajas de instrumento se leen como una pantalla de datos: todo a la izquierda.
            horizontalAlignment = if (t.chrome == Chrome.INSTRUMENT) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            val levelLine = if (c.level > c.fromLevel) "LV ${c.fromLevel}  →  LV ${c.level}" else null
            when (t.chrome) {
                Chrome.RULES -> {
                    Text(
                        shownHeading, color = MenuInk, fontSize = 20.sp, fontFamily = MenuDisplay,
                        fontWeight = FontWeight.Bold, letterSpacing = t.titleTracking,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                    PanelDivider()
                    Spacer(Modifier.height(12.dp))
                    levelLine?.let {
                        Text(it, color = t.accent, fontSize = 17.sp, fontFamily = MenuBody)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                // El titulo ya va en la ceja: dentro, el salto de nivel como una lectura grande.
                Chrome.INSTRUMENT -> levelLine?.let {
                    Text(it, color = t.accent, fontSize = 24.sp, fontFamily = MenuDisplay, letterSpacing = t.titleTracking)
                    Spacer(Modifier.height(8.dp))
                }
                // Como la placa de una consola: el nombre fino y grande, una raya corta y debajo el
                // dato pequeño y espaciado.
                Chrome.BORDER -> {
                    Text(
                        shownHeading, color = MenuInk, fontSize = 34.sp, fontFamily = MenuDisplay,
                        fontWeight = FontWeight.Light, letterSpacing = 8.sp,
                    )
                    Box(Modifier.padding(vertical = 10.dp).width(56.dp).height(1.dp).drawBehind { drawRect(faint) })
                    levelLine?.let {
                        Text(it, color = MenuDim, fontSize = 12.sp, fontFamily = MenuBody, letterSpacing = t.titleTracking)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            Details(c, heading, t)
        }
    }
}

/** Lo que dio la partida, los logros y las misiones: igual en todos los temas salvo la viñeta. */
@Composable
private fun ColumnScope.Details(c: Celebration, heading: String, t: Theme) {
    val accent = t.accent
    val bullet = when (t.chrome) {
        Chrome.RULES -> "◆  "
        Chrome.INSTRUMENT -> "▸ "
        Chrome.BORDER -> ""
    }
    c.gain?.let { g ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("+${Metagame.xp(g.total)} XP", color = accent, fontSize = 18.sp, fontFamily = MenuDisplay)
            for ((v, m) in g.byVertex.entries.sortedByDescending { it.value }) {
                if (m < 0.5) continue
                Spacer(Modifier.width(12.dp))
                Text("${v.label} +${Metagame.xp(m)}", color = v.color, fontSize = 12.sp, fontFamily = MenuBody)
            }
        }
        if (g.rested >= 0.5) Text("rested +${Metagame.xp(g.rested)}", color = accent.copy(alpha = .8f), fontSize = 11.sp, fontFamily = MenuBody)
    }
    // Si el titulo ya dice que es, la linea no lo repite.
    for (a in c.achievements) {
        Spacer(Modifier.height(6.dp))
        val bare = heading == "ACHIEVEMENT"
        Line(if (bare) a else "${bullet}ACHIEVEMENT  ·  $a", bare)
    }
    for ((m, xp) in c.missions) {
        Spacer(Modifier.height(6.dp))
        val bare = heading == "MISSION COMPLETE"
        Line((if (bare) m else "${bullet}MISSION COMPLETE  ·  $m") + "  +${Metagame.xp(xp.toDouble())} XP", bare)
    }
}

@Composable
private fun Line(text: String, big: Boolean, color: Color = MenuInk) =
    Text(text, color = color, fontSize = if (big) 16.sp else 13.sp, fontFamily = MenuBody)
