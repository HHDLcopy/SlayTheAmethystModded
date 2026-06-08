package io.stamethyst.backend.presence

import android.content.Context
import android.provider.Settings
import io.stamethyst.BuildConfig
import io.stamethyst.config.LauncherConfig
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class GamePresenceSummary(
    val online: Int,
    val checkedAt: String?,
    val heartbeatIntervalSeconds: Int,
    val offlineTimeoutSeconds: Int,
    val rawResponse: String
)

object GamePresenceClient {
    const val DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000L
    const val OFFLINE_TIMEOUT_MS = 90_000L

    fun buildHeartbeatPayload(
        context: Context,
        launchMode: String
    ): JSONObject {
        val identity = GamePresenceIdentity.resolve(context)
        return JSONObject().apply {
            put("type", "presence")
            put("client_id", identity.clientId)
            put("device_id", identity.deviceId)
            put("id_type", identity.idType)
            put("state", "game")
            put("launch_mode", launchMode)
            put("player_name", LauncherConfig.readPlayerName(context))
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("sent_at", System.currentTimeMillis())
        }
    }

    fun parseSummary(responseText: String): GamePresenceSummary {
        val response = parseJsonObject(responseText)
            ?: throw IOException("Presence API returned an invalid response.")
        return GamePresenceSummary(
            online = response.optInt("online", 0),
            checkedAt = response.optString("checkedAt").trim().ifEmpty { null },
            heartbeatIntervalSeconds = response.optInt(
                "heartbeatIntervalSeconds",
                (DEFAULT_HEARTBEAT_INTERVAL_MS / 1000L).toInt()
            ),
            offlineTimeoutSeconds = response.optInt(
                "offlineTimeoutSeconds",
                (OFFLINE_TIMEOUT_MS / 1000L).toInt()
            ),
            rawResponse = responseText
        )
    }

    private fun parseJsonObject(text: String): JSONObject? {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return null
        }
        return try {
            JSONTokener(normalized).nextValue() as? JSONObject
        } catch (_: Throwable) {
            null
        }
    }
}

private data class GamePresenceIdentity(
    val clientId: String,
    val deviceId: String,
    val idType: String
) {
    companion object {
        private const val PREFS_NAME = "game_presence_identity"
        private const val KEY_INSTALL_ID = "install_id"
        private const val ANDROID_ID_BUG_VALUE = "9774d56d682e549c"

        fun resolve(context: Context): GamePresenceIdentity {
            val androidId = resolveAndroidId(context)
            if (!androidId.isNullOrBlank()) {
                val deviceId = sha256Hex("android_id:$androidId")
                return GamePresenceIdentity(
                    clientId = "android:$deviceId",
                    deviceId = deviceId,
                    idType = "android_id_sha256"
                )
            }

            val installId = resolveInstallId(context)
            val deviceId = sha256Hex("install_id:$installId")
            return GamePresenceIdentity(
                clientId = "install:$deviceId",
                deviceId = deviceId,
                idType = "install_id_sha256"
            )
        }

        private fun resolveAndroidId(context: Context): String? {
            return try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )?.trim()
                    ?.lowercase(Locale.US)
                    ?.takeIf { it.isNotEmpty() && it != ANDROID_ID_BUG_VALUE }
            } catch (_: Throwable) {
                null
            }
        }

        private fun resolveInstallId(context: Context): String {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_INSTALL_ID, null)?.trim()
            if (!existing.isNullOrEmpty()) {
                return existing
            }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
            return generated
        }

        private fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { byte ->
                String.format(Locale.US, "%02x", byte.toInt() and 0xff)
            }
        }
    }
}
