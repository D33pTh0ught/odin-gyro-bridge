package dev.gyrobridge

import kotlin.math.abs

data class GyroBias(val x: Float, val y: Float, val z: Float)

/** Accumulates one uninterrupted stationary interval, never across screen sleep. */
class GyroCalibration {
    var active = false
        private set
    private var startedAt = 0L
    private var count = 0
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0

    fun start() {
        active = true
        resetInterval()
    }

    fun resetInterval() {
        startedAt = 0L
        count = 0
        sumX = 0.0
        sumY = 0.0
        sumZ = 0.0
    }

    fun sample(timeNanos: Long, x: Float, y: Float, z: Float): GyroBias? {
        if (!active) return null
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() ||
            abs(x) > STATIONARY_THRESHOLD || abs(y) > STATIONARY_THRESHOLD || abs(z) > STATIONARY_THRESHOLD) {
            resetInterval()
            return null
        }
        if (startedAt == 0L) startedAt = timeNanos
        sumX += x
        sumY += y
        sumZ += z
        count++
        if (timeNanos - startedAt < INTERVAL_NANOS || count < MINIMUM_SAMPLES) return null
        active = false
        return GyroBias((sumX / count).toFloat(), (sumY / count).toFloat(), (sumZ / count).toFloat())
    }

    companion object {
        private const val STATIONARY_THRESHOLD = .15f
        private const val INTERVAL_NANOS = 2_000_000_000L
        private const val MINIMUM_SAMPLES = 100
    }
}
