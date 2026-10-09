package com.felp.frontcomp

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Los sonidos de la interfaz.
 *
 * Los cortos por un SoundPool, incluido el ambiente cuando cabe. Los clics por lo de siempre
 * —se descomprimen al arrancar y suenan sin latencia, que es lo unico que importa cuando el
 * sonido tiene que caer encima de la pulsacion—, y el ambiente porque un SoundPool repite su
 * propio PCM ya descodificado y eso no deja hueco. Lo que no cabe va por LoopTrack, y nada por
 * MediaPlayer: ver LoopTrack.
 *
 * Van declarados como USAGE_MEDIA y no como sonido de sistema. Lo segundo parece lo
 * correcto y es justo lo que no se oye: en muchos aparatos esa via cuelga del volumen de
 * notificaciones, que suele estar bajo o mudo, asi que los clics se reproducian de verdad
 * —el registro del sistema los contaba— sin que saliera nada por el altavoz.
 *
 * Es un objeto suelto y no algo que viaje por la composicion a proposito. Un sonido de
 * interfaz no es estado: no se dibuja, no cambia lo que se ve, y pasarlo de mano en mano
 * por veinte composables solo para que el ultimo pueda hacer un clic seria mucho cableado
 * para nada.
 */
internal object Sfx {

    /** Lo que puede sonar. Un nombre por gesto, no por fichero. */
    enum class Cue(val file: String) {
        /** El selector se ha movido de fila. */
        MOVE("move.ogg"),
        /** Se entra: de consolas a juegos, al cajon de apps, de una consola a la siguiente. */
        OPEN("open.ogg"),
        /** Se sale: atras, o se cierra lo que estuviera abierto. */
        CLOSE("close.ogg"),
        /** Se abre un menu rapido o los ajustes. */
        MENU("options.ogg"),
        /** Se entra al cuaderno, que no es un menu mas sino otra sala. */
        BOOK_OPEN("companion_open.ogg"),
        BOOK_CLOSE("companion_close.ogg"),
    }

    private const val AMBIENCE_FILE = "sounds/ambience.ogg"

    /** Debajo de todo lo demas: es un fondo, no una pista. */
    private const val AMBIENCE_LEVEL = 0.6f

    /**
     * Dos reproductores y no uno, y esta es la razon.
     *
     * Un SoundPool tiene un numero fijo de huecos, y un sonido ocupa el suyo mientras dura
     * el FICHERO, no mientras dura lo que se oye. Con clics de nueve segundos de los que
     * medio tiene sonido, bajar por una lista llena los huecos en un momento. Cuando no
     * queda sitio, el pool echa al de menor prioridad y, si empatan, al mas viejo; el
     * ambiente es siempre el mas viejo porque arranca el primero y no termina nunca, asi
     * que era el primero en caer y el fondo se apagaba solo.
     *
     * Subirle la prioridad lo arreglaba en teoria y no habia forma de comprobarlo desde
     * fuera: un SoundPool no dice si una corriente sigue viva. Con dos pools separados el
     * fallo deja de depender de una regla de desempate y pasa a ser imposible: el ambiente
     * tiene su propio hueco y nadie mas puede pedirlo.
     */
    private var pool: SoundPool? = null
    private var ambiencePool: SoundPool? = null

    private val ids = HashMap<Cue, Int>()
    private val ready = HashSet<Int>()

    private var ambienceId = 0
    private var ambienceStream = 0
    private var wantAmbience = false

    /**
     * El ambiente largo se toca con un reproductor, no con el SoundPool.
     *
     * Un SoundPool descomprime la muestra ENTERA en memoria y tiene un techo por muestra de un
     * megabyte de PCM. A veintidos mil hercios en mono eso son unos veinticuatro segundos: un
     * fichero mas largo no falla ni avisa —carga con status cero— sino que se queda CORTADO, y
     * lo que se oye es el principio repitiendose. Medido con el ambiente de cuatro minutos y
     * medio del Mainframe: volvia a empezar a los veinte segundos.
     *
     * Asi que el que no quepa va por LoopTrack, que lo descodifica a medida que suena y no
     * gasta memoria. Iba por MediaPlayer, que al dar la vuelta dejaba un hueco y ademas abria
     * su pista en otra salida del sistema, lo que en este aparato baja todo lo demas: ver
     * LoopTrack. El corto se queda en el SoundPool, que repite el PCM ya descomprimido sin
     * costura.
     */
    private var ambienceMusic: LoopTrack? = null

