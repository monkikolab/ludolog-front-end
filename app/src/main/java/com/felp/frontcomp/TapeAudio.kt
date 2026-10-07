package com.felp.frontcomp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * El sonido de cinta, grabado en el fichero al bajarlo.
 *
 * Antes esto era un ecualizador colgado del reproductor, en vivo. Sonaba igual y estaba
 * mal: un efecto puesto sobre una sesion de audio no se queda en su carril, porque el
 * aparato vigila el nivel de la salida ENTERA para proteger el altavoz. El video empujaba,
 * la proteccion bajaba la ganancia de todo, y los clics y el ambiente se apagaban con el.
 *
 * Tratado aqui no puede pasar: lo que sale del altavoz es un fichero ya filtrado, como
 * cualquier otro. Los demas sonidos son constantes por construccion y no por suerte.
 *
 * Son dos filtros de segundo orden, de los de manual. Uno quita por debajo de ciento veinte
 * hercios y otro por encima de seis mil, que es mas o menos lo que daba la pista lineal de
 * una VHS, y esa estrechez es lo que hace que algo suene a cinta en cuanto lo oyes.
 */
internal object TapeAudio {

    // Mas estrecho y mas empinado que antes. Dos filtros en cascada por lado hacen una
    // caida del doble de pendiente, y la banda que queda es casi solo voz y medios: un
    // altavoz pequeno de television con una cinta ya gastada.
    private const val HIGH_PASS_HZ = 180.0
    private const val LOW_PASS_HZ = 4_500.0
    /** El corte de la version limpia: por debajo de los 11 025 que admite el destino. */
    private const val ANTI_ALIAS_HZ = 9_500.0
    /** Lo justo para una banda de seis kilohercios; mas seria guardar silencio caro. */
    private const val BITRATE = 48_000

    /**
     * A donde se lleva el sonido: mono y a veintidos mil, igual que los clics y el
     * ambiente.
     *
     * No es solo por tamano. Era la unica pista de toda la aplicacion a cuarenta y ocho mil
     * y en estereo, o sea la unica que obligaba al aparato a tratarla aparte del resto.
     * Y con la banda ya cortada en seis mil, cuarenta y ocho mil eran muestras de sobra
     * describiendo silencio.
     */
    private const val OUT_RATE = 22_050

    /** Margen para que la sala, que anade energia, no llegue a recortar. */
    private const val HEADROOM = 0.8
    private const val TIMEOUT_US = 10_000L

    /** Sin nada nuevo de los codificadores durante esto, el tratamiento esta atascado y se deja. */
    private const val STALL_MS = 5_000L

    /**
     * Baja la frecuencia de muestreo, interpolando entre muestras.
     *
     * Interpolar recto es lo mas burdo que hay y aqui esta bien, porque antes ya se han
     * quitado los agudos: lo que queda no pasa de seis kilohercios, muy por debajo de los
     * once que admite el destino, y sin nada ahi arriba no hay nada que se pueda plegar.
     * Un remuestreador de verdad serviria para lo mismo con mas codigo.
     */

    /**
     * Lo digital: menos muestras y menos bits, sin suavizar ninguna de las dos cosas.
     *
     * Es lo contrario de la cinta. La cinta estrecha la banda y la redondea con una sala; esto la
     * deja entera y la rompe en escalones. Cada muestra se repite `HOLD` veces —a veintidos mil,
     * dos es sonar a once mil— y sin filtro que lo impida lo que sobra se pliega hacia abajo y
     * vuelve como ese brillo metalico de los muestreadores baratos. Y cada valor se redondea a
     * `BITS` bits: el redondeo, y no el truncado, deja el cero en su sitio, asi que lo que suena
     * por debajo del primer escalon —el siseo de fondo, las colas de las notas— se queda en
     * silencio exacto, que es como calla una maquina.
     */
    private class Crusher {
        private val step = 65_536.0 / (1 shl BITS)
        private var n = 0
        private var held = 0.0

