package io.stamethyst.backend.easytier

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object EasyTierDiagnosticsStore {
    private const val SUMMARY_FILE_NAME = "last-session-summary.txt"
    private const val EVENT_HISTORY_DIR_NAME = "events"
    private const val EVENT_HISTORY_LIMIT = 5
    private val HISTORY_STATES = setOf(EasyTierConnectionStatus.FAILED)

    @JvmStatic
    fun summaryFile(context: Context): File = File(EasyTierStateStore.outputDir(context), SUMMARY_FILE_NAME)

    fun eventHistoryDir(context: Context): File =
        File(EasyTierStateStore.outputDir(context), EVENT_HISTORY_DIR_NAME)

    @Throws(IOException::class)
    fun recordStateTransition(
        context: Context,
        snapshot: EasyTierConnectionSnapshot,
        extraLines: List<String> = emptyList(),
        error: Throwable? = null,
    ) {
        val text = buildSummaryText(context, snapshot, extraLines, error)
        EasyTierAtomicFileStore.writeText(summaryFile(context), text, Charsets.UTF_8)
        if (snapshot.status in HISTORY_STATES) {
            writeEventHistory(context, snapshot, text)
        }
    }

    fun clear(context: Context) {
        summaryFile(context).delete()
    }

    private fun buildSummaryText(
        context: Context,
        snapshot: EasyTierConnectionSnapshot,
        extraLines: List<String>,
        error: Throwable?,
    ): String {
        val lines = buildList {
            add("EasyTier diagnostics summary")
            add("")
            add("Status: ${snapshot.status.name}")
            add("Enabled: ${if (snapshot.enabled) "yes" else "no"}")
            add("Can Connect: ${if (snapshot.canConnect) "yes" else "no"}")
            add("Mode: ${snapshot.mode.cloudControlValue}")
            add("Failure Category: ${snapshot.failureCategory.name}")
            add("Session ID: ${snapshot.sessionId.ifBlank { "<none>" }}")
            add("Room ID: ${snapshot.roomId.ifBlank { "<none>" }}")
            add("Entry Node: ${snapshot.entryNodeUrl.ifBlank { "<none>" }}")
            add("Config Server: ${snapshot.configServerUrl.ifBlank { "<none>" }}")
            add("ACL Group: ${snapshot.aclGroup.ifBlank { "<none>" }}")
            add("Expires At Epoch Seconds: ${snapshot.expiresAtEpochSeconds?.toString() ?: "<none>"}")
            add("Started At: ${formatTimestamp(snapshot.startedAtMs)}")
            add("Connected At: ${formatTimestamp(snapshot.connectedAtMs)}")
            add("Updated At: ${formatTimestamp(snapshot.lastUpdatedAtMs.takeIf { it > 0L })}")
            add("User Initiated: ${if (snapshot.userInitiated) "yes" else "no"}")
            add("Last Session State: ${snapshot.lastSessionState.ifBlank { "<unknown>" }}")
            add("Last Room State: ${snapshot.lastRoomState.ifBlank { "<unknown>" }}")
            add(
                "Peer Count: ${
                    snapshot.peerCount?.toString() ?: "<unknown>"
                }"
            )
            add("Assigned IPv4 CIDR: ${snapshot.assignedIpv4Cidr.ifBlank { "<unknown>" }}")
            add("Relay Server: ${snapshot.relayServerDescription.ifBlank { "<unknown>" }}")
            add(
                "Failure Summary: ${
                    snapshot.lastErrorSummary.takeIf { it.isNotBlank() } ?: describeFailure(error)
                }"
            )
            add("State File: ${EasyTierStateStore.stateFile(context).absolutePath}")
            add("Summary: ${summaryFile(context).absolutePath}")
            if (extraLines.isNotEmpty()) {
                add("")
                add("Details:")
                extraLines.forEach { line ->
                    add("  - ${line.trim().ifBlank { "<blank>" }}")
                }
            }
            error?.let { failure ->
                add("")
                add("Error Type: ${failure.javaClass.name}")
                add("Error Cause Chain: ${formatExceptionCauseChain(failure)}")
                add("")
                add("Full Exception Stack:")
                add(exceptionStack(failure).trimEnd())
            }
        }
        return lines.joinToString("\n") + "\n"
    }

    @Throws(IOException::class)
    private fun writeEventHistory(
        context: Context,
        snapshot: EasyTierConnectionSnapshot,
        text: String,
    ) {
        val dir = eventHistoryDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Failed to create EasyTier event history directory: ${dir.absolutePath}")
        }
        val baseFileName = buildString {
            append("event-")
            append(snapshot.status.name.lowercase(Locale.US))
            append("-")
            append(formatFileTimestamp(snapshot.lastUpdatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()))
            append(".txt")
        }
        val target = allocateHistoryFile(dir, baseFileName)
        EasyTierAtomicFileStore.writeText(target, text, Charsets.UTF_8)
        pruneHistory(dir)
    }

    private fun allocateHistoryFile(dir: File, baseFileName: String): File {
        val plain = File(dir, baseFileName)
        if (plain.createNewFile()) {
            return plain
        }
        val stem = baseFileName.removeSuffix(".txt")
        for (sequence in 1..99) {
            val candidate = File(dir, "$stem-$sequence.txt")
            if (candidate.createNewFile()) {
                return candidate
            }
        }
        throw IOException("Failed to allocate EasyTier event history slot in ${dir.absolutePath}")
    }

    private fun pruneHistory(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("event-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(EVENT_HISTORY_LIMIT).forEach { it.delete() }
    }

    private fun formatTimestamp(timestampMs: Long?): String {
        if (timestampMs == null || timestampMs <= 0L) {
            return "<none>"
        }
        return TIMESTAMP_FORMAT.format(Date(timestampMs))
    }

    private fun formatFileTimestamp(timestampMs: Long): String =
        FILE_TIMESTAMP_FORMAT.format(Date(timestampMs))

    private fun describeFailure(error: Throwable?): String {
        val meaningful = error?.message?.trim().orEmpty()
        return meaningful.ifBlank { "<none>" }
    }

    private fun formatExceptionCauseChain(error: Throwable): String {
        val chain = mutableListOf<String>()
        var cursor: Throwable? = error
        while (cursor != null) {
            val message = cursor.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "<no message>"
            chain += "${cursor.javaClass.name}: $message"
            cursor = cursor.cause
        }
        return chain.joinToString(" <- ")
    }

    private fun exceptionStack(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            error.printStackTrace(printWriter)
        }
        return writer.toString()
    }

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private val FILE_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
}
