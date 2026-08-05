package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lease-renewal and error-classification rules that keep an EasyTier session alive.
 *
 * The failure these guard against: the Room API only renews a session lease when the client posts
 * a runtime report, the client only posted one on the fully healthy path, and any 404 was read as
 * "this server has no runtime endpoint". A brief runtime stall therefore stopped renewal, the
 * server expired the session after its TTL, and every subsequent request returned 404 — an
 * unrecoverable disconnect from a recoverable hiccup.
 */
class EasyTierSessionLeaseTest {

    @Test
    fun sessionGone_whenServerReportsSessionNotFound() {
        assertTrue(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(
                    statusCode = 404,
                    message = "LAN session not found",
                    errorCode = EasyTierRoomApiHttpException.ERROR_CODE_SESSION_NOT_FOUND,
                )
            )
        )
    }

    @Test
    fun sessionGone_whenServerReportsRoomNotFound() {
        // The room being deleted takes the session with it, so this is equally terminal.
        assertTrue(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(
                    statusCode = 404,
                    message = "LAN room not found",
                    errorCode = "lan_room_not_found",
                )
            )
        )
    }

    @Test
    fun sessionGone_whenLegacyServerSendsUnlabelled404() {
        // Servers predating the error codes cannot say more than the status, so an unlabelled 404
        // from the status endpoint is still honoured as terminal for backwards compatibility.
        assertTrue(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(statusCode = 404, message = "Not Found")
            )
        )
    }

    @Test
    fun sessionNotGone_forNonNotFoundStatuses() {
        // A conflict means the session exists but is not accepting this report; a 5xx is a server
        // fault. Neither should end the session.
        assertFalse(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(
                    statusCode = 409,
                    message = "LAN session is no longer active",
                )
            )
        )
        assertFalse(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(statusCode = 500, message = "Boom")
            )
        )
        assertFalse(
            isEasyTierSessionGone(
                EasyTierRoomApiHttpException(statusCode = 502, message = "Bad Gateway")
            )
        )
    }

    @Test
    fun sessionMissing_isDistinctFromUnimplementedEndpoint() {
        val missingSession = EasyTierRoomApiHttpException(
            statusCode = 404,
            message = "LAN session not found",
            errorCode = EasyTierRoomApiHttpException.ERROR_CODE_SESSION_NOT_FOUND,
        )
        // The whole point of the error code: this must not disable lease renewal.
        assertTrue(missingSession.isSessionMissing)
        assertFalse(missingSession.isPossiblyUnimplementedEndpoint)

        val unlabelled404 = EasyTierRoomApiHttpException(statusCode = 404, message = "Not Found")
        assertFalse(unlabelled404.isSessionMissing)
        assertTrue(unlabelled404.isPossiblyUnimplementedEndpoint)
    }

    @Test
    fun unimplementedEndpoint_requiresNotFoundStatus() {
        // Only a 404 can mean "no such route"; other failures must not latch the renewal off.
        assertFalse(
            EasyTierRoomApiHttpException(statusCode = 409, message = "Conflict")
                .isPossiblyUnimplementedEndpoint
        )
        assertFalse(
            EasyTierRoomApiHttpException(statusCode = 500, message = "Boom")
                .isPossiblyUnimplementedEndpoint
        )
    }

    private fun snapshot(sessionId: String) = EasyTierConnectionSnapshot(
        enabled = true,
        canConnect = true,
        status = EasyTierConnectionStatus.CONNECTED,
        mode = EasyTierNetworkMode.Room,
        sessionId = sessionId,
    )

    @Test
    fun runtimeReportGate_requiresSessionIdAndAddress() {
        val withSession = snapshot("lan_abc")
        assertTrue(
            shouldReportEasyTierRuntime(
                snapshot = withSession,
                assignedIpv4Cidr = "10.126.5.184/24",
            )
        )
        // Without an address the server would reject a static-IP session, so there is nothing to
        // send yet.
        assertFalse(
            shouldReportEasyTierRuntime(snapshot = withSession, assignedIpv4Cidr = "")
        )
        assertFalse(
            shouldReportEasyTierRuntime(
                snapshot = snapshot(""),
                assignedIpv4Cidr = "10.126.5.184/24",
            )
        )
    }

    @Test
    fun errorCodeDefaultsToBlank() {
        // Callers must be able to rely on a non-null code, so the default has to be empty rather
        // than absent.
        assertEquals("", EasyTierRoomApiHttpException(statusCode = 404, message = "x").errorCode)
    }
}
