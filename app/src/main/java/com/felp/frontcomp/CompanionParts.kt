package com.felp.frontcomp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit

// Separado de StatsWindow.kt sin cambios: lo que leen las pestanas del Companion (Book) y sus
// celdas y rotulos. Sin nada de Android, porque Ludolog Link del PC compila este mismo archivo
// para dibujar su Companion con las pestanas de Ludolog. Ver docs/ludolog-link.md.

/** Todo lo que la pantalla necesita, leido de una sentada. */
internal class Book(
    val overview: LogStats.Overview,
    val bySystem: List<LogStats.Slice>,
    val ranking: List<LogStats.Ranked>,
    val recent: List<LogStats.Entry>,
    val devices: List<String>,
    val splits: List<LogStats.Split>,
    val rates: List<LogStats.Rate>,
    val health: LogStats.Health,
    /** Cuantas lineas tiene el cuaderno y lo que ocupa su fichero. */
    val lines: Int,
    val bytes: Long,
    val chargeNowUah: Long?,
    /** El personaje: nivel, vertices y lo que dio cada partida. Ver Metagame. */
    val character: Metagame.Character,
    /** Los logros, con lo que lleva cada uno. Ver Achievements. */
    val achievements: List<Achievements.State>,
    /** Las partidas con todo, para lo que las misiones calculan en la pantalla. */
    val plays: List<LogStats.Play>,
    /** El año de cada consola, por su id. */
    val years: Map<String, Int>,
    /** Cuando se marco terminado cada juego, en cualquier consola. */
    val completions: List<Long>,
    /** Las misiones cumplidas en cualquier consola. */
    val missionsDone: List<Pair<String, Long>>,
    /**
     * A que vertices va cada juego de las listas, por su nombre visible y su consola: el mini
     * pentagono de GAMES y SESSIONS. Leido aqui, fuera de la interfaz, una vez por juego.
     */
    val feeds: Map<Pair<String, String>, Map<Metagame.Vertex, Double>> = emptyMap(),
) {
    fun feedsOf(title: String, system: String?): Map<Metagame.Vertex, Double>? = feeds[title to (system ?: "?")]
}

/** El rotulo de un bloque, con su regla debajo. */
@Composable
internal fun Heading(text: String) {
    Text(
        text, color = MenuInk, fontSize = 12.sp, fontFamily = MenuDisplay,
        letterSpacing = LocalTheme.current.titleTracking,
    )
    Spacer(Modifier.height(6.dp))
    HairLine()
    Spacer(Modifier.height(8.dp))
}

/** Una celda de ancho fijo: las columnas tienen que cuadrar entre filas. */
@Composable
internal fun Cell(text: String, width: Dp, color: Color, end: Boolean = true) {
    // Once puntos y no diez, y las cifras pegadas a la derecha de su celda: alineadas a la
    // izquierda, «4m» y «2h 13m» empezaban en el mismo sitio y acababan cada una donde podia, y
    // la columna no se leia como columna. Con un poco de aire delante, para que el final de una
    // celda no se pegue al principio de la siguiente —«01:45 4m» se leia como una sola cifra—.
    Text(
        text, color = color, fontSize = 11.sp, fontFamily = MenuBody, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = Modifier.padding(start = 8.dp).width(width),
    )
}

/**
 * El titulo de una columna, del mismo ancho que sus celdas: sin titulos, «37m  —  58°  397 mAh/h»
 * eran cuatro cifras sin nombre.
 */
@Composable
internal fun HeadCell(text: String, width: Dp, end: Boolean = true) {
    HeadLabel(
        text, Modifier.padding(start = 8.dp).width(width),
        align = if (end) TextAlign.End else TextAlign.Start,
    )
}

/** El titulo de la columna que se lleva lo que sobra —el del juego—, o de cualquier otra. */
@Composable
internal fun HeadLabel(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text, color = MenuFaint, fontSize = 9.5.sp, fontFamily = MenuBody, letterSpacing = 1.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = modifier,
    )
}

/** Una cifra con su rotulo: el rotulo a la izquierda, el numero pegado a la derecha. */
@Composable
internal fun Figure(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, color = MenuDim, fontSize = 10.sp, fontFamily = MenuBody)
        Spacer(Modifier.weight(1f))
        Text(value, color = MenuInk, fontSize = 12.sp, fontFamily = MenuBody, maxLines = 1)
    }
}

/** Horas y minutos. Los segundos solo cuando no hay ni un minuto que ensenar. */
internal fun span(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

/** Bytes en algo que quepa en una fila: KB hasta el mega, y de ahi MB con un decimal. */
internal fun weight(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1_048_576L -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}

/**
 * Las cifras del registro entero, en tarjetas.
 *
 * Debajo del aparato y no en una pestana propia: son el fondo contra el que se lee todo lo
 * demas, y mandarlas a su propia pantalla obligaria a ir y volver para comparar.
 */
@Composable
internal fun Cards(b: Book) {
    val o = b.overview
    // El aparato de ESTE registro, el que el tracker apunta en cada partida.
    Figure("Device", android.os.Build.MODEL.orEmpty().ifEmpty { "handheld" })
    // Y cuantos se cuentan, si no es solo este: las cifras de debajo son de todos, y justo
    // debajo del nombre de este se leian como suyas.
    if (b.devices.size > 1) Figure("Counting", "${b.devices.size} devices")
    Figure("Sessions", o.sessions.toString())
    Figure("Games", o.games.toString())
    Figure("Played", span(o.totalMs))
    Figure("Longest", span(o.longestMs))
    // Por debajo de un vatio-hora, en milivatios-hora. Una partida corta son centesimas de
    // Wh y "0.0 Wh" se lee como que la cuenta no funciona, no como que fue poco. Las que no se
    // saben, fuera: una tarjeta «—» no cuenta nada.
    o.energyWh?.let { Figure("Energy", if (it < 1f) "%.0f mWh".format(it * 1000f) else "%.1f Wh".format(it)) }
    // Dos cifras que se confundian con una: las baterias que se llevaron los juegos, y las cargas
    // que ha hecho cada consola (sus ciclos desde la primera partida apuntada), que incluyen el
    // reposo y todo lo demas. Esta es la que uno reconoce como «cuantas veces he cargado».
    o.charges?.let { Figure("Batteries played", "%.2f".format(it)) }
    // Las tres, de esta consola sola, como el pico de CPU: son del aparato.
    val here = android.os.Build.MODEL.orEmpty().ifEmpty { "handheld" }
    b.plays.filter { it.device == here }.mapNotNull { it.cycles }
        .takeIf { it.isNotEmpty() }
        ?.let { Figure("Charge cycles", (it.max() - it.min()).toString()) }
    // El nucleo de CPU mas caliente, un segundo: no la temperatura de la consola. Ver Telemetry.
    o.peakTempC?.let { Figure("CPU peak", "%.0f °C".format(it)) }
    if (o.bestStreakDays > 0) Figure("Streak", "${o.streakDays} d · best ${o.bestStreakDays}")
    // Lo que el cuaderno es como objeto, no como relato: cuantos renglones lleva escritos y
    // lo que ocupa. Va al final y no arriba porque no es una cifra de lo jugado; es de quien
    // se pregunta cuanto va a crecer esto con los anos.
    Figure("Lines", "%,d".format(b.lines))
    Figure("Size", weight(b.bytes))
}
