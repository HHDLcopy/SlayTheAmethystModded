package io.stamethyst.config

import android.content.Context
import android.util.Log
import io.stamethyst.BuildConfig
import io.stamethyst.backend.github.GithubAcceleratedHttp
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

private const val DEFAULT_QQ_GROUP_NUMBER_VALUE = "1029305387"
private const val STEAM_DEPOT_KEY_BYTES = 32

data class CloudControlSettings(
    val heartbeatIntervalSeconds: Int,
    val heartbeatWsUrl: String,
    val qqGroupNumber: String = DEFAULT_QQ_GROUP_NUMBER_VALUE,
    val steamDepotKeys: List<CloudControlSteamDepotKey> = emptyList()
) {
    val heartbeatIntervalMs: Long
        get() = heartbeatIntervalSeconds * 1000L

    val qqGroupUrl: String
        get() = CloudControlConfig.qqGroupUrlFor(qqGroupNumber)

    fun steamDepotKeyBytes(appId: UInt, depotId: UInt): ByteArray? =
        steamDepotKeys
            .firstOrNull { key ->
                key.appId == appId.toLong() && key.depotId == depotId.toLong()
            }
            ?.decodeKeyBytes()
}

data class CloudControlSteamDepotKey(
    val appId: Long,
    val depotId: Long,
    val keyHex: String
) {
    fun decodeKeyBytes(): ByteArray? =
        CloudControlConfig.decodeSteamDepotKeyHex(keyHex)
}

data class CloudControlRemoteConfigText(
    val sourceDisplayName: String,
    val requestUrl: String,
    val rawText: String
)

object CloudControlConfig {
    const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30
    const val DEFAULT_HEARTBEAT_WS_URL = "wss://heartbeat.nas.apricityx.top:23163/api/presence/ws"
    const val DEFAULT_QQ_GROUP_NUMBER = DEFAULT_QQ_GROUP_NUMBER_VALUE
    const val MIN_HEARTBEAT_INTERVAL_SECONDS = 30
    const val MAX_HEARTBEAT_INTERVAL_SECONDS = 3_600

    private const val TAG = "STS-CloudControl"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private val USER_AGENT = "SlayTheAmethyst/${BuildConfig.VERSION_NAME}"
    private val QQ_GROUP_NUMBER_REGEX = Regex("[1-9][0-9]{4,19}")
    private val STEAM_DEPOT_KEY_HEX_REGEX = Regex("[0-9a-f]{64}")
    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    private val startupRefreshStarted = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var startupRefreshCompleted = false

    @Volatile
    private var currentSettings: CloudControlSettings = defaultSettings()

    @JvmStatic
    fun current(): CloudControlSettings = currentSettings

    @JvmStatic
    fun heartbeatIntervalSeconds(): Int = current().heartbeatIntervalSeconds

    @JvmStatic
    fun heartbeatWsUrl(): String = current().heartbeatWsUrl

    @JvmStatic
    fun qqGroupNumber(): String = current().qqGroupNumber

    @JvmStatic
    fun qqGroupUrl(): String = current().qqGroupUrl

    fun steamDepotKeyBytes(appId: UInt, depotId: UInt): ByteArray? =
        current().steamDepotKeyBytes(appId, depotId)

    @JvmStatic
    fun isStartupRefreshCompleted(): Boolean = startupRefreshCompleted

    @JvmStatic
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun defaultSettings(): CloudControlSettings =
        CloudControlSettings(
            heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            heartbeatWsUrl = defaultHeartbeatWsUrl(),
            qqGroupNumber = DEFAULT_QQ_GROUP_NUMBER
        )

    @JvmStatic
    fun defaultHeartbeatWsUrl(): String =
        DEFAULT_HEARTBEAT_WS_URL

    @JvmStatic
    fun qqGroupUrlFor(groupNumber: String): String =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" +
            groupNumber +
            "&card_type=group&source=qrcode"

    fun fetchRemoteConfigText(context: Context): CloudControlRemoteConfigText {
        val configUrl = BuildConfig.CLOUD_CONTROL_CONFIG_URL.trim()
        if (configUrl.isEmpty()) {
            throw IOException("Cloud control config URL is empty.")
        }
        return fetchRemoteConfigText(context.applicationContext, configUrl)
    }

