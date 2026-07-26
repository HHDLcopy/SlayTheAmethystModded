package io.stamethyst.backend.workshop

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopSteamRateLimitStoreTest {
    @Test
    fun remainingCooldownExpiresAtStoredDeadline() {
        assertEquals(30_000L, remainingWorkshopSteamRateLimitMillis(130_000L, 100_000L))
        assertEquals(0L, remainingWorkshopSteamRateLimitMillis(100_000L, 100_000L))
        assertEquals(0L, remainingWorkshopSteamRateLimitMillis(90_000L, 100_000L))
    }

    @Test
    fun cooldownMessageRoundsUpToAWholeMinute() {
        assertEquals(
            "Steam 请求过于频繁，已暂停下载，请在约 1 分钟后重试",
            formatWorkshopSteamRateLimitCooldownMessage(1L),
        )
        assertEquals(
            "Steam 请求过于频繁，已暂停下载，请在约 2 分钟后重试",
            formatWorkshopSteamRateLimitCooldownMessage(60_001L),
        )
    }
}
