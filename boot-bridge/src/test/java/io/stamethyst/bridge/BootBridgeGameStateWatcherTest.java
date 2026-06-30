package io.stamethyst.bridge;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootBridgeGameStateWatcherTest {
    @Test
    public void charSelectDoesNotReportReadyBeforeStartupVisualSignal() {
        BootBridgeGameStateProbe.Snapshot snapshot = new BootBridgeGameStateProbe.Snapshot(
                "CHAR_SELECT",
                true,
                "MAIN_MENU",
                "",
                Float.NaN
        );

        assertFalse(BootBridgeGameStateWatcher.isReadyGameState(snapshot, false));
        assertTrue(BootBridgeGameStateWatcher.isReadyGameState(snapshot, true));
    }

    @Test
    public void gameplayCanReportReadyWithoutStartupVisualSignal() {
        BootBridgeGameStateProbe.Snapshot snapshot = new BootBridgeGameStateProbe.Snapshot(
                "GAMEPLAY",
                false,
                "",
                "",
                Float.NaN
        );

        assertTrue(BootBridgeGameStateWatcher.isReadyGameState(snapshot, false));
    }

    @Test
    public void emptyMenuScreenDoesNotReportReady() {
        BootBridgeGameStateProbe.Snapshot snapshot = new BootBridgeGameStateProbe.Snapshot(
                "CHAR_SELECT",
                true,
                "",
                "",
                Float.NaN
        );

        assertFalse(BootBridgeGameStateWatcher.isReadyGameState(snapshot, true));
    }
}
