package com.felp.frontcomp

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * De dónde sale el arte, en tres niveles.
 *
 * El orden es por certeza de identidad y por lo que cuesta ponerlo en marcha, no por calidad
 * de imagen. El primero funciona en una instalación recién hecha sin configurar nada; los
 * otros dos piden una cuenta que el usuario se hace, y a cambio saben cosas que el primero
 * no puede saber.
 *
 * Las credenciales son del usuario y solo suyas. No se compila ninguna clave en la
 * aplicación: una clave dentro de un APK no es una clave, se lee con unzip y grep. Y sería
 * un único cupo compartido por todas las instalaciones, revocable para todas a la vez el día
 * que alguien abusara.
 */

/** Una fuente de arte: qué necesita para funcionar y cómo se pone en marcha. */
internal class ArtTier(
    val id: String,
    val title: String,
    /** Qué sabe hacer, en una frase. */
    val blurb: String,
    /** Las casillas que hay que rellenar. Vacío = no necesita cuenta. */
    val fields: List<Pair<String, String>>,
    /** Los pasos para conseguir la cuenta, en orden. */
    val howTo: List<String>,
) {
    val keys: List<String> get() = fields.map { it.first }
    fun ready(prefs: Prefs) = fields.isEmpty() || prefs.sourceReady(*keys.toTypedArray())

    /** Las casillas que siguen vacias, por su nombre, para decirlo en la propia fila. */
    fun missing(prefs: Prefs): List<String> =
        fields.filter { prefs.credential(it.first).isEmpty() }.map { it.second }
}

internal val ART_TIERS = listOf(
    ArtTier(
        id = "free",
        title = "Open sources",
        blurb = "No account. Box art named after the No-Intro and Redump sets, Steam covers " +
            "read from the game file itself, and Switch art by title id.",
        fields = emptyList(),
        howTo = listOf(
            "Nothing to do: this one is always on.",
            "It works best when your files keep their dump-set names, because that name is " +
                "what the art is filed under.",
            "A .steam file holds its Steam number inside, and a Switch dump carries its " +
                "title id in the name. Those two cannot pick the wrong game.",
            "It cannot know about ROM hacks or fan translations, which are in no dump set.",
        ),
    ),
    ArtTier(
        id = "igdb",
        title = "IGDB",
        blurb = "A real games database: it knows every platform, lists alternative titles, and " +
            "is the only one here that catalogues ROM hacks.",
        fields = listOf("igdb.id" to "Client ID", "igdb.secret" to "Client secret"),
        howTo = listOf(
            "1  Sign in at dev.twitch.tv with any Twitch account and enable two-factor.",
            "2  Go to Your Console, Applications, Register Your Application.",
            "3  Name it anything, set the redirect to http://localhost, category Application " +
                "Integration.",
            "4  It gives you a Client ID; press New Secret for the other one.",
            "5  Type both here. They are yours: your allowance, and you can revoke them.",
        ),
    ),
)

/**
 * La pantalla donde se ve, de un vistazo, qué fuentes están listas.
 *
 * El rombo de cada fila es el indicador: relleno cuando la fuente puede usarse, hueco cuando
 * le falta algo. A abre sus credenciales; la fila de abajo, con el interrogante, explica
 * cómo conseguirlas paso a paso, porque sacar una clave de IGDB tiene cinco pasos en dos
 * sitios distintos y nadie los recuerda.
 */
