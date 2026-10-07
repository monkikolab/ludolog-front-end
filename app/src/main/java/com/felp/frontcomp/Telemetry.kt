package com.felp.frontcomp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.io.File

/**
 * Todo lo que se mide del aparato: carga, temperatura, uso y frecuencia de CPU y GPU.
 *
 * Sin permisos y sin root. Los nodos de GPU del fabricante se leen si el aparato los deja
 * leer, y si no, se quedan vacios: un nulo aqui quiere decir "este aparato no lo dice",
 * nunca "vale cero".
 *
 * Con estado a proposito: el uso de CPU es la diferencia entre dos lecturas de /proc/stat,
 * asi que la anterior tiene que vivir en algun sitio.
 *
 * Viene de RetroCompanion, del mismo autor, y se ha portado casi tal cual para que las
 * cifras salgan iguales. Lo unico que se ha quitado es el ayudante con root.
 */
class Telemetry {

    /** One sample. Nulls mean "not readable on this device", never "zero". */
    data class Sample(
        val tempC: Float? = null,
        val cpuPercent: Float? = null,
        val gpuPercent: Float? = null,
        /** Mean of the per-core current frequencies, in MHz. */
        val cpuMhz: Int? = null,
        val gpuMhz: Int? = null,
        /** The graphics side of the chip, where it has a sensor of its own. */
        val gpuTempC: Float? = null,
        /** What the battery is delivering right now, in watts. */
        val powerW: Float? = null,
        /** Frames the display actually put up in the last second. */
        val fps: Float? = null,
    )

    private var lastCpuBusy = 0L
    private var lastCpuTotal = 0L

    /** Cached once: the set of files to read, so each sample is not a directory scan. */
    private val cpuFreqFiles: List<File> by lazy {
        (0 until 16)
            .map { File("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }
            .filter { it.exists() }
    }

    private val cpuThermalZones: List<File> by lazy { zonesNamed("cpu") }

    /**
     * The zones that measure one part of the chip, found by what the kernel calls them.
     *
     * Matched on "contains" rather than "starts with", because the name is the vendor's:
     * Qualcomm writes `cpu-0-0-usr` and `gpuss-0-usr`, MediaTek writes `mtktscpu`, and a
     * prefix match misses the second one entirely.
     *
     * The exclusions are the other half of the job. A thermal zone is not always a
     * thermometer: beside the real ones sit trip points and protection levels wearing the
     * same clothes. Measured on one test handheld, `gpu-skin-avg-step` reports 13.9 and
     * `skin-therm-step` reports -274, which is below absolute zero; on another,
     * `pmih010x-ibat-lvl1` reports 21, and it is a level rather than a temperature.
     * Anything named for a step, a level or the battery protection circuit is skipped.
     */
    private fun zonesNamed(what: String): List<File> =
        (File("/sys/class/thermal").listFiles() ?: emptyArray())
            .filter { it.name.startsWith("thermal_zone") }
            .filter { zone ->
                val type = runCatching { File(zone, "type").readText().trim().lowercase() }
                    .getOrNull() ?: return@filter false
                type.contains(what) && JUNK_ZONES.none { type.contains(it) }
            }
            .map { File(it, "temp") }

    /** The graphics side, where the kernel exposes it as a zone of its own. */
    private val gpuThermalZones: List<File> by lazy {
        (zonesNamed("gpu") + zonesNamed("kgsl") + zonesNamed("gfx")).distinct()
    }

    fun gpuTempC(): Float? = gpuThermalZones.mapNotNull { readZone(it) }.maxOrNull()

    /**
     * One zone, in degrees.
     *
     * Most kernels report thousandths and some report whole degrees, so the scale is decided
     * by the size of the number: nothing on a handheld is 1000 C, and nothing that is
     * running is 0.037 C.
     */
    private fun readZone(file: File): Float? {
        val raw = runCatching { file.readText().trim().toFloat() }.getOrNull() ?: return null
        val c = if (raw > 1000f || raw < -1000f) raw / 1000f else raw
        return c.takeIf { it > 1f && it < 150f }
    }

    fun sample(context: Context): Sample = Sample(
        tempC = tempC(context),
        gpuTempC = gpuTempC(),
        cpuPercent = cpuPercent(),
        gpuPercent = gpuPercent(),
        cpuMhz = cpuMhz(),
        gpuMhz = gpuMhz(),
        powerW = powerW(context),
        fps = fps(),
    )

    /**
     * Power drawn from the battery, in watts: current times voltage.
     *
     * The charge counter already says how much a session costs, but only once it is over and
     * only as a total. Watts is the same thing as it happens, which is what makes it
     * comparable to a wall figure and to what a tuning tool shows. Checked against such a tool on
     * a test handheld: 3.078 A at 4.053 V, 12.5 W here against 11.8 W there.
     *
     * The sign of the current says charging or discharging and differs by vendor, so only the
     * magnitude is used; a session recorded on the charger is flagged and left out of battery
     * figures anyway.
     */
    fun powerW(context: Context): Float? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Long.MIN_VALUE || raw == 0L) return null
        val millivolts = voltageMv(context) ?: return null
        var microAmps = kotlin.math.abs(raw).toDouble()
        // Android documents microamps and most vendors obey, but some report milliamps. While
        // a game is running, twenty milliamps is not a figure any handheld produces, so a
        // reading that small is the other unit rather than a very quiet console.
        //
        // Solo sin cargador. Enchufada y con la bateria llena, lo que entra o sale SI son unos
        // pocos miliamperios de verdad, y multiplicados salian trece vatios que nadie gastaba,
        // apuntados como el consumo de la partida.
        if (microAmps < MILLIAMP_SUSPICION && !isCharging(context)) microAmps *= 1000.0
        val fromBattery = (microAmps / 1_000_000.0) * (millivolts / 1000.0)
        // On the charger that figure is not the game: the current is flowing the other way,
        // into the battery. What the game costs is then what the charger brings in minus
        // what is being stored, and that only works where the input rail can be read.
        if (raw > 0) return chargingDrawW(fromBattery)
        return fromBattery.toFloat().takeIf { it > 0f && it < MAX_PLAUSIBLE_W }
    }

