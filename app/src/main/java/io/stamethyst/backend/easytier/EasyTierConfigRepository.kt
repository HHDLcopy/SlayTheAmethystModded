package io.stamethyst.backend.easytier

import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.CloudControlEasyTierSettings

object EasyTierConfigRepository {
    @JvmStatic
    fun current(): EasyTierResolvedConfig =
        fromCloudControl(CloudControlConfig.easyTier())

    internal fun fromCloudControl(settings: CloudControlEasyTierSettings): EasyTierResolvedConfig {
        val requestedMode = EasyTierNetworkMode.fromCloudControl(settings.defaultMode)
        val resolvedMode = if (
            requestedMode == EasyTierNetworkMode.Community &&
            !settings.allowSharedCommunityNetwork
        ) {
            EasyTierNetworkMode.Room
        } else {
            requestedMode
        }

        return EasyTierResolvedConfig(
            enabled = true,
            defaultMode = resolvedMode,
            roomApiBaseUrl = settings.roomApiBaseUrl,
            webConsoleApiBaseUrl = settings.webConsoleApiBaseUrl,
            configServerUrl = settings.configServerUrl,
            entryNodeUrl = settings.entryNodeUrl,
            connectTimeoutSeconds = settings.connectTimeoutSeconds,
            statusPollIntervalSeconds = settings.statusPollIntervalSeconds,
            allowSharedCommunityNetwork = settings.allowSharedCommunityNetwork,
        )
    }
}