    /** El fichero del ambiente cuando toca transmitirlo, o nulo si cabe en el SoundPool. */
    private var ambienceBig: java.io.File? = null

    /**
     * El sonido del video que se esta viendo, en su propio reproductor.
     *
     * Tercer pool, con un solo hueco, por lo mismo que el ambiente: asi ni los clics pueden
     * echarlo ni el puede echar a nadie. Y va por aqui y no por el reproductor de video
     * porque ese abre una corriente que hace que el aparato baje la salida entera.
     *
     * Se descomprime entero al cargarlo, que para un recorte de quince segundos en mono son
     * unos seiscientos kilobytes y una decima de segundo. Ese es el precio de que no
     * moleste: el reproductor de video iba leyendo del fichero segun lo necesitaba.
     */
    private var clipPool: SoundPool? = null
    private var clipId = 0
    private var clipStream = 0
    private var clipPath: String? = null
    private var clipLevel = 1f

    /**
     * El sonido del video cuando no cabe en el SoundPool, transmitido como el ambiente largo.
     *
     * El pool corta cada muestra en un mega de PCM (ver tooBigForPool), y con el sonido que
     * saca TapeAudio —22 kHz en mono— eso son unos veinticuatro segundos. Un recorte de treinta,
     * o el video entero, volvia a empezar a los veinticuatro mientras la imagen seguia: cada
     * vuelta se desfasaba mas y la musica del titulo acababa sonando encima del juego.
     */
    private var clipMusic: LoopTrack? = null
    private var clipBig = false

    /** Lo ya medido, para no abrir el fichero cada vez que se vuelve al mismo juego. */
    private val clipTooBig = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Mira de antemano, fuera del hilo de la pantalla, si el sonido de un juego cabe en el SoundPool:
     * es abrir el fichero, y se hacia en el principal al llegar a cada juego (revision del
     * 09-10-2026). Ver [clip], que despues ya lo encuentra sabido.
     */
    fun probe(file: java.io.File) {
        probed[file.path] = clipTooBig.getOrPut("${file.path}@${file.lastModified()}") { tooBigForPool(file) }
    }

    /** Lo que dijo [probe] de cada ruta: [clip] lo usa sin mirar la fecha del fichero. */
    private val probed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Con la aplicacion al fondo. Mientras, nada EMPIEZA a sonar: lo que se pida se deja para
     * la vuelta.
     *
     * Pausar lo que suena no bastaba. Entre la pausa y la parada, Compose sigue componiendo
     * mientras se abre el emulador, y un sonido pedido en ese rato —o uno pedido justo antes que
     * terminaba de descomprimirse despues— arrancaba en bucle debajo del juego toda la partida.
     *
     * Y se empieza al fondo: hasta el primer onResume no suena nada.
     */
    private var suspended = true

    /** El sonido del video, listo con la aplicacion al fondo: suena al volver. */
    private var clipPending = false

    /**
     * Pone el sonido de este video, o lo quita si es null.
     *
     * Volver a pedir el mismo no hace nada: navegar por una lista vuelve a componer, y
     * recargar el mismo recorte en cada recomposicion seria descomprimirlo sin motivo.
     */
    fun clip(file: java.io.File?, level: Float) {
        // Con el ajuste del sonido de video: ver SoundGains.
        clipLevel = level * SoundGains.lin("video")
        val sp = clipPool ?: return
        if (file == null) {
            stopClip(sp)
            return
        }
        if (clipPath == file.path) {
            if (clipStream != 0) runCatching { sp.setVolume(clipStream, clipLevel, clipLevel) }
            clipMusic?.setVolume(clipLevel)
            return
        }
        stopClip(sp)
        clipPath = file.path
        clipBig = probed[file.path] ?: clipTooBig.getOrPut("${file.path}@${file.lastModified()}") { tooBigForPool(file) }
        if (clipBig) {
            if (suspended) clipPending = true else startClip()
            return
        }
        clipId = runCatching { sp.load(file.path, 1) }.getOrDefault(0)
    }

