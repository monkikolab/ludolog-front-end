package com.felp.frontcomp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/*
 * El cuaderno de partidas, sin adivinar nada.
 *
 * Esta es la diferencia con RetroCompanion, y es toda la diferencia. Alli habia que deducir
 * que juego habia empezado mirando que fichero se abria, que aplicacion pasaba al frente y
 * en que pantalla estaba, porque los front-ends no cuentan lo que lanzan. Aqui el front-end
 * ES quien lanza: sabe el juego, la consola, el emulador y el fichero, sin margen de error y
 * sin una sola linea de heuristica.
 *
 * Lo que queda por resolver no es QUE se juega sino CUANDO se deja de jugar, y para eso no
 * hace falta permiso de acceso al uso: el front-end es la pantalla a la que se vuelve. La
 * partida termina cuando esta aplicacion vuelve al frente.
 */

/** Lo que el front-end le cuenta al servicio al lanzar. */
internal object SessionTracker {

    /** Cuanto puede estar la pantalla apagada antes de dar la partida por terminada. */
    const val ASLEEP_LIMIT_MS = 10 * 60 * 1000L

    var recording: Boolean = false
        private set

    /**
     * Sube cada vez que una partida queda escrita en el cuaderno.
     *
     * Es estado de Compose para que la tarjeta de la lista se vuelva a leer sola al volver de
     * jugar. Leer al volver no sirve: la fila la cierra el servicio al pararse, un momento
     * DESPUES de que la actividad vuelva al frente, y se leeria el cuaderno sin la partida que
     * acaba de terminar.
     */
    var written by mutableIntStateOf(0)

    /**
     * Cuando volvio el front-end al frente por ultima vez: es donde termina la partida.
     *
     * La vuelta se confirma unos segundos despues (ver MainActivity.onResume), pero la hora que
     * cuenta es esta y no la de la confirmacion.
     */
    @Volatile
    var backAt = 0L

    /** Donde tiene que cerrar el servicio la partida que se para, o cero para «ahora». */
    @Volatile
    var endAt = 0L

    /**
     * Empieza a apuntar. Se llama JUSTO despues de que el intent arranque, no antes: una
     * partida que no llego a abrirse no es una partida.
     */
    fun started(ctx: Context, game: Game, emulator: String?, title: String, mission: String? = null) {
        // Apagado en ajustes: ni se abre el servicio ni se escribe una fila. Pero con Ludolog Link
        // instalado se le sigue diciendo que juego se abre y cuando se cierra: sin eso traeria
        // partidas de otro device con el juego abierto (Saves.inUse en Link) y no sabria donde se
        // jugo por ultima vez. Es solo ese aviso, sin medir nada. Ver docs/ludolog-link.md.
        if (!Prefs(ctx.applicationContext).logbook) {
            if (!LinkSaveCheck.available(ctx)) return
            if (recording) returned(ctx, backAt)
            val now = System.currentTimeMillis()
            linkOnly = LinkOnly(emulator.orEmpty(), game.systemId, game.fileName, now)
            recording = true
            LinkBridge.gameOpened(ctx.applicationContext, emulator, game.systemId, game.fileName, now)
            return
        }
        // Una que seguia abierta: se volvio al front-end y se lanzo otra antes de confirmar la
        // vuelta. Se cierra donde se volvio; si no, el servicio, que ya estaba en marcha, se
        // tragaria la nueva y apuntaria las dos como una sola.
        if (recording) returned(ctx, backAt)
        recording = true
        val i = Intent(ctx, SessionService::class.java).apply {
            putExtra(EXTRA_PACKAGE, emulator.orEmpty())
            putExtra(EXTRA_SYSTEM, game.systemId)
            putExtra(EXTRA_FILE, game.fileName)
            putExtra(EXTRA_TITLE, title)
            mission?.let { putExtra(EXTRA_MISSION, it) }
        }
        runCatching { ctx.startForegroundService(i) }
    }

