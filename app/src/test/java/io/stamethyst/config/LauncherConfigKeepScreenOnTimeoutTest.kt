package io.stamethyst.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherConfigKeepScreenOnTimeoutTest {
    @Test
    fun keepScreenOnTimeoutOptions_keepCurrentBehaviorByDefault() {
        assertEquals(
            LauncherConfig.KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES,
            LauncherConfig.DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES
        )
        assertArrayEquals(
            intArrayOf(0, 5, 10, 30, 60),
            LauncherConfig.KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS
        )
    }

    @Test
    fun normalizeKeepScreenOnTimeout_acceptsSupportedValues() {
        assertEquals(0, LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(0))
        assertEquals(5, LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(5))
        assertEquals(10, LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(10))
        assertEquals(30, LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(30))
        assertEquals(60, LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(60))
    }

    @Test
    fun normalizeKeepScreenOnTimeout_fallsBackToDefaultForUnsupportedValues() {
        assertEquals(
            LauncherConfig.DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES,
            LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(-1)
        )
        assertEquals(
            LauncherConfig.DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES,
            LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(15)
        )
    }

    @Test
    fun keepScreenOnTimeoutMs_treatsAlwaysAsNoDeadline() {
        assertNull(LauncherConfig.keepScreenOnTimeoutMs(0))
        assertEquals(5 * 60_000L, LauncherConfig.keepScreenOnTimeoutMs(5))
        assertNull(LauncherConfig.keepScreenOnTimeoutMs(15))
    }
}
