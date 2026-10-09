package com.felp.frontcomp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * The television the panel lives inside.
 *
 * A prerendered set rather than a model: a still image is one asset for every console
 * instead of ninety, and what actually has to move is the screen, which is a video anyway.
 * The set is described by four numbers saying where its screen is, so fitting a new one is
 * a PNG and a TOML entry rather than a code change.
 */
data class TvSkin(
    val id: String,
    val name: String,
    /** La imagen del mueble. Vacío quiere decir «dibújalo». */
    val image: String = "",
    /**
     * Imagen que va ENCIMA del vídeo: cristal, reflejo, suciedad.
     *
     * Separada del mueble porque el vídeo tiene que ir en medio de las dos capas, y una
     * sola imagen no puede estar a la vez delante y detrás.
     */
    val overlay: String = "",
    /**
     * El pase de luz de la pantalla: solo lo que la television ilumina, sobre negro.
     *
     * Va aparte porque si el resplandor se hornea en la imagen de la sala queda congelado, y
     * una escena oscura alumbraria el cuarto igual que una clara. Teniendolo suelto se puede
     * sumar tenido por el color de lo que se este viendo.
     */
    val glow: String = "",
    /**
     * Pase de las brasas: solo lo que alumbra el fuego.
     *
     * Aparte del de la television porque el fuego tiene vida propia — parpadea por su cuenta
     * y no se apaga porque el juego sea oscuro.
     */
    val embers: String = "",
    /**
     * Solo lo que alumbra el LED de la radio de Ludolog Link, en blanco: la app lo tiñe de verde
     * (Link escuchando, y late) o de rojo (apagado). Vacío en las salas sin radio.
     */
    val led: String = "",
    val aspect: Float = 1.30f,
    /** Dónde está la pantalla, en fracción de la imagen: x, y, ancho, alto. */
    val screen: Rect = Rect(0.085f, 0.070f, 0.915f, 0.775f),
    /**
     * Las cuatro esquinas de la pantalla, si la tele no está de frente.
     *
     * Ocho números —arriba-izq, arriba-der, abajo-der, abajo-izq— en fracción de la imagen.
     * Manda sobre `screen`: una tele vista en diagonal no es un rectángulo, y meter el vídeo
     * en su caja envolvente lo deja visiblemente resbalando fuera del cristal.
     */
    val corners: List<Float> = emptyList(),
    /** Si la escena ocupa la pantalla entera en vez de un panel a la derecha. */
    val fullScreen: Boolean = false,
    /**
     * De qué depende esta sala, o vacío si de nada: una o varias condiciones separadas por
     * espacios, que tienen que cumplirse todas (ver [needs]).
     *
     * Hay varias versiones del salón: la de siempre, otra con The Reckoning sobre la mesita
     * (`reckoning`), otra con la radio de Ludolog Link en el suelo (`link`) y la de las dos
     * (`reckoning link`). Cuál sale lo decide el aparato, y quien lo declara es el decorado, no
     * el código. Así montar otra sala con otra condición es una línea en el TOML.
     *
     * Un requisito que la app no conozca deja la sala fuera. Es deliberado: una condición mal
     * escrita tiene que quitar la sala, no colarla siempre.
     */
    val requires: String = "",
    /**
     * Donde se planta la consola que gira, en fraccion de la pantalla: x, y, ancho, alto.
     *
     * Va en el decorado y no en el codigo porque depende del decorado: el hueco libre de una
     * sala no esta en el mismo sitio que el de otra, y en esta hay que esquivar el menu por
     * la izquierda, la television arriba y la chimenea por la derecha.
     *
     * Vacio quiere decir que esta escena no enseña consolas.
     */
    val console: Rect? = null,
    /**
     * Donde va el diálogo de la sala: la caja en la que se escribe, letra a letra, lo que se
     * cuenta de la consola o del juego elegido. Mismo formato que `console`.
     */
    val caption: Rect? = null,
) {
    /** Las condiciones de [requires], sueltas. */
    val needs: Set<String> get() = requires.split(' ', ',').filter { it.isNotBlank() }.toSet()

    /** La transformación que lleva el vídeo a la pantalla, o null si es un rectángulo. */
    val quad: Homography? by lazy { Homography.fromCorners(corners) }

    /**
     * La caja envolvente del cuadrilátero.
     *
     * Es donde se coloca la capa del vídeo: dibujarlo a pantalla completa y luego muestrear
     * un trozo pequeño lo dejaría blando, porque el vídeo se habría ampliado seis veces para
     * volver a reducirse cuatro.
     */
    val quadBounds: Rect by lazy {
        if (corners.size != 8) screen else {
            val xs = corners.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = corners.filterIndexed { i, _ -> i % 2 == 1 }
            Rect(xs.min(), ys.min(), xs.max(), ys.max())
        }
    }

    /** Las esquinas relativas a una caja cualquiera, que es el espacio en el que trabaja la capa. */
    fun cornersIn(b: Rect): List<Float> =
        if (corners.size != 8 || b.width <= 0f || b.height <= 0f) emptyList()
        else corners.mapIndexed { i, v ->
            if (i % 2 == 0) (v - b.left) / b.width else (v - b.top) / b.height
        }

    /**
     * La caja envolvente con margen alrededor, que es donde vive la capa del vídeo.
     *
     * Hace falta porque la televisión no termina en el cristal: derrama luz sobre su propio
     * bisel y sobre la pared. Ajustada al cuadrilátero exacto, la capa se recorta justo en
     * el borde del tubo y ese derrame no tiene dónde caer.
     *
     * El vídeo se dibuja estirado a la caja y el shader lo muestrea por la coordenada del
     * tubo, así que agrandarla no cambia ni el tamaño ni el encuadre de la imagen: solo deja
     * sitio a los lados.
     */
    fun haloBounds(margin: Float): Rect {
        val b = quadBounds
        return Rect(
            b.left - b.width * margin, b.top - b.height * margin,
            b.right + b.width * margin, b.bottom + b.height * margin,
        )
    }

    /** Resuelve la imagen contra las carpetas de medios, como el arte de consolas. */
    fun imageFile(roots: List<File> = ArtIndex.defaultRoots()): File? = resolve(image, roots)

    fun overlayFile(roots: List<File> = ArtIndex.defaultRoots()): File? = resolve(overlay, roots)

    fun glowFile(roots: List<File> = ArtIndex.defaultRoots()): File? = resolve(glow, roots)

    fun embersFile(roots: List<File> = ArtIndex.defaultRoots()): File? = resolve(embers, roots)

    fun ledFile(roots: List<File> = ArtIndex.defaultRoots()): File? = resolve(led, roots)

    private fun resolve(path: String, roots: List<File>): File? {
        if (path.isEmpty()) return null
        // Lo encontrado se guarda (revision del 09-10-2026): se buscaba en la tarjeta al componer, en
        // el hilo de la pantalla. Se olvida con lo demas del tema: ver ThemeFiles.forget.
        val key = path + "|" + roots.joinToString("|") { it.path }
        return found.getOrPut(key) {
            java.util.Optional.ofNullable(
                File(path).takeIf { it.isAbsolute && it.isFile }
                    ?: roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile },
            )
        }.orElse(null)
    }

    companion object {
        private val found = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<File>>()

        /** Olvida las rutas encontradas: ver ThemeFiles.forget. */
        fun forget() = found.clear()

        /**
         * The set that ships, drawn rather than rendered.
         *
         * Exists so the panel is a television before anyone has made a picture of one, and
         * so a missing or misspelled file degrades to something that still works instead of
         * to a hole. Deliberately plain: a drawn set that tries to look photographic next
         * to real box art loses every time.
         */
        val Drawn = TvSkin(id = "drawn", name = "Drawn set")
    }
}

