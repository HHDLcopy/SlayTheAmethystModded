package io.stamethyst.backend.workshop

import java.util.Locale

internal object WorkshopDownloadBlocklist {
    private val blockedPublishedFileIds = setOf(
        1605060445uL,
        3658571962uL,
        1605833019uL,
        1609158507uL,
        3002563327uL,
    )
    private val blockedTitleTokens = setOf(
        "modthespire",
        "basemod",
        "stslib",
        "amethystruntimecompat",
        "amethystcompat",
        "amethystruntimecompatibility",
        "ramsaver",
    )

    fun isBlocked(publishedFileId: ULong): Boolean = publishedFileId in blockedPublishedFileIds

    fun isBlocked(summary: WorkshopItemSummary): Boolean {
        return isBlocked(summary.publishedFileId) || summary.title.normalizedTitleToken() in blockedTitleTokens
    }

    private fun String.normalizedTitleToken(): String {
        return trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
    }
}
