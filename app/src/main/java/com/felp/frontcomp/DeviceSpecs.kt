package com.felp.frontcomp

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * La ficha tecnica del aparato en el que corre esto, con la misma forma que la de una consola
 * del catalogo: rotulo y dato, cuatro como mucho.
 *
 * Es la de la fila de los juegos de Android. Esos juegos no corren en una maquina de otra epoca
 * sino en esta, asi que su consola es el propio aparato, y lo que se cuenta de ella es lo que se
 * cuenta de cualquier otra: el procesador, la memoria, la pantalla y el sistema.
 */
internal object DeviceSpecs {

    fun of(ctx: Context): List<Pair<String, String>> = listOfNotNull(
        chip()?.let { "CPU" to it },
        ram(ctx)?.let { "RAM" to it },
        display(ctx)?.let { "DISPLAY" to it },
        "ANDROID" to "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
    )

    /**
     * El procesador por su nombre comercial, que es el que se conoce.
     *
     * Android da el de pieza —SM8250— y ese no lo reconoce nadie. Los de esta tabla son los de
     * las consolas de mano con Android; uno que no este sale como lo da el sistema, que sigue
     * siendo verdad aunque se lea peor.
     */
    private fun chip(): String? {
        val model = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
            ?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: Build.HARDWARE.takeIf { it.isNotBlank() }
            ?: return null
        return SOC_NAMES[model.uppercase()] ?: model
    }

    private val SOC_NAMES = mapOf(
        "SM6115" to "Snapdragon 662",
        "SM6225" to "Snapdragon 680",
        "SM7325" to "Snapdragon 778G",
        "SM8150" to "Snapdragon 855",
        "SM8250" to "Snapdragon 865",
        "SM8350" to "Snapdragon 888",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8750" to "Snapdragon 8 Elite",
    )

    /**
     * La memoria como la anuncia la caja: 8 GB y no 7,5.
     *
     * El sistema cuenta lo que queda despues de apartar lo suyo, que siempre es algo menos que
     * lo que lleva el aparato. Se redondea hacia arriba a la medida comercial mas cercana.
     */
    private fun ram(ctx: Context): String? {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        if (info.totalMem <= 0L) return null
        val gb = info.totalMem / 1_073_741_824.0
        val sold = SIZES.firstOrNull { it >= gb } ?: kotlin.math.ceil(gb).toInt()
        return "$sold GB"
    }

    private val SIZES = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)

    /** La pantalla fisica, apaisada, con su refresco. */
    private fun display(ctx: Context): String? {
        val mode = ctx.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode ?: return null
        val w = max(mode.physicalWidth, mode.physicalHeight)
        val h = min(mode.physicalWidth, mode.physicalHeight)
        return "${w}×$h ${mode.refreshRate.roundToInt()} Hz"
    }
}
