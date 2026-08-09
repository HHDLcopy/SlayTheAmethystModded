package io.stamethyst.backend.steamcloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.stamethyst.R
import io.stamethyst.backend.presence.GamePresenceState
import io.stamethyst.backend.presence.GamePresenceStateMarker
import io.stamethyst.config.LauncherConfig
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental CM presence bridge. It is deliberately separate from Steam Cloud sync. */
class SteamGamePresenceService : Service() {
    companion object {
        private const val TAG = "SteamGamePresenceService"
        private const val CHANNEL_ID = "steam_game_presence"
        private const val NOTIFICATION_ID = 646572
        private const val ACTION_START = "io.stamethyst.action.STEAM_GAME_PRESENCE_START"
        private const val APP_ID = 646570L
        private const val STARTUP_TIMEOUT_MS = 60_000L

        fun startIfEnabled(context: Context) {
            val appContext = context.applicationContext
            val enabled = LauncherConfig.isSteamGamePresenceEnabled(appContext)
            val state = GamePresenceStateMarker.readCurrentState(appContext)
            val auth = SteamCloudAuthStore.readAuthMaterial(appContext)
            val skipReason = when {
                !enabled -> "feature_disabled"
                state.state != GamePresenceState.Game -> "game_state_not_active"
                auth == null -> "steam_auth_material_incomplete"
                else -> ""
            }
            if (skipReason.isNotEmpty()) {
                val now = System.currentTimeMillis()
                SteamGamePresenceDiagnosticsStore.writeSummary(
                    appContext,
                    "SKIPPED",
                    auth?.accountName.orEmpty(),
                    now,
                    now,
                    false,
                    false,
                    null,
                    null,
                    skipReason,
                )
                return
            }
            val requestedAtMs = System.currentTimeMillis()
            SteamGamePresenceDiagnosticsStore.writeSummary(
                appContext,
                "START_REQUESTED",
                auth!!.accountName,
                requestedAtMs,
                requestedAtMs,
                false,
                false,
                null,
                null,
                "foreground_service_start_requested",
            )
            val intent = Intent(appContext, SteamGamePresenceService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "Unable to start experimental Steam presence service.", error)
                SteamGamePresenceDiagnosticsStore.writeSummary(
                    appContext,
                    "FAILED",
                    auth.accountName,
                    requestedAtMs,
                    System.currentTimeMillis(),
                    false,
                    false,
                    error,
                    null,
                    "foreground_service_start_failed",
                )
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, SteamGamePresenceService::class.java))
        }
    }

    private val stopRequested = AtomicBoolean(false)
    private val terminalSummaryWritten = AtomicBoolean(false)
    private val clientLock = Any()
    @Volatile private var workerThread: Thread? = null
    @Volatile private var startupWatchdogThread: Thread? = null
    @Volatile private var client: SteamCloudClient? = null
    @Volatile private var loggedOn = false
    @Volatile private var appIdSent = false
    @Volatile private var clearStateSent = false
    @Volatile private var operationStartedAtMs = 0L
    @Volatile private var operationAccountName = ""
    @Volatile private var startupTimeoutFailure: TimeoutException? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        val acceptedAtMs = System.currentTimeMillis()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to enter foreground for experimental Steam presence service.", error)
            SteamGamePresenceDiagnosticsStore.writeSummary(
                applicationContext,
                "FAILED",
                SteamCloudAuthStore.readAuthMaterial(applicationContext)?.accountName.orEmpty(),
                acceptedAtMs,
                System.currentTimeMillis(),
                false,
                false,
                error,
                null,
                "foreground_notification_failed",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (workerThread?.isAlive == true) {
            return START_NOT_STICKY
        }
        stopRequested.set(false)
        terminalSummaryWritten.set(false)
        startupTimeoutFailure = null
        operationStartedAtMs = acceptedAtMs
        operationAccountName = SteamCloudAuthStore.readAuthMaterial(applicationContext)?.accountName.orEmpty()
        SteamGamePresenceDiagnosticsStore.writeSummary(
            applicationContext,
            "STARTED",
            operationAccountName,
            acceptedAtMs,
            acceptedAtMs,
            false,
            false,
            null,
            null,
            "foreground_service_started_watchdog_armed",
        )
        scheduleStartupTimeout(operationStartedAtMs)
        val thread = Thread(::runPresence, "STS-SteamGamePresence")
        workerThread = thread
        thread.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        cancelStartupTimeout()
        workerThread?.interrupt()
        closeClient(clearState = true)
        workerThread = null
        super.onDestroy()
    }

    private fun runPresence() {
        if (operationStartedAtMs <= 0L) operationStartedAtMs = System.currentTimeMillis()
        val auth = SteamCloudAuthStore.readAuthMaterial(applicationContext)
        val gameActive = isStillGameActive()
        if (auth == null || !gameActive) {
            writeTerminalSummary(
                "SKIPPED",
                operationStartedAtMs,
                System.currentTimeMillis(),
                false,
                false,
                null,
                null,
                when {
                    auth == null -> "steam_auth_material_incomplete"
                    else -> "game_state_not_active"
                },
            )
            stopSelf()
            return
        }
        operationAccountName = auth.accountName
        val steamClient = SteamCloudClient(applicationContext)
        client = steamClient
        var failure: Throwable? = null
        try {
            steamClient.beginOperationDiagnostics("game_presence", auth.accountName, auth.guardData.isNotBlank())
            steamClient.start()
            check(!stopRequested.get() && isStillGameActive()) { "Game is no longer active." }
            steamClient.logOnWithRefreshToken(auth.accountName, auth.refreshToken, auth.steamId64)
            check(!stopRequested.get() && isStillGameActive()) { "Game is no longer active." }
            loggedOn = true
            cancelStartupTimeout()
            steamClient.setPersonaOnline()
            steamClient.setGamePlayedAppId(APP_ID)
            appIdSent = true
            SteamGamePresenceDiagnosticsStore.writeSummary(
                applicationContext,
                "RUNNING",
                operationAccountName,
                operationStartedAtMs,
                System.currentTimeMillis(),
                true,
                false,
                null,
                steamClient.snapshotDiagnostics(),
                "steam_presence_app_state_sent",
            )
            Log.i(TAG, "Reported Steam game AppID $APP_ID through CM.")
            while (!stopRequested.get() && isStillGameActive()) {
                Thread.sleep(15_000L)
            }
        } catch (error: Throwable) {
            if (startupTimeoutFailure != null) {
                failure = startupTimeoutFailure
            } else if (error is InterruptedException && stopRequested.get()) {
                Thread.currentThread().interrupt()
            } else {
                failure = error
            }
            if (!stopRequested.get()) Log.w(TAG, "Experimental Steam presence stopped.", error)
        } finally {
            cancelStartupTimeout()
            closeClient(clearState = true)
            writeTerminalSummary(
                if (failure == null) "STOPPED" else "FAILED",
                operationStartedAtMs,
                System.currentTimeMillis(),
                appIdSent,
                clearStateSent,
                failure,
                steamClient.snapshotDiagnostics(),
            )
            stopSelf()
        }
    }

    private fun isStillGameActive(): Boolean {
        return GamePresenceStateMarker.readCurrentState(applicationContext).state == GamePresenceState.Game &&
            LauncherConfig.isSteamGamePresenceEnabled(applicationContext)
    }

    private fun scheduleStartupTimeout(startedAtMs: Long) {
        val watchdogThread = Thread({
            Log.i(TAG, "Steam presence startup watchdog started for operation=$startedAtMs.")
            val deadline = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS
            while (!loggedOn && !stopRequested.get() && operationStartedAtMs == startedAtMs) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    Thread.sleep(minOf(1_000L, remaining))
                } catch (_: InterruptedException) {
                    Log.i(TAG, "Steam presence startup watchdog interrupted for operation=$startedAtMs.")
                    return@Thread
                }
            }
            if (loggedOn || stopRequested.get() || operationStartedAtMs != startedAtMs) {
                Log.i(
                    TAG,
                    "Steam presence startup watchdog cancelled for operation=$startedAtMs " +
                        "loggedOn=$loggedOn stopped=${stopRequested.get()} currentOperation=$operationStartedAtMs.",
                )
                return@Thread
            }

            val failure = TimeoutException("Steam presence did not complete logon within ${STARTUP_TIMEOUT_MS / 1000L}s.")
            Log.w(TAG, "Steam presence startup watchdog timed out for operation=$startedAtMs.")
            startupTimeoutFailure = failure
            stopRequested.set(true)
            workerThread?.interrupt()
            writeTerminalSummary(
                "FAILED",
                startedAtMs,
                System.currentTimeMillis(),
                appIdSent,
                clearStateSent,
                failure,
                null,
                "steam_presence_startup_timeout",
            )
            closeClient(clearState = false)
            stopSelf()
        }, "STS-SteamPresenceWatchdog")
        watchdogThread.isDaemon = true
        startupWatchdogThread = watchdogThread
        watchdogThread.start()
    }

    private fun cancelStartupTimeout() {
        startupWatchdogThread?.interrupt()
        startupWatchdogThread = null
    }

    private fun writeTerminalSummary(
        outcome: String,
        startedAtMs: Long,
        completedAtMs: Long,
        appStateMessageSent: Boolean,
        clearStateMessageSent: Boolean,
        failure: Throwable?,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
        detail: String = "",
    ) {
        if (!terminalSummaryWritten.compareAndSet(false, true)) return
        SteamGamePresenceDiagnosticsStore.writeSummary(
            applicationContext,
            outcome,
            operationAccountName,
            startedAtMs,
            completedAtMs,
            appStateMessageSent,
            clearStateMessageSent,
            failure,
            diagnostics,
            detail,
        )
    }

    private fun closeClient(clearState: Boolean) {
        synchronized(clientLock) {
            val activeClient = client ?: return
            try {
                if (clearState && loggedOn) {
                    activeClient.setGamePlayedAppId(0L)
                    clearStateSent = true
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to clear Steam game state before disconnect.", error)
            } finally {
                loggedOn = false
                client = null
                activeClient.close()
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dock_game)
            .setContentTitle(getString(R.string.settings_steam_game_presence_title))
            .setContentText(getString(R.string.settings_steam_game_presence_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Steam 游戏状态", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
