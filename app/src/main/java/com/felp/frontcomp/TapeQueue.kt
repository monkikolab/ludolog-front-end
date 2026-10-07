package com.felp.frontcomp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/**
 * Separa el sonido de un video la primera vez que se sintoniza, y ya queda separado.
 *
 * El mp4 se queda sin pista de audio y el sonido vive al lado, en su propio fichero. Suena
 * raro hasta que se sabe por que: una pista de audio dentro de un mp4 la reproduce el
 * reproductor de video, y ese reproductor abre una corriente de audio que en este aparato
 * hace que el sistema baje la salida ENTERA mientras dura. Medido: con el mismo video sin
 * pista de audio, el ambiente y los clics no se mueven; con ella, bajan aunque el video
 * este en silencio.
 *
 * Separado, el sonido entra por el mismo sitio que los clics, que es el camino que no
 * molesta. El precio es que los dos van por su cuenta y con el tiempo se separan un poco.
 *
 * Se hace al sintonizar y no al bajar porque los videos que ya estan en la tarjeta nunca
 * pasaron por aqui, y volver a bajarlos solo por esto seria absurdo.
 *
 * De uno en uno y en un hilo aparte: son unos segundos de procesador y no deben robarle un
 * fotograma a quien esta navegando.
 */
internal object TapeQueue {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tape").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val queued = HashSet<String>()

    /** Los que ya fallaron una vez en esta sesion con sonido dentro: ver [split]. */
    private val failedOnce = HashSet<String>()

    /**
     * Sube cada vez que un video termina de separarse.
     *
     * Es estado de Compose a proposito: quien dibuja el panel lo lee, y asi la television
     * se enciende sola en cuanto el trabajo acaba, sin que nadie tenga que preguntar.
     */
    /**
     * Cuantos caben en la cola a la vez.
     *
     * Bajando deprisa por una lista se pasa por veinte juegos en cinco segundos, y sin tope
     * se encolaban los veinte: el hilo se pasaba un minuto trabajando para ensenar videos
     * que ya nadie estaba mirando. Con tres, lo que se queda fuera se pide otra vez cuando
     * alguien se pare de verdad sobre ese juego.
     */
    private const val MAX_QUEUE = 3

    /** Lo que se esta separando ahora mismo, para poder decirlo en pantalla. */
    var working by mutableStateOf<String?>(null)
        private set

    /**
     * Por donde va el que se esta separando, de cero a uno.
     *
     * El grueso del trabajo es descodificar y volver a codificar el sonido, asi que eso
     * ocupa casi toda la barra. El resto, quitar la pista del mp4, es copiar datos y se
     * lleva el ultimo tramo de golpe.
     */
    var progress by mutableFloatStateOf(0f)
        private set
    var revision by mutableIntStateOf(0)
        private set

    /*
     * Dos versiones del sonido, cada una con su marca: la de cinta —filtrada y con la sala— y
     * la limpia, solo sacada del mp4. La de cinta es la de siempre; la limpia es para los temas
     * con el filtro apagado. Solo existia la primera, y apagar el filtro no cambiaba nada: se
     * oia la misma pista ya filtrada, con su eco, porque era la unica que habia.
     */

    /*
     * Y una tercera, la digital, para el tema que suena a maquina y no a cinta. Cada una con su
     * marca y su fichero: cambiar de tema no borra la del otro, que se usa al volver.
     */

    /** La marca de que este video ya esta separado, en la version que se pida. Nulo es la limpia. */
    private fun markOf(video: File, fx: SoundFx?) = File(
        video.parentFile,
        video.name + when (fx) {
            null -> ".clean"
            SoundFx.TAPE -> ".split"
            SoundFx.DIGITAL -> ".digital"
        },
    )

    /** La marca vieja, de cuando el sonido se filtraba y se volvia a meter dentro. */
    private fun oldMarkOf(video: File) = File(video.parentFile, video.name + ".tape")

    private fun audioFile(video: File, fx: SoundFx?) = File(
        video.parentFile,
        video.name + when (fx) {
            null -> ".clean.m4a"
            SoundFx.TAPE -> ".m4a"
            SoundFx.DIGITAL -> ".digital.m4a"
        },
    )

    /** Donde vive el sonido de un video, si ya se separo, en la version que se pida. */
    fun audioFor(video: File, fx: SoundFx?): File? = audioFile(video, fx).takeIf { it.isFile }

    fun done(video: File, fx: SoundFx?): Boolean = markOf(video, fx).isFile

    /**
     * Olvida lo que se le hizo a un video: las marcas y los sonidos separados, de todas las
     * versiones. Para cuando en ese nombre hay un video nuevo, o el que habia era un resto roto.
     */
    fun forget(video: File) {
        for (fx in listOf(null) + SoundFx.entries) {
            markOf(video, fx).delete()
            audioFile(video, fx).delete()
        }
        oldMarkOf(video).delete()
    }

