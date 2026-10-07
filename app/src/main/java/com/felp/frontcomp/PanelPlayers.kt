package com.felp.frontcomp

import androidx.media3.common.Player
import java.util.Collections
import java.util.WeakHashMap

/**
 * Los reproductores del panel, para poder callarlos todos a la vez.
 *
 * Un reproductor no sabe nada del ciclo de vida de la aplicacion: si nadie lo para, sigue
 * reproduciendo con la aplicacion detras. Eso se oia al lanzar un juego, con el video de la
 * caratula sonando por debajo del emulador, y no habia forma de callarlo sin volver.
 *
 * Se lleva la cuenta con referencias debiles: si un panel desaparece sin avisar, su
 * reproductor se recoge igual y aqui no queda nada que lo retenga vivo.
 *
 * Y se apunta a cuales se paro, no se arrancan todos al volver. Un panel puede estar parado
 * por sus propios motivos —no hay video, o hay una ventana encima—, y reanudar a ciegas
 * encenderia cosas que nadie habia encendido.
 */
internal object PanelPlayers {

    private val live: MutableSet<Player> =
        Collections.newSetFromMap(WeakHashMap<Player, Boolean>())
    private val paused = HashSet<Player>()

    @Synchronized
    fun add(player: Player) {
        live += player
    }

    @Synchronized
    fun remove(player: Player) {
        live -= player
        paused -= player
    }

    /** Con la aplicacion al fondo: lo que se prepare ahora no arranca hasta volver. */
    private var backgrounded = false

    /** Al irse la aplicacion a segundo plano. */
    @Synchronized
    fun pauseAll() {
        backgrounded = true
        for (p in live) {
            // Los que QUIEREN sonar, no solo los que suenan: uno que aun esta cargando no
            // cuenta como sonando, y arrancaba solo en cuanto estaba listo, con el emulador
            // ya delante —el decodificador y los efectos del tubo trabajando para nadie—.
            val wants = runCatching { p.playWhenReady }.getOrDefault(false)
            if (!wants) continue
            runCatching { p.pause() }.onSuccess { paused += p }
        }
    }

    /** Al volver, y solo los que se pararon aqui. */
    @Synchronized
    fun resumeAll() {
        backgrounded = false
        for (p in paused) runCatching { p.play() }
        paused.clear()
    }

    /**
     * Arranca un reproductor recien preparado, o lo deja para cuando se vuelva.
     *
     * Entre la pausa y la parada de la actividad Compose sigue componiendo mientras se abre el
     * emulador, y un panel montado en ese rato se ponia a reproducir por su cuenta.
     */
    @Synchronized
    fun startOrHold(player: Player) {
        if (backgrounded) paused += player else runCatching { player.play() }
    }
}
