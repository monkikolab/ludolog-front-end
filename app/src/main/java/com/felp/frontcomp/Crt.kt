package com.felp.frontcomp

import android.graphics.RenderEffect
import android.media.MediaMetadataRetriever
import android.graphics.RuntimeShader
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.os.Build
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The preview panel as a CRT television.
 *
 * The picture is a real gameplay recording put through a shader, not a scanline PNG laid
 * on top. That distinction is the whole point: a static overlay cannot bend the image,
 * cannot brighten what is behind a phosphor stripe, and reads as a sticker the moment
 * anything moves.
 *
 * The set around it is a still picture and only the screen is alive, which is the only
 * reason this is affordable: one image serves every console, and the thing that has to
 * move was already a video.
 */

/* ------------------------------------------------------------------- cambio de canal */

/**
 * Where a channel change is, in seconds since it started.
 *
 * The timings are built around the wait the video already needed: a player cannot be torn
 * down and another opened instantly, and rather than hide that gap the set shows what a
 * television shows in exactly that situation. The compromise became the effect.
 */
internal object Tune {
    /** Hasta aquí, nieve pura: es cuando no hay imagen que enseñar de todos modos. */
    const val BLIND = 0.50f
    /** Y aquí ya está estable. */
    const val SETTLED = 0.82f

    /**
     * La nieve nunca llega a cero.
     *
     * Una antena que sintoniza bien del todo es cosa de la televisión digital. Dejar un
     * resto de ruido es lo que quita a la imagen ese aspecto de fichero de vídeo puesto
     * encima de una foto: el juego y la sala pasan a compartir el mismo defecto.
     */
    const val FLOOR = 0.22f

    fun snow(t: Float): Float = when {
        t < BLIND -> 1f
        t < SETTLED -> 1f - (t - BLIND) / (SETTLED - BLIND) * (1f - FLOOR)
        else -> FLOOR
    }

    /**
     * El arrastre vertical.
     *
     * Da vueltas deprisa al principio y se va frenando, que es cómo se comporta un tubo
     * cuando pierde el enganche vertical y lo recupera.
     */
    fun roll(t: Float): Float {
        val amp = (1f - t / SETTLED).coerceIn(0f, 1f)
        if (amp <= 0f) return 0f
        return ((t * 2.6f) % 1f) * amp
    }

    fun over(t: Float): Boolean = t >= SETTLED
}

/**
 * Barrel distortion, scanlines, an aperture-grille mask, a vignette, and the channel change.
 *
 * The scanline and mask periods are in real device pixels rather than in fractions of the
 * image, because that is what they are on a real tube: the stripes belong to the screen,
 * not to what is being shown, so they must not scale with the window.
 */
private const val CRT_SHADER = """
uniform shader content;
uniform float2 size;
uniform float curvature;
uniform float scanline;
uniform float maskDepth;
uniform float gain;
uniform float snow;
uniform float roll;
uniform float time;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

half4 main(float2 frag) {
    float2 uv = frag / size;
    float2 c = uv * 2.0 - 1.0;

    // Cada eje se estira en funcion del otro: eso es lo que da el abombado de cristal en
    // vez de un simple zoom hacia fuera.
    c.x *= 1.0 + curvature * (c.y * c.y);
    c.y *= 1.0 + curvature * (c.x * c.x);
    // Se vuelve a meter hacia dentro, porque si no los bordes de la imagen se salen del
    // area muestreable y se pierden.
    c *= 1.0 / (1.0 + curvature * 0.75);

    // El tubo no es un rectangulo ni una elipse, es una superelipse: esquinas redondeadas
    // con los lados casi rectos.
    float2 ad = abs(c);
    float se = pow(ad.x, 6.0) + pow(ad.y, 6.0);
    // Transparente, no negro: el efecto puede acabar aplicandose a un nodo mayor que el
    // panel, y un negro opaco ahi fuera tapa media interfaz.
    if (se > 1.0) return half4(0.0, 0.0, 0.0, 0.0);

    // Arrastre vertical con vuelta por arriba, mas el desgarro horizontal de las lineas
    // mientras la imagen no ha enganchado.
    float2 rc = c;
    float ny = fract((c.y * 0.5 + 0.5) + roll);
    rc.y = ny * 2.0 - 1.0;
    rc.x += (hash(float2(floor(frag.y / 3.0), floor(time * 24.0))) - 0.5) * 0.16 * snow;

    float2 src = (clamp(rc, -1.0, 1.0) * 0.5 + 0.5) * size;
    half4 col = content.eval(src);

    // Lineas de barrido, con periodo en pixeles fisicos.
    float s = sin(frag.y * 1.0471975);           // pi / 3 -> una linea cada 3 px
    col.rgb *= half3(1.0 - scanline * s * s);

    // Mascara de rejilla: cada subpixel deja pasar sobre todo su color.
    float m = mod(frag.x, 3.0);
    half3 tint = m < 1.0 ? half3(1.0, 0.75, 0.75)
               : m < 2.0 ? half3(0.75, 1.0, 0.75)
                         : half3(0.75, 0.75, 1.0);
    col.rgb *= mix(half3(1.0), tint, half(maskDepth));

    // Vineteado y caida del borde del tubo.
    col.rgb *= half3(1.0 - 0.20 * dot(c, c));
    col.rgb *= half3(1.0 - smoothstep(0.80, 1.0, se) * 0.55);
    col.rgb *= half3(gain);

    // La nieve. Va despues del tubo para que tambien se curve y se raye: la estatica sale
    // del mismo fosforo que la imagen.
    if (snow > 0.0) {
        float n = hash(float2(frag.x + time * 811.0, frag.y + time * 379.0));
        half3 grain = half3(half(n * 0.85 + 0.08));
        grain *= half3(1.0 - scanline * s * s * 0.6);
        col.rgb = mix(col.rgb, grain, half(snow));
    }

    return half4(clamp(col.rgb, 0.0, 1.0), 1.0);
}
"""

