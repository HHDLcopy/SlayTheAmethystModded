package io.stamethyst.backend.steamcloud

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.io.File

/** Bundled Slay the Spire achievement state service with one explicit test-account CM write. */
object SteamAchievementService {
    const val APP_ID = 646570L
    internal const val SHRUG_IT_OFF_API_NAME = "shrug_it_off"

    data class Achievement(
        val apiName: String,
        @get:StringRes val titleResId: Int,
        @get:StringRes val descriptionResId: Int,
        @get:DrawableRes val unlockedIconResId: Int,
        @get:DrawableRes val lockedIconResId: Int,
        val unlocked: Boolean,
        val unlockTimeSeconds: Long,
    )

    data class Snapshot(
        val steamId64: String,
        val achievements: List<Achievement>,
        val fetchedAtMs: Long,
        val fromCache: Boolean,
    ) {
        val unlockedCount: Int get() = achievements.count { it.unlocked }
    }

    fun fetchViaCm(
        context: Context,
        accountName: String,
        refreshToken: String,
        steamId64: String,
    ): Snapshot {
        require(accountName.isNotBlank()) { "Steam account name is not available." }
        require(refreshToken.isNotBlank()) { "Steam refresh token is not available." }
        val normalizedId = steamId64.trim()
        require(normalizedId.isNotEmpty()) { "Steam account is not available." }
        SteamCloudClient(context).use { client ->
            client.beginOperationDiagnostics("steam_achievements_cm", accountName, false)
            client.start()
            client.logOnWithRefreshToken(accountName, refreshToken, normalizedId)
            val result = client.getUserStats(APP_ID, normalizedId.toLong(), CM_TIMEOUT_MS)
            return snapshotFromResult(normalizedId, result, System.currentTimeMillis()).also {
                writeCached(context, it)
            }
        }
    }

    /**
     * Experimental test-account operation. It only sets the server schema bit for
     * `shrug_it_off`, then requires a fresh CM read to confirm the unlock.
     */
    fun unlockShrugItOffViaCm(
        context: Context,
        accountName: String,
        refreshToken: String,
        steamId64: String,
    ): Snapshot {
        require(accountName.isNotBlank()) { "Steam account name is not available." }
        require(refreshToken.isNotBlank()) { "Steam refresh token is not available." }
        val normalizedId = steamId64.trim()
        require(normalizedId.isNotEmpty()) { "Steam account is not available." }
        SteamCloudClient(context).use { client ->
            client.beginOperationDiagnostics("steam_achievement_test_unlock", accountName, false)
            client.start()
            client.logOnWithRefreshToken(accountName, refreshToken, normalizedId)
            val initial = client.getUserStats(APP_ID, normalizedId.toLong(), CM_TIMEOUT_MS)
            val target = initial.achievementStatTargets[SHRUG_IT_OFF_API_NAME]
                ?: knownShrugItOffTarget()
            val currentValue = initial.statValues[target.statId] ?: 0
            if (currentValue and target.mask != 0) {
                return snapshotFromResult(normalizedId, initial, System.currentTimeMillis()).also {
                    writeCached(context, it)
                }
            }

            client.storeUserStat(
                APP_ID,
                normalizedId.toLong(),
                initial.crcStats,
                target.statId,
                currentValue or target.mask,
                CM_TIMEOUT_MS,
            )

            val verified = client.getUserStats(APP_ID, normalizedId.toLong(), CM_TIMEOUT_MS)
            val verifiedValue = verified.statValues[target.statId] ?: 0
            if (verifiedValue and target.mask == 0) {
                throw IllegalStateException(
                    "Steam CM accepted the test write but did not confirm $SHRUG_IT_OFF_API_NAME as unlocked.",
                )
            }
            return snapshotFromResult(normalizedId, verified, System.currentTimeMillis()).also {
                writeCached(context, it)
            }
        }
    }

    fun readCached(context: Context, steamId64: String): Snapshot? {
        val normalizedId = steamId64.trim()
        if (normalizedId.isEmpty()) return null
        val cache = cacheFile(context, normalizedId)
        if (!cache.isFile) return null
        return parseCache(normalizedId, cache.readLines())
    }

