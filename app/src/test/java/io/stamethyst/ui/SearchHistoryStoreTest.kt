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
}
