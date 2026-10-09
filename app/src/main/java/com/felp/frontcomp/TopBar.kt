package com.felp.frontcomp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// La barra de arriba: el engranaje, la hora y los iconos de estado. Salio de MainActivity.kt el 07-10-2026.

@Composable
internal fun TopBar(vm: LibraryViewModel, onSettings: () -> Unit, onSearch: () -> Unit = {}) {
    // Con la barra escondida no se pide nada al aparato: ni receptor de bateria, ni consulta
    // de salidas de audio, ni el reloj despertandose cada veinte segundos. Esconderla es
    // esconderla de verdad, no dibujarla transparente.
    val full = vm.prefs.statusBar
    val dev = if (full) rememberDeviceState() else DeviceState()
    Row(
        // Asimetrica y pegada al canto. El hueco entre la barra y lo de debajo son DOS cosas
        // sumadas —lo que ella deja por debajo y lo que hay debajo deja por encima—, asi que se
        // recortan las dos. La raya de abajo cuelga de esta fila, asi que lo que se le quite por
        // debajo la sube a ella y sube con ella todo lo que venga despues.
        //
        // Y arriba tambien: la aplicacion va a pantalla completa, no hay barra
        // del sistema que esquivar, asi que los diez puntos de aire no los pedia nadie.
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Con el tema PSX los iconos son mapas de bits dibujados pixel a pixel, a la misma
        // escala que el texto del dialogo de la sala. Un icono vectorial y suave al lado de
        // un menu de letras cuadradas es lo unico que se veria de otra epoca.
        val pixel = LocalTheme.current.pixelIcons
        val ink = MenuInk
        // Por gestos y no con clickable: clickable hace focusable al engranaje, y desde la
        // primera fila de una ventana modal la cruceta hacia arriba se le iba, por detras
        // del velo. Alli B ya no era de la ventana sino del sistema, y cerraba todo.
        Box(Modifier.pointerInput(Unit) { detectTapGestures { onSettings() } }.padding(4.dp)) {
            if (pixel) PixelIcon(PixelIcons.gear, ink) else GearIcon()
        }
        // La lupa, al lado: buscar un juego con el dedo. Con el mando, su atajo (ver SearchWindow).
        Box(Modifier.pointerInput(Unit) { detectTapGestures { onSearch() } }.padding(start = 10.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
            if (pixel) PixelIcon(PixelIcons.search, ink) else SearchIcon()
        }
        if (!full) return@Row
        Spacer(Modifier.weight(1f))

        // Tambien la pasada de fichas, que va antes que el scraper: sin esto, el primer arranque
        // parecia parado mientras leia por dentro una biblioteca de cartuchos.
        (vm.progress ?: vm.readingGames)?.let { p ->
            Text(
                "${p.done}/${p.total}", color = MenuDim, fontSize = 12.sp,
                fontFamily = MenuBody,
                modifier = Modifier.padding(end = 14.dp)
            )
        }
        // El orden es por temas: primero por donde sale el sonido, luego la red, luego la
        // corriente y al final la hora. Dibujados en vez de emoji: los emoji del sistema
        // llegan en color y rompen el monocromo, ademas de cambiar de aspecto segun el
        // aparato.
        if (dev.headphones) {
            if (pixel) PixelIcon(PixelIcons.headphones, ink) else HeadphonesIcon()
            Spacer(Modifier.width(12.dp))
        }
        if (dev.bluetooth) {
            if (pixel) PixelIcon(PixelIcons.bluetooth, ink) else BluetoothIcon()
            Spacer(Modifier.width(12.dp))
        }
        if (dev.online) {
            if (pixel) PixelIcon(PixelIcons.wifi, ink) else WifiIcon()
            Spacer(Modifier.width(12.dp))
        }
        // El rayo va FUERA de la pila, y la pila enseña la carga de verdad.
        //
        // Antes, cargando, se le pasaba cien fijo: la pila salia llena al enchufar aunque
        // quedara el diez por ciento, que es justo cuando uno mira el icono.
        if (dev.charging) {
            if (pixel) PixelIcon(PixelIcons.bolt, ink) else BoltIcon()
            Spacer(Modifier.width(4.dp))
        }
        if (pixel) PixelIcon(PixelIcons.battery(dev.batteryPercent), ink)
        else BatteryIcon(dev.batteryPercent, dev.charging)
        // El numero al lado del dibujo: el dibujo dice de un vistazo si queda mucho o poco,
        // y el numero es el que hace falta para decidir si da tiempo a una partida mas.
        if (dev.batteryPercent >= 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "${dev.batteryPercent}%", color = ink, fontSize = 12.sp, fontFamily = MenuBody,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (dev.clock.isNotEmpty()) {
            Text(dev.clock, color = MenuInk, fontSize = 13.sp, fontFamily = MenuBody)
        }
    }
    if (full) HorizontalDivider(color = MenuFaint.copy(alpha = .4f), thickness = 1.dp)
}

@Composable
private fun BatteryIcon(pct: Int, charging: Boolean) {
    // El color se toma aqui: el lambda de dibujo no es composable y no ve el tema.
    val ink = MenuInk
    Canvas(Modifier.size(width = 26.dp, height = 13.dp)) {
        val stroke = 1.5.dp.toPx()
        val bodyW = size.width - 3.dp.toPx()
        drawRect(
            color = ink, topLeft = Offset(0f, 0f),
            size = Size(bodyW, size.height),
            style = Stroke(width = stroke),
        )
        // Terminal positivo.
        drawRect(
            color = ink,
            topLeft = Offset(bodyW + 1.dp.toPx(), size.height * .3f),
            size = Size(2.dp.toPx(), size.height * .4f),
        )
        val level = (pct.coerceIn(0, 100)) / 100f
        if (pct >= 0) {
            val inset = stroke + 1.dp.toPx()
            drawRect(
                color = if (charging) ink else ink.copy(alpha = .85f),
                topLeft = Offset(inset, inset),
                size = Size(((bodyW - inset * 2) * level).coerceAtLeast(0f), size.height - inset * 2),
            )
        }
    }
}

@Composable
private fun WifiIcon() {
    val ink = MenuInk
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val cx = size.width / 2f
        val base = size.height * .88f
        // Tres arcos concéntricos y el punto, que es como se lee "señal" de un vistazo.
        listOf(.95f, .62f, .30f).forEach { f ->
            val r = size.width * f / 2f
            drawArc(
                color = ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - r, base - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke),
            )
        }
        drawCircle(color = ink, radius = 1.4.dp.toPx(), center = Offset(cx, base))
    }
}

