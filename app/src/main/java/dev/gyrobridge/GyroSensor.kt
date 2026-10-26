package dev.gyrobridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Surface
import java.io.Closeable
import kotlin.math.sqrt

/** Sensor acquisition, screen coordinates, calibration and sampling rate.
 * All methods and callbacks run on the service's worker looper. */
class GyroSensor(
    context: Context,
    private val handler: Handler,
    private val settings: () -> GyroSettings,
    private val onSample: (Long, Float, Float) -> Unit,
    private val onCalibration: (GyroBias) -> Unit,
    private val onRate: (Int) -> Unit,
) : SensorEventListener, Closeable {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val calibration = GyroCalibration()
    private val down = FloatArray(3)
    private var gravityReady = false
    private var registered = false
    private var rotation = displays.getDisplay(0)?.rotation ?: Surface.ROTATION_90
    private var count = 0
    private var rateStart = 0L
    val available get() = gyro != null
    val calibrating get() = calibration.active

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == 0) rotation = displays.getDisplay(0)?.rotation ?: rotation
        }
    }

    init { displays.registerDisplayListener(displayListener, handler) }

    fun resume() {
        if (registered || gyro == null) return
        check(sensors.registerListener(this, gyro, 5000, 0, handler)) { "Cannot start gyroscope sampling." }
        registered = true
        gravity?.let { sensors.registerListener(this, it, 20000, 0, handler) }
    }

    fun pause() {
        sensors.unregisterListener(this)
        registered = false
        gravityReady = false
        count = 0
        rateStart = 0L
        calibration.resetInterval()
        onRate(0)
    }

    fun calibrate() = calibration.start()

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            val length = sqrt(x * x + y * y + z * z)
            if (length.isFinite() && length > 1f) {
                down[0] = x / length
                down[1] = y / length
                down[2] = z / length
                gravityReady = true
            }
            return
        }

        calibration.sample(event.timestamp, x, y, z)?.let(onCalibration)
        val config = settings()
        val gx = x - config.biasX
        val gy = y - config.biasY
        val gz = z - config.biasZ
        val mapped = mapToScreen(gx, gy, rotation)
        // Gravity projection includes roll when the handheld is tilted toward your lap.
        val horizontal = if (gravityReady) gx * down[0] + gy * down[1] + gz * down[2] else mapped.first
        onSample(event.timestamp, horizontal, mapped.second)

        if (rateStart == 0L) rateStart = event.timestamp
        count++
        val elapsed = event.timestamp - rateStart
        if (elapsed >= 1_000_000_000L) {
            onRate((count * 1e9 / elapsed).toInt())
            count = 0
            rateStart = event.timestamp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun close() {
        pause()
        displays.unregisterDisplayListener(displayListener)
    }

    companion object {
        fun mapToScreen(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
            Surface.ROTATION_90 -> x to -y
            Surface.ROTATION_180 -> y to x
            Surface.ROTATION_270 -> -x to y
            else -> -y to -x
        }
    }
}
