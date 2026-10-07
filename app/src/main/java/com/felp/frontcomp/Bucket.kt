package com.felp.frontcomp

/**
 * Collapses many fine-grained samples into one stored row.
 *
 * Why a mean and an extreme: the mean alone hides what you go looking for. A session at a
 * steady 60 C and one averaging 50 C with spikes to 75 C and throttling have similar means
 * and play nothing alike. So each metric keeps its mean plus whichever end of the range
 * carries the information:
 *
 *   temperature -> max   (the peak trips the throttling)
 *   CPU/GPU load -> max  (the peak explains a stutter)
 *   CPU clock -> min     (the floor is what throttling looks like)
 *   power -> max         (the peak is what the battery and the fan have to survive)
 *
 * Sampling fine and storing coarse costs more wakeups, not fewer; the point is fidelity, not
 * saving work. A CPU load figure is a delta between two /proc/stat reads, and a delta taken
 * over ten seconds has already flattened any spike inside it beyond recovery.
 */
class Bucket {

    private class Track {
        var sum = 0.0
        var count = 0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE

        fun add(v: Double) {
            sum += v; count++
            if (v < min) min = v
            if (v > max) max = v
        }

        val mean: Double? get() = if (count == 0) null else sum / count
        val lowest: Double? get() = if (count == 0) null else min
        val highest: Double? get() = if (count == 0) null else max
    }

    private val temp = Track()
    private val gpuTemp = Track()
    private val cpu = Track()
    private val gpu = Track()
    private val cpuClock = Track()
    private val gpuClock = Track()
    private val power = Track()
    private val frames = Track()

    var samples = 0
        private set

    fun add(s: Telemetry.Sample) {
        samples++
        s.tempC?.let { temp.add(it.toDouble()) }
        s.gpuTempC?.let { gpuTemp.add(it.toDouble()) }
        s.cpuPercent?.let { cpu.add(it.toDouble()) }
        s.gpuPercent?.let { gpu.add(it.toDouble()) }
        s.cpuMhz?.let { cpuClock.add(it.toDouble()) }
        s.gpuMhz?.let { gpuClock.add(it.toDouble()) }
        s.powerW?.let { power.add(it.toDouble()) }
        s.fps?.let { frames.add(it.toDouble()) }
    }

    fun isEmpty(): Boolean = samples == 0

    /** One stored row. Nulls survive: a metric this device cannot read stays empty. */
    fun close(): Row = Row(
        samples = samples,
        tempMeanC = temp.mean?.toFloat(),
        tempMaxC = temp.highest?.toFloat(),
        gpuTempMeanC = gpuTemp.mean?.toFloat(),
        gpuTempMaxC = gpuTemp.highest?.toFloat(),
        cpuMeanPercent = cpu.mean?.toFloat(),
        cpuMaxPercent = cpu.highest?.toFloat(),
        gpuMeanPercent = gpu.mean?.toFloat(),
        gpuMaxPercent = gpu.highest?.toFloat(),
        cpuMeanMhz = cpuClock.mean?.toInt(),
        cpuMinMhz = cpuClock.lowest?.toInt(),
        gpuMeanMhz = gpuClock.mean?.toInt(),
        powerMeanW = power.mean?.toFloat(),
        powerMaxW = power.highest?.toFloat(),
        // The mean says how it ran; the floor says where it stuttered, and on a handheld
        // the floor is the half anyone remembers.
        fpsMean = frames.mean?.toFloat(),
        fpsMinimum = frames.lowest?.toFloat(),
    )

    data class Row(
        val samples: Int,
        val tempMeanC: Float?,
        val tempMaxC: Float?,
        val gpuTempMeanC: Float?,
        val gpuTempMaxC: Float?,
        val cpuMeanPercent: Float?,
        val cpuMaxPercent: Float?,
        val gpuMeanPercent: Float?,
        val gpuMaxPercent: Float?,
        val cpuMeanMhz: Int?,
        val cpuMinMhz: Int?,
        val gpuMeanMhz: Int?,
        val powerMeanW: Float?,
        val powerMaxW: Float?,
        val fpsMean: Float?,
        val fpsMinimum: Float?,
    )
}
