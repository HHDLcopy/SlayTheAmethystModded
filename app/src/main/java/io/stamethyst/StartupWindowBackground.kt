package io.stamethyst

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.LauncherThemeMode

object StartupWindowBackground {
    fun launcherColor(context: Context): Int {
        return resolveColor(context, R.color.launcher_startup_background)
    }

    fun gameColor(context: Context): Int {
        return resolveColor(context, R.color.game_startup_background)
    }

    fun applyToWindow(window: Window, color: Int) {
        window.setBackgroundDrawable(ColorDrawable(color))
    }

    fun applyToDecorView(window: Window, color: Int) {
        window.decorView.setBackgroundColor(color)
    }

    private fun resolveColor(context: Context, @ColorRes colorResId: Int): Int {
        val configuration = Configuration(context.resources.configuration)
        val nightMode = when (LauncherConfig.readThemeMode(context)) {
            LauncherThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            LauncherThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            LauncherThemeMode.FOLLOW_SYSTEM -> systemNightMode(configuration)
        }
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        val themedContext = context.createConfigurationContext(configuration)
        return ContextCompat.getColor(themedContext, colorResId)
    }

    private fun systemNightMode(configuration: Configuration): Int {
        return when (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            else -> Configuration.UI_MODE_NIGHT_NO
        }
    }
}
