package io.stamethyst

import android.app.Activity
import android.content.pm.ActivityInfo

internal object LauncherOrientationPolicy {
    val requestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_FULL_USER

    fun applyTo(activity: Activity) {
        if (activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
        }
    }
}
