package io.stamethyst.backend.easytier

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierDiagnosticsStoreTest {
    @Test
    fun recordStateTransition_writesSummaryAndFailureHistory() {
        val roots = EasyTierTestRoots.create("easytier-diagnostics-store")
        try {
            val snapshot = EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.FAILED,
                mode = EasyTierNetworkMode.Room,
                sessionId = "sess-alpha",
                roomId = "room-alpha",
                entryNodeUrl = "tcp://online.example.com:11010",
                configServerUrl = "udp://online.example.com:22020",
                aclGroup = "player",
                expiresAtEpochSeconds = 1_720_000_000L,
                startedAtMs = 1_000L,
                lastUpdatedAtMs = 2_000L,
                lastErrorSummary = "runtime bridge missing",
                assignedIpv4Cidr = "10.144.144.10/24",
                peerCount = 2,
                relayServerDescription = "online.example.com:11010",
                lastSessionState = "stopped",
                lastRoomState = "closed",
                userInitiated = true,
            )

            EasyTierDiagnosticsStore.recordStateTransition(
                context = roots.context,
                snapshot = snapshot,
                extraLines = listOf("runtime_bridge_integrated=false"),
            )

            val summaryText = EasyTierDiagnosticsStore.summaryFile(roots.context)
                .readText(StandardCharsets.UTF_8)
            assertTrue(summaryText.contains("Status: FAILED"))
            assertTrue(summaryText.contains("Failure Category: None"))
            assertTrue(summaryText.contains("Session ID: sess-alpha"))
            assertTrue(summaryText.contains("Room ID: room-alpha"))
            assertTrue(summaryText.contains("Config Server: udp://online.example.com:22020"))
            assertTrue(summaryText.contains("ACL Group: player"))
            assertTrue(summaryText.contains("Last Session State: stopped"))
            assertTrue(summaryText.contains("Last Room State: closed"))
            assertTrue(summaryText.contains("Failure Summary: runtime bridge missing"))
            assertTrue(summaryText.contains("runtime_bridge_integrated=false"))

            val historyFiles = EasyTierDiagnosticsStore.eventHistoryDir(roots.context)
                .listFiles()
                ?.toList()
                .orEmpty()
            assertEquals(1, historyFiles.size)
            assertTrue(historyFiles.single().name.startsWith("event-failed-"))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }
}
