package io.stamethyst.config

import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherConfigMtsPatchCacheDefaultsTest {
    @Test
    fun mtsPatchCache_isDisabledByDefault() {
        assertFalse(LauncherConfig.DEFAULT_MTS_PATCH_CACHE_ENABLED)
    }
}
