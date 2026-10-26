package dev.gyrobridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Only state used by the UI; no sensor internals, raw keys or diagnostic counters. */
data class BridgeStatus(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val running: Boolean = false,
    val held: Boolean = false,
    val binding: Boolean = false,
    val calibrating: Boolean = false,
    val error: String? = null,
    val rate: Int = 0,
    val outX: Float = 0f,
    val outY: Float = 0f,
    val physicalX: Float = 0f,
    val physicalY: Float = 0f,
)

object ServiceState {
    private val mutableStatus = MutableStateFlow(BridgeStatus())
    val status = mutableStatus.asStateFlow()
    fun update(change: (BridgeStatus) -> BridgeStatus) = mutableStatus.update(change)
}
