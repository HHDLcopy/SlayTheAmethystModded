package io.stamethyst.backend.presence

import android.content.Context
import android.provider.Settings
import io.stamethyst.BuildConfig
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
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
    const val HEARTBEAT_INTERVAL_MS = 240_000L
    const val OFFLINE_TIMEOUT_MS = 500_000L

    private const val RESPONSE_PREVIEW_LIMIT = 320
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    fun sendHeartbeat(
        context: Context,
        launchMode: String
    ): GamePresenceSummary {
        val identity = GamePresenceIdentity.resolve(context)
        val payload = JSONObject().apply {
            put("client_id", identity.clientId)
            put("device_id", identity.deviceId)
            put("id_type", identity.idType)
            put("state", "game")
            put("process", "game")
            put("launch_mode", launchMode)
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("sent_at", System.currentTimeMillis())
        }
        return postJson(
            path = "/api/presence/heartbeat",
            payload = payload
        )
    }

    fun fetchOnlineSummary(): GamePresenceSummary {
        val connection = openConnection("/api/presence/summary").apply {
            requestMethod = "GET"
            doInput = true
        }

        val responseCode = connection.responseCode
        val responseText = readResponseText(connection)
        if (responseCode !in 200..299) {
            throw IOException(
                "Online count request failed ($responseCode): ${summarizeResponseText(responseText)}"
            )
        }
        return parseSummary(responseText)
    }

    private fun postJson(
        path: String,
        payload: JSONObject
    ): GamePresenceSummary {
        val connection = openConnection(path).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        val responseCode = connection.responseCode
        val responseText = readResponseText(connection)
        if (responseCode !in 200..299) {
            throw IOException(
                "Presence heartbeat failed ($responseCode): ${summarizeResponseText(responseText)}"
            )
        }
        return parseSummary(responseText)
    }

    private fun openConnection(path: String): HttpURLConnection {
        val baseUrl = BuildConfig.FEEDBACK_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            throw IOException("Feedback base URL not configured in this build.")
        }

        return (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
        }
    }

    private fun parseSummary(responseText: String): GamePresenceSummary {
        val response = parseJsonObject(responseText)
            ?: throw IOException("Presence API returned an invalid response.")
        return GamePresenceSummary(
            online = response.optInt("online", 0),
            checkedAt = response.optString("checkedAt").trim().ifEmpty { null },
            heartbeatIntervalSeconds = response.optInt(
                "heartbeatIntervalSeconds",
                (HEARTBEAT_INTERVAL_MS / 1000L).toInt()
            ),
            offlineTimeoutSeconds = response.optInt(
                "offlineTimeoutSeconds",
                (OFFLINE_TIMEOUT_MS / 1000L).toInt()
            ),
            rawResponse = responseText
        )
    }

    private fun readResponseText(connection: HttpURLConnection): String {
        val stream = try {
            if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
        } catch (_: Throwable) {
            connection.errorStream
        } ?: return ""
        stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                BufferedReader(reader).use { buffered ->
                    return buffered.readText()
                }
            }
        }
    }

    private fun summarizeResponseText(responseText: String): String {
        val normalized = responseText.trim()
        if (normalized.isEmpty()) {
            return "empty response"
        }
        return if (normalized.length > RESPONSE_PREVIEW_LIMIT) {
            normalized.take(RESPONSE_PREVIEW_LIMIT) + "..."
        } else {
            normalized
        }
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