    internal fun buildSnapshot(
        steamId64: String,
        unlockTimes: Map<String, Long>,
        fetchedAtMs: Long,
        fromCache: Boolean,
    ): Snapshot = Snapshot(
        steamId64 = steamId64,
        achievements = SteamAchievementCatalog.entries.map { entry ->
            val unlockTime = unlockTimes[entry.apiName] ?: 0L
            Achievement(
                apiName = entry.apiName,
                titleResId = entry.titleResId,
                descriptionResId = entry.descriptionResId,
                unlockedIconResId = entry.unlockedIconResId,
                lockedIconResId = entry.lockedIconResId,
                unlocked = unlockTime > 0L,
                unlockTimeSeconds = unlockTime,
            )
        },
        fetchedAtMs = fetchedAtMs,
        fromCache = fromCache,
    )

    private fun snapshotFromResult(
        steamId64: String,
        result: SteamCloudClient.UserStatsResult,
        fetchedAtMs: Long,
    ): Snapshot {
        val apiNameById = result.definitions.associate { it.achievementId to it.apiName }
        val stateUnlockTimes = result.states.mapNotNull { state ->
            apiNameById[state.achievementId]
                ?.takeIf(SteamAchievementCatalog.apiNames::contains)
                ?.let { it to state.unlockTimeSeconds }
        }.toMap()
        val targets = result.achievementStatTargets +
            (SHRUG_IT_OFF_API_NAME to knownShrugItOffTarget())
        val playerUnlockTimes = result.achievementUnlockTimes.associate { unlockTime ->
            unlockTime.statId to unlockTime.bitIndex to unlockTime.unlockTimeSeconds
        }
        val bitUnlockTimes = targets.mapNotNull { (apiName, target) ->
            val statValue = result.statValues[target.statId] ?: return@mapNotNull null
            if (statValue and target.mask != 0 && apiName in SteamAchievementCatalog.apiNames) {
                apiName to preferredBitfieldUnlockTimeSeconds(
                    playerUnlockTimes[target.statId to target.bitIndex] ?: stateUnlockTimes[apiName],
                )
            } else {
                null
            }
        }.toMap()
        val unlockTimes = if (bitUnlockTimes.isNotEmpty()) {
            stateUnlockTimes + bitUnlockTimes
        } else {
            stateUnlockTimes
        }
        return buildSnapshot(steamId64, unlockTimes, fetchedAtMs, false)
    }

    internal fun preferredBitfieldUnlockTimeSeconds(
        steamUnlockTimeSeconds: Long?,
    ): Long =
        steamUnlockTimeSeconds?.takeIf { it > BITFIELD_UNLOCKED_SENTINEL_SECONDS }
            // CM stat bits confirm unlock state but do not carry an unlock timestamp.
            ?: BITFIELD_UNLOCKED_SENTINEL_SECONDS

    private fun knownShrugItOffTarget(): SteamCloudClient.UserStatsResult.AchievementStatTarget =
        SteamCloudClient.UserStatsResult.AchievementStatTarget(1, 1)

    private fun writeCached(context: Context, snapshot: Snapshot) {
        val text = buildString {
            append(CACHE_VERSION).append('\t').append(snapshot.fetchedAtMs).append('\n')
            snapshot.achievements.forEach { achievement ->
                append(achievement.apiName).append('\t').append(achievement.unlockTimeSeconds).append('\n')
            }
        }
        SteamCloudAtomicFileStore.writeText(cacheFile(context, snapshot.steamId64), text)
    }

    private fun parseCache(steamId64: String, lines: List<String>): Snapshot? {
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.firstOrNull() != CACHE_VERSION) return null
        val fetchedAtMs = header.getOrNull(1)?.toLongOrNull() ?: return null
        val unlockTimes = lines.drop(1).mapNotNull { line ->
            val fields = line.split('\t')
            val apiName = fields.getOrNull(0)?.takeIf(SteamAchievementCatalog.apiNames::contains) ?: return@mapNotNull null
            apiName to (fields.getOrNull(1)?.toLongOrNull() ?: 0L)
        }.toMap()
        return buildSnapshot(steamId64, unlockTimes, fetchedAtMs, true)
    }

    private fun cacheFile(context: Context, steamId64: String): File =
        File(File(context.applicationContext.filesDir, CACHE_DIRECTORY), "$steamId64.tsv")

    private const val CACHE_VERSION = "v2"
    private const val CACHE_DIRECTORY = "steam-achievements"
    private const val BITFIELD_UNLOCKED_SENTINEL_SECONDS = 1L
    private const val CM_TIMEOUT_MS = 30_000L
}
