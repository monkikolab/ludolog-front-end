package com.felp.frontcomp

import kotlin.math.pow

/**
 * El ajuste de cada sonido, en decibelios, por tema: lo que se calibra a oido en Ajustes → Sound →
 * Sound levels.
 *
 * Existe para no rehacer los ficheros cada vez que algo suena alto o bajo: medidos en el PC dos
 * sonidos pueden ir igual y sonar distinto en el altavoz de una consola, y la calibracion de los
 * temas se hizo en una consola, que suena de otra manera que otra. Se ajusta en el aparato y
 * suena al momento.
 *
 * Casi todo solo hacia abajo, de 0 a [MIN_DB]: los reproductores de Android pueden bajar un
 * fichero, no subirlo por encima de como esta grabado. Si uno queda flojo al lado de los demas,
 * se bajan los otros; y lo que se decida se puede pasar despues a los propios ficheros, con las
 * filas a cero. El ambiente es la excepcion: suena al 60 % (Sfx.AMBIENCE_LEVEL), y le quedan
 * unos 4 dB hasta el volumen entero. Ver [maxDb].
 */
internal object SoundGains {

    const val MIN_DB = -30

    /** Hasta donde sube cada uno: 0, salvo el ambiente, que tiene margen. */
    fun maxDb(name: String): Int = if (name == "ambience") 4 else 0

    /**
     * Los que se ajustan, por el nombre de su fichero en el tema (sin extension), menos los dos
     * ultimos: el ambiente, y el sonido de los videos de juego, que no es de ningun fichero del
     * tema.
     */
    val NAMES = listOf(
        "move", "open", "close", "options", "companion_open", "companion_close",
        "levelup", "achievement", "mission", "ambience", "video",
    )

    private var prefs: Prefs? = null

    /** Con que preferencias: lo llama MainActivity al crearse. */
    fun init(p: Prefs) {
        prefs = p
    }

    fun db(name: String): Int = prefs?.let { it.soundGain(it.themeId, name) } ?: 0

    /** El mismo ajuste como factor de volumen, de 0 a 1. */
    fun lin(name: String): Float = 10f.pow(db(name) / 20f)
}