        fun step(x: Double): Double {
            if (n++ % HOLD == 0) held = Math.round(x / step) * step
            return held
        }

        private companion object {
            const val HOLD = 2
            const val BITS = 8
        }
    }

    /**
     * Un peine con realimentacion: el eco que vuelve una y otra vez, apagandose.
     *
     * El amortiguado es lo que hace que cada vuelta pierda agudos antes que graves, que es
     * lo que pasa en un cuarto de verdad: las paredes se comen los brillos primero.
     */
    private class Comb(size: Int, private val feedback: Double, private val damp: Double) {
        private val buf = DoubleArray(size.coerceAtLeast(1))
        private var at = 0
        private var store = 0.0

        fun step(x: Double): Double {
            val out = buf[at]
            store = out * (1 - damp) + store * damp
            buf[at] = x + store * feedback
            if (++at >= buf.size) at = 0
            return out
        }
    }

    /**
     * Un paso-todo: no cambia que frecuencias hay, cambia cuando llega cada una.
     *
     * Sin esto los ecos del peine se oyen como ecos, uno detras de otro. Con esto se
     * desordenan y dejan de contarse, que es la diferencia entre un eco y una sala.
     */
    private class Allpass(size: Int) {
        private val buf = DoubleArray(size.coerceAtLeast(1))
        private var at = 0

        fun step(x: Double): Double {
            val stored = buf[at]
            buf[at] = x + stored * 0.5
            if (++at >= buf.size) at = 0
            return stored - x
        }
    }

    /**
     * La habitacion: cuatro peines en paralelo y dos paso-todo detras.
     *
     * Es el reverberador de Schroeder, de 1962, y sigue siendo lo que se usa cuando hace
     * falta una sala barata. Los tamanos son primos entre si a proposito: si midieran lo
     * mismo sus ecos coincidirian y se oiria un tubo en vez de un cuarto.
     *
     * Va grabado en el fichero y no en vivo, que es de lo que iba todo esto. Aqui cuesta
     * unos segundos una vez; en vivo costaria un efecto colgado de la sesion de audio, y
     * eso es justo lo que hacia que el aparato bajase la salida entera.
     */
    private class Room(rate: Int) {
        private val scale = rate.toDouble() / 44_100
        // Arrays, no listas: un for sobre List crea un iterador por vuelta, y aqui hay una
        // vuelta por cada muestra de salida. Sobre array el compilador recorre por indice.
        private val combs = intArrayOf(1116, 1188, 1277, 1356).map {
            Comb((it * scale).toInt(), FEEDBACK, DAMP)
        }.toTypedArray()
        private val allpasses =
            intArrayOf(556, 441).map { Allpass((it * scale).toInt()) }.toTypedArray()

        fun step(dry: Double): Double {
            var wet = 0.0
            for (c in combs) wet += c.step(dry)
            wet /= combs.size
            for (a in allpasses) wet = a.step(wet)
            return dry * (1 - WET) + wet * WET
        }

        private companion object {
            /** Cuanto tarda en apagarse. Alto = sala grande. */
            const val FEEDBACK = 0.84
            /** Cuanto se come las paredes de los agudos en cada vuelta. */
            const val DAMP = 0.25
            /** Cuanta sala se oye frente al sonido directo. La tele esta al fondo. */
            const val WET = 0.34
        }
    }
    private class Resampler(inRate: Int, outRate: Int) {
        private val step = inRate.toDouble() / outRate
        private var pos = 0.0
        private var prev = 0.0
        private var started = false

        // En linea: sin esto, cada muestra emitida mete un Double en una caja y cada
        // muestra de entrada crea una instancia nueva de la funcion, porque captura el
        // destino. Son casi dos millones de objetos efimeros por video, todos en el hilo
        // de la cinta, peleando por el recolector con la interfaz.
        inline fun feed(sample: Double, emit: (Double) -> Unit) {
            if (!started) {
                prev = sample
                started = true
                return
            }
            while (pos < 1.0) {
                emit(prev + (sample - prev) * pos)
                pos += step
            }
            pos -= 1.0
            prev = sample
        }
    }