    /**
     * Pide la separacion si hace falta. Vuelve al momento: no bloquea a quien mira.
     *
     * Mientras tanto suena como venia. Es un fotograma de honestidad: la primera vez que se
     * abre un canal todavia no esta separado, y de la segunda en adelante si.
     */
    fun ensure(video: File, fx: SoundFx?) {
        if (!video.isFile || done(video, fx)) return
        val job = video.path + "#" + (fx?.name ?: "clean")
        synchronized(queued) {
            if (queued.size >= MAX_QUEUE) return
            if (!queued.add(job)) return
        }
        worker.execute {
            working = video.name
            progress = 0f
            runCatching { split(video, fx) }
                .onFailure { android.util.Log.w("Ludolog", "tape", it) }
            synchronized(queued) { queued.remove(job) }
            if (queued.isEmpty()) working = null
        }
    }

    /**
     * Recoge los .tap que dejara la version anterior.
     *
     * Hasta ahora el tratamiento reescribia el mp4 sin su pista de sonido, porque el
     * reproductor abria esa pista aunque estuviera en silencio y eso bastaba para que el
     * aparato bajase la salida de todo lo demas. Ese rodeo ya no hace falta —el reproductor
     * de ahora se construye sin descodificadores de sonido— asi que los ficheros a medias
     * que quedaran por ahi no valen para nada y se tiran.
     */
    fun sweep(roots: List<File>) {
        worker.execute {
            // Y los videos rotos que dejo la version anterior, en este mismo hilo: asi no se
            // cruza con una separacion del mismo video. Ver VideoRemnants.
            runCatching { VideoRemnants.repair(DataHome.file("media")) }
                .onFailure { android.util.Log.w("Ludolog", "videos rotos", it) }
            for (root in roots) {
                val dirs = root.listFiles()?.mapNotNull { File(it, "videos").takeIf(File::isDirectory) }
                for (dir in dirs.orEmpty()) {
                    for (tap in dir.listFiles { f -> f.name.endsWith(".tap") }.orEmpty()) {
                        tap.delete()
                    }
                }
            }
        }
    }

    private fun split(video: File, fx: SoundFx?) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        video.parentFile ?: return
        val audio = audioFile(video, fx)

        // Los que ya pasaron por la version anterior traen la cinta dentro. Ponersela otra vez
        // la estrecharia el doble, asi que a esos solo se les saca; lo digital si se les pone.
        val already = oldMarkOf(video).isFile
        val treat = if (already && fx == SoundFx.TAPE) null else fx
        val ok = TapeAudio.process(video, audio, treat) { p -> progress = p }

        if (!ok) {
            audio.delete()
            // Sin sonido que sacar hay tres casos, y la marca —que es para siempre— solo va en
            // dos. Antes iba en todos, y asi se quedaron mudos los Metal Slug de una consola de pruebas:
            // marcados sobre una cabecera rota, siguieron sin sonido cuando el fichero se arreglo.
            val tracks = VideoRemnants.tracks(video)
            val retry = when {
                // Roto: no se abre, o no trae imagen. Sin marca; ver VideoRemnants.
                tracks == null || tracks.none { it.startsWith("video/") } -> true
                // Sin pista de sonido: se marca, o se reintentaria cada vez que alguien se para
                // sobre ese juego.
                tracks.none { it.startsWith("audio/") } -> false
                // Con sonido y fallo al sacarlo: puede haber sido de paso —el codificador ocupado,
                // un atasco— y se reintenta una vez. A la segunda se marca, que un sonido que este
                // aparato no sabe leer no va a aprender a leerlo, y sin marca el video no se
                // encenderia nunca.
                else -> synchronized(failedOnce) { failedOnce.add(video.path + "#" + fx) }
            }
            if (retry) {
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                android.util.Log.w("Ludolog", "tape: ${video.name} fallo en ${ms}ms, sin marca")
                return
            }
        }

        // Y el mp4 NO se toca. Se queda con su sonido dentro, que ya no molesta a nadie
        // porque el tubo se reproduce sin abrir esa pista. Reescribirlo para quitarsela era
        // lo que costaba entre medio minuto y minuto y medio por fichero, medido en la
        // consola: el mismo video tardaba 57 ms unas veces y 30 755 ms otras, y una llego a
        // 91 526, todo dentro de MediaMuxer.writeSampleData.
        markOf(video, fx).writeText("")
        progress = 1f
        revision++
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        android.util.Log.i("Ludolog", "tape: ${video.name} en ${ms}ms (sonido=$ok, tratamiento=${fx ?: "limpio"})")
    }
}
