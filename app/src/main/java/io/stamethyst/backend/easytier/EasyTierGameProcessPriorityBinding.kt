package io.stamethyst.backend.easytier

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Raises the `:easytier` process priority to match the `:game` process for as long as the game
 * Activity is alive.
 *
 * Without this, `:easytier` only ever reaches `PERCEPTIBLE_APP_ADJ` (its foreground service), while
 * a visible `:game` Activity sits at `FOREGROUND_APP_ADJ`. lowmemorykiller walks oom_adj from high
 * to low, so on low-memory devices `:easytier` is always reclaimed *before* the game: the game keeps
 * running and the virtual network silently dies mid-session.
 *
 * `BIND_IMPORTANT` tells ActivityManager to hoist the bound process into the client process's
 * priority band, putting both in the same LMK bucket. The game is then reclaimed first, and a dead
 * game makes the virtual network moot anyway. That trades an invisible connectivity failure for a
 * visible, already-fatal one.
 *
 * The binding is purely a priority hint. It carries no IPC contract and never starts or stops a
 * session: [EasyTierProcessService] is still driven by `startForegroundService`.
 *
 * Two flags are deliberately omitted:
 *  - `BIND_AUTO_CREATE`, so binding cannot spawn an EasyTier service nobody asked to connect, and
 *    cannot keep one alive past `stopSelf`. Spending memory on an idle `:easytier` would work
 *    against the very devices this fix targets.
 *  - `BIND_ADJUST_WITH_ACTIVITY`, which would let the hoist decay whenever the game Activity is not
 *    visible. `:easytier` then follows `:game`'s own priority instead, which is the intended shape.
 *
 * Because auto-create is off, `bindService` only succeeds while a session is already running, and
 * the binding drops when that session ends. Both cases are recovered by observing
 * [EasyTierProcessService.ACTION_CONNECTION_EVENT], which the service already broadcasts on every
 * status poll. That keeps the retry self-contained here instead of coupling it to the launcher view
 * model.
 */
internal object EasyTierGameProcessPriorityBinding {
    private const val TAG = "EasyTierPriorityBind"

    /** The flag that performs the priority hoist. See the class docs for the omissions. */
    const val BIND_FLAGS: Int = Context.BIND_IMPORTANT

    /** True while a [ServiceConnection] is registered and therefore still owes an unbind. */
    private var connectionRegistered = false

    private var connectionEventReceiver: BroadcastReceiver? = null

    private var appContext: Context? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Bound :easytier from the game process to align LMK priority.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Reached when the session ended or the service process died. Without BIND_AUTO_CREATE
            // the rebind semantics are not guaranteed, so drop the stale record and let the next
            // connection event bind again instead of trusting an implicit reconnect.
            Log.w(TAG, "Lost the :easytier priority binding; will rebind on the next session event.")
            releaseConnection()
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, ":easytier priority binding died; will rebind on the next session event.")
            releaseConnection()
        }

        override fun onNullBinding(name: ComponentName?) {
            // ActivityManager still holds a live binding record, so the hoist applies even though
            // no interface was returned.
            Log.i(TAG, "Bound :easytier with a null binder; the priority hoist still applies.")
        }
    }

    /**
     * Starts holding `:easytier` at game priority. Must be called from the `:game` process; binding
     * from the launcher process would only hoist while the launcher UI is visible, which is exactly
     * the window where it does not matter.
     */
    fun attach(context: Context) {
        val application = context.applicationContext
        synchronized(this) {
            appContext = application
            registerConnectionEventReceiverLocked(application)
            bindLocked(application)
        }
    }

    fun detach(context: Context) {
        val application = context.applicationContext
        synchronized(this) {
            connectionEventReceiver?.let { receiver ->
                connectionEventReceiver = null
                runCatching { application.unregisterReceiver(receiver) }
            }
            unbindLocked(application)
            appContext = null
        }
    }

    private fun releaseConnection() {
        synchronized(this) {
            val application = appContext ?: return
            unbindLocked(application)
        }
    }

    private fun bindLocked(application: Context) {
        if (connectionRegistered) {
            return
        }
        val intent = Intent(application, EasyTierProcessService::class.java)
        val bound = runCatching { application.bindService(intent, connection, BIND_FLAGS) }
            .onFailure { error ->
                Log.w(TAG, "Failed to bind :easytier for priority alignment.", error)
            }
            .getOrDefault(false)
        // A false return is the normal outcome while no session is running. ActivityManager still
        // registered the connection in that case, so it has to be released either way.
        connectionRegistered = true
        if (!bound) {
            unbindLocked(application)
        }
    }

    private fun unbindLocked(application: Context) {
        if (!connectionRegistered) {
            return
        }
        connectionRegistered = false
        runCatching { application.unbindService(connection) }
            .onFailure { error ->
                Log.w(TAG, "Failed to unbind the :easytier priority binding.", error)
            }
    }

    private fun registerConnectionEventReceiverLocked(application: Context) {
        if (connectionEventReceiver != null) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != EasyTierProcessService.ACTION_CONNECTION_EVENT) {
                    return
                }
                // Any connection event proves the service is running, which is the precondition
                // bindService needs without BIND_AUTO_CREATE.
                synchronized(this@EasyTierGameProcessPriorityBinding) {
                    if (connectionEventReceiver != null) {
                        bindLocked(application)
                    }
                }
            }
        }
        val filter = IntentFilter(EasyTierProcessService.ACTION_CONNECTION_EVENT)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                application.registerReceiver(receiver, filter)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to observe EasyTier connection events for priority alignment.", error)
        }.isSuccess
        if (registered) {
            connectionEventReceiver = receiver
        }
    }
}