    /** Hace sonar el sonido del video ya pedido: del pool si cupo, transmitido si no. */
    private fun startClip() {
        val path = clipPath ?: return
        if (clipBig) {
            if (clipMusic == null) {
                clipMusic = LoopTrack(ATTRS, clipLevel) { ex -> ex.setDataSource(path); null }.start()
            }
            return
        }
        val cp = clipPool ?: return
        if (clipId == 0 || clipStream != 0) return
        clipStream = runCatching { cp.play(clipId, clipLevel, clipLevel, 0, -1, 1f) }.getOrDefault(0)
    }

    /**
     * El ambiente largo, en pausa mientras la aplicacion esta al fondo.
     *
     * Se tiraba y al volver se hacia otro, que empieza por el principio: cada vuelta de una
     * partida repetia el arranque del ambiente de cuatro minutos y medio. En pausa sigue desde
     * donde iba, que es para lo que LoopTrack.pause existe.
     */
    private var ambienceHeld: LoopTrack? = null

    /** Al irse la aplicacion al fondo: el sonido del video tambien se calla. */
    fun suspendAll() {
        suspended = true
        ambienceMusic?.let { it.pause(); ambienceHeld = it; ambienceMusic = null }
        ambience(false)
        runCatching { clipPool?.autoPause() }
        clipMusic?.pause()
    }

    fun resumeAll() {
        suspended = false
        ambienceHeld?.let { held ->
            ambienceHeld = null
            // El mismo, si sigue haciendo falta: con el ambiente apagado entretanto, se suelta.
            if (ambienceOn && ambienceBig != null) {
                held.setVolume(level())
                ambienceMusic = held.start()
            } else held.stop()
        }
        ambience(true)
        runCatching { clipPool?.autoResume() }
        clipMusic?.start()
        if (clipPending) {
            clipPending = false
            startClip()
        }
    }

    /**
     * El ambiente, callado por reposo largo: Ludolog en pantalla y nadie tocando nada desde hace
     * minutos (ver MainActivity.noteInput). Un bucle sonando a nadie mantiene encendidos el
     * procesador de audio y el amplificador todo el rato; con la consola dejada en la mesa eso
     * es bateria tirada. Se apaga en fundido, sin corte, y [wake] lo devuelve con la primera tecla.
     */
    private var resting = false
    private val fader = android.os.Handler(android.os.Looper.getMainLooper())

    fun rest() {
        if (suspended || resting || !wantAmbience) return
        resting = true
        val steps = 20
        for (i in 1..steps) {
            fader.postDelayed({
                if (!resting) return@postDelayed
                val v = level() * (1f - i / steps.toFloat())
                ambienceMusic?.setVolume(v)
                ambiencePool?.let { ap -> if (ambienceStream != 0) runCatching { ap.setVolume(ambienceStream, v, v) } }
                if (i == steps) {
                    ambienceMusic?.let { it.pause(); ambienceHeld = it; ambienceMusic = null }
                    ambience(false)
                    // Lo que se queria sigue queriendose: ambience(false) lo apunta como no.
                    wantAmbience = ambienceOn
                }
            }, i * 100L)
        }
    }

    fun wake() {
        if (!resting) return
        resting = false
        fader.removeCallbacksAndMessages(null)
        if (suspended) return
        // A medio fundido: se vuelve a su volumen y ya.
        ambienceMusic?.setVolume(level())
        ambiencePool?.let { ap -> if (ambienceStream != 0) runCatching { ap.setVolume(ambienceStream, level(), level()) } }
        ambienceHeld?.let { held ->
            ambienceHeld = null
            if (ambienceOn && ambienceBig != null) {
                held.setVolume(level())
                ambienceMusic = held.start()
            } else held.stop()
        }
        if (ambienceMusic == null && ambienceStream == 0) ambience(true)
    }

