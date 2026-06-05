package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudAuthStoreTest {
    @Test
    fun authSnapshot_isIncompleteWhenSteamId64Missing() {
        val snapshot = authSnapshot(steamId64 = "")

        assertFalse(snapshot.isComplete)
    }

    @Test
    fun authSnapshot_isCompleteWhenTokenAndSteamId64ArePresent() {
        val snapshot = authSnapshot(steamId64 = "76561198883607238")

        assertTrue(snapshot.isComplete)
    }

    private fun authSnapshot(steamId64: String): SteamCloudAuthStore.AuthSnapshot =
        SteamCloudAuthStore.AuthSnapshot(
            accountName = "account",
            refreshTokenConfigured = true,
            guardDataConfigured = false,
            steamId64 = steamId64,
            personaName = "",
            avatarUrl = "",
            lastAuthAtMs = null,
            lastManifestAtMs = null,
            lastPullAtMs = null,
            lastPushAtMs = null,
            lastError = "",
        )
}
