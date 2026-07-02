package io.stamethyst.backend.workshop

internal fun isWorkshopUpdateCheckDue(
    lastCheckedAtMs: Long,
    nowMs: Long,
    checkIntervalMs: Long,
): Boolean {
    return lastCheckedAtMs <= 0L || nowMs < lastCheckedAtMs || nowMs - lastCheckedAtMs >= checkIntervalMs
}

internal fun workshopUpdateCheckInitialDelayMs(
    lastCheckedAtMs: Long,
    nowMs: Long,
    checkIntervalMs: Long,
    appStartDelayMs: Long,
): Long {
    return if (isWorkshopUpdateCheckDue(lastCheckedAtMs, nowMs, checkIntervalMs)) {
        appStartDelayMs
    } else {
        0L
    }
}
