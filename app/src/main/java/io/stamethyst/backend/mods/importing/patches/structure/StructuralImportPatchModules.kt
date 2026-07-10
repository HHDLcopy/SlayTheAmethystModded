package io.stamethyst.backend.mods.importing.patches.structure

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.DuplicateZipEntryNormalizer
import io.stamethyst.backend.mods.ModManifestRootCompatPatcher
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.ImportPatchFailurePolicy
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.importString
import io.stamethyst.backend.mods.importing.patches.ImportPatchModule
import java.io.File

internal object DuplicateZipEntryPatchModule : ImportPatchModule {
    override val id = "structure.duplicate_zip_entries"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_zip_entry_title
    override val summaryResId = R.string.mod_import_patch_zip_entry_summary
    override val category = ImportPatchCategory.Structural
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 100
    override val failurePolicy = ImportPatchFailurePolicy.BlockImport

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
        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.changed,
            summary = if (result.changed) {
                context.importString(R.string.mod_import_patch_zip_entry_applied)
            } else {
                context.importString(R.string.mod_import_patch_zip_entry_noop)
            },
            details = listOf(
                context.importString(R.string.mod_import_patch_zip_entry_detail_total, result.totalEntries),
                context.importString(R.string.mod_import_patch_zip_entry_detail_unique, result.uniqueEntries),
                context.importString(R.string.mod_import_patch_zip_entry_detail_removed, result.duplicateEntriesRemoved)
            ),
            metrics = mapOf(
                "totalEntries" to result.totalEntries,
                "uniqueEntries" to result.uniqueEntries,
                "duplicateEntriesRemoved" to result.duplicateEntriesRemoved
            )
        )
    }
}

internal object ManifestRootPatchModule : ImportPatchModule {
    override val id = "structure.manifest_root"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_manifest_root_title
    override val summaryResId = R.string.mod_import_patch_manifest_root_summary
    override val category = ImportPatchCategory.Structural
    override val defaultEnabled = true
    override val userConfigurable = false
    override val order = 200
    override val failurePolicy = ImportPatchFailurePolicy.BlockImport

    override fun isAvailable(context: Context): Boolean {
        return CompatibilitySettings.isModManifestRootCompatEnabled(context)
    }

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        val details = item.patchPlans.firstOrNull { it.moduleId == id }?.details.orEmpty()
        return basePlan(applicable = true, details = details)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.hasPatchedChanges,
            summary = if (result.hasPatchedChanges) {
                context.importString(R.string.mod_import_patch_manifest_root_applied)
            } else {
                context.importString(R.string.mod_import_patch_manifest_root_noop)
            },
            details = listOf(
                context.importString(R.string.mod_import_patch_manifest_root_detail_moved, result.patchedFileEntries),
                context.importString(
                    R.string.mod_import_patch_manifest_root_detail_prefix,
                    result.sourceRootPrefix.ifBlank {
                        context.importString(R.string.mod_import_patch_manifest_root_prefix_none)
                    }
                )
            ),
            metrics = mapOf(
                "patchedFileEntries" to result.patchedFileEntries
            ),
            attributes = mapOf(
                "sourceRootPrefix" to result.sourceRootPrefix
            )
        )
    }
}
