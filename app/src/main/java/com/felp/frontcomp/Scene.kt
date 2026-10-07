package com.felp.frontcomp

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import java.io.File

/**
 * The room the whole frontend sits inside.
 *
 * A single prerendered still, with only the television's screen alive. That split is what
 * makes the scene affordable: the room never changes, so it costs one image, and the one
 * thing that has to move was a video already.
 *
 * The room blurs while you are choosing a console and sharpens once you are in a game list.
 * That is the whole navigation model stated in focus rather than in words — and it is done
 * at runtime rather than with two prerendered images, because the interesting part is the
 * transition between them, which two stills cannot do.
 */

/** Cuánto se desenfoca la sala mientras se eligen consolas. */
private const val BLUR_DP = 9f

/** El tinte de la nieve: la tele encendida sin sintonizar es blanco frío. */
val StaticTint = Color(0.86f, 0.90f, 1.0f)

/**
 * Cuánto se agranda la capa del vídeo por cada lado, en fracción de la pantalla.
 *
 * Es el sitio donde cae el resplandor de fuera del cristal. Con margen justo el derrame se
 * recorta contra el borde de la capa y se ve el corte, así que va por encima de los 0.42 de
 * alcance que usa el shader. Agrandar no reescala la imagen —el vídeo se muestrea por la
 * coordenada del tubo— pero sí se dibuja más grande, así que tampoco conviene pasarse.
 */
private const val HALO_BOX = 0.42f

/**
 * El parpadeo del fuego.
 *
 * Tres senos de frecuencias que no son múltiplos entre sí: la suma no vuelve a repetirse
 * nunca, así que las brasas no caen en un patrón reconocible. Se mueve alrededor de 0,72 y
 * despacio, porque unas brasas respiran, no parpadean.
 */

/**
 * Exagera lo que la television le hace a la habitacion.
 *
 * El color medio de un fotograma se mueve poco: casi todo el gameplay vive en una franja
 * estrecha de brillo, asi que el cuarto apenas se enteraba de si la escena era una cueva o
 * un campo nevado. Esto separa lo oscuro de lo claro alrededor de un punto medio bajo, que
 * es donde de verdad esta la mayoria del material.
 *
 * El limite de arriba no es uno sino algo mas: el tinte multiplica una capa de luz ya
 * horneada, y dejarlo pasar de uno permite que una escena clara encienda el cuarto por
 * encima de como se rindio, que es justo lo que se pide al exagerar.
 *
 * Y por abajo NO llega a cero, que es de lo que va TINT_FLOOR.
 *
 * Con la ganancia a secas, cualquier fotograma cuyo canal medio bajara de 0,156 daba cero, y
 * eso es media hora de cualquier juego de terror: en una escena oscura la television dejaba
 * de alumbrar DEL TODO y la sala se caia al pase base, como si el aparato estuviera apagado.
 * Un tubo no hace eso. Aunque la imagen sea casi negra sigue habiendo fosforo encendido, haz
 * de barrido y el propio cristal devolviendo algo.
 *
 * El suelo va TEÑIDO DEL COLOR DEL FOSFORO y no gris: lo que queda cuando la escena no aporta
 * color es la luz propia del tubo, y esa es blanco frio. Plano en los tres canales, una cueva
 * verde y una cueva roja alumbrarian la sala igual, que es justo lo contrario de para lo que
 * esta el tinte.
 */
private fun punch(v: Float, phosphor: Float): Float =
    ((v - TINT_PIVOT) * TINT_GAIN + TINT_PIVOT).coerceIn(TINT_FLOOR * phosphor, 1f)

private const val TINT_PIVOT = 0.34f
private const val TINT_GAIN = 1.85f

/**
 * Lo menos que la television alumbra, en fraccion de lo que alumbra a tope.
 *
 * Un cuarto se mide en el sitio donde la luz de la tele pega de verdad —la pared de encima y
 * el suelo de delante— y no en la pared del fondo, que el cono nunca alcanza. Ahi, con una
 * escena oscura: a 0 la silla y el suelo desaparecen enteros; a 0,18 no se distingue de 0; a
 * 0,25 vuelven la silla y el suelo sin que el cuarto deje de estar a oscuras; de 0,35 para
 * arriba la sala empieza a no enterarse de que la escena era oscura, que es lo contrario de
 * para lo que esta el tinte.
 */
private const val TINT_FLOOR = 0.40f

/**
 * El parpadeo de una television sin senal estable, para cuando el panel ensena una caratula.
 *
 * Una imagen fija deja el cuarto con una luz muerta, y un tubo de verdad nunca esta quieto:
 * la fuente respira, la senal tiembla. Son tres senos rapidos y sin relacion entre si, que
 * es lo que hace que no se oiga un patron, y con muy poca amplitud: esto se nota sin que
 * nadie pueda decir que lo ha visto.
 */
private fun tvJitter(t: Float): Float =
    1f + 0.035f * kotlin.math.sin(t * 11.3f) +
        0.022f * kotlin.math.sin(t * 19.7f + 0.9f) +
        0.014f * kotlin.math.sin(t * 31.1f + 2.2f)
private fun emberFlicker(t: Float): Float =
    0.72f + 0.17f * kotlin.math.sin(t * 2.1f) +
        0.09f * kotlin.math.sin(t * 3.7f + 1.3f) +
        0.05f * kotlin.math.sin(t * 6.3f + 2.7f)