    /**
     * Un filtro de segundo orden, con su propia memoria.
     *
     * Hace falta uno por canal: comparten los coeficientes pero no las dos muestras
     * anteriores, y mezclarlas es como se ensucia un estereo sin darse cuenta.
     */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        companion object {
            /** Las formulas de siempre, con Q de 0,707: la curva mas plana antes de caer. */
            fun highPass(hz: Double, rate: Int): Biquad = make(hz, rate, high = true)
            fun lowPass(hz: Double, rate: Int): Biquad = make(hz, rate, high = false)

            private fun make(hz: Double, rate: Int, high: Boolean): Biquad {
                val w = 2.0 * Math.PI * hz / rate
                val alpha = sin(w) / (2.0 * 0.70710678)
                val cosW = cos(w)
                val a0: Double
                val b0: Double
                val b1: Double
                val b2: Double
                if (high) {
                    b0 = (1 + cosW) / 2; b1 = -(1 + cosW); b2 = (1 + cosW) / 2
                } else {
                    b0 = (1 - cosW) / 2; b1 = 1 - cosW; b2 = (1 - cosW) / 2
                }
                a0 = 1 + alpha
                return Biquad(b0 / a0, b1 / a0, b2 / a0, (-2 * cosW) / a0, (1 - alpha) / a0)
            }
        }
    }

    /**
     * Escribe en destino la pista de audio de origen, ya filtrada, y nada mas.
     *
     * Un fichero de solo audio, que luego el paso de video mete en el contenedor final en
     * lugar de la pista original. Asi el tratamiento vive en un sitio y los dos caminos
     * —recortar y reescalar— lo usan igual sin repetir una linea.
     */
    fun process(
        src: File,
        dest: File,
        /** El tratamiento, o nulo para sacarlo tal cual. */
        fx: SoundFx? = SoundFx.TAPE,
        /** Por donde va, de cero a uno, para poder ensenarlo en la television. */
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var started = false

        return runCatching {
            val ex = MediaExtractor().also { extractor = it }
            ex.setDataSource(src.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@runCatching false
            val inFormat = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val totalUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Se mezcla a mono ANTES de filtrar, asi que hace falta una sola pareja de
            // filtros y no una por canal. Un recorte de partida no tiene nada que contar en
            // estereo que merezca el doble de cuentas y el doble de bytes.
            val stages = arrayOf(
                Biquad.highPass(HIGH_PASS_HZ, rate),
                Biquad.highPass(HIGH_PASS_HZ, rate),
                Biquad.lowPass(LOW_PASS_HZ, rate),
                Biquad.lowPass(LOW_PASS_HZ, rate),
            )
            // Sin la cinta, lo justo para bajar a veintidos mil sin ensuciarlo: un corte bajo la
            // mitad del destino. Sin el, lo que el origen tiene por encima de once kilohercios se
            // pliega al remuestrear —el interpolado recto no lo filtra— y vuelve como un siseo
            // metalico que el original no tenia. Con la cinta no hace falta: ya corta en 4,5.
            val clean = arrayOf(
                Biquad.lowPass(ANTI_ALIAS_HZ, rate),
                Biquad.lowPass(ANTI_ALIAS_HZ, rate),
            )
            val resampler = Resampler(rate, OUT_RATE)
            // La sala va DESPUES de bajar la frecuencia: sus retardos se miden en muestras,
            // y a la mitad de muestras por segundo cuesta la mitad y suena igual.
            val room = Room(OUT_RATE)
            // Y lo digital tambien despues: sus escalones se cuentan a la frecuencia de salida.
            // Antes lleva el mismo corte que la version limpia, para que lo que se pliegue sea
            // el escalon a proposito y no el siseo de bajar a veintidos mil.
            val crusher = if (fx == SoundFx.DIGITAL) Crusher() else null
            val tape = fx == SoundFx.TAPE

            val dec = MediaCodec.createDecoderByType(
                inFormat.getString(MediaFormat.KEY_MIME).orEmpty(),
            ).also { decoder = it }
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val outFormat = MediaFormat
                .createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, OUT_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                .also { encoder = it }
            enc.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val mx = MediaMuxer(dest.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                .also { muxer = it }

            val info = MediaCodec.BufferInfo()
            // El del codificador, aparte: se vacia tambien desde dentro de feed, con el del
            // descodificador todavia a medio leer.
            val done = MediaCodec.BufferInfo()
            // Sitio propio donde filtrar: el buffer que entrega el descodificador es de
            // solo lectura, asi que no se puede tocar en el sitio.
            var scratch = ByteArray(16 * 1024)
            // El sello de tiempo se lleva a mano: al bajar la frecuencia, el del origen ya
            // no corresponde con cuantas muestras salen de aqui.
            var outTimeUs = 0L
            var outTrack = -1
            var fedEnd = false
            var decodedEnd = false
            var encodedEnd = false
            var wrote = 0
            // Cuando salio algo por ultima vez, del descodificador o del codificador.
            var moved = android.os.SystemClock.elapsedRealtime()

            // Del codificador al fichero: todo lo que tenga hecho, esperando [waitUs] por pieza.
            //
            // Todo y no una pieza por vuelta. Un codificador con la salida llena deja de aceptar
            // entrada, y feed se quedaba esperando un hueco que solo podia abrir este mismo hilo
            // vaciando la salida: con trozos grandes de sonido, una pieza por vuelta no daba
            // abasto. Es el mismo atasco que colgaba las conversiones de VideoScale.
            //
            // Se PREGUNTA si hay salida, no se espera a que la haya. Con espera de diez
            // milisegundos en cada vuelta, y varios cientos de vueltas, se iban cuatro
            // segundos de los cinco y medio que costaba tratar un video: el grueso del
            // tiempo era el hilo parado mirando un codificador que todavia no tenia nada.
            // Solo al final, cuando ya no entra nada nuevo, tiene sentido esperar de verdad.
            fun drain(waitUs: Long) {
                while (!encodedEnd) {
                    val slot = enc.dequeueOutputBuffer(done, waitUs)
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outTrack = mx.addTrack(enc.outputFormat)
                        mx.start()
                        started = true
                    } else if (slot >= 0) {
                        moved = android.os.SystemClock.elapsedRealtime()
                        val buffer = enc.getOutputBuffer(slot)
                        val config = done.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (started && !config && done.size > 0 && buffer != null) {
                            mx.writeSampleData(outTrack, buffer, done)
                            wrote++
                        }
                        encodedEnd = done.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(slot, false)
                    } else {
                        return
                    }
                }
            }

            while (!encodedEnd) {
                if (android.os.SystemClock.elapsedRealtime() - moved > STALL_MS) {
                    error("nothing came out for ${STALL_MS / 1000} s")
                }
                // 1. Del fichero al descodificador.
                if (!fedEnd) {
                    val slot = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (slot >= 0) {
                        val buffer = dec.getInputBuffer(slot)
                        val size = if (buffer == null) -1 else ex.readSampleData(buffer, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fedEnd = true
                        } else {
                            dec.queueInputBuffer(slot, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                // 2. Del descodificador al filtro, y del filtro al codificador.
                if (!decodedEnd) {
                    val slot = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (slot >= 0) {
                        moved = android.os.SystemClock.elapsedRealtime()
                        val pcm = dec.getOutputBuffer(slot)
                        if (pcm != null && info.size > 0) {
                            if (scratch.size < info.size) scratch = ByteArray(info.size)
                            val n = grind(
                                pcm, info.offset, info.size, channels,
                                if (tape) stages else clean,
                                resampler, if (tape) room else null, crusher, scratch,
                            )
                            if (n > 0) feed(enc, scratch, n, outTimeUs) { drain(0L) }
                            outTimeUs += n.toLong() * 1_000_000L / (2 * OUT_RATE)
                            if (totalUs > 0) {
                                onProgress((outTimeUs.toFloat() / totalUs).coerceIn(0f, 1f))
                            }
                        }
                        dec.releaseOutputBuffer(slot, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decodedEnd = true
                            feedEnd(enc) { drain(0L) }
                        }
                    }
                }

                // 3. Del codificador al fichero.
                drain(if (decodedEnd) TIMEOUT_US else 0L)
            }
            if (started) mx.stop()
            wrote > 0
        }.onFailure { android.util.Log.w("Ludolog", "tape audio", it) }
            .getOrDefault(false).also { ok ->
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            if (!ok) runCatching { dest.delete() }
        }
    }
    /**
     * Mezcla a mono, filtra, baja la frecuencia y deja el resultado en `out`.
     *
     * Devuelve cuantos bytes escribio, que ya no son los que entraron: por cada dos
     * muestras de estereo a cuarenta y ocho mil sale menos de media a veintidos mil mono.
     */
    private fun grind(
        pcm: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        stages: Array<Biquad>,
        resampler: Resampler,
        room: Room?,
        crusher: Crusher?,
        out: ByteArray,
    ): Int {
        val src = pcm.duplicate()
        src.position(offset)
        src.limit(offset + size)
        val shorts = src.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val frames = (size / 2) / channels
        for (f in 0 until frames) {
            var mixed = 0.0
            for (c in 0 until channels) mixed += shorts.get(f * channels + c).toDouble()
            var v = mixed / channels
            for (stage in stages) v = stage.step(v)
            resampler.feed(v) { s ->
                if (dst.remaining() >= 2) {
                    val wet = room?.step(s) ?: s
                    val shaped = crusher?.step(wet) ?: wet
                    dst.putShort((shaped * HEADROOM).coerceIn(-32768.0, 32767.0).toInt().toShort())
                }
            }
        }
        return dst.position()
    }
    /**
     * Mete en el codificador lo que haya, esperando sitio si hace falta. Con tope: un codificador
     * que no suelta ningun hueco en [STALL_MS] no lo va a soltar, y sin tope este bucle era la
     * unica forma de dejar el hilo de TapeQueue girando para siempre —y con el, sin sonido
     * todos los videos que vinieran detras—.
     */
    private fun feed(enc: MediaCodec, data: ByteArray, size: Int, timeUs: Long, drain: () -> Unit) {
        var left = size
        var from = 0
        val until = android.os.SystemClock.elapsedRealtime() + STALL_MS
        while (left > 0) {
            val slot = enc.dequeueInputBuffer(TIMEOUT_US)
            if (slot < 0) {
                // Mientras no haya hueco, se vacia la salida: es lo que lo abre.
                drain()
                if (android.os.SystemClock.elapsedRealtime() > until) error("the encoder takes nothing")
                continue
            }
            val dst = enc.getInputBuffer(slot) ?: continue
            dst.clear()
            val n = minOf(left, dst.capacity())
            dst.put(data, from, n)
            enc.queueInputBuffer(slot, 0, n, timeUs, 0)
            from += n
            left -= n
        }
    }

    private fun feedEnd(enc: MediaCodec, drain: () -> Unit) {
        val until = android.os.SystemClock.elapsedRealtime() + STALL_MS
        while (true) {
            val slot = enc.dequeueInputBuffer(TIMEOUT_US)
            if (slot < 0) {
                // Mientras no haya hueco, se vacia la salida: es lo que lo abre.
                drain()
                if (android.os.SystemClock.elapsedRealtime() > until) error("the encoder takes nothing")
                continue
            }
            enc.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return
        }
    }
}
