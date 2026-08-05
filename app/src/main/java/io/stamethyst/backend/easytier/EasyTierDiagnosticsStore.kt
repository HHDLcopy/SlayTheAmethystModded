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
    private const val EASYTIER_EXIT_RECORD_LIMIT = 5

    /**
     * States worth archiving as their own history slot.
     *
     * `FAILED` alone is not enough. A session that dies mid-game normally lands on `DISCONNECTED`
     * (server-side terminal state, or the runtime going away), and a session fighting to stay up
     * lands on `RECONNECTING`. Both were previously overwritten in `last-session-summary.txt` by the
     * next transition, so a reported disconnect arrived with no history file at all and the summary
     * only described the final resting state.
     */
    private val HISTORY_STATES = setOf(
        EasyTierConnectionStatus.FAILED,
        EasyTierConnectionStatus.DISCONNECTED,
        EasyTierConnectionStatus.RECONNECTING,
    )

    @JvmStatic
    fun summaryFile(context: Context): File = File(EasyTierStateStore.outputDir(context), SUMMARY_FILE_NAME)

    /**
     * Last status written to the history, used to archive transitions only. Process-local: after an
     * `:easytier` restart this is null, so the first post-restart state is archived — which is
     * exactly the record needed to spot a kill/restart cycle.
     */
    @Volatile
    private var lastArchivedStatus: EasyTierConnectionStatus? = null

    fun eventHistoryDir(context: Context): File =
        File(EasyTierStateStore.outputDir(context), EVENT_HISTORY_DIR_NAME)

    @Throws(IOException::class)
    fun recordStateTransition(
        context: Context,
        snapshot: EasyTierConnectionSnapshot,
        extraLines: List<String> = emptyList(),
        error: Throwable? = null,
    ) {
        val previousStatus = lastArchivedStatus
        val text = buildSummaryText(context, snapshot, extraLines, error)
        EasyTierAtomicFileStore.writeText(summaryFile(context), text, Charsets.UTF_8)
        if (shouldArchive(previousStatus = previousStatus, status = snapshot.status)) {
            writeEventHistory(context, snapshot, text)
        }
        lastArchivedStatus = snapshot.status
    }

    /**
     * Archives only on entering a noteworthy state, never on staying in it.
     *
     * The status poll persists a snapshot on every iteration (default 5s), so archiving
     * unconditionally would burn all [EVENT_HISTORY_LIMIT] slots within ~25 seconds of a single
     * `RECONNECTING` stretch and evict the very transition that explains the disconnect.
     */
    internal fun shouldArchive(
        previousStatus: EasyTierConnectionStatus?,
        status: EasyTierConnectionStatus,
    ): Boolean = status in HISTORY_STATES && previousStatus != status

    /** Resets the transition filter. Exposed for tests, which reuse the singleton across cases. */
    internal fun resetArchivedStatusForTest() {
        lastArchivedStatus = null
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
            addAll(buildProcessExitLines(context))
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

    /**
     * Renders recent `:easytier` process deaths.
     *
     * This is the section that distinguishes "the virtual network was killed underneath the game"
     * from "the session ended on its own". Without it, a lowmemorykiller SIGKILL leaves no trace
     * anywhere in the bundle — no crash report, no log line — and the disconnect is
     * indistinguishable from a clean teardown.
     */
    internal fun buildProcessExitLines(context: Context): List<String> {
        val exits = runCatching {
            EasyTierProcessExitReader.readRecentExits(context, EASYTIER_EXIT_RECORD_LIMIT)
        }.getOrDefault(emptyList())
        return buildList {
            add("")
            add("Recent :easytier Process Exits:")
            if (exits.isEmpty()) {
                // Absence is not proof of survival, and saying so prevents the same wrong inference
                // that a missing record previously invited.
                add("  - <none recorded> (requires Android 11+; absence does not prove the process survived)")
                return@buildList
            }
            exits.forEach { exit ->
                add(
                    "  - ${formatTimestamp(exit.timestamp)} pid=${exit.pid} ${exit.reasonName}" +
                        " status=${exit.status}" +
                        " involuntary=${if (EasyTierProcessExitReader.isInvoluntaryExit(exit)) "yes" else "no"}" +
                        " memoryPressureKill=${
                            if (EasyTierProcessExitReader.isMemoryPressureKill(exit)) "yes" else "no"
                        }" +
                        exit.description.takeIf { it.isNotBlank() }?.let { " description=$it" }.orEmpty()
                )
            }
        }
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
        // The atomic writer drops a sibling "<name>.bak" next to each event, and those also start
        // with "event-". Counting them as slots would halve the effective history depth, so they are
        // excluded here and removed alongside the event they belong to.
        val files = dir.listFiles { file -> file.isFile && isEventHistoryFile(file.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(EVENT_HISTORY_LIMIT).forEach { event ->
            event.delete()
            EasyTierAtomicFileStore.backupFile(event).delete()
        }
    }

    internal fun isEventHistoryFile(fileName: String): Boolean =
        fileName.startsWith("event-") && fileName.endsWith(".txt")

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