    private fun stopClip(sp: SoundPool) {
        if (clipStream != 0) runCatching { sp.stop(clipStream) }
        if (clipId != 0) runCatching { sp.unload(clipId) }
        clipMusic?.stop()
        clipMusic = null
        clipBig = false
        clipPending = false
        clipStream = 0
        clipId = 0
        clipPath = null
    }

    /** Si suenan los cortos y si suena el ambiente. Lo pone quien lee las preferencias. */
    var uiOn = true
    var ambienceOn = true

    // ------------------------------------------------------------------------- el cuaderno

    /**
     * Cuanto baja el ambiente dentro del cuaderno: a la mitad.
     *
     * El cuaderno tuvo su propia musica, una pieza que sustituia al ambiente al entrar. Se quito
     * para que un tema sea UN fondo y no dos: la plantilla pedia dos ficheros, el
     * Mainframe ponia el mismo en los dos sitios, y cada pieza de mas era otra cosa que
     * encontrar y que nivelar. Ahora entrar al cuaderno no cambia de fondo, lo baja: se nota
     * que se ha entrado en otra cosa sin dejar de estar en el mismo sitio.
     */
    private const val BOOK_DUCK = 0.5f

    /** Verdadero mientras el cuaderno esta abierto. */
    private var inBook = false

    /** El volumen del ambiente ahora mismo: entero fuera del cuaderno, a la mitad dentro. */
    private fun level(): Float =
        (AMBIENCE_LEVEL * (if (inBook) BOOK_DUCK else 1f) * SoundGains.lin("ambience")).coerceAtMost(1f)

    /**
     * Vuelve a poner el volumen del ambiente y del sonido del video que esten sonando: al mover
     * su ajuste en los ajustes se oye al momento, sin tener que salir.
     */
    fun refreshLevels(videoLevel: Float) {
        ambienceMusic?.setVolume(level())
        ambiencePool?.let { ap -> if (ambienceStream != 0) runCatching { ap.setVolume(ambienceStream, level(), level()) } }
        clipLevel = videoLevel * SoundGains.lin("video")
        clipMusic?.setVolume(clipLevel)
        clipPool?.let { cp -> if (clipStream != 0) runCatching { cp.setVolume(clipStream, clipLevel, clipLevel) } }
    }

    /**
     * Entra o sale del cuaderno: baja el ambiente a la mitad, o lo devuelve.
     *
     * Sobre lo que ya este sonando, sin pararlo: el fondo no se corta ni vuelve a empezar. Y si
     * el ambiente arranca con el cuaderno ya abierto —al volver de segundo plano—, arranca ya a
     * la mitad, porque todo lo que lo arranca pregunta a `level`.
     */
    fun book(on: Boolean) {
        inBook = on
        val v = level()
        ambienceMusic?.setVolume(v)
        val ap = ambiencePool
        if (ap != null && ambienceStream != 0) runCatching { ap.setVolume(ambienceStream, v, v) }
    }

    /**
     * La clase de TODO lo que suena aqui: los clics, el ambiente, el ambiente largo y la musica
     * del cuaderno. Una sola, y en un solo sitio.
     *
     * Ojo, que la clase NO decide la salida, aunque lo parezca: la musica del cuaderno iba como
     * CONTENT_TYPE_MUSIC, se paso a esta, y medido en `dumpsys media.audio_flinger` seguia
     * saliendo por la de bufer profundo. Lo que la saca de ahi es no usar MediaPlayer: ver
     * LoopTrack. Esto es la clase y nada mas; que sea la misma para todo es orden, no arreglo.
     */
    private val ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()


