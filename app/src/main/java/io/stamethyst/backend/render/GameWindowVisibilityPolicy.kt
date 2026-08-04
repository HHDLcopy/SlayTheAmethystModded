package io.stamethyst.backend.render

/**
 * Resolves whether the game window is still shown to the user.
 *
 * The runtime treats "not foreground" as "stop rendering and mute", which is correct when the
 * Activity is genuinely hidden but wrong in multi-window: a docked or freeform window keeps
 * drawing next to the focused app even though it lost focus and got paused. Losing focus alone
 * must therefore not be conflated with becoming invisible.
 */
internal object GameWindowVisibilityPolicy {
    /**
     * @param activityStopped `onStop` has run without a matching `onStart`; the only signal that
     *   reliably means the window left the screen.
     * @param activityResumed the Activity currently holds the resumed state.
     * @param inMultiWindowMode the Activity is docked, split or freeform.
     */
    fun resolveRuntimeVisible(
        activityStopped: Boolean,
        activityResumed: Boolean,
        inMultiWindowMode: Boolean
    ): Boolean {
        if (activityStopped) {
            return false
        }
        if (activityResumed) {
            return true
        }
        // Paused but not stopped. Fullscreen means another Activity covers this one, while
        // multi-window means the window is still on screen beside the newly focused app.
        return inMultiWindowMode
    }
}
