package com.felp.frontcomp

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File

// La vista del juego elegido: caratula, video de partida y su sintonia. Salio de MainActivity.kt el 07-10-2026.

/**
 * Lo sintonizado: el video que toca poner, y el que espera a que su sonido este separado.
 *
 * El segundo es para la sala, que mientras tanto avisa dentro del tubo de que esta preparando
 * ese canal.
 */
internal class Gameplay(val clip: File?, val pending: File?, val coming: Boolean)

/**
 * El video de un juego tal como se sintoniza: el mismo para la sala y para el panel.
 *
 * Vivia dentro de la sala, y el panel tenia otra version, sin espera y con el sonido dentro
 * del mp4; como solo la usaba un mueble que ningun tema llegaba a ensenar, nadie noto que se
 * habia quedado atras. Desde que todos los temas reproducen los gameplays, los dos piden aqui.
 *
 * Devuelve el fichero que hay que poner, o nulo mientras no toque: sin juego, con el video
 * apagado en este tema, mientras se separa su sonido o durante la espera. Y deja sonando su
 * pista —aparte, por el camino de los clics— o la calla.
 */
@Composable
internal fun rememberGameplay(vm: LibraryViewModel, game: Game?): Gameplay {
    // Con el ahorro de bateria, ningun video: ver Motion.saver.
    val saver by Motion.saver
    val want = vm.prefs.playVideo && !saver
    val raw = remember(game?.path, vm.art, want) {
        game?.takeIf { want }?.let { g -> vm.art?.video(g) }
    }

    // Y no se enciende hasta que su sonido esta separado y guardado.
    //
    // Son dos motivos en uno. El obvio: hasta entonces el sonido sigue dentro del mp4, y es
    // justo eso lo que hace que el aparato baje la salida entera. Y el otro, que cambiar el
    // fichero mientras el reproductor lo tiene abierto falla en silencio, asi que no abrirlo
    // es lo que hace que el intercambio funcione siempre. Mientras tanto se ve la caratula,
    // igual que en cualquier juego que todavia no tiene video.
    //
    // En la version que toca: con el filtro del tema, la tratada como diga el tema —cinta o
    // digital—; sin el, la limpia. Y solo si el video suena: callado no hay nada que separar ni
    // que esperar.
    val fx = if (vm.prefs.tapeSound) LocalTheme.current.soundFx else null
    val withSound = vm.prefs.videoSound
    // Y la version del fichero, ademas del fichero. Un video bajado de nuevo para el mismo juego
    // tiene la misma ruta, asi que sin esto nada de aqui se enteraba: la television ponia el
    // nuevo (ver Crt, que ya la miraba) con el sonido del viejo, que seguia cargado, y el del
    // nuevo no se pedia hasta irse del juego y volver.
    val rev = raw?.let(ArtRevisions::of) ?: 0
    val ready = remember(raw, rev, TapeQueue.revision, fx, withSound) {
        raw != null && (!withSound || TapeQueue.done(raw, fx))
    }

    // Y espera lo que diga el ajuste antes de encenderse. El efecto se cancela al moverse,
    // asi que pasar por encima de un juego no llega a abrir nada.
    var armed by remember(raw) { mutableStateOf(vm.prefs.videoDelay == 0) }
    LaunchedEffect(raw, vm.prefs.videoDelay) {
        if (vm.prefs.videoDelay > 0) {
            armed = false
            kotlinx.coroutines.delay(vm.prefs.videoDelay * 1_000L)
            armed = true
        }
    }
    val clip = raw?.takeIf { ready && armed }

    LaunchedEffect(raw, rev, fx, withSound) {
        // Un segundo parado antes de empezar. Pasar por encima de un juego no es elegirlo, y
        // sin esta espera bajar por una lista encolaba veinte. El efecto se cancela solo al
        // moverse, que es justo lo que hace falta.
        kotlinx.coroutines.delay(1_000)
        if (withSound) raw?.let { TapeQueue.ensure(it, fx) }
    }
    // El sonido no viaja dentro del mp4: va por el mismo reproductor que los clics, que es el
    // camino que no molesta. Sin video sintonizado, se calla.
    LaunchedEffect(clip, rev, withSound, vm.prefs.panelVolume, fx) {
        Sfx.clip(
            clip?.takeIf { withSound }?.let { TapeQueue.audioFor(it, fx) },
            vm.prefs.panelVolume / 100f,
        )
    }
    // Y al irse, callado. El panel de un juego desaparece al volver a la lista de consolas, y
    // sin esto su pista seguiria sonando sin video: el efecto de arriba ya no llega a pedir
    // silencio porque se cancela con el.
    DisposableEffect(Unit) { onDispose { Sfx.clip(null, 0f) } }
    // Si hay un video en camino: lo hay y todavia no se puso, y algo esta pasando —la espera
    // corre, o la cola trabaja—. Con la cola parada y el video sin separar no viene nada: una
    // separacion que fallo no se reintenta hasta volver a pararse encima, y anunciar una
    // transmision que no va a llegar seria mentir.
    val coming = raw != null && clip == null && (!armed || TapeQueue.working != null)
    return Gameplay(clip, raw?.takeIf { !ready }, coming)
}