/** The shader needs API 33; below that the picture simply plays clean. */
internal val crtShaderAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * The whole set: housing, screen, and what is on it.
 *
 * @param channel changes whenever the set should retune. Anything works; only equality
 *   matters.
 */
@Composable
fun CrtTv(
    channel: Any?,
    video: File?,
    cover: File?,
    label: String,
    params: CrtParams,
    skin: TvSkin,
    /** Si el tubo suena. Mudo por defecto: un giro de consola no tiene por que sonar. */
    sound: Boolean = false,
    /** Volumen del panel, de cero a uno. La television esta al fondo del cuarto. */
    level: Float = 1f,
    modifier: Modifier = Modifier,
) {
    /**
     * Segundos desde que empezó el cambio de canal; grande = ya terminó.
     *
     * De cero con cada canal, que es donde lo pone el efecto de abajo: guardado de uno a otro,
     * el nuevo pasaba dos fotogramas con el «ya terminó» del anterior y se veia un instante
     * antes de la nieve. Ver SceneScreen.
     */
    var t by remember(channel) { mutableFloatStateOf(0f) }
    /** El vídeo no arranca hasta que la nieve lo tapa, y hasta entonces no hay nada que abrir. */
    var rolling by remember(channel) { mutableStateOf(false) }

    LaunchedEffect(channel) {
        val t0 = withFrameNanos { it }
        var elapsed = 0f
        while (!Tune.over(elapsed)) {
            elapsed = (withFrameNanos { it } - t0) / 1_000_000_000f
            t = elapsed
            // Se abre el reproductor en plena nieve, que es donde no se nota.
            if (elapsed >= Tune.BLIND * 0.9f) rolling = true
        }
        t = 999f
        rolling = true
    }

    val bodyFile = remember(skin.id) { skin.imageFile() }
    val glassFile = remember(skin.id) { skin.overlayFile() }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxMod = Modifier.fillMaxSize().aspectRatio(skin.aspect)
        Box(boxMod, contentAlignment = Alignment.TopStart) {
            // 1. El mueble.
            if (bodyFile != null) {
                AsyncImage(
                    model = bodyFile, contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
            } else {
                DrawnTvBody(skin.screen)
            }

            // 2. La pantalla, colocada donde dice el skin.
            ScreenSlot(skin = skin) {
                CrtScreen(
                    video = if (rolling) video else null,
                    cover = cover,
                    sound = sound,
                    level = level,
                    label = label,
                    params = params,
                    snow = Tune.snow(t),
                    roll = Tune.roll(t),
                    time = t,
                )
            }

            // 3. El cristal, si lo hay: va encima para que el reflejo caiga sobre la imagen.
            if (glassFile != null) {
                AsyncImage(
                    model = glassFile, contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Sitúa la pantalla dentro del mueble según las cuatro fracciones del skin. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ScreenSlot(
    skin: TvSkin,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val x = maxWidth * skin.screen.left
        val y = maxHeight * skin.screen.top
        val w = maxWidth * skin.screen.width
        val h = maxHeight * skin.screen.height
        Box(Modifier.offset(x, y).size(w, h)) { content() }
    }
}

/**
 * What is on the screen: the recording when there is one, the cover otherwise.
 *
 * Both go through the same shader on purpose. A set that is a television for some games
 * and a picture frame for others is two things, and the cover shown as a still frame on
 * the tube is what a television does when nothing is playing.
 */
@Composable
private fun CrtScreen(
    video: File?,
    cover: File?,
    sound: Boolean,
    level: Float,
    label: String,
    params: CrtParams,
    snow: Float,
    roll: Float,
    time: Float,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    // El shader se compila una vez y luego solo se le cambian los uniformes: recrear
    // RuntimeShader en cada fotograma seria recompilarlo sesenta veces por segundo.
    val shader = remember { if (crtShaderAvailable) runCatching { RuntimeShader(CRT_SHADER) }.getOrNull() else null }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            // El efecto va en la capa de Compose, no en la vista.
            //
            // `View.setRenderEffect` se aplica sobre un nodo de render que no coincide con
            // los limites del composable —el shader acababa pintando fuera del panel—
            // mientras que un graphicsLayer esta recortado exactamente a esta caja.
            .graphicsLayer {
                clip = true
                renderEffect = effectOf(shader, box, params, snow, roll, time)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (video != null) {
            VideoSurface(video, muted = !sound, level = level)
        } else if (cover != null) {
            AsyncImage(
                model = artModel(cover), contentDescription = label,
                // Y en fosforo, si el tema lo pide. Aqui hace falta decirlo aunque el tubo
                // tenga su propia saturacion: la caratula quieta se dibuja ANTES del cristal y
                // no toda la cadena de dibujo acaba pasando por el sombreador.
                colorFilter = phosphorFilter(),
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun effectOf(
    shader: RuntimeShader?,
    box: IntSize,
    p: CrtParams,
    snow: Float,
    roll: Float,
    time: Float,
): androidx.compose.ui.graphics.RenderEffect? {
    if (shader == null || box.width <= 0 || box.height <= 0) return null
    if (p == CrtParams.Off && snow <= 0f) return null
    return runCatching {
        shader.setFloatUniform("size", box.width.toFloat(), box.height.toFloat())
        shader.setFloatUniform("curvature", p.curvature)
        shader.setFloatUniform("scanline", p.scanline)
        shader.setFloatUniform("maskDepth", p.mask)
        shader.setFloatUniform("gain", p.gain)
        shader.setFloatUniform("snow", snow)
        shader.setFloatUniform("roll", roll)
        shader.setFloatUniform("time", time)
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }.getOrNull()
}

/**
 * Plays a gameplay recording.
 *
 * Uses a TextureView rather than a SurfaceView on purpose: a SurfaceView's picture is
 * composited by the system outside the view's own layer, so a RenderEffect never touches
 * it and the shader would silently do nothing.
 *
 * Y lo mueve ExoPlayer, no MediaPlayer, por un solo motivo: aqui se puede decirle que NO
 * abra la pista de sonido. MediaPlayer no tenia forma —su deselectTrack solo admite
 * subtitulos—, asi que la abria igual, y esa pista, aunque fuera con el volumen a cero,
 * bastaba para que el aparato bajase la salida de todo lo demas mientras duraba el video.
 * De ahi venia el apano de destripar el mp4 antes de tocarlo, que costaba entre medio
 * minuto y minuto y medio por fichero.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoSurface(
    file: File,
    muted: Boolean = true,
    /** Volumen del panel, de cero a uno. La television esta al fondo del cuarto. */
    level: Float = 1f,
    onSize: ((Int, Int) -> Unit)? = null,
    /** El color medio del fotograma actual, para teñir la luz que la tele echa al cuarto. */
    onTint: ((androidx.compose.ui.graphics.Color) -> Unit)? = null,
    /** Efectos en la GPU sobre cada fotograma, dentro del reproductor. Ver PhosphorFx. */
    effects: List<androidx.media3.common.Effect> = emptyList(),
    /** Parado en su fotograma: el giro de consola en reposo (ver Motion). */
    hold: Boolean = false,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().also { p ->
            p.repeatMode = Player.REPEAT_MODE_ALL
            // Los efectos, antes de conectar la pantalla: puestos despues, la cadena de la GPU
            // se quedaba sin salida y el video no llegaba a verse, sin error ninguno.
            if (effects.isNotEmpty()) p.setVideoEffects(effects)
            PanelPlayers.add(p)
        }
    }

    DisposableEffect(Unit) {
        // Lo que falle, al registro: un video que no llega a verse sin dejar rastro se confunde
        // con uno que tarda.
        val errors = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("Ludolog", "video ${file.name}: ${error.errorCodeName}", error)
            }
        }
        player.addListener(errors)
        onDispose {
            player.removeListener(errors)
            PanelPlayers.remove(player)
            runCatching { player.release() }
        }
    }

    // El sonido: apagado del todo, no bajado.
    //
    // Con la pista deshabilitada el selector no le da nada al descodificador de audio, el
    // descodificador no llega a encenderse y no se abre ninguna salida. Eso es lo que hace
    // que el resto de la interfaz no se agache. Cuando SI se quiere sonido —los temas de
    // panel, que no tienen la cinta tratada aparte— se declara igual que los clics, que es
    // el camino que no molesta.
    LaunchedEffect(player, muted, level) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, muted)
            .build()
        if (!muted) {
            player.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SONIFICATION)
                    .build(),
                // Sin pedir el foco de audio: pedirlo es la otra forma de callar a los demas.
                false,
            )
        }
        player.volume = if (muted) 0f else level
    }

    // Con la version del fichero: un video cambiado desde el menu tiene la misma ruta, y sin
    // esto la television seguiria con el de antes. Ver ArtRevisions.
    LaunchedEffect(player, file.path, ArtRevisions.of(file)) {
        runCatching {
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            player.prepare()
            // Quieto si toca estarlo: con el ahorro de bateria, al pasar a otra consola el giro
            // nuevo se ponia a andar igual (revision del 09-10-2026). Preparado, enseña su primer
            // fotograma. Con la aplicacion al fondo, al volver: ver PanelPlayers.startOrHold.
            if (hold) player.pause() else PanelPlayers.startOrHold(player)
        }
    }

    // En reposo, quieto en el fotograma en que estaba; con la primera tecla sigue.
    LaunchedEffect(player, hold) {
        if (hold) runCatching { player.pause() }
        else runCatching { PanelPlayers.startOrHold(player) }
    }

    var view by remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(player, onSize) {
        val cb = onSize
        if (cb == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) cb(size.width, size.height)
            }
            // Con efectos en la GPU (ver PhosphorFx) el reproductor no avisa del tamaño: el
            // primer fotograma llega y el panel seguia esperando, tapado. Se toma entonces del
            // formato del video, que es el mismo porque los efectos no lo cambian.
            override fun onRenderedFirstFrame() {
                val f = player.videoFormat ?: return
                if (f.width > 0 && f.height > 0) cb(f.width, f.height)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    /*
     * El color se saca leyendo el fotograma a 8x8 píxeles, cinco veces por segundo.
     *
     * Suena bruto y es justo lo contrario: 64 píxeles es exactamente lo que se necesita para
     * un promedio, y hacerlo cinco veces por segundo basta porque la luz de una habitación
     * no cambia más rápido que eso. La alternativa —un paso extra de shader para reducir la
     * imagen— costaría más y daría el mismo número.
     */
    // Solo con Ludolog delante (ver Motion): el bucle va con delay y no con los fotogramas, asi
    // que seguia leyendo la GPU cinco veces por segundo detras del emulador, compitiendo con el
    // juego por ella.
    val away by Motion.away
    LaunchedEffect(view, onTint, away) {
        val v = view ?: return@LaunchedEffect
        if (onTint == null || away) return@LaunchedEffect
        val small = android.graphics.Bitmap.createBitmap(
            8, 8, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val px = IntArray(64)
        while (true) {
            kotlinx.coroutines.delay(200)
            // `getBitmap(bitmap)` devuelve SIEMPRE el mapa que se le pasa, tambien cuando
            // la superficie todavia no existe: entonces no copia nada y lo que se promedia
            // son los ceros con los que nacio, o sea negro. Quien dice de verdad si hay
            // imagen es `isAvailable`; el `!= null` de antes era una comprobacion que no
            // comprobaba nada —ese metodo no devuelve nulo nunca— y el compilador lo decia.
            if (!v.isAvailable) continue
            if (runCatching { v.getBitmap(small) }.isFailure) continue
            runCatching {
                small.getPixels(px, 0, 8, 0, 0, 8, 8)
                var r = 0L
                var g = 0L
                var b = 0L
                for (p in px) {
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                // El promedio se sube 1.8 veces: el resplandor se rindió con la pantalla a
                // tope, y el fotograma medio de un juego está bastante por debajo de blanco.
                // Sin esto la habitación saldría siempre más oscura que el render.
                fun ch(v2: Long) = ((v2 / 64f / 255f) * 1.8f).coerceIn(0.05f, 1f)
                onTint(androidx.compose.ui.graphics.Color(ch(r), ch(g), ch(b)))
            }
        }
    }

    AndroidView(
        factory = { c -> TextureView(c).also { view = it }.apply { isOpaque = false } },
        update = { v -> player.setVideoTextureView(v) },
        modifier = Modifier.fillMaxSize(),
    )
}

/* ------------------------------------------------------------- giro de consola */

/**
 * Nearest-neighbour upscaling, done in the shader.
 *
 * The turntables are stored at twice their drawing resolution to keep them small, which
 * only works if whoever plays them does not smooth the picture on the way up. Everything
 * between the file and the screen — the decoder, the GPU, Compose — interpolates by
 * default, and that interpolation dissolves exactly the pixelation the render was built
 * around.
 *
 * By the time this runs the picture has already been stretched to the box and blurred.
 * Sampling at the centre of each source pixel's block recovers the original colour,
 * because that is the one point where bilinear interpolation returns a texel untouched.
 */
private const val SNAP_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 src;
// 1 si la mitad de abajo del video es la mascara de recorte. Se llama `masked` y no
// `packed` porque esa ultima es palabra reservada de SkSL: el shader no compilaba y, sin
// shader, lo que se veia era el fotograma entero con la mascara debajo.
uniform float masked;
// 1 si el muestreo se clava en la rejilla del fichero.
//
// Con el video MAS FINO que la caja, clavarlo es lo que hace que el pixel se vea como un
// bloque en vez de deshacerse en un degradado. Con el video MAS GORDO que la caja es al
// reves: cada fragmento cogeria un pixel y tiraria los de al lado, o sea uno de cada dos en
// un giro al doble de resolucion, y en una imagen de caracteres lo que se cae son los
// trazos finos. Ahi se muestrea suave y el escalado lo hace quien sabe.
uniform float snap;
// El fosforo, ya con su ganancia, y 1 si hay que usarlo.
//
// Un giro puede venir en color —una consola rendida— o ser una sola senal, como estos de
// caracteres. En el segundo caso la imagen no tiene color propio que respetar: tiene
// claridad, y el color lo pone el tema, igual que en las caratulas.
uniform float3 phosphor;
uniform float mono;

half4 main(float2 frag) {
    // Dos escalas distintas, y confundirlas deja la consola invisible.
    //
    // El VIDEO ENTERO —mascara incluida— es lo que se estira hasta llenar la caja, asi que
    // un pixel suyo mide size/src en la caja: eso es `toBox`, y con eso se muestrea.
    // La IMAGEN es solo la mitad de arriba, y es la que se reparte por toda la caja: por eso
    // el pixel de imagen que toca a cada fragmento sale de `pic`, no de `src`.
    //
    // Usando el mismo numero para las dos cosas, el muestreo de la imagen se iba a la mitad
    // de abajo del fotograma —o sea, a la mascara— y la mascara se iba fuera del video: todo
    // salia con alfa cero y la consola desaparecia sin dejar rastro.
    float2 pic = src;
    if (masked > 0.5) { pic.y = src.y * 0.5; }
    float2 toBox = size / src;

    // El punto de la IMAGEN que le toca a este fragmento, en pixeles del fichero. Clavado al
    // centro de su celda o tal cual, segun `snap`.
    float2 at = frag / size * pic;
    if (snap > 0.5) { at = floor(at) + 0.5; }

    half4 c = content.eval(at * toBox);

    half a;
    if (masked > 0.5) {
        // El recorte sale de la mascara, no se adivina.
        //
        // Un mp4 no lleva canal alfa, asi que antes se sacaba de la luminancia: lo oscuro se
        // volvia transparente. Con una consola clara cuela; con una negra, no — la
        // PlayStation 2 y el portatil salian agujereados justo por donde son negros de
        // verdad. Y no hay umbral posible, porque el negro del fondo y el negro de la
        // carcasa son el mismo negro. El alfa se conoce exacto al rendir, asi que viaja
        // dentro del propio video.
        //
        // Umbral en la mitad y no una rampa: el render va sin antialiasing, o sea que la
        // mascara original solo vale 0 o 255. Todo lo que quede en medio lo ha puesto el
        // codificador, y cortar por 0.5 se lo come sin tocar la silueta.
        half m = content.eval((at + float2(0.0, pic.y)) * toBox).r;
        a = step(half(0.5), m);
    } else {
        // Sin mascara queda el metodo viejo, para un mp4 que no venga de este guion. La
        // ultima fila se tira: llega con basura del codificador —medido sobre el giro de la
        // PlayStation, 4,45 de media contra 0,6 de la de encima— y se volvia una linea de
        // puntos colgando bajo la consola.
        if (at.y >= pic.y - 2.0) return half4(0.0);
        half lum = max(c.r, max(c.g, c.b));
        a = smoothstep(0.0, 0.05, lum);
    }

    half3 rgb = c.rgb;
    if (mono > 0.5) {
        half y = dot(c.rgb, half3(0.299, 0.587, 0.114));
        rgb = half3(phosphor) * y;
    }
    // AGSL trabaja con alfa premultiplicado.
    return half4(rgb * a, a);
}
"""

/**
 * A console turning on itself.
 *
 * No tube here: a console is an object sitting on a table, not something being shown on a
 * television, and putting it behind glass would say the wrong thing.
 */
@Composable
fun ConsoleTurntable(file: File, modifier: Modifier = Modifier) {
    // El fosforo del tema, si lo pide. Un giro convertido a caracteres es una sola senal —no
    // tiene color propio que respetar— y en una pantalla de un solo fosforo tiene que salir
    // del mismo color que las caratulas, o son dos aparatos distintos en la misma pantalla.
    val tint = LocalTheme.current.let { if (it.phosphor) it.accent else null }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // El tamaño del vídeo NO se olvida al cambiar de consola.
    //
    // Olvidándolo, durante un fotograma valía cero, y sin tamaño no hay shader: el efecto se
    // quedaba en nulo y lo que se veía era el rectángulo del vídeo en crudo, con su fondo
    // negro, parpadeando entre consola y consola. Guardando el último, el shader nunca se
    // queda sin uniformes.
    var source by remember { mutableStateOf(IntSize.Zero) }
    // Y hasta que el reproductor no dice que ha preparado el fichero nuevo, esto no se
    // dibuja. En ese hueco la superficie todavía tiene lo de antes, o nada.
    var ready by remember(file.path) { mutableStateOf(false) }
    val shader = remember {
        if (!crtShaderAvailable) null
        else runCatching { RuntimeShader(SNAP_SHADER) }
            .onFailure { android.util.Log.e("Ludolog", "shader del giro: " + it.message) }
            .getOrNull()
    }
    // Si hay lineas que sacar de la consola, donde esta dentro de su cuadro. Solo entonces:
    // medirla es leer un fotograma, y sin nadie que lo pida no hace falta.
    val callouts = LocalCalloutTarget.current != null
    var piece by remember(file.path) { mutableStateOf<Rect?>(null) }
    if (callouts) LaunchedEffect(file.path) { piece = withContext(Dispatchers.IO) { spinPiece(file) } }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            // La proporción sale de la parte con IMAGEN, no del fichero: con la máscara
            // empaquetada debajo, el vídeo mide el doble de alto y la consola salía
            // aplastada a la mitad.
            //
            // Y `fillMaxSize` SOLO mientras no se sepa esa proporción, nunca junto a ella.
            // Los dos a la vez no valen: `fillMaxSize` fija las medidas —mínimo igual a
            // máximo— y con medidas fijas `aspectRatio` no tiene por dónde encajar, así que
            // devuelve la que le sale y se sale de la caja. Mientras el panel fue un hueco
            // enorme con la consola en medio no se notaba; metida en un marco se salía por
            // arriba y por abajo. Con la caja suelta, `aspectRatio` elige el lado que limita,
            // que es lo que se quería desde el principio.
            Modifier
                .then(
                    if (source.width > 0) Modifier.aspectRatio(
                        pictureSize(source).let { it.width.toFloat() / it.height }
                    ) else Modifier.fillMaxSize()
                )
                .onSizeChanged { box = it }
                .calloutTarget(piece ?: WHOLE, enabled = ready && piece != null)
                .graphicsLayer {
                    // Sin esto el efecto se aplica a un nodo mayor que el panel y acaba
                    // pintando sobre la lista. Ya me pasó con la televisión.
                    clip = true
                    alpha = if (ready) 1f else 0f
                    renderEffect = snapEffect(shader, box, source, tint)
                    // Sin shader el recorte se hace al dibujar, y necesita su propia capa: ver
                    // snapWithoutShader.
                    if (shader == null) compositingStrategy = CompositingStrategy.Offscreen
                }
                .then(if (shader == null) Modifier.drawWithContent { snapWithoutShader(source, tint) } else Modifier),
        ) {
            // En reposo el giro se para en su fotograma: un video en bucle descodificandose sin
            // que nadie lo mire. Ver Motion.
            // Y con el ahorro de bateria, siempre quieto: la consola se ve, en un fotograma.
            val idle by Motion.idle
            val saver by Motion.saver
            VideoSurface(file, onSize = { w, h -> source = IntSize(w, h); ready = true }, hold = idle || saver)
        }
    }
}

/**
 * El giro sin shader, en Android 11 y 12 (el shader pide Android 13): lo mismo que SNAP_SHADER
 * con lo que tiene cualquier lienzo.
 *
 * Sin esto se veia el fotograma entero: la consola aplastada arriba y su mascara, blanca, debajo
 * (09-10-2026, en un emulador de Android 11). El recorte sale de la mascara igual que en el
 * shader: se pinta la mitad de arriba estirada a toda la caja y encima, con DstIn, la de abajo
 * subida a su sitio y pasada de claridad a alfa. Sin mascara, la propia imagen hace de mascara,
 * como en el metodo viejo. Va en una capa propia (CompositingStrategy.Offscreen) porque DstIn
 * recorta lo que ya hay pintado, y sin capa eso seria la sala entera.
 *
 * Se pierde el clavado a la rejilla del fichero (aqui escala el lienzo, suave) y el umbral es una
 * rampa corta en vez de un escalon.
 */
private fun ContentDrawScope.snapWithoutShader(src: IntSize, tint: Color?) {
    if (src.width <= 0) { drawContent(); return }
    val packed = isPacked(src)
    val h = size.height
    val bounds = Rect(0f, 0f, size.width, h)
    val canvas = drawContext.canvas
    // La imagen, con el fosforo del tema si lo hay.
    val picture = tint?.let { t -> Paint().apply { colorFilter = ColorFilter.colorMatrix(phosphorMatrix(t)) } }
    if (picture != null) canvas.saveLayer(bounds, picture)
    if (packed) withTransform({ scale(1f, 2f, pivot = Offset.Zero) }) { this@snapWithoutShader.drawContent() }
    else drawContent()
    if (picture != null) canvas.restore()
    // Y el recorte.
    canvas.saveLayer(bounds, Paint().apply {
        blendMode = BlendMode.DstIn
        colorFilter = ColorFilter.colorMatrix(if (packed) MASK_TO_ALPHA else LIGHT_TO_ALPHA)
    })
    if (packed) withTransform({ translate(top = -h); scale(1f, 2f, pivot = Offset.Zero) }) { this@snapWithoutShader.drawContent() }
    else drawContent()
    canvas.restore()
}

/** La mascara a alfa: una rampa corta alrededor de la mitad, que es donde corta el shader. */
private val MASK_TO_ALPHA = ColorMatrix(
    floatArrayOf(
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        6f, 0f, 0f, 0f, -637.5f,
    ),
)

/** Sin mascara, el metodo viejo: lo casi negro, transparente. */
private val LIGHT_TO_ALPHA = ColorMatrix(
    floatArrayOf(
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        10f, 10f, 10f, 0f, 0f,
    ),
)

/** El fosforo de SNAP_SHADER: la claridad de la imagen, en el color del tema. */
private fun phosphorMatrix(t: Color): ColorMatrix {
    fun row(c: Float) = (c * PHOSPHOR_GAIN).let { floatArrayOf(0.299f * it, 0.587f * it, 0.114f * it, 0f, 0f) }
    return ColorMatrix(row(t.red) + row(t.green) + row(t.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
}

/**
 * Si el vídeo lleva su máscara de recorte en la mitad de abajo.
 *
 * Se reconoce por la forma: un giro de consola sale en 4:3 apaisado, y empaquetarle el alfa
 * debajo lo deja en 2:3 vertical. Ningún giro es más alto que ancho por sí solo.
 *
 * Hace falta reconocerlo en vez de darlo por hecho porque la app no fabrica estos ficheros:
 * cualquiera puede dejar un mp4 suyo en systems/, y a ése hay que tratarlo como antes.
 */
private fun isPacked(src: IntSize): Boolean = src.height > src.width

/** El tamaño de la parte con imagen: todo el vídeo, o la mitad de arriba si lleva máscara. */
internal fun pictureSize(src: IntSize): IntSize =
    if (isPacked(src)) IntSize(src.width, src.height / 2) else src

/**
 * Lo que ocupa la consola dentro de su giro, en fracciones del cuadro.
 *
 * Del primer fotograma, y no por comodidad. Los giros llevan un solo fotograma clave —el
 * primero— y sacar cualquier otro obliga a descodificar desde el principio, una vez por cada
 * uno que se quiera. Y el primero es la pose de presentación: medido sobre doce giros, sus
 * bordes caen a cinco centésimas de media de los de la mediana de la vuelta entera, y a
 * catorce en el peor. Entre una PlayStation —de lado a lado del cuadro— y una Game Boy —la
 * cuarta parte del medio— va mucho más que eso, y con un margen fijo las líneas de la Game
 * Boy saldrían del vacío.
 *
 * Se recoge hacia dentro, para que un punto puesto en el borde caiga ENCIMA de la consola
 * también cuando gira, y más de lado que de alto: al girar, lo que cambia es el ancho. Con
 * lo mismo por los cuatro lados, la Game Boy Advance vista de canto dejaba los puntos de los
 * lados a un dedo de ella, en el negro. Con la máscara empaquetada debajo manda la
 * máscara, porque el fondo de esos giros no es negro; sin ella, lo que pasa del umbral con
 * el que el shader ya separa la consola del fondo. Se mide una vez por fichero.
 */
internal fun spinPiece(file: File): Rect {
    val key = "${file.path}:${file.lastModified()}"
    synchronized(pieces) { pieces[key]?.let { return it } }
    val found = runCatching { measurePiece(file) }
        .onFailure { android.util.Log.w("Ludolog", "medir el giro: " + it.message) }
        .getOrNull() ?: SPIN_FALLBACK
    synchronized(pieces) { pieces[key] = found }
    return found
}

private val pieces = HashMap<String, Rect>()

/** Si no se puede medir: el centro, que es donde suele estar. */
private val SPIN_FALLBACK = Rect(0.15f, 0.15f, 0.85f, 0.85f)

private fun measurePiece(file: File): Rect? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.path)
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val w = frame.width
        val all = frame.height
        val packed = isPacked(IntSize(w, all))
        val h = if (packed) all / 2 else all
        val px = IntArray(w * all)
        frame.getPixels(px, 0, w, 0, 0, w, all)
        frame.recycle()
        val floor = if (packed) 127 else 16
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        // Sin las dos ultimas filas, que traen basura del codificador (ver el shader).
        for (y in 0 until h - 2) {
            val row = (if (packed) y + h else y) * w
            for (x in 0 until w) {
                val c = px[row + x]
                val lum = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                if (lum > floor) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < x0 || y1 < y0) return null
        val l = x0 / w.toFloat()
        val t = y0 / h.toFloat()
        val r = (x1 + 1) / w.toFloat()
        val b = (y1 + 1) / h.toFloat()
        val dx = (r - l) * INWARD_X
        val dy = (b - t) * INWARD_Y
        return Rect(l + dx, t + dy, r - dx, b - dy)
    } finally {
        retriever.release()
    }
}

/** Lo que se recoge hacia dentro de lo medido: de lado, que es lo que cambia al girar, y de alto. */
private const val INWARD_X = 0.18f
private const val INWARD_Y = 0.08f

private fun snapEffect(
    shader: RuntimeShader?,
    box: IntSize,
    src: IntSize,
    /** El fosforo del tema, o nulo si el giro se pinta con sus propios colores. */
    tint: androidx.compose.ui.graphics.Color?,
): androidx.compose.ui.graphics.RenderEffect? {
    if (shader == null || box.width <= 0 || src.width <= 0) return null
    return runCatching {
        shader.setFloatUniform("size", box.width.toFloat(), box.height.toFloat())
        shader.setFloatUniform("src", src.width.toFloat(), src.height.toFloat())
        shader.setFloatUniform("masked", if (isPacked(src)) 1f else 0f)
        // Se clava solo cuando el fichero es MAS FINO que la caja.
        //
        // Antes, en el caso contrario, no se devolvia shader ninguno: si no habia que
        // redondear se daba por hecho que no habia nada que hacer. Y si lo habia — el
        // recorte del fondo negro sale de aqui, y el color del tema tambien. Con los giros
        // de 768 nunca se llego a dar, porque el panel mide 1159; con un giro de caracteres
        // al doble de resolucion, el fondo negro habria vuelto a salir opaco.
        shader.setFloatUniform("snap", if (isPacked(src) || src.width < box.width) 1f else 0f)
        val g = PHOSPHOR_GAIN
        shader.setFloatUniform(
            "phosphor",
            (tint?.red ?: 1f) * g, (tint?.green ?: 1f) * g, (tint?.blue ?: 1f) * g,
        )
        shader.setFloatUniform("mono", if (tint != null) 1f else 0f)
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }.getOrNull()
}
