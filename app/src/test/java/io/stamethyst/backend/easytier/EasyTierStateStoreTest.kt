package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EasyTierStateStoreTest {
    @Test
    fun writeSnapshot_roundTripsSerializedState() {
        val roots = EasyTierTestRoots.create("easytier-state-store-round-trip")
        try {
            val snapshot = EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.CONNECTED,
                mode = EasyTierNetworkMode.Room,
                sessionId = "sess-42",
                roomId = "room-42",
                entryNodeUrl = "tcp://online.example.com:11010",
                configServerUrl = "udp://online.example.com:22020",
                aclGroup = "player",
                expiresAtEpochSeconds = 1_800_000_000L,
                startedAtMs = 1_000L,
                connectedAtMs = 2_000L,
                lastUpdatedAtMs = 3_000L,
                lastErrorSummary = "",
                diagnosticsSummaryPath = "summary.txt",
                assignedIpv4Cidr = "10.144.144.10/24",
                currentPlayerId = "player-42",
                roomOwnerPlayerId = "player-7",
                roomOwnerIpv4Cidr = "10.144.144.7/24",
                peerCount = 3,
                relayServerDescription = "online.example.com:11010",
                lastSessionState = "connected",
                lastRoomState = "active",
                userInitiated = true,
            )

            EasyTierStateStore.writeSnapshot(roots.context, snapshot)

            val restored = EasyTierStateStore.readSnapshot(roots.context)
            assertNotNull(restored)
            assertEquals(snapshot, restored)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun clear_removesPersistedSnapshot() {
        val roots = EasyTierTestRoots.create("easytier-state-store-clear")
        try {
            EasyTierStateStore.writeSnapshot(
                roots.context,
                EasyTierConnectionSnapshot(
                    enabled = true,
                    canConnect = true,
                    status = EasyTierConnectionStatus.DISCONNECTED,
                    mode = EasyTierNetworkMode.Room,
                    lastUpdatedAtMs = 5_000L,
                )
            )

            EasyTierStateStore.clear(roots.context)

            assertNull(EasyTierStateStore.readSnapshot(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }
}
