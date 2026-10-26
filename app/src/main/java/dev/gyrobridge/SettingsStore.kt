package dev.gyrobridge

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The sole owner of persisted settings. Field updates use the latest value so
 * calibration and UI edits cannot overwrite each other's unrelated changes. */
object SettingsStore {
    private val mutableSettings = MutableStateFlow(GyroSettings())
    val settings = mutableSettings.asStateFlow()
    private var preferences: SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (preferences != null) return
        val p = context.applicationContext.getSharedPreferences("gyro", Context.MODE_PRIVATE)
        val defaults = GyroSettings()
        mutableSettings.value = GyroSettings(
            stick = p.getBoolean("stick", defaults.stick),
            activationType = p.getInt("type", defaults.activationType),
            activationCode = p.getInt("code", defaults.activationCode),
            activationName = p.getString("name", defaults.activationName) ?: defaults.activationName,
            consume = p.getBoolean("consume", defaults.consume),
            smoothing = p.getFloat("smooth", defaults.smoothing),
            mouseX = p.getFloat("mx", defaults.mouseX),
            mouseY = p.getFloat("my", defaults.mouseY),
            stickX = p.getFloat("sx", defaults.stickX),
            stickY = p.getFloat("sy", defaults.stickY),
            invertX = p.getBoolean("ix", defaults.invertX),
            invertY = p.getBoolean("iy", defaults.invertY),
            boot = p.getBoolean("boot", defaults.boot),
            biasX = p.getFloat("bx", defaults.biasX),
            biasY = p.getFloat("by", defaults.biasY),
            biasZ = p.getFloat("bz", defaults.biasZ),
        )
        preferences = p
    }

    @Synchronized fun update(change: SettingsChange) {
        val value = change(mutableSettings.value)
        checkNotNull(preferences) { "SettingsStore must be initialized first" }.edit()
            .putBoolean("stick", value.stick)
            .putInt("type", value.activationType)
            .putInt("code", value.activationCode)
            .putString("name", value.activationName)
            .putBoolean("consume", value.consume)
            .putFloat("smooth", value.smoothing)
            .putFloat("mx", value.mouseX)
            .putFloat("my", value.mouseY)
            .putFloat("sx", value.stickX)
            .putFloat("sy", value.stickY)
            .putBoolean("ix", value.invertX)
            .putBoolean("iy", value.invertY)
            .putBoolean("boot", value.boot)
            .putFloat("bx", value.biasX)
            .putFloat("by", value.biasY)
            .putFloat("bz", value.biasZ)
            .apply()
        mutableSettings.value = value
    }
}