    /**
     * What the console is taking while it charges: the charger's input, less what is going
     * into the battery.
     *
     * A charger feeds two things at once, and the battery gets the remainder, so the figure
     * the battery reports while plugged in says how fast it is filling, not what the game is
     * doing. Reading the USB rail gives the other term, and the subtraction is the answer.
     * Verified on a test handheld at 63 %: 13.8 W coming in, 10.4 W going into the
     * battery, 3.4 W left for a handheld sitting on its home screen.
     *
     * The result is a little generous. The charging circuit is not lossless, so some of what
     * it does not store was turned into heat rather than spent on the game, and the input
     * reading itself is coarse. It is an estimate, and it says so by being thrown away unless
     * it lands somewhere a handheld could plausibly be.
     */
    private fun chargingDrawW(intoBatteryW: Double): Float? {
        val volts = readVendor(USB_VOLTAGE)?.trim()?.toDoubleOrNull() ?: return null
        val amps = USB_CURRENT.firstNotNullOfOrNull { readVendor(it)?.trim()?.toDoubleOrNull() }
            ?: return null
        if (volts <= 0.0 || amps <= 0.0) return null
        val inputW = (volts / 1_000_000.0) * (amps / 1_000_000.0)
        val draw = inputW - intoBatteryW
        return draw.toFloat().takeIf { it > MIN_PLAUSIBLE_W && it < MAX_PLAUSIBLE_W }
    }

    /** Battery voltage in millivolts, as the battery broadcast reports it. */
    private fun voltageMv(context: Context): Int? {
        val intent = battery(context)
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return mv.takeIf { it > 0 }
    }

    // ---------- battery ----------

