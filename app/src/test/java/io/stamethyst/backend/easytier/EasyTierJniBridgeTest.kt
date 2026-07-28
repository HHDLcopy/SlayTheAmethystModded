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
            "EasyTier native runtime is missing from the installed resource pack.",
            EasyTierJniBridge.failureSummary(error)
        )
        assertEquals(
            EasyTierFailureCategory.RuntimeBridgeUnavailable,
            EasyTierJniBridge.failureCategory(error)
        )
    }

    @Test
    fun failureSummary_forMissingResourcePackReportsRuntimeUnavailable() {
        val error = UnsatisfiedLinkError(
            "EasyTier native runtime is missing from the installed resource pack: /data/user/0/test/libeasytier_ffi.so"
        )

        assertEquals(
            "EasyTier native runtime is missing from the installed resource pack.",
            EasyTierJniBridge.failureSummary(error)
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

    @Test
    fun failureSummary_forMissingNonEasyTierDependencyKeepsLinkerCause() {
        val error = UnsatisfiedLinkError(
            "dlopen failed: library \"libc++_shared.so\" not found: needed by \"libeasytier_ffi.so\""
        )

        val summary = EasyTierJniBridge.failureSummary(error)

        assertTrue(summary.contains("EasyTier native runtime failed to load."))
        assertTrue(summary.contains("libc++_shared.so"))
        assertFalse(summary.contains("missing from the installed resource pack"))
    }
}
