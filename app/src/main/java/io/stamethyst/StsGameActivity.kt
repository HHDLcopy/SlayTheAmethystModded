package io.stamethyst

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.stamethyst.backend.audio.GameAudioController
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.launch.GameProcessLaunchGuard
import io.stamethyst.backend.presence.GamePresenceStateMarker
import io.stamethyst.backend.render.DisplayPerformanceController
import io.stamethyst.backend.launch.AutoplayMode
import io.stamethyst.backend.launch.AutoplaySaveMode
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.input.GameInputHandler
import java.io.FileOutputStream
import java.util.UUID

class StsGameActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAUNCH_MODE = "io.stamethyst.launch_mode"
        const val EXTRA_BACK_BEHAVIOR = "io.stamethyst.back_behavior"
        const val EXTRA_BACK_IMMEDIATE_EXIT = "io.stamethyst.back_immediate_exit"
        const val EXTRA_MANUAL_DISMISS_BOOT_OVERLAY = "io.stamethyst.manual_dismiss_boot_overlay"
        const val EXTRA_FORCE_JVM_CRASH = "io.stamethyst.force_jvm_crash"
        const val EXTRA_FORCE_RUNTIME_CRASH = "io.stamethyst.force_runtime_crash"
        const val EXTRA_AUTOPLAY = "io.stamethyst.autoplay"
        const val EXTRA_AUTOPLAY_SAVE_MODE = "io.stamethyst.autoplay_save_mode"
        const val EXTRA_AUTOPLAY_MODE = "io.stamethyst.autoplay_mode"
        const val EXTRA_AUTOPLAY_SINGLE_ROOM_SPEC = "io.stamethyst.autoplay_single_room_spec"
        const val EXTRA_AUTOPLAY_CHOICE_DELAY_MS = "io.stamethyst.autoplay_choice_delay_ms"
        const val EXTRA_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT_ENABLED =
            "io.stamethyst.card_obtain_effect_ownership_compat_enabled"

        @JvmStatic
        fun launch(
            context: Context,
            launchMode: String,
            backBehavior: BackBehavior,
            manualDismissBootOverlay: Boolean,
            forceJvmCrash: Boolean = false,
            forceRuntimeCrash: Boolean = false,
            autoplay: Boolean = false,
            autoplaySaveMode: AutoplaySaveMode = AutoplaySaveMode.DEFAULT,
            autoplayMode: AutoplayMode = AutoplayMode.DEFAULT,
            autoplaySingleRoomSpecPath: String = "",
            autoplayChoiceDelayMs: Long = 0L,
            cardObtainEffectOwnershipCompatEnabled: Boolean = true
        ) {
            val intent = Intent(context, StsGameActivity::class.java)
            intent.putExtra(EXTRA_LAUNCH_MODE, launchMode)
            intent.putExtra(EXTRA_BACK_BEHAVIOR, backBehavior.persistedValue)
            intent.putExtra(
                EXTRA_BACK_IMMEDIATE_EXIT,
                backBehavior == BackBehavior.EXIT_TO_LAUNCHER
            )
            intent.putExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, manualDismissBootOverlay)
            intent.putExtra(EXTRA_FORCE_JVM_CRASH, forceJvmCrash)
            intent.putExtra(EXTRA_FORCE_RUNTIME_CRASH, forceRuntimeCrash)
            intent.putExtra(EXTRA_AUTOPLAY, autoplay)
            intent.putExtra(EXTRA_AUTOPLAY_SAVE_MODE, autoplaySaveMode.persistedValue)
            intent.putExtra(EXTRA_AUTOPLAY_MODE, autoplayMode.persistedValue)
            intent.putExtra(EXTRA_AUTOPLAY_SINGLE_ROOM_SPEC, autoplaySingleRoomSpecPath)
            intent.putExtra(EXTRA_AUTOPLAY_CHOICE_DELAY_MS, autoplayChoiceDelayMs)
            intent.putExtra(
                EXTRA_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT_ENABLED,
                cardObtainEffectOwnershipCompatEnabled
            )
            context.startActivity(intent)
        }
    }

    private lateinit var sessionConfig: GameSessionConfig
    private lateinit var renderSurfaceManager: RenderSurfaceManager
    private lateinit var inputHandler: GameInputHandler
    private lateinit var sessionCoordinator: GameSessionCoordinator
    private lateinit var gameAudioController: GameAudioController
    private val keepScreenOnHandler = Handler(Looper.getMainLooper())
    private val keepScreenOnIdleRunnable = Runnable {
        keepScreenOnActive = false
        updateKeepScreenOnFlag()
    }
    private var onBackInvokedCallback: OnBackInvokedCallback? = null
    private var bootOverlayKeepScreenOn = false
    private var keepScreenOnActive = false
    private var activityForeground = false
    private val launchGuardToken: String = UUID.randomUUID().toString()
    private val launchGuardLock = Any()
    private var launchGuardAcquired = false
    private var pendingFilePickerRequestId: String? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleFilePickerResult(uri)
    }

    private val gameBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            sessionCoordinator.handleAndroidBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupBackground = StartupWindowBackground.gameColor(this)
        StartupWindowBackground.applyToWindow(window, startupBackground)
        super.onCreate(savedInstanceState)
        StartupWindowBackground.applyToDecorView(window, startupBackground)
        launchGuardAcquired = GameProcessLaunchGuard.tryAcquire(launchGuardToken)
        if (!launchGuardAcquired) {
            MemoryDiagnosticsLogger.logEvent(
                this,
                "game_activity_launch_guard_rejected",
                mapOf("sessionToken" to launchGuardToken)
            )
            finish()
            return
        }
        setContentView(R.layout.activity_game)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        sessionConfig = GameSessionConfig.fromActivityIntent(this, intent)
        GamePresenceStateMarker.markGameActive(this, sessionConfig.launchMode)
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_created",
            buildMemoryEventExtras()
        )
        initControllers()
        renderSurfaceManager.applyImmersiveMode()
        initViews()
        registerSystemBackInvokedCallback()
    }

    override fun onDestroy() {
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_destroyed",
            buildMemoryEventExtras()
        )
        unregisterSystemBackInvokedCallback()
        DisplayPerformanceController.applySustainedPerformanceMode(this, false)
        if (::gameAudioController.isInitialized) {
            gameAudioController.onDestroy()
        }
        if (::inputHandler.isInitialized) {
            inputHandler.onDestroy()
        }
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onDestroy()
        }
        if (::sessionCoordinator.isInitialized) {
            sessionCoordinator.onDestroy()
        }
        GamePresenceStateMarker.markLauncherActive(this)
        if (launchGuardAcquired && (!::sessionCoordinator.isInitialized || !sessionCoordinator.jvmLaunchStarted)) {
            releaseLaunchGuard()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_resumed",
            buildMemoryEventExtras()
        )
        DisplayPerformanceController.applySustainedPerformanceMode(
            this,
            sessionConfig.sustainedPerformanceModeEnabled
        )
        renderSurfaceManager.applyImmersiveMode()
        inputHandler.resetGamepadState()
        gameAudioController.onResume()
        renderSurfaceManager.onForegroundChanged(true)
        sessionCoordinator.onResume()
        activityForeground = true
        GamePresenceStateMarker.markGameActive(this, sessionConfig.launchMode)
        resetKeepScreenOnIdleTimer()
    }

    override fun onPause() {
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_paused",
            buildMemoryEventExtras()
        )
        inputHandler.resetGamepadState()
        inputHandler.hideSoftKeyboard()
        gameAudioController.onPause()
        sessionCoordinator.onPause()
        renderSurfaceManager.onForegroundChanged(false)
        DisplayPerformanceController.applySustainedPerformanceMode(this, false)
        activityForeground = false
        keepScreenOnHandler.removeCallbacks(keepScreenOnIdleRunnable)
        updateKeepScreenOnFlag()
        super.onPause()
    }

    override fun onStop() {
        MemoryDiagnosticsLogger.logEvent(
            this,
            "game_activity_stopped",
            buildMemoryEventExtras()
        )
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        renderSurfaceManager.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            renderSurfaceManager.applyImmersiveMode()
        }
        sessionCoordinator.onWindowFocusChanged(hasFocus)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onWindowConfigurationChanged("window_configuration")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onWindowConfigurationChanged("multi_window_mode")
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (::renderSurfaceManager.isInitialized) {
            renderSurfaceManager.onWindowConfigurationChanged("multi_window_mode")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MemoryDiagnosticsLogger.logLowMemory(
            this,
            "game_activity",
            buildMemoryEventExtras()
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryDiagnosticsLogger.logTrimMemory(
            this,
            "game_activity",
            level,
            buildMemoryEventExtras()
        )
    }

    private fun initControllers() {
        inputHandler = GameInputHandler(
            activity = this,
            isInputDispatchReady = { sessionCoordinator.isInputDispatchReady() },
            requestRenderViewFocus = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.requestRenderViewFocus()
                }
            },
            getRenderViewWidth = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.getRenderViewWidth()
                } else {
                    0
                }
            },
            getRenderViewHeight = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.getRenderViewHeight()
                } else {
                    0
                }
            },
            getTargetWindowWidth = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.resolveVirtualWidth()
                } else {
                    0
                }
            },
            getTargetWindowHeight = {
                if (::renderSurfaceManager.isInitialized) {
                    renderSurfaceManager.resolveVirtualHeight()
                } else {
                    0
                }
            }
        )

        renderSurfaceManager = RenderSurfaceManager(
            activity = this,
            renderScale = sessionConfig.renderScale,
            targetFpsLimit = sessionConfig.effectiveTargetFps,
            useTextureViewSurface = sessionConfig.useTextureViewSurface,
            virtualResolutionMode = sessionConfig.virtualResolutionMode,
            avoidDisplayCutout = sessionConfig.avoidDisplayCutout,
            cropScreenBottom = sessionConfig.cropScreenBottom,
            isSoftKeyboardSessionActive = { inputHandler.isSoftKeyboardSessionActive() },
            onSurfaceReady = { sessionCoordinator.onSurfaceReady() },
            onSurfaceDestroyed = { },
            onTextureFrameUpdate = { timestampNs ->
                sessionCoordinator.onTextureFrameUpdate(timestampNs)
            }
        )

        sessionCoordinator = GameSessionCoordinator(
            activity = this,
            config = sessionConfig,
            renderSurfaceManager = renderSurfaceManager,
            inputHandler = inputHandler,
            onJvmLaunchFinished = { releaseLaunchGuard() }
        )
        gameAudioController = GameAudioController(
            activity = this,
            onAudioFocusGrantedChanged = { granted ->
                sessionCoordinator.onPlatformAudioFocusChanged(granted)
            },
            onAudioOutputRouteChanged = {
                sessionCoordinator.onAudioOutputRouteChanged()
            }
        )
    }

    private fun initViews() {
        onBackPressedDispatcher.addCallback(this, gameBackPressedCallback)

        val root = findViewById<FrameLayout>(R.id.gameRoot)
        renderSurfaceManager.init(root)

        sessionCoordinator.initSessionUi(findViewById(R.id.gamePerformanceOverlay))

        val host = findViewById<FrameLayout>(R.id.gameHost)
        inputHandler.initFloatingMouseControls(
            host = host,
            autoSwitchLeftAfterRightClick = sessionConfig.autoSwitchLeftAfterRightClick,
            touchDoubleClickAsRightClick = sessionConfig.touchDoubleClickAsRightClick,
            touchMouseInteractionMode = sessionConfig.touchMouseInteractionMode,
            builtInSoftKeyboardEnabled = sessionConfig.builtInSoftKeyboardEnabled
        )
        sessionCoordinator.refreshSessionUiVisibility()

        renderSurfaceManager.renderView.setOnTouchListener { _, event ->
            inputHandler.handleTouchEvent(event)
        }
        renderSurfaceManager.renderView.requestFocus()
    }

    fun setBootOverlayKeepScreenOn(enabled: Boolean) {
        if (bootOverlayKeepScreenOn == enabled) {
            return
        }
        bootOverlayKeepScreenOn = enabled
        if (enabled) {
            keepScreenOnActive = true
        } else {
            resetKeepScreenOnIdleTimer()
        }
        updateKeepScreenOnFlag()
    }

    private fun resetKeepScreenOnIdleTimer() {
        if (!::sessionConfig.isInitialized) {
            return
        }
        keepScreenOnHandler.removeCallbacks(keepScreenOnIdleRunnable)
        if (!activityForeground) {
            keepScreenOnActive = false
            updateKeepScreenOnFlag()
            return
        }
        keepScreenOnActive = true
        val timeoutMs = sessionConfig.keepScreenOnTimeoutMs
        if (timeoutMs != null && !bootOverlayKeepScreenOn) {
            keepScreenOnHandler.postDelayed(keepScreenOnIdleRunnable, timeoutMs)
        }
        updateKeepScreenOnFlag()
    }

    private fun updateKeepScreenOnFlag() {
        if (activityForeground && (bootOverlayKeepScreenOn || keepScreenOnActive)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun requestInGameFileSelection(requestId: String, mimeType: String) {
        if (requestId.isBlank()) {
            return
        }
        if (pendingFilePickerRequestId != null) {
            writeFilePickerResult(requestId, "ERROR", "picker_busy")
            return
        }
        pendingFilePickerRequestId = requestId
        try {
            filePickerLauncher.launch(arrayOf(mimeType.ifBlank { "*/*" }))
        } catch (throwable: Throwable) {
            pendingFilePickerRequestId = null
            writeFilePickerResult(requestId, "ERROR", throwable.javaClass.simpleName)
        }
    }

    private fun handleFilePickerResult(uri: Uri?) {
        val requestId = pendingFilePickerRequestId ?: return
        pendingFilePickerRequestId = null
        if (uri == null) {
            writeFilePickerResult(requestId, "CANCEL", "")
            return
        }
        try {
            val selectedFile = RuntimePaths.inGameFilePickerSelectionFile(this)
            selectedFile.parentFile?.mkdirs()
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    throw IllegalStateException("openInputStream returned null")
                }
                FileOutputStream(selectedFile, false).use { output ->
                    input.copyTo(output)
                }
            }
            writeFilePickerResult(requestId, "OK", selectedFile.absolutePath)
        } catch (throwable: Throwable) {
            writeFilePickerResult(requestId, "ERROR", throwable.javaClass.simpleName)
        }
    }

    private fun writeFilePickerResult(requestId: String, status: String, payload: String) {
        try {
            val resultFile = RuntimePaths.inGameFilePickerResultFile(this)
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(
                requestId + "\n" +
                    status + "\n" +
                    payload + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        return inputHandler.handleTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        return inputHandler.handleGenericMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetKeepScreenOnIdleTimer()
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK && sessionCoordinator.handleAndroidBackKeyEvent(event)) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return inputHandler.handleVolumeKeyEvent(event)
        }
        if (inputHandler.isGamepadKeyEvent(event) && inputHandler.handleGamepadKeyEvent(event)) {
            return true
        }
        if (inputHandler.dispatchKeyboardEventToGame(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun registerSystemBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (onBackInvokedCallback != null) {
            return
        }
        val callback = OnBackInvokedCallback {
            sessionCoordinator.handleAndroidBackPressed()
        }
        try {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            onBackInvokedCallback = callback
        } catch (_: Throwable) {
        }
    }

    private fun unregisterSystemBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val callback = onBackInvokedCallback ?: return
        try {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        } catch (_: Throwable) {
        }
        onBackInvokedCallback = null
    }

    private fun releaseLaunchGuard() {
        synchronized(launchGuardLock) {
            if (!launchGuardAcquired) {
                return
            }
            GameProcessLaunchGuard.release(launchGuardToken)
            launchGuardAcquired = false
        }
    }

    private fun buildMemoryEventExtras(): Map<String, Any?> {
        val launchMode = if (::sessionConfig.isInitialized) {
            sessionConfig.launchMode
        } else {
            intent?.getStringExtra(EXTRA_LAUNCH_MODE)
        }
        return linkedMapOf(
            "sessionToken" to launchGuardToken,
            "launchMode" to launchMode,
            "manualDismissBootOverlay" to intent?.getBooleanExtra(EXTRA_MANUAL_DISMISS_BOOT_OVERLAY, false),
            "forceJvmCrash" to intent?.getBooleanExtra(EXTRA_FORCE_JVM_CRASH, false),
            "forceRuntimeCrash" to intent?.getBooleanExtra(EXTRA_FORCE_RUNTIME_CRASH, false)
        )
    }
}
