package cz.photoscanar.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Rotation-rate watchdog, straight from the gyroscope.
 *
 * This is deliberately independent of ARCore: the "you are moving too fast"
 * warning and the blur-aware timing in SMART mode must keep working when AR is
 * off or tracking has been lost.
 */
class MotionMonitor(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Smoothed rotation rate magnitude in rad/s. */
    @Volatile var angularSpeedRadS: Float = 0f
        private set

    /** Peak over the last window, which is what actually smears a frame. */
    @Volatile var peakAngularSpeedRadS: Float = 0f
        private set

    /** Linear acceleration magnitude minus gravity, m/s^2; a rough shake measure. */
    @Volatile var linearAccelMagnitude: Float = 0f
        private set

    private var peakResetAt = 0L

    val available: Boolean get() = gyro != null

    fun start() {
        sm?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        sm?.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sm?.unregisterListener(this)
        angularSpeedRadS = 0f
        peakAngularSpeedRadS = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val m = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                // light low-pass so a single noisy sample does not trigger a warning
                angularSpeedRadS = angularSpeedRadS * 0.7f + m * 0.3f
                if (m > peakAngularSpeedRadS) peakAngularSpeedRadS = m
                val now = System.currentTimeMillis()
                if (now - peakResetAt > 700) {
                    peakResetAt = now
                    peakAngularSpeedRadS = m
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val m = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                linearAccelMagnitude = kotlin.math.abs(m - SensorManager.GRAVITY_EARTH)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Above this the frame is very likely smeared at normal shutter speeds. */
    fun isMovingTooFast(): Boolean = angularSpeedRadS > FAST_ROTATION_RAD_S

    companion object {
        const val FAST_ROTATION_RAD_S = 0.9f
    }
}
