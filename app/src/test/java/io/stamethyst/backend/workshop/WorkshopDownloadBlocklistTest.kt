package io.stamethyst.backend.workshop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDownloadBlocklistTest {
    @Test
    fun blocksLauncherManagedWorkshopItems() {
        assertTrue(WorkshopDownloadBlocklist.isBlocked(1605060445uL))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(3658571962uL))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(1605833019uL))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(1609158507uL))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(3002563327uL))
        assertFalse(WorkshopDownloadBlocklist.isBlocked(123456789uL))
    }

    @Test
    fun blocksLauncherManagedWorkshopItemTitles() {
        assertTrue(WorkshopDownloadBlocklist.isBlocked(summary(title = "RAM Saver")))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(summary(title = "Amethyst Runtime Compat")))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(summary(title = "Amethyst Compat")))
        assertFalse(WorkshopDownloadBlocklist.isBlocked(summary(title = "Regular Content Mod")))
    }

    private fun summary(
        title: String,
        publishedFileId: ULong = 123456789uL,
    ): WorkshopItemSummary {
        return WorkshopItemSummary(
            publishedFileId = publishedFileId,
            appId = 646570u,
            title = title,
            previewUrl = "",
            description = "",
        )
    }
}