    /**
     * Se ha vuelto al front-end: la partida termino.
     *
     * Se llama desde onResume de la actividad, confirmado. Volver aqui es exactamente lo que
     * hace alguien al salir de un emulador, asi que no hace falta preguntarle al sistema quien
     * esta delante —lo que exigiria el permiso de acceso al uso y una visita a los ajustes.
     *
     * `at` es cuando se volvio, que es cuando termino la partida.
     */
    fun returned(ctx: Context, at: Long) {
        if (!recording) return
        recording = false
        // Sin Companion: solo el cierre para Link (ver started).
        linkOnly?.let { g ->
            linkOnly = null
            LinkBridge.gameClosed(ctx.applicationContext, g.pkg, g.system, g.file, g.startedAt, at)
            return
        }
        endAt = at
        runCatching { ctx.stopService(Intent(ctx, SessionService::class.java)) }
    }

    /** La partida abierta con el Companion apagado, que solo se le cuenta a Ludolog Link. */
    private class LinkOnly(val pkg: String, val system: String, val file: String, val startedAt: Long)
    @Volatile private var linkOnly: LinkOnly? = null

    const val EXTRA_PACKAGE = "pkg"
    const val EXTRA_SYSTEM = "sys"
    const val EXTRA_FILE = "file"
    const val EXTRA_TITLE = "title"
    const val EXTRA_MISSION = "mission"
}

/**
 * El que mide mientras se juega.
 *
 * Tiene que ser un servicio en primer plano porque el front-end esta detras del emulador
 * todo el rato: en segundo plano Android deja de darnos hilo a los pocos segundos y las
 * medidas saldrian con agujeros. El aviso que obliga a ensenar es el precio, y es honesto:
 * algo esta midiendo.
 */
internal class SessionService : Service() {

    private lateinit var book: Logbook
    private lateinit var tel: Telemetry
    private var worker: HandlerThread? = null
    private var hand: Handler? = null

    private var sessionId = -1L
    private var startedAt = 0L
    private var startCharge: Long? = null
    private var startPct = -1

    /** Lo que se resume en cada fila de medidas. */
    private var bucket = Bucket()
    private var bucketAt = 0L

    /**
     * Cada cuanto se lee y cada cuanto se guarda, leidos UNA vez al abrir la partida.
     *
     * Una vez y no en cada tick: cambiarlos a mitad de una partida dejaria una traza con dos
     * pulsos distintos dentro, y la curva de esa partida ya no se podria comparar consigo
     * misma. Lo que se cambie en ajustes entra en la siguiente.
     */
    private var sampleMs = 1_000L
    private var bucketMs = 10_000L

    /** Y el de toda la partida, que es lo que acaba en la fila de la sesion. */
    private var whole = Bucket()

    private var charged = false
    /** Cuando se apago la pantalla, o cero. Lo escribe el receptor y lo lee el tick. */
    @Volatile private var asleepSince = 0L

    /** El intent de la partida: con el se abre otra fila del mismo juego tras un sueno largo. */
    private var started: Intent? = null
    private var device = "handheld"

    /**
     * La fila se cerro por un sueno largo y no se mide nada hasta que se encienda la pantalla.
     * Solo lo toca el hilo de las medidas.
     */
    private var dormant = false

