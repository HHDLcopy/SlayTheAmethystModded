package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Test

class EasyTierErrorClassifierTest {
    @Test
    fun classifyFromSummary_recognizesRuntimeBridgePending() {
        assertEquals(
            EasyTierFailureCategory.RuntimeBridgePending,
            EasyTierErrorClassifier.classifyFromSummary(
                "EasyTier runtime bridge is not wired into this build yet."
            )
        )
    }

    @Test
    fun classifyFromSummary_recognizesRuntimeBridgeUnavailable() {
        assertEquals(
            EasyTierFailureCategory.RuntimeBridgeUnavailable,
            EasyTierErrorClassifier.classifyFromSummary(
                "EasyTier native runtime failed to load. dlopen failed: cannot locate symbol"
            )
        )
    }

    @Test
    fun classifyFromSummary_recognizesConfigMissing() {
        assertEquals(
            EasyTierFailureCategory.ConfigMissing,
            EasyTierErrorClassifier.classifyFromSummary("EasyTier 云控配置暂不可用。")
        )
    }

    @Test
    fun classifyFromSummary_recognizesVpnPermissionDenied() {
        assertEquals(
            EasyTierFailureCategory.VpnPermissionDenied,
            EasyTierErrorClassifier.classifyFromSummary(
                "VPN permission was not granted. EasyTier cannot connect without it."
            )
        )
    }

    @Test
    fun classifyFromSummary_recognizesClosedSessionReasons() {
        assertEquals(
            EasyTierFailureCategory.RoomClosed,
            EasyTierErrorClassifier.classifyFromSummary("Room was closed by the owner.")
        )
        assertEquals(
            EasyTierFailureCategory.SessionExpired,
            EasyTierErrorClassifier.classifyFromSummary("Session expired on the server.")
        )
        assertEquals(
            EasyTierFailureCategory.SessionClosed,
            EasyTierErrorClassifier.classifyFromSummary("Session was stopped by the server.")
        )
    }

    @Test
    fun classify_usesSnapshotCategoryWhenAlreadyStructured() {
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.FAILED,
            mode = EasyTierNetworkMode.Room,
            failureCategory = EasyTierFailureCategory.BackgroundStartBlocked,
            lastErrorSummary = "Android blocked EasyTier background startup.",
        )

        assertEquals(
            EasyTierFailureCategory.BackgroundStartBlocked,
            EasyTierErrorClassifier.classify(snapshot)
        )
    }

    @Test
    fun classify_permissionRequiredFallsBackToPermissionCategory() {
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.PERMISSION_REQUIRED,
            mode = EasyTierNetworkMode.Room,
            lastErrorSummary = "",
        )

        assertEquals(
            EasyTierFailureCategory.VpnPermissionRequired,
            EasyTierErrorClassifier.classify(snapshot)
        )
    }
}
