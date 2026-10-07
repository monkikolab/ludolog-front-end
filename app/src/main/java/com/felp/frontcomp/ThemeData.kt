package com.felp.frontcomp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Los temas como datos, separados de Theme.kt sin cambios: este archivo no toca Android, y asi
// Ludolog Link del PC compila ESTE mismo y no una copia que se desincronice. Sin nada de
// Android aqui: ver docs/ludolog-link.md antes de tocar imports, nombre o paquete.

/**
 * The look, as data.
 *
 * Built before choosing an aesthetic on purpose. With this, trying a direction costs one
 * object; without it, each one is a branch of the code and comparing them honestly is
 * impossible — which is how projects end up keeping the first thing they tried.
 *
 * Sin valores por defecto, a proposito. Cada tema dice TODOS los campos, en este mismo orden
 * y por las mismas secciones, y asi los temas se comparan leyendo una columna: lo que en uno
 * vale una cosa, en el de al lado esta en la misma linea valiendo otra. Con valores por
 * defecto cada tema escribia solo lo suyo, en su orden, y lo que callaba lo decidia esta
 * clase sin que se viera: el Parlour no decia nada de su sala, de su mueble ni de su
 * movimiento, que son justo lo que lo define.
 *
 * El precio es que un campo nuevo hay que decidirlo en todos los temas. Es lo que se quiere:
 * el compilador no deja que un tema herede en silencio la decision de otro.
 */
