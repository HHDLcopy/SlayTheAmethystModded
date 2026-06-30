package io.stamethyst.ui.main

import io.stamethyst.backend.launch.LaunchPreparationFailureMessageResolver

internal data class CrashRecoveryReport(
    val summaryText: String,
    val reportText: String
)

internal object CrashRecoveryReportFormatter {
    private val skippedSummaryLines = setOf("game crashed.", "cause:")

    fun format(detail: String?, fallbackMessage: String): CrashRecoveryReport {
        val normalizedFallback = LaunchPreparationFailureMessageResolver
            .stripInternalDetailMarkers(fallbackMessage)
            ?: fallbackMessage.trim()
        val normalizedReport = LaunchPreparationFailureMessageResolver
            .stripInternalDetailMarkers(detail)
            ?: normalizedFallback
        val summaryText = normalizedReport
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() && skippedSummaryLines.none { skipped ->
                    line.equals(skipped, ignoreCase = true)
                }
            }
            ?: normalizedFallback
        return CrashRecoveryReport(
            summaryText = summaryText,
            reportText = normalizedReport
        )
    }
}
