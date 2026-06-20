package io.stamethyst

import android.view.WindowManager
import io.stamethyst.backend.render.VirtualResolutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSurfaceManagerPolicyTest {
    @Test
    fun resolveForegroundResyncDelayMs_debouncesSurfaceViewLayoutAndForegroundReasons() {
        assertEquals(
            48L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "layout"
            )
        )
        assertEquals(
            48L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "resume"
            )
        )
        assertEquals(
            16L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "surface_available"
            )
        )
        assertEquals(
            48L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "window_configuration"
            )
        )
    }

    @Test
    fun resolveForegroundResyncDelayMs_keepsTextureViewImmediate() {
        assertEquals(
            0L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = true,
                reason = "layout"
            )
        )
    }

    @Test
    fun shouldSkipSurfaceViewSteadyStateResync_onlySkipsStableForegroundReasons() {
        assertTrue(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "resume"
            )
        )
        assertTrue(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "focus"
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = true,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "resume"
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "layout"
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "window_configuration"
            )
        )
    }

    @Test
    fun surfaceViewFixedSizePolicy_doesNotSuppressWhenSurfaceFrameIsStale() {
        assertFalse(
            SurfaceViewHost.shouldSuppressFixedSize(
                requestedWidth = 2400,
                requestedHeight = 1080,
                frameWidth = 1200,
                frameHeight = 540
            )
        )
        assertTrue(
            SurfaceViewHost.shouldSuppressFixedSize(
                requestedWidth = 2400,
                requestedHeight = 1080,
                frameWidth = 2400,
                frameHeight = 1080
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_placesCropOppositeDisplayCutoutSide() {
        assertEquals(
            RenderViewportInsets(right = 96),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(),
                cameraInsets = RenderViewportInsets(left = 96),
                fallbackInset = 24
            )
        )
        assertEquals(
            RenderViewportInsets(left = 96),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(),
                cameraInsets = RenderViewportInsets(right = 96),
                fallbackInset = 24
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_usesGestureSideWhenAvailable() {
        assertEquals(
            RenderViewportInsets(left = 80),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(left = 80),
                cameraInsets = RenderViewportInsets(top = 40),
                fallbackInset = 24
            )
        )
        assertEquals(
            RenderViewportInsets(right = 24),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(),
                cameraInsets = RenderViewportInsets(),
                fallbackInset = 24
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_keepsRightCutoutCropOnLeftEvenWithRightGestureInset() {
        assertEquals(
            RenderViewportInsets(left = 80),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(right = 48),
                cameraInsets = RenderViewportInsets(right = 80),
                fallbackInset = 24
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_usesWindowGapBeforeInsets() {
        assertEquals(
            RenderViewportInsets(left = 96),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(right = 48),
                cameraInsets = RenderViewportInsets(),
                fallbackInset = 24,
                windowCropHint = RenderViewportCropHint(
                    side = HorizontalCropSide.LEFT,
                    inset = 96
                )
            )
        )
    }

    @Test
    fun resolveWindowConstrainedCropHint_cropsOppositeExistingSystemGap() {
        assertEquals(
            RenderViewportCropHint(side = HorizontalCropSide.LEFT, inset = 96),
            RenderSurfaceManager.resolveWindowConstrainedCropHint(
                rootLeft = 0,
                rootWidth = 2304,
                displayWidth = 2400
            )
        )
        assertEquals(
            RenderViewportCropHint(side = HorizontalCropSide.RIGHT, inset = 96),
            RenderSurfaceManager.resolveWindowConstrainedCropHint(
                rootLeft = 96,
                rootWidth = 2304,
                displayWidth = 2400
            )
        )
        assertEquals(
            null,
            RenderSurfaceManager.resolveWindowConstrainedCropHint(
                rootLeft = 0,
                rootWidth = 2400,
                displayWidth = 2400
            )
        )
    }

    @Test
    fun resolveViewportLayout_keepsLeftAndRightCropsSeparate() {
        assertEquals(
            RenderViewportLayout(
                width = 2180,
                height = 1080,
                leftMargin = 100,
                topMargin = 0,
                rightMargin = 120,
                bottomMargin = 0
            ),
            RenderSurfaceManager.resolveViewportLayout(
                rootWidth = 2400,
                rootHeight = 1080,
                cropInsets = RenderViewportInsets(left = 100, right = 120),
                virtualResolutionMode = VirtualResolutionMode.FULLSCREEN_FILL
            )
        )
    }

    @Test
    fun resolveDisplayCutoutMode_keepsBootOverlayFullScreen() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = true,
                bootOverlayActive = true
            )
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = false,
                bootOverlayActive = true
            )
        )
    }

    @Test
    fun resolveDisplayCutoutMode_restoresGameCutoutAvoidanceAfterBootOverlay() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = true,
                bootOverlayActive = false
            )
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = false,
                bootOverlayActive = false
            )
        )
    }

    @Test
    fun shouldApplyManualDisplayCutoutAvoidance_onlyWhileWindowIsUnconstrained() {
        assertTrue(
            RenderSurfaceManager.shouldApplyManualDisplayCutoutAvoidance(
                avoidDisplayCutout = true,
                windowConstrained = false
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldApplyManualDisplayCutoutAvoidance(
                avoidDisplayCutout = true,
                windowConstrained = true
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldApplyManualDisplayCutoutAvoidance(
                avoidDisplayCutout = false,
                windowConstrained = false
            )
        )
    }

    @Test
    fun mergeViewportInsets_preservesIndependentGameCrops() {
        assertEquals(
            RenderViewportInsets(left = 72, top = 12, right = 96, bottom = 0),
            RenderSurfaceManager.mergeViewportInsets(
                RenderViewportInsets(right = 96),
                RenderViewportInsets(left = 72, top = 12)
            )
        )
    }

    @Test
    fun resolvePostBootSurfaceSoftRefreshBlocker_prioritizesImeActivity() {
        assertEquals(
            "ime_active",
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = true,
                hasWindowFocus = true,
                hasCurrentSurface = true,
                softKeyboardSessionActive = true
            )
        )
    }

    @Test
    fun resolvePostBootSurfaceSoftRefreshBlocker_reportsForegroundAndSurfaceReadiness() {
        assertEquals(
            "not_ready_foreground",
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = false,
                hasWindowFocus = true,
                hasCurrentSurface = true,
                softKeyboardSessionActive = false
            )
        )
        assertEquals(
            "surface_unavailable",
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = true,
                hasWindowFocus = true,
                hasCurrentSurface = false,
                softKeyboardSessionActive = false
            )
        )
        assertEquals(
            null,
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = true,
                hasWindowFocus = true,
                hasCurrentSurface = true,
                softKeyboardSessionActive = false
            )
        )
    }
}
