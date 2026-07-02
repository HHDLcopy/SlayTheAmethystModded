package io.stamethyst.backend.launch

import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StsLaunchSpecRamSaverMemoryPolicyTest {
    @Test
    fun resolveTexturePressureDownscaleEnabled_disablesWhenRamSaverEnabled() {
        assertFalse(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = true,
                configuredEnabled = true
            )
        )
    }

    @Test
    fun resolveTexturePressureDownscaleEnabled_preservesConfiguredValueWithoutRamSaver() {
        assertTrue(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = false
            )
        )
    }

    @Test
    fun resolveGpuResourceGuardianModeForLaunch_disablesWhenRamSaverEnabled() {
        assertEquals(
            GpuResourceGuardianMode.OFF,
            StsLaunchSpec.resolveGpuResourceGuardianModeForLaunch(
                ramSaverEnabled = true,
                configuredMode = GpuResourceGuardianMode.ULTRA_AGGRESSIVE
            )
        )
    }

    @Test
    fun resolveGpuResourceGuardianModeForLaunch_preservesConfiguredModeWithoutRamSaver() {
        assertEquals(
            GpuResourceGuardianMode.AGGRESSIVE,
            StsLaunchSpec.resolveGpuResourceGuardianModeForLaunch(
                ramSaverEnabled = false,
                configuredMode = GpuResourceGuardianMode.AGGRESSIVE
            )
        )
    }

    @Test
    fun resolveFboPressureDownscaleEnabled_disablesWhenRamSaverEnabled() {
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = true,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = true
            )
        )
    }

    @Test
    fun resolveFboPressureDownscaleEnabled_requiresConfigAndMaterialPolicyWithoutRamSaver() {
        assertTrue(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = false,
                offscreenFrameBuffersEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = false
            )
        )
    }

    @Test
    fun appendDebugJvmPropertiesForLaunch_doesNotOverrideManagedCompatibilityProperty() {
        val args = mutableListOf("-Damethyst.lwjgl.default_fbo_fast_rebind=false")

        val result = StsLaunchSpec.appendDebugJvmPropertiesForLaunch(
            args,
            mapOf("amethyst.lwjgl.default_fbo_fast_rebind" to "true")
        )

        assertEquals(listOf("amethyst.lwjgl.default_fbo_fast_rebind"), result.skippedManagedKeys)
        assertTrue(result.appendedKeys.isEmpty())
        assertEquals(listOf("-Damethyst.lwjgl.default_fbo_fast_rebind=false"), args)
    }

    @Test
    fun appendDebugJvmPropertiesForLaunch_keepsUnmanagedDebugProperty() {
        val args = mutableListOf("-Damethyst.lwjgl.default_fbo_fast_rebind=false")

        val result = StsLaunchSpec.appendDebugJvmPropertiesForLaunch(
            args,
            mapOf("amethyst.gdx.debug_leak_interval_frames" to "120")
        )

        assertEquals(listOf("amethyst.gdx.debug_leak_interval_frames"), result.appendedKeys)
        assertTrue(result.skippedManagedKeys.isEmpty())
        assertTrue(args.contains("-Damethyst.gdx.debug_leak_interval_frames=120"))
    }

    @Test
    fun shouldEnableAgentConnector_disablesForNormalMtsLaunch() {
        assertFalse(
            StsLaunchSpec.shouldEnableAgentConnector(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = false,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = false
            )
        )
    }

    @Test
    fun shouldEnableAgentConnector_keepsDebugAndAutomationMtsLaunchesEnabled() {
        assertTrue(
            StsLaunchSpec.shouldEnableAgentConnector(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = true,
                forceJvmCrash = false,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = false
            )
        )
        assertTrue(
            StsLaunchSpec.shouldEnableAgentConnector(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = true,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = false
            )
        )
        assertTrue(
            StsLaunchSpec.shouldEnableAgentConnector(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = false,
                forceRuntimeCrash = true,
                performanceDeepDiagnostics = false
            )
        )
        assertTrue(
            StsLaunchSpec.shouldEnableAgentConnector(
                launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
                autoplay = false,
                forceJvmCrash = false,
                forceRuntimeCrash = false,
                performanceDeepDiagnostics = true
            )
        )
    }

    @Test
    fun shouldEnableAgentConnector_disablesForVanillaEvenWhenDebugFlagsAreSet() {
        assertFalse(
            StsLaunchSpec.shouldEnableAgentConnector(
                launchMode = StsLaunchSpec.LAUNCH_MODE_VANILLA,
                autoplay = true,
                forceJvmCrash = true,
                forceRuntimeCrash = true,
                performanceDeepDiagnostics = true
            )
        )
    }

    @Test
    fun resolveGamePerformanceDeepDiagnosticsEnabled_requiresOverlayAndGpuDiagnostics() {
        assertFalse(
            LauncherConfig.resolveGamePerformanceDeepDiagnosticsEnabled(
                showPerformanceOverlay = false,
                gpuResourceDiagEnabled = false
            )
        )
        assertFalse(
            LauncherConfig.resolveGamePerformanceDeepDiagnosticsEnabled(
                showPerformanceOverlay = true,
                gpuResourceDiagEnabled = false
            )
        )
        assertFalse(
            LauncherConfig.resolveGamePerformanceDeepDiagnosticsEnabled(
                showPerformanceOverlay = false,
                gpuResourceDiagEnabled = true
            )
        )
        assertTrue(
            LauncherConfig.resolveGamePerformanceDeepDiagnosticsEnabled(
                showPerformanceOverlay = true,
                gpuResourceDiagEnabled = true
            )
        )
    }
}
