package io.stamethyst.backend.steamcloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal object SteamCloudAuthStore {
    private const val PREFS_NAME = "steam_cloud_auth"
    private const val TAG = "SteamCloudAuthStore"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_GUARD_DATA = "guard_data"
    private const val KEY_STEAM_ID_64 = "steam_id_64"
    private const val KEY_PERSONA_NAME = "persona_name"
    private const val KEY_AVATAR_URL = "avatar_url"
    private const val KEY_LAST_AUTH_AT_MS = "last_auth_at_ms"
    private const val KEY_LAST_MANIFEST_AT_MS = "last_manifest_at_ms"
    private const val KEY_LAST_PULL_AT_MS = "last_pull_at_ms"
    private const val KEY_LAST_PUSH_AT_MS = "last_push_at_ms"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_WEB_ACCESS_TOKEN = "web_access_token"
    private const val KEY_WEB_ACCESS_TOKEN_EXPIRES_AT_MS = "web_access_token_expires_at_ms"
    private const val KEY_WEB_ACCESS_TOKEN_STEAM_ID_64 = "web_access_token_steam_id_64"
    private const val KEY_WEB_ACCESS_TOKEN_REFRESH_FINGERPRINT = "web_access_token_refresh_fingerprint"

    data class SavedAuthMaterial(
        val accountName: String,
        val refreshToken: String,
        val guardData: String,
        val steamId64: String,
    )

    data class AuthSnapshot(
        val accountName: String,
        val refreshTokenConfigured: Boolean,
        val guardDataConfigured: Boolean,
        val steamId64: String,
        val personaName: String,
        val avatarUrl: String,
        val lastAuthAtMs: Long?,
        val lastManifestAtMs: Long?,
        val lastPullAtMs: Long?,
        val lastPushAtMs: Long?,
        val lastError: String,
    ) {
        val isComplete: Boolean
            get() = accountName.isNotBlank() &&
                refreshTokenConfigured &&
                isValidSteamId64(steamId64)
    }

    data class CachedWebAccessToken(
        val accessToken: String,
        val expiresAtMs: Long,
    )

    fun readAuthMaterial(context: Context): SavedAuthMaterial? {
        return readSafely(context, "read Steam Cloud auth material") { prefs ->
            val accountName = prefs.getString(KEY_ACCOUNT_NAME, null)?.trim().orEmpty()
            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
            val steamId64 = prefs.getString(KEY_STEAM_ID_64, null)?.trim().orEmpty()
            if (accountName.isBlank() || refreshToken.isBlank() || !isValidSteamId64(steamId64)) {
                return@readSafely null
            }
            SavedAuthMaterial(
                accountName = accountName,
                refreshToken = refreshToken,
                guardData = prefs.getString(KEY_GUARD_DATA, null)?.trim().orEmpty(),
                steamId64 = steamId64,
            )
        }
    }

    fun readSnapshot(context: Context): AuthSnapshot {
        return readSafely(context, "read Steam Cloud auth snapshot") { prefs ->
            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
            val guardData = prefs.getString(KEY_GUARD_DATA, null)?.trim().orEmpty()
            AuthSnapshot(
                accountName = prefs.getString(KEY_ACCOUNT_NAME, null)?.trim().orEmpty(),
                refreshTokenConfigured = refreshToken.isNotBlank(),
                guardDataConfigured = guardData.isNotBlank(),
                steamId64 = prefs.getString(KEY_STEAM_ID_64, null)?.trim().orEmpty(),
                personaName = prefs.getString(KEY_PERSONA_NAME, null)?.trim().orEmpty(),
                avatarUrl = prefs.getString(KEY_AVATAR_URL, null)?.trim().orEmpty(),
                lastAuthAtMs = prefs.optionalLong(KEY_LAST_AUTH_AT_MS),
                lastManifestAtMs = prefs.optionalLong(KEY_LAST_MANIFEST_AT_MS),
                lastPullAtMs = prefs.optionalLong(KEY_LAST_PULL_AT_MS),
                lastPushAtMs = prefs.optionalLong(KEY_LAST_PUSH_AT_MS),
                lastError = prefs.getString(KEY_LAST_ERROR, null)?.trim().orEmpty(),
            )
        } ?: emptySnapshot()
    }

    fun recordAuthSuccess(
        context: Context,
        accountName: String,
        refreshToken: String,
        guardData: String,
        steamId64: String,
    ) {
        require(isValidSteamId64(steamId64)) {
            "SteamID64 is required before Steam Cloud auth can be saved."
        }
        writeSafely(context, "record Steam Cloud auth success") { prefs ->
            prefs.edit()
                .putString(KEY_ACCOUNT_NAME, accountName.trim())
                .putString(KEY_REFRESH_TOKEN, refreshToken.trim())
                .putString(KEY_GUARD_DATA, guardData.trim())
                .putString(KEY_STEAM_ID_64, steamId64.trim())
                .remove(KEY_PERSONA_NAME)
                .remove(KEY_AVATAR_URL)
                .putLong(KEY_LAST_AUTH_AT_MS, System.currentTimeMillis())
                .remove(KEY_LAST_MANIFEST_AT_MS)
                .remove(KEY_LAST_PULL_AT_MS)
                .remove(KEY_LAST_PUSH_AT_MS)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_WEB_ACCESS_TOKEN)
                .remove(KEY_WEB_ACCESS_TOKEN_EXPIRES_AT_MS)
                .remove(KEY_WEB_ACCESS_TOKEN_STEAM_ID_64)
                .remove(KEY_WEB_ACCESS_TOKEN_REFRESH_FINGERPRINT)
                .apply()
        }
    }

    fun readCachedWebAccessToken(
        context: Context,
        steamId: Long,
        refreshToken: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumRemainingLifetimeMs: Long,
    ): CachedWebAccessToken? {
        return readSafely(context, "read cached Steam web access token") { prefs ->
            val accessToken = prefs.getString(KEY_WEB_ACCESS_TOKEN, null)?.trim().orEmpty()
            val expiresAtMs = prefs.getLong(KEY_WEB_ACCESS_TOKEN_EXPIRES_AT_MS, 0L)
            val storedSteamId = prefs.getString(KEY_WEB_ACCESS_TOKEN_STEAM_ID_64, null)?.trim().orEmpty()
            val storedRefreshFingerprint = prefs.getString(KEY_WEB_ACCESS_TOKEN_REFRESH_FINGERPRINT, null)?.trim().orEmpty()
            if (
                accessToken.isBlank() ||
                expiresAtMs <= nowMs + minimumRemainingLifetimeMs ||
                storedSteamId != steamId.toString() ||
                storedRefreshFingerprint != refreshToken.fingerprintForCacheScope()
            ) {
                return@readSafely null
            }
            CachedWebAccessToken(accessToken = accessToken, expiresAtMs = expiresAtMs)
        }
    }

    fun cacheWebAccessToken(
        context: Context,
        steamId: Long,
        refreshToken: String,
        accessToken: String,
        expiresAtMs: Long,
    ) {
        require(accessToken.isNotBlank()) { "Steam web access token must not be blank." }
        require(expiresAtMs > System.currentTimeMillis()) { "Steam web access token must expire in the future." }
        writeSafely(context, "cache Steam web access token") { prefs ->
            prefs.edit()
                .putString(KEY_WEB_ACCESS_TOKEN, accessToken.trim())
                .putLong(KEY_WEB_ACCESS_TOKEN_EXPIRES_AT_MS, expiresAtMs)
                .putString(KEY_WEB_ACCESS_TOKEN_STEAM_ID_64, steamId.toString())
                .putString(KEY_WEB_ACCESS_TOKEN_REFRESH_FINGERPRINT, refreshToken.fingerprintForCacheScope())
                .apply()
        }
    }

    fun recordProfile(
        context: Context,
        steamId64: String,
        personaName: String,
        avatarUrl: String,
    ) {
        writeSafely(context, "record Steam Cloud profile") { prefs ->
            prefs.edit()
                .putString(KEY_STEAM_ID_64, steamId64.trim())
                .putString(KEY_PERSONA_NAME, personaName.trim())
                .putString(KEY_AVATAR_URL, avatarUrl.trim())
                .apply()
        }
    }

    fun recordManifestSuccess(context: Context, fetchedAtMs: Long) {
        writeSafely(context, "record Steam Cloud manifest success") { prefs ->
            prefs.edit()
                .putLong(KEY_LAST_MANIFEST_AT_MS, fetchedAtMs)
                .remove(KEY_LAST_ERROR)
                .apply()
        }
    }

    fun recordPullSuccess(context: Context, completedAtMs: Long) {
        writeSafely(context, "record Steam Cloud pull success") { prefs ->
            prefs.edit()
                .putLong(KEY_LAST_PULL_AT_MS, completedAtMs)
                .remove(KEY_LAST_ERROR)
                .apply()
        }
    }

    fun recordPushSuccess(context: Context, completedAtMs: Long) {
        writeSafely(context, "record Steam Cloud push success") { prefs ->
            prefs.edit()
                .putLong(KEY_LAST_PUSH_AT_MS, completedAtMs)
                .remove(KEY_LAST_ERROR)
                .apply()
        }
    }

    fun recordFailure(context: Context, errorMessage: String) {
        writeSafely(context, "record Steam Cloud failure") { prefs ->
            prefs.edit()
                .putString(KEY_LAST_ERROR, errorMessage.trim())
                .apply()
        }
    }

    fun clearGuardData(context: Context) {
        writeSafely(context, "clear Steam Cloud guard data") { prefs ->
            prefs.edit()
                .remove(KEY_GUARD_DATA)
                .apply()
        }
    }

    fun clear(context: Context) {
        clearEncryptedPrefs(context.applicationContext)
    }

    private inline fun <T> readSafely(
        context: Context,
        operation: String,
        block: (SharedPreferences) -> T,
    ): T? {
        val appContext = context.applicationContext
        val prefs = prefsOrNull(appContext) ?: return null
        return runCatching { block(prefs) }
            .getOrElse { error ->
                Log.w(TAG, "Unable to $operation; clearing stored Steam Cloud auth.", error)
                clearEncryptedPrefs(appContext)
                null
            }
    }

    private inline fun writeSafely(
        context: Context,
        operation: String,
        block: (SharedPreferences) -> Unit,
    ) {
        val appContext = context.applicationContext
        val prefs = prefsOrNull(appContext) ?: return
        runCatching { block(prefs) }
            .onFailure { error ->
                Log.w(TAG, "Unable to $operation; resetting encrypted storage and retrying.", error)
                clearEncryptedPrefs(appContext)
                prefsOrNull(appContext)?.let { recoveredPrefs ->
                    runCatching { block(recoveredPrefs) }
                        .onFailure { retryError ->
                            Log.w(TAG, "Unable to $operation after resetting encrypted storage.", retryError)
                        }
                }
            }
    }

    private fun prefsOrNull(context: Context): SharedPreferences? {
        val appContext = context.applicationContext
        return runCatching { createEncryptedPrefs(appContext) }
            .getOrElse { error ->
                Log.w(TAG, "Encrypted Steam Cloud auth storage unavailable; clearing stored auth.", error)
                clearEncryptedPrefs(appContext)
                runCatching { createEncryptedPrefs(appContext) }
                    .getOrElse { retryError ->
                        Log.w(TAG, "Encrypted Steam Cloud auth storage still unavailable after reset.", retryError)
                        null
                    }
            }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun clearEncryptedPrefs(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            createEncryptedPrefs(appContext).edit().clear().commit()
        }.onFailure { error ->
            Log.w(TAG, "Unable to clear encrypted Steam Cloud auth via SharedPreferences.", error)
        }
        if (!appContext.deleteSharedPreferences(PREFS_NAME)) {
            Log.w(TAG, "Encrypted Steam Cloud auth preferences were not present or could not be deleted.")
        }
    }

    private fun emptySnapshot(): AuthSnapshot = AuthSnapshot(
        accountName = "",
        refreshTokenConfigured = false,
        guardDataConfigured = false,
        steamId64 = "",
        personaName = "",
        avatarUrl = "",
        lastAuthAtMs = null,
        lastManifestAtMs = null,
        lastPullAtMs = null,
        lastPushAtMs = null,
        lastError = "",
    )

    private fun isValidSteamId64(value: String): Boolean =
        value.toULongOrNull()?.let { it > 0uL } == true

    private fun String.fingerprintForCacheScope(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun SharedPreferences.optionalLong(key: String): Long? {
        if (!contains(key)) {
            return null
        }
        return getLong(key, 0L).takeIf { it > 0L }
    }
}

internal fun reusableGuardDataForCredentials(
    savedAccountName: String,
    savedGuardData: String,
    requestedUsername: String,
): String {
    val normalizedGuardData = savedGuardData.trim()
    if (normalizedGuardData.isBlank()) {
        return ""
    }
    val normalizedSavedAccountName = savedAccountName.trim()
    val normalizedRequestedUsername = requestedUsername.trim()
    if (normalizedSavedAccountName.isBlank() || normalizedRequestedUsername.isBlank()) {
        return ""
    }
    return normalizedGuardData.takeIf {
        normalizedSavedAccountName.equals(normalizedRequestedUsername, ignoreCase = true)
    }.orEmpty()
}
