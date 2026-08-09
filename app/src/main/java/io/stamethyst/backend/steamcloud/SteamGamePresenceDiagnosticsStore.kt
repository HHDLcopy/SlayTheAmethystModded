package io.stamethyst.backend.steamcloud

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SteamGamePresenceDiagnosticsStore {
    private const val TAG = "SteamGamePresenceDiag"
    private const val DIRECTORY_NAME = "steam-game-presence"
    private const val SUMMARY_FILE_NAME = "last-operation-summary.txt"

    fun summaryFile(context: Context): File =
        File(File(context.filesDir, DIRECTORY_NAME), SUMMARY_FILE_NAME)

    fun writeSummary(
        context: Context,
        outcome: String,
        accountName: String,
        startedAtMs: Long,
        completedAtMs: Long,
        appIdSent: Boolean,
        clearStateSent: Boolean,
        error: Throwable?,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
        detail: String = "",
    ) {
        val target = summaryFile(context)
        runCatching {
            val directory = target.parentFile!!
            check(directory.mkdirs() || directory.isDirectory) {
                "Unable to create Steam game presence diagnostics directory"
            }
            val summary = buildString {
                appendLine("Steam game presence diagnostics")
                appendLine("Generated: ${formatTime(System.currentTimeMillis())}")
                appendLine("Outcome: $outcome")
                appendLine("App ID: 646570")
                appendLine("Account: ${accountName.ifBlank { "<unknown>" }}")
                appendLine("Started: ${formatTime(startedAtMs)}")
                appendLine("Completed: ${formatTime(completedAtMs)}")
                appendLine("Summary file: ${target.absolutePath}")
                appendLine("App state message sent: $appIdSent")
                appendLine("Clear state message sent: $clearStateSent")
                if (detail.isNotBlank()) appendLine("Detail: $detail")
                if (diagnostics != null) {
                    appendLine("Current stage: ${diagnostics.currentStage}")
                    appendLine("Protocol types: ${diagnostics.protocolTypesDescription}")
                    appendLine("Connected callback: ${diagnostics.connectedCallbackReceived}")
                    appendLine("Logon result: ${diagnostics.loggedOnResultDescription}")
                    appendLine("Disconnected: ${diagnostics.disconnectedDescription}")
                    appendLine("CM endpoint: ${diagnostics.resolvedServerDescription}")
                    appendLine("CM candidate source: ${diagnostics.candidateSourceDescription}")
                    appendLine("Steam ID from logon: ${diagnostics.loggedOnCallbackSteamId64}")
                    appendLine("Steam ID from client: ${diagnostics.steamClientSteamId64}")
                     appendLine("CM selection ms: ${diagnostics.cmServerSelectionMs}")
                     appendLine("CM connect wait ms: ${diagnostics.cmConnectWaitMs}")
                     appendLine("Playing session blocked: ${diagnostics.playingSessionBlocked}")
                     appendLine("Playing session App ID: ${diagnostics.playingSessionAppId}")
                     appendLine("JavaSteam last log: ${diagnostics.javaSteamLastLogDescription}")
                    appendLine("JavaSteam last error: ${diagnostics.javaSteamLastErrorDescription}")
                    appendLine("Diagnostic events:")
                    diagnostics.diagnosticEventLines.forEach { appendLine("  $it") }
                    appendLine("JavaSteam log tail:")
                    diagnostics.javaSteamLogTailLines.forEach { appendLine("  $it") }
                    appendLine("JavaSteam error tail:")
                    diagnostics.javaSteamErrorStackLines.forEach { appendLine("  $it") }
                }
                if (error != null) {
                    appendLine("Exception: ${error.javaClass.name}: ${error.message}")
                    appendLine("Stack trace:")
                    error.stackTrace.forEach { appendLine("  at $it") }
                }
            }
            SteamCloudAtomicFileStore.writeText(target, summary)
            Log.i(TAG, "Wrote Steam game presence diagnostics to ${target.absolutePath}")
        }.onFailure { error ->
            Log.e(TAG, "Unable to write Steam game presence diagnostics to ${target.absolutePath}", error)
        }
    }

    private fun formatTime(timestampMs: Long): String =
        if (timestampMs <= 0L) "<not recorded>"
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timestampMs))
}
