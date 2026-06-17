package io.stamethyst.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.stamethyst.config.LauncherConfig

internal data class GameAndroidUiPalette(
    val primaryStrong: Int,
    val primaryContainer: Int,
    val primaryContainerHigh: Int,
    val surfaceScrim: Int,
    val surface: Int,
    val surfaceHigh: Int,
    val surfaceHighest: Int,
    val outline: Int,
    val outlineStrong: Int,
    val onSurface: Int,
    val onSurfaceMuted: Int,
    val onPrimary: Int,
    val dangerContainer: Int,
    val dangerOutline: Int,
) {
    companion object {
        private const val DARK_SURFACE_BASE = 0xFF15181D.toInt()
        private const val DARK_SURFACE_HIGH = 0xFF242B34.toInt()
        private const val DARK_SURFACE_HIGHEST = 0xFF303743.toInt()

        fun from(context: Context): GameAndroidUiPalette {
            val seed = ColorUtils.setAlphaComponent(
                LauncherConfig.readThemeColor(context).seedColor.toArgb(),
                255
            )
            val seedLuminance = ColorUtils.calculateLuminance(seed)
            val primary = ColorUtils.blendARGB(seed, Color.WHITE, if (seedLuminance < 0.45) 0.18f else 0.08f)
            val primaryStrong = ColorUtils.blendARGB(seed, Color.WHITE, if (seedLuminance < 0.55) 0.36f else 0.18f)
            val surface = ColorUtils.blendARGB(DARK_SURFACE_BASE, seed, 0.10f)
            val surfaceHigh = ColorUtils.blendARGB(DARK_SURFACE_HIGH, seed, 0.14f)
            val surfaceHighest = ColorUtils.blendARGB(DARK_SURFACE_HIGHEST, seed, 0.18f)
            val primaryContainer = ColorUtils.blendARGB(DARK_SURFACE_HIGH, seed, 0.36f)
            val primaryContainerHigh = ColorUtils.blendARGB(DARK_SURFACE_HIGHEST, seed, 0.48f)
            val outline = ColorUtils.blendARGB(0xFF4F5864.toInt(), primary, 0.26f)
            val outlineStrong = ColorUtils.blendARGB(0xFF848E9A.toInt(), primaryStrong, 0.42f)

            return GameAndroidUiPalette(
                primaryStrong = primaryStrong,
                primaryContainer = withAlpha(primaryContainer, 0xE6),
                primaryContainerHigh = withAlpha(primaryContainerHigh, 0xF2),
                surfaceScrim = withAlpha(surface, 0xE6),
                surface = withAlpha(surface, 0xF2),
                surfaceHigh = withAlpha(surfaceHigh, 0xF2),
                surfaceHighest = surfaceHighest,
                outline = outline,
                outlineStrong = outlineStrong,
                onSurface = Color.WHITE,
                onSurfaceMuted = ColorUtils.blendARGB(0xFFDDE3EA.toInt(), primaryStrong, 0.12f),
                onPrimary = contentColorFor(primaryStrong),
                dangerContainer = 0xE6A33A3A.toInt(),
                dangerOutline = 0xFFFF9A90.toInt(),
            )
        }

        fun withAlpha(color: Int, alpha: Int): Int {
            return ColorUtils.setAlphaComponent(color, alpha.coerceIn(0, 255))
        }

        private fun contentColorFor(color: Int): Int {
            return if (ColorUtils.calculateLuminance(color) > 0.50) {
                0xFF171A1D.toInt()
            } else {
                Color.WHITE
            }
        }
    }
}
