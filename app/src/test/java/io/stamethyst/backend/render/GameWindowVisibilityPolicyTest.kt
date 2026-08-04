package io.stamethyst.backend.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameWindowVisibilityPolicyTest {
    @Test
    fun resolveRuntimeVisible_resumedActivityIsVisible() {
        assertTrue(
            GameWindowVisibilityPolicy.resolveRuntimeVisible(
                activityStopped = false,
                activityResumed = true,
                inMultiWindowMode = false
            )
        )
    }

    @Test
    fun resolveRuntimeVisible_pausedFullscreenIsHidden() {
        assertFalse(
            GameWindowVisibilityPolicy.resolveRuntimeVisible(
                activityStopped = false,
                activityResumed = false,
                inMultiWindowMode = false
            )
        )
    }

    @Test
    fun resolveRuntimeVisible_pausedMultiWindowStaysVisible() {
        // Tapping the app beside a docked game pauses it without hiding it; the runtime must keep
        // rendering instead of freezing the visible window.
        assertTrue(
            GameWindowVisibilityPolicy.resolveRuntimeVisible(
                activityStopped = false,
                activityResumed = false,
                inMultiWindowMode = true
            )
        )
    }

    @Test
    fun resolveRuntimeVisible_stoppedMultiWindowIsHidden() {
        assertFalse(
            GameWindowVisibilityPolicy.resolveRuntimeVisible(
                activityStopped = true,
                activityResumed = false,
                inMultiWindowMode = true
            )
        )
    }

    @Test
    fun resolveRuntimeVisible_stoppedWinsOverStaleResumedFlag() {
        assertFalse(
            GameWindowVisibilityPolicy.resolveRuntimeVisible(
                activityStopped = true,
                activityResumed = true,
                inMultiWindowMode = false
            )
        )
    }
}
