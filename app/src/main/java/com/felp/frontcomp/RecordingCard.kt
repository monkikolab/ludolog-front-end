package com.felp.frontcomp

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb

/**
 * La tarjeta que asoma en la esquina al empezar a jugar.
 *
 * Dice lo que el cuaderno ya sabe de ESTE juego —por cuantas veces va esta partida, cuanto se
 * lleva jugado, cuanta bateria daria— y con eso dice de sobra que lo esta apuntando. En
 * RetroCompanion la tarjeta tenia ademas que preguntar si habia acertado de juego, porque lo
 * adivinaba; aqui no hay nada que preguntar: el front-end lanzo la partida.
 *
 * Vistas planas, no Compose. Una capa de Compose colgada de un servicio necesita un dueno de
 * ciclo de vida, otro de estado guardado y un recompositor enganchados a una ventana que no es
 * de ninguna actividad: mucha maquinaria para tres lineas que no cambian una vez dibujadas.
 *
 * La ventana es WRAP_CONTENT y se coloca por gravedad. Lo evidente seria una ventana
 * transparente a pantalla completa con la tarjeta dentro, pero eso reserva un lienzo de
 * 1920x1080 —unos ocho megas, veinte veces lo que esto necesita— y le entrega al compositor
 * una capa del tamano de la pantalla por encima de un juego en marcha.
 *
 * Y va sin foco y sin tacto, para no robarle una tecla al emulador, no sacarlo del modo
 * inmersivo y no tragarse un toque que iba a sus botones. Se quita cuando termina en vez de
 * esconderse: entre partida y partida no hay ventana, ni superficie, ni lienzo.
 */
internal object RecordingCard {

    /**
     * Lo que el cuaderno ya sabia de este juego cuando se abrio la partida.
     *
     * Lo trae hecho quien la llama, desde el hilo de medir: son dos consultas y esto se
     * dibuja en el hilo principal, que en ese instante esta levantando un emulador.
     */
    data class Note(
        val title: String,
        val system: String?,
        /** Horas que la carga de ahora daria EN ESTE juego. Nulo = no hay con que estimarlo. */
        val hoursLeft: Float?,
        val sessions: Int,
        val totalMs: Long,
        /** La mision que se sigue y que este juego sirve para cumplir: lo que pide. Ver Missions. */
        val mission: String? = null,
    )

    /**
     * Lo que espera antes de asomarse.
     *
     * El servicio arranca en el mismo instante que el emulador, y el emulador tarda un rato
     * en tener algo en pantalla. Sin esta pausa la tarjeta saldria encima del front-end, que
     * es justo donde no hace falta: quien acaba de dar a jugar ya sabe que ha dado a jugar.
     */
    private const val DELAY_MS = 1_500L

    /** Y lo que tarda en irse: lo justo para leer tres lineas sin llegar a molestar. */
    private const val VISIBLE_MS = 5_000L

    /**
     * Lo que mide la tarjeta de ancho: lo mismo en los tres temas.
     *
     * Fija y no WRAP_CONTENT, por dos razones medidas. La primera, que con WRAP_CONTENT no
     * salian iguales: cada tema escribe con su letra, la monoespaciada es bastante mas ancha,
     * y la tarjeta del Gallery media 264 puntos mientras la del Parlour llegaba a 320.
     *
     * La segunda, que ni siquiera se ajustaba al texto. Android mide una ventana WRAP_CONTENT
     * probando primero con el ancho preferido de un dialogo —320 puntos, o sea 720 pixeles en
     * este aparato—, y como un TextView no avisa de que no le cabe, se quedaba en esos 320. La
     * del Parlour medía 718: por eso la del Parlour y la del Mainframe cortaban «~9h 55m» y
     * perdian el «left».
     *
     * 280 es lo que ocupa la linea de datos de siempre en monoespaciada a nueve puntos, con su
     * emblema y su aire. La que no quepa encoge la letra —ver `line`—, no el ancho.
     */
    private const val WIDTH_DP = 280

    /**
     * El emblema, del mismo tamano en los tres: el libro, la chapa y la gema.
     *
     * Iban a 30, 28 y 26, uno por tema, y con eso la tarjeta de cada tema tenia otra altura
     * de aire alrededor. A 26 caben los tres dentro de la tarjeta compacta sin mandar en su
     * altura —la ponen las dos lineas de texto— y el ojo de la gema, que es lo mas pequeno de
     * los tres, todavia se lee.
     */
    private const val EMBLEM_DP = 26

    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null

    /**
     * La ensena, si se puede.
     *
     * El permiso de dibujar encima lo concede la persona en los ajustes del sistema y no hay
     * forma de pedirlo desde aqui sin sacarla de su juego. Sin el, no hay tarjeta y ya: la
     * partida se apunta igual, que es lo que importa.
     */
    fun show(ctx: Context, note: Note) {
        if (note.title.isBlank()) return
        if (!Prefs(ctx.applicationContext).overlay) return
        if (!Settings.canDrawOverlays(ctx)) return
        val app = ctx.applicationContext
        main.postDelayed({ runCatching { put(app, note) } }, DELAY_MS)
    }

