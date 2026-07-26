package io.stamethyst.backend.workshop

import android.content.Context

internal class WorkshopSteamRateLimitStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun activate(nowMillis: Long = System.currentTimeMillis()): Long {
        val cooldownUntilMillis = nowMillis + WORKSHOP_STEAM_RATE_LIMIT_COOLDOWN_MS
        preferences.edit().putLong(KEY_COOLDOWN_UNTIL_MILLIS, cooldownUntilMillis).apply()
        return cooldownUntilMillis
    }

    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val cooldownUntilMillis = preferences.getLong(KEY_COOLDOWN_UNTIL_MILLIS, 0L)
        val remainingMillis = remainingWorkshopSteamRateLimitMillis(cooldownUntilMillis, nowMillis)
        if (cooldownUntilMillis > 0L && remainingMillis == 0L) {
            preferences.edit().remove(KEY_COOLDOWN_UNTIL_MILLIS).apply()
        }
        return remainingMillis
    }

    fun cooldownMessage(nowMillis: Long = System.currentTimeMillis()): String =
        formatWorkshopSteamRateLimitCooldownMessage(remainingMillis(nowMillis))

    private companion object {
        const val PREFERENCES_NAME = "workshop_steam_rate_limit"
        const val KEY_COOLDOWN_UNTIL_MILLIS = "cooldown_until_millis"
    }
}

internal const val WORKSHOP_STEAM_RATE_LIMIT_COOLDOWN_MS = 15 * 60 * 1_000L

internal fun remainingWorkshopSteamRateLimitMillis(cooldownUntilMillis: Long, nowMillis: Long): Long =
    (cooldownUntilMillis - nowMillis).coerceAtLeast(0L)

internal fun formatWorkshopSteamRateLimitCooldownMessage(remainingMillis: Long): String {
    val minutes = ((remainingMillis.coerceAtLeast(1L) + 59_999L) / 60_000L).coerceAtLeast(1L)
    return "Steam 请求过于频繁，已暂停下载，请在约 $minutes 分钟后重试"
}
