package io.stamethyst.backend.steamcloud

import android.content.Context
import `in`.dragonbra.javasteam.enums.EResult
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal object SteamCloudPushCoordinator {
    private const val FAILURE_PATH_SAMPLE_LIMIT = 12
    // After a CompleteAppUploadBatch failure the coordinator polls the manifest to verify that
    // the batch changes are already visible server-side before deciding to surface the error.
    // Steam's eventual-consistency window can be several seconds, so use more attempts and
    // longer delays than the original 3 × 1 s that was too aggressive.
    private const val UPLOAD_BATCH_RECONCILIATION_MAX_ATTEMPTS = 6
    private val UPLOAD_BATCH_RECONCILIATION_DELAY_MS_VALUES = longArrayOf(
        2_000L, 5_000L, 10_000L, 15_000L, 20_000L,
    )

    private data class PlanUploadTelemetry(
        var clientInitMs: Long? = null,
        var connectMs: Long? = null,
        var logOnMs: Long? = null,
        var manifestRpcMs: Long? = null,
        var manifestMapMs: Long? = null,
        var manifestWriteMs: Long? = null,
        var baselineReadMs: Long? = null,
        var localSnapshotMs: Long? = null,
        var diffPlanMs: Long? = null,
        var totalMeasuredMs: Long? = null,
        var remoteEntryCount: Int? = null,
        var localEntryCount: Int? = null,
    )

    private data class PreparedMirrorPlan(
        val uploadCandidates: List<SteamCloudUploadCandidate>,
        val deleteRemotePaths: List<String>,
        val warnings: List<String>,
        val removedDeleteOverlaps: List<String>,
    )

    @Throws(Exception::class)
    fun buildUploadPlan(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        shouldContinue: () -> Boolean = { true },
        allowReconnectRetry: Boolean = true,
    ): SteamCloudUploadPlan {
        val startedAtMs = System.currentTimeMillis()
        val totalStartedAtNs = System.nanoTime()
        val clientInitStartedAtNs = System.nanoTime()
        val client = SteamCloudClient(host)
        val telemetry = PlanUploadTelemetry(
            clientInitMs = elapsedMs(clientInitStartedAtNs),
        )
        try {
            client.use {
                client.beginOperationDiagnostics(
                    "plan_upload",
                    authMaterial.accountName,
                    authMaterial.guardData.isNotBlank(),
                )
                val connectStartedAtNs = System.nanoTime()
                client.start()
                telemetry.connectMs = elapsedMs(connectStartedAtNs)
                val logOnStartedAtNs = System.nanoTime()
                client.logOnWithRefreshToken(
                    authMaterial.accountName,
                    authMaterial.refreshToken,
                    authMaterial.steamId64,
                )
                telemetry.logOnMs = elapsedMs(logOnStartedAtNs)

                val manifestRpcStartedAtNs = System.nanoTime()
                val remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID)
                telemetry.manifestRpcMs = elapsedMs(manifestRpcStartedAtNs)
                telemetry.remoteEntryCount = remoteEntries.size

                val manifestMapStartedAtNs = System.nanoTime()
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = remoteEntries,
                )
                telemetry.manifestMapMs = elapsedMs(manifestMapStartedAtNs)

                val manifestWriteStartedAtNs = System.nanoTime()
                SteamCloudManifestStore.writeSnapshot(host, snapshot)
                SteamCloudAuthStore.recordManifestSuccess(host, snapshot.fetchedAtMs)
                telemetry.manifestWriteMs = elapsedMs(manifestWriteStartedAtNs)

                val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
                val filteredSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
                    snapshot = snapshot,
                    configuredBlacklist = syncBlacklist,
                )

                val baselineReadStartedAtNs = System.nanoTime()
                val baseline = SteamCloudSyncBlacklist.filterBaseline(
                    baseline = SteamCloudBaselineStore.readSnapshot(host),
                    configuredBlacklist = syncBlacklist,
                )
                telemetry.baselineReadMs = elapsedMs(baselineReadStartedAtNs)

                val localSnapshotStartedAtNs = System.nanoTime()
                val localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                    entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                    configuredBlacklist = syncBlacklist,
                )
                telemetry.localSnapshotMs = elapsedMs(localSnapshotStartedAtNs)
                telemetry.localEntryCount = localEntries.size

                val diffPlanStartedAtNs = System.nanoTime()
                val plan = SteamCloudDiffPlanner.buildUploadPlan(
                    plannedAtMs = System.currentTimeMillis(),
                    currentLocalEntries = localEntries,
                    currentRemoteSnapshot = filteredSnapshot,
                    baseline = baseline,
                )
                val baselineRefreshed = plan.isAlreadySynced() && shouldContinue()
                if (baselineRefreshed) {
                    SteamCloudBaselineStore.writeSnapshot(
                        host,
                        SteamCloudSyncBaseline(
                            syncedAtMs = System.currentTimeMillis(),
                            localEntries = localEntries,
                            remoteEntries = filteredSnapshot.entries,
                        )
                    )
                }
                telemetry.diffPlanMs = elapsedMs(diffPlanStartedAtNs)
                telemetry.totalMeasuredMs = elapsedMs(totalStartedAtNs)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "plan_upload",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    extraLines = buildList {
                        addAll(planUploadTimingLines(telemetry))
                        add("Manifest files: ${snapshot.fileCount}")
                        add("Upload candidates: ${plan.uploadCandidates.size}")
                        add("Remote delete candidates: ${plan.remoteDeleteCandidates.size}")
                        add("Conflicts: ${plan.conflicts.size}")
                        add("Remote-only changes: ${plan.remoteOnlyChanges.size}")
                        add("Baseline configured: ${plan.baselineConfigured}")
                        add("Baseline refreshed: ${if (baselineRefreshed) "yes" else "no"}")
                    } + plan.warnings.map { "Warning: $it" },
                )
                return plan
            }
        } catch (error: Throwable) {
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry && shouldContinue() && isReconnectRetryCandidate(error, failureDiagnostics)) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                return buildUploadPlan(
                    host = host,
                    authMaterial = authMaterial,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                )
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                telemetry.totalMeasuredMs = elapsedMs(totalStartedAtNs)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "plan_upload",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = buildList {
                        addAll(planUploadTimingLines(telemetry))
                        add("Existing guard data provided: ${if (authMaterial.guardData.isBlank()) "no" else "yes"}")
                    },
                )
            }
            throw error
        }
    }

    @Throws(Exception::class)
    fun pushLocalChanges(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        plan: SteamCloudUploadPlan,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
        allowReconnectRetry: Boolean = true,
    ): SteamCloudPushResult {
        require(plan.conflicts.isEmpty()) {
            "Steam Cloud push was requested with unresolved conflicts."
        }
        require(plan.uploadCandidates.isNotEmpty() || plan.remoteDeleteCandidates.isNotEmpty()) {
            "Steam Cloud push was requested with no upload or delete candidates."
        }
        require(plan.remoteDeleteCandidates.all { it.rootKind == SteamCloudRootKind.SAVES }) {
            "Steam Cloud push attempted to delete non-save files."
        }

        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        var uploadBatch: SteamCloudClient.UploadBatch? = null
        var uploadedBytes = 0L
        var uploadedFileCount = 0
        val totalOperations = plan.syncOperationCount()

        try {
            client.beginOperationDiagnostics(
                "manual_push",
                authMaterial.accountName,
                authMaterial.guardData.isNotBlank(),
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.CONNECTING,
                    progressPercent = 5,
                )
            )
            client.start()
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.LOGGING_ON,
                    progressPercent = 12,
                )
            )
            client.logOnWithRefreshToken(
                authMaterial.accountName,
                authMaterial.refreshToken,
                authMaterial.steamId64,
            )
            ensureNotCancelled(shouldContinue)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.PREPARING_UPLOAD,
                    completedFiles = 0,
                    totalFiles = totalOperations,
                    progressPercent = 20,
                )
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.CREATING_UPLOAD_BATCH,
                    completedFiles = 0,
                    totalFiles = totalOperations,
                    progressPercent = 24,
                )
            )
            uploadBatch = client.beginUploadBatch(
                STEAM_CLOUD_APP_ID,
                plan.uploadCandidates.map { it.remotePath },
                plan.remoteDeleteCandidates.map { it.remotePath },
            )
            ensureNotCancelled(shouldContinue)

            val totalUploads = plan.uploadCandidates.size
            plan.uploadCandidates.forEachIndexed { index, candidate ->
                ensureNotCancelled(shouldContinue)
                val sourceFile = File(
                    RuntimePaths.stsRoot(host),
                    candidate.localRelativePath.replace('/', File.separatorChar)
                )
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.REQUESTING_UPLOAD_SLOT,
                        completedFiles = index + 1,
                        totalFiles = totalOperations,
                        currentPath = candidate.localRelativePath,
                        progressPercent = 28 + ((index * 55) / totalUploads),
                    )
                )
                val uploadedFile = try {
                    client.uploadFile(
                        STEAM_CLOUD_APP_ID,
                        candidate.remotePath,
                        sourceFile,
                        requireNotNull(uploadBatch).batchId,
                    )
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "Steam Cloud upload failed for ${candidate.remotePath} (${candidate.localRelativePath}, localSha1=${candidate.sha1.ifBlank { "<none>" }}, size=${candidate.fileSize}, batchId=${requireNotNull(uploadBatch).batchId}): ${summarizeErrorWithCauses(error)}",
                        error,
                    )
                }
                ensureNotCancelled(shouldContinue)
                uploadedBytes += uploadedFile.fileSize
                uploadedFileCount = index + 1
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.UPLOADING,
                        completedFiles = index + 1,
                        totalFiles = totalOperations,
                        currentPath = candidate.localRelativePath,
                        progressPercent = 30 + (((index + 1) * 55) / totalUploads),
                    )
                )
            }
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.FINALIZING,
                    completedFiles = totalOperations,
                    totalFiles = totalOperations,
                    progressPercent = 92,
                )
            )
            ensureNotCancelled(shouldContinue)
            val completionRecoveredFromManifest = completeUploadBatchOrReconcile(
                client = client,
                batch = requireNotNull(uploadBatch),
                uploadCandidates = plan.uploadCandidates,
                deleteRemotePaths = plan.remoteDeleteCandidates.map { it.remotePath },
                shouldContinue = shouldContinue,
            )
            uploadBatch = null

            val refreshedSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
            )
            SteamCloudManifestStore.writeSnapshot(host, refreshedSnapshot)
            SteamCloudAuthStore.recordManifestSuccess(host, refreshedSnapshot.fetchedAtMs)

            val result = SteamCloudPushResult(
                uploadedFileCount = plan.uploadCandidates.size,
                uploadedBytes = uploadedBytes,
                deletedRemoteFileCount = plan.remoteDeleteCandidates.size,
                completedAtMs = System.currentTimeMillis(),
                summaryPath = SteamCloudManifestStore.pushSummaryFile(host).absolutePath,
                warnings = plan.warnings + refreshedSnapshot.warnings,
            )
            val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
            SteamCloudBaselineStore.writeSnapshot(
                host,
                SteamCloudSyncBaseline(
                    syncedAtMs = result.completedAtMs,
                    localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                        entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                        configuredBlacklist = syncBlacklist,
                    ),
                    remoteEntries = SteamCloudSyncBlacklist.filterManifestSnapshot(
                        snapshot = refreshedSnapshot,
                        configuredBlacklist = syncBlacklist,
                    ).entries,
                )
            )
            writePushSummary(
                host = host,
                plan = plan,
                snapshot = refreshedSnapshot,
                result = result,
            )
            SteamCloudAuthStore.recordPushSuccess(host, result.completedAtMs)
            SteamCloudDiagnosticsStore.writeSummary(
                context = host,
                operation = "manual_push",
                outcome = "SUCCESS",
                accountName = authMaterial.accountName,
                startedAtMs = startedAtMs,
                completedAtMs = result.completedAtMs,
                diagnostics = client.snapshotDiagnostics(),
                extraLines = listOf(
                    "Uploaded files: ${result.uploadedFileCount}",
                    "Uploaded bytes: ${result.uploadedBytes}",
                    "Deleted remote files: ${result.deletedRemoteFileCount}",
                    "Upload summary: ${result.summaryPath}",
                    "Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}",
                    "Baseline path: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}",
                    "Upload batch completion recovered from manifest: ${if (completionRecoveredFromManifest) "yes" else "no"}",
                ) + result.warnings.distinct().map { "Warning: $it" },
            )
            return result
        } catch (error: Throwable) {
            var uploadBatchCompletionError: Throwable? = null
            uploadBatch?.let { batch ->
                runCatching {
                    client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.Fail)
                }.onFailure { completionError ->
                    uploadBatchCompletionError = completionError
                }
            }
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry &&
                uploadedFileCount == 0 &&
                shouldContinue() &&
                isReconnectRetryCandidate(error, failureDiagnostics)
            ) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                client.close()
                return pushLocalChanges(
                    host = host,
                    authMaterial = authMaterial,
                    plan = plan,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                )
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "manual_push",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = buildList {
                        add("Upload candidates before failure: ${plan.uploadCandidates.size}")
                        add("Remote delete candidates before failure: ${plan.remoteDeleteCandidates.size}")
                        add("Conflicts before failure: ${plan.conflicts.size}")
                        uploadBatch?.let { batch ->
                            add("Upload batch id: ${batch.batchId}")
                        }
                        uploadBatchCompletionError?.let { completionError ->
                            add("Upload batch failure completion failed: ${summarizeErrorWithCauses(completionError)}")
                        }
                        plan.warnings.forEach { warning -> add("Warning: $warning") }
                    },
                )
            }
            throw error
        } finally {
            client.close()
        }
    }

    @Throws(Exception::class)
    fun overwriteRemoteWithLocal(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        sourceRoot: File = RuntimePaths.stsRoot(host),
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
        allowReconnectRetry: Boolean = true,
    ): SteamCloudPushResult {
        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        var uploadBatch: SteamCloudClient.UploadBatch? = null
        var preparedPlan: PreparedMirrorPlan? = null
        var uploadedBytes = 0L
        var uploadedFileCount = 0

        try {
            client.beginOperationDiagnostics(
                "force_push",
                authMaterial.accountName,
                authMaterial.guardData.isNotBlank(),
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.CONNECTING,
                    progressPercent = 5,
                )
            )
            client.start()
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.LOGGING_ON,
                    progressPercent = 12,
                )
            )
            client.logOnWithRefreshToken(
                authMaterial.accountName,
                authMaterial.refreshToken,
                authMaterial.steamId64,
            )
            ensureNotCancelled(shouldContinue)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.REFRESHING_MANIFEST,
                    progressPercent = 20,
                )
            )

            val currentRemoteSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
            )
            SteamCloudManifestStore.writeSnapshot(host, currentRemoteSnapshot)
            SteamCloudAuthStore.recordManifestSuccess(host, currentRemoteSnapshot.fetchedAtMs)

            val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
            val localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                entries = SteamCloudLocalSnapshotCollector.collect(sourceRoot),
                configuredBlacklist = syncBlacklist,
            )
            preparedPlan = prepareMirrorPlan(
                SteamCloudMirrorPlanner.buildLocalMirrorPlan(
                    currentLocalEntries = localEntries,
                    currentRemoteSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
                        snapshot = currentRemoteSnapshot,
                        configuredBlacklist = syncBlacklist,
                    ),
                    baseline = SteamCloudSyncBlacklist.filterBaseline(
                        baseline = SteamCloudBaselineStore.readSnapshot(host),
                        configuredBlacklist = syncBlacklist,
                    ),
                )
            )
            ensureNotCancelled(shouldContinue)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.PREPARING_UPLOAD,
                    completedFiles = 0,
                    totalFiles = preparedPlan.uploadCandidates.size,
                    progressPercent = 28,
                )
            )

            if (preparedPlan.uploadCandidates.isNotEmpty() || preparedPlan.deleteRemotePaths.isNotEmpty()) {
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.CREATING_UPLOAD_BATCH,
                        completedFiles = 0,
                        totalFiles = preparedPlan.uploadCandidates.size,
                        progressPercent = 29,
                    )
                )
                uploadBatch = client.beginUploadBatch(
                    STEAM_CLOUD_APP_ID,
                    preparedPlan.uploadCandidates.map { it.remotePath },
                    preparedPlan.deleteRemotePaths,
                )
                ensureNotCancelled(shouldContinue)
            }

            val totalUploads = preparedPlan.uploadCandidates.size
            preparedPlan.uploadCandidates.forEachIndexed { index, candidate ->
                ensureNotCancelled(shouldContinue)
                val sourceFile = File(
                    sourceRoot,
                    candidate.localRelativePath.replace('/', File.separatorChar)
                )
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.REQUESTING_UPLOAD_SLOT,
                        completedFiles = index + 1,
                        totalFiles = totalUploads,
                        currentPath = candidate.localRelativePath,
                        progressPercent = if (totalUploads <= 0) {
                            85
                        } else {
                            30 + ((index * 55) / totalUploads)
                        },
                    )
                )
                val uploadedFile = try {
                    client.uploadFile(
                        STEAM_CLOUD_APP_ID,
                        candidate.remotePath,
                        sourceFile,
                        requireNotNull(uploadBatch).batchId,
                    )
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "Steam Cloud upload failed for ${candidate.remotePath} (${candidate.localRelativePath}, localSha1=${candidate.sha1.ifBlank { "<none>" }}, size=${candidate.fileSize}, batchId=${requireNotNull(uploadBatch).batchId}): ${summarizeErrorWithCauses(error)}",
                        error,
                    )
                }
                ensureNotCancelled(shouldContinue)
                uploadedBytes += uploadedFile.fileSize
                uploadedFileCount = index + 1
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.UPLOADING,
                        completedFiles = index + 1,
                        totalFiles = totalUploads,
                        currentPath = candidate.localRelativePath,
                        progressPercent = if (totalUploads <= 0) {
                            85
                        } else {
                            30 + (((index + 1) * 55) / totalUploads)
                        },
                    )
                )
            }

            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.FINALIZING,
                    completedFiles = totalUploads,
                    totalFiles = totalUploads,
                    progressPercent = 92,
                )
            )
            ensureNotCancelled(shouldContinue)
            var completionRecoveredFromManifest = false
            uploadBatch?.let { batch ->
                completionRecoveredFromManifest = completeUploadBatchOrReconcile(
                    client = client,
                    batch = batch,
                    uploadCandidates = preparedPlan.uploadCandidates,
                    deleteRemotePaths = preparedPlan.deleteRemotePaths,
                    shouldContinue = shouldContinue,
                )
                uploadBatch = null
            }

            val refreshedSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
            )
            SteamCloudManifestStore.writeSnapshot(host, refreshedSnapshot)
            SteamCloudAuthStore.recordManifestSuccess(host, refreshedSnapshot.fetchedAtMs)

            val result = SteamCloudPushResult(
                uploadedFileCount = preparedPlan.uploadCandidates.size,
                uploadedBytes = uploadedBytes,
                deletedRemoteFileCount = preparedPlan.deleteRemotePaths.size,
                completedAtMs = System.currentTimeMillis(),
                summaryPath = SteamCloudManifestStore.pushSummaryFile(host).absolutePath,
                warnings = currentRemoteSnapshot.warnings + preparedPlan.warnings + refreshedSnapshot.warnings,
            )
            SteamCloudBaselineStore.writeSnapshot(
                host,
                SteamCloudSyncBaseline(
                    syncedAtMs = result.completedAtMs,
                    localEntries = localEntries,
                    remoteEntries = SteamCloudSyncBlacklist.filterManifestSnapshot(
                        snapshot = refreshedSnapshot,
                        configuredBlacklist = syncBlacklist,
                    ).entries,
                )
            )
            writeMirrorPushSummary(
                host = host,
                plan = SteamCloudMirrorPlan(
                    uploadCandidates = preparedPlan.uploadCandidates,
                    deleteRemotePaths = preparedPlan.deleteRemotePaths,
                ),
                snapshot = refreshedSnapshot,
                result = result,
            )
            SteamCloudAuthStore.recordPushSuccess(host, result.completedAtMs)
            SteamCloudDiagnosticsStore.writeSummary(
                context = host,
                operation = "force_push",
                outcome = "SUCCESS",
                accountName = authMaterial.accountName,
                startedAtMs = startedAtMs,
                completedAtMs = result.completedAtMs,
                diagnostics = client.snapshotDiagnostics(),
                extraLines = listOf(
                    "Uploaded files: ${result.uploadedFileCount}",
                    "Uploaded bytes: ${result.uploadedBytes}",
                    "Deleted remote files: ${result.deletedRemoteFileCount}",
                    "Upload summary: ${result.summaryPath}",
                    "Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}",
                    "Baseline path: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}",
                    "Upload batch completion recovered from manifest: ${if (completionRecoveredFromManifest) "yes" else "no"}",
                ) + result.warnings.distinct().map { "Warning: $it" },
            )
            return result
        } catch (error: Throwable) {
            var uploadBatchCompletionError: Throwable? = null
            uploadBatch?.let { batch ->
                runCatching {
                    client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.Fail)
                }.onFailure { completionError ->
                    uploadBatchCompletionError = completionError
                }
            }
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry &&
                uploadedFileCount == 0 &&
                shouldContinue() &&
                isReconnectRetryCandidate(error, failureDiagnostics)
            ) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                client.close()
                return overwriteRemoteWithLocal(
                    host = host,
                    authMaterial = authMaterial,
                    sourceRoot = sourceRoot,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                )
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "force_push",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = buildList {
                        addAll(describePreparedMirrorPlan(preparedPlan))
                        uploadBatch?.let { batch ->
                            add("Upload batch id: ${batch.batchId}")
                        }
                        uploadBatchCompletionError?.let { completionError ->
                            add("Upload batch failure completion failed: ${summarizeErrorWithCauses(completionError)}")
                        }
                    },
                )
            }
            throw error
        } finally {
            client.close()
        }
    }

    private fun writePushSummary(
        host: Context,
        plan: SteamCloudUploadPlan,
        snapshot: SteamCloudManifestSnapshot,
        result: SteamCloudPushResult,
    ) {
        val summaryFile = SteamCloudManifestStore.pushSummaryFile(host)
        val parent = summaryFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud summary directory: ${parent.absolutePath}")
        }

        val lines = buildList {
            add("Steam Cloud push summary")
            add("")
            add("Completed At: ${formatTimestamp(result.completedAtMs)}")
            add("App ID: $STEAM_CLOUD_APP_ID")
            add("Uploaded Files: ${result.uploadedFileCount}")
            add("Uploaded Bytes: ${result.uploadedBytes}")
            if (result.deletedRemoteFileCount > 0) {
                add("Deleted Remote Files: ${result.deletedRemoteFileCount}")
            }
            add("Remote Files After Push: ${snapshot.fileCount}")
            add("Manifest: ${SteamCloudManifestStore.manifestFile(host).absolutePath}")
            add("Baseline: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}")
            if (plan.remoteOnlyChanges.isNotEmpty()) {
                add("Remote-only Changes Left Unmodified: ${plan.remoteOnlyChanges.size}")
            }
            if (plan.remoteDeleteCandidates.isNotEmpty()) {
                add("Deleted Remote Paths:")
                plan.remoteDeleteCandidates.forEach { candidate -> add(" - ${candidate.remotePath}") }
            }
            if (result.warnings.isNotEmpty()) {
                add("")
                add("Warnings:")
                result.warnings.distinct().forEach { add(" - $it") }
            }
        }
        summaryFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun writeMirrorPushSummary(
        host: Context,
        plan: SteamCloudMirrorPlan,
        snapshot: SteamCloudManifestSnapshot,
        result: SteamCloudPushResult,
    ) {
        val summaryFile = SteamCloudManifestStore.pushSummaryFile(host)
        val parent = summaryFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud summary directory: ${parent.absolutePath}")
        }

        val lines = buildList {
            add("Steam Cloud push summary")
            add("")
            add("Completed At: ${formatTimestamp(result.completedAtMs)}")
            add("App ID: $STEAM_CLOUD_APP_ID")
            add("Uploaded Files: ${result.uploadedFileCount}")
            add("Uploaded Bytes: ${result.uploadedBytes}")
            add("Deleted Remote Files: ${result.deletedRemoteFileCount}")
            add("Remote Files After Push: ${snapshot.fileCount}")
            add("Manifest: ${SteamCloudManifestStore.manifestFile(host).absolutePath}")
            add("Baseline: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}")
            if (plan.deleteRemotePaths.isNotEmpty()) {
                add("Deleted Remote Paths:")
                plan.deleteRemotePaths.forEach { add(" - $it") }
            }
            if (result.warnings.isNotEmpty()) {
                add("")
                add("Warnings:")
                result.warnings.distinct().forEach { add(" - $it") }
            }
        }
        summaryFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun reportProgress(
        progressCallback: ((SteamCloudSyncProgress) -> Unit)?,
        progress: SteamCloudSyncProgress,
    ) {
        progressCallback?.invoke(progress)
    }

    private fun completeUploadBatchOrReconcile(
        client: SteamCloudClient,
        batch: SteamCloudClient.UploadBatch,
        uploadCandidates: List<SteamCloudUploadCandidate>,
        deleteRemotePaths: List<String>,
        shouldContinue: () -> Boolean,
    ): Boolean {
        try {
            client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.OK)
            return false
        } catch (completionError: Throwable) {
            // completeUploadBatch already retried internally on EResult.Fail and other transient
            // codes.  If it still failed, check whether the changes landed anyway (the CM can
            // commit the batch server-side before returning a successful response, so the error
            // may be a false-negative in the protocol layer).
            for (attempt in 1..UPLOAD_BATCH_RECONCILIATION_MAX_ATTEMPTS) {
                ensureNotCancelled(shouldContinue)
                if (attempt > 1) {
                    val delayMs = UPLOAD_BATCH_RECONCILIATION_DELAY_MS_VALUES[
                        minOf(attempt - 2, UPLOAD_BATCH_RECONCILIATION_DELAY_MS_VALUES.size - 1)
                    ]
                    Thread.sleep(delayMs)
                }
                val remoteEntries = try {
                    client.listFiles(STEAM_CLOUD_APP_ID)
                } catch (manifestError: Throwable) {
                    completionError.addSuppressed(manifestError)
                    continue
                }
                if (uploadBatchChangesAreVisible(remoteEntries, uploadCandidates, deleteRemotePaths)) {
                    return true
                }
            }
            throw completionError
        }
    }

    internal fun uploadBatchChangesAreVisible(
        remoteEntries: List<SteamCloudClient.RemoteFileRecord>,
        uploadCandidates: List<SteamCloudUploadCandidate>,
        deleteRemotePaths: List<String>,
    ): Boolean {
        val entriesByPath = remoteEntries.associateBy { normalizeRemotePathKey(it.remotePath) }
        val uploadsVisible = uploadCandidates.all { candidate ->
            val remote = entriesByPath[normalizeRemotePathKey(candidate.remotePath)] ?: return@all false
            // Size must always match.
            if (remote.rawFileSize != candidate.fileSize) return@all false
            // Use SHA-1 comparison only when both sides have a hash; if either side is missing
            // the hash (e.g. older manifest entries or locally-collected entries without SHA-1)
            // fall back to size-only which is still a meaningful guard.
            val candidateSha1 = candidate.sha1
            val remoteSha1 = remote.sha1
            if (candidateSha1.isNotBlank() && remoteSha1.isNotBlank()) {
                candidateSha1.equals(remoteSha1, ignoreCase = true)
            } else {
                true
            }
        }
        val deletesVisible = deleteRemotePaths.none { remotePath ->
            normalizeRemotePathKey(remotePath) in entriesByPath
        }
        return uploadsVisible && deletesVisible
    }

    private fun ensureNotCancelled(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) {
            throw CancellationException("Steam Cloud sync cancelled by user.")
        }
    }

    private fun SteamCloudUploadPlan.isAlreadySynced(): Boolean =
        conflicts.isEmpty() &&
            uploadCandidates.isEmpty() &&
            remoteDeleteCandidates.isEmpty() &&
            remoteOnlyChanges.isEmpty()

    private fun SteamCloudUploadPlan.syncOperationCount(): Int =
        uploadCandidates.size + remoteDeleteCandidates.size

    private fun planUploadTimingLines(telemetry: PlanUploadTelemetry): List<String> = listOf(
        "Plan total measured ms: ${formatTimingMs(telemetry.totalMeasuredMs)}",
        "Client init ms: ${formatTimingMs(telemetry.clientInitMs)}",
        "Connect ms: ${formatTimingMs(telemetry.connectMs)}",
        "Logon ms: ${formatTimingMs(telemetry.logOnMs)}",
        "Manifest RPC ms: ${formatTimingMs(telemetry.manifestRpcMs)}",
        "Manifest map ms: ${formatTimingMs(telemetry.manifestMapMs)}",
        "Manifest write ms: ${formatTimingMs(telemetry.manifestWriteMs)}",
        "Baseline read ms: ${formatTimingMs(telemetry.baselineReadMs)}",
        "Local snapshot ms: ${formatTimingMs(telemetry.localSnapshotMs)}",
        "Diff plan ms: ${formatTimingMs(telemetry.diffPlanMs)}",
        "Remote entries: ${formatTimingCount(telemetry.remoteEntryCount)}",
        "Local entries: ${formatTimingCount(telemetry.localEntryCount)}",
    )

    private fun formatTimingMs(value: Long?): String = value?.toString() ?: "<not reached>"

    private fun formatTimingCount(value: Int?): String = value?.toString() ?: "<not reached>"

    private fun elapsedMs(startedAtNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs).coerceAtLeast(0L)

    private fun summarizeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            message
        } else {
            error.javaClass.simpleName
        }
    }

    private fun summarizeErrorWithCauses(error: Throwable): String {
        return generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .take(8)
            .joinToString(" <- ") { current ->
                val message = current.message?.trim().orEmpty()
                if (message.isNotEmpty()) {
                    "${current.javaClass.simpleName}: $message"
                } else {
                    current.javaClass.simpleName
                }
            }
    }

    private fun isReconnectRetryCandidate(
        error: Throwable,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
    ): Boolean {
        var sawReconnectableStage = diagnostics?.currentStage
            .orEmpty()
            .lowercase(Locale.US)
            .let { stage ->
                stage.contains("beginappuploadbatch") ||
                    stage.contains("beginhttpupload") ||
                    stage.contains("commithttpupload") ||
                    stage.contains("completeappuploadbatch") ||
                    stage.contains("getappfilechangelist")
            }
        var sawReconnectFailure = diagnostics?.disconnectedDescription
            .orEmpty()
            .lowercase(Locale.US)
            .let { description ->
                description.contains("unexpected") || description.contains("transport abort")
            }
        diagnostics?.diagnosticEventLines.orEmpty().forEach { eventLine ->
            val normalized = eventLine.lowercase(Locale.US)
            sawReconnectableStage = sawReconnectableStage ||
                normalized.contains("begin_app_upload_batch") ||
                normalized.contains("begin_http_upload") ||
                normalized.contains("http_upload") ||
                normalized.contains("upload_file failed") ||
                normalized.contains("getappfilechangelist")
            sawReconnectFailure = sawReconnectFailure ||
                normalized.contains("disconnected_callback reason=unexpected") ||
                normalized.contains("transport_abort_log") ||
                normalized.contains("software caused connection abort") ||
                normalized.contains("connection reset") ||
                normalized.contains("broken pipe")
        }
        var current: Throwable? = error
        while (current != null) {
            val normalized = current.message.orEmpty().lowercase(Locale.US)
            sawReconnectableStage = sawReconnectableStage ||
                normalized.contains("beginappuploadbatch") ||
                normalized.contains("beginhttpupload") ||
                normalized.contains("commithttpupload") ||
                normalized.contains("completeappuploadbatch") ||
                normalized.contains("getappfilechangelist")
            if ((normalized.contains("steam disconnected") && normalized.contains("unexpected")) ||
                normalized.contains("client or session is no longer active") ||
                normalized.contains("software caused connection abort") ||
                normalized.contains("connection reset") ||
                normalized.contains("broken pipe") ||
                current is SocketException
            ) {
                sawReconnectFailure = true
            }
            current = current.cause
        }
        return sawReconnectableStage && sawReconnectFailure
    }

    private fun prepareMirrorPlan(plan: SteamCloudMirrorPlan): PreparedMirrorPlan {
        val duplicateUploads = plan.uploadCandidates
            .groupBy { normalizeRemotePathKey(it.remotePath) }
            .values
            .filter { it.size > 1 }
        require(duplicateUploads.isEmpty()) {
            val sample = duplicateUploads
                .map { group -> group.first().remotePath }
                .sortedWith(compareBy<String>({ it.lowercase(Locale.ROOT) }, { it }))
                .take(FAILURE_PATH_SAMPLE_LIMIT)
                .joinToString(", ")
            "Steam Cloud local override planned duplicate upload paths: $sample"
        }

        val uploadCandidates = plan.uploadCandidates
            .distinctBy { normalizeRemotePathKey(it.remotePath) }
            .sortedWith(
                compareBy<SteamCloudUploadCandidate>({ normalizeRemotePathKey(it.remotePath) }, { it.remotePath })
            )
        val uploadKeys = uploadCandidates.mapTo(linkedSetOf()) { normalizeRemotePathKey(it.remotePath) }
        val removedDeleteOverlaps = plan.deleteRemotePaths
            .filter { normalizeRemotePathKey(it) in uploadKeys }
            .distinctBy(::normalizeRemotePathKey)
            .sortedWith(compareBy<String>({ normalizeRemotePathKey(it) }, { it }))
        val deleteRemotePaths = plan.deleteRemotePaths
            .distinctBy(::normalizeRemotePathKey)
            .filterNot { normalizeRemotePathKey(it) in uploadKeys }
            .sortedWith(compareBy<String>({ normalizeRemotePathKey(it) }, { it }))

        val warnings = buildList {
            if (removedDeleteOverlaps.isNotEmpty()) {
                add(
                    "Removed ${removedDeleteOverlaps.size} overlapping Steam Cloud delete request(s) because those paths are also being uploaded."
                )
            }
        }

        return PreparedMirrorPlan(
            uploadCandidates = uploadCandidates,
            deleteRemotePaths = deleteRemotePaths,
            warnings = warnings,
            removedDeleteOverlaps = removedDeleteOverlaps,
        )
    }

    private fun describePreparedMirrorPlan(plan: PreparedMirrorPlan?): List<String> {
        if (plan == null) {
            return emptyList()
        }
        return buildList {
            add("Mirror upload candidates before failure: ${plan.uploadCandidates.size}")
            add("Mirror delete candidates before failure: ${plan.deleteRemotePaths.size}")
            if (plan.removedDeleteOverlaps.isNotEmpty()) {
                add("Removed overlapping delete paths: ${plan.removedDeleteOverlaps.size}")
                plan.removedDeleteOverlaps
                    .take(FAILURE_PATH_SAMPLE_LIMIT)
                    .forEach { remotePath -> add("Overlap path: $remotePath") }
            }
            plan.uploadCandidates
                .take(FAILURE_PATH_SAMPLE_LIMIT)
                .forEach { candidate ->
                    add(
                        "Upload path: ${candidate.remotePath} | localSha1=${candidate.sha1.ifBlank { "<none>" }} | size=${candidate.fileSize}"
                    )
                }
            plan.deleteRemotePaths
                .take(FAILURE_PATH_SAMPLE_LIMIT)
                .forEach { remotePath -> add("Delete path: $remotePath") }
            plan.warnings.forEach { warning -> add("Warning: $warning") }
        }
    }

    private fun normalizeRemotePathKey(remotePath: String): String =
        remotePath.trim().replace('\\', '/').lowercase(Locale.ROOT)

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }
}
