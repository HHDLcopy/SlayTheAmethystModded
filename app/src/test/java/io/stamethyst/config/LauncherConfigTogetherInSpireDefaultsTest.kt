package io.stamethyst.config

import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigTogetherInSpireDefaultsTest {
    @Test
    fun togetherInSpireCompatibilityFeaturesRemainEnabledByDefault() {
        assertTrue(LauncherConfig.DEFAULT_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED)
        assertTrue(LauncherConfig.DEFAULT_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED)
    }
}
