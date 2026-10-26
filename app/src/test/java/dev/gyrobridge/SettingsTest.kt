package dev.gyrobridge

import org.junit.Assert.*
import org.junit.Test

class SettingsTest {
    @Test fun smoothingDisplaysTimeConstantWithoutChangingSavedStrength() {
        assertEquals(10f, GyroSettings(smoothing = .25f).smoothingMillis, .0001f)
        assertEquals(4f, GyroSettings(smoothing = .1f).smoothingMillis, .0001f)
        assertEquals(0f, GyroSettings(smoothing = 0f).smoothingMillis, .0001f)
        assertEquals(40f, GyroSettings(smoothing = 1f).smoothingMillis, .0001f)
    }
    @Test fun modeChangesRetainSeparateAxisGains() {
        val mouse = GyroSettings(mouseX = 8f, mouseY = 15f, stickX = .5f, stickY = 2f)
        val stick = mouse.copy(stick = true)
        assertEquals(8f, stick.mouseX)
        assertEquals(15f, stick.mouseY)
        assertEquals(.5f, stick.stickX)
        assertEquals(2f, stick.stickY)
        assertTrue(stick.stick)
    }
    @Test fun triggerAndGripCodesRemainDistinct() {
        assertNotEquals(ControllerButtons.nameOf(1, 306), ControllerButtons.nameOf(1, 309))
        assertEquals("L2 (analog)", ControllerButtons.nameOf(3, 10))
        assertEquals("R2 (analog)", ControllerButtons.nameOf(3, 9))
    }
}
