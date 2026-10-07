package com.felp.frontcomp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Baja un video de partida a la resolucion y al caudal de la casa.
 *
 * Las colecciones de archive.org vienen como las subio cada cual: casi la mitad estan a 640
 * por 480, y alguna a 320 por 240 pero con caudal de sobra. En un panel que ocupa un
 * cuadrado de pantalla en un aparato de mano, eso son bytes que nadie llega a ver.
 *
 * Esto si vuelve a codificar, al contrario que el recorte. Se descodifica a una textura, se
 * pinta en un cuadro mas pequeno y se codifica otra vez, y en ese pintado es donde ocurre
 * el reescalado. El sonido no se toca: se copia tal cual, porque reducirlo tambien costaria
 * mas trabajo del que ahorra.
 *
 * Nada de esto es obligatorio. Si el aparato no puede con alguna parte, quien llama se
 * queda con el recorte sin reescalar, que ocupa mas pero se ve igual de bien.
 */
internal object VideoScale {

    /** Un fotograma clave por segundo basta: el panel no es un monitor. */
    private const val KEYFRAME_SECONDS = 1
    private const val TIMEOUT_US = 10_000L

    /**
     * Sin nada nuevo del descodificador ni del codificador durante esto, la conversion esta
     * atascada y se deja. Una sana saca algo cada pocos milisegundos; la que mas tardo en
     * arrancar, medida en una consola de pruebas, dio su primera salida a los 230 ms.
     */
    private const val STALL_MS = 5_000L

    /** Donde corren las conversiones: un hilo cada una, para poder dejar atras el que no vuelve. */
    private val runner = Executors.newCachedThreadPool { r ->
        Thread(r, "scale").apply { isDaemon = true }
    }

    /**
     * El hilo por donde llegan los avisos de fotograma de todas las conversiones.
     *
     * Sin manejador propio, SurfaceTexture los manda por el hilo principal, que es el de la
     * interfaz. Bastaba con que la interfaz se entretuviera mas de lo que se espera por un
     * fotograma para que uno llegara tarde, y desde ahi la conversion se quedaba parada para
     * siempre: medido en una consola de pruebas, con el hilo principal ocupado tres segundos a proposito,
     * el hilo de la conversion se quedo dentro de glClear y no volvio.
     */
    private val frames: Handler by lazy {
        Handler(HandlerThread("scale-frames").apply { start() }.looper)
    }

    private fun MediaFormat.optInt(key: String, fallback: Int) =
        if (containsKey(key)) getInteger(key) else fallback

    /**
     * Si merece la pena pasar por aqui.
     *
     * Reescalar cuesta segundos de procesador por video, asi que no se hace por gusto: solo
     * cuando la imagen es mas alta que el objetivo o el caudal se pasa de largo.
     *
     * El caudal se mide del fichero y no de lo que diga la pista, porque casi nunca lo dice:
     * MediaExtractor deja fuera KEY_BIT_RATE en la mayoria de los mp4, asi que fiarse de el
     * era dejar pasar sin tocar precisamente los videos mas pesados.
     */
    fun worthIt(format: MediaFormat, maxHeight: Int, maxBitrate: Int, fileBitrate: Int): Boolean {
        val h = format.optInt(MediaFormat.KEY_HEIGHT, 0)
        val declared = format.optInt(MediaFormat.KEY_BIT_RATE, 0)
        val rate = maxOf(declared, fileBitrate)
        return h > maxHeight || rate > maxBitrate * 3 / 2
    }

    /** El caudal real de un fichero, en bits por segundo, o cero si no se puede saber. */
    fun fileBitrate(src: File, format: MediaFormat): Int {
        val us = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        if (us <= 0L) return 0
        return (src.length() * 8.0 * 1_000_000.0 / us).toInt()
    }

