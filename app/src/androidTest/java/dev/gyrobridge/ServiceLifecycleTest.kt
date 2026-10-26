package dev.gyrobridge

import android.content.Intent
import android.app.Notification
import android.app.NotificationManager
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class ServiceLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("$message: ${ServiceState.status.value}", condition())
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    @Test fun independentSettingsUpdatesDoNotOverwriteEachOther() {
        SettingsStore.initialize(context)
        val original = SettingsStore.settings.value
        try {
            val start = CountDownLatch(1)
            val ui = thread {
                start.await()
                repeat(20) { SettingsStore.update { it.copy(mouseX = 21f) } }
            }
            val calibration = thread {
                start.await()
                repeat(20) { SettingsStore.update { it.copy(biasX = .012f) } }
            }
            start.countDown()
            ui.join()
            calibration.join()
            assertEquals(21f, SettingsStore.settings.value.mouseX, .00001f)
            assertEquals(.012f, SettingsStore.settings.value.biasX, .00001f)
            assertEquals(original.activationCode, SettingsStore.settings.value.activationCode)
            assertEquals(original.boot, SettingsStore.settings.value.boot)
        } finally {
            SettingsStore.update { original }
        }
    }

    @Test fun serviceCanStopWhileConnectingAndRestartAcrossScreenSleep() {
        context.stopService(Intent(context, GyroService::class.java))
        Thread.sleep(300)
        SettingsStore.initialize(context)
        val original = SettingsStore.settings.value
        val activity = instrumentation.startActivitySync(Intent(context, InputProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("Test activity focused") { activity.hasWindowFocus() }
            repeat(3) {
                context.startForegroundService(Intent(context, GyroService::class.java).setAction(GyroService.START))
                waitFor("Service starting") { ServiceState.status.value.connecting || ServiceState.status.value.connected }
                context.stopService(Intent(context, GyroService::class.java))
                waitFor("Stopped during connection") { !ServiceState.status.value.connecting && !ServiceState.status.value.connected }
            }
            context.startForegroundService(Intent(context, GyroService::class.java).setAction(GyroService.START))
            waitFor("Service running with sensor samples") { ServiceState.status.value.running && ServiceState.status.value.rate > 100 }
            SettingsStore.update { it.copy(activationType = 1, activationCode = 310, activationName = "L1") }
            context.startService(Intent(context, GyroService::class.java).setAction(GyroService.UPDATE))
            waitFor("Notification follows activation changes") {
                context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Hold L1 to aim"
                }
            }
            context.startService(Intent(context, GyroService::class.java).setAction(GyroService.CALIBRATE))
            waitFor("Calibration requested") { ServiceState.status.value.calibrating }
            shell("input keyevent KEYCODE_SLEEP")
            waitFor("Screen-off pauses sensors") { !context.getSystemService(PowerManager::class.java).isInteractive && ServiceState.status.value.rate == 0 }
            assertTrue(ServiceState.status.value.calibrating)
            Thread.sleep(300)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            waitFor("Screen awake") { context.getSystemService(PowerManager::class.java).isInteractive }
            assertTrue("Sleep did not finish calibration", ServiceState.status.value.calibrating)
            waitFor("Gyro resumes after wake") { ServiceState.status.value.rate > 100 }
            assertTrue(ServiceState.status.value.running)
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            context.stopService(Intent(context, GyroService::class.java))
            waitFor("Service stopped") { !ServiceState.status.value.connected && !ServiceState.status.value.connecting }
            SettingsStore.update { original }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
