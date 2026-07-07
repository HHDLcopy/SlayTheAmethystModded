package io.stamethyst.ui.workshop

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkshopYouTubePlayerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var resultFile: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext.applicationContext
        device = UiDevice.getInstance(instrumentation)
        resultFile = WorkshopYouTubePlaybackTestHost.resultFile(context)
        resultFile.delete()
    }

    @After
    fun tearDown() {
        runCatching { device.pressHome() }
    }

    @Test
    fun youtubeEmbedLoadsAndReportsPlayingState() {
        prepareDeviceForPlaybackHost()
        val launchResult = launchPlaybackHost()
        assertTrue(
            "Workshop YouTube playback host did not launch. launchResult=$launchResult",
            launchResult.contains("Status: ok") &&
                launchResult.contains(WorkshopYouTubeTestActivity::class.java.simpleName),
        )

        val result = waitForPlaybackResult()
        assertTrue(
            "YouTube player did not reach PLAYING state. launchResult=$launchResult result=$result",
            result.lineSequence().any { it == "status=${WorkshopYouTubePlaybackTestHost.STATUS_PLAYING}" },
        )
    }

    private fun launchPlaybackHost(): String {
        val component = "${context.packageName}/${WorkshopYouTubeTestActivity::class.java.name}"
        return device.executeShellCommand(
            "am start -W -n $component " +
                "--es ${WorkshopYouTubePlaybackTestHost.EXTRA_VIDEO_ID} $DOWNFALL_WORKSHOP_VIDEO_ID",
        )
    }

    private fun prepareDeviceForPlaybackHost() {
        runCatching { device.wakeUp() }
        runCatching { device.executeShellCommand("cmd power wakeup 0") }
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
        runCatching { device.executeShellCommand("cmd statusbar collapse") }
    }

    private fun waitForPlaybackResult(): String {
        val deadline = System.currentTimeMillis() + PLAYBACK_RESULT_TIMEOUT_MS
        var latest = ""
        var tappedPlayer = false
        while (System.currentTimeMillis() < deadline) {
            if (resultFile.isFile) {
                latest = resultFile.readText()
                if (!tappedPlayer && latest.contains(WORKSHOP_YOUTUBE_READY_CONSOLE_MESSAGE)) {
                    tappedPlayer = true
                    device.click(device.displayWidth / 2, device.displayHeight / 2)
                }
                if (
                    latest.lineSequence().any {
                        it == "status=${WorkshopYouTubePlaybackTestHost.STATUS_PLAYING}" ||
                            it == "status=${WorkshopYouTubePlaybackTestHost.STATUS_ERROR}" ||
                            it == "status=${WorkshopYouTubePlaybackTestHost.STATUS_TIMEOUT}"
                    }
                ) {
                    return latest
                }
            }
            Thread.sleep(250L)
        }
        return latest.ifBlank { "<no result file at ${resultFile.absolutePath}>" }
    }

    private companion object {
        private const val DOWNFALL_WORKSHOP_VIDEO_ID = "vYthsh8a1Dc"
        private const val PLAYBACK_RESULT_TIMEOUT_MS = 60_000L
    }
}
