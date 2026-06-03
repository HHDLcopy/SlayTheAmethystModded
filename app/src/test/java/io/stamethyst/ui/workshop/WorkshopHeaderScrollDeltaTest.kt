package io.stamethyst.ui.workshop

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopHeaderScrollDeltaTest {
    @Test
    fun usesSharedVisibleItemOffsetWhenFirstVisibleItemChanges() {
        val previous = sample(
            firstIndex = 0,
            firstOffset = 240,
            visibleItems = listOf(
                0 to -240,
                1 to 12,
                2 to 96,
            )
        )
        val current = sample(
            firstIndex = 1,
            firstOffset = 4,
            visibleItems = listOf(
                1 to -4,
                2 to 80,
            )
        )

        assertEquals(16, workshopHeaderScrollDeltaPx(previous, current))
    }

    @Test
    fun reportsNegativeDeltaWhenSharedVisibleItemMovesDown() {
        val previous = sample(
            firstIndex = 2,
            firstOffset = 10,
            visibleItems = listOf(
                2 to -10,
                3 to 88,
            )
        )
        val current = sample(
            firstIndex = 1,
            firstOffset = 50,
            visibleItems = listOf(
                1 to -50,
                2 to 50,
                3 to 148,
            )
        )

        assertEquals(-60, workshopHeaderScrollDeltaPx(previous, current))
    }

    @Test
    fun fallsBackToFirstVisibleScrollOffsetWithinSameItem() {
        val previous = sample(
            firstIndex = 1,
            firstOffset = 20,
            visibleItems = emptyList()
        )
        val current = sample(
            firstIndex = 1,
            firstOffset = 35,
            visibleItems = emptyList()
        )

        assertEquals(15, workshopHeaderScrollDeltaPx(previous, current))
    }

    private fun sample(
        firstIndex: Int,
        firstOffset: Int,
        visibleItems: List<Pair<Int, Int>>,
    ): WorkshopHeaderScrollSample {
        return WorkshopHeaderScrollSample(
            firstVisibleItemIndex = firstIndex,
            firstVisibleItemScrollOffset = firstOffset,
            visibleItemOffsets = visibleItems.map { (index, offset) ->
                WorkshopHeaderVisibleItemOffset(index = index, offset = offset)
            },
        )
    }
}
