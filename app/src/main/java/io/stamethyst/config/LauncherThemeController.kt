package io.stamethyst.config

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

object LauncherThemeController {
    @JvmStatic
    fun apply(context: Context, themeMode: LauncherThemeMode) {
        applyFrameworkApplicationNightMode(context, themeMode)
        AppCompatDelegate.setDefaultNightMode(themeMode.appCompatNightMode)
    }

    @JvmStatic
    fun applySavedThemeMode(context: Context) {
        apply(context, LauncherConfig.readThemeMode(context))
    }

    private fun applyFrameworkApplicationNightMode(
        context: Context,
        themeMode: LauncherThemeMode
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val uiModeManager = context.applicationContext.getSystemService(UiModeManager::class.java)
            ?: return
        uiModeManager.setApplicationNightMode(frameworkNightMode(themeMode))
    }

    @Suppress("DEPRECATION")
    private fun frameworkNightMode(themeMode: LauncherThemeMode): Int {
        return when (themeMode) {
            LauncherThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            LauncherThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            LauncherThemeMode.FOLLOW_SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
    }
}
