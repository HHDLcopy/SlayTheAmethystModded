package io.stamethyst.backend.steamcloud

internal data class SteamCloudMirrorPlan(
    val uploadCandidates: List<SteamCloudUploadCandidate>,
    val deleteRemotePaths: List<String>,
)

internal object SteamCloudMirrorPlanner {
    fun buildLocalMirrorPlan(
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline? = null,
    ): SteamCloudMirrorPlan {
        if (baseline != null) {
            return buildBaselineAwareLocalMirrorPlan(
                currentLocalEntries = currentLocalEntries,
                currentRemoteSnapshot = currentRemoteSnapshot,
                baseline = baseline,
            )
        }

        return buildFullLocalMirrorPlan(
            currentLocalEntries = currentLocalEntries,
            currentRemoteSnapshot = currentRemoteSnapshot,
        )
    }

    private fun buildFullLocalMirrorPlan(
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
    ): SteamCloudMirrorPlan {
        val currentRemoteByPath = currentRemoteSnapshot.entries.associateBy { it.localRelativePath }
        val uploadCandidates = currentLocalEntries
            .sortedWith(compareBy<SteamCloudLocalFileSnapshotEntry>({ it.localRelativePath.lowercase() }, { it.localRelativePath }))
            .mapNotNull { localEntry ->
                val currentRemote = currentRemoteByPath[localEntry.localRelativePath]
                if (shouldSkipUploadBecauseRemoteMatches(localEntry, currentRemote)) {
                    return@mapNotNull null
                }
                val remotePath = currentRemote?.remotePath
                    ?: SteamCloudPathMapper.buildRemotePath(localEntry.localRelativePath)
                    ?: return@mapNotNull null
                SteamCloudUploadCandidate(
                    remotePath = remotePath,
                    localRelativePath = localEntry.localRelativePath,
                    rootKind = localEntry.rootKind,
                    fileSize = localEntry.fileSize,
                    lastModifiedMs = localEntry.lastModifiedMs,
                    sha256 = localEntry.sha256,
                    sha1 = localEntry.sha1,
                    kind = if (currentRemote != null) {
                        SteamCloudUploadCandidateKind.MODIFIED_FILE
                    } else {
                        SteamCloudUploadCandidateKind.NEW_FILE
                    },
                )
            }

        val localPaths = currentLocalEntries.mapTo(linkedSetOf()) { it.localRelativePath }
        val deleteRemotePaths = currentRemoteSnapshot.entries
            .asSequence()
            .filter { it.localRelativePath !in localPaths }
            .map { it.remotePath }
            .sortedWith(compareBy<String>({ it.lowercase() }, { it }))
            .toList()

        return SteamCloudMirrorPlan(
            uploadCandidates = uploadCandidates,
            deleteRemotePaths = deleteRemotePaths,
        )
    }

    private fun buildBaselineAwareLocalMirrorPlan(
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        baseline: SteamCloudSyncBaseline,
    ): SteamCloudMirrorPlan {
        val currentLocalByPath = currentLocalEntries.associateBy { it.localRelativePath }
        val currentRemoteByPath = currentRemoteSnapshot.entries.associateBy { it.localRelativePath }
        val baselineLocalByPath = baseline.localEntries.associateBy { it.localRelativePath }
        val baselineRemoteByPath = baseline.remoteEntries.associateBy { it.localRelativePath }
        val allPaths = linkedSetOf<String>().apply {
            addAll(baselineLocalByPath.keys)
            addAll(baselineRemoteByPath.keys)
            addAll(currentLocalByPath.keys)
            addAll(currentRemoteByPath.keys)
        }

        val uploadCandidates = mutableListOf<SteamCloudUploadCandidate>()
        val deleteRemotePaths = mutableListOf<String>()

        for (localRelativePath in allPaths.sortedWith(compareBy<String>({ it.lowercase() }, { it }))) {
            val currentLocal = currentLocalByPath[localRelativePath]
            val currentRemote = currentRemoteByPath[localRelativePath]
            val baselineLocal = baselineLocalByPath[localRelativePath]
            val baselineRemote = baselineRemoteByPath[localRelativePath]
            val rootKind = currentLocal?.rootKind
                ?: currentRemote?.rootKind
                ?: baselineLocal?.rootKind
                ?: baselineRemote?.rootKind
                ?: SteamCloudPathMapper.mapLocalRelativePath(localRelativePath)?.rootKind
                ?: continue

            if (currentLocal == null) {
                currentRemote?.remotePath?.let(deleteRemotePaths::add)
                continue
            }

            val remotePath = currentRemote?.remotePath
                ?: baselineRemote?.remotePath
                ?: SteamCloudPathMapper.buildRemotePath(localRelativePath)
                ?: continue

            val shouldUpload = when {
                currentRemote == null -> true
                else -> {
                    val localChanged = hasLocalChanged(baselineLocal, currentLocal)
                    val remoteChanged = hasRemoteChanged(baselineRemote, currentRemote)
                    localChanged || remoteChanged
                }
            }
            if (!shouldUpload) {
                continue
            }
            if (shouldSkipUploadBecauseRemoteMatches(currentLocal, currentRemote)) {
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
                kind = if (currentRemote == null && baselineRemote == null) {
                    SteamCloudUploadCandidateKind.NEW_FILE
                } else {
                    SteamCloudUploadCandidateKind.MODIFIED_FILE
                },
            )
        }

        return SteamCloudMirrorPlan(
            uploadCandidates = uploadCandidates,
            deleteRemotePaths = deleteRemotePaths
                .sortedWith(compareBy<String>({ it.lowercase() }, { it })),
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
        // Normalize path separators — Steam may return '\' or '/' depending on client/platform.
        val baselinePath = baseline.remotePath.replace('\\', '/')
        val currentPath = current.remotePath.replace('\\', '/')
        if (baselinePath != currentPath) {
            return true
        }
        if (baseline.persistState != current.persistState) {
            return true
        }
        // SHA-1 is the authoritative content identity signal.
        val baselineSha1 = baseline.sha1.trim()
        val currentSha1 = current.sha1.trim()
        if (baselineSha1.isNotBlank() && currentSha1.isNotBlank()) {
            return !baselineSha1.equals(currentSha1, ignoreCase = true)
        }
        // Do NOT compare timestamp — Steam's CM timestamp reflects server processing time, not
        // content change time, and drifts slightly after every push even for identical content.
        return baseline.rawSize != current.rawSize
    }

    private fun shouldSkipUploadBecauseRemoteMatches(
        local: SteamCloudLocalFileSnapshotEntry,
        remote: SteamCloudManifestEntry?,
    ): Boolean {
        if (remote == null) {
            return false
        }
        // Use SHA-1 when both sides have it; fall back to size when either is missing.
        val localSha1 = local.sha1.trim()
        val remoteSha1 = remote.sha1.trim()
        if (localSha1.isNotBlank() && remoteSha1.isNotBlank()) {
            return localSha1.equals(remoteSha1, ignoreCase = true)
        }
        return local.fileSize == remote.rawSize
    }
}