data class Theme(
    val id: String,
    val name: String,

    /* ---- colour ---- */
    val ground: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    /**
     * El color de las reglas, las cejas y los filetes.
     *
     * Vacio quiere decir «el del acento». No es un atajo: hay temas en los que la regla ES
     * el acento, y escribirlo dos veces es pedir que se separen. Pasaba —el Mainframe
     * lo tenia escrito dos veces— y la unica forma de arreglarlo sin esto era ampliar
     * `withAccent` para que copiara tambien este campo, o sea otro remiendo mas. Un valor
     * que no se guarda no se puede desincronizar.
     */
    val line: Color?,
    val accent: Color,
    /**
     * Si el tema es de fondo claro.
     *
     * No basta con poner los colores del reves. Hay dos cosas que no salen de la paleta y
     * que sobre blanco quedan mal: el velo con el que se atenua lo que hay detras de una
     * ventana —negro al 80 por ciento sobre una pagina clara es un apagon— y el esquema de
     * Material, que decide los colores por defecto de lo que no pinta este tema.
     */
    val light: Boolean,

    /* ---- type ---- */
    val display: FontFamily,
    val body: FontFamily,
    /** Títulos en mayúsculas o tal cual: una blackletter en mayúsculas es ilegible. */
    val upperTitles: Boolean,
    val titleTracking: TextUnit,
    val itemTracking: TextUnit,
    /** Espaciado de los rótulos pequeños en mayúsculas, tipo «SYSTEM // 01». */
    val captionTracking: TextUnit,
    /**
     * Factor sobre todos los tamaños de letra del tema.
     *
     * Se aplica una vez, en la densidad de la composición, y no tamaño a tamaño: así
     * «un diez por ciento menos» es un número y no treinta ediciones que se desajustan.
     * No toca el diálogo de la sala ni los iconos, que se miden en píxeles.
     */
    val typeScale: Float,
    /**
     * El dialogo escrito con una lamina de letras, no con una tipografia.
     *
     * Aparte de `pixelIcons` aunque suene a lo mismo. Un icono de mapa de bits de once por
     * once lo dibuja cualquiera en una tarde; una lamina de letras es un abecedario entero, y
     * un tema puede querer los iconos de pixeles mucho antes de tener letra propia. Mientras
     * no la tenga, escribir con la del vecino es peor que escribir con una tipografia: la
     * lamina que trae el programa es la de los dialogos de un juego concreto y se reconoce.
     */
    val pixelText: Boolean,

    /* ---- chrome ---- */
    /** Como se dibujan las cajas: filete, regletas sueltas o cajas de instrumento. Ver [Chrome]. */
    val chrome: Chrome,
    /** Radio de las esquinas de las ventanas. Cero es cuadradas. */
    val corner: Dp,
    val divider: DividerStyle,
    /** Cómo se marca la fila seleccionada. */
    val selection: SelectionStyle,
    /** El relleno de la fila seleccionada cuando `selection` es BAR. */
    val selectionFill: Color,
    /** Los iconos de la barra de arriba como mapas de bits, no vectoriales. */
    val pixelIcons: Boolean,

    /* ---- ornament ---- */
    /**
     * El adorno del tema, o su ausencia: una sola decision, decorado o liso.
     *
     * Decorado es el rombo y su familia, repetido en todas partes para que la pantalla tenga
     * UN adorno y no uno por sitio: los rombos de las regletas, la tira del cursor con los
     * suyos y su latido, las esquinas de la casilla del cajon, el vitral de las barras del
     * cuaderno, las pestanas partidas por un rombo y el marco labrado del avatar. Liso es lo
     * mismo sin nada de eso: reglas enteras, relleno, subrayado, un pelo alrededor.
     *
     * Es todo el adorno Parlour que admite este aspecto: los menus de la PSX eran secos, y
     * un remate pequeno en los vertices dice «Parlour» sin volverlos florituras.
     *
     * Y SOLO eso. Hacia dos trabajos mas que no eran adorno —con que marca firma el tema y
     * si la caratula lleva filete— y los dos se han ido a donde tocaban: `emblem` y
     * `cabinet`. Ver los dos.
     */
    val ornament: Boolean,
    /** Con que firma el programa si el tema no trae la suya. Ver [Emblem]. */
    val emblem: Emblem,

    /* ---- screen ---- */
    /**
     * Todo lo que se vea, pintado por el MISMO tubo y en UN color.
     *
     * Las caratulas son fotografias a todo color, y en un tema que se sostiene sobre la idea
     * de que la pantalla es una maquina encendida, una fotografia a todo color rompe la idea
     * mas que cualquier adorno: dice que hay una ventana al mundo dentro del terminal. Con
     * esto, de cada imagen se toma su claridad y se vuelve a montar con el acento, que es lo
     * que hace un fosforo: una sola sustancia que brilla mas o menos.
     *
     * Alcanza tambien a la señal del tubo, por la via que ya existia: saturacion a cero y el
     * tinte en el acento. Ver `withAccent`, que es quien lo mantiene atado si se cambia.
     *
     * Y SOLO la señal. El marco de las cajas —la ceja gruesa, el rotulo dentro de ella— lo
     * decidia tambien esto, y ahora lo decide `chrome`; como se marca lo elegido, `selection`.
     * Que un tema pinte en un color no dice nada de como enmarca ni de como señala.
     *
     * Con UNA excepcion: las barras del cuaderno llevan un color por consola. Alli el color no
     * adorna, dice que consola es cada barra, y en un solo tono dejaba de decirlo. Ver
     * `categorical`.
     */
    val phosphor: Boolean,
    /** Como se comporta la TV del panel en este tema. */
    val crt: CrtParams,

    /* ---- scene and motion ---- */
    /**
     * Si este tema usa la sala que declare tvs.toml, cuando haya una.
     *
     * Cada tema puede traer el suyo —`themes/<id>/tvs.toml`— y el que manda es el del tema
     * puesto; si no lo trae, se mira el comun del aparato. Esta bandera dice si la sala que se
     * encuentre se USA, que no es lo mismo: un tema puede no querer sala ninguna y con la del
     * vecino instalada en la carpeta comun se la encontraria de fondo, cambiando la letra y el
     * color mientras el escenario sigue siendo de otro.
     *
     * Antes hacia falta ademas para tapar un agujero: el fichero era UNO para todo el aparato
     * y no habia forma de que un tema dijera cual era la suya. Ya no; ahora dice solo lo que
     * dice.
     */
    val room: Boolean,
    /**
     * Cuanto se apaga el fondo plano bajo el velo del color de fondo.
     *
     * Va en el tema y no es una constante porque la cuenta no es la misma en las dos luces:
     * la misma imagen gris tiene que bajar hasta casi negro en el oscuro y subir hasta casi
     * papel en el claro, y la distancia que hay que recorrer es distinta. Con un valor unico,
     * el tema oscuro salia de un gris de niebla que no era ni la foto ni el fondo.
     */
    val backdropVeil: Float,
    /**
     * Si la caratula se ve dentro de un mueble de television.
     *
     * El mueble es la mitad del Parlour: la lista es un menu y lo de al lado es un aparato
     * encendido. En un tema de minima expresion es justo lo que sobra, y sin el el gameplay
     * va solo, donde estaria la caratula: un hueco de la pantalla y no un aparato.
     */
    val cabinet: Boolean,
    /**
     * Los giros renderizados: las consolas del panel y el aparato del Companion.
     *
     * El video de juego no pasa por aqui: lo pueden poner todos los temas, y si lo ponen lo
     * decide cada uno en sus ajustes («Gameplay video», guardado por tema). Hubo un campo
     * `stills` que lo prohibia en el Gallery —la quietud era el tema—, y se quito para que
     * el video fuera de todos. Los giros siguen siendo del tema: un video de juego es metraje
     * y un giro es un render en tres dimensiones, y una pantalla de fosforo no dibuja objetos
     * con volumen, dibuja lo que le llega por el cable.
     */
    val spins: Boolean,
    /**
     * Y si giran los MEDALLONES del cajon de apps, que es otra cosa.
     *
     * Dos cadenas distintas de medios y por eso dos interruptores. Los giros de consola puede
     * tenerlos el tema en su carpeta y ser suyos —los del Mainframe son de caracteres, no
     * de volumen—, pero `media/apps/` es UNA sola para todo el aparato: quien no traiga los
     * suyos se encuentra los del vecino. Con un solo interruptor, encender los giros de consola
     * encendia tambien medallones renderizados en tres dimensiones que no son de este tema.
     *
     * Apagado, en su sitio va el icono de la app en grande, que es lo que el medallon
     * representaba.
     */
    val appSpins: Boolean,
    /**
     * Si una consola se enseña con una placa de letras —su nombre corto grande, y debajo quien
     * la hizo y cuando— en vez de con un dibujo. Es lo del Gallery: las siluetas sacadas de
     * los renders se veian como manchas a esa escala, y la tipografia es lo que ese tema tiene.
     * Ver ConsolePlate.
     */
    val plates: Boolean,
    /**
     * Como suena el gameplay con el filtro del tema encendido: la cinta —la banda de una VHS y
     * el eco de una sala— o lo digital —pocas muestras y pocos bits—. Apagado, suena como vino
     * en los dos casos. Ver TapeAudio.
     */
    val soundFx: SoundFx,
    /**
     * Lo que se lee sobre la caratula mientras llega su video, o nada.
     *
     * La espera del video es de todos los temas; lo que se cuenta durante ella es de cada uno. En
     * el de fosforo es una transmision entrante, que es lo que un monitor de esos esperaba.
     */
    val tuning: String?,
    /**
     * Cuanto mayor que la caratula queda el video del panel, al abrirse: uno es igual.
     *
     * El video no necesita el aire que deja una portada vertical, y en el de fosforo el hueco de
     * la grafica le quedaba estrecho. Crece hacia los rotulos sin llegar a ellos: ver Callouts.
     */
    val videoGrow: Float,
)

