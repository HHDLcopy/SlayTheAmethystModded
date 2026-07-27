package io.stamethyst.backend.mods

/** Records the version of one import patch that changed a stored mod jar. */
internal data class ImportedModPatchRecord(
    val moduleId: String,
    val version: Int,
)

internal data class ImportedModPatchInfo(
    val modId: String,
    val modName: String,
    val patchedAtlasEntries: Int = 0,
    val patchedFilterLines: Int = 0,
    val downscaledAtlasEntries: Int = 0,
    val downscaledAtlasPageEntries: Int = 0,
    val downscaledAtlasRuntimeMemorySavedMb: Int = 0,
    val patchedManifestRootEntries: Int = 0,
    val patchedManifestRootPrefix: String = "",
    val patchedFrierenAntiPirateMethod: Boolean = false,
    val patchedDownfallClassEntries: Int = 0,
    val patchedDownfallMerchantClassEntries: Int = 0,
    val patchedDownfallHexaghostBodyClassEntries: Int = 0,
    val patchedDownfallBossMechanicPanelClassEntries: Int = 0,
    val patchedVupShionWebButtonConstructor: Boolean = false,
    val patchedChaofanModSteamworksHelperInitialization: Boolean = false,
    val patchedJacketNoAnoKoShaderEntries: Int = 0,
    val patchedJacketNoAnoKoDesktopVersionDirectives: Int = 0,
    val patchedJacketNoAnoKoFragmentPrecisionBlocks: Int = 0,
    val patchedOriShaderEntries: Int = 0,
    val patchedOriGaussianBlurShaderEntries: Int = 0,
    val patchedOriBoxBlurShaderEntries: Int = 0,
    val patchedOriTextureSamplesBefore: Int = 0,
    val patchedOriTextureSamplesAfter: Int = 0,
    val appliedPatches: List<ImportedModPatchRecord> = emptyList(),
) {
    val wasAtlasPatched: Boolean
        get() = patchedFilterLines > 0
    val wasAtlasDownscaled: Boolean
        get() = downscaledAtlasPageEntries > 0
    val wasManifestRootPatched: Boolean
        get() = patchedManifestRootEntries > 0
    val wasFrierenAntiPiratePatched: Boolean
        get() = patchedFrierenAntiPirateMethod
    val wasDownfallPatched: Boolean
        get() = patchedDownfallClassEntries > 0
    val wasVupShionPatched: Boolean
        get() = patchedVupShionWebButtonConstructor
    val wasChaofanModPatched: Boolean
        get() = patchedChaofanModSteamworksHelperInitialization
    val wasJacketNoAnoKoPatched: Boolean
        get() = patchedJacketNoAnoKoShaderEntries > 0
    val wasOriRenderShaderPatched: Boolean
        get() = patchedOriShaderEntries > 0

    fun appliedPatchVersion(moduleId: String): Int? {
        val normalizedModuleId = moduleId.trim()
        if (normalizedModuleId.isEmpty()) return null
        return appliedPatches
            .asSequence()
            .filter { it.moduleId == normalizedModuleId }
            .map { it.version }
            .maxOrNull()
    }

    val hasCompatibilityPatches: Boolean
        get() = appliedPatches.isNotEmpty() ||
            wasAtlasPatched ||
            wasAtlasDownscaled ||
            wasManifestRootPatched ||
            wasFrierenAntiPiratePatched ||
            wasDownfallPatched ||
            wasVupShionPatched ||
            wasChaofanModPatched ||
            wasJacketNoAnoKoPatched ||
            wasOriRenderShaderPatched

    /**
     * Metadata written before the versioned manager only carries aggregate patch metrics. Those
     * metrics still identify which patch changed the jar, so import them as the legacy v0 format.
     * The old duplicate-entry normalizer had no dedicated metric. It ran before the other
     * compatibility work, so an entry with any legacy compatibility metric conservatively records
     * that structural patch as v0 as well.
     */
    internal fun inferLegacyAppliedPatches(): List<ImportedModPatchRecord> {
        val moduleIds = ArrayList<String>()
        if (hasLegacyAggregateCompatibilityPatches) {
            moduleIds += DUPLICATE_ZIP_ENTRY_PATCH_MODULE_ID
        }
        if (wasAtlasPatched) moduleIds += "texture.atlas_filter"
        if (wasAtlasDownscaled) moduleIds += "texture.atlas_offline_downscale"
        if (wasManifestRootPatched) moduleIds += "structure.manifest_root"
        if (wasFrierenAntiPiratePatched) moduleIds += "mod.frieren.anti_pirate"
        if (wasDownfallPatched) moduleIds += "mod.downfall.mobile_layout"
        if (wasVupShionPatched) moduleIds += "mod.vupshion.startup_compat"
        if (wasChaofanModPatched) moduleIds += "mod.chaofanmod.steamworks"
        if (wasJacketNoAnoKoPatched) moduleIds += "mod.jacketnoanoko.shader"
        if (wasOriRenderShaderPatched) moduleIds += "mod.ori.fast_blur"
        return moduleIds.map { moduleId ->
            ImportedModPatchRecord(moduleId = moduleId, version = LEGACY_PATCH_VERSION)
        }
    }

    private val hasLegacyAggregateCompatibilityPatches: Boolean
        get() = wasAtlasPatched ||
            wasAtlasDownscaled ||
            wasManifestRootPatched ||
            wasFrierenAntiPiratePatched ||
            wasDownfallPatched ||
            wasVupShionPatched ||
            wasChaofanModPatched ||
            wasJacketNoAnoKoPatched ||
            wasOriRenderShaderPatched

    private companion object {
        const val LEGACY_PATCH_VERSION = 0
        const val DUPLICATE_ZIP_ENTRY_PATCH_MODULE_ID = "structure.duplicate_zip_entries"
    }
}