    /** Se quita antes de tiempo si la partida no llego ni a durar lo que la tarjeta. */
    fun hide() {
        main.removeCallbacksAndMessages(null)
        main.post { runCatching { drop() } }
    }

    private fun put(app: Context, note: Note) {
        drop()
        // En la pantalla del juego, que con dos puede no ser la de la consola: ver DualPlay.
        val ctx = runCatching { DualPlay.overlayContext(app) }.getOrDefault(app)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val card = build(ctx, note)
        wm.addView(card, params())
        shown = card
        main.postDelayed({ runCatching { drop() } }, VISIBLE_MS)
    }

    private fun drop() {
        val v = shown ?: return
        shown = null
        val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
    }

    private fun params() = WindowManager.LayoutParams(
        dp(WIDTH_DP),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Arriba a la derecha: abajo viven los botones en pantalla de casi todos los
        // emuladores, y a la izquierda su menu.
        gravity = Gravity.TOP or Gravity.END
        x = dp(16)
        y = dp(16)
    }

    /**
     * La tarjeta: el emblema, una regla del color del tema a la izquierda, y dos lineas.
     *
     * El titulo siempre; debajo, lo que el cuaderno sabe de este juego. Y nada mas: ver
     * `facts` para por que no hay una linea de estado.
     *
     * El color sale de la preferencia y, si no hay ninguna elegida, del tema guardado. Aqui no
     * hay arbol de Compose del que sacarlo, pero las preferencias estan igual de a mano y un
     * Color de Compose es un valor, no una vista: se lee fuera del arbol sin problema.
     */
    private fun build(ctx: Context, note: Note): View {
        val prefs = Prefs(ctx)
        // La tarjeta se viste con el tema puesto, no con un negro fijo.
        //
        // Aqui no hay arbol de Compose del que sacarlo —esto cuelga de un servicio— pero el
        // tema es un objeto de datos y las preferencias estan igual de a mano: se lee igual de
        // bien fuera del arbol. Sin esto, un tema de papel sacaba encima del juego una tarjeta
        // negra con letra de maquina de escribir, que es la del vecino.
        val look = themeById(prefs.themeId)
        val accent = prefs.accent?.removePrefix("0x")?.toLongOrNull(16)?.toInt()
            ?: look.accent.toArgb()
        val ground = look.ground.toArgb()
        val ink = look.ink.toArgb()
        val dim = look.dim.toArgb()
        // La misma letra que el resto del tema. Es lo unico que hay que traducir a mano: el
        // tema habla de familias de Compose y una vista plana quiere un Typeface.
        val face = if (look.body == androidx.compose.ui.text.font.FontFamily.Monospace)
            Typeface.MONOSPACE else Typeface.SANS_SERIF

        val lines = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(10), dp(7))
            // El resto del ancho, sea el que sea: la tarjeta lo fija y aqui se reparte.
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { gravity = Gravity.CENTER_VERTICAL }
            addView(line(ctx, note.title, ink, 12f, face))
            facts(prefs, note)?.let { addView(line(ctx, it, dim, 9f, face, shrinkTo = 7.5f)) }
            // La mision, en el acento y en dos renglones como mucho: es un recordatorio, no un
            // texto que haya que leer en medio del juego.
            note.mission?.let { m ->
                addView(line(ctx, "◆ $m", accent, 9f, face).apply { isSingleLine = false; maxLines = 2 })
            }
        }
        val rule = View(ctx).apply {
            setBackgroundColor(accent)
            layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        // El emblema del cuaderno, que es lo que firma la tarjeta.
        //
        // Sin el, la tarjeta podria ser de cualquier cosa que se ponga encima de un juego. Con
        // el, quien ya lo ha visto en la lista de consolas sabe de que programa viene antes de
        // leer una palabra, que es para lo que sirve un emblema.
        val mark = android.widget.ImageView(ctx).apply {
            // Pintado al tamano exacto al que se va a ver, y CENTER para que nadie lo
            // reescale por el camino. Un emblema dibujado a un tamano y estirado a otro pierde
            // justo lo que lo hace legible a veinte puntos: el filo del ojo.
// Y del mismo tamano en los tres temas, sea libro, chapa o gema: ver EMBLEM_DP.
            // Que marca, segun el tema.
            //
            // Primero la del propio tema, si trae una dibujada: es un SVG y de el se saca la
            // silueta, que es lo unico que se puede tenir del acento. Si no la trae, la que el
            // tema diga de las dos del programa. Ver `Emblem`.
            setImageBitmap(
                ThemeFiles.companion(ctx)?.let { SvgMark.bitmap(it, dp(EMBLEM_DP), accent) }
                    ?: when (look.emblem) {
                        Emblem.RECKONING -> ReckoningMark.bitmap(dp(EMBLEM_DP), accent)
                        Emblem.DIAMOND -> DiamondMark.bitmap(dp(EMBLEM_DP), accent)
                    },
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(9)
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.argb(0xEE, Color.red(ground), Color.green(ground), Color.blue(ground)))
                // El filo, sacado de la TINTA y no de la regleta.
                //
                // La regleta del Parlour es gris oscuro y sobre una tarjeta casi negra no se
                // ve: la tarjeta se quedaba sin canto contra el juego. La tinta al treinta y
                // tres por ciento funciona en las dos luces —un pelo claro sobre lo oscuro, un
                // pelo oscuro sobre el papel— y es exactamente el filo que la tarjeta tenia
                // antes de que esto fuera del tema.
                setStroke(dp(1), Color.argb(0x55, Color.red(ink), Color.green(ink), Color.blue(ink)))
            }
            addView(rule)
            addView(mark)
            addView(lines)
        }
    }

    /**
     * Lo que la tarjeta cuenta debajo del titulo.
     *
     * Aqui iba antes una linea de estado —«RECORDING»— en el color de acento, y era el error
     * de la tarjeta: encima de un juego, esa palabra se lee como que se esta capturando la
     * pantalla. Y no era un problema de palabra sino de forma. Una linea de estado anuncia una
     * ACCION en marcha, y una accion en marcha encima de un juego solo puede ser una: grabar.
     *
     * Asi que no hay estado, hay HECHOS: la consola, por cuantas veces va esta, cuanto se lleva
     * jugado y cuanta bateria daria. Un dato como «session 6» solo lo puede decir algo que
     * lleva la cuenta, asi que anunciar ademas que la lleva sobra. Y quien firma la tarjeta es
     * el emblema de la izquierda, que para eso esta.
     *
     * El orden es el de una frase: que es, cuantas veces, cuanto tiempo, cuanto queda. Lo que
     * se apague en ajustes simplemente no aparece, y con todo apagado queda el titulo, que
     * sigue diciendo que el front-end sabe que has abierto esto.
     */
    private fun facts(prefs: Prefs, note: Note): String? {
        val parts = buildList {
            if (prefs.overlaySystem) note.system?.uppercase()?.let(::add)
            // ESTA partida, no las de antes: `sessions` cuenta las cerradas y esta acaba de
            // empezar. «6 sessions» al abrir la septima es una cifra correcta y una frase
            // equivocada.
            if (prefs.overlaySessions) {
                add(if (note.sessions == 0) "first session" else "session ${note.sessions + 1}")
            }
            // En la primera no hay tiempo jugado que dar, y «0m played» no informa: confunde.
            if (prefs.overlayPlayed && note.totalMs > 0) add("${span(note.totalMs)} played")
            if (prefs.overlayBattery) note.hoursLeft?.let { add("~${span(it)} left") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Una linea de la tarjeta, que nunca se sale de ella.
     *
     * Con `shrinkTo` la letra puede ENCOGER hasta ese tamano para que la linea quepa entera;
     * sin el, lo que no quepa se corta con puntos suspensivos. El titulo se corta y los datos
     * encogen, y no es capricho: un titulo largo sigue diciendo que juego es con la mitad de
     * sus letras, pero un dato cortado miente —«~9h 55» no es lo que queda de bateria—, y lo
     * ultimo de la linea de datos es justo la bateria.
     *
     * Encoger va con `maxLines` y no con `isSingleLine`, y no por gusto: una linea unica
     * desplaza el texto en horizontal, y a un texto que se desplaza siempre le cabe todo, asi
     * que el ajuste automatico nunca llega a encoger nada.
     */
    private fun line(
        ctx: Context,
        text: String,
        colour: Int,
        size: Float,
        face: Typeface,
        shrinkTo: Float? = null,
    ) = TextView(ctx).apply {
        this.text = text
        setTextColor(colour)
        typeface = face
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        if (shrinkTo == null) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        } else {
            // El tamano de partida, ANTES de encender el ajuste: con el ajuste encendido,
            // setTextSize ya no hace nada. Y sin tamano de partida la linea se mide con los
            // catorce puntos que Android pone por defecto y se dibuja a nueve: medida en el
            // aparato, la tarjeta salia con el alto de antes aunque la letra fuera menor.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(
                sp(shrinkTo), sp(size), 1, TypedValue.COMPLEX_UNIT_PX,
            )
        }
    }

    private fun sp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, android.content.res.Resources.getSystem().displayMetrics,
    ).toInt()

    /** Horas y minutos, sin ceros de adorno: "2h 10m", "40m". */
    private fun span(hours: Float): String = span((hours * 3_600_000f).toLong())

    private fun span(ms: Long): String {
        val mins = ms / 60_000L
        return if (mins < 60) "${mins}m" else "${mins / 60}h ${mins % 60}m"
    }

    private fun dp(v: Int): Int =
        (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
