package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudPushCoordinatorTest {
    @Test
    fun uploadBatchChangesAreVisible_acceptsMatchingUploadsAndDeletes() {
        assertTrue(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(remoteFile(UPLOAD_PATH, 3L, SHA1)),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, SHA1.lowercase())),
                deleteRemotePaths = listOf(DELETE_PATH),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_rejectsStaleUpload() {
        assertFalse(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(remoteFile(UPLOAD_PATH, 3L, "0000000000000000000000000000000000000000")),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, SHA1)),
                deleteRemotePaths = emptyList(),
            )
        )
    }

    @Test
    fun uploadBatchChangesAreVisible_rejectsPendingDelete() {
        assertFalse(
            SteamCloudPushCoordinator.uploadBatchChangesAreVisible(
                remoteEntries = listOf(
                    remoteFile(UPLOAD_PATH, 3L, SHA1),
                    remoteFile(DELETE_PATH, 4L, SHA1),
                ),
                uploadCandidates = listOf(uploadCandidate(UPLOAD_PATH, 3L, SHA1)),
                deleteRemotePaths = listOf(DELETE_PATH),
            )
        )
    }

    private fun uploadCandidate(remotePath: String, size: Long, sha1: String) = SteamCloudUploadCandidate(
        remotePath = remotePath,
        localRelativePath = "preferences/STSPlayer",
        rootKind = SteamCloudRootKind.PREFERENCES,
        fileSize = size,
        lastModifiedMs = 0L,
        sha256 = "sha256",
        sha1 = sha1,
        kind = SteamCloudUploadCandidateKind.MODIFIED_FILE,
    )

    private fun remoteFile(remotePath: String, size: Long, sha1: String) = SteamCloudClient.RemoteFileRecord(
        remotePath,
        size,
        0L,
        "device",
        "Persisted",
        sha1,
    )

    private companion object {
        const val UPLOAD_PATH = "%GameInstall%preferences/STSPlayer"
        const val DELETE_PATH = "%GameInstall%saves/IRONCLAD.autosave"
        const val SHA1 = "A9993E364706816ABA3E25717850C26C9CD0D89D"
    }
}
