package com.felp.frontcomp

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Lo que Ludolog sabe de Ludolog Link. Dos cosas: si esta instalado ([present], para la radio de
 * la sala) y la unica pregunta de Ludolog a Ludolog Link que espera respuesta, solo con Link instalado:
 * antes de abrir un juego, ¿hay en otro device una partida de este juego mas nueva que la de aqui?
 * Link la trae si puede; si no, Ludolog avisa antes de lanzar. Sin Link, con su opcion apagada o
 * si no contesta a tiempo, Ludolog lanza como siempre. Ver docs/ludolog-link.md.
 */
object LinkSaveCheck {
    private const val AUTHORITY = BuildConfig.LINK_PACKAGE + ".savecheck"

    /**
     * ok · synced (la trajo) · stale (la hay, pero [peer] no contesta) · conflict (cambio en los dos) ·
     * off (la hay, pero compartir con los devices esta apagado en Link: no la trae; ver [syncOn]).
     */
    class Answer(val result: String, val peer: String?, val playedAt: Long)

    /**
     * Si Link esta instalado, segun se miro la ultima vez que Ludolog volvio a primer plano
     * (MainActivity.onResume). La sala de Parlour lo usa para sacar la radio de Link en el suelo
     * (requires = "link" en tvs.toml). Es estado de Compose: si se instala o se quita, la sala
     * cambia sola al volver.
     */
    val present = androidx.compose.runtime.mutableStateOf(false)

    /**
     * Si Link esta escuchando (su servicio encendido): el LED de su radio va en verde, si no en
     * rojo. Lo dice Link al encenderse y al apagarse (LinkBridge, accion STATE) y se le pregunta
     * al volver a primer plano, por si se perdio el aviso. Con la direccion y cuantos devices
     * tiene emparejados, para lo que se cuenta en su entrada.
     */
    val on = androidx.compose.runtime.mutableStateOf(false)
    val address = androidx.compose.runtime.mutableStateOf("")
    val peers = androidx.compose.runtime.mutableIntStateOf(0)
    /** PC Link puesto (el PC la encuentra) y compartir con los devices: para el texto de su entrada. */
    val pcLink = androidx.compose.runtime.mutableStateOf(false)
    val sync = androidx.compose.runtime.mutableStateOf(true)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Pregunta a Link como esta. Link aprovecha para despertar si le hace falta escuchar (Android o el
     * sistema de la consola pudieron pararlo): volver a Ludolog es lo que lo pone al dia.
     */
    fun refresh(ctx: Context) {
        val now = available(ctx)
        if (present.value != now) present.value = now
        if (!now) { noteState(false, "", 0, false, true); return }
        // Fuera del hilo de la interfaz: la pregunta puede levantar el proceso de Link.
        val app = ctx.applicationContext
        Thread {
            val b = runCatching { app.contentResolver.call(Uri.parse("content://$AUTHORITY"), "state", null, null) }.getOrNull()
            // Un Link que no sabe contestar esto (anterior a la pregunta) se queda como estaba.
            if (b != null) main.post {
                noteState(b.getBoolean("on"), b.getString("address").orEmpty(), b.getInt("peers"),
                    b.getBoolean("pcLink", false), b.getBoolean("sync", true))
                if (b.getBoolean("needed") && !b.getBoolean("on")) wake(app)
            }
        }.start()
    }

    /**
     * Despierta el servicio de Link (tiene devices o PC Link y no escucha: Android o el sistema
     * de la consola lo pararon). Lo hace Ludolog porque esta a la vista: a Link, en segundo plano,
     * Android 15 no se lo deja. Link lo exporta solo con el permiso de firma. Si Android tampoco
     * lo deja (con la pantalla bloqueada), sera la proxima vez que se vuelva aqui.
     */
    private fun wake(ctx: Context) = runCatching {
        ctx.startForegroundService(android.content.Intent("com.felp.ludologlink.WAKE")
            .setClassName(BuildConfig.LINK_PACKAGE, "com.felp.ludologlink.LinkService"))
    }

    /** Lo que dijo Link de si mismo. En el hilo principal. */
    fun noteState(isOn: Boolean, addr: String, paired: Int, pc: Boolean, sharing: Boolean) {
        if (on.value != isOn) on.value = isOn
        if (address.value != addr) address.value = addr
        if (peers.intValue != paired) peers.intValue = paired
        if (pcLink.value != pc) pcLink.value = pc
        if (sync.value != sharing) sync.value = sharing
    }

    /**
     * Volver a compartir con los devices: la persona lo eligio en el aviso "off" de antes de jugar.
     * Link lo enciende y se pone al dia; despues se vuelve a preguntar por la partida. Fuera del hilo
     * de la interfaz. Falso si Link no contesto.
     */
    fun syncOn(ctx: Context): Boolean = runCatching {
        ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), "sync_on", null, null)?.getBoolean("sync") == true
    }.getOrDefault(false)

    /** Si Link esta instalado y ofrece la pregunta. Solo mira el sistema: barato. */
    fun available(ctx: Context): Boolean =
        runCatching { ctx.packageManager.resolveContentProvider(AUTHORITY, 0) != null }.getOrDefault(false)

    /** Pregunta y espera: fuera del hilo de la interfaz. Nulo si falla. */
    fun ask(ctx: Context, pkg: String, file: String, title: String): Answer? = runCatching {
        val b = ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), "check", null, Bundle().apply {
            putString("pkg", pkg); putString("file", file); putString("title", title)
        }) ?: return null
        Answer(b.getString("result") ?: "ok", b.getString("peer"), b.getLong("playedAt"))
    }.getOrNull()

    /** Abre Ludolog Link en su pestaña de partidas, con los conflictos a la vista. */
    fun openLink(ctx: Context) = runCatching {
        ctx.startActivity(android.content.Intent().setClassName(BuildConfig.LINK_PACKAGE, "com.felp.ludologlink.MainActivity")
            .putExtra("tab", "saves").putExtra("conflicts", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Abre Ludolog Link tal cual, desde su entrada en la lista de consolas. */
    fun openHome(ctx: Context) = runCatching {
        ctx.startActivity(android.content.Intent().setClassName(BuildConfig.LINK_PACKAGE, "com.felp.ludologlink.MainActivity")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
