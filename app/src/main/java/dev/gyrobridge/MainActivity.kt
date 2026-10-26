package dev.gyrobridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.initialize(this)
        setContent { GyroScreen(::action, ::save) }
    }
    private fun action(action: String) {
        if (action == GyroService.STOP) {
            stopService(Intent(this, GyroService::class.java))
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, GyroService::class.java).setAction(action))
    }
    private fun save(change: SettingsChange) {
        SettingsStore.update(change)
        if (ServiceState.status.value.connected) startService(Intent(this, GyroService::class.java).setAction(GyroService.UPDATE))
    }
    @android.annotation.SuppressLint("RestrictedApi") // Public platform override; AndroidX's inherited annotation is overbroad here.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Recording is handled in the bridge, so even system-like gamepad buttons can
        // be assigned without this activity navigating away.
        if (ServiceState.status.value.binding && event.device?.vendorId == 0x2020) return true
        return super.dispatchKeyEvent(event)
    }
}