/**
 * The panel for a game: the cover, and its recording once there is one to play.
 *
 * The video waits before it starts. Walking a list of two hundred games would otherwise
 * open and tear down a player per row, and the panel would strobe through covers it never
 * finishes drawing; the delay means only the game you actually stopped on plays.
 *
 * The cover stays until the video is ready, so the panel never goes empty during that wait.
 */
@Composable
internal fun GamePreview(
    vm: LibraryViewModel,
    game: Game,
    cover: File?,
    playing: Boolean,
) {
    val theme = LocalTheme.current
    // Con una ventana encima no se sintoniza nada: ni se ve ni tiene que sonar.
    val tuned = rememberGameplay(vm, game.takeIf { playing })
    val clip = tuned.clip

    // El mueble del panel no lo elige el tema: lo elige el fichero.
    //
    // Habia un campo `tvId` para que cada tema apuntara al suyo por id, y no lo ponia
    // ninguno de los tres. Sobraba ademas por otra razon: `tvs.toml` ya trae una forma de
    // hacer lo mismo, y es la que esta documentada —una entrada con `id = "drawn"`
    // sustituye a la que dibuja el codigo—. Dos caminos para una decision, y el que se
    // usaba era el otro.
    val skin = remember(vm.tvSkins) {
        vm.tvSkins.firstOrNull { it.id == TvSkin.Drawn.id } ?: TvSkin.Drawn
    }

    // `playing` es falso cuando hay una ventana encima: entonces el televisor no se ve, y
    // ni decodificar video ni animar nieve tiene sentido.
    if (!playing) {
        Preview(cover, vm.displayTitle(game), ContentScale.Fit)
        return
    }
    // Y un tema sin mueble pone el video solo, donde estaria la caratula.
    if (!theme.cabinet) {
        PanelGameplay(clip, cover, vm.displayTitle(game), coming = tuned.coming)
        return
    }
    CrtTv(
        channel = game.path,
        video = clip,
        cover = cover,
        label = vm.displayTitle(game),
        params = LocalTheme.current.crt,
        skin = skin,
        // Mudo: el sonido va aparte, por el camino de los clics. Ver `rememberGameplay`.
        sound = false,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * El gameplay en un panel sin mueble: el video solo, donde estaria la caratula.
 *
 * Sin televisor dibujado. En estos temas el panel es un hueco de la pantalla y no un aparato
 * dentro de una sala, y meter ahi un mueble seria un objeto con volumen en una pantalla que no
 * los dibuja. Lleva el mismo filete que la caratula, y el fosforo del tema si lo tiene: una
 * imagen a todo color en una pantalla de un solo fosforo serian dos aparatos distintos.
 *
 * Mudo: el sonido va aparte (ver `rememberGameplay`). Y la caratula se queda mientras el
 * reproductor no dice que tiene imagen, asi el panel nunca se queda vacio.
 *
 * En un tema cuyo video crece (ver `Theme.videoGrow`), el video se ABRE desde la caratula: la
 * ventana va del rectangulo de la portada al del video, ya mas grande, y la portada se apaga
 * mientras tanto. El video esta a su tamaño final desde el principio y lo que se anima es la
 * ventana, asi que la portada se abre sobre la imagen en vez de estirarla. En los demas, el
 * cambio es de golpe, como siempre.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PanelGameplay(video: File?, cover: File?, label: String, coming: Boolean) {
    val look = LocalTheme.current
    // Lo que se lee sobre la portada mientras llega el video, en el tema que cuenta algo.
    val tuning = look.tuning
    val onCover: (@Composable BoxScope.() -> Unit)? = tuning?.let { { Tuning(it) } }
    // La proporcion de la portada, que es de donde se abre el video.
    var coverRatio by remember(cover?.path) { mutableFloatStateOf(0f) }
    if (video == null) {
        Preview(
            cover, label, ContentScale.Fit, onRatio = { coverRatio = it },
            overlay = onCover.takeIf { coming },
        )
        return
    }
    val phosphor = phosphorFilter()
    // En el tema de fosforo, el video como lo pintaria su monitor: estela, tramado y barrido.
    // Uno solo para todos los juegos, avisado de cada cambio: ver PhosphorFx.retune.
    val fosforo = look.phosphor
    val phosphorFx = remember(fosforo) { if (fosforo) PhosphorFx() else null }
    val fx = remember(phosphorFx) { listOfNotNull<androidx.media3.common.Effect>(phosphorFx) }
    LaunchedEffect(video.path) { phosphorFx?.retune() }
    val frame = MenuLine
    var source by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var ready by remember(video.path) { mutableStateOf(false) }
    val grow = look.videoGrow
    // Lo abierto que esta el video, de cero —el hueco de la portada— a uno —su sitio—.
    val opening by androidx.compose.animation.core.animateFloatAsState(
        if (ready) 1f else 0f,
        androidx.compose.animation.core.tween(
            OPEN_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "abrir",
    )
    val open = if (grow != 1f) opening else if (ready) 1f else 0f
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // Hasta que el reproductor dice su tamaño, cuatro tercios, que es lo que miden casi todos.
        val videoRatio = if (source.width > 0) source.width.toFloat() / source.height else 4f / 3f
        val full = fitRect(videoRatio, boxW * grow, boxH * grow)
        // Desde la portada si se sabe donde queda; si no, desde el propio video sin crecer.
        val from = fitRect(if (coverRatio > 0f) coverRatio else videoRatio, boxW, boxH)
        val w = from.width + (full.width - from.width) * open
        val h = from.height + (full.height - from.height) * open
        val density = LocalDensity.current
        if (open < 1f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - open }) {
                Preview(
                    cover, label, ContentScale.Fit, onRatio = { coverRatio = it },
                    overlay = onCover.takeIf { !ready },
                )
            }
        }
        Box(
            // Con medida obligada y no con la que deje el hueco: al crecer se sale de el, hacia
            // los rotulos, y es a proposito.
            Modifier
                .requiredSize(with(density) { w.toDp() }, with(density) { h.toDp() })
                // Las lineas, del video cuando se ve; mientras, de la caratula que lo tapa.
                .calloutTarget(enabled = ready)
                .graphicsLayer {
                    clip = true
                    alpha = if (ready) 1f else 0f
                    renderEffect = phosphor?.let(::layerColour)
                }
                // Dentro de la capa, para que el filete aparezca con la imagen y no antes.
                .border(1.dp, frame),
            contentAlignment = Alignment.Center,
        ) {
            // El video, a su tamaño final: lo que se abre es la ventana de encima.
            Box(
                Modifier.requiredSize(
                    with(density) { full.width.toDp() },
                    with(density) { full.height.toDp() },
                ),
            ) {
                VideoSurface(
                    video,
                    muted = true,
                    onSize = { vw, vh ->
                        source = androidx.compose.ui.unit.IntSize(vw, vh)
                        ready = true
                    },
                    effects = fx,
                )
            }
        }
    }
}

