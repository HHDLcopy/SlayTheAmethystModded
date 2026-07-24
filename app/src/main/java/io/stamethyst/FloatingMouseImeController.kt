package io.stamethyst

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal class FloatingMouseImeController(
    private val activity: AppCompatActivity,
    private val requestRenderViewFocus: () -> Unit,
    private val debugLogger: (String) -> Unit,
    private val callbacks: InputCallbacks
) {
    data class PreviewConfig(
        val initialText: String,
        val allowedCharacters: Set<Char>?,
        val characterLimit: Int?,
        val textSyncSource: String? = null,
    )

    interface InputCallbacks {
        fun onCommitText(
            text: CharSequence?,
            source: String
        ): Boolean

        fun onPreviewTextChanged(
            text: CharSequence,
            source: String
        ): Boolean

        fun onDeleteSurroundingText(
            beforeLength: Int,
            afterLength: Int
        ): Boolean

        fun onSendKeyEvent(event: KeyEvent): Boolean

        fun onPerformEditorAction(actionCode: Int): Boolean

        fun onKeyboardVisibilityChanged(visible: Boolean)
    }

    private val sessionMachine = SoftKeyboardSessionMachine()
    private var sessionState = SoftKeyboardSessionMachine.State()
    private var hostView: FrameLayout? = null
    private var editorView: GameImeEditor? = null
    private var pendingVerifyRunnable: Runnable? = null
    private var pendingShowReadyRunnable: Runnable? = null
    private var pendingUnexpectedHideRecoveryRunnable: Runnable? = null
    private var lastKeyboardVisible = false
    private var keepKeyboardVisibleRequested = false
    private var lastExplicitShowRequestAtMs = 0L
    private var lastInputInteractionAtMs = 0L
    private var unexpectedHideRecoveryAttempts = 0

    fun attachToHost(host: FrameLayout) {
        detach()
        hostView = host
        val editor = GameImeEditor(
            context = activity,
            debugLogger = debugLogger,
            callbacks = callbacks,
            windowFocusChangedCallback = ::onEditorWindowFocusChanged,
            inputInteractionCallback = ::noteInputInteraction
        ).apply {
            // Keep the editor fully opaque to system focus heuristics while still visually invisible.
            alpha = EDITOR_HOST_ALPHA
            setTextColor(Color.TRANSPARENT)
            highlightColor = Color.TRANSPARENT
            setBackgroundColor(Color.TRANSPARENT)
            isCursorVisible = false
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setOnFocusChangeListener { _, hasFocus ->
                debugLogger(
                    "editorFocus hasFocus=$hasFocus " +
                        snapshotState(editor = this)
                )
                onEditorFocusChanged(hasFocus)
            }
        }
        host.addView(
            editor,
            FrameLayout.LayoutParams(
                HIDDEN_EDITOR_SIZE_PX,
                HIDDEN_EDITOR_SIZE_PX,
                Gravity.TOP or Gravity.START
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(editor) { _, insets ->
            updateEditorLayout(
                editor = editor,
                imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            syncVisibilitySnapshot(source = "insets")
            insets
        }
        editorView = editor
        lastKeyboardVisible = isKeyboardVisible()
        sessionState = sessionMachine.onVisibilityChanged(sessionState, lastKeyboardVisible)
        debugLogger("attachToHost ${snapshotState(editor = editor)}")
        ViewCompat.requestApplyInsets(editor)
    }

    fun detach() {
        cancelPendingVerify()
        cancelPendingShowReady()
        cancelPendingUnexpectedHideRecovery()
        editorView?.let { editor ->
            ViewCompat.setOnApplyWindowInsetsListener(editor, null)
            (editor.parent as? FrameLayout)?.removeView(editor)
        }
        editorView = null
        hostView = null
        lastKeyboardVisible = false
        keepKeyboardVisibleRequested = false
        lastExplicitShowRequestAtMs = 0L
        lastInputInteractionAtMs = 0L
        unexpectedHideRecoveryAttempts = 0
        sessionState = SoftKeyboardSessionMachine.State()
    }

    fun requestShow(
        reason: String,
        keepVisible: Boolean = true,
        previewConfig: PreviewConfig? = null,
    ) {
        editorView?.let { editor ->
            editor.configurePreview(previewConfig)
            updateEditorLayout(editor)
            ViewCompat.requestApplyInsets(editor)
        }
        keepKeyboardVisibleRequested = keepVisible
        lastExplicitShowRequestAtMs = SystemClock.uptimeMillis()
        cancelPendingUnexpectedHideRecovery()
        debugLogger(
            "requestShow reason=$reason keepVisible=$keepVisible " +
                snapshotState()
        )
        applyTransition(
            sessionMachine.requestShow(
                state = sessionState,
                currentlyVisible = isKeyboardVisible()
            )
        )
    }

    fun requestHide(
        reason: String,
        refocusRenderView: Boolean = true
    ) {
        keepKeyboardVisibleRequested = false
        unexpectedHideRecoveryAttempts = 0
        cancelPendingUnexpectedHideRecovery()
        debugLogger(
            "requestHide reason=$reason " +
                snapshotState()
        )
        applyTransition(
            sessionMachine.requestHide(
                state = sessionState,
                refocusRenderView = refocusRenderView
            )
        )
    }

    fun isVisible(): Boolean = isKeyboardVisible()

    fun isPreviewActive(): Boolean = editorView?.isPreviewActive() == true

    fun shouldHoldRenderSurfaceStable(): Boolean {
        return keepKeyboardVisibleRequested ||
            sessionState.pendingShow ||
            lastKeyboardVisible ||
            pendingUnexpectedHideRecoveryRunnable != null
    }

    fun postOnEditor(
        runnable: Runnable,
        delayMs: Long = 0L
    ): Boolean {
        val editor = editorView ?: return false
        if (delayMs <= 0L) {
            editor.post(runnable)
        } else {
            editor.postDelayed(runnable, delayMs)
        }
        return true
    }

    fun removeEditorCallback(runnable: Runnable): Boolean {
        val editor = editorView ?: return false
        editor.removeCallbacks(runnable)
        return true
    }

    private fun applyTransition(transition: SoftKeyboardSessionMachine.Transition) {
        sessionState = transition.state
        cancelPendingVerify()
        cancelPendingShowReady()
        transition.commands.forEach(::executeCommand)
        syncVisibilitySnapshot(source = "transition")
    }

    private fun executeCommand(command: SoftKeyboardSessionMachine.Command) {
        when (command) {
            is SoftKeyboardSessionMachine.Command.PerformShowAttempt -> {
                performShowAttempt(
                    generation = command.generation,
                    attempt = command.attempt
                )
            }

            is SoftKeyboardSessionMachine.Command.ScheduleVerify -> {
                scheduleVerify(
                    generation = command.generation,
                    delayMs = command.delayMs
                )
            }

            is SoftKeyboardSessionMachine.Command.PerformHide -> {
                performHide(refocusRenderView = command.refocusRenderView)
            }
        }
    }

    private fun performShowAttempt(
        generation: Int,
        attempt: Int
    ) {
        val editor = editorView ?: return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (generation != sessionState.generation) {
            return
        }

        editor.prepareForIme()
        val focusRequested = editor.requestFocus()
        val touchFocusRequested = editor.requestFocusFromTouch()
        editor.setSelection(editor.text?.length ?: 0)
        imm.restartInput(editor)
        debugLogger(
            "showAttemptPrepare generation=$generation attempt=$attempt " +
                "focus=$focusRequested touchFocus=$touchFocusRequested " +
                snapshotState(editor = editor, imm = imm)
        )
        scheduleShowWhenReady(
            generation = generation,
            attempt = attempt,
            checksRemaining = SHOW_READY_MAX_CHECKS,
            reason = "attempt_prepare"
        )
    }

    private fun performHide(refocusRenderView: Boolean) {
        val editor = editorView ?: return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        cancelPendingShowReady()
        cancelPendingUnexpectedHideRecovery()
        windowInsetsController(editor).hide(WindowInsetsCompat.Type.ime())
        val hideAccepted = imm.hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
        editor.configurePreview(null)
        updateEditorLayout(editor)
        debugLogger(
            "hideKeyboard hideAccepted=$hideAccepted " +
                snapshotState(editor = editor, imm = imm)
        )
        if (refocusRenderView) {
            requestRenderViewFocus.invoke()
        }
        syncVisibilitySnapshot(source = "hide")
    }

    private fun scheduleVerify(
        generation: Int,
        delayMs: Long
    ) {
        val editor = editorView ?: return
        val verifyRunnable = Runnable {
            pendingVerifyRunnable = null
            val visible = isKeyboardVisible()
            debugLogger(
                "verifyShow generation=$generation delayMs=$delayMs " +
                    "visible=$visible ${snapshotState(editor = editor)}"
            )
            applyTransition(
                sessionMachine.onVerify(
                    state = sessionState,
                    generation = generation,
                    currentlyVisible = visible
                )
            )
        }
        pendingVerifyRunnable = verifyRunnable
        if (delayMs <= 0L) {
            editor.post(verifyRunnable)
        } else {
            editor.postDelayed(verifyRunnable, delayMs)
        }
    }

    private fun cancelPendingVerify() {
        val editor = editorView
        val verifyRunnable = pendingVerifyRunnable
        if (editor != null && verifyRunnable != null) {
            editor.removeCallbacks(verifyRunnable)
        }
        pendingVerifyRunnable = null
    }

    private fun scheduleShowWhenReady(
        generation: Int,
        attempt: Int,
        checksRemaining: Int,
        reason: String
    ) {
        val editor = editorView ?: return
        cancelPendingShowReady()
        val runnable = Runnable {
            pendingShowReadyRunnable = null
            runShowWhenReady(
                generation = generation,
                attempt = attempt,
                checksRemaining = checksRemaining,
                reason = reason
            )
        }
        pendingShowReadyRunnable = runnable
        editor.postDelayed(
            runnable,
            if (checksRemaining == SHOW_READY_MAX_CHECKS) 0L else SHOW_READY_RECHECK_DELAY_MS
        )
    }

    private fun runShowWhenReady(
        generation: Int,
        attempt: Int,
        checksRemaining: Int,
        reason: String
    ) {
        val editor = editorView ?: return
        if (generation != sessionState.generation) {
            return
        }
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

        editor.prepareForIme()
        if (!editor.hasFocus()) {
            editor.requestFocus()
            editor.requestFocusFromTouch()
        }
        val hasEditorFocus = editor.hasFocus()
        val hasEditorWindowFocus = editor.hasWindowFocus()
        val hasWindowFocus = hasActivityWindowFocus()
        val isEditorActive = imm.isActive(editor)
        val shouldWaitForConnection = hasEditorFocus && hasEditorWindowFocus && hasWindowFocus && !isEditorActive

        if (shouldWaitForConnection && checksRemaining > 0) {
            imm.restartInput(editor)
        }

        if (hasEditorFocus && hasEditorWindowFocus && hasWindowFocus && (!shouldWaitForConnection || checksRemaining <= 0)) {
            issueExplicitShow(
                editor = editor,
                imm = imm,
                generation = generation,
                attempt = attempt,
                reason = reason,
                checksRemaining = checksRemaining
            )
            return
        }

        debugLogger(
            "showAwait generation=$generation attempt=$attempt " +
                "checksRemaining=$checksRemaining reason=$reason " +
                snapshotState(editor = editor, imm = imm)
        )
        if (checksRemaining <= 0) {
            return
        }
        scheduleShowWhenReady(
            generation = generation,
            attempt = attempt,
            checksRemaining = checksRemaining - 1,
            reason = reason
        )
    }

    private fun issueExplicitShow(
        editor: GameImeEditor,
        imm: InputMethodManager,
        generation: Int,
        attempt: Int,
        reason: String,
        checksRemaining: Int
    ) {
        editor.prepareForIme()
        editor.setSelection(editor.text?.length ?: 0)
        imm.restartInput(editor)
        windowInsetsController(editor).show(WindowInsetsCompat.Type.ime())
        val showAccepted = imm.showSoftInput(editor, 0)
        debugLogger(
            "showDispatch generation=$generation attempt=$attempt " +
                "checksRemaining=$checksRemaining reason=$reason " +
                "showAccepted=$showAccepted ${snapshotState(editor = editor, imm = imm)}"
        )
        syncVisibilitySnapshot(source = "show_dispatch")
    }

    private fun cancelPendingShowReady() {
        val editor = editorView
        val runnable = pendingShowReadyRunnable
        if (editor != null && runnable != null) {
            editor.removeCallbacks(runnable)
        }
        pendingShowReadyRunnable = null
    }

    private fun scheduleUnexpectedHideRecovery(trigger: String) {
        if (!shouldAttemptUnexpectedHideRecovery()) {
            return
        }
        val editor = editorView ?: return
        cancelPendingUnexpectedHideRecovery()
        val runnable = Runnable {
            pendingUnexpectedHideRecoveryRunnable = null
            if (!shouldAttemptUnexpectedHideRecovery()) {
                return@Runnable
            }
            unexpectedHideRecoveryAttempts++
            debugLogger(
                "unexpectedHideRecovery trigger=$trigger attempt=$unexpectedHideRecoveryAttempts " +
                    snapshotState(editor = editor)
            )
            requestShow(reason = "unexpected_hide_recovery:$trigger")
        }
        pendingUnexpectedHideRecoveryRunnable = runnable
        editor.postDelayed(runnable, UNEXPECTED_HIDE_RECOVERY_DELAY_MS)
    }

    private fun cancelPendingUnexpectedHideRecovery() {
        val editor = editorView
        val runnable = pendingUnexpectedHideRecoveryRunnable
        if (editor != null && runnable != null) {
            editor.removeCallbacks(runnable)
        }
        pendingUnexpectedHideRecoveryRunnable = null
    }

    private fun syncVisibilitySnapshot(source: String) {
        val visible = isKeyboardVisible()
        sessionState = sessionMachine.onVisibilityChanged(sessionState, visible)
        if (visible == lastKeyboardVisible) {
            return
        }
        lastKeyboardVisible = visible
        debugLogger(
            "keyboardVisibility source=$source visible=$visible " +
                snapshotState()
        )
        if (visible) {
            cancelPendingVerify()
            cancelPendingShowReady()
            cancelPendingUnexpectedHideRecovery()
            unexpectedHideRecoveryAttempts = 0
        } else {
            scheduleUnexpectedHideRecovery(trigger = "visibility:$source")
            editorView?.let { editor ->
                if (editor.isPreviewActive()) {
                    editor.configurePreview(null)
                    updateEditorLayout(editor)
                }
            }
        }
        callbacks.onKeyboardVisibilityChanged(visible)
    }

    private fun isKeyboardVisible(): Boolean {
        val anchor = editorView ?: hostView ?: return false
        return ViewCompat.getRootWindowInsets(anchor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun isEditorActive(): Boolean {
        val editor = editorView ?: return false
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.isActive(editor)
    }

    private fun onEditorFocusChanged(hasFocus: Boolean) {
        if (hasFocus && sessionState.pendingShow) {
            scheduleShowWhenReady(
                generation = sessionState.generation,
                attempt = sessionState.nextRetryIndex.coerceAtLeast(1) - 1,
                checksRemaining = SHOW_READY_MAX_CHECKS,
                reason = "editor_focus"
            )
        }
        if (hasFocus) {
            scheduleUnexpectedHideRecovery(trigger = "editor_focus")
        }
    }

    private fun onEditorWindowFocusChanged(hasWindowFocus: Boolean) {
        debugLogger(
            "editorWindowFocus hasWindowFocus=$hasWindowFocus " +
                "pendingShow=${sessionState.pendingShow} ${snapshotState()}"
        )
        if (hasWindowFocus && sessionState.pendingShow) {
            scheduleShowWhenReady(
                generation = sessionState.generation,
                attempt = sessionState.nextRetryIndex.coerceAtLeast(1) - 1,
                checksRemaining = SHOW_READY_MAX_CHECKS,
                reason = "window_focus"
            )
        }
        if (hasWindowFocus) {
            scheduleUnexpectedHideRecovery(trigger = "window_focus")
        }
    }

    private fun shouldAttemptUnexpectedHideRecovery(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (!keepKeyboardVisibleRequested ||
            sessionState.pendingShow ||
            lastKeyboardVisible ||
            activity.isFinishing ||
            activity.isDestroyed ||
            !hasActivityWindowFocus() ||
            unexpectedHideRecoveryAttempts >= MAX_UNEXPECTED_HIDE_RECOVERY_ATTEMPTS
        ) {
            return false
        }

        val recentShowRequest = nowMs - lastExplicitShowRequestAtMs
        if (recentShowRequest in 0..UNEXPECTED_HIDE_RECOVERY_AFTER_SHOW_WINDOW_MS) {
            return true
        }

        val recentInputInteraction = nowMs - lastInputInteractionAtMs
        return recentInputInteraction in 0..UNEXPECTED_HIDE_RECOVERY_AFTER_INPUT_WINDOW_MS
    }

    private fun noteInputInteraction() {
        lastInputInteractionAtMs = SystemClock.uptimeMillis()
    }

    private fun snapshotState(
        editor: View? = editorView,
        imm: InputMethodManager? =
            activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    ): String {
        val target = editor
        return buildString {
            append("visible=").append(isKeyboardVisible())
            append(" editorAttached=").append(target?.isAttachedToWindow == true)
            append(" editorFocus=").append(target?.hasFocus() == true)
            append(" editorWindowFocus=").append(target?.hasWindowFocus() == true)
            append(" activityWindowFocus=").append(hasActivityWindowFocus())
            append(" windowToken=").append(target?.windowToken != null)
            append(" active=").append(target != null && imm?.isActive(target) == true)
            append(" acceptingText=").append(imm?.isAcceptingText == true)
        }
    }

    private fun hasActivityWindowFocus(): Boolean {
        return activity.window?.decorView?.hasWindowFocus() == true
    }

    private fun windowInsetsController(editor: View) =
        WindowCompat.getInsetsController(activity.window, editor)

    private fun updateEditorLayout(
        editor: GameImeEditor,
        imeBottom: Int = ViewCompat.getRootWindowInsets(editor)
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.bottom
            ?: 0,
    ) {
        val params = if (editor.isPreviewActive()) {
            val horizontalMargin = dpToPx(PREVIEW_HORIZONTAL_MARGIN_DP)
            val availableWidth = (activity.resources.displayMetrics.widthPixels - horizontalMargin * 2)
                .coerceAtLeast(1)
            FrameLayout.LayoutParams(
                availableWidth.coerceAtMost(dpToPx(PREVIEW_MAX_WIDTH_DP)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = imeBottom + dpToPx(PREVIEW_IME_GAP_DP)
            }
        } else {
            FrameLayout.LayoutParams(
                HIDDEN_EDITOR_SIZE_PX,
                HIDDEN_EDITOR_SIZE_PX,
                Gravity.TOP or Gravity.START,
            )
        }
        editor.layoutParams = params
    }

    private fun dpToPx(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics,
        ).toInt()
    }

    private class GameImeEditor(
        context: Context,
        private val debugLogger: (String) -> Unit,
        private val callbacks: InputCallbacks,
        private val windowFocusChangedCallback: (Boolean) -> Unit,
        private val inputInteractionCallback: () -> Unit
    ) : AppCompatEditText(context) {
        private var previewActive = false
        private var previewTextSyncSource: String? = null

        init {
            inputType = DEFAULT_INPUT_TYPE
            imeOptions = DEFAULT_IME_OPTIONS
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            setEms(1)
            setText("", BufferType.EDITABLE)
        }

        fun prepareForIme() {
            if (text == null) {
                setText("", BufferType.EDITABLE)
            }
            setSelection(text?.length ?: 0)
        }

        fun isPreviewActive(): Boolean = previewActive

        fun configurePreview(config: PreviewConfig?) {
            previewActive = config != null
            previewTextSyncSource = config?.textSyncSource
            if (config == null) {
                filters = emptyArray()
                setSingleLine(false)
                inputType = DEFAULT_INPUT_TYPE
                imeOptions = DEFAULT_IME_OPTIONS
                setText("", BufferType.EDITABLE)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minHeight = 0
                setTextColor(Color.TRANSPARENT)
                highlightColor = Color.TRANSPARENT
                background = null
                elevation = 0f
                isCursorVisible = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                return
            }

            val inputFilters = mutableListOf<InputFilter>()
            config.allowedCharacters?.takeIf { it.isNotEmpty() }?.let { allowed ->
                inputFilters += AllowedCharacterFilter(allowed)
            }
            config.characterLimit?.takeIf { it > 0 }?.let { limit ->
                inputFilters += InputFilter.LengthFilter(limit)
            }
            filters = inputFilters.toTypedArray()
            setSingleLine(true)
            inputType = if (config.allowedCharacters?.all(Char::isDigit) == true) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            val horizontalPadding = dpToPx(PREVIEW_TEXT_HORIZONTAL_PADDING_DP)
            val verticalPadding = dpToPx(PREVIEW_TEXT_VERTICAL_PADDING_DP)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            minHeight = dpToPx(PREVIEW_MIN_HEIGHT_DP)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PREVIEW_TEXT_SIZE_SP)
            setTextColor(Color.WHITE)
            highlightColor = Color.argb(96, 126, 184, 255)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(PREVIEW_CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(242, 30, 33, 38))
                setStroke(dpToPx(PREVIEW_STROKE_WIDTH_DP).coerceAtLeast(1), Color.argb(210, 210, 216, 224))
            }
            elevation = dpToPx(PREVIEW_ELEVATION_DP).toFloat()
            isCursorVisible = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setText(config.initialText, BufferType.EDITABLE)
            setSelection(text?.length ?: 0)
        }

        private fun dpToPx(value: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics,
            ).toInt()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            debugLogger("editorAttached attached=$isAttachedToWindow hasWindowFocus=${hasWindowFocus()} hasFocus=${hasFocus()}")
        }

        override fun onDetachedFromWindow() {
            debugLogger("editorDetached attached=$isAttachedToWindow hasWindowFocus=${hasWindowFocus()} hasFocus=${hasFocus()}")
            super.onDetachedFromWindow()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            debugLogger("editorWindowFocusCallback hasWindowFocus=$hasWindowFocus hasFocus=${hasFocus()}")
            windowFocusChangedCallback.invoke(hasWindowFocus)
        }

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            val textEnd = text?.length ?: 0
            if (previewActive && previewTextSyncSource == null &&
                (selStart != textEnd || selEnd != textEnd)
            ) {
                setSelection(textEnd)
                return
            }
            super.onSelectionChanged(selStart, selEnd)
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = inputType
            outAttrs.imeOptions = imeOptions
            val baseConnection = super.onCreateInputConnection(outAttrs)
            return object : InputConnectionWrapper(
                baseConnection,
                true
            ) {
                override fun commitText(
                    text: CharSequence?,
                    newCursorPosition: Int
                ): Boolean {
                    val result = super.commitText(text, newCursorPosition)
                    debugLogger(
                        "InputConnection.commitText text=${describeText(text)} " +
                            "cursor=$newCursorPosition result=$result"
                    )
                    inputInteractionCallback.invoke()
                    publishPreviewTextOrCommit(text)
                    return true
                }

                override fun setComposingText(
                    text: CharSequence?,
                    newCursorPosition: Int
                ): Boolean {
                    val result = super.setComposingText(text, newCursorPosition)
                    debugLogger(
                        "InputConnection.setComposingText text=${describeText(text)} " +
                            "cursor=$newCursorPosition result=$result"
                    )
                    return result
                }

                override fun finishComposingText(): Boolean {
                    val result = super.finishComposingText()
                    debugLogger("InputConnection.finishComposingText result=$result")
                    return result
                }

                override fun deleteSurroundingText(
                    beforeLength: Int,
                    afterLength: Int
                ): Boolean {
                    val previewTextBeforeDelete = text?.toString()
                    val result = super.deleteSurroundingText(beforeLength, afterLength)
                    val previewDeleteApplied = applyPreviewDeleteIfNeeded(
                        previewTextBeforeDelete,
                        beforeLength,
                        afterLength,
                        deleteByCodePoints = false,
                    )
                    debugLogger(
                        "InputConnection.deleteSurroundingText before=$beforeLength " +
                            "after=$afterLength result=$result " +
                            "previewDeleteApplied=$previewDeleteApplied"
                    )
                    inputInteractionCallback.invoke()
                    publishPreviewTextOrDelete(beforeLength, afterLength)
                    return true
                }

                override fun deleteSurroundingTextInCodePoints(
                    beforeLength: Int,
                    afterLength: Int
                ): Boolean {
                    val previewTextBeforeDelete = text?.toString()
                    val result = super.deleteSurroundingTextInCodePoints(
                        beforeLength,
                        afterLength
                    )
                    val previewDeleteApplied = applyPreviewDeleteIfNeeded(
                        previewTextBeforeDelete,
                        beforeLength,
                        afterLength,
                        deleteByCodePoints = true,
                    )
                    debugLogger(
                        "InputConnection.deleteSurroundingTextInCodePoints before=$beforeLength " +
                            "after=$afterLength result=$result " +
                            "previewDeleteApplied=$previewDeleteApplied"
                    )
                    inputInteractionCallback.invoke()
                    publishPreviewTextOrDelete(beforeLength, afterLength)
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    val textSyncSource = previewTextSyncSource
                    if (textSyncSource != null) {
                        val beforeText = text?.toString().orEmpty()
                        val result = super.sendKeyEvent(event)
                        val previewDeleteApplied = when {
                            event.action != KeyEvent.ACTION_DOWN -> false
                            event.keyCode == KeyEvent.KEYCODE_DEL -> applyPreviewDeleteIfNeeded(
                                beforeText,
                                beforeLength = 1,
                                afterLength = 0,
                                deleteByCodePoints = true,
                            )
                            event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> applyPreviewDeleteIfNeeded(
                                beforeText,
                                beforeLength = 0,
                                afterLength = 1,
                                deleteByCodePoints = true,
                            )
                            else -> false
                        }
                        debugLogger(
                            "InputConnection.sendKeyEvent event=${describeKeyEvent(event)} " +
                                "result=$result previewDeleteApplied=$previewDeleteApplied"
                        )
                        if (beforeText != text?.toString().orEmpty()) {
                            callbacks.onPreviewTextChanged(
                                text?.toString().orEmpty(),
                                textSyncSource
                            )
                        }
                        if (event.action == KeyEvent.ACTION_UP &&
                            event.keyCode == KeyEvent.KEYCODE_ENTER
                        ) {
                            callbacks.onPerformEditorAction(EditorInfo.IME_ACTION_DONE)
                        }
                        return true
                    }
                    debugLogger(
                        "InputConnection.sendKeyEvent event=${describeKeyEvent(event)}"
                    )
                    inputInteractionCallback.invoke()
                    return callbacks.onSendKeyEvent(event)
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    debugLogger("InputConnection.performEditorAction actionCode=$actionCode")
                    inputInteractionCallback.invoke()
                    previewTextSyncSource?.let { source ->
                        callbacks.onPreviewTextChanged(text?.toString().orEmpty(), source)
                    }
                    return callbacks.onPerformEditorAction(actionCode)
                }

                private fun publishPreviewTextOrCommit(committedText: CharSequence?) {
                    val textSyncSource = previewTextSyncSource
                    if (textSyncSource == null) {
                        callbacks.onCommitText(committedText, source = "commit_text")
                        return
                    }
                    callbacks.onPreviewTextChanged(text?.toString().orEmpty(), textSyncSource)
                }

                private fun publishPreviewTextOrDelete(
                    beforeLength: Int,
                    afterLength: Int
                ) {
                    val textSyncSource = previewTextSyncSource
                    if (textSyncSource == null) {
                        callbacks.onDeleteSurroundingText(beforeLength, afterLength)
                        return
                    }
                    callbacks.onPreviewTextChanged(text?.toString().orEmpty(), textSyncSource)
                }

                private fun applyPreviewDeleteIfNeeded(
                    previewTextBeforeDelete: String?,
                    beforeLength: Int,
                    afterLength: Int,
                    deleteByCodePoints: Boolean,
                ): Boolean {
                    if (previewTextSyncSource == null ||
                        previewTextBeforeDelete != text?.toString()
                    ) {
                        return false
                    }

                    val editable = this@GameImeEditor.text ?: return false
                    val selectionStart = this@GameImeEditor.selectionStart
                        .coerceIn(0, editable.length)
                    val selectionEnd = this@GameImeEditor.selectionEnd
                        .coerceIn(0, editable.length)
                    val selectedStart = minOf(selectionStart, selectionEnd)
                    val selectedEnd = maxOf(selectionStart, selectionEnd)
                    val deleteStart = offsetByDeleteLength(
                        editable,
                        selectedStart,
                        -beforeLength.coerceAtLeast(0),
                        deleteByCodePoints,
                    )
                    val deleteEnd = offsetByDeleteLength(
                        editable,
                        selectedEnd,
                        afterLength.coerceAtLeast(0),
                        deleteByCodePoints,
                    )
                    if (deleteStart == deleteEnd) {
                        return false
                    }
                    editable.delete(deleteStart, deleteEnd)
                    this@GameImeEditor.setSelection(deleteStart)
                    return true
                }

                private fun offsetByDeleteLength(
                    editable: CharSequence,
                    index: Int,
                    codePointOffset: Int,
                    deleteByCodePoints: Boolean,
                ): Int {
                    if (!deleteByCodePoints) {
                        return (index + codePointOffset).coerceIn(0, editable.length)
                    }
                    var offset = index
                    var remaining = codePointOffset
                    while (remaining < 0 && offset > 0) {
                        offset = Character.offsetByCodePoints(editable, offset, -1)
                        remaining += 1
                    }
                    while (remaining > 0 && offset < editable.length) {
                        offset = Character.offsetByCodePoints(editable, offset, 1)
                        remaining -= 1
                    }
                    return offset
                }
            }
        }

        private fun describeText(text: CharSequence?): String {
            if (text == null) {
                return "<null>"
            }
            return buildString {
                append('"')
                text.forEach { ch ->
                    append(
                        when (ch) {
                            '\b' -> "\\b"
                            '\n' -> "\\n"
                            '\r' -> "\\r"
                            '\t' -> "\\t"
                            else -> if (Character.isISOControl(ch)) {
                                "\\u" + ch.code.toString(16).padStart(4, '0')
                            } else {
                                ch
                            }
                        }
                    )
                }
                append('"')
                append(" len=").append(text.length)
            }
        }

        private fun describeKeyEvent(event: KeyEvent): String {
            return buildString {
                append(
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> "DOWN"
                        KeyEvent.ACTION_UP -> "UP"
                        KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
                        else -> event.action.toString()
                    }
                )
                append('/')
                append(KeyEvent.keyCodeToString(event.keyCode))
                append(" repeat=").append(event.repeatCount)
                append(" unicode=").append(event.unicodeChar)
                if (!event.characters.isNullOrEmpty()) {
                    append(" chars=").append(describeText(event.characters))
                }
            }
        }

        private class AllowedCharacterFilter(
            private val allowedCharacters: Set<Char>,
        ) : InputFilter {
            override fun filter(
                source: CharSequence,
                start: Int,
                end: Int,
                dest: android.text.Spanned,
                dstart: Int,
                dend: Int,
            ): CharSequence? {
                var changed = false
                val accepted = StringBuilder(end - start)
                for (index in start until end) {
                    val character = source[index]
                    if (character in allowedCharacters) {
                        accepted.append(character)
                    } else {
                        changed = true
                    }
                }
                return if (changed) accepted.toString() else null
            }
        }
    }

    companion object {
        private const val EDITOR_HOST_ALPHA = 1f
        private const val HIDDEN_EDITOR_SIZE_PX = 1
        private const val PREVIEW_HORIZONTAL_MARGIN_DP = 16
        private const val PREVIEW_MAX_WIDTH_DP = 640
        private const val PREVIEW_IME_GAP_DP = 12
        private const val PREVIEW_MIN_HEIGHT_DP = 52
        private const val PREVIEW_TEXT_HORIZONTAL_PADDING_DP = 16
        private const val PREVIEW_TEXT_VERTICAL_PADDING_DP = 10
        private const val PREVIEW_CORNER_RADIUS_DP = 8
        private const val PREVIEW_STROKE_WIDTH_DP = 1
        private const val PREVIEW_ELEVATION_DP = 6
        private const val PREVIEW_TEXT_SIZE_SP = 18f
        private const val SHOW_READY_RECHECK_DELAY_MS = 16L
        private const val SHOW_READY_MAX_CHECKS = 8
        private const val UNEXPECTED_HIDE_RECOVERY_DELAY_MS = 96L
        private const val UNEXPECTED_HIDE_RECOVERY_AFTER_SHOW_WINDOW_MS = 1_500L
        private const val UNEXPECTED_HIDE_RECOVERY_AFTER_INPUT_WINDOW_MS = 900L
        private const val MAX_UNEXPECTED_HIDE_RECOVERY_ATTEMPTS = 1
        private const val DEFAULT_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        private const val DEFAULT_IME_OPTIONS = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }
}
