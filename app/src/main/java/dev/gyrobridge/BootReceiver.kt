package dev.gyrobridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SettingsStore.initialize(context)
        if (SettingsStore.settings.value.boot) {
            runCatching {
                context.startForegroundService(Intent(context, GyroService::class.java).setAction(GyroService.START))
            }.onFailure {
                android.util.Log.e("GyroBridge", "Unable to start after boot", it)
            }
        }
    }
}
