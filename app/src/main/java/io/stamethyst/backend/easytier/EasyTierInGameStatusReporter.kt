package io.stamethyst.backend.easytier

import android.content.Context

internal enum class EasyTierInGameSessionState(
    val wireValue: String,
) {
    Online("online"),
    Game("game");

    companion object {
        fun fromWireValue(value: String): EasyTierInGameSessionState? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
    }
}

/** Sends Together in Spire's room state without exposing LAN session credentials to JVM mods. */
internal object EasyTierInGameStatusReporter {
    fun report(context: Context, state: EasyTierInGameSessionState) {
        val appContext = context.applicationContext
        val snapshot = EasyTierSessionController.currentSnapshot(appContext)
        if (snapshot.status != EasyTierConnectionStatus.CONNECTED ||
            snapshot.sessionId.isBlank() ||
            snapshot.roomId.isBlank() ||
            snapshot.currentPlayerId.isBlank()
        ) {
            return
        }
        val sessionToken = EasyTierCredentialStore.sessionToken(
            appContext,
            snapshot.roomId,
            snapshot.currentPlayerId,
        )
        if (sessionToken.isBlank()) {
            return
        }
        EasyTierRoomApiClient(appContext).reportSessionGameState(
            sessionId = snapshot.sessionId,
            sessionToken = sessionToken,
            gameState = state.wireValue,
        )
    }
}
