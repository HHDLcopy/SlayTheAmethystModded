package io.stamethyst

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootOverlayFrameDismissGateTest {
    @Test
    fun shouldNotDismissBeforeRequest() {
        val gate = BootOverlayFrameDismissGate(
            readyDelayMs = 700L,
            requiredPostSignalFrames = 2
        )

        assertFalse(gate.shouldDismissOnFrame(frameTimestampNs = 101L, nowMs = 700L))
    }

    @Test
    fun shouldWaitForDelayAndPostSignalFrames() {
        val gate = BootOverlayFrameDismissGate(
            readyDelayMs = 700L,
            requiredPostSignalFrames = 2
        )

        gate.request(frameTimestampNs = 100L, nowMs = 1_000L)

        assertFalse(gate.shouldDismissOnFrame(frameTimestampNs = 100L, nowMs = 2_000L))
        assertFalse(gate.shouldDismissOnFrame(frameTimestampNs = 101L, nowMs = 1_200L))
        assertFalse(gate.shouldDismissOnFrame(frameTimestampNs = 101L, nowMs = 1_900L))
        assertTrue(gate.shouldDismissOnFrame(frameTimestampNs = 102L, nowMs = 1_900L))
        assertFalse(gate.pending)
    }

    @Test
    fun shouldResetAfterDismiss() {
        val gate = BootOverlayFrameDismissGate(
            readyDelayMs = 0L,
            requiredPostSignalFrames = 1
        )

        gate.request(frameTimestampNs = 50L, nowMs = 10L)

        assertTrue(gate.shouldDismissOnFrame(frameTimestampNs = 51L, nowMs = 10L))
        assertFalse(gate.shouldDismissOnFrame(frameTimestampNs = 52L, nowMs = 10L))
    }
}