/** Los dos tratamientos del sonido de un gameplay. Ver [Theme.soundFx]. */
enum class SoundFx { TAPE, DIGITAL }

enum class DividerStyle { PLAIN, PARLOUR, SCAN, HAIR, LOZENGE }

/**
 * Cómo se señala la fila elegida.
 *
 * OUTLINE es un marco alrededor. BAR es un relleno de la fila; con el adorno del tema
 * (`ornament`) lleva ademas la tira del canto, sus rombos y el latido, que es como marcaban
 * la fila los menus de sistema de la PSX. Sin adorno es el relleno y nada mas: asi lo usa el
 * Gallery, y por eso su BAR no tiene barra.
 *
 * INVERT lo dibujaba antes `phosphor`, que es un campo sobre el COLOR de la señal. El
 * Mainframe declaraba OUTLINE y en pantalla salia un bloque invertido, porque en
 * cinco sitios distintos el color pisaba a la forma —y en uno de ellos, la rejilla del
 * cajon de apps, este campo ni se miraba—. Una decision, un campo.
 */
enum class SelectionStyle { OUTLINE, BAR, INVERT }

/**
 * La firma que dibuja el propio programa cuando el tema no trae ninguna.
 *
 * Solo se usa si en la carpeta del tema no hay `companion.svg` ni `mark.svg`: un tema que
 * dibuje la suya firma con la suya y esto no se mira. Hoy es el caso del Parlour.
 *
 * DIAMOND es un rombo, la firma de un tema de lineas rectas; RECKONING es un libro con un
 * ojo, que es una palabra de sala encantada. Antes lo decidia `ornament`, que es un campo
 * sobre si se pintan rombos y vitrales: que un tema decore no dice nada de como firma.
 */
enum class Emblem { DIAMOND, RECKONING }

/**
 * Como se dibujan las cajas: los paneles del menu, las ventanas y sus rotulos.
 *
 * - BORDER: un filete alrededor de las ventanas, y el menu sin caja, suelto sobre el fondo.
 *   El Gallery.
 * - RULES: dos regletas sueltas, arriba y abajo, en vez de un marco. El menu no es una
 *   ventana sobre la sala sino un rotulo escrito encima: lleva su titulo, su regla y un pie en
 *   mayusculas. En las ventanas, la regleta de arriba va en rojo cuando preguntan algo, que es
 *   la firma de un aviso en los menus de la PSX. El Parlour.
 * - INSTRUMENT: cajas de instrumento, con la ceja gruesa arriba y el rotulo DENTRO de la
 *   ceja. Y el panel se reparte al reves: la lista con todo el alto, y a la derecha dos cajas
 *   —la imagen arriba con sus cruces y su texto debajo—, porque en una interfaz de instrumento
 *   lo que no tiene marco no esta en ningun sitio. El Mainframe.
 *
 * Antes eran tres campos: `looseRules`, `framedPanel` y el `phosphor` que, ademas de pintar
 * la señal de un color, decidia el marco. Tres booleanos para una eleccion de tres dejaban
 * escribir combinaciones que ningun tema pedia —regletas Y cajas de instrumento— y el codigo
 * las resolvia en silencio por el orden de sus `if`. Y `framedPanel` no podia valer por su
 * cuenta: cada vez que era cierto se dibujaban cajas de instrumento. Era la misma decision
 * escrita dos veces.
 */
