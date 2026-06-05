package io.stamethyst.ui.main

import android.content.Context
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModManifestNameRewriter
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap

internal data class ModManifestNameMigrationResult(
    val appliedCount: Int,
    val rewrittenCount: Int,
    val failedCount: Int
) {
    val attemptedCount: Int
        get() = appliedCount + failedCount
}

internal data class ModManifestNameMigrationSpaceCheck(
    val pendingCount: Int,
    val rewriteCount: Int,
    val requiredExtraBytes: Long,
    val availableBytes: Long,
    val hasEnoughSpace: Boolean,
    val consumesLegacyFileNameState: Boolean
) {
    val hasPendingMigration: Boolean
        get() = pendingCount > 0 || consumesLegacyFileNameState
}

internal data class ModManifestNameMigrationProgress(
    val currentIndex: Int,
    val completedCount: Int,
    val totalCount: Int,
    val currentModName: String,
    val currentStoragePath: String,
    val progressPercent: Int
)

internal class ModManifestNameMigrationStorageException(
    val spaceCheck: ModManifestNameMigrationSpaceCheck
) : IOException("Not enough free space for mod name migration")

internal object ModManifestNameMigration {
    private data class PendingNameRewrite(
        val jarFile: File,
        val storagePath: String,
        val targetName: String,
        val displayName: String,
        val clearsAlias: Boolean,
        val requiresRewrite: Boolean,
        val migrationWorkBytes: Long
    ) {
        val requiredExtraBytes: Long
            get() = if (requiresRewrite) {
                requiredExtraBytesForJar(jarFile.length())
            } else {
                0L
            }

        val availableBytes: Long
            get() = jarFile.parentFile?.usableSpace ?: jarFile.usableSpace
    }

    private data class MigrationPlan(
        val pendingRewrites: List<PendingNameRewrite>,
        val consumesLegacyFileNameState: Boolean
    )

    fun evaluateStoredNameMigrationSpace(context: Context): ModManifestNameMigrationSpaceCheck {
        return evaluateSpace(buildStoredNameMigrationPlan(context))
    }

    fun evaluateFileNameMigrationSpace(context: Context): ModManifestNameMigrationSpaceCheck {
        return evaluateSpace(buildFileNameMigrationPlan(context))
    }

    @Throws(ModManifestNameMigrationStorageException::class)
    fun migrateStoredNamesIfNeeded(
        context: Context,
        requireSufficientSpace: Boolean = false,
        onProgress: ((ModManifestNameMigrationProgress) -> Unit)? = null
    ): ModManifestNameMigrationResult {
        val plan = buildStoredNameMigrationPlan(context)
        if (plan.pendingRewrites.isEmpty() && !plan.consumesLegacyFileNameState) {
            return ModManifestNameMigrationResult(0, 0, 0)
        }
        if (requireSufficientSpace) {
            requireEnoughSpace(plan)
        }

        val result = rewritePlannedNames(context, plan, onProgress)
        if (plan.consumesLegacyFileNameState && result.failedCount == 0) {
            LauncherPreferences.saveShowModFileName(context, false)
            ModAliasStore.markShowFileNameRemovalNoticeHandled(context)
        }
        return result
    }

    @Throws(ModManifestNameMigrationStorageException::class)
    fun applyFileNamesToInstalledOptionalMods(
        context: Context,
        requireSufficientSpace: Boolean = false,
        onProgress: ((ModManifestNameMigrationProgress) -> Unit)? = null
    ): ModManifestNameMigrationResult {
        val plan = buildFileNameMigrationPlan(context)
        if (requireSufficientSpace) {
            requireEnoughSpace(plan)
        }
        return rewritePlannedNames(context, plan, onProgress)
    }

    private fun buildStoredNameMigrationPlan(context: Context): MigrationPlan {
        val aliases = ModAliasStore.loadAliases(context)
        val migrateLegacyFileNames = LauncherPreferences.readShowModFileName(context) &&
            !ModAliasStore.isShowFileNameRemovalNoticeHandled(context)
        if (aliases.isEmpty() && !migrateLegacyFileNames) {
            return MigrationPlan(emptyList(), consumesLegacyFileNameState = false)
        }
        return buildMigrationPlan(
            context = context,
            aliases = aliases,
            useFileNamesWhenAliasMissing = migrateLegacyFileNames,
            forceFileNames = false,
            consumesLegacyFileNameState = migrateLegacyFileNames
        )
    }

