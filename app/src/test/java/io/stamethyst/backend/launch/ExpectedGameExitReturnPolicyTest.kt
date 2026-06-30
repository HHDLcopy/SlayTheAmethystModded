package io.stamethyst.backend.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpectedGameExitReturnPolicyTest {
    @Test
    fun evaluate_keepsPollingUntilExpectedMarkerAppears() {
        val policy = ExpectedGameExitReturnPolicy(returnGraceMs = 100L)

        val decision = policy.evaluate(
            nowElapsedMs = 1000L,
            expectedExitMarkerRecent = false,
            active = true
        )

        assertEquals(ExpectedGameExitReturnPolicy.Decision.ContinuePolling, decision)
    }

    @Test
    fun evaluate_waitsForGraceAfterExpectedMarkerAppears() {
        val policy = ExpectedGameExitReturnPolicy(returnGraceMs = 100L)

        assertEquals(
            ExpectedGameExitReturnPolicy.Decision.ContinuePolling,
            policy.evaluate(1000L, expectedExitMarkerRecent = true, active = true)
        )
        assertEquals(
            ExpectedGameExitReturnPolicy.Decision.ContinuePolling,
            policy.evaluate(1099L, expectedExitMarkerRecent = true, active = true)
        )
    }

    @Test
    fun evaluate_returnsToLauncherWhenMarkerSurvivesGrace() {
        val policy = ExpectedGameExitReturnPolicy(returnGraceMs = 100L)

        policy.evaluate(1000L, expectedExitMarkerRecent = true, active = true)

        assertEquals(
            ExpectedGameExitReturnPolicy.Decision.ReturnToLauncher,
            policy.evaluate(1100L, expectedExitMarkerRecent = true, active = true)
        )
        assertEquals(
            ExpectedGameExitReturnPolicy.Decision.StopPolling,
            policy.evaluate(1200L, expectedExitMarkerRecent = true, active = true)
        )
    }

    @Test
    fun evaluate_resetsGraceWindowWhenMarkerDisappears() {
        val policy = ExpectedGameExitReturnPolicy(returnGraceMs = 100L)

        policy.evaluate(1000L, expectedExitMarkerRecent = true, active = true)
        policy.evaluate(1050L, expectedExitMarkerRecent = false, active = true)

        assertEquals(
            ExpectedGameExitReturnPolicy.Decision.ContinuePolling,
            policy.evaluate(1100L, expectedExitMarkerRecent = true, active = true)
        )
    }

    @Test
    fun evaluate_stopsWhenInactive() {
        val policy = ExpectedGameExitReturnPolicy(returnGraceMs = 100L)

        assertEquals(
            ExpectedGameExitReturnPolicy.Decision.StopPolling,
            policy.evaluate(1000L, expectedExitMarkerRecent = true, active = false)
        )
    }
}
