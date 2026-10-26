package dev.gyrobridge

import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class BridgeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }
    @Test fun nativeMotionMath() {
        System.loadLibrary("gyrobridge")
        assertEquals("PASS: 13 native motion checks", NativeBridge.selfTest())
    }
    @Test fun stockBridgeMergesGyroAndProducesOsInput() {
        context.stopService(Intent(context, GyroService::class.java))
        Thread.sleep(400)
        val activity = instrumentation.startActivitySync(Intent(context, InputProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Probe activity has input focus") { activity.hasWindowFocus() }
        val padCount = InputDevice.getDeviceIds().count { InputDevice.getDevice(it)?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true }
        val physicalController = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
            .first { it.vendorId == 0x2020 && it.productId == 0x0112 && it.name != "GyroBridge Controller" }
        fun checkAxisMetadata(controller: InputDevice, gyroStick: Boolean) {
            val rightStick = setOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
            val axes = rightStick + setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
            for (axis in axes) {
                val original = checkNotNull(physicalController.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK))
                val forwarded = checkNotNull(controller.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK))
                val expectedFlat = if (gyroStick && axis in rightStick) 0f else original.flat
                assertEquals("Axis $axis retains the appropriate deadzone metadata", expectedFlat, forwarded.flat, .000001f)
            }
        }
        val session = DeviceBridge(context)
        val lines = session.lines
        try {
            session.awaitReady()
            session.send("CONFIG 1 1 310 1 0 12 12 1 1 0 0")
            session.send("START")
            waitFor("Stick service starts: $lines") { lines.any { it.startsWith("STATE 1 ") } }
            waitFor("Virtual gamepad registered") { InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
            val mergedId = InputDevice.getDeviceIds().first { InputDevice.getDevice(it)?.name == "GyroBridge Controller" }
            checkAxisMetadata(checkNotNull(InputDevice.getDevice(mergedId)), gyroStick = true)
            assertEquals(padCount + 1, InputDevice.getDeviceIds().count { InputDevice.getDevice(it)?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true })
            session.send("START")
            Thread.sleep(100)
            assertNotNull("Repeated START keeps the same controller", InputDevice.getDevice(mergedId))
            InputProbeActivity.sticks.clear(); InputProbeActivity.keys.clear()
            session.send("TESTEVENT 1 310 1")
            session.send("TESTEVENT 3 2 8192")
            session.send("TESTEVENT 0 0 0")
            session.send("GYRO 100 -0.436332313 0")
            session.send("GYRO 100.005 -0.436332313 0")
            waitFor("Physical .25 + gyro .25 reaches Android through the virtual gamepad") {
                InputProbeActivity.sticks.any { abs(it.x - .5f) < .005f && it.deviceId == mergedId }
            }
            assertFalse("Consumed activation button reaches no app", InputProbeActivity.keys.any { it.first == KeyEvent.KEYCODE_BUTTON_L1 })
            // Tuning must not disconnect/recreate the controller while a game uses it.
            session.send("CONFIG 1 1 310 1 0 12 12 2 1 0 0")
            session.send("GYRO 100.010 -0.436332313 0"); session.send("GYRO 100.015 -0.436332313 0")
            waitFor("Sensitivity updates in place") { InputProbeActivity.sticks.any { abs(it.x - .75f) < .005f && it.deviceId == mergedId } }
            assertEquals("Controller identity stays stable", mergedId, InputDevice.getDeviceIds().first { InputDevice.getDevice(it)?.name == "GyroBridge Controller" })
            InputProbeActivity.sticks.clear()
            session.send("TESTEVENT 1 310 0")
            waitFor("Release removes gyro and preserves physical stick") { InputProbeActivity.sticks.any { abs(it.x - .25f) < .005f } }
            session.send("TESTEVENT 1 304 1"); session.send("TESTEVENT 1 304 0")
            waitFor("Other buttons pass through") { InputProbeActivity.keys.contains(KeyEvent.KEYCODE_BUTTON_A to KeyEvent.ACTION_DOWN) && InputProbeActivity.keys.contains(KeyEvent.KEYCODE_BUTTON_A to KeyEvent.ACTION_UP) }
            // Both extra/grip event codes and analog triggers can be assigned.
            session.send("BIND"); session.send("TESTEVENT 1 306 1"); session.send("TESTEVENT 1 306 0")
            waitFor("First grip code records") { lines.contains("BOUND 1 306") }
            session.send("BIND"); session.send("TESTEVENT 1 309 1"); session.send("TESTEVENT 1 309 0")
            waitFor("Second grip code records") { lines.contains("BOUND 1 309") }
            session.send("BIND"); session.send("TESTEVENT 3 10 25000"); session.send("TESTEVENT 3 10 0")
            waitFor("Analog trigger records") { lines.contains("BOUND 3 10") }
            // Switch to real uinput mouse output and verify Android receives pointer events.
            session.send("CONFIG 0 1 310 1 0 12 12 1 1 0 0"); session.send("START")
            waitFor("Virtual mouse registered") { InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == "GyroBridge Mouse" } }
            val mouseController = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
                .first { it.name == "GyroBridge Controller" }
            checkAxisMetadata(mouseController, gyroStick = false)
            InputProbeActivity.mice.clear()
            session.send("TESTEVENT 1 310 1")
            session.send("GYRO 101 1 0"); session.send("GYRO 101.005 1 0"); session.send("GYRO 101.010 1 0")
            waitFor("OS mouse event reaches focused app") { InputProbeActivity.mice.isNotEmpty() }
            session.send("TESTEVENT 1 310 0"); session.send("TESTEVENT 3 2 0")
            session.send("STOP")
            waitFor("Stop removes virtual mouse") { InputDevice.getDeviceIds().none { InputDevice.getDevice(it)?.name == "GyroBridge Mouse" } }
            waitFor("Stop removes passthrough controller") { InputDevice.getDeviceIds().none { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
            session.checkHealthy()
        } finally {
            session.close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun lostHeartbeatRemovesVirtualDevicesAndReleasesController() {
        context.stopService(Intent(context, GyroService::class.java))
        Thread.sleep(400)
        val session = DeviceBridge(context, heartbeat = false)
        val lines = session.lines
        try {
            session.awaitReady()
            session.send("CONFIG 1 1 310 0 0 12 12 1 1 0 0"); session.send("START")
            waitFor("Virtual gamepad exists") { InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
            waitFor("Watchdog reports heartbeat loss") { lines.any { it.contains("heartbeat lost") } }
            waitFor("Watchdog removes virtual gamepad") { InputDevice.getDeviceIds().none { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
            assertTrue("Original controller remains available", InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == "Xbox Wireless Controller" })
        } finally { session.close() }
    }

    @Test fun closingConnectionReleasesVirtualPadImmediately() {
        context.stopService(Intent(context, GyroService::class.java))
        Thread.sleep(400)
        val session = DeviceBridge(context)
        try {
            session.awaitReady()
            session.send("CONFIG 1 1 310 0 0 12 12 1 1 0 0")
            session.send("START")
            waitFor("Pad registered") { InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
        } finally { session.close() }
        waitFor("Disconnect removes pad") { InputDevice.getDeviceIds().none { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
    }

    @Test fun invalidConfigurationFailsOpen() {
        context.stopService(Intent(context, GyroService::class.java))
        Thread.sleep(400)
        DeviceBridge(context).use { session ->
            session.awaitReady()
            session.send("CONFIG 1 1 310 0 0 12 12 1 1 0 0")
            session.send("START")
            waitFor("Pad registered") { InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
            session.send("CONFIG 8 1 310 0 0 12 12 1 1 0 0")
            waitFor("Malformed flags rejected") { session.lines.any { it.startsWith("ERROR ") } }
            waitFor("Error releases virtual pad") { InputDevice.getDeviceIds().none { InputDevice.getDevice(it)?.name == "GyroBridge Controller" } }
        }
    }
}
