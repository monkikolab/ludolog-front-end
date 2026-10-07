package com.felp.frontcomp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.exp

/**
 * Los jingles del personaje: subir de nivel, un logro, una mision cumplida.
 *
 * Sintetizados aqui, nota a nota, y no sacados de un fichero: son propios, no se parecen a los de
 * ningun juego, y no pesan nada. Onda de pulso suavizada con un poco de triangulo, que suena a
 * consola de 8 bits sin el chirrido de la cuadrada pura.
 *
 * Suenan si suenan los efectos de la interfaz (Sfx.uiOn).
 */
internal object Jingle {

    /** Cada uno, con el fichero con que un tema puede traer el suyo en `sounds/`. */
    enum class Kind(val file: String) { LEVEL_UP("levelup.ogg"), ACHIEVEMENT("achievement.ogg"), MISSION("mission.ogg") }

    private const val RATE = 22_050

    /** Notas en semitonos sobre La 440, y lo que dura cada una en milisegundos. */
    private val TUNES = mapOf(
        // Arpegio de Do mayor que sube y se queda arriba.
        Kind.LEVEL_UP to listOf(3 to 90, 7 to 90, 10 to 90, 15 to 110, 10 to 70, 15 to 420),
        // Dos notas, una cuarta: una campanilla.
        Kind.ACHIEVEMENT to listOf(10 to 110, 15 to 380),
        // Cuatro que suben y la ultima larga.
        Kind.MISSION to listOf(7 to 90, 10 to 90, 15 to 90, 19 to 380),
    )

    /**
     * Los del Parlour: en La menor, mas graves y lentos, con timbre de organo de iglesia. El arpegio
     * alegre de 8 bits no pegaba con una sala de terror; aqui sube por la menor y se resuelve
     * desde la sensible, que es lo que suena a castillo.
     */
    private val PARLOUR = mapOf(
        Kind.LEVEL_UP to listOf(-12 to 170, -9 to 170, -5 to 170, 0 to 240, -1 to 200, 0 to 950),
        Kind.ACHIEVEMENT to listOf(-5 to 220, 0 to 800),
        Kind.MISSION to listOf(-9 to 160, -5 to 160, 0 to 160, 3 to 800),
    )

    /**
     * El del tema, si trae el suyo en `sounds/` (el Parlour trae los suyos, industriales, al
     * estilo de la sala); si no, el sintetizado. Como los demas sonidos de la interfaz: el
     * fichero del tema manda.
     */
    private val playing = java.util.Collections.synchronizedSet(HashSet<android.media.MediaPlayer>())

    fun play(kind: Kind, themeId: String = "", ctx: android.content.Context? = null) {
        if (!Sfx.uiOn) return
        val own = ctx?.let { ThemeFiles.sound(it, kind.file) }
        if (own != null) {
            runCatching {
                android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(own.path)
                    // Hay que retenerlo hasta que acabe: suelto, el recolector se lo lleva a
                    // medio sonar y el jingle se corta al segundo.
                    setOnCompletionListener { playing -= it; it.release() }
                    // Con el ajuste de ese jingle en el tema puesto: ver SoundGains.
                    SoundGains.lin(kind.file.substringBeforeLast('.')).let { v -> setVolume(v, v) }
                    prepare()
                    start()
                    playing += this
                }
            }.onSuccess { return }
        }
        val organ = themeId == "parlour"
        val tune = (if (organ) PARLOUR else TUNES).getValue(kind)
        kotlin.concurrent.thread(name = "jingle") {
            runCatching {
                val pcm = if (organ) organ(tune) else synth(tune)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.setVolume(SoundGains.lin(kind.file.substringBeforeLast('.')))
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 100)
                track.release()
            }
        }
    }

    /**
     * Timbre de organo: la fundamental con tres armonicos, un vibrato lento y un ataque blando. Cada
     * nota suena un poco sobre la siguiente, como en una nave con eco.
     */
    private fun organ(tune: List<Pair<Int, Int>>): ShortArray {
        val total = tune.sumOf { it.second } + 700
        val out = DoubleArray(RATE * total / 1000)
        var at = 0
        for ((semi, ms) in tune) {
            val f = 440.0 * Math.pow(2.0, semi / 12.0)
            val start = RATE * at / 1000
            val ring = RATE * (ms + 600) / 1000
            for (i in 0 until ring) {
                val k = start + i
                if (k >= out.size) break
                val t = i.toDouble() / RATE
                val vib = 1.0 + 0.004 * kotlin.math.sin(2 * Math.PI * 5.0 * t)
                val w = 2 * Math.PI * f * vib * t
                val wave = kotlin.math.sin(w) * 0.55 + kotlin.math.sin(2 * w) * 0.25 +
                    kotlin.math.sin(3 * w) * 0.12 + kotlin.math.sin(4 * w) * 0.08
                val attack = (t / 0.03).coerceAtMost(1.0)
                val held = i < RATE * ms / 1000
                val tail = if (held) 1.0 else exp(-(t - ms / 1000.0) * 7.0)
                out[k] += wave * attack * tail * exp(-t * 0.8) * 0.22
            }
            at += ms
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Las notas, una tras otra, cada una con su ataque corto y su caida. */
    private fun synth(tune: List<Pair<Int, Int>>): ShortArray {
        val out = ArrayList<Short>()
        for ((semi, ms) in tune) {
            val f = 440.0 * Math.pow(2.0, semi / 12.0)
            val n = RATE * ms / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val phase = (f * t) % 1.0
                // Pulso al 25 % mezclado con triangulo.
                val pulse = if (phase < 0.25) 1.0 else -1.0
                val tri = 1.0 - 4.0 * kotlin.math.abs(phase - 0.5)
                val wave = pulse * 0.45 + tri * 0.55
                val attack = (i / (RATE * 0.004)).coerceAtMost(1.0)
                val decay = exp(-t * (if (ms > 200) 3.5 else 9.0))
                out += (wave * attack * decay * 0.30 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        // Un respiro de silencio al final: sin el, algunos altavoces cortan en seco.
        repeat(RATE / 20) { out += 0 }
        return out.toShortArray()
    }
}
