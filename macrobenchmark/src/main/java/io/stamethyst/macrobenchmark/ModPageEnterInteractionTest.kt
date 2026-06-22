package io.stamethyst.macrobenchmark

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModPageEnterInteractionTest {
    @Test
    fun enterModsPageRealisticWorkshopAndDownloadTaskStress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val durations = LongArray(ITERATIONS)

        repeat(ITERATIONS) { index ->
            device.executeShellCommand(
                "am start -S -W -n $TARGET_PACKAGE/.ui.main.ModPageTransitionBenchmarkActivity " +
                    "--ei $EXTRA_MOD_COUNT $STRESS_MOD_COUNT " +
                    "--ez $EXTRA_WORKSHOP_MODS true " +
                    "--ei $EXTRA_COMPLETED_DOWNLOAD_TASK_COUNT $STRESS_COMPLETED_DOWNLOAD_TASK_COUNT " +
                    "--ei $EXTRA_DOWNLOAD_TASK_LOG_BYTES $STRESS_DOWNLOAD_TASK_LOG_BYTES " +
                    "--es $EXTRA_INITIAL_PAGE $PAGE_WORKSHOP " +
                    "--ez $EXTRA_FULL_MOUNT_MARKER true"
            )
            assertTrue(
                "Timed out waiting for mods dock entry",
                device.wait(Until.hasObject(By.res(MODS_DOCK_ITEM_RES)), STARTUP_TIMEOUT_MS)
            )
            assertTrue(
                "Timed out waiting for stable workshop start state",
                device.wait(Until.hasObject(By.res(WORKSHOP_TRANSITION_START_READY_RES)), STARTUP_TIMEOUT_MS)
            )
            device.waitForIdle()

            val modsEntry = device.findObject(By.res(MODS_DOCK_ITEM_RES))
                ?: throw AssertionError("Mods dock entry was not found")
            var startMs = SystemClock.elapsedRealtime()
            modsEntry.click()
            var modsContentReady = device.wait(
                Until.hasObject(By.res(MODS_CONTENT_READY_RES)),
                CLICK_ACK_TIMEOUT_MS,
            )
            if (!modsContentReady) {
                val fallbackBounds = device.findObject(By.res(MODS_DOCK_ITEM_RES))?.visibleBounds
                    ?: modsEntry.visibleBounds
                Log.w(TAG, "mods dock object click did not navigate; falling back to input tap")
                startMs = SystemClock.elapsedRealtime()
                device.executeShellCommand("input tap ${fallbackBounds.centerX()} ${fallbackBounds.centerY()}")
                modsContentReady = device.wait(
                    Until.hasObject(By.res(MODS_CONTENT_READY_RES)),
                    PAGE_READY_TIMEOUT_MS,
                )
            }
            assertTrue("Timed out waiting for mods content", modsContentReady)
            assertTrue(
                "Timed out waiting for realistic 400-mod and download-task mount marker",
                device.wait(Until.hasObject(By.res(MODS_FULL_MOUNT_READY_RES)), PAGE_READY_TIMEOUT_MS)
            )
            durations[index] = SystemClock.elapsedRealtime() - startMs
            instrumentation.waitForIdleSync()
            Log.i(
                TAG,
                "enterModsPageRealisticWorkshopAndDownloadTaskStress " +
                    "iteration=${index + 1} durationMs=${durations[index]}"
            )
        }

        val sorted = durations.sorted()
        Log.i(
            TAG,
            "enterModsPageRealisticWorkshopAndDownloadTaskStress summary iterations=$ITERATIONS " +
                "minMs=${sorted.first()} medianMs=${sorted[ITERATIONS / 2]} maxMs=${sorted.last()} " +
                "allMs=${durations.joinToString(prefix = "[", postfix = "]")}")
    }

    private companion object {
        private const val TAG = "ModPageEnterInteraction"
        private const val TARGET_PACKAGE = "io.stamethyst"
        private const val STRESS_MOD_COUNT = 400
        private const val STRESS_COMPLETED_DOWNLOAD_TASK_COUNT = 400
        private const val STRESS_DOWNLOAD_TASK_LOG_BYTES = 4 * 1024
        private const val ITERATIONS = 5
        private const val STARTUP_TIMEOUT_MS = 60_000L
        private const val CLICK_ACK_TIMEOUT_MS = 3_000L
        private const val PAGE_READY_TIMEOUT_MS = 60_000L
        private const val MODS_DOCK_ITEM_RES = "launcher_dock_item_Mods"
        private const val WORKSHOP_TRANSITION_START_READY_RES = "workshop_transition_start_ready"
        private const val MODS_CONTENT_READY_RES = "mods_content_ready"
        private const val MODS_FULL_MOUNT_READY_RES = "mods_transition_full_mount_ready"
        private const val EXTRA_MOD_COUNT = "io.stamethyst.benchmark.extra.MOD_COUNT"
        private const val EXTRA_WORKSHOP_MODS = "io.stamethyst.benchmark.extra.WORKSHOP_MODS"
        private const val EXTRA_COMPLETED_DOWNLOAD_TASK_COUNT =
            "io.stamethyst.benchmark.extra.COMPLETED_DOWNLOAD_TASK_COUNT"
        private const val EXTRA_DOWNLOAD_TASK_LOG_BYTES = "io.stamethyst.benchmark.extra.DOWNLOAD_TASK_LOG_BYTES"
        private const val EXTRA_INITIAL_PAGE = "io.stamethyst.benchmark.extra.INITIAL_PAGE"
        private const val EXTRA_FULL_MOUNT_MARKER = "io.stamethyst.benchmark.extra.FULL_MOUNT_MARKER"
        private const val PAGE_WORKSHOP = "Workshop"
    }
}
