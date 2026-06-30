package io.stamethyst.backend.launch

internal class ExpectedGameExitReturnPolicy(
    private val returnGraceMs: Long = DEFAULT_RETURN_GRACE_MS
) {
    enum class Decision {
        ContinuePolling,
        StopPolling,
        ReturnToLauncher
    }

    private var firstMarkerSeenElapsedMs = -1L
    private var returnTriggered = false

    fun reset() {
        firstMarkerSeenElapsedMs = -1L
        returnTriggered = false
    }

    fun evaluate(
        nowElapsedMs: Long,
        expectedExitMarkerRecent: Boolean,
        active: Boolean
    ): Decision {
        if (returnTriggered) {
            return Decision.StopPolling
        }
        if (!active) {
            firstMarkerSeenElapsedMs = -1L
            return Decision.StopPolling
        }
        if (!expectedExitMarkerRecent) {
            firstMarkerSeenElapsedMs = -1L
            return Decision.ContinuePolling
        }
        if (firstMarkerSeenElapsedMs < 0L) {
            firstMarkerSeenElapsedMs = nowElapsedMs
            return Decision.ContinuePolling
        }
        if (nowElapsedMs - firstMarkerSeenElapsedMs < returnGraceMs) {
            return Decision.ContinuePolling
        }
        returnTriggered = true
        return Decision.ReturnToLauncher
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 100L
        const val DEFAULT_RETURN_GRACE_MS = 200L
    }
}