    @JvmStatic
    fun refreshOnAppStart(context: Context) {
        if (!startupRefreshStarted.compareAndSet(false, true)) {
            return
        }
        currentSettings = defaultSettings()
        startupRefreshCompleted = false
        refreshAsync(context)
    }

    @JvmStatic
    fun refreshAsync(context: Context) {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        Thread({
            try {
                val configUrl = BuildConfig.CLOUD_CONTROL_CONFIG_URL.trim()
                if (configUrl.isEmpty()) {
                    Log.i(TAG, "Cloud control config URL is empty; using defaults")
                    updateCurrentSettings(defaultSettings())
                    return@Thread
                }

                val fetched = fetchRemoteSettings(appContext, configUrl)
                updateCurrentSettings(fetched)
                Log.i(
                    TAG,
                    "Cloud control config loaded; heartbeatIntervalSeconds=" +
                        "${fetched.heartbeatIntervalSeconds}, heartbeatWsUrl=" +
                        "${fetched.heartbeatWsUrl}, qqGroupNumber=" +
                        "${fetched.qqGroupNumber}, steamDepotKeys=" +
                        fetched.steamDepotKeys.size
                )
            } catch (error: Throwable) {
                updateCurrentSettings(defaultSettings())
                Log.w(
                    TAG,
                    "Cloud control config fetch failed; using defaults: " +
                        "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
                )
            } finally {
                refreshing.set(false)
            }
        }, "STS-CloudControlFetch").apply {
            isDaemon = true
            start()
        }
    }

    @JvmStatic
    fun refreshBlocking(context: Context): CloudControlSettings {
        val appContext = context.applicationContext
        return try {
            val configUrl = BuildConfig.CLOUD_CONTROL_CONFIG_URL.trim()
            if (configUrl.isEmpty()) {
                Log.i(TAG, "Cloud control config URL is empty; keeping current settings")
                return currentSettings
            }
            val fetched = fetchRemoteSettings(appContext, configUrl)
            updateCurrentSettings(fetched)
            fetched
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Cloud control config fetch failed; keeping current settings: " +
                    "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            )
            currentSettings
        }
    }

    private fun updateCurrentSettings(settings: CloudControlSettings) {
        currentSettings = settings
        startupRefreshCompleted = true
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun parseSettings(
        responseText: String,
        defaults: CloudControlSettings = defaultSettings()
    ): CloudControlSettings? {
        val root = parseJsonObject(responseText) ?: return null
        val heartbeatObject = root.optJSONObject("heartbeat")
        val qqGroupObject = root.optJSONObject("qqGroup")
            ?: root.optJSONObject("officialQqGroup")
        val qqObject = root.optJSONObject("qq")

        val intervalSeconds = normalizeHeartbeatIntervalSeconds(
            firstPositiveInt(
                root,
                heartbeatObject,
                "heartbeatIntervalSeconds",
                "heartbeatFrequencySeconds",
                "presenceHeartbeatIntervalSeconds",
                "intervalSeconds",
                "heartbeat_interval_seconds",
                "heartbeat_frequency_seconds"
            ) ?: defaults.heartbeatIntervalSeconds
        )
        val wsUrl = normalizeHeartbeatWsUrl(
            firstNonBlankString(
                root,
                heartbeatObject,
                "heartbeatWsUrl",
                "presenceHeartbeatWsUrl",
                "heartbeatWebSocketUrl",
                "presenceHeartbeatWebSocketUrl",
                "wsUrl",
                "websocketUrl",
                "heartbeat_ws_url",
                "presence_heartbeat_ws_url",
                "heartbeat_websocket_url"
            ) ?: defaults.heartbeatWsUrl,
            defaults.heartbeatWsUrl
        )
        val qqGroupNumber = normalizeQqGroupNumber(
            firstNonBlankString(
                root,
                "qqGroupNumber",
                "officialQqGroupNumber",
                "qq_group_number",
                "official_qq_group_number"
            )
                ?: firstNonBlankString(
                    qqGroupObject,
                    "number",
                    "groupNumber",
                    "qqGroupNumber",
                    "uin"
                )
                ?: firstNonBlankString(
                    qqObject,
                    "groupNumber",
                    "qqGroupNumber",
                    "number",
                    "uin"
                )
                ?: defaults.qqGroupNumber,
            defaults.qqGroupNumber
        )
        val steamDepotKeys = parseSteamDepotKeys(root)
            .ifEmpty { defaults.steamDepotKeys }

        return CloudControlSettings(
            heartbeatIntervalSeconds = intervalSeconds,
            heartbeatWsUrl = wsUrl,
            qqGroupNumber = qqGroupNumber,
            steamDepotKeys = steamDepotKeys
        )
    }

    private fun fetchRemoteSettings(
        context: Context,
        configUrl: String
    ): CloudControlSettings {
        val responseText = fetchRemoteConfigText(context, configUrl).rawText

        return parseSettings(responseText)
            ?: throw IOException("Cloud control response is not a JSON object.")
    }

    private fun fetchRemoteConfigText(
        context: Context,
        configUrl: String
    ): CloudControlRemoteConfigText {
        val clients = GithubAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true
        )

        if (UpdateSource.isMirrorableGithubUrl(configUrl)) {
            val preferredSource = UpdateMirrorManager.current(context)
            val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
            return GithubMirrorFallback.run(
                preferredUserSource = preferredSource,
                bypassAcceleratedLinks = bypassAcceleratedLinks
            ) { source ->
                val requestUrl = source.buildUrl(configUrl)
                CloudControlRemoteConfigText(
                    sourceDisplayName = source.displayName,
                    requestUrl = requestUrl,
                    rawText = requestText(
                        client = clients.pick(source.usesGithubAcceleration),
                        requestUrl = requestUrl
                    )
                )
            }.value
        }

        return CloudControlRemoteConfigText(
            sourceDisplayName = "Direct",
            requestUrl = configUrl,
            rawText = requestText(
                client = clients.plainClient,
                requestUrl = configUrl
            )
        )
    }