    /**
     * La pantalla apagada es la unica senal de "ya no esta jugando" que llega sola.
     *
     * Sin esto, dejar la consola encima de la mesa con el emulador abierto apuntaria ocho
     * horas de partida. Con esto, a los diez minutos de pantalla apagada la partida se cierra
     * en el instante en que se apago, que es cuando de verdad se dejo de jugar.
     *
     * Y se decide AL ENCENDERSE, con el reloj de pared. Confiado solo al tick no funcionaba:
     * el tick va con el reloj que se para cuando el aparato se duerme, y sin nada que lo
     * mantuviera despierto no corria en toda la noche. Al encenderse, esto ponia el sueno a
     * cero antes de que el tick lo viera, y la noche entera quedaba como partida. Y cuando el
     * tick si lo veia —en un despertar de fondo— cerraba la fila y paraba el servicio, y lo que
     * se jugaba despues ya no lo apuntaba nadie. Ahora lo de antes del sueno es una partida,
     * cerrada donde se apago la pantalla, y al encenderse empieza otra del mismo juego; si no
     * se vuelve a jugar, esa se tira por corta al volver al front-end.
     */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> asleepSince = System.currentTimeMillis()
                Intent.ACTION_SCREEN_ON -> {
                    val off = asleepSince
                    asleepSince = 0L
                    val long = off > 0L &&
                        System.currentTimeMillis() - off >= SessionTracker.ASLEEP_LIMIT_MS
                    hand?.post { woke(off, long) }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ya en marcha, midiendo o dormida tras un sueno largo: es la misma partida.
        if (worker != null || sessionId >= 0) return START_NOT_STICKY

        startForeground(NOTE_ID, note(intent?.getStringExtra(SessionTracker.EXTRA_TITLE)))

        book = Logbook(applicationContext)
        tel = Telemetry()
        started = intent
        val prefs = Prefs(applicationContext)
        sampleMs = prefs.sampleMs.coerceAtLeast(250L)
        // El resumen nunca por debajo de la medida: si fueran iguales, cada lectura seria su
        // propia fila y el «cada cuanto se guarda» dejaria de querer decir nada.
        bucketMs = prefs.bucketMs.coerceAtLeast(sampleMs)

        device = android.os.Build.MODEL.orEmpty().ifEmpty { "handheld" }

        registerReceiver(
            screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )

        val t = HandlerThread("session").also { it.start() }
        worker = t
        hand = Handler(t.looper).also {
            // Abrir la fila es escribir en el cuaderno: aqui, en el hilo de las medidas, y no en el
            // principal justo cuando arranca el emulador (revision del 09-10-2026).
            it.post {
                if (!begin()) {
                    hand?.removeCallbacksAndMessages(null)
                    stopSelf()
                }
            }
            it.post {
                if (sessionId < 0) return@post
                card(
                    intent?.getStringExtra(SessionTracker.EXTRA_TITLE),
                    intent?.getStringExtra(SessionTracker.EXTRA_SYSTEM),
                    intent?.getStringExtra(SessionTracker.EXTRA_FILE),
                    device,
                    intent?.getStringExtra(SessionTracker.EXTRA_MISSION),
                )
            }
            it.post(tick)
        }
        return START_NOT_STICKY
    }

    /** Abre la fila de la partida desde ahora, con lo que trajo el intent. False si no se pudo. */
    private fun begin(): Boolean {
        val intent = started
        startedAt = System.currentTimeMillis()
        bucketAt = startedAt
        bucket = Bucket()
        whole = Bucket()
        startCharge = tel.chargeMicroAh(this)
        startPct = tel.batteryPercent(this)
        charged = tel.isCharging(this)
        sessionId = runCatching {
            book.open(
                Logbook.Start(
                    device = device,
                    packageName = intent?.getStringExtra(SessionTracker.EXTRA_PACKAGE).orEmpty(),
                    emulator = intent?.getStringExtra(SessionTracker.EXTRA_PACKAGE),
                    system = intent?.getStringExtra(SessionTracker.EXTRA_SYSTEM),
                    title = intent?.getStringExtra(SessionTracker.EXTRA_FILE),
                    displayName = intent?.getStringExtra(SessionTracker.EXTRA_TITLE),
                    startedAt = startedAt,
                    chargeUah = startCharge,
                    batteryPercent = startPct,
                )
            )
        }.getOrDefault(-1L)
        // Para Ludolog Link (partidas guardadas): que emulador tiene abierto un juego. Ver LinkBridge.
        intent?.let { LinkBridge.gameOpened(applicationContext, it.getStringExtra(SessionTracker.EXTRA_PACKAGE),
            it.getStringExtra(SessionTracker.EXTRA_SYSTEM), it.getStringExtra(SessionTracker.EXTRA_FILE), startedAt) }
        return sessionId >= 0
    }

    /**
     * Se encendio la pantalla, en el hilo de las medidas. Tras un sueno largo, la fila de antes
     * se cierra donde se apago —si el tick no lo hizo ya— y se abre otra desde ahora. Ver
     * `screen`.
     */
    private fun woke(off: Long, long: Boolean) {
        if (!long && !dormant) return
        if (!dormant) finish(off)
        dormant = false
        if (!begin()) return
        hand?.removeCallbacks(tick)
        hand?.post(tick)
    }

