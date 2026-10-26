package dev.gyrobridge

/** The small line protocol shared by the app and its private native helper. */
object BridgeProtocol {
    fun configuration(settings: GyroSettings): String = with(settings) {
        "CONFIG ${if (stick) 1 else 0} $activationType $activationCode ${if (consume) 1 else 0} $smoothing $mouseX $mouseY $stickX $stickY ${if (invertX) 1 else 0} ${if (invertY) 1 else 0}"
    }

    sealed interface Event {
        data object Ready : Event
        data class Bound(val type: Int, val code: Int) : Event
        data class State(
            val running: Boolean,
            val held: Boolean,
            val outputX: Float,
            val outputY: Float,
            val physicalX: Float,
            val physicalY: Float,
        ) : Event
        data class Failure(val message: String) : Event
    }

    fun parse(line: String): Event? {
        if (line.startsWith("READY ")) return Event.Ready
        if (line.startsWith("ERROR ")) return Event.Failure(line.removePrefix("ERROR "))
        val parts = line.split(' ')
        return when (parts.firstOrNull()) {
            "BOUND" -> {
                require(parts.size == 3) { "Invalid button response" }
                val type = parts[1].toInt()
                val code = parts[2].toInt()
                require((type == 1 && code in 1..767) || (type == 3 && code in listOf(9, 10)))
                Event.Bound(type, code)
            }
            "STATE" -> {
                require(parts.size == 7 && parts[1] in listOf("0", "1") && parts[2] in listOf("0", "1")) { "Invalid state response" }
                val values = parts.drop(3).map(String::toFloat)
                require(values.all(Float::isFinite)) { "Invalid input values" }
                Event.State(parts[1] == "1", parts[2] == "1", values[0], values[1], values[2], values[3])
            }
            else -> null
        }
    }
}