enum class Chrome { BORDER, RULES, INSTRUMENT }

/**
 * Gallery: la minima expresion, en oscuro.
 *
 * La regla es que aqui no hay nada que mirar salvo lo que se ha venido a mirar. Sin sala, sin
 * television y sin consolas girando: la caratula del juego —o su gameplay, si se deja
 * encendido— es lo unico con color en pantalla y todo lo demas es tipografia y aire.
 *
 * Eso no es lo mismo que «sin adorno». Hay adorno, y por eso hay UN adorno: la regla de un
 * pelo que separa el titulo de la lista, y la barra de acento de la fila elegida. Dos gestos,
 * repetidos en todas partes. Lo que hace elegante a un tema asi no es quitar cosas hasta que
 * no queda ninguna, es quitar hasta que queda una y usarla siempre igual.
 *
 * Palo seco y no monoespaciada. La monoespaciada es de maquina —esta bien en el Parlour,
 * donde el menu tiene que parecer burocratico— y aqui el texto solo tiene que leerse.
 *
 * El acento que trae es un azul de grafito: se ve que es un color y no se pone delante de
 * nada. Quien quiera otro lo cambia en Ajustes, que para eso esta la lista.
 */
val GalleryTheme = Theme(
    id = "gallery",
    name = "Gallery",

    /* ---- colour ---- */
    ground = Color(0xFF0E0E10),
    ink = Color(0xFFF2F2F4),
    dim = Color(0xFF9A9AA2),
    faint = Color(0xFF5A5A62),
    line = Color(0xFF26262B),
    accent = Color(0xFF7F94A8),
    light = false,

    /* ---- type ---- */
    display = FontFamily.SansSerif,
    body = FontFamily.SansSerif,
    upperTitles = true,
    // Abierto en el titulo y casi cerrado en la lista: el espaciado es lo unico que distingue
    // un rotulo de una linea de texto cuando no hay ni caja ni color que los separe.
    titleTracking = 5.sp,
    itemTracking = 0.5.sp,
    captionTracking = 2.sp,
    typeScale = 1f,
    pixelText = false,

    /* ---- chrome ---- */
    chrome = Chrome.BORDER,
    corner = 0.dp,
    divider = DividerStyle.HAIR,
    selection = SelectionStyle.BAR,
    selectionFill = Color(0xFF191A1D),
    pixelIcons = false,

    /* ---- ornament ---- */
    ornament = false,
    emblem = Emblem.DIAMOND,

    /* ---- screen ---- */
    phosphor = false,
    // Sin tubo. Los valores estan a cero y no «casi a cero» a proposito: lo que hay en el panel
    // es una caratula o un video limpio, y con curvatura y lineas de barrido cualquiera de los
    // dos es una imagen estropeada.
    crt = CrtParams(curvature = 0f, scanline = 0f, mask = 0f, gain = 1f, halo = 0f),

    /* ---- scene and motion ---- */
    room = false,
    // Hondo: la imagen que viene es un gris neutro y aqui tiene que quedarse en insinuacion.
    backdropVeil = 0.80f,
    cabinet = false,
    spins = false,
    // Y sin medallones en el cajon, por lo mismo: aqui no se mueve nada.
    appSpins = false,
    plates = true,
    soundFx = SoundFx.TAPE,
    tuning = null,
    videoGrow = 1f,
)

/**
 * El mismo tema sobre papel.
 *
 * No es la version oscura con los colores invertidos y ya: el contraste no es simetrico. En
 * oscuro el texto apagado tiene que SUBIR hacia el fondo para apagarse y en claro tiene que
 * BAJAR, asi que `dim` y `faint` no son el reflejo uno del otro sino dos escaleras distintas
 * medidas cada una contra su propio fondo. Invirtiendo a secas, el texto secundario salia mas
 * marcado que el principal.
 *
 * Y el fondo no es blanco puro sino hueso. Un blanco de 255 en una pantalla brillante a un
 * palmo de la cara es una lampara; dos puntos por debajo ya se lee como papel.
 */
