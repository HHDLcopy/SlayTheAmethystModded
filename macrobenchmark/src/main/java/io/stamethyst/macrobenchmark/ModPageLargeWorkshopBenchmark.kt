package io.stamethyst.macrobenchmark

import android.content.Intent
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ModPageLargeWorkshopBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun enterModsPageFromWorkshopPageWith400ModsAndCompletedTasks() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric(
                sectionName = TRACE_SECTION_ENTER_MODS,
                mode = TraceSectionMetric.Mode.First,
                label = "modPage400WorkshopTasksEnter",
                targetPackageOnly = false
            )
        ),
        compilationMode = CompilationMode.None(),
        startupMode = null,
        iterations = 3,
        setupBlock = {
            startLauncherActivity()
            assertTrue(
                "Timed out waiting for mods dock entry",
                device.wait(Until.hasObject(By.res(MODS_DOCK_ITEM_RES)), STARTUP_TIMEOUT_MS)
            )
            assertTrue(
                "Timed out waiting for workshop dock entry",
                device.wait(Until.hasObject(By.res(WORKSHOP_DOCK_ITEM_RES)), STARTUP_TIMEOUT_MS)
            )
            assertTrue(
                "Timed out waiting for stable workshop start state",
                device.wait(Until.hasObject(By.res(WORKSHOP_TRANSITION_START_READY_RES)), STARTUP_TIMEOUT_MS)
            )
            device.waitForIdle()
        }
    ) {
        val startMs = SystemClock.elapsedRealtime()
        Trace.beginSection(TRACE_SECTION_ENTER_MODS)
        try {
            val modsEntry = device.findObject(By.res(MODS_DOCK_ITEM_RES))
            assertTrue("Mods dock entry was not found", modsEntry != null)
            val modsEntryBounds = modsEntry.visibleBounds
            assertTrue(
                "Failed to click mods dock entry",
                device.click(modsEntryBounds.centerX(), modsEntryBounds.centerY())
            )
            assertTrue(
                "Timed out waiting for mods content root",
                device.wait(Until.hasObject(By.res(MODS_CONTENT_READY_RES)), PAGE_READY_TIMEOUT_MS)
            )
            assertTrue(
                "Timed out waiting for 400-mod full mount marker",
                device.wait(Until.hasObject(By.res(MODS_FULL_MOUNT_READY_RES)), PAGE_READY_TIMEOUT_MS)
            )
        } finally {
            Trace.endSection()
            Log.i(
                TAG,
                "enterModsPageFromWorkshopPageWith400ModsAndCompletedTasks durationMs=" +
                    (SystemClock.elapsedRealtime() - startMs)
            )
        }
    }

    private fun MacrobenchmarkScope.startLauncherActivity() {
        val intent = Intent().apply {
            setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.ui.main.ModPageTransitionBenchmarkActivity")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_MOD_COUNT, STRESS_MOD_COUNT)
            putExtra(EXTRA_WORKSHOP_MODS, true)
            putExtra(EXTRA_COMPLETED_DOWNLOAD_TASK_COUNT, STRESS_COMPLETED_DOWNLOAD_TASK_COUNT)
            putExtra(EXTRA_DOWNLOAD_TASK_LOG_BYTES, STRESS_DOWNLOAD_TASK_LOG_BYTES)
            putExtra(EXTRA_INITIAL_PAGE, PAGE_WORKSHOP)
            putExtra(EXTRA_FULL_MOUNT_MARKER, true)
        }
        startActivityAndWait(intent)
    }

    private companion object {
        private const val TAG = "ModPageLargeWorkshop"
        private const val TARGET_PACKAGE = "io.stamethyst"
        private const val STRESS_MOD_COUNT = 400
        private const val STRESS_COMPLETED_DOWNLOAD_TASK_COUNT = 400
        private const val STRESS_DOWNLOAD_TASK_LOG_BYTES = 4 * 1024
        private const val STARTUP_TIMEOUT_MS = 60_000L
        private const val PAGE_READY_TIMEOUT_MS = 60_000L
        private const val TRACE_SECTION_ENTER_MODS = "modPage400WorkshopTasksEnter"
        private const val MODS_DOCK_ITEM_RES = "launcher_dock_item_Mods"
        private const val WORKSHOP_DOCK_ITEM_RES = "launcher_dock_item_Workshop"
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