    /** El formato de la pista de video de un fichero, para poder decidir sin abrirlo dos veces. */
    fun videoFormat(src: File): MediaFormat? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(src.path)
        val i = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        val f = i?.let { ex.getTrackFormat(it) }
        ex.release()
        f
    }.getOrNull()

    /**
     * Reescala [src] en [dest]. Falso si no pudo, y entonces [dest] no queda.
     *
     * Con un tope de tiempo, y en un hilo aparte justo por eso. Hay llamadas de esta tuberia
     * —glClear, eglSwapBuffers, las del codificador— que se pueden quedar esperando dentro del
     * controlador sin volver nunca, y desde fuera no hay forma de despertarlas; lo que si se
     * puede es dejar de esperarlas. Pasado el tope quien llama sigue con el recorte, y el hilo
     * se queda atras con el aviso de que lo deje todo en cuanto despierte.
     *
     * Asi se colgo una pasada entera en una consola de pruebas: tres conversiones paradas mas de una hora con
     * la cabecera escrita y nada mas, cada una con un permiso del scraper en la mano.
     */
    fun convert(
        src: File,
        dest: File,
        maxSeconds: Int,
        maxHeight: Int,
        bitrate: Int,
        keepAudio: Boolean,
    ): Boolean {
        val quit = AtomicBoolean(false)
        val job = runner.submit<Boolean> {
            transcode(src, dest, maxSeconds, maxHeight, bitrate, keepAudio, quit)
        }
        // Holgado para las lentas: un recorte de quince segundos tarda de 4 a 7 s en una consola de pruebas,
        // con tres a la vez. El tope no es para esas, es para las que no van a acabar.
        val limitMs = if (maxSeconds > 0) 30_000L + maxSeconds * 2_000L else 300_000L
        return try {
            job.get(limitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            quit.set(true)
            android.util.Log.w("Ludolog", "scale ${src.name}: mas de ${limitMs / 1000} s, se deja")
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun transcode(
        src: File,
        dest: File,
        maxSeconds: Int,
        maxHeight: Int,
        bitrate: Int,
        keepAudio: Boolean,
        quit: AtomicBoolean,
    ): Boolean {
        var video: MediaExtractor? = null
        var audio: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var input: InputSurface? = null
        var output: OutputSurface? = null
        var muxer: MediaMuxer? = null
        var started = false

        return runCatching {
            val vx = MediaExtractor().also { video = it }
            vx.setDataSource(src.path)
            val vTrack = (0 until vx.trackCount).firstOrNull {
                vx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@runCatching false
            val vFormat = vx.getTrackFormat(vTrack)
            vx.selectTrack(vTrack)

            val srcW = vFormat.optInt(MediaFormat.KEY_WIDTH, 0)
            val srcH = vFormat.optInt(MediaFormat.KEY_HEIGHT, 0)
            if (srcW <= 0 || srcH <= 0) return@runCatching false
            // Se conserva la proporcion y se redondea a par, que es lo unico que aceptan
            // los codificadores de hardware.
            val outH = minOf(srcH, maxHeight).let { it - it % 2 }
            val outW = (srcW.toLong() * outH / srcH).toInt().let { it - it % 2 }
            if (outW <= 0 || outH <= 0) return@runCatching false

            val fps = vFormat.optInt(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(10, 60)
            val target = MediaFormat
                .createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
                }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .also { encoder = it }
            enc.configure(target, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val ins = InputSurface(enc.createInputSurface()).also { input = it }
            ins.makeCurrent()
            enc.start()

            val outs = OutputSurface().also { output = it }
            outs.setUp(frames)
            val dec = MediaCodec
                .createDecoderByType(vFormat.getString(MediaFormat.KEY_MIME).orEmpty())
                .also { decoder = it }
            dec.configure(vFormat, outs.surface, null, 0)
            dec.start()

            val mx = MediaMuxer(dest.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                .also { muxer = it }

            // El sonido va por su propio lector: el del video se esta vaciando a la vez, y
            // no se le puede pedir a un MediaExtractor que sirva dos pistas descompasadas.
            var aFormat: MediaFormat? = null
            if (keepAudio) {
                val ax = MediaExtractor().also { audio = it }
                ax.setDataSource(src.path)
                val aTrack = (0 until ax.trackCount).firstOrNull {
                    ax.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
                if (aTrack != null) {
                    aFormat = ax.getTrackFormat(aTrack)
                    ax.selectTrack(aTrack)
                }
            }

            val limitUs = if (maxSeconds <= 0) Long.MAX_VALUE else maxSeconds * 1_000_000L
            val info = MediaCodec.BufferInfo()
            var outVideo = -1
            var outAudio = -1
            var fedEnd = false
            var decodedEnd = false
            var encodedEnd = false
            var wrote = 0
            // Cuando salio algo por ultima vez, del descodificador o del codificador.
            var moved = SystemClock.elapsedRealtime()

            while (!encodedEnd) {
                if (quit.get()) error("left behind")
                if (SystemClock.elapsedRealtime() - moved > STALL_MS) {
                    error("nothing came out for ${STALL_MS / 1000} s")
                }

                // 1. Del fichero al descodificador.
                if (!fedEnd) {
                    val slot = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (slot >= 0) {
                        val buffer = dec.getInputBuffer(slot)
                        val size = if (buffer == null) -1 else vx.readSampleData(buffer, 0)
                        if (size < 0 || vx.sampleTime > limitUs) {
                            dec.queueInputBuffer(
                                slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            fedEnd = true
                        } else {
                            dec.queueInputBuffer(slot, 0, size, vx.sampleTime, 0)
                            vx.advance()
                        }
                    }
                }

                // 2. Del descodificador a la textura, y de ahi al codificador.
                if (!decodedEnd) {
                    val slot = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (slot >= 0) {
                        moved = SystemClock.elapsedRealtime()
                        val show = info.size > 0
                        dec.releaseOutputBuffer(slot, show)
                        if (show) {
                            // Un fotograma que no llega se deja entero, no se salta. Saltarlo
                            // dejaba la textura un fotograma por detras para siempre, y con el
                            // retraso el codificador acababa lleno y la tuberia parada.
                            if (!outs.awaitFrame()) error("a frame never reached the texture")
                            outs.draw(outW, outH)
                            ins.setPresentationTime(info.presentationTimeUs * 1000)
                            ins.swapBuffers()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decodedEnd = true
                            enc.signalEndOfInputStream()
                        }
                    }
                }

                // 3. Del codificador al fichero: todo lo que tenga hecho, no una muestra por
                // vuelta. Con una por vuelta la salida se iba quedando atras —medido: once
                // fotogramas dentro del codificador a mitad de un video—, y un codificador con la
                // salida llena deja de aceptar imagen: el pintado siguiente se queda esperando
                // sitio, y como es este mismo hilo el que tendria que vaciarla, no llega nunca.
                //
                // Se PREGUNTA si hay salida, no se espera a que la haya: mientras el
                // descodificador va por delante el codificador aun no tiene nada, y con la
                // espera de diez milisegundos se pagaba ese tiempo en cada vuelta del bucle.
                // Es el mismo fallo que ya costo cuatro segundos por video en TapeAudio. Solo al
                // final, sin nada mas que meter, se espera de verdad.
                while (true) {
                    val slot = enc.dequeueOutputBuffer(info, if (decodedEnd) TIMEOUT_US else 0L)
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Aqui es donde se conoce el formato real de salida, y por eso es aqui
                        // donde se anaden las pistas: una vez arrancado el contenedor ya no se puede.
                        outVideo = mx.addTrack(enc.outputFormat)
                        aFormat?.let { outAudio = mx.addTrack(it) }
                        mx.start()
                        started = true
                    } else if (slot >= 0) {
                        moved = SystemClock.elapsedRealtime()
                        val buffer = enc.getOutputBuffer(slot)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (started && !config && info.size > 0 && buffer != null) {
                            mx.writeSampleData(outVideo, buffer, info)
                            wrote++
                        }
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(slot, false)
                        if (end) {
                            encodedEnd = true
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            // 4. Y el sonido al final, que el contenedor lo admite sin rechistar.
            val ax = audio
            if (started && outAudio >= 0 && ax != null) {
                val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
                while (true) {
                    val size = ax.readSampleData(buffer, 0)
                    if (size < 0) break
                    val at = ax.sampleTime
                    if (at > limitUs) break
                    info.set(0, size, at, ax.sampleFlags)
                    mx.writeSampleData(outAudio, buffer, info)
                    if (!ax.advance()) break
                }
            }

            if (started) mx.stop()
            // Si quien espera ya se fue, lo hecho no lo va a recoger nadie.
            wrote > 0 && !quit.get()
        }.onFailure { android.util.Log.w("Ludolog", "scale ${src.name}: $it") }
            .getOrDefault(false).also { ok ->
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { encoder?.stop() }
                runCatching { encoder?.release() }
                runCatching { output?.release() }
                runCatching { input?.release() }
                runCatching { muxer?.release() }
                runCatching { video?.release() }
                runCatching { audio?.release() }
                // Un destino a medias es peor que ninguno: el indice lo daria por bueno.
                if (!ok) runCatching { dest.delete() }
            }
    }
}
