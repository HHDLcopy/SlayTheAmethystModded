package io.stamethyst.backend.presence

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File

object GamePresenceStateMarker {
    fun markGameActive(context: Context, launchMode: String) {
        writeState(context, GamePresenceState.Game, launchMode)
    }

    fun markLauncherActive(context: Context) {
        writeState(context, GamePresenceState.Launcher, "")
    }

    fun readCurrentState(context: Context): GamePresenceSnapshot {
        val file = gamePresenceStateFile(context)
        val lines = runCatching { file.readLines() }.getOrNull().orEmpty()
        val state = when (lines.getOrNull(0)?.trim()) {
            GamePresenceState.Game.wireValue -> GamePresenceState.Game
            else -> GamePresenceState.Launcher
        }
        val launchMode = lines.getOrNull(1)?.trim().orEmpty()
        return GamePresenceSnapshot(
            state = state,
            launchMode = launchMode
        )
    }

    private fun writeState(
        context: Context,
        state: GamePresenceState,
        launchMode: String
    ) {
        runCatching {
            val file = gamePresenceStateFile(context)
            file.parentFile?.mkdirs()
            file.writeText(
                state.wireValue + "\n" +
                    launchMode + "\n"
            )
        }
    }

    private fun gamePresenceStateFile(context: Context): File =
        File(RuntimePaths.stsRoot(context), ".game_presence_state")
}

data class GamePresenceSnapshot(
    val state: GamePresenceState,
    val launchMode: String
)