val GalleryLightTheme = Theme(
    id = "gallery-light",
    name = "Gallery light",

    /* ---- colour ---- */
    ground = Color(0xFFF6F6F3),
    ink = Color(0xFF16161A),
    dim = Color(0xFF4A4A54),
    // Mas oscuro de lo que pediria la simetria: sobre papel, el gris que en oscuro se lee
    // como «apagado» se lee como «no esta». Medido en pantalla, el pie de la lista con
    // 0xA8A8B0 no se distinguia del fondo a un palmo de la cara.
    faint = Color(0xFF6E6E7A),
    line = Color(0xFFC4C4BD),
    accent = Color(0xFF4A6076),
    light = true,

    /* ---- type ---- */
    display = FontFamily.SansSerif,
    body = FontFamily.SansSerif,
    upperTitles = true,
    titleTracking = 5.sp,
    itemTracking = 0.5.sp,
    captionTracking = 2.sp,
    typeScale = 1f,
    pixelText = false,

    /* ---- chrome ---- */
    chrome = Chrome.BORDER,
    corner = 0.dp,
    divider = DividerStyle.HAIR,
    selection = SelectionStyle.BAR,
    // Un gris de papel, no un tinte del acento: sobre claro, un relleno de color se lee como
    // un subrayado de rotulador.
    selectionFill = Color(0xFFE8E8E2),
    pixelIcons = false,

    /* ---- ornament ---- */
    ornament = false,
    emblem = Emblem.DIAMOND,

    /* ---- screen ---- */
    phosphor = false,
    crt = CrtParams(curvature = 0f, scanline = 0f, mask = 0f, gain = 1f, halo = 0f),

    /* ---- scene and motion ---- */
    room = false,
    backdropVeil = 0.62f,
    cabinet = false,
    spins = false,
    // Y sin medallones en el cajon, por lo mismo: aqui no se mueve nada.
    appSpins = false,
    plates = true,
    soundFx = SoundFx.TAPE,
    tuning = null,
    videoGrow = 1f,
)

/**
 * Parlour: the system menus of a PSX survival horror.
 *
 * Not blackletter any more. The first version put a Parlour typeface on the titles, and it
 * read as a fantasy game, not as a haunted machine. What those games actually looked like
 * on the console — Silent Hill's options screen, Resident Evil's file select — is the
 * opposite: a stark monospace in capitals, tracked wide, in boxed panels with corner marks,
 * and a single dark red for whatever is selected. The horror was in the room around the
 * menu, and the menu itself was cold and bureaucratic. That contrast is the look.
 *
 * Near-black ground, white text, one red. The panel borders are barely darker than the
 * ground so the corner marks — in the grey of the dim text — are what draws the box.
 */
val ParlourTheme = Theme(
    id = "parlour",
    name = "Parlour",

    /* ---- colour ---- */
    ground = Color(0xFF0A0A0A),
    ink = Color(0xFFEDEDED),
    dim = Color(0xFF8C8C8C),
    faint = Color(0xFF4C4C4C),
    line = Color(0xFF2E2E2E),
    accent = Color(0xFFC4262A),
    light = false,

    /* ---- type ---- */
    display = FontFamily.Monospace,
    body = FontFamily.Monospace,
    upperTitles = true,
    // Muy abierto a proposito: en una monoespaciada estrecha, el espaciado es lo que
    // convierte una palabra en un rotulo.
    titleTracking = 6.sp,
    // Menos que el titulo: un nombre de juego largo con 3sp se cortaba a la mitad en la
    // columna de la lista, y lo que esta para leerse tiene que caber.
    itemTracking = 2.sp,
    captionTracking = 3.sp,
    typeScale = 0.9f,
    pixelText = true,

    /* ---- chrome ---- */
    chrome = Chrome.RULES,
    corner = 0.dp,
    divider = DividerStyle.LOZENGE,
    selection = SelectionStyle.BAR,
    // Rojo casi negro: se ve como un cambio de fondo, no como una luz. El rojo vivo va solo
    // en la barra del borde y en el texto de un boton activo.
    selectionFill = Color(0xFF2A0C0C),
    pixelIcons = true,

    /* ---- ornament ---- */
    ornament = true,
    emblem = Emblem.RECKONING,

    /* ---- screen ---- */
    phosphor = false,
    // La ganancia sube a 1.50 desde 1.32 porque al quitar la floración la imagen perdió nivel:
    // medido dentro del tubo, de 69,7 a 60,3 sobre 255. La sala no se tocó —medida sin la
    // pantalla da 5,67 antes y después, el mismo byte— pero la televisión es lo más claro del
    // cuadro y con ella apagándose parece que se apaga el cuarto entero.
    crt = CrtParams(curvature = 0.11f, scanline = 0.38f, mask = 0.28f, gain = 1.50f),

    /* ---- scene and motion ---- */
    room = true,
    backdropVeil = 0.45f,
    cabinet = true,
    spins = true,
    appSpins = true,
    plates = false,
    soundFx = SoundFx.TAPE,
    tuning = null,
    videoGrow = 1f,
)
/**
 * Mainframe, a la manera de Signalis.
 *
 * Los otros dos temas son una decoracion puesta encima de un menu. Este es lo contrario: la
 * pantalla ES una maquina encendida, y el menu es lo que esa maquina muestra. De ahi sale
 * todo lo demas —el tubo cargado, la trama, la letra de mapa de bits— y por eso es el unico
 * en el que el efecto de television no es un adorno que se pueda quitar sin perder el tema.
 *
 * Negro, hueso y UN fosforo. En Signalis ese fosforo es rojo, y no el rojo oscuro del Parlour
 * sino uno mas vivo y mas naranja: alli el rojo es sangre en la penumbra, aqui es luz saliendo
 * de un tubo. Se distinguen puestos uno al lado del otro, que es la prueba que importa.
 *
 * Con movimiento, al reves que el Gallery: una maquina encendida no esta quieta. Pero sin
 * sala —no tiene una todavia— asi que de fondo va lo mismo que el Gallery, una imagen
 * plana con su velo.
 *
 * Iconos de pixeles SI, letra de pixeles NO, y esa asimetria es a proposito: la lamina que
 * trae el programa es la de los dialogos de un juego concreto y se reconoce. Hasta que este
 * tema tenga la suya, escribe con una monoespaciada, que dice «terminal» sin decir «soy otro
 * juego». Ver `pixelText`.
 */
