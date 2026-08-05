package io.stamethyst.backend.easytier

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.stamethyst.backend.crash.ProcessExitSummary

/**
 * Reads `ApplicationExitInfo` records for the `:easytier` process.
 *
 * This exists because `ProcessExitInfoCapture` hard-filters every query to the `:game` process, so
 * an `:easytier` death was invisible in every diagnostics bundle. That gap made the central question
 * — "was the virtual network killed, or did it disconnect on its own?" — unanswerable after the
 * fact: lowmemorykiller uses SIGKILL, which produces no crash report, no log line, and no trace.
 * `ApplicationExitInfo` is the only source that can attribute such a kill, and it is queried
 * *retroactively*, so it survives the very kill it describes.
 *
 * `REASON_LOW_MEMORY` is the direct LMK signal, but it is not the only one that matters: a
 * cgroup-level kill is commonly reported as `REASON_SIGNALED` with SIGKILL (status 9), and
 * `REASON_EXCESSIVE_RESOURCE_USAGE` and `REASON_DEPENDENCY_DIED` are also involuntary. They are all
 * treated as memory-pressure candidates so a diagnosis is never silently downgraded to "clean exit".
 */
internal object EasyTierProcessExitReader {
    private const val EASYTIER_PROCESS_SUFFIX = ":easytier"
    private const val MAX_EXIT_RECORDS = 24
    private const val SIGKILL_STATUS = 9

    /** Involuntary exits: the process did not choose to stop. */
    private val INVOLUNTARY_EXIT_REASONS = setOf(
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_SIGNALED,
    )

    /**
     * Returns the most recent `:easytier` exit records, newest first, capped at [limit].
     *
     * Records are *not* filtered by reason: a voluntary `REASON_USER_REQUESTED` or `REASON_EXIT_SELF`
     * is just as diagnostically valuable, because it rules the LMK hypothesis out instead of leaving
     * it open.
     */
    fun readRecentExits(context: Context, limit: Int): List<ProcessExitSummary> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }
        return readRecentExitsApi30(context, limit)
    }

    /** True when [summary] describes an exit the process did not ask for. */
    fun isInvoluntaryExit(summary: ProcessExitSummary): Boolean =
        summary.reason in INVOLUNTARY_EXIT_REASONS

    /**
     * True when [summary] is consistent with a lowmemorykiller / cgroup kill. `REASON_SIGNALED` only
     * qualifies for SIGKILL, since other signals indicate a crash rather than reclamation.
     */
    fun isMemoryPressureKill(summary: ProcessExitSummary): Boolean = when (summary.reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
        ApplicationExitInfo.REASON_SIGNALED -> summary.status == SIGKILL_STATUS
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readRecentExitsApi30(context: Context, limit: Int): List<ProcessExitSummary> {
        if (limit <= 0) {
            return emptyList()
        }
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val records = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
        } catch (_: Throwable) {
            // The query itself must never break diagnostics collection.
            return emptyList()
        }
        if (records.isNullOrEmpty()) {
            return emptyList()
        }
        val easyTierProcessName = context.packageName + EASYTIER_PROCESS_SUFFIX
        return records
            .asSequence()
            .filter { it.processName == easyTierProcessName }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map(::buildSummary)
            .toList()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildSummary(exitInfo: ApplicationExitInfo): ProcessExitSummary = ProcessExitSummary(
        pid = exitInfo.pid,
        processName = exitInfo.processName.trim(),
        reason = exitInfo.reason,
        reasonName = reasonName(exitInfo.reason),
        status = exitInfo.status,
        timestamp = exitInfo.timestamp,
        description = exitInfo.description?.trim().orEmpty(),
        isSignal = exitInfo.reason == ApplicationExitInfo.REASON_SIGNALED,
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "REASON_PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "REASON_PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "REASON_UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
        else -> "REASON_$reason"
    }
}