    /**
     * Prepara la tarjeta que asoma encima del emulador.
     *
     * Aqui y no en onStartCommand porque son dos consultas al cuaderno, y onStartCommand
     * corre en el hilo principal justo cuando el sistema esta levantando un emulador: es el
     * peor instante del dia para leer de un fichero. Se encola antes que el primer tick para
     * que la tarjeta no espere a que empiecen las medidas.
     *
     * Solo cuenta lo TERMINADO, asi que la partida recien abierta no se cuenta a si misma.
     *
     * Con la misma cuenta que la tarjeta de la lista, y no con una propia: el juego se busca
     * por su fichero ademas de por su nombre —las partidas viejas se apuntaron con otro—, y lo
     * que queda sale de lo que gasta por hora y de la carga de AHORA. Buscando solo por el
     * nombre, un juego que la lista daba por jugado dos veces empezaba aqui su «first session».
     */
    private fun card(title: String?, system: String?, file: String?, device: String, mission: String?) {
        if (title.isNullOrBlank()) return
        runCatching {
            // Con las otras consolas, como la tarjeta de la lista: solo con esta, un juego jugado
            // cinco veces en otra salia aqui como «first session» (revision del 09-10-2026).
            val t = Logbook(applicationContext, withOthers = true).use { LogStats(it).played(device).game(system ?: "?", file.orEmpty(), title) }
            RecordingCard.show(
                this,
                RecordingCard.Note(
                    title = title,
                    system = system,
                    hoursLeft = t?.hoursLeft(startCharge),
                    sessions = t?.sessions ?: 0,
                    totalMs = t?.totalMs ?: 0L,
                    mission = mission,
                ),
            )
        }
    }

    /** Una medida por segundo, una fila cada diez. Igual que en RetroCompanion. */
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            // Dormida demasiado rato, visto en un despertar de fondo con la pantalla aun apagada:
            // se cierra en el momento en que se apago, y no se mide nada mas hasta que se encienda
            // (ver `screen`). Sin parar el servicio: se paraba, y lo que se jugaba al encenderla
            // se perdia, porque la aplicacion seguia creyendo que habia una partida en marcha.
            val off = asleepSince
            if (off > 0L && now - off >= SessionTracker.ASLEEP_LIMIT_MS) {
                finish(off)
                dormant = true
                return
            }
            // Y con la pantalla apagada no se mide: no se esta jugando. Las lecturas del aparato
            // en reposo —la CPU parada, la temperatura bajando— se colaban en las medias de la
            // partida, y su traza seguia diez minutos mas alla de donde la partida termina.
            // Visto en una consola de pruebas: 66 medidas en una partida de 70 segundos.
            if (off > 0L) {
                hand?.postDelayed(this, sampleMs)
                return
            }

            runCatching {
                val s = tel.sample(this@SessionService)
                bucket.add(s)
                whole.add(s)
            }
            if (tel.isCharging(this@SessionService)) charged = true

