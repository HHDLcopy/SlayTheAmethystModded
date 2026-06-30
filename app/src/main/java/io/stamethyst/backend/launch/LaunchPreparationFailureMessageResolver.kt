package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.StsDesktopJarPatcher

internal object LaunchPreparationFailureMessageResolver {
    fun resolve(context: Context, throwable: Throwable): String? {
        val requiresJarReimport = generateSequence(throwable) { it.cause }
            .mapNotNull { error ->
                StsDesktopJarPatcher.extractLegacyWholeClassUiPatchClass(error.message)
            }
            .firstOrNull() != null
        if (!requiresJarReimport) {
            return null
        }
        return context.getString(
            R.string.startup_failure_legacy_patched_desktop_jar_requires_reimport
        )
    }

    internal fun stripInternalDetailMarkers(detail: String?): String? {
        val normalized = detail?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return normalized.lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .trim()
            .ifEmpty { null }
    }
}
