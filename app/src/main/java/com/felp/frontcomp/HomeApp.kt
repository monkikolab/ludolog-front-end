package com.felp.frontcomp

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ludolog como app de inicio: lo que abren el boton de casa y el arranque del aparato.
 *
 * La decide la persona, nunca el programa. Lo que hay aqui es saber si lo es y abrir lo que
 * Android ofrece para cambiarlo: su propia peticion, con su boton de confirmar, para serlo; y sus
 * ajustes de inicio para dejar de serlo, que es donde se elige otro —Android no deja soltar el
 * papel desde la app que lo tiene—.
 */
internal object HomeApp {

    /**
     * Si lo es ahora mismo.
     *
     * Se cambia fuera del programa, en los ajustes de Android o en su dialogo, asi que se vuelve a
     * mirar cada vez que la actividad vuelve a primer plano: ver MainActivity.onResume.
     */
    var held by mutableStateOf(false)
        private set

    fun refresh(ctx: Context) {
        held = runCatching {
            val roles = ctx.getSystemService(RoleManager::class.java)
            roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) &&
                roles.isRoleHeld(RoleManager.ROLE_HOME)
        }.getOrDefault(false)
    }

    /**
     * Si esta app puede ser la de inicio: si declara la pantalla de inicio en su manifiesto. La
     * copia de prueba (`fresh`) no la declara, y su fila «Home app» se pulsaba sin que pasara nada.
     */
    fun possible(ctx: Context): Boolean = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
        ctx.packageManager.queryIntentActivities(home, 0).isNotEmpty()
    }.getOrDefault(true)

    /**
     * Lo que hay que abrir para cambiarlo: la peticion para serlo, o, si ya lo es, los ajustes de
     * inicio de Android para volver al de antes.
     */
    fun intent(ctx: Context): Intent {
        val roles = ctx.getSystemService(RoleManager::class.java)
        return if (!held && roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
    }
}