            if (now - bucketAt >= bucketMs) {
                if (!bucket.isEmpty()) runCatching { book.sample(sessionId, bucketAt, bucket.close()) }
                bucket = Bucket()
                bucketAt = now
            }
            hand?.postDelayed(this, sampleMs)
        }
    }

    override fun onDestroy() {
        RecordingCard.hide()
        hand?.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        // El hilo de las medidas, parado y esperado ANTES de cerrar la fila. Se paraba despues,
        // y un tick a medias —el resumen de diez segundos escribiendose— seguia en su hilo
        // mientras aqui se cerraba: medidas apuntadas a la partida -1, o a una recien tirada
        // por corta, y el cuaderno reabierto despues de cerrarlo.
        worker?.quitSafely()
        runCatching { worker?.join(JOIN_MS) }
        // Donde se volvio al front-end, si se sabe; si no, ahora.
        val at = SessionTracker.endAt.takeIf { it > startedAt } ?: System.currentTimeMillis()
        SessionTracker.endAt = 0L
        finish(at)
        // Tambien si la fila no se llego a abrir: la conexion quedaba abierta (revision del 09-10-2026).
        if (::book.isInitialized) runCatching { book.close() }
        super.onDestroy()
    }

    /** Cierra la fila. Idempotente: onDestroy puede llegar despues de un cierre por sueno. */
    private fun finish(endedAt: Long) {
        if (sessionId < 0) return
        val id = sessionId
        sessionId = -1

        // Demasiado corta: se tira en vez de guardarse.
        //
        // Abrir un juego por error, o cerrarlo nada mas cargar, no es jugar, y un punado de
        // esas arrastra todas las medias hacia su propio arranque: cargar es la parte mas
        // caliente y mas cara, y la menos parecida a jugar. El umbral lo elige el usuario en
        // ajustes; en cero se apunta todo.
        // Para Ludolog Link: que emulador se cerro, aunque la partida sea demasiado corta para el
        // Companion (pudo guardar). Ver LinkBridge.gameClosed.
        started?.let { LinkBridge.gameClosed(applicationContext, it.getStringExtra(SessionTracker.EXTRA_PACKAGE),
            it.getStringExtra(SessionTracker.EXTRA_SYSTEM), it.getStringExtra(SessionTracker.EXTRA_FILE), startedAt, endedAt) }

        val least = Prefs(applicationContext).minSessionSeconds * 1000L
        val lasted = (endedAt - startedAt).coerceAtLeast(0L)
        if (least > 0 && lasted < least) {
            runCatching { book.forget(id) }
            runCatching { book.close() }
            return
        }

        // El ultimo tramo, el que no llego a completar su resumen: se perdia, y una partida mas corta
        // que un tramo (con «Stored every» en un minuto o mas) quedaba sin medidas (revision del
        // 09-10-2026).
        if (!bucket.isEmpty()) runCatching { book.sample(id, bucketAt, bucket.close()) }
        bucket = Bucket()

        val r = whole.close()
        val endCharge = tel.chargeMicroAh(this)
        runCatching {
            book.close(
                id,
                Logbook.End(
                    endedAt = endedAt,
                    durationMs = (endedAt - startedAt).coerceAtLeast(0L),
                    chargeUah = endCharge,
                    // Restar y no al reves: el contador BAJA al gastar. Y solo si no estuvo
                    // enchufada, porque entonces la cuenta no dice lo que costo el juego.
                    chargeUsedUah = if (charged) null else {
                        val a = startCharge
                        if (a != null && endCharge != null) (a - endCharge).coerceAtLeast(0L) else null
                    },
                    batteryPercent = tel.batteryPercent(this),
                    charged = charged,
                    tempMeanC = r.tempMeanC,
                    tempMaxC = r.tempMaxC,
                    gpuTempMeanC = r.gpuTempMeanC,
                    gpuTempMaxC = r.gpuTempMaxC,
                    cpuMeanPercent = r.cpuMeanPercent,
                    cpuMaxPercent = r.cpuMaxPercent,
                    gpuMeanPercent = r.gpuMeanPercent,
                    gpuMaxPercent = r.gpuMaxPercent,
                    cpuMeanMhz = r.cpuMeanMhz,
                    cpuMinMhz = r.cpuMinMhz,
                    gpuMeanMhz = r.gpuMeanMhz,
                    powerMeanW = r.powerMeanW,
                    powerMaxW = r.powerMaxW,
                    fpsMean = r.fpsMean,
                    fpsMinimum = r.fpsMinimum,
                    chargeFullUah = tel.fullChargeMicroAh(this),
                    chargeDesignUah = tel.designChargeMicroAh(),
                    batteryCycles = tel.batteryCycles(this),
                    samples = r.samples,
                ),
            )
        }
        runCatching { book.close() }
        SessionTracker.written++
        // Una partida nueva en el cuaderno: Ludolog Link, si escucha, la comparte. Ver LinkBridge.
        LinkBridge.companionChanged(applicationContext)
    }

    private fun note(title: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title ?: "Playing")
            .setContentText("Keeping the logbook")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "session"
        const val NOTE_ID = 42
        /** Lo mas que se espera a que acabe un tick: una lectura de sensores y una escritura. */
        const val JOIN_MS = 2_000L
    }
}
