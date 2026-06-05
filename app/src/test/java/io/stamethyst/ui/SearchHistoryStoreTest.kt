package io.stamethyst.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryStoreTest {
    @Test
    fun mergeSearchHistory_movesExistingQueryToFrontIgnoringCase() {
        val result = mergeSearchHistory(
            existing = listOf("Downfall", "Replay", "QoL"),
            query = "downfall",
        )

        assertEquals(listOf("downfall", "Replay", "QoL"), result)
    }

    @Test
    fun mergeSearchHistory_ignoresBlankQueryAndKeepsLimit() {
        val result = mergeSearchHistory(
            existing = listOf("a", "b", "c"),
            query = "   ",
            limit = 2,
        )

        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun removeSearchHistoryEntry_removesQueryIgnoringCase() {
        val result = removeSearchHistoryEntry(
            existing = listOf("Downfall", "Replay", "QoL"),
            query = "downfall",
        )

        assertEquals(listOf("Replay", "QoL"), result)
    }

    @Test
    fun removeSearchHistoryEntry_trimsDedupesAndKeepsLimit() {
        val result = removeSearchHistoryEntry(
            existing = listOf("  Replay  ", "QoL", "replay", "Downfall"),
            query = "missing",
            limit = 2,
        )

        assertEquals(listOf("Replay", "QoL"), result)
    }
}