    /**
     * El estado de la bateria que Android deja guardado, pedido una vez por lectura.
     *
     * Cada peticion es una llamada al sistema, y en cada medida del seguidor salian dos —la
     * tension para los vatios y si esta enchufada—, una vez por segundo toda la partida. En
     * medio segundo no cambia nada que importe.
     */
    private fun battery(context: Context): Intent? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - batteryAt < 500L) return batteryIntent
        batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        batteryAt = now
        return batteryIntent
    }

    private var batteryIntent: Intent? = null
    private var batteryAt = Long.MIN_VALUE / 2

    /**
     * Charge remaining in microamp-hours. The whole app is built around this number: a
     * session's consumption is the value at the start minus the value at the end, so nothing
     * here needs periodic sampling.
     *
     * Returns null when the device does not implement the property (some do not), in which
     * case consumption falls back to the coarse percentage.
     */
    fun chargeMicroAh(context: Context): Long? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val v = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return if (v == Long.MIN_VALUE || v <= 0L) null else v
    }

    /**
     * What the battery can hold when full, in microamp-hours.
     *
     * The kernel's own figure where it is exposed, and where it is not, the charge counter
     * divided by the level: if the gauge says 8.41 Ah at 97 %, a full one is 8.67 Ah. On one
     * test handheld those two agree to within a thousandth; on another the estimate
     * came out 4 % low at 88 %, which is the ordinary error of reading a percentage that is
     * only reported in whole points.
     *
     * Not the design capacity: this is what the cell holds now, which is the honest divisor
     * for "how much of a battery did that game cost".
     */
    fun fullChargeMicroAh(context: Context): Long? {
        readVendor(BATTERY_FULL, remember = false)?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val counter = chargeMicroAh(context) ?: return null
        val percent = batteryPercent(context).takeIf { it in 5..100 } ?: return null
        return counter * 100L / percent
    }

    /**
     * What it held when new, and how many times it has been round. Both come straight from
     * the kernel or not at all: nothing in the Android API reports either.
     */
    fun designChargeMicroAh(): Long? =
        readVendor(BATTERY_DESIGN, remember = false)?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * Los ciclos de la bateria: del nucleo y, si no deja leerlo, de Android 14 en adelante, que
     * los da con el estado de la bateria.
     */
    fun batteryCycles(context: Context? = null): Int? =
        readVendor(BATTERY_CYCLES, remember = false)?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            ?: context?.takeIf { android.os.Build.VERSION.SDK_INT >= 34 }
                ?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }

    fun batteryPercent(context: Context): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) return (level * 100f / scale).toInt().coerceIn(0, 100)
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Charging invalidates a session's consumption figure: the charge counter climbs while
     * playing, so the subtraction stops meaning anything. Sessions record this so those rows
     * can be excluded from an average instead of poisoning it.
     */
    /**
     * Whether a charger is attached, which is the question that decides if the charge
     * counter means anything.
     *
     * Not `BatteryManager.isCharging`. That asks the battery statistics service whether it
     * considers the battery to be charging, which is its own idea and not the same as a
     * cable being in. Measured on a test handheld at 73 % with 4.2 A going into the
     * battery: the system reported "AC powered: true, status 2" and `isCharging` still
     * answered false. A session started in that state was stored as if it had run on the
     * battery, and its rising percentage then counted as drain.
     *
     * The sticky battery broadcast is the same data the system shows, and `EXTRA_PLUGGED`
     * says plainly what is attached. The old call stays as a last resort for a device that
     * somehow has no sticky intent to hand.
     */
    fun isCharging(context: Context): Boolean {
        val sticky = battery(context)
        if (sticky != null) {
            val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            if (plugged > 0) return true
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            ) {
                return true
            }
            if (plugged == 0) return false
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    // ---------- temperature ----------

    /** SoC temperature, with two fallbacks behind it. */
    fun tempC(context: Context): Float? =
        socTempC() ?: thermalHeadroom(context)?.let { headroomAsTempC(it) } ?: batteryTempC(context)

    /**
     * The hottest part of the chip, which is the one that decides when it throttles.
     *
     * The maximum of the CPU zones rather than the first of them: this is a record of how
     * hot the console ran, and a handheld with one core at 95 C is a handheld at 95 C
     * whatever the other seven say.
     */
    private fun socTempC(): Float? = cpuThermalZones.mapNotNull { readZone(it) }.maxOrNull()

    private fun batteryTempC(context: Context): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private fun thermalHeadroom(context: Context): Float? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val h = runCatching { pm.getThermalHeadroom(0) }.getOrNull() ?: return null
        return if (h.isNaN()) null else h
    }

    private fun headroomAsTempC(headroom: Float): Float =
        HEADROOM_MIN_C + headroom.coerceIn(0f, 1f) * (HEADROOM_MAX_C - HEADROOM_MIN_C)

    // ---------- load ----------

    /**
     * CPU load 0..100, the delta between two samples of the aggregate line in /proc/stat.
     *
     * The first call has nothing to compare against and returns null. At a 1 s sampling
     * interval that costs one sample at the start of a session and nothing after.
     */
    fun cpuPercent(): Float? {
        // The first line only. /proc/stat carries one line per core plus interrupt counters,
        // some fifteen lines on an eight-core part. Reading the whole file to use one line
        // meant parsing all of it into a string, once a second, for nothing.
        val fields = runCatching {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        }.getOrNull()
            ?.takeIf { it.startsWith("cpu ") }
            ?.trim()?.split(' ')?.filter { it.isNotBlank() }
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            ?: return null
        if (fields.size < 4) return null

        val idle = fields[3] + (fields.getOrNull(4) ?: 0L)   // idle + iowait
        val total = fields.sum()
        val busy = total - idle

        val first = lastCpuTotal == 0L
        val deltaBusy = busy - lastCpuBusy
        val deltaTotal = total - lastCpuTotal
        lastCpuBusy = busy
        lastCpuTotal = total

        if (first || deltaTotal <= 0L) return null
        return (deltaBusy * 100f / deltaTotal).coerceIn(0f, 100f)
    }

    /**
     * GPU load 0..100 from the Adreno kgsl driver, or null where the driver is not counting.
     *
     * `gpubusy` is the counter the others come from, and it holds two numbers. They are not
     * running totals: the driver reports busy and total for the interval since the last read
     * and starts again, so the load is one divided by the other. Measured on one test handheld,
     * reading twice a second while the screen scrolled, "29372 1004756" is 2.9 per cent and
     * `gpu_load` said 2 at the same moment.
     *
     * The catch is that a dead counter and an idle GPU both say zero, and the difference
     * matters. On another the pair is frozen at "0 1719110" for hours, through a session of
     * Dissidia 012 with the GPU clocked, and reading the same file as root is no better; five
     * sessions of Mario Odyssey and Blasphemous went into the record as a confident 0 per
     * cent, which is worse than an empty column because it drags an average down and reads as
     * a measurement.
     *
     * What separates them is movement. A driver that is counting produces a busy figure above
     * zero sooner or later, or at the very least a total that differs from the last one. Until
     * this device has shown one of those, a zero is treated as no answer and nothing is
     * recorded. The first handheld proves itself on the first sample; the second never does.
     */
    fun gpuPercent(): Float? {
        busyPair(::readVendor)?.let { (busy, total) ->
            val moved = total != lastGpuTotal && lastGpuTotal != 0L
            lastGpuTotal = total
            if (busy > 0L || moved) gpuCounterAlive = true
            // An interval with no total at all is not a reading, on any device.
            if (total <= 0L) return null
            if (!gpuCounterAlive) return null
            return (busy * 100f / total).coerceIn(0f, 100f)
        }
        // No gpubusy on this device: the derived nodes are all there is, and a flat zero from
        // them cannot be told from a real one.
        readVendor(KGSL_BUSY_PERCENT)?.let { raw ->
            raw.trim().removeSuffix("%").trim().toFloatOrNull()?.let { return it.coerceIn(0f, 100f) }
        }
        readVendor(KGSL_LOAD)?.trim()?.toFloatOrNull()?.let { return it.coerceIn(0f, 100f) }
        return null
    }

    /**
     * The two numbers in `gpubusy`, which the driver prints padded and space separated:
     *
     *     "      0 1719110"
     */
    private fun busyPair(read: (String) -> String?): Pair<Long, Long>? {
        val parts = read(KGSL_BUSY)?.trim()?.split(SPACES) ?: return null
        if (parts.size < 2) return null
        val busy = parts[0].toLongOrNull() ?: return null
        val total = parts[1].toLongOrNull() ?: return null
        return busy to total
    }

    private var lastGpuTotal = 0L

    /** Set once this device's counter has shown any sign of being updated. See [gpuPercent]. */
    private var gpuCounterAlive = false

    /**
     * Frames per second, as the display controller counts them.
     *
     * The compositor keeps its own tally and the kernel publishes it, so the frame rate can
     * be measured without root, without the emulator's cooperation and without an overlay.
     * The node reads
     *
     *     fps: 59.3 duration:1000000 frame_count:60
     *
     * and what it counts is frames actually put on the panel. Not what the emulator rendered
     * internally: a core running at 30 fps into a 60 Hz panel shows here as what reached the
     * screen, which is the number a player would recognise as smooth or not.
     *
     * Zero is thrown away rather than recorded. A still screen composes nothing, so a menu, a
     * pause or a device whose counter never moves would otherwise fill the average with
     * zeros and make a smooth game look terrible.
     *
     * Two paths because vendors disagree on where the display controller lives; whichever
     * answers first is remembered by [readVendor] and the other is never tried again.
     */
    fun fps(): Float? {
        val line = FPS_NODES.firstNotNullOfOrNull { readVendor(it) } ?: return null
        val value = FPS_VALUE.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        return value?.takeIf { it > 0.5f && it < 500f }
    }

    // ---------- clocks ----------

    /**
     * Mean of the per-core current frequencies, in MHz.
     *
     * The mean rather than the maximum: clocks are recorded to catch throttling, and on a
     * big.LITTLE part the maximum stays pinned to whichever core is boosted. Verified on a
     * test handheld: 6 cores at 1996 MHz and 2 at 1017 MHz.
     */
    fun cpuMhz(): Int? {
        val values = cpuFreqFiles.mapNotNull {
            runCatching { it.readText().trim().toLongOrNull() }.getOrNull()
        }
        if (values.isEmpty()) return null
        return (values.average() / 1000.0).toInt()
    }

    fun gpuMhz(): Int? =
        readVendor(KGSL_CUR_FREQ)?.trim()?.toLongOrNull()?.let { (it / 1_000_000L).toInt() }

    /**
     * Lee un nodo sysfs del fabricante.
     *
     * En RetroCompanion esto caia en un ayudante con root cuando SELinux le cerraba la
     * puerta al dominio de la aplicacion. Aqui no: un front-end no debe traerse un
     * servidor con privilegios para dibujar una grafica. Lo que se pueda leer de plano se
     * lee, y lo que no, se queda vacio; el propio fichero ya decia que nada dependia de
     * ese ayudante. /proc y /sys/class/thermal se leen siempre; kgsl no esta garantizado.
     */
    private fun readVendor(path: String, remember: Boolean = true): String? {
        if (path in unreadable) return null
        runCatching { File(path).readText() }.getOrNull()?.let { if (it.isNotBlank()) return it }
        // A node that could not be read will not become readable: the GPU counters are either
        // exposed to the app domain or they are not. Remembering that matters most on the
        // hardware where they are blocked, where the fallback otherwise costs a binder
        // transaction per node per sample: three a second, all session, to be told three
        // times that there is nothing there.
        //
        // Salvo los de la bateria, que se leen una vez por partida y cuestan nada: esos si
        // vuelven. En una consola de pruebas el nodo de ciclos se rehizo el 24-09 (al despertar o con el
        // driver), una lectura fallo en ese momento, y como alli Ludolog es la pantalla de
        // inicio y el proceso no muere, quedo marcado para siempre: desde entonces ninguna
        // partida apunto los ciclos ni la capacidad de fabrica, aunque el nodo se lee bien.
        if (remember) unreadable += path
        return null
    }

    /** Vendor nodes this device does not expose. Established on the first attempt. */
    private val unreadable = mutableSetOf<String>()

    private companion object {
        /** Compiladas una vez: se usaban en cada medida, una por segundo toda la partida. */
        val SPACES = Regex("\\s+")
        val FPS_VALUE = Regex("fps:\\s*([0-9.]+)")

        /** Below this many microamps the reading is milliamps mislabelled. See [powerW]. */
        const val MILLIAMP_SUSPICION = 20_000.0

        /** A handheld that reports more than this is reporting nonsense. */
        const val MAX_PLAUSIBLE_W = 60f

        const val HEADROOM_MIN_C = 30f
        const val HEADROOM_MAX_C = 80f
        /**
         * The charger's own rail. Not part of any Android API: the battery is exposed
         * through BatteryManager, what arrives from the wall is not, so it is sysfs or
         * nothing. Read through [readVendor], which gives up quietly and remembers.
         */
        const val USB_VOLTAGE = "/sys/class/power_supply/usb/voltage_now"

        /**
         * Where the input current lives, in the order it is worth trying. Vendors disagree,
         * and `input_current_settled` is deliberately last: on some kernels it is the limit
         * that was negotiated rather than the current actually flowing.
         */
        val USB_CURRENT = listOf(
            "/sys/class/power_supply/usb/input_current_now",
            "/sys/class/power_supply/usb/current_now",
            "/sys/class/power_supply/usb/input_current_settled",
        )

        /**
         * Below this, a "draw while charging" is the subtraction of two coarse readings
         * rather than a measurement. A handheld with its screen on does not run on a third
         * of a watt.
         */
        const val MIN_PLAUSIBLE_W = 0.3f

        /** The cell itself: what it holds now, what it held new, and how many cycles. */
        const val BATTERY_FULL = "/sys/class/power_supply/battery/charge_full"
        const val BATTERY_DESIGN = "/sys/class/power_supply/battery/charge_full_design"
        const val BATTERY_CYCLES = "/sys/class/power_supply/battery/cycle_count"

        /**
         * Words that mark a thermal zone as something other than a thermometer: a trip
         * point, a protection level or a battery-current limit. See [zonesNamed].
         */
        val JUNK_ZONES = listOf("step", "lvl", "bcl", "ibat", "vbat", "charger", "usb")

        /**
         * The display controller's frame counter. The first path is what one test handheld
         * has, the second what another has; the rest of the family is the same file
         * under whichever card the panel ended up on.
         */
        val FPS_NODES = listOf(
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/drm/card0-sde-crtc-0/measured_fps",
            "/sys/class/drm/card0-sde-crtc-1/measured_fps",
        )

        const val KGSL_BUSY = "/sys/class/kgsl/kgsl-3d0/gpubusy"
        const val KGSL_BUSY_PERCENT = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
        const val KGSL_LOAD = "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load"
        const val KGSL_CUR_FREQ = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
    }
}
