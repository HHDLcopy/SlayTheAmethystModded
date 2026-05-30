package io.stamethyst

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherOrientationPolicyTest {
    @Test
    fun requestedOrientation_honorsUserRotationPreference() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            LauncherOrientationPolicy.requestedOrientation
        )
    }
}
