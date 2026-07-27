package io.stamethyst.backend.mods.importing.patches

import android.content.Context
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.downfall.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.frieren.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.jacketnoanoko.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.vupshion.VupShionImportPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import io.stamethyst.config.LauncherConfig

/**
 * Persistent user choices for import-time compatibility patches.
 *
 * A stored manager choice is authoritative. Before a module has an explicit manager choice,
 * legacy preferences are used as a migration bridge.
 */
internal object ImportPatchSettings {
    private const val PREFERENCES_NAME = "import_patch_settings"
    private const val ENABLED_KEY_PREFIX = "enabled."

    fun isEnabled(context: Context, module: ImportPatchModule): Boolean {
        val preferences = preferences(context)
        val key = enabledKey(module.id)
        if (preferences.contains(key)) {
            return preferences.getBoolean(key, module.defaultEnabled)
        }
        return legacyEnabled(context, module.id) ?: module.defaultEnabled
    }

    fun isEnabled(context: Context, moduleId: String): Boolean? {
        val module = ImportPatchRegistry.moduleForId(moduleId) ?: return null
        return isEnabled(context, module)
    }

    fun setEnabled(context: Context, module: ImportPatchModule, enabled: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(enabledKey(module.id), enabled)
            .apply()
        synchronizeLegacySetting(context, module.id, enabled)
    }

    fun setEnabled(context: Context, moduleId: String, enabled: Boolean): Boolean {
        val module = ImportPatchRegistry.moduleForId(moduleId) ?: return false
        setEnabled(context, module, enabled)
        return true
    }

    fun resetToDefaults(context: Context) {
        preferences(context).edit().clear().apply()
        // Keep legacy readers at the same defaults after clearing manager overrides.
        ImportPatchRegistry.modules(context).forEach { module ->
            synchronizeLegacySetting(context, module.id, module.defaultEnabled)
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun legacyEnabled(context: Context, moduleId: String): Boolean? {
        return when (moduleId) {
            ManifestRootPatchModule.id -> CompatibilitySettings.isModManifestRootCompatEnabled(context)
            AtlasFilterPatchModule.id -> CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)
            AtlasOfflineDownscalePatchModule.id ->
                LauncherConfig.isWorkshopAutoImportAtlasDownscaleEnabled(context)

            FrierenImportPatchModule.id -> CompatibilitySettings.isFrierenModCompatEnabled(context)
            DownfallImportPatchModule.id -> CompatibilitySettings.isDownfallImportCompatEnabled(context)
            VupShionImportPatchModule.id -> CompatibilitySettings.isVupShionModCompatEnabled(context)
            ChaofanModImportPatchModule.id -> CompatibilitySettings.isChaofanModCompatEnabled(context)
            JacketNoAnoKoImportPatchModule.id ->
                CompatibilitySettings.isJacketNoAnoKoModCompatEnabled(context)

            else -> null
        }
    }

    private fun synchronizeLegacySetting(context: Context, moduleId: String, enabled: Boolean) {
        when (moduleId) {
            ManifestRootPatchModule.id ->
                CompatibilitySettings.setModManifestRootCompatEnabled(context, enabled)

            AtlasFilterPatchModule.id ->
                CompatibilitySettings.setGlobalAtlasFilterCompatEnabled(context, enabled)

            AtlasOfflineDownscalePatchModule.id ->
                LauncherConfig.setWorkshopAutoImportAtlasDownscaleEnabled(context, enabled)

            FrierenImportPatchModule.id ->
                CompatibilitySettings.setFrierenModCompatEnabled(context, enabled)

            DownfallImportPatchModule.id ->
                CompatibilitySettings.setDownfallImportCompatEnabled(context, enabled)

            VupShionImportPatchModule.id ->
                CompatibilitySettings.setVupShionModCompatEnabled(context, enabled)

            ChaofanModImportPatchModule.id ->
                CompatibilitySettings.setChaofanModCompatEnabled(context, enabled)

            JacketNoAnoKoImportPatchModule.id ->
                CompatibilitySettings.setJacketNoAnoKoModCompatEnabled(context, enabled)
        }
    }

    private fun enabledKey(moduleId: String): String = ENABLED_KEY_PREFIX + moduleId.trim()
}
