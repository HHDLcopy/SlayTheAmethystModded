package io.stamethyst.config

import android.content.Context
import android.util.Log
import io.stamethyst.BuildConfig
import io.stamethyst.backend.github.GithubAcceleratedHttp
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

data class CloudControlSettings(
    val heartbeatIntervalSeconds: Int,
    val heartbeatWsUrl: String
) {
    val heartbeatIntervalMs: Long
        get() = heartbeatIntervalSeconds * 1000L
}

data class CloudControlRemoteConfigText(
    val sourceDisplayName: String,
    val requestUrl: String,
    val rawText: String
)

object CloudControlConfig {
    const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30
    const val MIN_HEARTBEAT_INTERVAL_SECONDS = 30
    const val MAX_HEARTBEAT_INTERVAL_SECONDS = 3_600

    private const val TAG = "STS-CloudControl"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private val USER_AGENT = "SlayTheAmethyst/${BuildConfig.VERSION_NAME}"

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
            heartbeatWsUrl = defaultHeartbeatWsUrl()
        )

    @JvmStatic
    fun defaultHeartbeatWsUrl(): String =
        ""

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
                        fetched.heartbeatWsUrl
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

        return CloudControlSettings(
            heartbeatIntervalSeconds = intervalSeconds,
            heartbeatWsUrl = wsUrl
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