val MainframeTheme = Theme(
    id = "mainframe",
    name = "Mainframe",

    /* ---- colour ---- */
    // DOS COLORES Y NEGRO, y de ahi sale todo.
    //
    // No hay grises. En una pantalla de un solo fosforo no puede haberlos: lo que se ve o es
    // el fosforo encendido o es el hueco entre lineas. Lo que en otro tema seria «texto
    // secundario en gris» aqui es texto EN EL ACENTO, y lo apagado es el acento con menos
    // brillo — menos fosforo, no otro color. Es lo que hace que la pantalla parezca emitida y
    // no impresa.
    ground = Color(0xFF0A0A0A),
    // Blanco sin saturar: un fosforo no emite blanco de pantalla, y el blanco puro contra el
    // negro puro es el contraste de una hoja de calculo.
    ink = Color(0xFFD6D6D0),
    // El reparto no es «principal y secundario», es DATO y CROMO.
    //
    // En las maquinas que inspiran esto, lo que se ha venido a leer —el nombre del juego, la
    // cifra— va en blanco, y todo lo que lo enmarca —reglas, cejas, rotulos, unidades— va en
    // el fosforo. Asi que `dim` es el mismo blanco con menos brillo, no otro color: sigue
    // siendo dato, solo que no es el elegido.
    dim = Color(0xFF86867F),
    // Lo apagado de verdad: pistas, fechas, unidades. Era rojo —«cromo, o sea rojo»— y fijo, asi
    // que con el acento cambiado en Ajustes se quedaba rojo igual, y sobre este negro daba un
    // contraste de 2,4 a 1: en el cuaderno, las pistas, las fechas y las unidades no se leian.
    // Ahora es el mismo blanco que `dim` un punto mas bajo, a 4,8 a 1, que se lee sin competir
    // con el dato.
    faint = Color(0xFF7E7E78),
    // La regla va VACIA a proposito: aqui ES el acento, y vacia quiere decir eso. Estaba puesta
    // con el mismo rojo escrito por segunda vez, y al elegir otro acento en Ajustes se quedaban
    // atras las reglas, el filete de la caratula y las barras de estadisticas. Ver `line`.
    line = null,
    accent = Color(0xFFD8453A),
    light = false,

    /* ---- type ---- */
    display = FontFamily.Monospace,
    body = FontFamily.Monospace,
    upperTitles = true,
    titleTracking = 3.sp,
    itemTracking = 2.sp,
    captionTracking = 3.sp,
    typeScale = 1f,
    pixelText = false,

    /* ---- chrome ---- */
    // Cajas de instrumento, y la lista con todo el alto. Ver `Chrome.INSTRUMENT`.
    chrome = Chrome.INSTRUMENT,
    corner = 0.dp,
    divider = DividerStyle.SCAN,
    // La fila elegida se invierte entera y late, como un bloque de terminal: el bloque toma
    // el color de la TINTA y el texto el del fondo. Un marco dice «esto esta rodeado»; un
    // bloque dice «esto esta encendido», que es lo que dice el cursor de un terminal.
    selection = SelectionStyle.INVERT,
    selectionFill = Color.Transparent,
    pixelIcons = true,

    /* ---- ornament ---- */
    ornament = false,
    emblem = Emblem.DIAMOND,

    /* ---- screen ---- */
    phosphor = true,
    // El mas cargado de los tres: esta estetica ES una pantalla, no una decoracion. Y la señal
    // sale en UN color: saturacion a cero y el tinte en el acento, que es lo que hace que el
    // video del juego se vea como lo veria este aparato y no como una ventana al mundo.
    // El tubo, APAGADO. La deformacion y el barrido del cristal son para mas adelante: lo
    // que hace a este tema no es el efecto de television sino la interfaz, y con el cristal
    // puesto encima no se ve si la interfaz esta bien o mal. Lo unico que se queda de aqui es
    // el color: saturacion a cero y el tinte en el acento, que es lo que pinta la señal en un
    // solo fosforo.
    crt = CrtParams(
        curvature = 0f, scanline = 0f, mask = 0f, gain = 1f, halo = 0f,
        saturation = 0f, tint = Color(0xFFD8453A),
    ),

    /* ---- scene and motion ---- */
    // Sin sala, y hay que DECIRLO. Este tema no trae tvs.toml propio, asi que si hay uno comun
    // en el aparato —el del Parlour, por ejemplo— se encontraria de fondo el salon victoriano:
    // cambiaria la letra y el color, y el escenario seguiria siendo de otro.
    room = false,
    backdropVeil = 0.74f,
    // Y sin mueble. El televisor dibujado es un objeto —tiene carcasa, bisel y mandos— y
    // aqui no hay objetos: hay una pantalla. Ademas se contradecia solo, porque ese televisor
    // estaba enseñando una caratula quieta: un marco alrededor de una foto, no un aparato
    // encendido. La caratula se ve y ya, con el filo del tema alrededor.
    cabinet = false,
    // Con giros, y estuvieron apagados por una razon que ya no vale: que una pantalla de
    // fosforo no dibuja objetos con volumen. Eso era cierto del render de tres dimensiones;
    // los giros de este tema no lo son. Van convertidos a ASCII, o sea que lo que se mueve
    // en el panel son caracteres encendiendose y apagandose, que es exactamente lo que una
    // pantalla de fosforo sabe hacer. Ver ludolog-assets, themes/mainframe/media/systems/.
    spins = true,
    // Ni medallones: los del cajon son renders de volumen y ademas son los del vecino, porque
    // media/apps es una sola carpeta para todo el aparato. Ver `appSpins`.
    appSpins = false,
    plates = false,
    soundFx = SoundFx.DIGITAL,
    tuning = "INCOMING TRANSMISSION",
    videoGrow = 1.15f,
)

