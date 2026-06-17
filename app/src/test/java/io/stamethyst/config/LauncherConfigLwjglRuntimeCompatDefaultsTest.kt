package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigLwjglRuntimeCompatDefaultsTest {
    @Test
    fun androidLwjglFramePacing_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_ANDROID_LWJGL_FRAME_PACING_COMPAT_ENABLED)
    }

    @Test
    fun hotLoopNoopTrim_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_LWJGL_HOT_LOOP_NOOP_TRIM_COMPAT_ENABLED)
    }

    @Test
    fun defaultFramebufferFastRebind_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_FRAMEBUFFER_FAST_REBIND_COMPAT_ENABLED)
    }
}
