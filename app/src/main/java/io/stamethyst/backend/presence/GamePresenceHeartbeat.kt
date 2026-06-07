package io.stamethyst.backend.presence

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class GamePresenceHeartbeat(
    context: Context,
    private val launchMode: String
) {
    companion object {
        private const val TAG = "STS-Presence"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sending = AtomicBoolean(false)

    @Volatile
    private var running = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!running) {
                return
            }
            sendHeartbeatAsync()
            scheduleNext()
        }
    }

    fun start() {
        if (running) {
            return
        }
        running = true
        Log.i(
            TAG,
            "Starting game presence heartbeat; launchMode=$launchMode"
        )
        sendHeartbeatAsync()
        scheduleNext()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        Log.i(TAG, "Stopped game presence heartbeat")
    }

    private fun scheduleNext() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        if (running) {
            mainHandler.postDelayed(
                heartbeatRunnable,
                GamePresenceClient.HEARTBEAT_INTERVAL_MS
            )
        }
    }

    private fun sendHeartbeatAsync() {
        if (!sending.compareAndSet(false, true)) {
            return
        }

        Thread({
            try {
                val summary = GamePresenceClient.sendHeartbeat(appContext, launchMode)
                Log.i(
                    TAG,
                    "Presence heartbeat accepted; online=${summary.online}, checkedAt=${summary.checkedAt ?: "unknown"}"
                )
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Presence heartbeat failed: ${error.javaClass.simpleName}: ${error.message ?: "no message"}",
                    error
                )
            } finally {
                sending.set(false)
            }
        }, "STS-GamePresenceHeartbeat").apply {
            isDaemon = true
            start()
        }
    }
}
