package com.felp.frontcomp

import android.app.Application

/**
 * El proceso, antes que cualquier pantalla o servicio.
 *
 * Existe para una sola cosa: que la carpeta de datos se sepa antes de que nadie la pida. La
 * piden objetos sin Context —el cuaderno, el catalogo, los temas— desde la actividad y desde
 * el servicio que mide las partidas, y ese servicio puede levantar el proceso sin que la
 * actividad llegue a existir.
 */
class LudologApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Lo primero: un fallo de aqui en adelante queda para el diagnostico (ver Diagnostics).
        CrashLog.install(this)
        DataHome.init(this)
        // Lo que quedara en la carpeta de trabajo de un proceso anterior: descargas cortadas,
        // conversiones dejadas atras. Aqui el proceso acaba de nacer y nada lo esta usando.
        runCatching { DataHome.work().listFiles()?.forEach { it.delete() } }
        // Un respaldo que Ludolog Link devolvio desde el PC: ahora, que nadie tiene abiertos los
        // cuadernos. Sin Link no hay nada. Ver LinkRestore.
        runCatching { LinkRestore.applyPending() }
        // Ajustes que Ludolog Link dejo desde el PC y cuyo aviso no llego: antes que nadie los lea.
        // Sin Link no hay nada que aplicar. Ver LinkConfig.
        runCatching { LinkConfig.applyPending() }
        // Correcciones de otras consolas cuyo aviso no llego (Ludolog detenida): igual. Ver LinkEdits.
        runCatching { LinkEdits.applyIncoming(this) }
    }
}
