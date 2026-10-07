package com.felp.frontcomp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable

/**
 * Un sonido largo en bucle, por la MISMA salida que los clics.
 *
 * Existe porque MediaPlayer no deja elegir salida, y en este aparato la salida importa. Medido
 * en `dumpsys media.audio_flinger`: un MediaPlayer abre su pista en la salida de bufer profundo
 * (AudioOut_15, DEEP_BUFFER) aunque se le declare como sonido de interfaz y aunque se le pida
 * baja latencia —probadas las dos cosas, las dos ignoradas—, mientras los clics suenan en la
 * principal (AudioOut_D). Con las dos salidas activas a la vez sobre el mismo altavoz, el
 * aparato baja todo lo demas: al entrar al cuaderno, en cuanto empezaba su musica. Es el mismo
 * bajon que tuvo el video de la sala, y se curo igual: quitando la segunda salida.
 *
 * No es el efecto que Qualcomm engancha a la musica («Volume listener for Music»), aunque fue lo
 * primero que parecio: esta tambien fuera del cuaderno, enganchado al ambiente de la sala, que
 * es justo cuando nada baja. Lo que cambia entre el caso bueno y el malo es cuantas salidas hay.
 *
 * Aqui la pista la pide el programa: un AudioTrack en baja latencia, que el sistema coloca en
 * la salida principal (PRIMARY|FAST), donde ya suenan los clics: una salida y no dos. El
 * fichero se descodifica en su propio hilo y se escribe a medida que suena.
 *
 * Al llegar al final vuelve al principio SIN soltar la pista: la pista lleva un cuarto de
 * segundo en cola y la vuelta del descodificador cabe dentro, asi que el bucle no tiene la
 * costura que tenia el de MediaPlayer, que cerraba y volvia a abrir.
 */
internal class LoopTrack(
    private val attrs: AudioAttributes,
    volume: Float,
    /** Pone la fuente en el extractor. Lo que devuelva se cierra al terminar. */
    private val open: (MediaExtractor) -> Closeable?,
) {
    @Volatile private var running = false
    /**
     * Que hilo es el bueno. Cambia con cada `start` y cada `stop`: un hilo que ve otro numero
     * sale, aunque `running` vuelva a estar a verdadero porque se reanudo antes de que acabase.
     * Sin esto, pausar y reanudar en un cuarto de segundo dejaba dos hilos sonando a la vez.
     */
    @Volatile private var generation = 0
    @Volatile private var live: AudioTrack? = null
    private var thread: Thread? = null
    /** Por donde iba al pararse, en microsegundos del fichero, para seguir desde ahi. */
    @Volatile private var resumeAtUs = 0L
    /** El volumen de ahora. Cambia sonando: el cuaderno baja el ambiente a la mitad. */
    @Volatile private var level = volume

    fun start(): LoopTrack {
        if (running) return this
        running = true
        val gen = ++generation
        thread = Thread({ run(gen) }, "Ludolog-loop").apply { isDaemon = true; start() }
        return this
    }

    /**
     * Calla al instante y deja que el hilo recoja.
     *
     * Silencio y no pausa, a proposito: con la pista en pausa, una escritura bloqueante que
     * encontrase la cola llena se quedaria esperando para siempre. Sonando a volumen cero, la
     * cola se vacia sola y el hilo sale en la siguiente vuelta, un cuarto de segundo como mucho.
     */
    fun stop() {
        running = false
        generation++
        runCatching { live?.setVolume(0f) }
        thread = null
    }

    /**
     * Parar para seguir luego desde el mismo sitio, como hacia MediaPlayer al irse la aplicacion
     * al fondo. Es `stop` con memoria: el hilo guarda por donde iba al salir, y el siguiente
     * `start` empieza ahi.
     */
    fun pause() = stop()

    /** Cambia el volumen sonando, sin cortar. Si la pista aun no existe, empieza con este. */
    fun setVolume(v: Float) {
        level = v
        runCatching { live?.setVolume(v) }
    }

    private fun run(gen: Int) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var source: Closeable? = null
        var lastUs = resumeAtUs
        try {
            val ex = MediaExtractor().also { extractor = it }
            source = open(ex)
            val index = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
            ex.selectTrack(index)
            if (resumeAtUs > 0L) ex.seekTo(resumeAtUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = ex.getTrackFormat(index)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // Enteros de dieciseis bits, que es lo que la pista espera. Sin pedirlo, un
            // descodificador puede entregar coma flotante y la pista sonaria a ruido.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                .also { codec = it }
            dec.configure(format, null, null, 0)
            dec.start()

            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val least = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val out = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Esto es lo que la lleva a la salida principal. Ver el comentario de la clase.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                // Un cuarto de segundo, en muestras ENTERAS: en bytes a pelo salian 11 025 a 22 kHz
                // en mono, un numero impar con muestras de dos bytes, y la pista no se creaba.
                .setBufferSizeInBytes(maxOf(least, (rate / 4) * channels * 2))
                .build()
                .also { track = it }
            out.setVolume(level)
            live = out
            out.play()

            // Las marcas de tiempo siguen subiendo de vuelta en vuelta: un descodificador que
            // las ve ir hacia atras puede tirar lo que produce.
            var base = 0L
            var last = 0L
            val info = MediaCodec.BufferInfo()
            while (running && gen == generation) {
                val inIx = dec.dequeueInputBuffer(10_000)
                if (inIx >= 0) {
                    val buf = dec.getInputBuffer(inIx)!!
                    var size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        base += last + 1
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        size = ex.readSampleData(buf, 0)
                    }
                    if (size >= 0) {
                        last = ex.sampleTime.coerceAtLeast(0L)
                        lastUs = last
                        dec.queueInputBuffer(inIx, 0, size, base + last, 0)
                        ex.advance()
                    } else {
                        dec.queueInputBuffer(inIx, 0, 0, base + last, 0)
                    }
                }
                val outIx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIx >= 0) {
                    val pcm = dec.getOutputBuffer(outIx)!!
                    if (info.size > 0) {
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        out.write(pcm, info.size, AudioTrack.WRITE_BLOCKING)
                    }
                    dec.releaseOutputBuffer(outIx, false)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Ludolog", "loop: ${t.javaClass.simpleName} ${t.message}")
        } finally {
            if (live === track) live = null
            resumeAtUs = lastUs
            runCatching { track?.pause(); track?.flush(); track?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { source?.close() }
        }
    }
}
