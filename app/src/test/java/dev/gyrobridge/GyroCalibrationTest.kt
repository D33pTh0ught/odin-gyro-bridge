package dev.gyrobridge

import org.junit.Assert.*
import org.junit.Test

class GyroCalibrationTest {
    @Test fun calibrationNeedsAFullStationaryInterval() {
        val calibration = GyroCalibration()
        calibration.start()
        for (sample in 0 until 400) {
            assertNull(calibration.sample(1_000_000_000L + sample * 5_000_000L, .01f, -.02f, .03f))
        }
        val bias = calibration.sample(3_000_000_000L, .01f, -.02f, .03f)!!
        assertEquals(.01f, bias.x, .00001f)
        assertEquals(-.02f, bias.y, .00001f)
        assertEquals(.03f, bias.z, .00001f)
        assertFalse(calibration.active)
    }

    @Test fun sleepDoesNotCountAsStationarySampling() {
        val calibration = GyroCalibration()
        calibration.start()
        for (sample in 0..200) calibration.sample(1_000_000_000L + sample * 5_000_000L, .01f, 0f, 0f)
        calibration.resetInterval() // Screen goes off; calibration remains requested.
        assertTrue(calibration.active)
        assertNull(calibration.sample(90_000_000_000L, .04f, 0f, 0f))
        for (sample in 1 until 400) {
            assertNull(calibration.sample(90_000_000_000L + sample * 5_000_000L, .04f, 0f, 0f))
        }
        assertEquals(.04f, calibration.sample(92_000_000_000L, .04f, 0f, 0f)!!.x, .00001f)
    }

    @Test fun movementRestartsTheStationaryWindow() {
        val calibration = GyroCalibration()
        calibration.start()
        for (sample in 0..300) calibration.sample(1_000_000_000L + sample * 5_000_000L, 0f, 0f, 0f)
        assertNull(calibration.sample(2_505_000_000L, 1f, 0f, 0f))
        assertNull(calibration.sample(3_000_000_000L, 0f, 0f, 0f))
        assertTrue(calibration.active)
    }
}