/** El rectangulo de una proporcion encajado en un hueco, sin salirse por ningun lado. */
private fun fitRect(ratio: Float, w: Float, h: Float): androidx.compose.ui.geometry.Size =
    if (ratio > w / h) androidx.compose.ui.geometry.Size(w, w / ratio)
    else androidx.compose.ui.geometry.Size(h * ratio, h)

/**
 * Un filtro de color como efecto de capa.
 *
 * Es lo unico que alcanza a lo que pinta un TextureView: el `colorFilter` de una imagen de
 * Compose no llega a un video. Necesita Android 12; antes, el video sale con sus colores.
 */
private fun layerColour(filter: androidx.compose.ui.graphics.ColorFilter): androidx.compose.ui.graphics.RenderEffect? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        android.graphics.RenderEffect
            .createColorFilterEffect(filter.asAndroidColorFilter())
            .asComposeRenderEffect()
    } else null

@Composable
internal fun Preview(
    file: File?,
    label: String,
    scale: ContentScale,
    /** La proporcion de la caratula en cuanto se sabe: el video del panel se abre desde ella. */
    onRatio: (Float) -> Unit = {},
    /** Algo encima de la caratula, dentro de su marco: ver [Tuning]. */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val t = LocalTheme.current
    if (file != null) {
        // La caratula, con un encuadre que la CIÑE y no la caja donde vive.
        //
        // Sin el, la portada flota en medio del panel y el tema no tiene donde apoyarse: es la
        // unica cosa con color en pantalla y esta suelta. Con una regla de un pelo alrededor
        // pasa a estar puesta, que es lo que hace un marco.
        //
        // Lo evidente —un borde en el hueco entero— dibuja un rectangulo que no es el de la
        // caratula: las portadas son mas altas que anchas y el hueco es apaisado, asi que el
        // marco quedaba a un palmo de la imagen por los lados. Aqui se mide el hueco, se
        // compara con la proporcion de la imagen y el marco se ajusta a la que manda.
        var ratio by remember(file.path) { mutableFloatStateOf(0f) }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val box = maxWidth / maxHeight
            val fit = when {
                ratio <= 0f -> Modifier.fillMaxSize()
                ratio > box -> Modifier.fillMaxWidth().aspectRatio(ratio)
                else -> Modifier.fillMaxHeight().aspectRatio(ratio)
            }
            // La imagen y lo que vaya encima, en la misma caja: lo de encima es de la caratula,
            // no del hueco, y se ciñe a sus cantos igual que el filete.
            Box(
                fit.then(
                    // Sin filete si el tema tiene mueble: ahi lo que encuadra la caratula es el
                    // televisor, y este pelo es justo lo que lo sustituye en los que no lo
                    // tienen —el `else` de GamePreview es este Preview—. Lo decidia `ornament`,
                    // o sea que el tema MAS decorado era el que se quedaba SIN marco.
                    if (t.cabinet || ratio <= 0f) Modifier
                    else Modifier.border(1.dp, MenuLine)
                )
                    // Y las lineas del Mainframe salen de sus cantos, una vez sabida su
                    // proporcion: antes ocupa el hueco entero, y ahi no es donde va a estar.
                    .calloutTarget(enabled = ratio > 0f),
            ) {
                AsyncImage(
                    model = artModel(file), contentDescription = label, contentScale = scale,
                    // Y pintada por el mismo tubo que todo lo demas, si el tema es de ese tipo.
                    colorFilter = phosphorFilter(),
                    // La proporcion se sabe cuando la imagen esta cargada y no antes. Hasta
                    // entonces ocupa el hueco entero y no se dibuja marco, que es preferible a
                    // dibujar uno en el sitio equivocado y verlo saltar.
                    onSuccess = {
                        val s = it.painter.intrinsicSize
                        if (s.width > 0f && s.height > 0f) {
                            ratio = s.width / s.height
                            onRatio(ratio)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (ratio > 0f) overlay?.invoke(this)
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label.uppercase(), color = MenuFaint, fontSize = 22.sp,
                     fontFamily = MenuBody, letterSpacing = 3.sp,
                     textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("no image", color = MenuFaint.copy(alpha = .6f), fontSize = 11.sp,
                     fontFamily = MenuBody)
            }
            // Sin caratula tambien se anuncia lo que llega: abajo del hueco.
            overlay?.invoke(this)
        }
    }
}

/**
 * Lo que dice el panel mientras llega el video de un juego, sobre su caratula.
 *
 * Una franja al pie de la portada, con el texto del tema y unos puntos que avanzan: la espera
 * ya existia en todos los temas y aqui se cuenta, como la cuenta un monitor que esta
 * sintonizando. Sobre la portada y no debajo del hueco, porque a los lados del hueco estan los
 * rotulos de la ficha y abajo no queda sitio que sea de nadie.
 */
@Composable
private fun BoxScope.Tuning(text: String) {
    val accent = LocalTheme.current.accent
    // Los puntos: de cero a tres, a su paso, y otra vez.
    val dots by produceState(0) {
        while (true) {
            kotlinx.coroutines.delay(TUNING_STEP_MS)
            value = (value + 1) % 4
        }
    }
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        // En dos renglones si no cabe en uno: una portada vertical mide poco mas de doscientos
        // puntos, y en uno solo se cortaba en «INCOMING TRANS». Los puntos van pegados a la
        // ultima palabra y con sus huecos, para que el renglon no cambie de ancho al avanzar.
        Text(
            text + ".".repeat(dots) + " ".repeat(3 - dots),
            color = accent, fontSize = 9.sp, fontFamily = MenuBody, letterSpacing = 1.sp,
            textAlign = TextAlign.Center, maxLines = 2, lineHeight = 12.sp,
        )
    }
}

private const val TUNING_STEP_MS = 320L

/** Lo que tarda el video en abrirse desde la caratula hasta su sitio. */
private const val OPEN_MS = 520

/* -------------------------------------------------------------------------- arranque */

@Composable
internal fun StartPanel(vm: LibraryViewModel) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val cat = vm.catalog
        Text(
            if (cat == null) "LOADING CATALOG" else "${cat.systems.size} SYSTEMS IN CATALOG",
            color = MenuDim, fontSize = 13.sp, fontFamily = MenuBody, letterSpacing = 2.sp
        )
        Spacer(Modifier.height(20.dp))
        val requester = remember { FocusRequester() }
        AutoFocus(requester)
        Box(
            Modifier.focusRequester(requester)
                .padItem(onActivate = vm::scan)
                .clickable(enabled = !vm.busy) { vm.scan() }
                .padding(horizontal = 26.dp, vertical = 12.dp)
        ) {
            Text(
                if (vm.busy) "SCANNING…" else "SCAN ROMS",
                color = MenuInk, fontSize = 16.sp, fontFamily = MenuBody,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )
        }
        vm.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Color(0xFFE0805F), fontSize = 12.sp, fontFamily = MenuBody)
        }
    }
}
