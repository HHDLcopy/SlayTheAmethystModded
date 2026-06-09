package io.stamethyst.backend.presence

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.stamethyst.BuildConfig
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.LauncherConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    private const val PRESENCE_HEARTBEAT_PATH = "/api/presence/heartbeat"
    private const val PRESENCE_WEBSOCKET_PATH = "/api/presence/ws"
    private const val METADATA_SEPARATOR = "\u001F"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val DEVICE_MODEL: String by lazy { deviceModel() }
    private val ANDROID_VERSION: String by lazy { androidVersion() }

    fun buildHeartbeatPayload(
        context: Context,
        launchMode: String,
        state: GamePresenceState
    ): JSONObject {
        val identity = GamePresenceIdentity.resolve(context)
        return buildHeartbeatPayload(
            identity = identity,
            launchMode = launchMode,
            state = state,
            playerName = LauncherConfig.readPlayerName(context)
        )
    }

    internal fun buildHeartbeatPayload(
        identity: GamePresenceIdentityPayload,
        launchMode: String,
        state: GamePresenceState,
        playerName: String
    ): JSONObject {
        return JSONObject().apply {
            put("type", "presence")
            put("client_id", identity.clientId)
            put("device_id", identity.deviceId)
            put("id_type", identity.idType)
            put("state", state.wireValue)
            put("launch_mode", launchMode)
            put("player_name", playerName)
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("device_model", DEVICE_MODEL)
            put("android_version", ANDROID_VERSION)
            put("sent_at", System.currentTimeMillis())
        }
    }

    internal fun buildMinimalHeartbeatPayload(
        identity: GamePresenceIdentityPayload,
        state: GamePresenceState
    ): JSONObject {
        return JSONObject().apply {
            put("type", "presence")
            put("client_id", identity.clientId)
            put("state", state.wireValue)
            put("sent_at", System.currentTimeMillis())
        }
    }

    internal fun buildHeartbeatMetadataSignature(
        identity: GamePresenceIdentityPayload,
        launchMode: String,
        playerName: String
    ): String = listOf(
        identity.clientId,
        identity.deviceId,
        identity.idType,
        launchMode,
        playerName,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE.toString(),
        DEVICE_MODEL,
        ANDROID_VERSION
    ).joinToString(METADATA_SEPARATOR)

    internal fun resolveIdentityPayload(context: Context): GamePresenceIdentityPayload =
        GamePresenceIdentity.resolve(context)

    fun sendHeartbeatAsync(
        client: OkHttpClient,
        context: Context,
        launchMode: String,
        state: GamePresenceState,
        callback: Callback
    ): Call {
        val payload = buildHeartbeatPayload(context, launchMode, state)
        val heartbeatUrl = resolveHttpHeartbeatUrl()
        if (heartbeatUrl.isEmpty()) {
            throw IOException("Presence heartbeat endpoint is not configured.")
        }
        val request = Request.Builder()
            .url(heartbeatUrl)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).also { call ->
            call.enqueue(callback)
        }
    }

    fun silentCallback(onComplete: () -> Unit = {}): Callback =
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                onComplete()
            }
        }

    fun buildHeartbeatWebSocketRequest(): Request =
        buildHeartbeatWebSocketRequest(CloudControlConfig.heartbeatWsUrl())

    internal fun buildHeartbeatWebSocketRequest(heartbeatWsUrl: String): Request =
        Request.Builder()
            .url(heartbeatWsUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .build()

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

    internal fun resolveHttpHeartbeatUrl(heartbeatWsUrl: String = CloudControlConfig.heartbeatWsUrl()): String {
        val normalized = heartbeatWsUrl.trim()
        if (normalized.isEmpty()) {
            return ""
        }

        val httpUrl = when {
            normalized.startsWith("wss://", ignoreCase = true) ->
                "https://" + normalized.substringAfter("://")
            normalized.startsWith("ws://", ignoreCase = true) ->
                "http://" + normalized.substringAfter("://")
            else -> normalized
        }
        val withoutTrailingSlash = httpUrl.trimEnd('/')
        return if (withoutTrailingSlash.endsWith(PRESENCE_WEBSOCKET_PATH, ignoreCase = true)) {
            withoutTrailingSlash.removeSuffix(PRESENCE_WEBSOCKET_PATH) + PRESENCE_HEARTBEAT_PATH
        } else {
            withoutTrailingSlash
        }
    }

    private fun deviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        if (manufacturer.isEmpty()) {
            return model
        }
        if (model.startsWith(manufacturer, ignoreCase = true)) {
            return model
        }
        return "$manufacturer $model".trim()
    }

    private fun androidVersion(): String =
        "Android ${Build.VERSION.RELEASE.orEmpty().trim()} (SDK ${Build.VERSION.SDK_INT})"
}

private data class GamePresenceIdentity(
    override val clientId: String,
    override val deviceId: String,
    override val idType: String
) : GamePresenceIdentityPayload {
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

internal interface GamePresenceIdentityPayload {
    val clientId: String
    val deviceId: String
    val idType: String
}
