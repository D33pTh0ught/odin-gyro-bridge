package dev.gyrobridge

typealias SettingsChange = (GyroSettings) -> GyroSettings

/** Persisted aiming preferences. Smoothing remains normalized for backwards compatibility. */
data class GyroSettings(
    val stick: Boolean = false,
    val activationType: Int = 1,
    val activationCode: Int = 310,
    val activationName: String = "L1",
    val consume: Boolean = false,
    val smoothing: Float = .25f,
    val mouseX: Float = 12f,
    val mouseY: Float = 12f,
    val stickX: Float = 1f,
    val stickY: Float = 1f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val boot: Boolean = false,
    val biasX: Float = 0f,
    val biasY: Float = 0f,
    val biasZ: Float = 0f,
) {
    val smoothingMillis: Float get() = smoothing * 40f

}

object ControllerButtons {
    fun nameOf(type: Int, code: Int): String {
        if (type == 3) return if (code == 10) "L2 (analog)" else "R2 (analog)"
        return when (code) {
            // Confirmed on Portal firmware 1.0.0.338: M1 = Z, M2 = C.
            304 -> "A"
            305 -> "B"
            306 -> "M2 (grip / C)"
            307 -> "X"
            308 -> "Y"
            309 -> "M1 (grip / Z)"
            310 -> "L1"
            311 -> "R1"
            312 -> "L2"
            313 -> "R2"
            314 -> "Select"
            315 -> "Start"
            316 -> "Mode"
            317 -> "L3"
            318 -> "R3"
            544 -> "D-pad up"
            545 -> "D-pad down"
            546 -> "D-pad left"
            547 -> "D-pad right"
            102 -> "Home"
            158 -> "Back"
            580 -> "App switch"
            else -> "Button $code"
        }
    }
}
