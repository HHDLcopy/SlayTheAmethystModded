package io.stamethyst.backend.easytier

object EasyTierErrorClassifier {
    fun classify(
        snapshot: EasyTierConnectionSnapshot,
        resultCode: Int? = null,
    ): EasyTierFailureCategory {
        if (snapshot.failureCategory != EasyTierFailureCategory.None) {
            return snapshot.failureCategory
        }
        if (snapshot.status == EasyTierConnectionStatus.PERMISSION_REQUIRED) {
            return classifyFromSummary(snapshot.lastErrorSummary).takeUnless {
                it == EasyTierFailureCategory.None
            } ?: EasyTierFailureCategory.VpnPermissionRequired
        }
        if (snapshot.status != EasyTierConnectionStatus.FAILED) {
            return EasyTierFailureCategory.None
        }
        if (resultCode == EasyTierProcessService.RESULT_PERMISSION_REQUIRED) {
            return EasyTierFailureCategory.VpnPermissionRequired
        }
        return classifyFromSummary(snapshot.lastErrorSummary)
    }

    fun classifyFromSummary(summary: String?): EasyTierFailureCategory {
        val normalized = summary
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (normalized.isBlank()) {
            return EasyTierFailureCategory.None
        }
        return when {
            normalized.contains("room has been closed") ||
                normalized.contains("room was closed") ||
                normalized.contains("房间已关闭") ||
                normalized.contains("房間已關閉") -> EasyTierFailureCategory.RoomClosed
            normalized.contains("session expired") ||
                normalized.contains("会话已过期") ||
                normalized.contains("會話已過期") -> EasyTierFailureCategory.SessionExpired
            normalized.contains("session stopped") ||
                normalized.contains("session was stopped") ||
                normalized.contains("会话已停止") ||
                normalized.contains("會話已停止") -> EasyTierFailureCategory.SessionClosed
            normalized.contains("native runtime library") ||
                normalized.contains("native runtime failed to load") ||
                normalized.contains("not bundled") ||
                normalized.contains("dlopen failed") ||
                normalized.contains("cannot locate symbol") ||
                normalized.contains("unsatisfiedlinkerror") -> EasyTierFailureCategory.RuntimeBridgeUnavailable
            normalized.contains("runtime bridge") -> EasyTierFailureCategory.RuntimeBridgePending
            normalized.contains("config is unavailable") ||
                normalized.contains("cloud-control config is unavailable") ||
                normalized.contains("云控配置") ||
                normalized.contains("雲端控制設定") -> EasyTierFailureCategory.ConfigMissing
            normalized.contains("background startup") ||
                normalized.contains("后台启动") ||
                normalized.contains("後台啟動") -> EasyTierFailureCategory.BackgroundStartBlocked
            normalized.contains("permission was revoked") ||
                normalized.contains("权限已被系统回收") ||
                normalized.contains("權限已被系統回收") -> EasyTierFailureCategory.VpnPermissionRevoked
            normalized.contains("permission was not granted") ||
                normalized.contains("未授予 vpn 权限") ||
                normalized.contains("未授予 vpn 權限") -> EasyTierFailureCategory.VpnPermissionDenied
            normalized.contains("grant vpn permission") ||
                normalized.contains("授予 vpn 权限") ||
                normalized.contains("授予 vpn 權限") ||
                normalized.contains("vpn permission required") -> EasyTierFailureCategory.VpnPermissionRequired
            else -> EasyTierFailureCategory.Unknown
        }
    }
}
