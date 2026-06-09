package io.stamethyst

import android.content.ClipboardManager
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import io.stamethyst.ui.haptics.LauncherHaptics
import java.util.Locale
import kotlin.math.roundToInt

internal class InGameSoftKeyboardOverlayController(
    private val activity: AppCompatActivity,
    private val requestRenderViewFocus: () -> Unit,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCommitText(text: CharSequence): Boolean

        fun onBackspace(): Boolean

        fun onEnter(): Boolean

        fun onTab(): Boolean

        fun onKey(androidKeyCode: Int): Boolean

        fun onToggleKey(androidKeyCode: Int, active: Boolean): Boolean

        fun onSystemKeyboardRequested()

        fun onVisibilityChanged(visible: Boolean)
    }

    private sealed interface KeySpec {
        val weight: Float

        data class TextKey(
            val base: String,
            val shifted: String = base.uppercase(Locale.ROOT),
            override val weight: Float = 1f
        ) : KeySpec

        data class ActionKey(
            val action: Action,
            override val weight: Float
        ) : KeySpec

        data class PhysicalKey(
            val label: String,
            val shiftedLabel: String = label,
            val androidKeyCode: Int,
            val toggleable: Boolean = false,
            override val weight: Float = 1f
        ) : KeySpec
    }

    private data class KeyRow(
        val keys: List<KeySpec>,
        val keyHeightDp: Int = KEY_HEIGHT_DP,
        val keySpacingDp: Int = KEY_SPACING_DP
    )

    private enum class Action {
        SHIFT,
        CAPS_LOCK,
        MODE,
        TAB,
        PASTE,
        SPACE,
        BACKSPACE,
        ENTER,
        SYSTEM_KEYBOARD,
        HIDE
    }

    private enum class LayoutMode {
        LETTERS,
        SYMBOLS
    }

    private var hostView: FrameLayout? = null
    private var panelView: LinearLayout? = null
    private var rowsContainer: LinearLayout? = null
    private var visible = false
    private var shiftEnabled = false
    private var capsLockEnabled = false
    private var layoutMode = LayoutMode.LETTERS
    private val activeToggleKeyCodes = mutableSetOf<Int>()

    fun attachToHost(host: FrameLayout) {
        detach()
        hostView = host
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            elevation = dpToPx(18).toFloat()
            isClickable = true
            isFocusable = false
            setBackgroundColor(0xCC1A1A1A.toInt())
            setPadding(
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_TOP_DP),
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_BOTTOM_DP)
            )
        }
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(
            rows,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_TOP_DP),
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_BOTTOM_DP) + bottomInset
            )
            insets
        }
        host.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        panelView = panel
        rowsContainer = rows
        rebuildKeys()
        ViewCompat.requestApplyInsets(panel)
    }

    fun detach() {
        panelView?.animate()?.cancel()
        panelView?.let { panel ->
            ViewCompat.setOnApplyWindowInsetsListener(panel, null)
            (panel.parent as? FrameLayout)?.removeView(panel)
        }
        panelView = null
        rowsContainer = null
        hostView = null
        visible = false
        shiftEnabled = false
        capsLockEnabled = false
        layoutMode = LayoutMode.LETTERS
        releaseActiveToggleKeys()
    }

    fun show() {
        val panel = panelView ?: return
        if (visible) {
            return
        }
        visible = true
        shiftEnabled = false
        capsLockEnabled = false
        layoutMode = LayoutMode.LETTERS
        rebuildKeys()
        panel.bringToFront()
        panel.visibility = View.VISIBLE
        panel.animate().cancel()
        panel.alpha = 0f
        panel.translationY = dpToPx(SHOW_TRANSLATION_Y_DP).toFloat()
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_HIDE_ANIM_DURATION_MS)
            .start()
        callbacks.onVisibilityChanged(true)
    }

    fun hide(refocusRenderView: Boolean = true) {
        val panel = panelView ?: return
        if (!visible && panel.visibility != View.VISIBLE) {
            return
        }
        visible = false
        shiftEnabled = false
        capsLockEnabled = false
        layoutMode = LayoutMode.LETTERS
        releaseActiveToggleKeys()
        panel.animate().cancel()
        panel.visibility = View.GONE
        panel.alpha = 0f
        panel.translationY = 0f
        rebuildKeys()
        callbacks.onVisibilityChanged(false)
        if (refocusRenderView) {
            requestRenderViewFocus.invoke()
        }
    }

    fun isVisible(): Boolean = visible

    private fun rebuildKeys() {
        val rows = rowsContainer ?: return
        rows.removeAllViews()
        resolveKeyRows().forEachIndexed { index, row ->
            rows.addView(
                createKeyRow(row),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) {
                        topMargin = dpToPx(KEY_ROW_SPACING_DP)
                    }
                }
            )
        }
    }

    private fun resolveKeyRows(): List<KeyRow> {
        return if (layoutMode == LayoutMode.LETTERS) {
            listOf(
                functionKeyRow(),
                KeyRow(
                    listOf(
                        physicalKey("`", KeyEvent.KEYCODE_GRAVE, shiftedLabel = "~", weight = 0.95f),
                        textKey("1", "!"),
                        textKey("2", "@"),
                        textKey("3", "#"),
                        textKey("4", "$"),
                        textKey("5", "%"),
                        textKey("6", "^"),
                        textKey("7", "&"),
                        textKey("8", "*"),
                        textKey("9", "("),
                        textKey("0", ")"),
                        KeySpec.ActionKey(Action.BACKSPACE, 1.45f)
                    )
                ),
                KeyRow(
                    listOf(KeySpec.ActionKey(Action.TAB, 1.25f)) +
                        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map(::textKey)
                ),
                KeyRow(
                    listOf(KeySpec.ActionKey(Action.CAPS_LOCK, 1.35f)) +
                        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map(::textKey) +
                        KeySpec.ActionKey(Action.ENTER, 1.45f)
                ),
                KeyRow(
                    listOf(
                        KeySpec.ActionKey(Action.SHIFT, 1.35f),
                        textKey("z"),
                        textKey("x"),
                        textKey("c"),
                        textKey("v"),
                        textKey("b"),
                        textKey("n"),
                        textKey("m"),
                        textKey(",", "<"),
                        textKey(".", ">"),
                        textKey("/", "?")
                    )
                ),
                controlKeyRow()
            )
        } else {
            listOf(
                functionKeyRow(),
                KeyRow(
                    listOf(
                        physicalKey("`", KeyEvent.KEYCODE_GRAVE, shiftedLabel = "~", weight = 0.95f),
                        textKey("-"),
                        textKey("="),
                        textKey("["),
                        textKey("]"),
                        textKey("\\"),
                        textKey(";"),
                        textKey("'"),
                        textKey(","),
                        textKey("."),
                        KeySpec.ActionKey(Action.BACKSPACE, 1.45f)
                    )
                ),
                KeyRow(listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")").map(::textKey)),
                KeyRow(
                    listOf(
                        textKey("?"),
                        textKey("!"),
                        textKey("/"),
                        textKey("\\"),
                        textKey("\""),
                        textKey("'"),
                        textKey(":"),
                        textKey(";"),
                        textKey("_"),
                        textKey("|"),
                        KeySpec.ActionKey(Action.ENTER, 1.45f)
                    )
                ),
                controlKeyRow()
            )
        }
    }

    private fun functionKeyRow(): KeyRow {
        return KeyRow(
            listOf(physicalKey("Esc", KeyEvent.KEYCODE_ESCAPE, weight = 1.15f)) +
                (1..12).map { index ->
                    physicalKey("F$index", functionAndroidKeyCode(index))
                },
            keyHeightDp = FUNCTION_KEY_HEIGHT_DP,
            keySpacingDp = FUNCTION_KEY_SPACING_DP
        )
    }

    private fun controlKeyRow(): KeyRow {
        return KeyRow(
            listOf(
                KeySpec.ActionKey(Action.MODE, 1.15f),
                physicalKey("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, toggleable = true),
                physicalKey("Alt", KeyEvent.KEYCODE_ALT_LEFT, toggleable = true),
                KeySpec.ActionKey(Action.SPACE, 4.2f),
                KeySpec.ActionKey(Action.PASTE, 1.25f),
                KeySpec.ActionKey(Action.SYSTEM_KEYBOARD, 1.1f),
                KeySpec.ActionKey(Action.HIDE, 1.1f)
            )
        )
    }

    private fun functionAndroidKeyCode(index: Int): Int {
        return when (index) {
            1 -> KeyEvent.KEYCODE_F1
            2 -> KeyEvent.KEYCODE_F2
            3 -> KeyEvent.KEYCODE_F3
            4 -> KeyEvent.KEYCODE_F4
            5 -> KeyEvent.KEYCODE_F5
            6 -> KeyEvent.KEYCODE_F6
            7 -> KeyEvent.KEYCODE_F7
            8 -> KeyEvent.KEYCODE_F8
            9 -> KeyEvent.KEYCODE_F9
            10 -> KeyEvent.KEYCODE_F10
            11 -> KeyEvent.KEYCODE_F11
            12 -> KeyEvent.KEYCODE_F12
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun createKeyRow(row: KeyRow): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            row.keys.forEachIndexed { index, spec ->
                addView(
                    createKeyButton(spec, compact = row.keyHeightDp < KEY_HEIGHT_DP),
                    LinearLayout.LayoutParams(
                        0,
                        dpToPx(row.keyHeightDp),
                        spec.weight
                    ).apply {
                        if (index > 0) {
                            leftMargin = dpToPx(row.keySpacingDp)
                        }
                    }
                )
            }
        }
    }

    private fun createKeyButton(spec: KeySpec, compact: Boolean): View {
        val isActive = when (spec) {
            is KeySpec.ActionKey -> {
                spec.action == Action.SHIFT && shiftEnabled ||
                    spec.action == Action.CAPS_LOCK && capsLockEnabled ||
                    spec.action == Action.MODE && layoutMode == LayoutMode.SYMBOLS
            }

            is KeySpec.PhysicalKey -> {
                spec.toggleable && activeToggleKeyCodes.contains(spec.androidKeyCode)
            }

            else -> false
        }
        if (spec is KeySpec.ActionKey && spec.action == Action.SYSTEM_KEYBOARD) {
            return ImageView(activity).apply {
                background = createKeyBackground(accent = true, active = false, compact = compact)
                contentDescription = resolveLabel(spec)
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.ic_keyboard)
                setColorFilter(0xFFFFFFFF.toInt())
                setOnClickListener {
                    LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                    handleKeyPress(spec)
                }
            }
        }
        return TextView(activity).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            isAllCaps = false
            isSingleLine = true
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (compact) COMPACT_KEY_TEXT_SIZE_SP else KEY_TEXT_SIZE_SP
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                MIN_KEY_TEXT_SIZE_SP.toInt(),
                (if (compact) COMPACT_KEY_TEXT_SIZE_SP else KEY_TEXT_SIZE_SP).toInt(),
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
            typeface = if (spec is KeySpec.ActionKey || spec is KeySpec.PhysicalKey) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }
            text = resolveLabel(spec)
            background = createKeyBackground(
                accent = spec is KeySpec.ActionKey || spec is KeySpec.PhysicalKey,
                active = isActive,
                compact = compact
            )
            setOnClickListener {
                LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                handleKeyPress(spec)
            }
        }
    }

    private fun resolveLabel(spec: KeySpec): String {
        return when (spec) {
            is KeySpec.TextKey -> {
                if (shouldUseShiftedText(spec)) {
                    spec.shifted
                } else {
                    spec.base
                }
            }

            is KeySpec.PhysicalKey -> {
                if (layoutMode == LayoutMode.LETTERS && shiftEnabled) {
                    spec.shiftedLabel
                } else {
                    spec.label
                }
            }

            is KeySpec.ActionKey -> when (spec.action) {
                Action.SHIFT -> activity.getString(R.string.touch_mouse_builtin_keyboard_shift)
                Action.CAPS_LOCK -> activity.getString(R.string.touch_mouse_builtin_keyboard_caps)
                Action.MODE -> if (layoutMode == LayoutMode.LETTERS) {
                    activity.getString(R.string.touch_mouse_builtin_keyboard_symbols)
                } else {
                    activity.getString(R.string.touch_mouse_builtin_keyboard_letters)
                }

                Action.TAB -> activity.getString(R.string.touch_mouse_builtin_keyboard_tab)
                Action.PASTE -> activity.getString(R.string.touch_mouse_builtin_keyboard_paste)
                Action.SPACE -> activity.getString(R.string.touch_mouse_builtin_keyboard_space)
                Action.BACKSPACE -> activity.getString(R.string.touch_mouse_builtin_keyboard_backspace)
                Action.ENTER -> activity.getString(R.string.touch_mouse_builtin_keyboard_enter)
                Action.SYSTEM_KEYBOARD -> activity.getString(R.string.touch_mouse_builtin_keyboard_system)
                Action.HIDE -> activity.getString(R.string.touch_mouse_builtin_keyboard_hide)
            }
        }
    }

    private fun handleKeyPress(spec: KeySpec) {
        when (spec) {
            is KeySpec.TextKey -> {
                val text = if (shouldUseShiftedText(spec)) {
                    spec.shifted
                } else {
                    spec.base
                }
                callbacks.onCommitText(text)
                if (layoutMode == LayoutMode.LETTERS && shiftEnabled) {
                    shiftEnabled = false
                    rebuildKeys()
                }
            }

            is KeySpec.PhysicalKey -> {
                if (spec.toggleable) {
                    val active = !activeToggleKeyCodes.contains(spec.androidKeyCode)
                    if (callbacks.onToggleKey(spec.androidKeyCode, active)) {
                        if (active) {
                            activeToggleKeyCodes.add(spec.androidKeyCode)
                        } else {
                            activeToggleKeyCodes.remove(spec.androidKeyCode)
                        }
                        rebuildKeys()
                    }
                } else {
                    callbacks.onKey(spec.androidKeyCode)
                }
            }

            is KeySpec.ActionKey -> when (spec.action) {
                Action.SHIFT -> {
                    shiftEnabled = !shiftEnabled
                    rebuildKeys()
                }

                Action.CAPS_LOCK -> {
                    capsLockEnabled = !capsLockEnabled
                    callbacks.onKey(KeyEvent.KEYCODE_CAPS_LOCK)
                    rebuildKeys()
                }

                Action.MODE -> {
                    layoutMode = if (layoutMode == LayoutMode.LETTERS) {
                        LayoutMode.SYMBOLS
                    } else {
                        LayoutMode.LETTERS
                    }
                    shiftEnabled = false
                    rebuildKeys()
                }

                Action.TAB -> callbacks.onTab()
                Action.PASTE -> {
                    readClipboardText()?.let(callbacks::onCommitText)
                }
                Action.SPACE -> callbacks.onCommitText(" ")
                Action.BACKSPACE -> callbacks.onBackspace()
                Action.ENTER -> callbacks.onEnter()
                Action.SYSTEM_KEYBOARD -> {
                    hide(refocusRenderView = false)
                    callbacks.onSystemKeyboardRequested()
                }
                Action.HIDE -> hide()
            }
        }
    }

    private fun releaseActiveToggleKeys() {
        val activeKeys = activeToggleKeyCodes.toList()
        activeToggleKeyCodes.clear()
        activeKeys.forEach { keyCode ->
            callbacks.onToggleKey(keyCode, false)
        }
    }

    private fun readClipboardText(): CharSequence? {
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return null
        if (!clipboard.hasPrimaryClip()) {
            return null
        }
        val item = clipboard.primaryClip?.getItemAt(0) ?: return null
        return item.coerceToText(activity)
            ?.takeIf { it.isNotBlank() }
    }

    private fun shouldUseShiftedText(spec: KeySpec.TextKey): Boolean {
        if (layoutMode != LayoutMode.LETTERS) {
            return shiftEnabled
        }
        return if (isCapsAffectedTextKey(spec)) {
            shiftEnabled.xor(capsLockEnabled)
        } else {
            shiftEnabled
        }
    }

    private fun isCapsAffectedTextKey(spec: KeySpec.TextKey): Boolean {
        return spec.base.length == 1 &&
            spec.base[0].isLetter() &&
            spec.shifted == spec.base.uppercase(Locale.ROOT)
    }

    private fun createKeyBackground(
        accent: Boolean,
        active: Boolean,
        compact: Boolean
    ): android.graphics.drawable.GradientDrawable {
        val color = when {
            active -> 0xCC355B2E.toInt()
            accent -> 0xB83A3A3A.toInt()
            else -> 0xA82A2A2A.toInt()
        }
        val strokeColor = when {
            active -> 0xFF98D96A.toInt()
            accent -> 0xFF636363.toInt()
            else -> 0xFF4A4A4A.toInt()
        }
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(
                if (compact) FUNCTION_KEY_CORNER_RADIUS_DP else KEY_CORNER_RADIUS_DP
            ).toFloat()
            setColor(color)
            setStroke(dpToPx(1), strokeColor)
        }
    }

    private fun textKey(
        value: String,
        shifted: String = value.uppercase(Locale.ROOT),
        weight: Float = 1f
    ): KeySpec.TextKey {
        return KeySpec.TextKey(base = value, shifted = shifted, weight = weight)
    }

    private fun physicalKey(
        label: String,
        keyCode: Int,
        shiftedLabel: String = label,
        weight: Float = 1f,
        toggleable: Boolean = false
    ): KeySpec.PhysicalKey {
        return KeySpec.PhysicalKey(
            label = label,
            shiftedLabel = shiftedLabel,
            androidKeyCode = keyCode,
            toggleable = toggleable,
            weight = weight
        )
    }

    private fun dpToPx(dp: Int): Int {
        return (activity.resources.displayMetrics.density * dp).roundToInt()
    }

    companion object {
        private const val PANEL_PADDING_HORIZONTAL_DP = 10
        private const val PANEL_PADDING_TOP_DP = 10
        private const val PANEL_PADDING_BOTTOM_DP = 10
        private const val KEY_HEIGHT_DP = 42
        private const val FUNCTION_KEY_HEIGHT_DP = 30
        private const val KEY_SPACING_DP = 5
        private const val FUNCTION_KEY_SPACING_DP = 3
        private const val KEY_ROW_SPACING_DP = 5
        private const val KEY_CORNER_RADIUS_DP = 9
        private const val FUNCTION_KEY_CORNER_RADIUS_DP = 6
        private const val SHOW_TRANSLATION_Y_DP = 10
        private const val SHOW_HIDE_ANIM_DURATION_MS = 160L
        private const val KEY_TEXT_SIZE_SP = 14f
        private const val COMPACT_KEY_TEXT_SIZE_SP = 12f
        private const val MIN_KEY_TEXT_SIZE_SP = 8f
    }
}
