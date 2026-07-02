package io.stamethyst.backend.workshop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopInterruptedDownloadPolicyTest {
    @Test
    fun finishedOrUserStoppedTasksDoNotRecoverOnStartup() {
        val statuses = listOf(
            WorkshopDownloadTaskStatus.Queued,
            WorkshopDownloadTaskStatus.Paused,
            WorkshopDownloadTaskStatus.Completed,
            WorkshopDownloadTaskStatus.Failed,
            WorkshopDownloadTaskStatus.Cancelled,
        )

        statuses.forEach { status ->
            assertFalse(
                "$status should not trigger interrupted-download recovery",
                task(status).shouldRecoverInterruptedDownload(
                    nowMillis = NOW,
                    isActiveDownload = false,
                    activeDownloadRecoveryGraceMs = GRACE_MS,
                )
            )
        }
    }

    @Test
    fun activeServiceTaskDoesNotRecover() {
        assertFalse(
            task(WorkshopDownloadTaskStatus.Downloading, updatedAtMillis = NOW - GRACE_MS - 1)
                .shouldRecoverInterruptedDownload(
                    nowMillis = NOW,
                    isActiveDownload = true,
                    activeDownloadRecoveryGraceMs = GRACE_MS,
                )
        )
    }

    @Test
    fun recentRunningTaskDoesNotRecoverWithinGraceWindow() {
        assertFalse(
            task(WorkshopDownloadTaskStatus.Downloading, updatedAtMillis = NOW - GRACE_MS + 1)
                .shouldRecoverInterruptedDownload(
                    nowMillis = NOW,
                    isActiveDownload = false,
                    activeDownloadRecoveryGraceMs = GRACE_MS,
                )
        )
    }

    @Test
    fun staleRunningTaskRecoversAfterGraceWindow() {
        assertTrue(
            task(WorkshopDownloadTaskStatus.Downloading, updatedAtMillis = NOW - GRACE_MS - 1)
                .shouldRecoverInterruptedDownload(
                    nowMillis = NOW,
                    isActiveDownload = false,
                    activeDownloadRecoveryGraceMs = GRACE_MS,
                )
        )
    }

    @Test
    fun stoppingTaskRecoversImmediatelyWhenServiceIsNotActive() {
        val statuses = listOf(
            WorkshopDownloadTaskStatus.Pausing,
            WorkshopDownloadTaskStatus.Cancelling,
        )

        statuses.forEach { status ->
            assertTrue(
                "$status should be converted out of an interrupted stopping state",
                task(status).shouldRecoverInterruptedDownload(
                    nowMillis = NOW,
                    isActiveDownload = false,
                    activeDownloadRecoveryGraceMs = GRACE_MS,
                )
            )
        }
    }

    private fun task(
        status: WorkshopDownloadTaskStatus,
        updatedAtMillis: Long = NOW - GRACE_MS - 1,
    ): WorkshopDownloadTaskRecord {
        val summary = WorkshopItemSummary(
            appId = 646570u,
            publishedFileId = 1u,
            title = "Synthetic task",
            previewUrl = "",
            description = "",
            authorName = "",
            fileSizeBytes = 1L,
            updatedAtMillis = updatedAtMillis,
        )
        return WorkshopDownloadTaskRecord(
            publishedFileId = summary.publishedFileId,
            title = summary.title,
            status = status,
            message = status.name,
            updatedAtMillis = updatedAtMillis,
            details = WorkshopItemDetails(summary = summary),
        )
    }

    private companion object {
        private const val NOW = 10_000L
        private const val GRACE_MS = 1_000L
    }
}
