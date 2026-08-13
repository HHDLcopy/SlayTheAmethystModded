package io.stamethyst.backend.steamcloud

internal object SteamCloudDiffPlanner {
    fun buildUploadPlan(
        plannedAtMs: Long,
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline?,
    ): SteamCloudUploadPlan {
        val currentLocalByPath = currentLocalEntries.associateBy { it.localRelativePath }
        val currentRemoteByPath = currentRemoteSnapshot.entries.associateBy { it.localRelativePath }
        val baselineLocalByPath = baseline?.localEntries?.associateBy { it.localRelativePath }.orEmpty()
        val baselineRemoteByPath = baseline?.remoteEntries?.associateBy { it.localRelativePath }.orEmpty()
        val allPaths = linkedSetOf<String>().apply {
            addAll(baselineLocalByPath.keys)
            addAll(baselineRemoteByPath.keys)
            addAll(currentLocalByPath.keys)
            addAll(currentRemoteByPath.keys)
        }

        val uploadCandidates = mutableListOf<SteamCloudUploadCandidate>()
        val remoteDeleteCandidates = mutableListOf<SteamCloudRemoteDeleteCandidate>()
        val conflicts = mutableListOf<SteamCloudConflict>()
        val remoteOnlyChanges = mutableListOf<SteamCloudRemoteOnlyChange>()
        val warnings = mutableListOf<String>()
        var ignoredLocalDeletionCount = 0

        for (localRelativePath in allPaths.sortedWith(compareBy<String>({ it.lowercase() }, { it }))) {
            val currentLocal = currentLocalByPath[localRelativePath]
            val currentRemote = currentRemoteByPath[localRelativePath]
            val baselineLocal = baselineLocalByPath[localRelativePath]
            val baselineRemote = baselineRemoteByPath[localRelativePath]
            val rootKind = resolveRootKind(localRelativePath, currentLocal, currentRemote, baselineLocal, baselineRemote)
                ?: run {
                    warnings += SteamCloudUserWarning.UnsupportedLocalPath(localRelativePath).rawMessage()
                    continue
                }

            if (baseline == null && currentLocal != null && currentRemote != null) {
                // No sync baseline exists yet.  Only raise a BASELINE_REQUIRED conflict when the
                // two sides actually disagree — if SHA-1 (or size as fallback) shows the content
                // is identical there is nothing to resolve; treat the file as already in sync and
                // let the planner continue so the overall plan can be baseline-free and auto-sync
                // can run and write the baseline on completion.
                if (!currentLocalMatchesRemote(currentLocal, currentRemote)) {
                    conflicts += SteamCloudConflict(
                        localRelativePath = localRelativePath,
                        rootKind = rootKind,
                        kind = SteamCloudConflictKind.BASELINE_REQUIRED,
                        currentLocal = currentLocal,
                        currentRemote = currentRemote,
                        baselineLocal = null,
                        baselineRemote = null,
                    )
                }
                continue
            }

            val localChanged = hasLocalChanged(baselineLocal, currentLocal)
            val remoteChanged = hasRemoteChanged(baselineRemote, currentRemote)

            when {
                !localChanged && !remoteChanged -> Unit
                localChanged && remoteChanged -> {
                    if (!currentLocalMatchesRemote(currentLocal, currentRemote)) {
                        conflicts += SteamCloudConflict(
                            localRelativePath = localRelativePath,
                            rootKind = rootKind,
                            kind = SteamCloudConflictKind.BOTH_CHANGED,
                            currentLocal = currentLocal,
                            currentRemote = currentRemote,
                            baselineLocal = baselineLocal,
                            baselineRemote = baselineRemote,
                        )
                    }
                }

                localChanged -> {
                    if (currentLocal == null) {
                        if (rootKind == SteamCloudRootKind.SAVES && currentRemote != null) {
                            remoteDeleteCandidates += SteamCloudRemoteDeleteCandidate(
                                remotePath = currentRemote.remotePath,
                                localRelativePath = localRelativePath,
                                rootKind = rootKind,
                            )
                        } else {
                            ignoredLocalDeletionCount++
                        }
                        continue
                    }
                    val remotePath = currentRemote?.remotePath
                        ?: baselineRemote?.remotePath
                        ?: SteamCloudPathMapper.buildRemotePath(localRelativePath)
                    if (remotePath == null) {
                        warnings += SteamCloudUserWarning.FailedToMapLocalFile(localRelativePath).rawMessage()
                        continue
                    }
                    uploadCandidates += SteamCloudUploadCandidate(
                        remotePath = remotePath,
                        localRelativePath = localRelativePath,
                        rootKind = rootKind,
                        fileSize = currentLocal.fileSize,
                        lastModifiedMs = currentLocal.lastModifiedMs,
                        sha256 = currentLocal.sha256,
                        sha1 = currentLocal.sha1,
                        kind = if (baselineRemote == null && currentRemote == null) {
                            SteamCloudUploadCandidateKind.NEW_FILE
                        } else {
                            SteamCloudUploadCandidateKind.MODIFIED_FILE
                        },
                    )
                }

                remoteChanged -> {
                    remoteOnlyChanges += SteamCloudRemoteOnlyChange(
                        localRelativePath = localRelativePath,
                        rootKind = rootKind,
                        kind = when {
                            baselineRemote == null && currentRemote != null ->
                                SteamCloudRemoteOnlyChangeKind.NEW_REMOTE_FILE
                            baselineRemote != null && currentRemote == null ->
                                SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED
                            else ->
                                SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE
                        },
                        currentRemote = currentRemote,
                        baselineRemote = baselineRemote,
                    )
                }
            }
        }

        if (baseline == null) {
            warnings += SteamCloudUserWarning.BaselineRequired.rawMessage()
        }
        if (ignoredLocalDeletionCount > 0) {
            warnings += SteamCloudUserWarning.IgnoredLocalDeletions(ignoredLocalDeletionCount).rawMessage()
        }
        currentRemoteSnapshot.warnings.forEach { warnings += it }

        return SteamCloudUploadPlan(
            plannedAtMs = plannedAtMs,
            remoteManifestFetchedAtMs = currentRemoteSnapshot.fetchedAtMs,
            baselineConfigured = baseline != null,
            uploadCandidates = uploadCandidates,
            conflicts = conflicts,
            remoteOnlyChanges = remoteOnlyChanges,
            remoteDeleteCandidates = remoteDeleteCandidates,
            warnings = warnings.distinct(),
        )
    }

