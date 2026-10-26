package dev.gyrobridge

import org.junit.Assert.*
import org.junit.Test

class BridgeProtocolTest {
    @Test fun configurationContainsInputSettingsOnly() {
        val settings = GyroSettings(stick = true, activationCode = 309, consume = true, invertY = true)
        assertEquals("CONFIG 1 1 309 1 0.25 12.0 12.0 1.0 1.0 0 1", BridgeProtocol.configuration(settings))
        assertEquals(BridgeProtocol.configuration(settings), BridgeProtocol.configuration(settings.copy(boot = true, biasX = .01f)))
    }
    @Test fun parseNativeState() {
        val event = BridgeProtocol.parse("STATE 1 0 0.5 -0.2 0.25 0.1") as BridgeProtocol.Event.State
        assertTrue(event.running)
        assertFalse(event.held)
        assertEquals(.5f, event.outputX, .00001f)
        assertEquals(.25f, event.physicalX, .00001f)
    }

    @Test fun parseGripAndAnalogTriggerAssignments() {
        assertEquals(BridgeProtocol.Event.Bound(1, 309), BridgeProtocol.parse("BOUND 1 309"))
        assertEquals(BridgeProtocol.Event.Bound(3, 10), BridgeProtocol.parse("BOUND 3 10"))
    }

    @Test fun rejectMalformedResponsesBeforeChangingServiceState() {
        listOf("STATE 1", "STATE 2 0 0 0 0 0", "STATE 1 0 NaN 0 0 0", "BOUND 3 99", "BOUND 1 -1").forEach { line ->
            assertTrue("Accepted $line", runCatching { BridgeProtocol.parse(line) }.isFailure)
        }
    }
}
