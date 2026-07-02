package io.stamethyst.backend.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopUpdateCheckTimingPolicyTest {
    @Test
    fun checkIsDueWhenNeverChecked() {
        assertTrue(isWorkshopUpdateCheckDue(lastCheckedAtMs = 0L, nowMs = 10_000L, checkIntervalMs = 1_000L))
    }

    @Test
    fun checkIsNotDueWithinInterval() {
        assertFalse(isWorkshopUpdateCheckDue(lastCheckedAtMs = 9_500L, nowMs = 10_000L, checkIntervalMs = 1_000L))
    }

    @Test
    fun dueAppStartCheckUsesStartupDelay() {
        assertEquals(
            60_000L,
            workshopUpdateCheckInitialDelayMs(
                lastCheckedAtMs = 0L,
                nowMs = 10_000L,
                checkIntervalMs = 1_000L,
                appStartDelayMs = 60_000L,
            )
        )
    }

    @Test
    fun notDueAppStartCheckDoesNotAddDelay() {
        assertEquals(
            0L,
            workshopUpdateCheckInitialDelayMs(
                lastCheckedAtMs = 9_500L,
                nowMs = 10_000L,
                checkIntervalMs = 1_000L,
                appStartDelayMs = 60_000L,
            )
        )
    }
}