@Composable
fun SceneBackground(
    file: File,
    glow: File?,
    embers: File?,
    tint: Color,
    blurred: Boolean,
    /** Si lo que se ve es una imagen fija: entonces el cuarto tiembla por su cuenta. */
    still: Boolean = false,
    /** La luz del LED de la radio de Link, en blanco (ver TvSkin.led), y si Link esta escuchando. */
    led: File? = null,
    ledOn: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // El temblor y las brasas, al ritmo de Motion y no al de la pantalla: redibujar tres imagenes
    // a pantalla completa 120 veces por segundo era lo que mas gastaba de todo el programa.
    // Desenfocado (la lista de consolas) no se mueve: bajo el desenfoque no se ve temblar, y el
    // desenfoque se volvia a calcular entero en cada paso. En reposo, a 8 por segundo: el cuarto
    // sigue vivo sin pedir casi nada.
    val idle by Motion.idle
    val clockState = rememberMotionClock(
        running = (embers != null || still || (led != null && ledOn)) && !blurred,
        fps = if (idle) 8 else null,
    )
    val clock by clockState
    val blur by animateFloatAsState(
        targetValue = if (blurred) BLUR_DP else 0f,
        animationSpec = tween(420),
        label = "sceneBlur",
    )
    // El tinte se suaviza: leer el color de un fotograma da saltos, y un cuarto que
    // parpadea con cada corte de plano marea en vez de acompañar.
    // Mas corto que antes: con 280 ms el cuarto llegaba tarde a todo y la modulacion se
    // perdia por el camino. La suavidad sigue haciendo falta —leer el color de un fotograma
    // da saltos— pero 140 basta para que no de tirones.
    val r by animateFloatAsState(punch(tint.red, StaticTint.red), tween(140), label = "tintR")
    val g by animateFloatAsState(punch(tint.green, StaticTint.green), tween(140), label = "tintG")
    val b by animateFloatAsState(punch(tint.blue, StaticTint.blue), tween(140), label = "tintB")

    val base = remember(file.path) { loadBitmap(file) }
    val lit = remember(glow?.path) { glow?.let { loadBitmap(it) } }
    val fire = remember(embers?.path) { embers?.let { loadBitmap(it) } }
    val pilot = remember(led?.path) { led?.let { loadBitmap(it) } }

    Canvas(
        modifier.fillMaxSize().graphicsLayer {
            renderEffect =
                if (blur <= 0.1f) null
                else RenderEffect.createBlurEffect(
                    blur * density, blur * density, Shader.TileMode.CLAMP,
                ).asComposeRenderEffect()
        }
    ) {
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        base?.let {
            // Vecino más próximo: la sala se rinde a 384x216 y sube por 5 exacto hasta
            // 1920x1080. Con filtrado bilineal se deshace el pixelado que da todo el
            // aspecto.
            drawImage(it, dstSize = dst, filterQuality = FilterQuality.None)
        }
        lit?.let {
            // Con una caratula en el tubo la luz se queda muerta, asi que tiembla ella sola.
            // Con video no hace falta: ya se mueve con la imagen.
            //
            // Aqui dentro, en el dibujado: el reloj cambia en cada fotograma, y leido en la
            // composicion la sala entera se recomponia sesenta veces por segundo mientras
            // hubiera una imagen fija, que en la lista de consolas es siempre.
            val jitter = if (still) tvJitter(clock) else 1f
            // Suma, no mezcla: el pase del resplandor es luz, y la luz se añade a lo que ya
            // hay. Teñirlo por el color medio del gameplay es lo que hace que la habitación
            // se apague con una escena oscura y se encienda con una clara.
            drawImage(
                image = it,
                dstSize = dst,
                filterQuality = FilterQuality.None,
                colorFilter = ColorFilter.tint(
                    Color(
                        (r * jitter).coerceIn(0f, 1f),
                        (g * jitter).coerceIn(0f, 1f),
                        (b * jitter).coerceIn(0f, 1f),
                    ),
                    BlendMode.Modulate,
                ),
                blendMode = BlendMode.Plus,
            )
        }
        fire?.let {
            // Las brasas van por su cuenta: parpadean con el tiempo, no con lo que haya en
            // la televisión. Es lo único vivo de la habitación que no depende del juego.
            val f = emberFlicker(clock)
            drawImage(
                image = it,
                dstSize = dst,
                filterQuality = FilterQuality.None,
                colorFilter = ColorFilter.tint(Color(f, f, f), BlendMode.Modulate),
                blendMode = BlendMode.Plus,
            )
        }
        pilot?.let {
            // El LED de la radio de Ludolog Link: verde que respira (cada dos segundos) mientras
            // Link escucha, rojo quieto si esta apagado. Igual que en el giro de su entrada.
            val c = if (ledOn) {
                val k = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.cos(clock * Math.PI.toFloat()))
                Color(0.25f * k, 1f * k, 0.32f * k)
            } else Color(0.85f, 0.10f, 0.05f)
            drawImage(
                image = it,
                dstSize = dst,
                filterQuality = FilterQuality.None,
                colorFilter = ColorFilter.tint(c, BlendMode.Modulate),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

private fun loadBitmap(f: File): ImageBitmap? =
    runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()

/* ----------------------------------------------------------------- la pantalla */

/**
 * The picture on the glass, put through the same projection the render used.
 *
 * `quad` is the inverse of the transform that took a rectangle to the four corners of the
 * screen: for each pixel on the way out it says which point of the video to read. Without
 * it the video would be stretched into the screen's bounding box and would visibly slide
 * off the glass as the eye followed the edges of the tube.
 */
private const val SCREEN_SHADER = """
uniform shader content;
uniform float2 size;
uniform float3x3 quad;
uniform float curvature;
uniform float scanline;
uniform float maskDepth;
uniform float gain;
uniform float snow;
uniform float roll;
uniform float time;
// El lado de un pixel de la habitacion, en pixeles del aparato.
uniform float pixel;
uniform float bloom;
uniform float halo;
uniform float saturation;
uniform half3 tintCol;
// Donde cae la esquina de la capa dentro de la pantalla, en pixeles del aparato.
uniform float2 gridOff;

/*
 * El color de la señal, antes de que el tubo le haga nada.
 *
 * Va aqui y no al final a proposito: es lo que llega por el cable, asi que las lineas de
 * barrido, la mascara y la nieve tienen que caer ENCIMA de esto. Corregir el color despues
 * tiñe tambien los defectos del tubo, que son del aparato y no de la señal.
 *
 * Quita saturacion y tira a ambar. La television es lo unico con color vivo de un cuarto que
 * es olivas y penumbra, y esa distancia de paleta es la mitad de lo que la separa de la
 * escena; lo otro es que un tubo cansado, con el vidrio sucio y la señal por antena, nunca
 * dio el color limpio de un fichero digital.
 */
half3 grade(half3 c) {
    half3 lum = half3(dot(c, half3(0.299, 0.587, 0.114)));
    return mix(lum, c, half(saturation)) * tintCol;
}

// La rejilla de la SALA, no la de la capa.
//
// Las coordenadas entran medidas desde la esquina de la capa del video, y esa esquina cae en
// una fraccion cualquiera de la pantalla, mientras que el render de la habitacion se amplia
// por cinco desde el origen. Cuantizando sin corregirlo, los bloques del tubo y los del
// cuarto quedan desplazados entre si: cada capa se ve con su propia rejilla, y dos rejillas
// distintas en la misma imagen es justo lo que se lee como una cosa pegada sobre la otra.
float2 gridIndex(float2 p, float step) {
    return floor((p + gridOff) / step);
}

float2 snapGrid(float2 p, float step) {
    return (gridIndex(p, step) + 0.5) * step - gridOff;
}

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// De pixel de la caja a coordenada del tubo, centrada y con el barril ya aplicado.
//
// Va en una funcion porque hace falta DOS veces con rejillas distintas: la senal se muestrea
// en media rejilla de sala y el resplandor de fuera, en la rejilla entera.
float2 tubeCoord(float2 p) {
    float3 q = quad * float3(p.x / size.x, p.y / size.y, 1.0);
    // Lejos del cuadrilatero la homografia puede cruzar el horizonte y devolver el punto
    // reflejado. Se manda fuera de todo en vez de dibujar un fantasma.
    if (q.z < 1e-6) return float2(99.0);
    float2 c = q.xy / q.z * 2.0 - 1.0;
    c.x *= 1.0 + curvature * (c.y * c.y);
    c.y *= 1.0 + curvature * (c.x * c.x);
    return c * (1.0 / (1.0 + curvature * 0.75));
}

// La forma del tubo: cuanto mayor el exponente, mas cuadrada.
//
// A 6 salia demasiado redonda y no pegaba con el hueco del modelo, que es casi recto. A 12
// las esquinas siguen sin ser esquinas —ningun tubo las tiene— pero los lados van rectos
// hasta muy cerca de ellas. Vale 1 justo en el borde.
float tubeShape(float2 c) {
    float2 ad = abs(c);
    return pow(ad.x, 12.0) + pow(ad.y, 12.0);
}

half3 sampleTube(float2 c) {
    return grade(content.eval((clamp(c, -1.0, 1.0) * 0.5 + 0.5) * size).rgb);
}

// Lo que pasa del umbral en una muestra de la floracion. Va corregida de color como el
// resto: sumar el derrame sin corregir devolveria a la imagen el color vivo que se le acaba
// de quitar, solo que alrededor de lo claro.
half3 bloomTap(float2 p) {
    return max(grade(content.eval(p).rgb) - half3(0.55), half3(0.0));
}

// Tramado ordenado de 4x4, el mismo que lleva horneado el render de la sala.
//
// El resplandor es una rampa muy suave sobre valores muy bajos, y la sala esta cuantizada a
// los 32 niveles por canal de la consola: sin tramar salen anillos concentricos. La matriz
// se construye anidando dos de 2x2, igual que en tools/psx-post.sh.
float bayer4(float2 p) {
    float2 a = mod(p, 2.0);
    float2 b = mod(floor(p * 0.5), 2.0);
    float hi = 2.0 * b.x + 3.0 * b.y - 4.0 * b.x * b.y;
    float lo = 2.0 * a.x + 3.0 * a.y - 4.0 * a.x * a.y;
    return (hi + 4.0 * lo) / 16.0 - 0.5;
}

/*
 * Lo que la television derrama FUERA del cristal.
 *
 * Medido en el aparato: dentro del tubo la imagen iba a 70 sobre 255 y el bisel, a un pixel,
 * a 1. Setenta a uno en un pixel no lo hace ninguna luz real, y es lo que dejaba el video
 * como una pegatina por mucho que se le trabajara el interior.
 *
 * No lo arregla el pase `tv` de la sala, que es luz repartida por la habitacion: el bisel es
 * coplanar con la pantalla, no la ve, y ninguna luz colocada en Blender lo alcanza. Y
 * tampoco lo arregla mas floracion dentro, que solo mancha hacia dentro.
 */
half4 glowOutside(float2 fragIn) {
    if (halo <= 0.0) return half4(0.0);

    // En la rejilla ENTERA de la sala, no en la media del video: esto es luz cayendo sobre
    // una pared, no señal, y a media rejilla se veia mas fino que el muro que la recibe.
    float2 c = tubeCoord(snapGrid(fragIn, pixel));
    float se = tubeShape(c);
    if (se <= 1.0) return half4(0.0);

    // Cuanto se ha pasado del borde. La raiz doceava de la superelipse da un radio que vale
    // 1 en el borde y crece igual en todas las direcciones.
    float r = pow(se, 1.0 / 12.0);
    // El resplandor se mide con una forma MAS REDONDA que el tubo.
    //
    // Con el mismo exponente 12 el halo salia con el contorno de un rectangulo redondeado
    // dibujado a proposito, con sus tramos rectos y todo. La luz no conserva la forma de la
    // fuente al alejarse: se va redondeando. Con exponente 6 sigue siendo rectangular de
    // cerca y se suaviza en las esquinas, que es lo que hace de verdad.
    // (Y no hace falta comprobar que no se meta dentro del cristal: la norma de exponente
    // menor siempre es la mayor de las dos, asi que el halo nunca empieza antes del borde.)
    float2 ad = abs(c);
    float d = pow(pow(ad.x, 6.0) + pow(ad.y, 6.0), 1.0 / 6.0) - 1.0;
    // El alcance va fijo y no en el tema: es dispersion del vidrio, una propiedad del
    // cristal, no una forma de calibrar el aparato. 0.42 del semiancho del tubo son unos
    // siete pixeles de sala, que es lo que ocupa el bisel y algo de mueble.
    float reach = 0.42;
    if (d > reach) return half4(0.0);

    // El color sale de un rayo que ATRAVIESA la pantalla, no del borde.
    //
    // Empezo mirando solo el borde, y con la caratula de un juego —que casi siempre tiene el
    // marco oscuro— el bisel se quedaba en 12 sobre 255 teniendo la imagen a 75 al lado. La
    // razon es que un bisel no ve el borde del tubo: ve el fosforo entero, mas de cerca la
    // parte que tiene delante. Por eso son cuatro muestras desde el borde hasta el centro,
    // pesadas hacia el borde pero sin descartar el resto.
    //
    // Y por eso no vale un color medio global: una escena con cielo arriba y sombra abajo
    // tiene que manchar distinto arriba que abajo, que es lo que hace el tubo de verdad.
    float2 e = c / r;
    half3 g = sampleTube(e * 0.97) * half(0.35)
            + sampleTube(e * 0.75) * half(0.30)
            + sampleTube(e * 0.45) * half(0.20)
            + sampleTube(float2(0.0)) * half(0.15);

    // Caida cuadratica con algo de cola lineal.
    //
    // Al cubo el resplandor se pegaba tanto al cristal que el mueble de debajo seguia a cero;
    // lineal del todo daba una niebla uniforme, que es el aspecto de un filtro y no el de una
    // lampara. La mezcla cae rapido al principio y deja rastro hasta el final.
    float t = 1.0 - d / reach;
    float a = (t * t * 0.75 + t * 0.25) * halo;
    g += half3(half(bayer4(gridIndex(fragIn, pixel)) / 32.0));

    // Premultiplicado, que es como espera el color un RenderEffect.
    return half4(clamp(g, 0.0, 1.0) * half(a), half(a));
}

half4 main(float2 fragIn) {
    // Dentro del tubo se trabaja en rejilla, no a resolucion del aparato.
    //
    // La sala se rinde a 384 de ancho y se amplia por cinco, asi que cada pixel suyo es un
    // bloque de 5x5 en pantalla. Dibujar aqui a resolucion del aparato dejaba el video y el
    // ruido cinco veces mas finos que todo lo que los rodea, y por eso se sentian pegados
    // encima en vez de formar parte de la escena.
    //
    // Pero se separan dos cosas. Los DEFECTOS del tubo —la mota, la mascara, las lineas de
    // barrido— van en la rejilla de la sala exacta, porque pertenecen a la habitacion. La
    // SENAL va en media rejilla: a 33 pixeles de ancho el juego dejaba de leerse, y una
    // senal con mas detalle que el grano que la ensucia es justo lo que pasa en una tele.
    float signal = pixel * 0.5;
    float2 frag = snapGrid(fragIn, signal);

    // De la caja a la pantalla: se deshace la perspectiva para saber que punto del video
    // corresponde a este pixel.
    float2 c = tubeCoord(frag);
    float se = tubeShape(c);
    // Fuera del cristal no hay nada que recortar, hay luz que sale.
    if (se > 1.0) return glowOutside(fragIn);

    // Arrastre vertical del cambio de canal, con vuelta por arriba.
    float ny = fract((c.y * 0.5 + 0.5) + roll);
    float2 rc = float2(c.x, ny * 2.0 - 1.0);
    // El desgarro va con el CUADRADO de la nieve, la mota no.
    //
    // Son dos averias distintas: las lineas descolocadas son perdida de sincronismo y solo
    // pasan al cambiar de canal, mientras que la mota es una antena que capta flojo y esta
    // siempre. Con el mismo factor, el ruido de fondo salia rasgando la imagen.
    rc.x += (hash(float2(gridIndex(frag, pixel).y, floor(time * 24.0))) - 0.5) * 0.16 * snow * snow;

    float2 src = (clamp(rc, -1.0, 1.0) * 0.5 + 0.5) * size;
    half4 col = half4(grade(content.eval(src).rgb), 1.0);

    // Floracion.
    //
    // Un blanco en un tubo no termina donde termina: el haz sobreexcita el fosforo y el
    // vidrio dispersa, asi que mancha lo que tiene alrededor. Se toman ocho muestras en
    // corona y solo cuenta lo que pasa del umbral, porque solo lo MUY claro florece: si
    // floreciera todo seria un desenfoque, no una pantalla encendida.
    if (bloom > 0.0) {
        float br = signal * 2.0;
        half3 bl = bloomTap(src + float2( br, 0.0))
                 + bloomTap(src + float2(-br, 0.0))
                 + bloomTap(src + float2(0.0,  br))
                 + bloomTap(src + float2(0.0, -br))
                 + (bloomTap(src + float2( br,  br))
                 +  bloomTap(src + float2(-br,  br))
                 +  bloomTap(src + float2( br, -br))
                 +  bloomTap(src + float2(-br, -br))) * half(0.7);
        col.rgb += bl * half(bloom * 0.42);
    }

    // Las lineas de barrido van por pixel de habitacion: a una cada tres pixeles del aparato
    // eran mas finas que la propia trama de la sala y desaparecian en el ruido.
    //
    // Y ALTERNAN de campo en cada fotograma. Un tubo no dibuja la imagen entera de una vez:
    // hace un barrido con las lineas pares y otro con las impares. Ese parpadeo entre los
    // dos campos es lo que hace que una pantalla de tubo se vea viva y no como un video
    // pegado, y es lo unico de todo esto que no se puede fingir con una imagen fija.
    float field = mod(floor(time * 60.0), 2.0);
    float row = gridIndex(fragIn, pixel).y + field;
    float s = sin(row * 1.5707963);
    col.rgb *= half3(1.0 - scanline * s * s);

    float m = mod(gridIndex(fragIn, pixel).x, 3.0);
    half3 tint = m < 1.0 ? half3(1.0, 0.75, 0.75)
               : m < 2.0 ? half3(0.75, 1.0, 0.75)
                         : half3(0.75, 0.75, 1.0);
    col.rgb *= mix(half3(1.0), tint, half(maskDepth));
    // Vineteado y, sobre todo, caida en el borde.
    //
    // Un tubo no corta la imagen en seco contra el marco: el fosforo se apaga hacia el
    // borde. Sin esto la pantalla parecia un recorte pegado dentro de la television; con
    // ello el video se funde con el propio cristal.
    col.rgb *= half3(1.0 - 0.18 * dot(c, c));
    col.rgb *= half3(1.0 - smoothstep(0.70, 1.0, se) * 0.92);
    col.rgb *= half3(gain);

    // La banda de refresco.
    //
    // En un tubo el haz recorre la pantalla de arriba abajo y el fosforo se va apagando por
    // detras, asi que en cualquier instante hay una franja mas oscura bajando. A 60 Hz el
    // ojo no la ve, pero es exactamente lo que aparece al grabar un televisor, y es la
    // segunda firma temporal del tubo despues del entrelazado.
    float bandPos = fract(time * 0.9);
    float dband = abs(fract((c.y * 0.5 + 0.5) - bandPos + 0.5) - 0.5);
    col.rgb *= half3(1.0 - 0.13 * (1.0 - smoothstep(0.0, 0.20, dband)));

    if (snow > 0.0) {
        // La mota se hace con DOS escalas, no con una.
        //
        // Una sola celda de tamano fijo da un patron regular: fina se lee como suciedad de
        // pantalla moderna, y gruesa se lee como un tablero de ajedrez. El grano de verdad
        // tiene estructura a varias escalas a la vez, y basta con sumar dos para que deje
        // de verse la rejilla.
        float2 ia = gridIndex(frag, pixel);
        float na = hash(float2(ia.x + time * 811.0, ia.y + time * 379.0));
        float2 ib = gridIndex(frag, pixel * 2.0);
        float nb = hash(float2(ib.x + time * 149.0, ib.y + time * 263.0));
        float n = na * 0.55 + nb * 0.45;
        half3 grain = half3(half(n * 0.85 + 0.08));
        grain *= half3(1.0 - scanline * s * s * 0.6);
        col.rgb = mix(col.rgb, grain, half(snow));
    }

    return half4(clamp(col.rgb, 0.0, 1.0), 1.0);
}
"""

/**
 * La consola girando, plantada dentro de la sala.
 *
 * No va desenfocada aunque el cuarto sí lo esté, y esa es toda la idea: la sala se difumina
 * mientras eliges consola precisamente para que lo único nítido sea la consola. Enfocar es
 * lo que dice dónde estás, sin escribirlo.
 *
 * Aparece y desaparece con una atenuación corta. Un objeto que salta a existir en mitad de
 * una habitación se lee como un error de dibujado; uno que se enciende se lee como que la
 * habitación lo ha traído.
 */
@Composable
fun SceneConsole(
    skin: TvSkin,
    video: File?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val where = skin.console ?: return
    val alpha by animateFloatAsState(
        targetValue = if (visible && video != null) 1f else 0f,
        animationSpec = tween(320),
        label = "consoleFade",
    )
    // El ultimo que hubo, para irse con el. Al entrar en una consola el video se va a null
    // en el mismo fotograma en que deja de ser visible, y sin esto el plato desaparecia de
    // golpe en vez de fundirse. En un array y no en un estado: es memoria, no algo que pinte.
    val last = remember { arrayOfNulls<File>(1) }
    if (video != null) last[0] = video
    val shown = video ?: last[0]
    if (alpha <= 0.01f || shown == null) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(maxWidth * where.left, maxHeight * where.top)
                .size(maxWidth * where.width, maxHeight * where.height)
                .graphicsLayer { this.alpha = alpha }
        ) {
            ConsoleTurntable(shown, Modifier.fillMaxSize())
        }
    }
}

/**
 * La misma corrección de color que `grade()` dentro del shader.
 *
 * Existe por duplicado a propósito: el shader corrige la señal que se ve en el cristal, y
 * esto corrige el color con el que esa misma señal alumbra la habitación, que se saca leyendo
 * el fotograma directamente del reproductor y por tanto nunca pasa por el shader. Si se
 * cambia una, hay que cambiar la otra.
 */
private fun gradeTint(c: Color, p: CrtParams): Color {
    val lum = c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f
    // El tinte cambia DE QUÉ COLOR alumbra la televisión, no CUÁNTO.
    //
    // Multiplicar por (1, 0.96, 0.87) y ya está le quita luminancia al cuarto —hasta un 7%
    // con una escena fría— y eso es un cambio de nivel colado dentro de un cambio de color.
    // Devolviendo la luminancia al valor que tenía, el ajuste es puramente de tono: la sala
    // se sigue apagando y encendiendo con lo que se esté viendo, que es lo que debe mandar.
    // (Quitar saturación no hace falta corregirlo: mezclar hacia la luminancia la conserva.)
    val tl = p.tint.red * 0.299f + p.tint.green * 0.587f + p.tint.blue * 0.114f
    val keep = if (tl > 0.001f) 1f / tl else 1f
    fun ch(v: Float, t: Float) =
        ((lum + (v - lum) * p.saturation) * t * keep).coerceIn(0f, 1f)
    return Color(ch(c.red, p.tint.red), ch(c.green, p.tint.green), ch(c.blue, p.tint.blue))
}

/**
 * @param channel changes whenever the set should retune. Null keeps it on static, which is
 *   what the room shows while nobody has picked a game.
 */
@Composable
fun SceneScreen(
    skin: TvSkin,
    channel: Any?,
    video: File?,
    still: File?,
    params: CrtParams,
    modifier: Modifier = Modifier,
    /** Al elegir consola se difumina como el resto de la sala, no solo el fondo. */
    blurred: Boolean = false,
    /**
     * Cuantos pixeles de ancho tiene el render de la sala.
     *
     * Con esto se sabe el tamano de un pixel de la habitacion en pantalla, y todo lo que
     * pasa dentro del tubo se dibuja en esa misma rejilla. Es lo que hace que el video
     * pertenezca a la escena en vez de flotar encima.
     */
    roomWidth: Int = 384,
    onTint: ((Color) -> Unit)? = null,
    /** Si este canal todavia se esta preparando: se avisa dentro del tubo. */
    loading: Boolean = false,
    /** Por donde va esa preparacion, de cero a uno. */
    progress: Float = 0f,
) {
    val quad = skin.quad ?: return
    // Con resplandor la capa va MAS GRANDE que el cuadrilátero, porque entonces la televisión
    // no termina en el cristal y alrededor cae lo que derrama. Sin él se ajusta al
    // cuadrilátero y no se dibuja de más. Las esquinas se miden contra la caja que toque, no
    // siempre contra la envolvente, o el vídeo saldría desplazado dentro del tubo.
    val box = remember(skin.id, skin.corners, params.halo) {
        if (params.halo > 0f) skin.haloBounds(HALO_BOX) else skin.quadBounds
    }
    val inv = remember(skin.id, skin.corners, box) {
        Homography.fromCorners(skin.cornersIn(box))?.inverse()
    } ?: return

    // De cero con cada canal: guardado de uno a otro, el nuevo pasaba sus dos primeros
    // fotogramas con el tiempo del anterior —sintonizado y sin nieve— y cada paso del
    // cursor encendia la caratula nueva un instante antes de la estatica.
    // Desenfocado y sin canal (la lista de consolas) solo hay nieve, y bajo el desenfoque la
    // nieve quieta no se distingue de la que se mueve: el reloj se para. Si no, al ritmo de
    // Motion; el shader de la nieve se recalculaba en cada refresco de la pantalla.
    val clock = rememberMotionClock(key = channel, running = !(blurred && channel == null))
    val rolling by remember(channel) { derivedStateOf { clock.floatValue >= Tune.BLIND * 0.9f } }

    // El reloj no se para al sintonizar.
    //
    // Antes se detenía en cuanto la imagen enganchaba, y con él la nieve: el ruido de fondo
    // se quedaba congelado en un patrón fijo, que es peor que no tenerlo. Sigue corriendo
    // mientras se vea; solo se para bajo el desenfoque sin canal (arriba) y con Ludolog fuera de
    // la vista (ver Motion).

    // El reloj se LEE en el dibujado, no en la composicion.
    //
    // Escribe un valor nuevo en cada fotograma. Leido aqui arriba, cada uno de esos valores
    // invalidaba el composable entero -el tubo, la caratula, el aviso de carga- sesenta
    // veces por segundo y para siempre, aunque ninguna de esas cosas hubiera cambiado.
    // Pasando funciones, la lectura ocurre dentro del bloque de la capa grafica, que es fase
    // de dibujado: se recalcula el efecto, que es lo unico que depende del tiempo, y no se
    // recompone nada.
    val snow = { if (channel == null) 1f else Tune.snow(clock.floatValue) }
    val roll = { if (channel == null) 0f else Tune.roll(clock.floatValue) }
    // El tiempo se envuelve antes de entrar al shader: creciendo sin limite, el hash del
    // ruido pierde precision y la nieve acaba congelandose.
    val time = { clock.floatValue % 600f }
    // La unica lectura que SI hace falta en composicion, porque decide si se compone la
    // caratula. Derivada, asi que solo avisa cuando el booleano cambia de valor, no en cada
    // fotograma.
    val tuned by remember(channel) {
        derivedStateOf { channel != null && Tune.snow(clock.floatValue) < 0.9f }
    }

    val blur by animateFloatAsState(
        targetValue = if (blurred) BLUR_DP else 0f,
        animationSpec = tween(420),
        label = "screenBlur",
    )

    BoxWithConstraints(
        modifier.fillMaxSize().graphicsLayer {
            renderEffect =
                if (blur <= 0.1f) null
                else RenderEffect.createBlurEffect(
                    blur * density, blur * density, Shader.TileMode.DECAL,
                ).asComposeRenderEffect()
        }
    ) {
        val b = box
        // El lado de un pixel de la sala: el ancho total de la pantalla dividido entre los
        // pixeles que tiene el render de la habitacion. Se calcula AQUI porque los marcadores
        // de ambito de Compose no dejan ver maxWidth desde dentro del Box.
        val pixelSize = with(androidx.compose.ui.platform.LocalDensity.current) {
            maxWidth.toPx()
        } / roomWidth
        Box(
            Modifier
                .offset(maxWidth * b.left, maxHeight * b.top)
                .size(maxWidth * b.width, maxHeight * b.height)
        ) {
            ScreenLayer(
                inv = inv,
                params = params,
                snow = snow,
                roll = roll,
                pixel = pixelSize,
                time = time,
                content = {
                    // El tubo va SIEMPRE mudo. Su sonido es la cinta tratada, que
                    // suena por el mismo reproductor que los clics; el mp4 conserva
                    // el suyo dentro y aqui simplemente no se abre esa pista.
                    if (rolling && video != null) VideoSurface(
                        video,
                        muted = true,
                        // El color de la sala sale del fotograma en CRUDO, porque se lee del
                        // reproductor antes de que el shader lo toque. Hay que corregirlo
                        // igual que la señal o la pantalla se ve en ambar apagado mientras
                        // el cuarto se alumbra con el rojo vivo del vídeo original.
                        onTint = onTint?.let { cb -> { c -> cb(gradeTint(c, params)) } },
                    )
                    else if (still != null && tuned) {
                        AsyncImage(
                            model = artModel(still), contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // El aviso de que ese canal se esta preparando.
                    //
                    // Va DENTRO del tubo, no encima de la sala, para que pase por el mismo
                    // shader que todo lo demas: curvado, con sus lineas y su grano. Un
                    // rotulo limpio flotando sobre una television vieja canta muchisimo.
                    //
                    // Letra corriente, pequena y pegada al borde de abajo. No es parte del
                    // decorado ni quiere serlo: es una maquina diciendo que esta ocupada.
                    if (loading) {
                        Box(Modifier.fillMaxSize()) {
                            // La barra arriba, de borde a borde, como la de un aparato que
                            // se esta poniendo al dia. Sobre fondo propio para que se lea
                            // igual con una caratula clara detras.
                            Box(
                                Modifier.fillMaxWidth().height(3.dp)
                                    .align(Alignment.TopStart)
                                    .background(Color(0x40000000)),
                            ) {
                                Box(
                                    Modifier.fillMaxHeight()
                                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                                        .background(Color(0xFF6BFF6B)),
                                )
                            }
                            androidx.compose.material3.Text(
                                "loading...",
                                color = Color(0xFF6BFF6B),
                                fontSize = 7.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                modifier = Modifier.align(Alignment.BottomStart)
                                    .padding(start = 4.dp, bottom = 1.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ScreenLayer(
    inv: Homography,
    params: CrtParams,
    snow: () -> Float,
    roll: () -> Float,
    time: () -> Float,
    pixel: Float,
    content: @Composable () -> Unit,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    // Donde empieza la capa dentro de la ventana. El fondo de la sala se dibuja desde ese
    // mismo origen, asi que es lo que hace falta para que las dos rejillas coincidan.
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val shader = remember {
        if (!crtShaderAvailable) null
        else runCatching { RuntimeShader(SCREEN_SHADER) }
            .onFailure { android.util.Log.e("Ludolog", "shader de pantalla: ${it.message}") }
            .getOrNull()
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer {
                clip = true
                renderEffect =
                    screenEffect(shader, box, inv, params, snow(), roll(), time(), pixel, origin)
            }
            // El negro va DESPUES del graphicsLayer, no antes.
            //
            // El orden de los modificadores decide quien envuelve a quien: puesto antes, el
            // fondo se dibuja FUERA de la capa, la capa se queda sin nada que pintar, Compose
            // se la salta entera y el shader no llega a ejecutarse nunca.
            .background(Color.Black),
    ) { content() }
}

private fun screenEffect(
    shader: RuntimeShader?,
    box: IntSize,
    inv: Homography,
    p: CrtParams,
    snow: Float,
    roll: Float,
    time: Float,
    pixel: Float,
    origin: androidx.compose.ui.geometry.Offset,
): androidx.compose.ui.graphics.RenderEffect? {
    // En el primer fotograma la caja aun no esta medida; el efecto entra en el siguiente.
    if (shader == null || box.width <= 0 || box.height <= 0) return null
    return runCatching {
        shader.setFloatUniform("size", box.width.toFloat(), box.height.toFloat())
        shader.setFloatUniform("quad", inv.toShaderArray())
        shader.setFloatUniform("curvature", p.curvature)
        shader.setFloatUniform("scanline", p.scanline)
        shader.setFloatUniform("maskDepth", p.mask)
        shader.setFloatUniform("gain", p.gain)
        shader.setFloatUniform("snow", snow)
        shader.setFloatUniform("roll", roll)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("pixel", pixel.coerceAtLeast(1f))
        shader.setFloatUniform("bloom", p.bloom)
        shader.setFloatUniform("halo", p.halo)
        shader.setFloatUniform("saturation", p.saturation)
        shader.setFloatUniform("tintCol", p.tint.red, p.tint.green, p.tint.blue)
        shader.setFloatUniform("gridOff", origin.x, origin.y)
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }.onFailure { android.util.Log.e("Ludolog", "uniformes: $it") }.getOrNull()
}

/**
 * El diálogo de la sala: lo que se cuenta de lo elegido, escrito letra a letra.
 *
 * Sustituye a la descripción que iba al pie del panel de la lista. Allí era un bloque de
 * texto más; aquí es un cuadro de diálogo debajo de la consola, escrito con letra de
 * mapa de bits y a máquina, que es como esos juegos contaban lo que era un objeto. La
 * diferencia no es cosmética: un texto que aparece de golpe se lee como una etiqueta, y
 * uno que se escribe delante de ti se lee como que alguien te lo está diciendo.
 *
 * El ajuste de línea se calcula sobre el texto entero y se recorta después, para que la
 * palabra que se está escribiendo no salte de línea a medias.
 */
@Composable
internal fun SceneCaption(skin: TvSkin, details: Details?, modifier: Modifier = Modifier) {
    val where = skin.caption ?: return
    val d = details ?: return
    BoxWithConstraints(modifier.fillMaxSize()) {
        TypedDetails(
            d,
            Modifier
                .offset(maxWidth * where.left, maxHeight * where.top)
                .size(maxWidth * where.width, maxHeight * where.height),
        )
    }
}

/**
 * Lo que se cuenta de algo, escrito a maquina con la fuente de los dialogos: el titulo en el
 * color de acento, el texto letra a letra y el pie detras. Sin caja: el contorno oscuro que
 * cada letra trae de la hoja es lo que lo separa del fondo. Lo usan la sala, bajo la
 * consola, y el cajon de apps, bajo el telefono o el medallon.
 *
 * El titulo va mas grande salvo cuando es un nombre largo, que a tres no cabria en una
 * linea.
 */
@Composable
internal fun TypedDetails(
    d: Details,
    modifier: Modifier = Modifier,
    lines: Int = 3,
    /**
     * Si el bloque de texto ocupa SIEMPRE `lines` renglones, llenos o no.
     *
     * Para el que va dentro de una caja. Sin esto la caja mide lo que mida el texto, y al
     * recorrer la lista saltaba: una consola de dos lineas y la siguiente de tres cambiaban la
     * altura del marco y, con ella, la de la imagen de arriba. Achicar la letra no lo arregla,
     * porque lo que manda es el numero de renglones y no su tamaņo; reservarlos si.
     *
     * Suelto sobre la sala no hace falta: alli no hay caja que redimensionar y tres renglones
     * reservados serian tres huecos de aire bajo una consola.
     */
    steady: Boolean = false,
) {
    val t = LocalTheme.current
    // Se escribe primero el texto y despues el pie, con el mismo presupuesto de letras.
    val total = d.text.length + 1 + d.footer.length
    var shown by remember(d.title, d.text) { androidx.compose.runtime.mutableIntStateOf(0) }
    val latest by androidx.compose.runtime.rememberUpdatedState(total)
    LaunchedEffect(d.title, d.text) {
        shown = 0
        // Un respiro antes de empezar. Si arranca en el mismo fotograma en que cambia la
        // seleccion, al recorrer la lista deprisa se ve un tartamudeo de primeras letras.
        kotlinx.coroutines.delay(140)
        // Hasta el final del texto de AHORA y no del que habia al empezar. El pie cambia solo
        // cuando llega lo que cuenta —las caratulas se cuentan un momento despues de arrancar,
        // y «0 with art» pasa a «18 with art»—, y con el tope del principio se quedaba sin sus
        // ultimas letras: «18 with ar». Asi que al terminar se espera, por si crece.
        while (true) {
            if (shown < latest) {
                kotlinx.coroutines.delay(22)
                shown++
            } else {
                androidx.compose.runtime.snapshotFlow { latest }.first { it > shown }
                    .let { }
            }
        }
    }
    // La lamina de pixeles es de los temas que dibujan con pixeles, y de nadie mas.
    //
    // Esto no solo sale en la sala: el cajon de apps lo usa tambien, y ahi un tema de palo
    // seco escribia de pronto con la letra de los dialogos de una consola de mil novecientos
    // noventa y siete. Se escribe igual —letra a letra, al mismo ritmo— con la letra que el
    // tema use en todo lo demas.
    // Y solo si la lamina esta: viene con el tema que la usa, no con el APK.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pixel = t.pixelText && remember { ReFont.load(ctx) != null }
    Column(modifier) {
        if (pixel) {
            ReText(d.title, color = t.accent, scale = if (d.title.length > 28) 2 else 3, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            ReText(d.text, color = t.ink, visibleChars = shown, maxLines = lines)
            Spacer(Modifier.height(4.dp))
            ReText(
                d.footer, color = t.ink.copy(alpha = .78f),
                visibleChars = (shown - d.text.length - 1).coerceAtLeast(0), maxLines = 1,
            )
        } else {
            // Sin titulo, su renglon se lo queda el texto: mas grande, que a once puntos era de lo
            // que peor se leia. Lo pide la caja de texto de la lista, donde el titulo repetia la
            // fila elegida (ver MenuLayout).
            val roomy = d.title.isEmpty()
            if (!roomy) {
                androidx.compose.material3.Text(
                    d.title, color = t.accent, fontSize = 15.sp, fontFamily = MenuDisplay,
                    letterSpacing = t.titleTracking, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }
            androidx.compose.material3.Text(
                d.text.take(shown), color = t.ink,
                fontSize = if (roomy) 13.sp else 11.sp, lineHeight = if (roomy) 18.sp else 15.sp,
                fontFamily = MenuBody, maxLines = lines, overflow = TextOverflow.Ellipsis,
                minLines = if (steady) lines else 1,
            )
            Spacer(Modifier.height(if (roomy) 6.dp else 4.dp))
            androidx.compose.material3.Text(
                d.footer.take((shown - d.text.length - 1).coerceAtLeast(0)),
                color = t.ink.copy(alpha = .70f), fontSize = if (roomy) 11.5.sp else 10.sp,
                fontFamily = MenuBody, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * El fondo plano de un tema que no tiene sala.
 *
 * Una imagen quieta, o un video corto en bucle. Nada mas: no se tiñe con lo que se esté
 * viendo, no se desenfoca al cambiar de nivel y no tiene televisión que sintonizar. Es lo
 * contrario de [SceneBackground], que es una habitación con tres pases de luz que responden
 * al juego elegido, y esa diferencia es a propósito: hay temas cuyo asunto es la quietud.
 *
 * Encima va un velo del color de fondo del tema. Sin él, el texto del menú se apoya en lo que
 * haya debajo y la legibilidad depende de la foto que cada cual ponga; con él, el fondo baja a
 * ser un fondo y el menú sigue siendo el menú. Sale del tema y no es negro fijo, así que en el
 * tema de papel el velo es papel.
 */
@Composable
internal fun Backdrop(file: File, modifier: Modifier = Modifier) {
    val ground = LocalTheme.current.ground
    val veil = LocalTheme.current.backdropVeil
    Box(modifier.fillMaxSize()) {
        if (file.extension.equals("mp4", ignoreCase = true)) {
            // VideoSurface ya repite y ya va callado; aqui no hay nada que decidir.
            VideoSurface(file)
        } else {
            coil3.compose.AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(ground.copy(alpha = veil)))
    }
}


/**
 * El marco de la sala: la imagen a escala ENTERA, pegada a la derecha y centrada en alto, y lo
 * que no cabe, fuera. Las cuatro capas —fondo, tele, consola y texto— van dentro y se colocan en
 * fracciones de ESTE marco, no de la pantalla.
 *
 * Antes la sala se estiraba a la pantalla entera: en 16:9 a 1920x1080 era su x5 exacto, pero en
 * 4:3 o 1:1 se aplastaba, el pixel dejaba de ser cuadrado, y la tele y la consola, colocadas en
 * fracciones de pantalla, se aplastaban con ella. Ahora la escala es la entera que llena el alto
 * (redondeando hacia arriba, para que nunca quede banda arriba ni abajo):
 *
 * - mas estrecha que la sala (4:3, 1:1): se recorta por la izquierda, que queda detras de la
 *   lista; la tele y la consola, a la derecha, siguen enteras;
 * - mas ancha (20:9): sobra a la izquierda, y ahi queda el color de fondo del tema, detras de
 *   la lista.
 *
 * En 16:9 a 1920x1080 no cambia nada: x5, sin recorte.
 */
@Composable
internal fun RoomFrame(image: File, focus: Float? = null, content: @Composable () -> Unit) {
    val src = remember(image) { roomSize(image) }
    val ground = LocalTheme.current.ground
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().clipToBounds().background(ground).layout { measurable, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val scale = kotlin.math.ceil(h / src.height.toFloat()).toInt().coerceAtLeast(1)
            val rw = src.width * scale
            val rh = src.height * scale
            // Pegada a la derecha. En una pantalla estrecha, con lo que importa —la tele, `focus`,
            // en fraccion de la sala— centrado en el hueco que deja la lista: pegada a la derecha
            // la tele quedaba debajo de la lista en 4:3 y 1:1. Sin dejar nunca hueco a la derecha.
            var x = w - rw
            if (focus != null && w.toFloat() / h < WIDE_ASPECT) {
                val list = with(density) { listWidth((w / density.density).dp, instrument = false).toPx() }
                val wanted = (list + (w - list) / 2f - focus * rw).toInt()
                x = wanted.coerceIn(w - rw, 0)
            }
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(rw, rh))
            layout(w, h) { placeable.place(x, (h - rh) / 2) }
        },
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { content() }
    }
}

/** El tamaño en pixeles de la imagen de la sala, sin descodificarla; 384x216 si no se puede leer. */
private fun roomSize(f: File): IntSize = runCatching {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.path, o)
    if (o.outWidth > 0 && o.outHeight > 0) IntSize(o.outWidth, o.outHeight) else null
}.getOrNull() ?: IntSize(384, 216)