@Composable
internal fun ColumnScope.ArtSourcesPane(vm: LibraryViewModel) {
    var revision by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Pair<ArtTier, Int>?>(null) }
    // Ojo: esta fila va aqui arriba a proposito. Debajo hay returns tempranos, y un remember
    // que quede detras de un return se olvida al volver, asi que el cursor saltaria a la
    // primera fila cada vez que se cierra una credencial o un tutorial.
    var selected by remember { mutableStateOf(0) }
    var helping by remember { mutableStateOf<ArtTier?>(null) }
    val t = LocalTheme.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    editing?.let { (tier, field) ->
        val (key, label) = tier.fields[field]
        val cancel = { editing = null }
        // Como pagina de los ajustes y no como ventana dentro de ellos: anidada salia tan
        // pequeña que los botones no enseñaban su rotulo. Ver TextPage. B y el rotulo de la
        // cabecera vuelven a las fuentes, no cierran los ajustes.
        CompositionLocalProvider(LocalDismiss provides cancel) {
            Column(Modifier.weight(1f).fillMaxWidth().padBack(cancel)) {
                TextPage(
                    title = label.uppercase(),
                    initial = vm.prefs.credential(key),
                    secret = "pass" in key || "secret" in key,
                    // Con Link, la misma clave llega a los aparatos emparejados: ver Prefs.sharedCredentials.
                    help = "${tier.title}. Kept encrypted. Ludolog Link shares it with your paired devices. " +
                        "Leave it empty to turn the source off.",
                    onCancel = cancel,
                    onDone = { value ->
                        if (value.trim() != vm.prefs.credential(key)) {
                            vm.prefs.setCredential(key, value)
                            LinkBridge.keysChanged(ctx)
                        }
                        editing = null
                        revision++
                    },
                )
            }
        }
        return
    }
    helping?.let { tier ->
        HowToPage(tier) { helping = null }
        return
    }

    // Una fila por fuente y, debajo de cada una, sus casillas cuando las tiene.
    val rows = buildList {
        for (tier in ART_TIERS) {
            add(tier to -1)
            tier.fields.indices.forEach { add(tier to it) }
        }
    }
    val sel = selected.coerceIn(0, rows.lastIndex)
    val (curTier, curField) = rows[sel]

    WindowFrame(
        title = "ART SOURCES",
        subtitle = "${ART_TIERS.count { it.ready(vm.prefs) }} of ${ART_TIERS.size} ready",
        description = if (curField < 0) "${curTier.blurb}\nA  see how to set it up"
                      else "${curTier.fields[curField].second} for ${curTier.title}.  A  edit",
        hint = "A  open      B  back",
    ) {
        ModalRows(
            count = rows.size,
            selected = sel,
            onSelect = { selected = it },
            onActivate = {
                val (tier, field) = rows[sel]
                if (field < 0) helping = tier else editing = tier to field
            },
        ) { index ->
            val (tier, field) = rows[index]
            if (field < 0) {
                val ready = remember(revision, tier.id) { tier.ready(vm.prefs) }
                val gaps = remember(revision, tier.id) { tier.missing(vm.prefs) }
                ModalRow(
                    label = tier.title,
                    // Media credencial puesta es el caso que mas despista: la fila decia «not
                    // set up» igual que si estuviera vacia, y no habia forma de ver cual de
                    // las cuatro faltaba sin bajar a mirarlas una por una.
                    value = when {
                        ready -> "ready"
                        gaps.size == tier.fields.size -> "not set up"
                        else -> "needs ${gaps.first().lowercase()}"
                    },
                    chevron = true,
                    swatch = if (ready) t.accent else t.dim,
                    swatchFilled = ready,
                )
            } else {
                val (key, label) = tier.fields[field]
                val set = remember(revision, key) { vm.prefs.credential(key).isNotEmpty() }
                // Una contraseña no se enseña. Que esté puesta o no es lo único que importa.
                ModalRow(
                    label = "    $label",
                    value = if (!set) "empty" else if ("pass" in key || "secret" in key) "set" else vm.prefs.credential(key),
                    dimmed = !set,
                )
            }
        }
    }
}

/**
 * Los pasos para poner en marcha una fuente, que es lo que el interrogante despliega.
 *
 * Una hoja para leer y no una lista: texto seguido y un solo boton para volver. Eran filas
 * por las que se movia un cursor, cada paso cortado en un renglon con puntos al final, y
 * pulsar A en una no hacia nada: parecia un menu sin opciones.
 *
 * Ocupa la ventana de los ajustes entera, como cualquier otra pagina suya, y no una ventana
 * dentro de ella: asi anidada salia pequeña y solo cabia el primer paso. Si el texto no cabe,
 * se arrastra con el dedo o se baja con la cruceta; A en el boton o B vuelven.
 */
@Composable
private fun ColumnScope.HowToPage(tier: ArtTier, onClose: () -> Unit) {
    val back = remember { FocusRequester() }
    AutoFocus(back)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 60.dp.toPx() }
    // Lo que se toque en la cabecera —«B  back»— vuelve de aqui, no de los ajustes.
    CompositionLocalProvider(LocalDismiss provides onClose) {
        WindowFrame(
            title = "?  ${tier.title.uppercase()}",
            subtitle = "${tier.howTo.size} steps",
            description = null,
            hint = "B  back",
        ) {
            Column(
                Modifier.fillMaxSize()
                    .padBack(onClose)
                    // Arriba y abajo mueven el texto: el foco esta en el boton y no hay otra
                    // cosa a la que llevarlo.
                    .onPreviewKeyEvent { e ->
                        val dir = when (e.key) {
                            Key.DirectionDown -> 1
                            Key.DirectionUp -> -1
                            else -> 0
                        }
                        if (dir == 0) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(dir * step) }
                        true
                    },
            ) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                    Text(
                        tier.blurb, color = MenuDim, fontSize = 13.sp, lineHeight = 19.sp,
                        fontFamily = MenuBody,
                    )
                    Spacer(Modifier.height(16.dp))
                    for (line in tier.howTo) {
                        Text(
                            line, color = MenuInk, fontSize = 14.sp, lineHeight = 21.sp,
                            fontFamily = MenuBody,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                PadButton("BACK", onClose, primary = true, focusRequester = back)
            }
        }
    }
}