    private fun buildFileNameMigrationPlan(context: Context): MigrationPlan {
        return buildMigrationPlan(
            context = context,
            aliases = emptyMap(),
            useFileNamesWhenAliasMissing = true,
            forceFileNames = true,
            consumesLegacyFileNameState = false
        )
    }

    private fun buildMigrationPlan(
        context: Context,
        aliases: Map<String, String>,
        useFileNamesWhenAliasMissing: Boolean,
        forceFileNames: Boolean,
        consumesLegacyFileNameState: Boolean
    ): MigrationPlan {
        val pending = ArrayList<PendingNameRewrite>()
        val aliasesByPath = LinkedHashMap(aliases)

        ModManager.listInstalledMods(context).forEach { mod ->
            if (mod.required || !mod.installed || !mod.jarFile.isFile) {
                return@forEach
            }
            val storagePath = mod.jarFile.absolutePath
            val alias = if (forceFileNames) {
                ""
            } else {
                ModAliasStore.resolveAlias(storagePath, aliasesByPath).trim()
            }
            val targetName = when {
                alias.isNotEmpty() -> alias
                useFileNamesWhenAliasMissing ->
                    resolveModFileNameWithoutJar(storagePath).orEmpty().trim()
                else -> ""
            }
            if (targetName.isEmpty()) {
                return@forEach
            }
            val manifestName = mod.name.trim()
            val requiresRewrite = targetName != manifestName
            val clearsAlias = alias.isNotEmpty() || forceFileNames

            if (!requiresRewrite && !clearsAlias && !consumesLegacyFileNameState) {
                return@forEach
            }
            pending += PendingNameRewrite(
                jarFile = mod.jarFile,
                storagePath = storagePath,
                targetName = targetName,
                displayName = resolveMigrationDisplayName(
                    targetName = targetName,
                    manifestName = mod.name,
                    storagePath = storagePath
                ),
                clearsAlias = clearsAlias,
                requiresRewrite = requiresRewrite,
                migrationWorkBytes = if (requiresRewrite) {
                    mod.jarFile.length().coerceAtLeast(1L)
                } else {
                    1L
                }
            )
        }

        return MigrationPlan(
            pendingRewrites = pending,
            consumesLegacyFileNameState = consumesLegacyFileNameState
        )
    }

    private fun rewritePlannedNames(
        context: Context,
        plan: MigrationPlan,
        onProgress: ((ModManifestNameMigrationProgress) -> Unit)?
    ): ModManifestNameMigrationResult {
        var appliedCount = 0
        var rewrittenCount = 0
        var failedCount = 0
        var completedWorkBytes = 0L
        val totalWorkBytes = plan.pendingRewrites
            .fold(0L) { total, pending -> total.saturatingAdd(pending.migrationWorkBytes) }
            .coerceAtLeast(1L)
        var lastReportedProgress: ModManifestNameMigrationProgress? = null

        fun reportProgress(
            pending: PendingNameRewrite,
            itemIndex: Int,
            itemWorkBytes: Long,
            completedCount: Int
        ) {
            val currentWorkBytes = itemWorkBytes
                .coerceAtLeast(0L)
                .coerceAtMost(pending.migrationWorkBytes)
            val absoluteWorkBytes = completedWorkBytes
                .saturatingAdd(currentWorkBytes)
                .coerceAtMost(totalWorkBytes)
            val progress = ModManifestNameMigrationProgress(
                currentIndex = (itemIndex + 1).coerceIn(1, plan.pendingRewrites.size.coerceAtLeast(1)),
                completedCount = completedCount.coerceIn(0, plan.pendingRewrites.size),
                totalCount = plan.pendingRewrites.size,
                currentModName = pending.displayName,
                currentStoragePath = pending.storagePath,
                progressPercent = progressPercent(absoluteWorkBytes, totalWorkBytes)
            )
            val last = lastReportedProgress
            if (last == null ||
                progress.progressPercent != last.progressPercent ||
                progress.currentIndex != last.currentIndex ||
                progress.currentModName != last.currentModName
            ) {
                lastReportedProgress = progress
                onProgress?.invoke(progress)
            }
        }

        plan.pendingRewrites.forEachIndexed { index, pending ->
            reportProgress(
                pending = pending,
                itemIndex = index,
                itemWorkBytes = 0L,
                completedCount = index
            )
            try {
                val rewriteResult = if (pending.requiresRewrite) {
                    ModManifestNameRewriter.rewriteNameInPlace(
                        modJar = pending.jarFile,
                        requestedName = pending.targetName,
                        onRewriteProgress = { bytesRead ->
                            reportProgress(
                                pending = pending,
                                itemIndex = index,
                                itemWorkBytes = bytesRead,
                                completedCount = index
                            )
                        }
                    )
                } else {
                    null
                }
                appliedCount++
                if (rewriteResult?.changed == true) {
                    rewrittenCount++
                }
                if (pending.clearsAlias) {
                    ModAliasStore.setAlias(context, pending.storagePath, "")
                }
            } catch (_: Throwable) {
                failedCount++
            }
            completedWorkBytes = completedWorkBytes.saturatingAdd(pending.migrationWorkBytes)
            reportProgress(
                pending = pending,
                itemIndex = index,
                itemWorkBytes = pending.migrationWorkBytes,
                completedCount = index + 1
            )
        }

        return ModManifestNameMigrationResult(
            appliedCount = appliedCount,
            rewrittenCount = rewrittenCount,
            failedCount = failedCount
        )
    }

