package io.stamethyst.backend.easytier

import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierProcessServiceNotificationTest {
    @Test
    fun easyTierNotificationMessageResIdForStatus_mapsForegroundStatuses() {
        assertEquals(
            R.string.main_easytier_notification_permission_required,
            easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.PERMISSION_REQUIRED)
        )
        assertEquals(
            R.string.main_easytier_notification_connecting,
            easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.CONNECTING)
        )
        assertEquals(
            R.string.main_easytier_notification_runtime_starting,
            easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.SESSION_READY)
        )
        assertEquals(
            R.string.main_easytier_notification_connected,
            easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.CONNECTED)
        )
        assertEquals(
            R.string.main_easytier_notification_reconnecting,
            easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.RECONNECTING)
        )
    }

    @Test
    fun easyTierNotificationMessage_usesDynamicFailureSummaryWhenPresent() {
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.FAILED,
            mode = EasyTierNetworkMode.Room,
            lastErrorSummary = "session_status_poll_failed=true",
        )

        assertEquals(
            "session_status_poll_failed=true",
            easyTierNotificationMessage(
                snapshot = snapshot,
                resolveString = { "unused:$it" },
                unknownErrorMessage = "unknown-error",
            )
        )
    }

    @Test
    fun easyTierNotificationMessage_usesFallbackWhenFailureSummaryIsBlank() {
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.FAILED,
            mode = EasyTierNetworkMode.Room,
        )

        assertEquals(
            "unknown-error",
            easyTierNotificationMessage(
                snapshot = snapshot,
                resolveString = { "unused:$it" },
                unknownErrorMessage = "unknown-error",
            )
        )
    }

    @Test
    fun easyTierNotificationMessage_usesDisconnectSummaryForDisconnectedState() {
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.DISCONNECTED,
            mode = EasyTierNetworkMode.Room,
            lastErrorSummary = "Room was closed by the owner.",
        )

        assertEquals(
            "Room was closed by the owner.",
            easyTierNotificationMessage(
                snapshot = snapshot,
                resolveString = { "unused:$it" },
                unknownErrorMessage = "unknown-error",
            )
        )
    }

    @Test
    fun easyTierNotificationMessageResIdForStatus_returnsNullForSilentStatuses() {
        assertNull(easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.IDLE))
        assertNull(easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.DISCONNECTING))
        assertNull(easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.DISCONNECTED))
        assertNull(easyTierNotificationMessageResIdForStatus(EasyTierConnectionStatus.FAILED))
    }

    @Test
    fun isTerminalSessionState_exitsWithoutExposingTheTerminationReason() {
        assertEquals(true, isTerminalSessionState("issued", "closed"))
        assertEquals(true, isTerminalSessionState("expired", "idle"))
        assertEquals(true, isTerminalSessionState("stopped", "idle"))
        assertEquals(true, isTerminalSessionState("superseded", "idle"))
        assertEquals(true, isTerminalSessionState("kicked", "active"))
        assertEquals(false, isTerminalSessionState("issued", "active"))
    }

    @Test
    fun applyEasyTierRoomInfo_capturesOwnerIdentityAndIp() {
        val snapshot = applyEasyTierRoomInfo(
            snapshot = EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.SESSION_READY,
                mode = EasyTierNetworkMode.Room,
                currentPlayerId = "member-1",
            ),
            roomInfo = EasyTierRoomInfo(
                roomId = "room-1",
                ownerPlayerId = "owner-1",
                ownerDisplayName = "Owner",
                mode = EasyTierNetworkMode.Room,
                allowNewJoins = true,
                memberCount = 2,
                members = listOf(
                    EasyTierRoomMember(
                        playerId = "owner-1",
                        displayName = "Owner",
                        role = "owner",
                        online = true,
                        assignedIpv4Cidr = "10.144.0.1/24",
                    ),
                    EasyTierRoomMember(
                        playerId = "member-1",
                        displayName = "Member",
                        role = "member",
                        online = true,
                        assignedIpv4Cidr = "10.144.0.2/24",
                    ),
                ),
            ),
        )

        assertEquals("owner-1", snapshot.roomOwnerPlayerId)
        assertEquals("10.144.0.1/24", snapshot.roomOwnerIpv4Cidr)
    }

    @Test
    fun applyLocalEasyTierOwnerIpv4_prefersLocalRuntimeIpForOwner() {
        val snapshot = applyLocalEasyTierOwnerIpv4(
            snapshot = EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.CONNECTED,
                mode = EasyTierNetworkMode.Room,
                currentPlayerId = "owner-1",
                roomOwnerPlayerId = "owner-1",
            ),
            assignedIpv4Cidr = "10.144.0.1/24",
        )

        assertEquals("10.144.0.1/24", snapshot.roomOwnerIpv4Cidr)
    }

    @Test
    fun selectRuntimePollBaseSnapshot_preservesConcurrentVpnConnectedState() {
        val polled = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.SESSION_READY,
            mode = EasyTierNetworkMode.Room,
            sessionId = "sess-1",
        )
        val connected = polled.copy(
            status = EasyTierConnectionStatus.CONNECTED,
            connectedAtMs = 42L,
            assignedIpv4Cidr = "10.144.0.1/24",
        )

        assertEquals(connected, selectRuntimePollBaseSnapshot(polled, connected))
        assertEquals(
            polled,
            selectRuntimePollBaseSnapshot(polled, connected.copy(sessionId = "sess-2")),
        )
    }

    @Test
    fun resolveEasyTierAssignedIpv4Cidr_preservesRuntimeIpWhenServerOmitsIt() {
        assertEquals(
            "10.126.126.1/24",
            resolveEasyTierAssignedIpv4Cidr(
                currentValue = "10.126.126.1/24",
                reportedValue = "",
            ),
        )
        assertEquals(
            "10.126.126.2/24",
            resolveEasyTierAssignedIpv4Cidr(
                currentValue = "10.126.126.1/24",
                reportedValue = " 10.126.126.2/24 ",
            ),
        )
    }

    @Test
    fun shouldReportEasyTierRuntime_usesEveryRuntimePollAsALeaseHeartbeat() {
        val baseSnapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.CONNECTED,
            mode = EasyTierNetworkMode.Room,
            sessionId = "sess-1",
            assignedIpv4Cidr = "10.144.0.1/24",
            lastSessionState = "connected",
            relayServerDescription = "relay-a",
        )

        assertEquals(false, shouldReportEasyTierRuntime(baseSnapshot, ""))
        assertEquals(true, shouldReportEasyTierRuntime(baseSnapshot, "10.144.0.1/24"))
        assertEquals(true, shouldReportEasyTierRuntime(baseSnapshot, "10.144.0.2/24"))
        assertEquals(false, shouldReportEasyTierRuntime(baseSnapshot.copy(sessionId = ""), "10.144.0.1/24"))
    }

    @Test
    fun hasEasyTierConnectionTimedOut_releasesFailedConnectionWithoutAnIp() {
        val config = EasyTierResolvedConfig(
            enabled = true,
            defaultMode = EasyTierNetworkMode.Room,
            roomApiBaseUrl = "https://online.example.com",
            webConsoleApiBaseUrl = "",
            configServerUrl = "",
            entryNodeUrl = "tcp://online.example.com:11010",
            connectTimeoutSeconds = 12,
            statusPollIntervalSeconds = 5,
            allowSharedCommunityNetwork = false,
        )
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.FAILED,
            mode = EasyTierNetworkMode.Room,
            startedAtMs = 1_000L,
        )

        assertEquals(true, hasEasyTierConnectionTimedOut(snapshot, config, nowMs = 13_000L))
        assertEquals(
            true,
            hasEasyTierConnectionTimedOut(
                snapshot.copy(assignedIpv4Cidr = "10.144.0.2/24"),
                config,
                nowMs = 13_000L,
            ),
        )
        assertEquals(
            true,
            hasEasyTierConnectionTimedOut(
                snapshot.copy(
                    status = EasyTierConnectionStatus.SESSION_READY,
                    assignedIpv4Cidr = "10.126.42.17/24",
                ),
                config,
                nowMs = 13_000L,
            ),
        )
        assertEquals(
            false,
            hasEasyTierConnectionTimedOut(
                snapshot.copy(
                    status = EasyTierConnectionStatus.CONNECTED,
                    assignedIpv4Cidr = "10.126.42.17/24",
                ),
                config,
                nowMs = 13_000L,
            ),
        )
    }

    @Test
    fun shouldClearEasyTierSessionCredential_preservesCredentialUntilServerReleaseIsConfirmed() {
        assertTrue(shouldClearEasyTierSessionCredential(stopSucceeded = true))
        assertTrue(
            shouldClearEasyTierSessionCredential(
                stopSucceeded = false,
                stopFailureStatusCode = 404,
            )
        )
        assertFalse(
            shouldClearEasyTierSessionCredential(
                stopSucceeded = false,
                stopFailureStatusCode = 503,
            )
        )
        assertFalse(shouldClearEasyTierSessionCredential(stopSucceeded = false))
    }
}
