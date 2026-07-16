package io.stamethyst.input

import org.junit.Assert.assertEquals
import org.junit.Test

class StsInputCoordMapperTest {
    @Test
    fun glfwCursorYKeepsTopLeftOrigin() {
        assertEquals(0f, glfwCursorYFromMappedViewY(0f, 1080), 0.0001f)
        assertEquals(240f, glfwCursorYFromMappedViewY(240f, 1080), 0.0001f)
        assertEquals(1079f, glfwCursorYFromMappedViewY(1079f, 1080), 0.0001f)
    }

    @Test
    fun glfwCursorYClampsToWindow() {
        assertEquals(0f, glfwCursorYFromMappedViewY(-30f, 1080), 0.0001f)
        assertEquals(1079f, glfwCursorYFromMappedViewY(1200f, 1080), 0.0001f)
    }
}