    fun load(ctx: Context) {
        if (pool != null) return
        val attrs = ATTRS

        // Doce huecos para los cortos: uno ocupa el suyo mientras dura el fichero. Sobran desde
        // que los del Parlour dejaron de arrastrar nueve segundos de silencio cada uno, pero no
        // cuestan nada y un tema nuevo puede traer colas largas.
        val sp = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        sp.setOnLoadCompleteListener { _, sample, status ->
            // Un sonido que el aparato no puede descomprimir se queda fuera en silencio, y
            // eso es media hora buscando por que no suena. Se dice en el registro.
            android.util.Log.i("Ludolog", "sfx sample=$sample status=$status")
            if (status == 0) ready += sample
        }
        // El fichero del tema instalado manda sobre el del APK, uno a uno.
        for (cue in Cue.entries) {
            runCatching {
                val own = ThemeFiles.sound(ctx, cue.file)
                if (own != null) {
                    ids[cue] = sp.load(own.path, 1)
                } else ctx.assets.openFd("sounds/${cue.file}").use { fd ->
                    ids[cue] = sp.load(fd.fileDescriptor, fd.startOffset, fd.length, 1)
                }
            }
        }
        pool = sp

        // Y uno aparte, de un solo hueco, para el fondo.
        val ap = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
        ap.setOnLoadCompleteListener { _, sample, status ->
            android.util.Log.i("Ludolog", "ambience sample=$sample status=$status")
            if (status != 0) return@setOnLoadCompleteListener
            ready += sample
            // Puede pedirse antes de estar descomprimido; entonces se arranca aqui.
            if (wantAmbience && !suspended) startAmbience()
        }
        runCatching {
            // El del tema, si cabe. El que no cabe no entra aqui: se transmite.
            val own = ThemeFiles.sound(ctx, AMBIENCE_FILE)
            ambienceBig = own?.takeIf { tooBigForPool(it) }
            if (ambienceBig != null) {
                android.util.Log.i("Ludolog", "ambience: ${ambienceBig?.name} se transmite")
            } else if (own != null) {
                ambienceId = ap.load(own.path, 1)
            } else ctx.assets.openFd(AMBIENCE_FILE).use { fd ->
                ambienceId = ap.load(fd.fileDescriptor, fd.startOffset, fd.length, 1)
            }
        }
        ambiencePool = ap

        // Y un tercero, tambien de un hueco, para el sonido del video que se este viendo.
        val cp = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
        cp.setOnLoadCompleteListener { _, sample, status ->
            if (status != 0 || sample != clipId) return@setOnLoadCompleteListener
            // Descomprimido con la aplicacion ya al fondo: a la vuelta. Ver `suspended`.
            if (suspended) {
                clipPending = true
                return@setOnLoadCompleteListener
            }
            startClip()
        }
        clipPool = cp
    }

    fun play(cue: Cue) {
        if (!uiOn) return
        val sp = pool ?: return
        val id = ids[cue]?.takeIf { it in ready } ?: return
        // Sin exclusividad y con prioridad baja: si llegan dos a la vez suenan las dos, y
        // moverse deprisa por una lista no deja la cola atascada.
        // Con el ajuste de ese sonido en el tema puesto: ver SoundGains.
        val v = SoundGains.lin(cue.file.substringBeforeLast('.'))
        runCatching { sp.play(id, v, v, 0, 0, 1f) }
        // Cada sonido, en el registro detallado: un clic que suena dos veces no se ve, y con
        // `logcat -s Ludolog:V` se cuenta.
        android.util.Log.v("Ludolog", "sfx $cue")
    }

    /**
     * El ambiente, en bucle infinito y sin costura.
     *
     * Se para de verdad al salir en vez de bajar el volumen: un bucle sonando con la
     * pantalla apagada es bateria gastada en nada, y encima se oiria sobre el emulador.
     */
    fun ambience(on: Boolean) {
        wantAmbience = on && ambienceOn
        if (!wantAmbience) {
            stopAmbienceStream()
            val ap = ambiencePool
            if (ap != null && ambienceStream != 0) runCatching { ap.stop(ambienceStream) }
            ambienceStream = 0
            return
        }
        // Con la aplicacion al fondo no empieza: queda pedido y lo arranca resumeAll. La primera
        // composicion lo pide por su cuenta, y cuando Android relanza la app de inicio con la
        // consola en reposo —tras una actualizacion, o si el sistema la habia cerrado— eso llega
        // DESPUES de la pausa: el ambiente sonaba con la pantalla apagada y la consola bloqueada.
        if (suspended) return
        startAmbience()
    }

    /** El ambiente largo, transmitido del fichero. Mismo volumen y mismo bucle que el corto. */
    private fun startAmbienceStream(f: java.io.File) {
        if (ambienceMusic != null) return
        // Por LoopTrack y no por MediaPlayer: ver LoopTrack.
        ambienceMusic = LoopTrack(ATTRS, level()) { ex -> ex.setDataSource(f.path); null }.start()
    }

