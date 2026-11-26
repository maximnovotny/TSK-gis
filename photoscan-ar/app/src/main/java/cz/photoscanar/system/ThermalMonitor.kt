package cz.photoscanar.system

import android.content.Context
import android.os.PowerManager

/**
 * Thermal headroom. Sustained full-resolution capture plus ARCore heats a phone
 * quickly; when the platform reports throttling the capture rate is reduced
 * instead of letting the camera pipeline stall or the device shut the app down.
 */
class ThermalMonitor(context: Context) {

    private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    @Volatile
    var status: Int = PowerManager.THERMAL_STATUS_NONE
        private set

    fun start(onChange: (Int) -> Unit) {
        val manager = pm ?: return
        status = try { manager.currentThermalStatus } catch (_: Exception) { 0 }
        val l = PowerManager.OnThermalStatusChangedListener { s ->
            status = s
            onChange(s)
        }
        listener = l
        try {
            manager.addThermalStatusListener(l)
        } catch (_: Exception) {
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        try { pm?.removeThermalStatusListener(l) } catch (_: Exception) { }
        listener = null
    }

    fun isThrottling(): Boolean = status >= PowerManager.THERMAL_STATUS_MODERATE

    fun describe(): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }
}