/**
 * Loads the television sets.
 *
 * Same shape as the system catalog: one file the user can edit, merged over what ships,
 * keyed by id.
 */
object TvSkins {
    /** El comun, el de siempre: uno para todo el aparato. */
    val shared: File get() = DataHome.file("tvs.toml")

    /**
     * El que manda: el del tema puesto si lo trae, y si no el comun.
     *
     * Una sala es del tema que la dibujo, no del aparato. Con un solo fichero para todos, un
     * tema sin sala propia se encontraba la del vecino en cuanto estuviera instalada —le
     * cambiaba la letra y el color y el escenario seguia siendo de otro— y por eso hubo que
     * inventar la bandera `room` para que cada uno dijera «esta no es mia». La bandera sigue
     * valiendo, pero ahora por lo que dice y no para tapar un agujero.
     */
    val userFile: File get() = ThemeFiles.tvs() ?: shared

    var lastError: String? = null
        private set

    fun load(): List<TvSkin> {
        lastError = null
        val user = runCatching {
            userFile.takeIf { it.isFile }?.let { parse(it.readText()) }.orEmpty()
        }.onFailure { lastError = it.message }.getOrDefault(emptyList())

        // El dibujado va primero y el usuario puede sustituirlo declarando el mismo id.
        val byId = LinkedHashMap<String, TvSkin>()
        byId[TvSkin.Drawn.id] = TvSkin.Drawn
        user.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    fun parse(text: String): List<TvSkin> = Toml.parse(text).all("tv").mapNotNull { t ->
        val id = t.string("id")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val r = t.numbers("screen").map { it.toFloat() }
        TvSkin(
            id = id,
            name = t.string("name") ?: id,
            image = t.string("image").orEmpty(),
            overlay = t.string("overlay").orEmpty(),
            glow = t.string("glow").orEmpty(),
            embers = t.string("embers").orEmpty(),
            led = t.string("led").orEmpty(),
            aspect = t.number("aspect")?.toFloat()?.takeIf { it > 0f } ?: 1.30f,
            // x, y, ancho, alto -> Rect quiere los dos bordes.
            screen = if (r.size == 4) Rect(r[0], r[1], r[0] + r[2], r[1] + r[3])
                     else TvSkin.Drawn.screen,
            corners = t.numbers("corners").map { it.toFloat() }.takeIf { it.size == 8 }.orEmpty(),
            fullScreen = t.bool("fullScreen") ?: false,
            requires = t.string("requires").orEmpty(),
            console = t.numbers("console").map { it.toFloat() }.takeIf { it.size == 4 }
                ?.let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) },
            caption = t.numbers("caption").map { it.toFloat() }.takeIf { it.size == 4 }
                ?.let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) },
        )
    }
}