// El orden es el de la lista de Ajustes: primero el que no pide nada instalado.
//
// El claro NO esta aqui, y no es un olvido: no es un tema aparte sino la otra luz del mismo.
// Estuvo en la lista dos dias y era una entrada que mentia: quien elige un aspecto elige un
// aspecto, no dos veces el mismo con el papel cambiado, y ademas el que se dejara puesto
// decidia por su cuenta de que carpeta salian los sonidos.
val AllThemes = listOf(GalleryTheme, ParlourTheme, MainframeTheme)

/**
 * La version en claro de un tema, si la tiene.
 *
 * Un mapa aparte y no un campo del propio tema: un `Theme` que se apunta a otro `Theme` es una
 * referencia circular en un data class, y ademas nadie que dibuje necesita saber que existe la
 * otra luz. Solo lo necesita quien resuelve cual va puesta.
 */
private val BrightVariants: Map<String, Theme> = mapOf(GalleryTheme.id to GalleryLightTheme)

/** Si este tema tiene otra luz, y cual. */
fun Theme.bright(): Theme? = BrightVariants[id]

/**
 * El id del aspecto ELEGIDO, que no es el del objeto cuando lo que se ve es su otra luz.
 *
 * Quien pregunta «cual esta puesto» quiere saber que aspecto eligio la persona, no en que luz
 * lo esta mirando. Sin esto, la lista de aspectos dejaba de marcar «in use» en cuanto se
 * encendia el modo claro: el tema puesto pasaba a ser un objeto que no esta en la lista.
 */
val Theme.chosenId: String
    get() = BrightVariants.entries.firstOrNull { it.value.id == id }?.key ?: id

fun themeById(id: String?): Theme = when (LEGACY_THEME_IDS[id] ?: id) {
    // El claro fue un tema con id propio antes de ser un modo. Quien lo tuviera puesto se
    // queda donde estaba en vez de aparecer de golpe en otro sitio.
    GalleryLightTheme.id -> GalleryTheme
    else -> AllThemes.firstOrNull { it.id == (LEGACY_THEME_IDS[id] ?: id) } ?: GalleryTheme
}

