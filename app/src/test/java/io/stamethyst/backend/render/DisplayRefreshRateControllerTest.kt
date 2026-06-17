package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayRefreshRateControllerTest {
    @Test
    fun resolveWindowRefreshPreference_requests60HzWhenTargetIs60Fps() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 60,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 120f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 60f,
                preferredDisplayModeId = null
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_mapsSub60TargetsTo60Hz() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 30,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 120f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 60f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 60f,
                preferredDisplayModeId = 2
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_canSwitchDownTo60HzSameSizeMode() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 60,
            currentDisplayModeId = 2,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 120f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 60f,
                preferredDisplayModeId = 1
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_prefersSameSizeHighRefreshModeId() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 120f),
                mode(modeId = 3, width = 1920, height = 864, refreshRateHz = 144f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = 2
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_canSwitchDownToHighRefreshTargetMode() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120,
            currentDisplayModeId = 2,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 120f),
                mode(modeId = 2, width = 2400, height = 1080, refreshRateHz = 240f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = 1
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_fallsBackToTargetRefreshWhenDisplayModesLookStuckAt60() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = null
            ),
            preference
        )
    }

    @Test
    fun resolveWindowRefreshPreference_usesGlobalHighRefreshHintWhenOnlyOtherSizesExposeIt() {
        val preference = DisplayRefreshRateController.resolveWindowRefreshPreference(
            targetFpsLimit = 120,
            currentDisplayModeId = 1,
            supportedModes = listOf(
                mode(modeId = 1, width = 2400, height = 1080, refreshRateHz = 60f),
                mode(modeId = 2, width = 1920, height = 864, refreshRateHz = 120f)
            )
        )

        assertEquals(
            WindowRefreshPreference(
                preferredRefreshRateHz = 120f,
                preferredDisplayModeId = null
            ),
            preference
        )
    }

    private fun mode(
        modeId: Int,
        width: Int,
        height: Int,
        refreshRateHz: Float
    ) = DisplayModeCandidate(
        modeId = modeId,
        width = width,
        height = height,
        refreshRateHz = refreshRateHz
    )
}
