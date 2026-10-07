package com.felp.frontcomp

import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb

/**
 * El sonido del panel: una television en mal estado, al fondo de una habitacion grande.
 *
 * Dos efectos, y ninguno cuesta nada medible. No se procesa audio en Kotlin ni se copia un
 * solo byte: los dos son del sistema, viven en el servidor de audio junto al resto de la
 * cadena, y suman unos pocos filtros de segundo orden mas una linea de retardo.
 *
 * El ecualizador hace el trabajo principal. Lo que hace que algo suene a cinta no es el
 * ruido ni la distorsion, es la estrechez: aqui se deja pasar poco mas que la banda media,
 * que es justo lo que da un altavoz pequeno con una cinta gastada.
 *
 * La reverberacion pone la habitacion. Sin ella la television suena pegada a la oreja, y en
 * la escena esta al otro lado del cuarto.
 *
 * AQUI HUBO UN TERCERO Y SE FUE. Un compresor que subia el suelo y aplastaba los picos,
 * porque una cinta mala lo tiene todo al mismo volumen. Sonaba bien y traia un efecto
 * secundario feo: al empujar tan fuerte, la proteccion del altavoz del propio aparato bajaba
 * la ganancia de TODA la salida mientras el video sonaba, y la soltaba despacio. Se oia como
 * que los clics y el ambiente se apagaban al entrar en un juego y tardaban unos segundos en
 * volver. Un compresor con recuperacion lenta es exactamente eso, y el unico de la cadena
 * que anadia ganancia era este.
 *
 * Lo que tampoco hace es el vaiven de tono, el wow and flutter, que pediria remuestrear la
 * senal y eso ya es trabajo de procesador de verdad.
 */
internal class TapeChain {

    private var eq: Equalizer? = null
    private var room: PresetReverb? = null

    /**
     * Cuelga la cadena del reproductor que tenga esta sesion de audio.
     *
     * Cada efecto va por su cuenta: que uno no exista en un aparato no debe llevarse por
     * delante al otro.
     */
    fun attach(sessionId: Int) {
        if (sessionId == 0) return   // la sesion 0 es la salida entera, no este panel
        runCatching {
            eq = Equalizer(0, sessionId).apply {
                val floor = bandLevelRange[0]        // lo mas bajo que admite, en milibelios
                for (band in 0 until numberOfBands) {
                    val b = band.toShort()
                    val hz = getCenterFreq(b) / 1000 // viene en milihercios
                    val level = when {
                        hz < 120 -> floor                       // nada de cuerpo
                        hz < 500 -> (floor * 0.6f).toInt()      // hueco y cajonero
                        hz < 2_000 -> 0                         // lo unico que queda entero
                        hz < 8_000 -> (floor * 0.8f).toInt()    // cinta gastada, sin brillo
                        else -> floor                           // ni un agudo
                    }
                    runCatching { setBandLevel(b, level.toShort()) }
                }
                enabled = true
                // Una linea en el registro con lo que quedo puesto. Los ecualizadores del
                // sistema no son todos iguales: cambian el numero de bandas y donde estan
                // centradas, y sin esto no habria forma de saber que corta de verdad.
                android.util.Log.i(
                    "Ludolog",
                    "tape: " + (0 until numberOfBands).joinToString(" ") { i ->
                        val b = i.toShort()
                        "${getCenterFreq(b) / 1000}Hz=${getBandLevel(b)}"
                    },
                )
            }
        }
        runCatching {
            room = PresetReverb(0, sessionId).apply {
                preset = PresetReverb.PRESET_LARGEROOM
                enabled = true
            }
        }
    }

    /** Un efecto que nadie libera se queda ocupando un hueco del servidor, y son pocos. */
    fun release() {
        for (fx in listOf<android.media.audiofx.AudioEffect?>(eq, room)) {
            runCatching { fx?.enabled = false }
            runCatching { fx?.release() }
        }
        eq = null
        room = null
    }
}
