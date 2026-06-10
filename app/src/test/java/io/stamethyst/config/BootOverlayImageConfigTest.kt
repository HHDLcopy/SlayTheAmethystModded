package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootOverlayImageConfigTest {
    @Test
    fun fromPersistedValue_returnsMatchingMode() {
        assertEquals(BootOverlayImageMode.SINGLE, BootOverlayImageMode.fromPersistedValue("single"))
        assertEquals(BootOverlayImageMode.DUAL, BootOverlayImageMode.fromPersistedValue("dual"))
        assertNull(BootOverlayImageMode.fromPersistedValue("unknown"))
    }

    @Test
    fun singleMode_resolvesEndImageToStartImage() {
        val config = BootOverlayImageConfig(
            mode = BootOverlayImageMode.SINGLE,
            startImagePath = "/tmp/start.png",
            endImagePath = "/tmp/end.png",
            startImageVersion = 11L,
            endImageVersion = 22L
        )

        assertEquals("/tmp/start.png", config.resolvedStartImagePath())
        assertEquals("/tmp/start.png", config.resolvedEndImagePath())
        assertEquals(11L, config.resolvedStartImageVersion())
        assertEquals(11L, config.resolvedEndImageVersion())
    }

    @Test
    fun dualMode_resolvesEachImageIndependently() {
        val config = BootOverlayImageConfig(
            mode = BootOverlayImageMode.DUAL,
            startImagePath = "/tmp/start.png",
            endImagePath = "/tmp/end.png",
            startImageVersion = 11L,
            endImageVersion = 22L
        )

        assertEquals("/tmp/start.png", config.resolvedStartImagePath())
        assertEquals("/tmp/end.png", config.resolvedEndImagePath())
        assertEquals(11L, config.resolvedStartImageVersion())
        assertEquals(22L, config.resolvedEndImageVersion())
    }

    @Test
    fun hasCustomImages_acceptsEitherSlot() {
        assertFalse(BootOverlayImageConfig().hasCustomImages)
        assertTrue(
            BootOverlayImageConfig(
                mode = BootOverlayImageMode.DUAL,
                startImagePath = "/tmp/start.png"
            ).hasCustomImages
        )
        assertTrue(
            BootOverlayImageConfig(
                mode = BootOverlayImageMode.DUAL,
                endImagePath = "/tmp/end.png"
            ).hasCustomImages
        )
    }
}
