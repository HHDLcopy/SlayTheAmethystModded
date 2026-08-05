package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EasyTierDiagnosticsCaptureTest {
    @Before
    fun resetTransitionFilter() {
        EasyTierDiagnosticsStore.resetArchivedStatusForTest()
    }

    @Test
    fun shouldArchive_capturesDisconnectAndReconnect_notJustFailure() {
        // The reported disconnect landed on DISCONNECTED, which previously produced no history file
        // at all and left the bundle with nothing to diagnose.
        assertTrue(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = EasyTierConnectionStatus.CONNECTED,
                status = EasyTierConnectionStatus.DISCONNECTED,
            )
        )
        assertTrue(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = EasyTierConnectionStatus.CONNECTED,
                status = EasyTierConnectionStatus.RECONNECTING,
            )
        )
        assertTrue(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = EasyTierConnectionStatus.CONNECTING,
                status = EasyTierConnectionStatus.FAILED,
            )
        )
    }

    @Test
    fun shouldArchive_ignoresRepeatsSoThePollLoopCannotEvictHistory() {
        // The status poll persists a snapshot every ~5s. Archiving each one would burn all five
        // slots within half a minute and discard the transition that explains the disconnect.
        assertFalse(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = EasyTierConnectionStatus.RECONNECTING,
                status = EasyTierConnectionStatus.RECONNECTING,
            )
        )
        assertFalse(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = EasyTierConnectionStatus.DISCONNECTED,
                status = EasyTierConnectionStatus.DISCONNECTED,
            )
        )
    }

    @Test
    fun shouldArchive_skipsHealthyStates() {
        assertFalse(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = EasyTierConnectionStatus.CONNECTING,
                status = EasyTierConnectionStatus.CONNECTED,
            )
        )
        assertFalse(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = null,
                status = EasyTierConnectionStatus.IDLE,
            )
        )
    }

    @Test
    fun shouldArchive_recordsFirstStateAfterProcessRestart() {
        // A null previous status means this process just started. If :easytier was killed and
        // restarted mid-session, the first state it reports is precisely the evidence of that cycle.
        assertTrue(
            EasyTierDiagnosticsStore.shouldArchive(
                previousStatus = null,
                status = EasyTierConnectionStatus.RECONNECTING,
            )
        )
    }

    @Test
    fun recordStateTransition_keepsDisconnectHistoryAcrossRepeatedPolls() {
        val roots = EasyTierTestRoots.create("easytier-capture")
        try {
            val connected = EasyTierSessionController.buildInitialSnapshot(
                roots.context,
                EasyTierConfigRepository.current(),
            ).copy(
                status = EasyTierConnectionStatus.CONNECTED,
                sessionId = "sess-capture",
                roomId = "room-capture",
                lastUpdatedAtMs = 1_000L,
            )
            EasyTierDiagnosticsStore.recordStateTransition(roots.context, connected)

            val disconnected = connected.copy(
                status = EasyTierConnectionStatus.DISCONNECTED,
                lastUpdatedAtMs = 2_000L,
            )
            EasyTierDiagnosticsStore.recordStateTransition(roots.context, disconnected)
            // Simulate the poll loop re-persisting the same state repeatedly.
            repeat(8) { iteration ->
                EasyTierDiagnosticsStore.recordStateTransition(
                    roots.context,
                    disconnected.copy(lastUpdatedAtMs = 3_000L + iteration),
                )
            }

            // Count only archived events. The atomic writer leaves a sibling ".bak" next to each
            // file, and pruneHistory filters on the same "event-" prefix.
            val historyFiles = EasyTierDiagnosticsStore.eventHistoryDir(roots.context)
                .listFiles()
                ?.filter { it.isFile && it.name.startsWith("event-") && !it.name.endsWith(".bak") }
                .orEmpty()
            assertEquals(
                "Repeated polls must not create extra slots: ${historyFiles.map { it.name }}",
                1,
                historyFiles.size,
            )
            assertTrue(historyFiles.single().name.startsWith("event-disconnected-"))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun isEventHistoryFile_excludesAtomicWriterBackupsFromSlotAccounting() {
        // Backups also start with "event-". Counting them as slots would halve the retained history.
        assertTrue(EasyTierDiagnosticsStore.isEventHistoryFile("event-disconnected-20260805-081900-000.txt"))
        assertFalse(
            EasyTierDiagnosticsStore.isEventHistoryFile("event-disconnected-20260805-081900-000.txt.bak")
        )
    }

    @Test
    fun recordStateTransition_retainsFiveDistinctEventsAcrossReconnectCycles() {
        val roots = EasyTierTestRoots.create("easytier-slots")
        try {
            val base = EasyTierSessionController.buildInitialSnapshot(
                roots.context,
                EasyTierConfigRepository.current(),
            ).copy(sessionId = "sess-slots", roomId = "room-slots")

            // Six disconnect/reconnect cycles: the newest five must survive pruning.
            repeat(6) { cycle ->
                EasyTierDiagnosticsStore.recordStateTransition(
                    roots.context,
                    base.copy(
                        status = EasyTierConnectionStatus.RECONNECTING,
                        lastUpdatedAtMs = 10_000L + cycle * 100L,
                    ),
                )
                EasyTierDiagnosticsStore.recordStateTransition(
                    roots.context,
                    base.copy(
                        status = EasyTierConnectionStatus.CONNECTED,
                        lastUpdatedAtMs = 10_050L + cycle * 100L,
                    ),
                )
            }

            val historyFiles = EasyTierDiagnosticsStore.eventHistoryDir(roots.context)
                .listFiles()
                ?.filter { it.isFile && EasyTierDiagnosticsStore.isEventHistoryFile(it.name) }
                .orEmpty()
            assertEquals(
                "Five slots must hold five events, not be diluted by .bak siblings: " +
                    "${historyFiles.map { it.name }}",
                5,
                historyFiles.size,
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun buildProcessExitLines_alwaysEmitsSectionAndNeverClaimsSurvival() {
        val roots = EasyTierTestRoots.create("easytier-exit-lines")
        try {
            val lines = EasyTierDiagnosticsStore.buildProcessExitLines(roots.context)
            assertTrue(lines.any { it.contains("Recent :easytier Process Exits:") })
            // The unit-test Context has no ActivityManager records, which is the same shape as a
            // device where nothing was killed. The wording must not be read as proof of survival.
            assertTrue(
                lines.any { it.contains("absence does not prove the process survived") }
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }
}
