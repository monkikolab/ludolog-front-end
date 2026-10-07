package com.felp.frontcomp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The bits of device state the top bar shows.
 *
 * A handheld is used away from a desk, so battery and connectivity are not decoration:
 * they are the reason people glance at the top of the screen at all.
 */
data class DeviceState(
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val online: Boolean = false,
    /** Auriculares con cable, o por USB. */
    val headphones: Boolean = false,
    /** Algo conectado por bluetooth que reproduce sonido: cascos, altavoz, casco de juego. */
    val bluetooth: Boolean = false,
    val clock: String = "",
)

/**
 * Watches device state.
 *
 * Battery arrives as a sticky broadcast, which is both the value now and the updates
 * later, so one receiver covers both. The clock ticks on its own because no broadcast
 * fires for "a minute passed" that is worth registering for here.
 */
@Composable
fun rememberDeviceState(): DeviceState {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(DeviceState()) }

    DisposableEffect(ctx) {
        fun read(intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1

            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
            val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

            // Cable y bluetooth se cuentan por separado: son dos iconos distintos y en un
            // aparato de mano no significan lo mismo. Se leen de las SALIDAS de audio y no
            // del adaptador bluetooth a proposito, porque preguntarle al adaptador exige el
            // permiso BLUETOOTH_CONNECT desde Android 12, y un front-end que pide permisos
            // al abrirse es lo que nadie quiere. Asi se ve lo que de verdad importa: que hay
            // algo puesto por donde va a salir el sonido.
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val outs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            val wired = outs.any {
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val bt = outs.any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == android.media.AudioDeviceInfo.TYPE_HEARING_AID
            }

            state = state.copy(
                batteryPercent = pct,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
                online = online,
                headphones = wired,
                bluetooth = bt,
            )
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = read(i)
        }
        // registerReceiver with a battery filter returns the current value immediately,
        // so there is no separate "read once at startup" path to keep in sync.
        val sticky = ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        read(sticky)

        // Los cascos, en cambio, no avisan por ahi.
        //
        // Enchufarlos o soltarlos no cambia la bateria, asi que el icono se quedaba como
        // estuviera hasta el siguiente aviso de carga, que pueden ser minutos. Esta llamada
        // avisa en el momento, que es cuando la persona esta mirando el icono para saber si
        // ha funcionado.
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val plugs = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) =
                read(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

            override fun onAudioDevicesRemoved(gone: Array<out android.media.AudioDeviceInfo>?) =
                read(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }
        audio?.registerAudioDeviceCallback(plugs, null)

        onDispose {
            runCatching { ctx.unregisterReceiver(receiver) }
            runCatching { audio?.unregisterAudioDeviceCallback(plugs) }
        }
    }

    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            state = state.copy(clock = fmt.format(Date()))
            delay(20_000)
        }
    }

    return state
}