/**
 * The shipped set, drawn.
 *
 * Kept to the housing, the recess and a chin: every extra invented detail is one more
 * thing that reads as a drawing rather than as an object, and this sits beside real
 * photographs of boxes.
 */
@Composable
fun DrawnTvBody(screen: Rect, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Canvas(modifier.fillMaxSize()) {
        val body = theme.faint.copy(alpha = .30f)
        val edge = theme.faint.copy(alpha = .55f)

        drawRoundRect(
            color = body,
            cornerRadius = CornerRadius(size.minDimension * .055f),
        )
        drawRoundRect(
            color = edge,
            cornerRadius = CornerRadius(size.minDimension * .055f),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // El hueco de la pantalla, un punto mas oscuro que el fondo: es lo que hace que el
        // tubo parezca metido dentro y no pegado encima.
        val r = Rect(
            screen.left * size.width, screen.top * size.height,
            screen.right * size.width, screen.bottom * size.height,
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = .55f),
            topLeft = Offset(r.left, r.top),
            size = Size(r.width, r.height),
            cornerRadius = CornerRadius(r.minDimension * .085f),
        )

        // La barbilla: rejilla de altavoz a la izquierda y dos mandos a la derecha.
        val chinTop = r.bottom + (size.height - r.bottom) * .22f
        val chinMid = (chinTop + size.height * .97f) / 2f
        val grillLeft = size.width * .10f
        val grillRight = size.width * .58f
        var y = chinTop
        while (y < size.height * .94f) {
            drawLine(edge, Offset(grillLeft, y), Offset(grillRight, y), strokeWidth = 1f)
            y += 4.dp.toPx()
        }
        val knob = (size.height - chinTop) * .26f
        listOf(.74f, .88f).forEach { fx ->
            drawCircle(edge, knob, Offset(size.width * fx, chinMid), style = Stroke(1.5.dp.toPx()))
        }
    }
}