    private fun requestText(
        client: OkHttpClient,
        requestUrl: String
    ): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body.bytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun normalizeHeartbeatIntervalSeconds(value: Int): Int =
        value.coerceIn(
            MIN_HEARTBEAT_INTERVAL_SECONDS,
            MAX_HEARTBEAT_INTERVAL_SECONDS
        )

    private fun normalizeQqGroupNumber(
        value: String,
        fallback: String = DEFAULT_QQ_GROUP_NUMBER
    ): String {
        val normalized = value.trim()
        return if (QQ_GROUP_NUMBER_REGEX.matches(normalized)) {
            normalized
        } else {
            fallback
        }
    }

    private fun parseSteamDepotKeys(root: JSONObject): List<CloudControlSteamDepotKey> {
        val steamObject = root.optJSONObject("steam")
        val keys = ArrayList<CloudControlSteamDepotKey>()
        parseSteamDepotKeyArray(root.optJSONArray("steamDepotKeys"), keys)
        parseSteamDepotKeyArray(root.optJSONArray("depotKeys"), keys)
        parseSteamDepotKeyArray(steamObject?.optJSONArray("depotKeys"), keys)
        parseSteamDepotKeyArray(steamObject?.optJSONArray("steamDepotKeys"), keys)
        return keys.distinctBy { key -> key.appId to key.depotId }
    }

