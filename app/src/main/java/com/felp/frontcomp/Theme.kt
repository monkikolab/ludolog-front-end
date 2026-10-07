package com.felp.frontcomp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalTheme = compositionLocalOf { GalleryTheme }

/** Atajo para no escribir LocalTheme.current en cada línea. */
val theme: Theme
    @Composable get() = LocalTheme.current

/** Cambia el tema y lo recuerda. Lo usa la pantalla de ajustes. */
val LocalThemeSwitch = compositionLocalOf<(String) -> Unit> { {} }

/** Cambia el acento y lo recuerda. Lo usa la pantalla de ajustes. */
val LocalAccentSwitch = compositionLocalOf<(String?) -> Unit> { {} }

/** Cambia de luz y lo recuerda. Solo hace algo en los temas que tienen las dos. */
val LocalBrightSwitch = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Puts the chosen theme in scope for the whole app.
 *
 * The choice is read once and kept in state rather than re-read from storage on each
 * frame: switching has to repaint everything at once, and a preference read per
 * recomposition would be both slow and unreliable.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var id by remember { mutableStateOf(prefs.themeId) }
    var accent by remember { mutableStateOf(prefs.accent) }
    var bright by remember { mutableStateOf(prefs.brightMode) }
    // El tema elegido, en la luz elegida. Uno que no tenga otra luz ignora el interruptor en
    // vez de quedarse a medias: `bright()` devuelve nulo y manda el de siempre.
    val base = themeById(id)
    // Y se le dice a ThemeFiles cual es, que es lo que hace que los medios y las salas salgan
    // de la carpeta de ESTE tema y no de la comun. Aqui y no en otro sitio: este es el unico
    // lugar del programa donde vive el tema en vivo. Con el id del ASPECTO —`base`, no
    // `current`— porque la luz clara es un ajuste del mismo tema y comparte carpeta.
    //
    // En el CUERPO y no en un SideEffect, aunque sea un efecto. El orden importa: un
    // SideEffect corre cuando la composicion ya se aplico, o sea DESPUES de que los hijos
    // hayan buscado sus ficheros, y entonces buscaban en la carpeta del tema anterior. Se veia
    // exactamente asi: al cambiar de Gallery a Mainframe, el color y la letra
    // cambiaban al instante y la consola seguia siendo la silueta del Gallery, teñida de
    // rojo. Aqui corre antes que ellos, que es cuando sirve. Es idempotente y se guarda solo si
    // cambia, asi que repetirlo en cada recomposicion no cuesta nada.
    ThemeFiles.use(base.id)
    val current = (if (bright) base.bright() ?: base else base).withAccent(accent).withFont(ctx)

    // El esquema de Material decide los colores por defecto de lo que este tema no pinta.
    // Con uno oscuro sobre un fondo claro, eso es texto blanco sobre papel.
    val scheme = if (current.light) lightColorScheme(primary = current.ink, onPrimary = current.ground)
                 else darkColorScheme(primary = current.ink, onPrimary = current.ground)

    // La escala de letra del tema entra por la densidad: todo lo que se mide en sp la
    // hereda sin que ningún texto tenga que saberlo.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalTheme provides current,
        LocalThemeSwitch provides { next: String -> prefs.themeId = next; id = next },
        LocalAccentSwitch provides { next: String? -> prefs.accent = next; accent = next },
        LocalBrightSwitch provides { next: Boolean -> prefs.brightMode = next; bright = next },
        LocalDensity provides Density(density.density, density.fontScale * current.typeScale),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}



/**
 * El filtro que convierte cualquier imagen en fosforo, o nada si el tema no lo pide.
 *
 * De cada pixel se toma su CLARIDAD y se vuelve a montar con el acento. Eso es exactamente lo
 * que hace una pantalla de un solo fosforo: no hay tres sustancias que mezclen un color, hay
 * una que brilla mas o menos. Una caratula pasa de ser una fotografia a ser lo que ese aparato
 * puede enseñar de ella.
 *
 * Con una ganancia por encima de uno. La claridad pura deja la imagen a media luz —un gris
 * medio da medio acento— y lo que se quiere es que lo mas claro de la caratula llegue al color
 * entero, como llega el blanco en un tubo. Lo que se pase, se recorta, que es lo que hace un
 * tubo tambien.
 */
@Composable
fun phosphorFilter(): ColorFilter? {
    val t = LocalTheme.current
    if (!t.phosphor) return null
    val c = t.accent
    return remember(c) {
        val g = PHOSPHOR_GAIN
        val r = c.red * g
        val gr = c.green * g
        val b = c.blue * g
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.299f * r, 0.587f * r, 0.114f * r, 0f, 0f,
                    0.299f * gr, 0.587f * gr, 0.114f * gr, 0f, 0f,
                    0.299f * b, 0.587f * b, 0.114f * b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
}

/**
 * El tema con la letra que traiga instalada.
 *
 * Hasta aqui un tema podia cambiar el color, el adorno y los sonidos, pero la letra era la del
 * sistema: monoespaciada o palo seco, y se acabo. Y la letra es la mitad de un aspecto — dos
 * temas con la misma tipografia se parecen por mucho que cambien de color.
 *
 * Se carga del fichero y no del APK, igual que los sonidos: un tema es una carpeta. Si el
 * fichero no esta, o no es una tipografia que el aparato sepa leer, se queda la del sistema y
 * no pasa nada mas; por eso va envuelto en un `runCatching` y no en una comprobacion.
 */
@Composable
fun Theme.withFont(ctx: android.content.Context): Theme {
    val d = remember(id) { ThemeFiles.display(ctx) }
    val b = remember(id) { ThemeFiles.body(ctx) }
    if (d == null && b == null) return this
    val dFamily = remember(d) {
        d?.let { runCatching { FontFamily(androidx.compose.ui.text.font.Font(it)) }.getOrNull() }
    }
    val bFamily = remember(b) {
        b?.let { runCatching { FontFamily(androidx.compose.ui.text.font.Font(it)) }.getOrNull() }
    }
    return copy(display = dFamily ?: display, body = bFamily ?: body)
}
