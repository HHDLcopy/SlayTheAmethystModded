package io.stamethyst.backend.easytier

import android.content.Context

internal object EasyTierModInventoryReporter {
    fun reportCurrentSessionAsync(context: Context) {
        val appContext = context.applicationContext
        Thread(
            { runCatching { reportCurrentSession(appContext) } },
            "STS-EasyTierModInventory",
        ).apply {
            isDaemon = true
            start()
        }
    }

    internal fun reportCurrentSession(context: Context) {
        val snapshot = EasyTierSessionController.currentSnapshot(context)
        if (snapshot.status != EasyTierConnectionStatus.CONNECTED ||
            snapshot.sessionId.isBlank() ||
            snapshot.roomId.isBlank() ||
            snapshot.currentPlayerId.isBlank()
        ) {
            return
        }
        val sessionToken = EasyTierCredentialStore.sessionToken(
            context,
            snapshot.roomId,
            snapshot.currentPlayerId,
        )
        if (sessionToken.isBlank()) {
            return
        }
        EasyTierRoomApiClient(context).reportSessionMods(
            sessionId = snapshot.sessionId,
            sessionToken = sessionToken,
            mods = EasyTierModInventory.collect(context),
        )
    }
}
