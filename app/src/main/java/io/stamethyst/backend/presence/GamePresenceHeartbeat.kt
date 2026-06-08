package io.stamethyst.backend.presence

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.stamethyst.BuildConfig
import io.stamethyst.config.CloudControlConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONTokener

class GamePresenceHeartbeat(
    context: Context,
    private val launchMode: String
) {
    companion object {
        private const val TAG = "STS-Presence"
        private const val NORMAL_CLOSE_CODE = 1000
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var running = false

    @Volatile
    private var webSocket: WebSocket? = null

    private var reconnectAttempts = 0
    private var connectedWsUrl = ""

    private val cloudControlListener = {
        mainHandler.post {
            if (running && CloudControlConfig.heartbeatWsUrl() != connectedWsUrl) {
                Log.i(TAG, "Presence websocket config changed; reconnecting")
                reconnectNow()
            }
        }
        Unit
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!running) {
                return
            }
            sendPresenceFrame()
            scheduleNextHeartbeat()
        }
    }

    private val reconnectRunnable = Runnable {
        if (running && webSocket == null) {
            connect()
        }
    }

    fun start() {
        if (running) {
            return
        }
        running = true
        reconnectAttempts = 0
        Log.i(TAG, "Starting game presence websocket; launchMode=$launchMode")
        CloudControlConfig.addListener(cloudControlListener)
        connect()
    }

    fun stop() {
        running = false
        CloudControlConfig.removeListener(cloudControlListener)
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        closeWebSocket()
        Log.i(TAG, "Stopped game presence websocket")
    }

    private fun connect() {
        if (!running || webSocket != null) {
            return
        }

        val wsUrl = CloudControlConfig.heartbeatWsUrl().trim()
        if (wsUrl.isEmpty()) {
            Log.w(TAG, "Presence websocket URL is empty; retrying later")
            scheduleReconnect()
            return
        }

        connectedWsUrl = wsUrl
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .build()
        val nextWebSocket = client.newWebSocket(request, PresenceWebSocketListener(wsUrl))
        webSocket = nextWebSocket
    }

    private fun reconnectNow() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(heartbeatRunnable)
        closeWebSocket()
        reconnectAttempts = 0
        connect()
    }

    private fun closeWebSocket() {
        val socket = webSocket
        webSocket = null
        if (socket != null) {
            try {
                socket.close(NORMAL_CLOSE_CODE, "presence stopped")
            } catch (_: Throwable) {
                socket.cancel()
            }
        }
    }

    private fun scheduleNextHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        if (running) {
            mainHandler.postDelayed(
                heartbeatRunnable,
                CloudControlConfig.current().heartbeatIntervalMs
            )
        }
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        if (!running) {
            return
        }
        val delayMs = computeReconnectDelayMs()
        reconnectAttempts += 1
        Log.i(TAG, "Presence websocket reconnect scheduled in ${delayMs}ms")
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun computeReconnectDelayMs(): Long {
        val shift = reconnectAttempts.coerceIn(0, 6)
        val baseDelayMs = 1_000L shl shift
        return baseDelayMs.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun sendPresenceFrame() {
        val socket = webSocket
        if (!running || socket == null) {
            return
        }

        val payload = GamePresenceClient.buildHeartbeatPayload(appContext, launchMode)
        val sent = socket.send(payload.toString())
        if (!sent) {
            Log.w(TAG, "Presence websocket send queue rejected heartbeat")
            closeWebSocket()
            scheduleReconnect()
        }
    }

    private fun handleOpen(socket: WebSocket, wsUrl: String) {
        mainHandler.post {
            if (!running || socket !== webSocket) {
                socket.close(NORMAL_CLOSE_CODE, "stale presence websocket")
                return@post
            }
            reconnectAttempts = 0
            connectedWsUrl = wsUrl
            Log.i(TAG, "Presence websocket connected: $wsUrl")
            sendPresenceFrame()
            scheduleNextHeartbeat()
        }
    }

    private fun handleMessage(text: String) {
        val summary = parseAckSummary(text)
        if (summary != null) {
            Log.i(
                TAG,
                "Presence heartbeat accepted; online=${summary.online}, checkedAt=${summary.checkedAt ?: "unknown"}"
            )
        }
    }

    private fun handleClosed(socket: WebSocket) {
        mainHandler.post {
            if (socket === webSocket) {
                webSocket = null
                mainHandler.removeCallbacks(heartbeatRunnable)
                if (running) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun handleFailure(socket: WebSocket, error: Throwable) {
        Log.w(
            TAG,
            "Presence websocket failed: ${error.javaClass.simpleName}: ${error.message ?: "no message"}",
            error
        )
        mainHandler.post {
            if (socket === webSocket) {
                webSocket = null
                mainHandler.removeCallbacks(heartbeatRunnable)
                if (running) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun parseAckSummary(text: String): GamePresenceSummary? {
        val json = try {
            JSONTokener(text.trim()).nextValue() as? JSONObject
        } catch (_: Throwable) {
            null
        } ?: return null

        val type = json.optString("type").trim()
        if (type.isNotEmpty() && type != "presence_ack") {
            return null
        }
        return try {
            GamePresenceClient.parseSummary(text)
        } catch (_: Throwable) {
            null
        }
    }

    private inner class PresenceWebSocketListener(
        private val wsUrl: String
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            handleOpen(webSocket, wsUrl)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleClosed(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleFailure(webSocket, t)
        }
    }
}
