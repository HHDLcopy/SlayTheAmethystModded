package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMouseOverlayPositionPersistenceTest {
    @Test
    fun captureStoredPosition_usesNormalizedFractions() {
        val stored = captureFloatingMouseStoredPosition(
            leftMargin = 225,
            topMargin = 90,
            maxLeft = 300,
            maxTop = 180
        )

        assertEquals(0.75f, stored.leftFraction, 0.0001f)
        assertEquals(0.5f, stored.topFraction, 0.0001f)
    }

    @Test
    fun restoreResolvedPosition_clampsStoredFractionsIntoBounds() {
        val restored = restoreFloatingMouseResolvedPosition(
            leftFraction = 1.2f,
            topFraction = -0.25f,
            maxLeft = 400,
            maxTop = 200,
            defaultLeft = 40,
            defaultTop = 80
        )

        assertEquals(400, restored.left)
        assertEquals(0, restored.top)
    }

    @Test
    fun restoreResolvedPosition_fallsBackToClampedDefaultsWhenStoredFractionsMissing() {
        val restored = restoreFloatingMouseResolvedPosition(
            leftFraction = Float.NaN,
            topFraction = null,
            maxLeft = 300,
            maxTop = 120,
            defaultLeft = 360,
            defaultTop = 60
        )

        assertEquals(300, restored.left)
        assertEquals(60, restored.top)
    }
}
