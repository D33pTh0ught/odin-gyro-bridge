package dev.gyrobridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

/** Coordinates the foreground lifecycle, settings, sensors and private input bridge.
 * Session events and commands are serialized on one worker looper. */
class GyroService : Service() {
    private val worker = HandlerThread("gyro-service")
    private lateinit var handler: Handler
    private lateinit var sensor: GyroSensor
    private var session: BridgeSession? = null
    @Volatile private var closing = false
    @Volatile private var desired = false
    private var ready = false
    private var appliedConfiguration: String? = null
    private var bindRequested = false
    private var resumeAfterBind = false
    private var screenAwake = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (closing) return
            if (ready) send("PING")
            handler.postDelayed(this, 500)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = postWork {
            screenAwake = intent?.action == Intent.ACTION_SCREEN_ON
            applySensorState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        SettingsStore.initialize(this)
        worker.start()
        handler = Handler(worker.looper)
        screenAwake = getSystemService(PowerManager::class.java).isInteractive
        sensor = GyroSensor(
            this, handler,
            settings = { SettingsStore.settings.value },
            onSample = { time, x, y -> if (!closing && ready) send("GYRO ${time / 1e9} $x $y") },
            onCalibration = { bias -> postWork {
                    SettingsStore.update { it.copy(biasX = bias.x, biasY = bias.y, biasZ = bias.z) }
                    ServiceState.update { it.copy(calibrating = false) }
                    applySensorState()
                    updateNotification()
            } },
            onRate = { hz -> if (!closing) ServiceState.update { it.copy(rate = hz) } },
        )
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Gyro input service", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Connecting to stock AYN service"))
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, RECEIVER_NOT_EXPORTED)
        ServiceState.update { BridgeStatus(connecting = true) }
        if (!sensor.available) {
            fail("No gyroscope is available on this device.")
            return
        }

        val connection = BridgeSession()
        session = connection
        thread(name = "gyro-bridge") {
            try {
                connection.connect(this) { line -> postWork { onBridgeEvent(BridgeProtocol.parse(line)) } }
            } catch (e: Exception) {
                postWork { fail(e.message ?: "Input bridge failed") }
            }
        }
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == STOP) {
            desired = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == START || action == null) desired = true
        postWork {
            when (action) {
                START, null -> {
                    bindRequested = false
                    ServiceState.update { it.copy(binding = false) }
                    if (ready) {
                        applySettings()
                        send("START")
                        updateNotification()
                    }
                }
                BIND -> {
                    resumeAfterBind = desired
                    bindRequested = true
                    ServiceState.update { it.copy(binding = true) }
                    if (ready) send("BIND")
                    updateNotification()
                }
                CANCEL_BIND -> {
                    bindRequested = false
                    ServiceState.update { it.copy(binding = false) }
                    if (ready) {
                        send("CANCEL")
                        if (resumeAfterBind) send("START")
                    }
                    updateNotification()
                }
                UPDATE -> if (ready) {
                    applySettings()
                    updateNotification()
                }
                CALIBRATE -> {
                    sensor.calibrate()
                    ServiceState.update { it.copy(calibrating = true) }
                    applySensorState()
                    updateNotification()
                }
            }
        }
        return if (desired) START_STICKY else START_NOT_STICKY
    }

    private fun onBridgeEvent(event: BridgeProtocol.Event?) {
        when (event) {
            BridgeProtocol.Event.Ready -> {
                ready = true
                ServiceState.update { it.copy(connecting = false, connected = true, error = null) }
                applySettings()
                applySensorState()
                if (bindRequested) send("BIND") else if (desired) send("START")
                updateNotification()
            }
            is BridgeProtocol.Event.Failure -> fail(event.message)
            is BridgeProtocol.Event.Bound -> {
                SettingsStore.update { it.copy(
                    activationType = event.type,
                    activationCode = event.code,
                    activationName = ControllerButtons.nameOf(event.type, event.code),
                ) }
                bindRequested = false
                ServiceState.update { it.copy(binding = false) }
                applySettings()
                if (resumeAfterBind) send("START")
                updateNotification()
            }
            is BridgeProtocol.Event.State -> ServiceState.update { it.copy(
                running = event.running,
                held = event.held,
                outX = event.outputX,
                outY = event.outputY,
                physicalX = event.physicalX,
                physicalY = event.physicalY,
            ) }
            null -> Unit
        }
    }

    private fun applySensorState() {
        if (closing) return
        if (screenAwake) sensor.resume() else sensor.pause()
        if (ready) send("SUSPEND ${if (!screenAwake || sensor.calibrating) 1 else 0}")
    }

    private fun applySettings() {
        val configuration = BridgeProtocol.configuration(SettingsStore.settings.value)
        if (configuration != appliedConfiguration) {
            send(configuration)
            appliedConfiguration = configuration
        }
    }

    private fun postWork(block: () -> Unit) {
        handler.post {
            if (!closing) {
                try { block() } catch (e: Exception) { fail(e.message ?: "Gyro service failed") }
            }
        }
    }

    private fun send(command: String) {
        if (closing) return
        try {
            session?.send(command)
        } catch (e: Exception) {
            fail("Bridge connection lost: ${e.message}")
        }
    }

    private fun fail(message: String) {
        if (closing) return
        closing = true
        session?.close() // Release input immediately, before Android destroys the service.
        Log.e("GyroBridge", message)
        ServiceState.update { BridgeStatus(error = message) }
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GyroService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GyroBridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification() {
        if (closing) return
        val button = SettingsStore.settings.value.activationName
        val text = when {
            bindRequested -> "Press a controller button to assign"
            sensor.calibrating -> "Calibrating • keep the device still"
            !desired -> "Output paused • $button selected"
            else -> "Hold $button to aim"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        closing = true
        session?.close()
        session = null
        handler.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(screenReceiver) }
        handler.post {
            try { sensor.close() } finally { worker.quitSafely() }
        }
        ServiceState.update { BridgeStatus(error = it.error) }
        super.onDestroy()
    }

    companion object {
        const val START = "dev.gyrobridge.START"
        const val STOP = "dev.gyrobridge.STOP"
        const val BIND = "dev.gyrobridge.BIND"
        const val CANCEL_BIND = "dev.gyrobridge.CANCEL_BIND"
        const val UPDATE = "dev.gyrobridge.UPDATE"
        const val CALIBRATE = "dev.gyrobridge.CALIBRATE"
        private const val CHANNEL = "gyro"
        private const val NOTIFICATION_ID = 1
    }
}