    private fun stopAmbienceStream() {
        val m = ambienceMusic ?: return
        ambienceMusic = null
        m.stop()
    }


    /**
     * Si el fichero no cabe en un SoundPool.
     *
     * El techo es un megabyte de PCM por muestra, y lo que ocupa no lo dice el tamaņo del fichero
     * —eso es lo comprimido— sino la duracion por la frecuencia por los canales. Se lee del
     * propio fichero y no se estima: un mismo minuto son 2,6 MB a 22 kHz en mono y 11,5 a 48 en
     * estereo, y estimando por duracion se colaria justo el caso que se quiere evitar.
     *
     * Con margen: se corta en 900 KB y no en el mega justo, porque el techo real depende de la
     * version y del aparato y quedarse al borde es pedirlo.
     */
    private fun tooBigForPool(f: java.io.File): Boolean = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.path)
            val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@runCatching false
            val us = fmt.getLong(android.media.MediaFormat.KEY_DURATION)
            val rate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val bytes = us / 1_000_000.0 * rate * ch * 2
            android.util.Log.i("Ludolog", "sonido: ${f.name} ${us / 1_000_000}s -> ${bytes.toLong() / 1024} KB de PCM")
            bytes > 900_000
        } finally {
            runCatching { ex.release() }
        }
    }.getOrDefault(false)

    private fun startAmbience() {
        // El que no cabia en el SoundPool va por su cuenta.
        val big = ambienceBig
        if (big != null) { startAmbienceStream(big); return }
        val ap = ambiencePool ?: return
        if (ambienceStream != 0 || ambienceId !in ready) return
        // El -1 es el bucle: repite el PCM ya descodificado, sin volver a abrir nada.
        ambienceStream = runCatching {
            ap.play(ambienceId, level(), level(), 0, -1, 1f)
        }.getOrDefault(0)
    }

    /**
     * Vuelve a cargar los sonidos porque el tema ha cambiado.
     *
     * Hace falta desde que el tema se puede cambiar EN VIVO. `load` se llamaba una sola vez al
     * arrancar y sale sola si ya hay reproductor, asi que al elegir otro tema cambiaban el
     * color, la letra, los medios y la sala — y seguian sonando los sonidos del tema con el que
     * se habia abierto la aplicacion, hasta la siguiente vez que se abriera. Un tema es una
     * carpeta entera: si cambia, cambia toda.
     *
     * Se suelta y se vuelve a montar, que es lo unico que vale: un SoundPool guarda el PCM ya
     * descodificado y no hay forma de cambiarle un sample de debajo.
     *
     * Lo que estuviera sonando se recuerda y se vuelve a pedir. El ambiente no arranca aqui
     * —el fichero todavia se esta descomprimiendo— sino en el aviso de carga, que ya mira si
     * alguien lo queria.
     */
    fun retheme(ctx: Context) {
        val wasBook = inBook
        val wasAmbience = wantAmbience
        // Y el sonido del video que se estuviera viendo. Nadie lo vuelve a pedir: quien lo pide
        // solo lo hace al cambiar el video o su volumen, y cambiar de tema no toca ninguno de
        // los dos, asi que el video se quedaba mudo hasta pasar a otro juego.
        val wasClip = clipPath
        val wasLevel = clipLevel
        release()
        load(ctx)
        book(wasBook)
        ambience(wasAmbience)
        wasClip?.let { clip(java.io.File(it), wasLevel) }
    }

    fun release() {
        stopAmbienceStream()
        ambienceHeld?.stop()
        ambienceHeld = null
        clipMusic?.stop()
        clipMusic = null
        clipBig = false
        clipPending = false
        inBook = false
        runCatching { pool?.release() }
        runCatching { ambiencePool?.release() }
        runCatching { clipPool?.release() }
        pool = null
        ambiencePool = null
        clipPool = null
        clipStream = 0
        clipId = 0
        clipPath = null
        ids.clear()
        ready.clear()
        ambienceStream = 0
        ambienceId = 0
        ambienceBig = null
    }
}
