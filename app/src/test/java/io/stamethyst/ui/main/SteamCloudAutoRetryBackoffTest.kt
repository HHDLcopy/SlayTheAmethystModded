package io.stamethyst.ui.main

import io.stamethyst.backend.steamcloud.SteamCloudFailureCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudAutoRetryBackoffTest {
    @Test
    fun steamCloudAutoRetryDelaySeconds_usesExponentialBackoffCappedAtFiveMinutes() {
        assertEquals(5, steamCloudAutoRetryDelaySeconds(-1))
        assertEquals(5, steamCloudAutoRetryDelaySeconds(0))
        assertEquals(10, steamCloudAutoRetryDelaySeconds(1))
        assertEquals(20, steamCloudAutoRetryDelaySeconds(2))
        assertEquals(40, steamCloudAutoRetryDelaySeconds(3))
        assertEquals(80, steamCloudAutoRetryDelaySeconds(4))
        assertEquals(160, steamCloudAutoRetryDelaySeconds(5))
        assertEquals(300, steamCloudAutoRetryDelaySeconds(6))
        assertEquals(300, steamCloudAutoRetryDelaySeconds(20))
    }

    @Test
    fun shouldAutoRetrySteamCloudFailure_onlyRetriesTransientNetworkThreeTimes() {
        assertTrue(shouldAutoRetrySteamCloudFailure(SteamCloudFailureCategory.TRANSIENT_NETWORK, 0))
        assertTrue(shouldAutoRetrySteamCloudFailure(SteamCloudFailureCategory.TRANSIENT_NETWORK, 2))
        assertFalse(shouldAutoRetrySteamCloudFailure(SteamCloudFailureCategory.TRANSIENT_NETWORK, 3))
        assertFalse(shouldAutoRetrySteamCloudFailure(SteamCloudFailureCategory.AUTH_REJECTED, 0))
        assertFalse(shouldAutoRetrySteamCloudFailure(SteamCloudFailureCategory.RATE_LIMITED, 0))
        assertFalse(shouldAutoRetrySteamCloudFailure(SteamCloudFailureCategory.CANCELLED, 0))
        assertFalse(shouldAutoRetrySteamCloudFailure(null, 0))
    }
}
