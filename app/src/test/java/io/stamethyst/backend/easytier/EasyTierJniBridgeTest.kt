package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierJniBridgeTest {
    @Test
    fun failureSummary_forMissingLibraryReportsNotBundled() {
        val error = UnsatisfiedLinkError(
            "dlopen failed: library \"libeasytier_android_jni.so\" not found"
        )

        assertEquals(
            "EasyTier native runtime library is not bundled in this build yet.",
            EasyTierJniBridge.failureSummary(error)
        )
        assertEquals(
            EasyTierFailureCategory.RuntimeBridgeUnavailable,
            EasyTierJniBridge.failureCategory(error)
        )
    }

    @Test
    fun failureSummary_forUnresolvedNativeSymbolKeepsLinkerCause() {
        val error = UnsatisfiedLinkError(
            "dlopen failed: cannot locate symbol \"collect_network_infos\" " +
                "referenced by \"libeasytier_android_jni.so\""
        )

        val summary = EasyTierJniBridge.failureSummary(error)

        assertTrue(summary.contains("EasyTier native runtime failed to load."))
        assertTrue(summary.contains("cannot locate symbol"))
        assertTrue(summary.contains("collect_network_infos"))
        assertFalse(summary.contains("not bundled"))
        assertEquals(
            EasyTierFailureCategory.RuntimeBridgeUnavailable,
            EasyTierJniBridge.failureCategory(error)
        )
    }
}
