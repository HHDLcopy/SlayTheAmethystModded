package io.stamethyst.backend.mods.importing.patches.texture

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.backend.mods.ModAtlasOfflineDownscalePatcher
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.ImportPatchFailurePolicy
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.importString
import io.stamethyst.backend.mods.importing.patches.ImportPatchModule
import java.io.File

internal object AtlasFilterPatchModule : ImportPatchModule {
    override val id = "texture.atlas_filter"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_atlas_filter_title
    override val summaryResId = R.string.mod_import_patch_atlas_filter_summary
    override val category = ImportPatchCategory.Texture
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 300
    override val failurePolicy = ImportPatchFailurePolicy.BlockImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = ModAtlasFilterCompatPatcher.patchMipMapFiltersInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.hasPatchedChanges,
            summary = if (result.hasPatchedChanges) {
                context.importString(R.string.mod_import_patch_atlas_filter_applied)
            } else {
                context.importString(R.string.mod_import_patch_atlas_filter_noop)
            },
            details = listOf(
                context.importString(R.string.mod_import_patch_atlas_filter_detail_files, result.patchedAtlasEntries),
                context.importString(R.string.mod_import_patch_atlas_filter_detail_lines, result.patchedFilterLines)
            ),
            metrics = mapOf(
                "patchedAtlasEntries" to result.patchedAtlasEntries,
                "patchedFilterLines" to result.patchedFilterLines
            )
        )
    }
}

internal object AtlasOfflineDownscalePatchModule : ImportPatchModule {
    override val id = "texture.atlas_offline_downscale"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_atlas_downscale_title
    override val summaryResId = R.string.mod_import_patch_atlas_downscale_summary
    override val category = ImportPatchCategory.Texture
    override val defaultEnabled = false
    override val userConfigurable = true
    override val order = 400
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        return plan(context, item, item.source.file)
    }

    override fun plan(context: Context, item: ModImportItemPlan, inspectionJar: File): ImportPatchPlan? {
        val result = ModAtlasOfflineDownscalePatcher.inspectOversizedAtlasPages(
            inspectionJar,
            AtlasOfflineDownscaleStrategy.previewCandidates(),
            CompatibilitySettings.readImportDownscaleMaterialPolicy(context)
        )
        if (!result.hasPatchedChanges) {
            return null
        }
        return basePlan(
            applicable = true,
            details = listOf(
                context.importString(R.string.mod_import_patch_atlas_downscale_candidate_files, result.patchedAtlasEntries),
                context.importString(R.string.mod_import_patch_atlas_downscale_candidate_pages, result.downscaledPageEntries),
                context.importString(
                    R.string.mod_import_patch_atlas_downscale_detail_saved_memory,
                    formatRuntimeMemorySaved(result.estimatedRuntimeBytesSaved)
                )
            )
        )
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val strategy = decisions.atlasDownscaleStrategy
            ?: AtlasOfflineDownscaleStrategy.maxEdge(AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX)
        val result = ModAtlasOfflineDownscalePatcher.patchOversizedAtlasPagesInPlace(
            workingJar,
            strategy,
            CompatibilitySettings.readImportDownscaleMaterialPolicy(context)
        )
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.hasPatchedChanges,
            summary = if (result.hasPatchedChanges) {
                context.importString(R.string.mod_import_patch_atlas_downscale_applied)
            } else {
                context.importString(R.string.mod_import_patch_atlas_downscale_noop)
            },
            details = listOf(
                context.importString(R.string.mod_import_patch_atlas_downscale_detail_files, result.patchedAtlasEntries),
                context.importString(R.string.mod_import_patch_atlas_downscale_detail_pages, result.downscaledPageEntries),
                context.importString(R.string.mod_import_patch_atlas_downscale_detail_strategy, strategy.mode.name, strategy.value),
                context.importString(
                    R.string.mod_import_patch_atlas_downscale_detail_saved_memory,
                    formatRuntimeMemorySaved(result.estimatedRuntimeBytesSaved)
                )
            ),
            metrics = mapOf(
                "patchedAtlasEntries" to result.patchedAtlasEntries,
                "downscaledPageEntries" to result.downscaledPageEntries,
                "estimatedRuntimeBytesSavedMb" to bytesToWholeMegabytes(result.estimatedRuntimeBytesSaved)
            )
        )
    }

    private fun formatRuntimeMemorySaved(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 10.0) {
            String.format(java.util.Locale.US, "%.0f MB", mb)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    private fun bytesToWholeMegabytes(bytes: Long): Int {
        if (bytes <= 0L) return 0
        return (bytes / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