/**
 * Los nombres de antes de los temas, y los de ahora (05-10-2026). Los ids pasaron a ser los
 * nombres que se ven —Parlour, Mainframe, Gallery— para que carpetas, zips y ajustes digan lo
 * mismo que la pantalla. Lo que se guardo con el viejo se reconoce y se pasa al nuevo: ver
 * Prefs.renameThemes y ThemeFiles.renameFolders.
 */
val LEGACY_THEME_IDS: Map<String?, String> = mapOf(
    "gothic" to "parlour", "retrofuture" to "mainframe",
    "minimal" to "gallery", "minimal-light" to "gallery-light",
)

/**
 * Colours the accent can be switched to, from Settings: a short named list for each theme.
 *
 * The accent is the one colour in this interface, so changing it changes the whole mood
 * with a single value: the cursor, the rules, the title of the dialog, the medallion frame.
 * A short named list rather than a colour picker, because on a pad a picker is a chore and
 * a list can be walked with one thumb while the screen behind shows the result.
 *
 * Una lista por tema y no una para todos (05-10-2026): la de antes mezclaba la sangre del
 * Parlour con el hielo y el rosa, y en el Mainframe un acento es el color de un fosforo, que
 * no es cualquier color. Cada una medida contra su fondo, a 3:1 o mas:
 *
 * - Parlour: los de una casa victoriana de noche —sangre, brasa, vela, laton, hueso, cardenillo,
 *   amatista, luz de luna—.
 * - Mainframe: fosforos y pantallas de verdad —rojo, verde, ambar, blanco frio, plasma, radar,
 *   tubo fluorescente (VFD)—.
 * - Gallery: tintas de museo, medias a proposito: el mismo acento sirve en la luz oscura y en
 *   la clara (comparten ajuste), asi que cada una se lee sobre negro Y sobre papel.
 */
fun accentPresets(themeId: String): List<Pair<String, Long?>> = listOf("Theme default" to null) +
    when (themeById(themeId).id) {
        ParlourTheme.id -> listOf(
            "Blood" to 0xFFC4262A, "Claret" to 0xFFB8365E, "Ember" to 0xFFD9622B,
            "Candle" to 0xFFE0B24A, "Brass" to 0xFFB8913A, "Bone" to 0xFFD8CFC0,
            "Verdigris" to 0xFF4E9C86, "Amethyst" to 0xFF8E5BB8, "Moonlight" to 0xFF8FA6C4,
        )
        MainframeTheme.id -> listOf(
            "Red phosphor" to 0xFFD8453A, "Green phosphor" to 0xFF33E066, "Amber" to 0xFFFFB000,
            "Cold white" to 0xFFC9D8E0, "Plasma" to 0xFFFF6A1A, "Radar" to 0xFF4FA3FF,
            "VFD" to 0xFF2FE0C8, "Lime" to 0xFFB6E83A, "Magenta" to 0xFFE04FC4,
        )
        else -> listOf(
            "Graphite" to 0xFF5F7487, "Prussian" to 0xFF3F6E9E, "Teal" to 0xFF2E8A8A,
            "Sage" to 0xFF6A8A6E, "Olive" to 0xFF6E7F3A, "Ochre" to 0xFFA8792A,
            "Terracotta" to 0xFFB4573A, "Oxblood" to 0xFFA83A4A, "Plum" to 0xFF8A4F8E,
        )
    }

fun accentHex(argb: Long?): String? = argb?.let { "0x%08X".format(it) }

/** Su nombre en la lista de ese tema; si es de otra lista (una eleccion de antes), su numero. */
fun accentName(hex: String?, themeId: String): String =
    accentPresets(themeId).firstOrNull { accentHex(it.second) == hex }?.first ?: hex ?: "Theme default"

/**
 * The theme with another accent. The selection fill follows it, darkened almost to the
 * ground, so a teal accent gets a teal-black bar and not a red one.
 */
fun Theme.withAccent(hex: String?): Theme {
    val argb = hex?.removePrefix("0x")?.toLongOrNull(16) ?: return this
    val c = Color(argb.toULong().toLong())
    return copy(
        accent = c,
        selectionFill = lerp(ground, c, 0.20f),
        // En un tema de fosforo, el tinte del tubo ES el acento: si se cambia uno y el otro no,
        // la television se queda emitiendo en el color del tema anterior mientras todo lo demas
        // ya ha cambiado, que es de las cosas que mas cantan.
        crt = if (phosphor) crt.copy(tint = c) else crt,
    )
}

/**
 * Cuanto se levanta la claridad al pintarla de fosforo.
 *
 * Vive aqui y no dentro del filtro porque hay DOS sitios que pintan fosforo: las imagenes
 * fijas, con una matriz de color, y el giro de la consola, dentro de su shader. Con el numero
 * escrito dos veces, cambiar uno y olvidar el otro deja la caratula y la consola de dos rojos
 * distintos en la misma pantalla.
 */
const val PHOSPHOR_GAIN = 1.18f
