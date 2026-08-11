package io.stamethyst

import android.app.Activity
import android.content.pm.ActivityInfo

/** Keeps the game task landscape, including when a vendor freeform environment hosts it. */
internal object GameOrientationPolicy {
    internal fun resolveRequestedOrientation(isInMultiWindowMode: Boolean): Int =
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    fun apply(activity: Activity, isInMultiWindowMode: Boolean) {
        val requestedOrientation = resolveRequestedOrientation(isInMultiWindowMode)
        if (activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
        }
    }
}
