package io.stamethyst.backend.steam

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAvatarCacheStore
import io.stamethyst.backend.steamcloud.SteamCloudBaselineStore
import io.stamethyst.backend.steamcloud.SteamCloudDiagnosticsStore
import io.stamethyst.backend.steamcloud.SteamCloudManifestStore
import io.stamethyst.backend.steamcloud.SteamCloudOperationMutex
import io.stamethyst.backend.steamcloud.SteamCloudSaveProfileManager
import io.stamethyst.backend.steamcloud.SteamCloudSyncProcessService
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.preferences.LauncherPreferences
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession

internal object SteamAccountLogoutCoordinator {
    fun logout(
        context: Context,
        clearDiagnostics: Boolean,
    ) {
        val appContext = context.applicationContext

        runCatching { SteamCloudSyncProcessService.cancel(appContext) }
        WorkshopService.cancelAllActiveCalls()
        OkHttpSteamCmSession.closeAllActiveSessions()

        SteamCloudOperationMutex.runExclusive {
            val currentMode = LauncherPreferences.readSteamCloudSaveMode(appContext)
            if (currentMode != SteamCloudSaveMode.INDEPENDENT) {
                SteamCloudSaveProfileManager.switchMode(
                    context = appContext,
                    fromMode = currentMode,
                    toMode = SteamCloudSaveMode.INDEPENDENT,
                )
            }
            LauncherPreferences.saveSteamCloudSaveMode(appContext, SteamCloudSaveMode.INDEPENDENT)
            SteamCloudAuthStore.clear(appContext)
            SteamCloudAvatarCacheStore.clear(appContext)
            SteamCloudManifestStore.clear(appContext)
            SteamCloudBaselineStore.clear(appContext)
            if (clearDiagnostics) {
                SteamCloudDiagnosticsStore.clear(appContext)
            }

            val snapshot = SteamCloudAuthStore.readSnapshot(appContext)
            check(snapshot.accountName.isBlank() &&
                !snapshot.refreshTokenConfigured &&
                !snapshot.guardDataConfigured &&
                snapshot.steamId64.isBlank()
            ) {
                "Steam account credentials are still present after logout."
            }
        }
    }
}
