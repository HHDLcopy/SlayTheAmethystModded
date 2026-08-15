package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudSyncProcessServiceTest {
    @Test
    fun replacementStartIsRejected_whileCancelledWorkerIsUnwinding() {
        assertTrue(
            SteamCloudSyncProcessService.shouldRejectReplacementStart(
                isRunning = true,
                cancellationPending = true,
            )
        )
    }

    @Test
    fun replacementStartIsAllowed_whenNoCancellationIsPending() {
        assertFalse(
            SteamCloudSyncProcessService.shouldRejectReplacementStart(
                isRunning = true,
                cancellationPending = false,
            )
        )
    }
}
