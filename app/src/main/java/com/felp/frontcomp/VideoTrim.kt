package com.felp.frontcomp

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Deja de un video de partida solo los segundos que se llegan a ver.
 *
 * No vuelve a codificar nada: copia las muestras tal como vienen de un contenedor al otro,
 * asi que no hay perdida de calidad y el trabajo dura lo que dura escribir unos megabytes.
 *
 * Estos recortes duran unos treinta y seis segundos y el panel solo los ensena mientras
 * alguien esta parado encima del juego. Lo que pasa de ahi es tarjeta ocupada en algo que
 * nadie llega a ver.
 */
internal object VideoTrim {

    /**
     * Escribe en destino la parte util del origen. Falso si no pudo, y entonces quien llama
     * se queda con el original entero, que ocupa mas pero se ve igual.
     */
    fun shrink(
        src: File,
        dest: File,
        maxSeconds: Int,
        keepAudio: Boolean = true,
        /** De donde sale el audio. Null es el propio origen; si no, el ya tratado. */
        audioFrom: File? = null,
    ): Boolean {
        val audioSrc = audioFrom ?: src
        var extractor: MediaExtractor? = null
        var audio: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return runCatching {
            val ex = MediaExtractor().also { extractor = it }
            ex.setDataSource(src.path)
            // Si el audio viene ya tratado de otro fichero, este lector solo trae imagen.
            val separate = audioFrom != null

            val mx = MediaMuxer(dest.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                .also { muxer = it }

            // De cada pista de origen, a que pista del destino va. Lo que no este aqui
            // —subtitulos, datos, y el audio cuando no se quiere— ni se lee ni se escribe.
            val tracks = HashMap<Int, Int>()
            var room = 64 * 1024
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val video = mime.startsWith("video/")
                val wantAudio = keepAudio && !separate && mime.startsWith("audio/")
                if (!video && !wantAudio) continue
                // Si el video venia girado hay que decirlo, o se guarda tumbado.
                if (video && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    mx.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    room = maxOf(room, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                tracks[i] = mx.addTrack(format)
                ex.selectTrack(i)
            }
            // El audio ya tratado, si lo hay, entra como una pista mas. Su lector es otro
            // porque sale de otro fichero, y la pista se anade ANTES de arrancar, que es
            // lo unico que el contenedor no deja hacer despues.
            var audioOut = -1
            if (keepAudio && separate) {
                val ax = MediaExtractor().also { audio = it }
                ax.setDataSource(audioSrc.path)
                val at = (0 until ax.trackCount).firstOrNull {
                    ax.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
                if (at != null) {
                    val af = ax.getTrackFormat(at)
                    if (af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        room = maxOf(room, af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    audioOut = mx.addTrack(af)
                    ax.selectTrack(at)
                }
            }
            if (tracks.isEmpty()) return@runCatching false
            mx.start()

            val limitUs = if (maxSeconds <= 0) Long.MAX_VALUE else maxSeconds * 1_000_000L
            val buffer = ByteBuffer.allocate(room)
            val info = MediaCodec.BufferInfo()
            // Las pistas no acaban a la vez. Se para cuando TODAS han pasado del limite, no
            // cuando lo pasa la primera, o el sonido se cortaria antes que la imagen.
            val past = HashSet<Int>()
            var written = 0

            while (true) {
                val from = ex.sampleTrackIndex
                if (from < 0) break
                val to = tracks[from]
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                val at = ex.sampleTime
                if (at > limitUs) {
                    past += from
                    if (past.size == tracks.size) break
                // Y nada mas de una pista que ya paso: con fotogramas B, detras del que se quedo
                // fuera llegan otros de antes del limite que se apoyan en el, y escritos sin el
                // salian rotos al final de cada vuelta del recorte.
                } else if (to != null && from !in past) {
                    info.set(0, size, at, ex.sampleFlags)
                    mx.writeSampleData(to, buffer, info)
                    written++
                }
                if (!ex.advance()) break
            }

            // Y el audio tratado al final, que el contenedor lo admite sin rechistar.
            val ax = audio
            if (audioOut >= 0 && ax != null) {
                while (true) {
                    val size = ax.readSampleData(buffer, 0)
                    if (size < 0) break
                    val at = ax.sampleTime
                    if (at > limitUs) break
                    info.set(0, size, at, ax.sampleFlags)
                    mx.writeSampleData(audioOut, buffer, info)
                    written++
                    if (!ax.advance()) break
                }
            }
            mx.stop()
            written > 0
        }.getOrDefault(false).also {
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { audio?.release() }
            // Un destino a medias es peor que ninguno: el indice lo daria por bueno.
            if (!it) runCatching { dest.delete() }
        }
    }
}
