package io.stamethyst.backend.workshop

internal fun WorkshopDownloadTaskRecord.shouldRecoverInterruptedDownload(
    nowMillis: Long,
    isActiveDownload: Boolean,
    activeDownloadRecoveryGraceMs: Long,
): Boolean {
    if (isActiveDownload) return false
    if (!status.isRunningDownload() && !status.isStoppingDownload()) return false
    if (status.isRunningDownload() && nowMillis - updatedAtMillis < activeDownloadRecoveryGraceMs) return false
    return true
}