    private fun hasLocalChanged(
        baseline: SteamCloudLocalFileSnapshotEntry?,
        current: SteamCloudLocalFileSnapshotEntry?,
    ): Boolean {
        if (baseline == null && current == null) {
            return false
        }
        if (baseline == null || current == null) {
            return true
        }
        return baseline.fileSize != current.fileSize || baseline.sha256 != current.sha256
    }

    private fun hasRemoteChanged(
        baseline: SteamCloudManifestEntry?,
        current: SteamCloudManifestEntry?,
    ): Boolean {
        if (baseline == null && current == null) {
            return false
        }
        if (baseline == null || current == null) {
            return true
        }
        // Normalize path separators before comparing: Steam can return either '/' or '\' depending
        // on client/platform, and the baseline may have been written with a different separator.
        val baselinePath = baseline.remotePath.replace('\\', '/')
        val currentPath = current.remotePath.replace('\\', '/')
        if (baselinePath != currentPath) {
            return true
        }
        // persistState change (e.g. deleted marker) always counts as a change.
        if (baseline.persistState != current.persistState) {
            return true
        }
        // When both sides have a SHA-1, that is the authoritative content identity check.
        // Size is a secondary guard for when SHA-1 is missing.
        val baselineSha1 = baseline.sha1.trim()
        val currentSha1 = current.sha1.trim()
        if (baselineSha1.isNotBlank() && currentSha1.isNotBlank()) {
            return !baselineSha1.equals(currentSha1, ignoreCase = true)
        }
        // Fall back to size-only comparison.  NOTE: we intentionally do NOT compare
        // `timestamp` here.  Steam's manifest timestamp reflects when the CM *processed*
        // the upload, not when the file content changed.  After every push the server
        // often returns a slightly different timestamp on the next manifest fetch even
        // though the content is identical — comparing timestamps therefore produces false
        // "remote changed" signals that, combined with any local change, escalate to a
        // spurious BOTH_CHANGED conflict on every subsequent game session.
        return baseline.rawSize != current.rawSize
    }

    private fun currentLocalMatchesRemote(
        local: SteamCloudLocalFileSnapshotEntry?,
        remote: SteamCloudManifestEntry?,
    ): Boolean {
        if (local == null || remote == null) {
            return false
        }
        // SHA-1 is the preferred equality signal when both sides have it.
        val localSha1 = local.sha1.trim()
        val remoteSha1 = remote.sha1.trim()
        if (localSha1.isNotBlank() && remoteSha1.isNotBlank()) {
            return localSha1.equals(remoteSha1, ignoreCase = true)
        }
        // If either side is missing a SHA-1 (e.g. older manifest entries or local entries
        // collected before SHA-1 support was added), fall back to size comparison so that
        // identical files are not forced into a conflict purely due to missing hash data.
        return local.fileSize == remote.rawSize
    }

    private fun resolveRootKind(
        localRelativePath: String,
        currentLocal: SteamCloudLocalFileSnapshotEntry?,
        currentRemote: SteamCloudManifestEntry?,
        baselineLocal: SteamCloudLocalFileSnapshotEntry?,
        baselineRemote: SteamCloudManifestEntry?,
    ): SteamCloudRootKind? {
        return currentLocal?.rootKind
            ?: currentRemote?.rootKind
            ?: baselineLocal?.rootKind
            ?: baselineRemote?.rootKind
            ?: SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)?.rootKind
    }
}