    private fun resolveMigrationDisplayName(
        targetName: String,
        manifestName: String,
        storagePath: String
    ): String {
        return targetName.trim()
            .ifEmpty { manifestName.trim() }
            .ifEmpty { resolveModFileNameWithoutJar(storagePath).orEmpty().trim() }
            .ifEmpty { File(storagePath).name }
    }

    @Throws(ModManifestNameMigrationStorageException::class)
    private fun requireEnoughSpace(plan: MigrationPlan) {
        val spaceCheck = evaluateSpace(plan)
        if (spaceCheck.hasPendingMigration && !spaceCheck.hasEnoughSpace) {
            throw ModManifestNameMigrationStorageException(spaceCheck)
        }
    }

    private fun evaluateSpace(plan: MigrationPlan): ModManifestNameMigrationSpaceCheck {
        var pendingCount = 0
        var rewriteCount = 0
        var maxRequiredExtraBytes = 0L
        var minAvailableBytes = Long.MAX_VALUE
        var insufficientRequiredBytes = 0L
        var insufficientAvailableBytes = 0L

        plan.pendingRewrites.forEach { pending ->
            pendingCount++
            val required = pending.requiredExtraBytes
            val available = pending.availableBytes
            if (pending.requiresRewrite) {
                rewriteCount++
            }
            if (required > maxRequiredExtraBytes) {
                maxRequiredExtraBytes = required
            }
            if (available < minAvailableBytes) {
                minAvailableBytes = available
            }
            if (required > 0L && available < required) {
                if (required - available > insufficientRequiredBytes - insufficientAvailableBytes) {
                    insufficientRequiredBytes = required
                    insufficientAvailableBytes = available.coerceAtLeast(0L)
                }
            }
        }

        val hasInsufficientEntry = insufficientRequiredBytes > 0L
        return ModManifestNameMigrationSpaceCheck(
            pendingCount = pendingCount,
            rewriteCount = rewriteCount,
            requiredExtraBytes = if (hasInsufficientEntry) {
                insufficientRequiredBytes
            } else {
                maxRequiredExtraBytes
            },
            availableBytes = if (pendingCount == 0) {
                0L
            } else if (hasInsufficientEntry) {
                insufficientAvailableBytes
            } else {
                minAvailableBytes.coerceAtLeast(0L)
            },
            hasEnoughSpace = !hasInsufficientEntry,
            consumesLegacyFileNameState = plan.consumesLegacyFileNameState
        )
    }

    private fun requiredExtraBytesForJar(length: Long): Long {
        val safeLength = length.coerceAtLeast(0L)
        val margin = (safeLength / 20L)
            .coerceAtLeast(MIN_REWRITE_SPACE_MARGIN_BYTES)
            .coerceAtMost(MAX_REWRITE_SPACE_MARGIN_BYTES)
        return safeLength.saturatingAdd(margin)
    }

    private fun Long.saturatingAdd(other: Long): Long {
        if (other <= 0L) {
            return this
        }
        return if (this > Long.MAX_VALUE - other) {
            Long.MAX_VALUE
        } else {
            this + other
        }
    }

    private fun progressPercent(completedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) {
            return 0
        }
        if (completedBytes >= totalBytes) {
            return 100
        }
        return ((completedBytes.toDouble() / totalBytes.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private const val MIN_REWRITE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L
    private const val MAX_REWRITE_SPACE_MARGIN_BYTES = 128L * 1024L * 1024L
}