@Composable
private fun HeadphonesIcon() {
    val ink = MenuInk
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val r = size.width * .42f
        val cx = size.width / 2f
        val cy = size.height * .52f
        drawArc(
            color = ink, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
            style = Stroke(width = stroke),
        )
        val earW = 3.2.dp.toPx()
        val earH = 6.dp.toPx()
        drawRect(ink, Offset(cx - r - earW / 2, cy), Size(earW, earH))
        drawRect(ink, Offset(cx + r - earW / 2, cy), Size(earW, earH))
    }
}

/** La runa de bluetooth: el palo y los dos triangulos que lo cruzan. */
@Composable
private fun BluetoothIcon() {
    val ink = MenuInk
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val cx = size.width / 2f
        val top = size.height * .10f
        val bot = size.height * .90f
        val mid = size.height / 2f
        val q1 = size.height * .30f
        val q3 = size.height * .70f
        val right = size.width * .78f
        val left = size.width * .22f
        // El palo, los dos triangulos a la derecha y los dos trazos que los cruzan.
        drawLine(ink, Offset(cx, top), Offset(cx, bot), stroke)
        drawLine(ink, Offset(cx, top), Offset(right, q1), stroke)
        drawLine(ink, Offset(right, q1), Offset(left, q3), stroke)
        drawLine(ink, Offset(cx, bot), Offset(right, q3), stroke)
        drawLine(ink, Offset(right, q3), Offset(left, q1), stroke)
        drawLine(ink, Offset(left, q1), Offset(cx, mid), stroke)
    }
}

/** El rayo de la carga, al lado de la pila. */
@Composable
private fun BoltIcon() {
    val ink = MenuInk
    Canvas(Modifier.size(width = 9.dp, height = 14.dp)) {
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .60f, 0f)
            lineTo(size.width * .05f, size.height * .58f)
            lineTo(size.width * .45f, size.height * .58f)
            lineTo(size.width * .35f, size.height)
            lineTo(size.width * .95f, size.height * .40f)
            lineTo(size.width * .55f, size.height * .40f)
            close()
        }
        drawPath(p, ink)
    }
}

/* --------------------------------------------------------------------- nivel 1: consolas */

/** La lupa de la barra de arriba, dibujada como el engranaje (ver GearIcon). */
@Composable
internal fun SearchIcon() {
    val ink = MenuInk
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.minDimension
        val stroke = w * 0.11f
        val r = w * 0.30f
        val c = androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.42f)
        drawCircle(ink, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val from = androidx.compose.ui.geometry.Offset(c.x + r * 0.72f, c.y + r * 0.72f)
        drawLine(ink, from, androidx.compose.ui.geometry.Offset(w * 0.90f, w * 0.90f), stroke * 1.3f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