    private fun parseSteamDepotKeyArray(
        array: JSONArray?,
        output: MutableList<CloudControlSteamDepotKey>
    ) {
        if (array == null) {
            return
        }
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val appId = firstPositiveLong(
                item,
                "appId",
                "appID",
                "app_id",
                "app"
            ) ?: continue
            val depotId = firstPositiveLong(
                item,
                "depotId",
                "depotID",
                "depot_id",
                "depot"
            ) ?: continue
            val keyHex = normalizeSteamDepotKeyHex(
                firstNonBlankString(
                    item,
                    "keyHex",
                    "depotKeyHex",
                    "depot_key_hex",
                    "hex",
                    "key"
                )
            ) ?: normalizeSteamDepotKeyBase64(
                firstNonBlankString(
                    item,
                    "keyBase64",
                    "depotKeyBase64",
                    "depot_key_base64",
                    "base64"
                )
            ) ?: continue
            output += CloudControlSteamDepotKey(
                appId = appId,
                depotId = depotId,
                keyHex = keyHex
            )
        }
    }

    private fun firstPositiveLong(
        json: JSONObject?,
        vararg names: String
    ): Long? {
        if (json == null) {
            return null
        }
        for (name in names) {
            val value = optionalPositiveLong(json, name)
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun optionalPositiveLong(json: JSONObject, name: String): Long? {
        if (!json.has(name)) {
            return null
        }
        val rawValue = json.opt(name) ?: return null
        val parsed = when (rawValue) {
            is Number -> rawValue.toLong()
            is String -> rawValue.trim().toLongOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0L }
    }

    private fun normalizeSteamDepotKeyHex(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.removePrefix("0x")
            ?.removePrefix("0X")
            ?.filterNot(Char::isWhitespace)
            ?.lowercase(Locale.ROOT)
            ?: return null
        return normalized.takeIf { STEAM_DEPOT_KEY_HEX_REGEX.matches(it) }
    }

    private fun normalizeSteamDepotKeyBase64(value: String?): String? =
        runCatching {
            val decoded = Base64.getDecoder().decode(value?.trim().orEmpty())
            decoded.takeIf { it.size == STEAM_DEPOT_KEY_BYTES }?.toHexString()
        }.getOrNull()

    internal fun decodeSteamDepotKeyHex(value: String): ByteArray? {
        val normalized = normalizeSteamDepotKeyHex(value) ?: return null
        return ByteArray(STEAM_DEPOT_KEY_BYTES) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val unsigned = this[index].toInt() and 0xff
            chars[index * 2] = HEX_DIGITS[unsigned ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0f]
        }
        return String(chars)
    }

    private fun normalizeHttpUrl(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        if (normalized.startsWith("/")) {
            return BuildConfig.FEEDBACK_BASE_URL.trim().trimEnd('/') + normalized
        }
        val parsed = try {
            URL(normalized)
        } catch (_: Throwable) {
            return null
        }
        return when (parsed.protocol.lowercase()) {
            "http", "https" -> normalized
            else -> null
        }
    }

    private fun normalizeHeartbeatWsUrl(
        value: String,
        fallback: String = defaultHeartbeatWsUrl()
    ): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return fallback
        }
        if (normalized.startsWith("/")) {
            return httpUrlToWebSocketUrl(BuildConfig.FEEDBACK_BASE_URL.trim().trimEnd('/') + normalized)
        }
        val scheme = try {
            URI(normalized).scheme?.lowercase().orEmpty()
        } catch (_: Throwable) {
            return fallback
        }
        return when (scheme) {
            "ws", "wss" -> if (hasNetworkHost(normalized)) normalized else fallback
            "http", "https" -> normalizeHttpUrl(normalized)
                ?.let(::httpUrlToWebSocketUrl)
                ?: fallback
            else -> fallback
        }
    }

    private fun hasNetworkHost(value: String): Boolean =
        try {
            !URI(value).host.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }

    private fun httpUrlToWebSocketUrl(value: String): String =
        when {
            value.startsWith("https://", ignoreCase = true) ->
                "wss://" + value.substringAfter("://")
            value.startsWith("http://", ignoreCase = true) ->
                "ws://" + value.substringAfter("://")
            else -> value
        }

    private fun firstPositiveInt(
        root: JSONObject,
        nested: JSONObject?,
        vararg names: String
    ): Int? {
        for (name in names) {
            val rootValue = optionalPositiveInt(root, name)
            if (rootValue != null) {
                return rootValue
            }
            val nestedValue = if (nested != null) {
                optionalPositiveInt(nested, name)
            } else {
                null
            }
            if (nestedValue != null) {
                return nestedValue
            }
        }
        return null
    }

    private fun optionalPositiveInt(json: JSONObject, name: String): Int? {
        if (!json.has(name)) {
            return null
        }
        val rawValue = json.opt(name) ?: return null
        val parsed = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.trim().toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0 }
    }

    private fun firstNonBlankString(
        root: JSONObject,
        nested: JSONObject?,
        vararg names: String
    ): String? {
        for (name in names) {
            val rootValue = optionalNonBlankString(root, name)
            if (rootValue != null) {
                return rootValue
            }
            val nestedValue = if (nested != null) {
                optionalNonBlankString(nested, name)
            } else {
                null
            }
            if (nestedValue != null) {
                return nestedValue
            }
        }
        return null
    }

    private fun firstNonBlankString(
        json: JSONObject?,
        vararg names: String
    ): String? {
        if (json == null) {
            return null
        }
        for (name in names) {
            val value = optionalNonBlankString(json, name)
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun optionalNonBlankString(json: JSONObject, name: String): String? {
        if (!json.has(name)) {
            return null
        }
        return json.optString(name).trim().ifEmpty { null }
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
