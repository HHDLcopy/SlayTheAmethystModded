package io.stamethyst.backend.workshop

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDownloadTaskStoreInstrumentedTest {
    @Test
    fun launcherVisibleListExcludesFinishedHistoryButFullListKeepsIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = WorkshopDownloadTaskStore(context)
        val originalTasks = store.list()
        try {
            val completed = task(10uL, WorkshopDownloadTaskStatus.Completed)
            val cancelled = task(11uL, WorkshopDownloadTaskStatus.Cancelled)
            val failed = task(12uL, WorkshopDownloadTaskStatus.Failed)
            val downloading = task(13uL, WorkshopDownloadTaskStatus.Downloading)
            store.save(listOf(completed, cancelled, failed, downloading))

            val launcherVisibleTasks = store.listLauncherVisible()
            assertEquals(
                listOf(12uL, 13uL),
                launcherVisibleTasks.map { it.publishedFileId }
            )
            assertTrue(
                "Launcher-card path should not carry retained download logs",
                launcherVisibleTasks.all { it.downloadLog.isEmpty() }
            )

            val fullTasks = store.list()
            assertEquals(
                listOf(10uL, 11uL, 12uL, 13uL),
                fullTasks.map { it.publishedFileId }
            )
            assertEquals(
                LARGE_DOWNLOAD_LOG,
                fullTasks.first { it.publishedFileId == 10uL }.downloadLog
            )
        } finally {
            store.save(originalTasks)
        }
    }

    private fun task(
        publishedFileId: ULong,
        status: WorkshopDownloadTaskStatus,
    ): WorkshopDownloadTaskRecord {
        val summary = WorkshopItemSummary(
            appId = 646570u,
            publishedFileId = publishedFileId,
            title = "Task $publishedFileId",
            previewUrl = "",
            description = "Synthetic task for launcher-visible filtering",
            authorName = "Instrumented Test",
            fileSizeBytes = 1024L,
            updatedAtMillis = publishedFileId.toLong(),
        )
        return WorkshopDownloadTaskRecord(
            publishedFileId = publishedFileId,
            title = summary.title,
            status = status,
            message = status.name,
            updatedAtMillis = publishedFileId.toLong(),
            details = WorkshopItemDetails(summary = summary),
            downloadLog = LARGE_DOWNLOAD_LOG,
        )
    }

    private companion object {
        private val LARGE_DOWNLOAD_LOG = "completed log line\n".repeat(256)
    }
}
