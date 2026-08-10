package io.stamethyst

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * Lets freeform and split-screen windows keep their system-selected geometry while the game
 * viewport preserves its own landscape aspect ratio.
 */
internal object GameOrientationPolicy {
    internal fun resolveRequestedOrientation(isInMultiWindowMode: Boolean): Int {
        return if (isInMultiWindowMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun apply(activity: Activity, isInMultiWindowMode: Boolean) {
        val requestedOrientation = resolveRequestedOrientation(isInMultiWindowMode)
        if (activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
        }
    }
}
