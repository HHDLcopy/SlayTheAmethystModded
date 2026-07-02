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

    @Test
    public void normalLaunchReportsSplashOnEarlyVisibleLogo() {
        BootBridgeGameStateProbe.Snapshot snapshot = new BootBridgeGameStateProbe.Snapshot(
                "SPLASH",
                false,
                "",
                "BOUNCE",
                0.08f
        );

        assertTrue(BootBridgeGameStateWatcher.isSplashLogoVisible(snapshot, false));
        assertTrue(BootBridgeGameStateWatcher.shouldSignalSplash(snapshot, false, 1));
    }

    @Test
    public void cacheLaunchRejectsEarlySplashLogoAlpha() {
        BootBridgeGameStateProbe.Snapshot snapshot = new BootBridgeGameStateProbe.Snapshot(
                "SPLASH",
                false,
                "",
                "BOUNCE",
                0.5f
        );

        assertFalse(BootBridgeGameStateWatcher.isSplashLogoVisible(snapshot, true));
        assertFalse(BootBridgeGameStateWatcher.shouldSignalSplash(snapshot, true, 10));
    }

    @Test
    public void cacheLaunchRequiresConfirmedVisibleSplashBeforeSignal() {
        BootBridgeGameStateProbe.Snapshot snapshot = new BootBridgeGameStateProbe.Snapshot(
                "SPLASH",
                false,
                "",
                "FADE",
                0.9f
        );

        assertTrue(BootBridgeGameStateWatcher.isSplashLogoVisible(snapshot, true));
        assertFalse(BootBridgeGameStateWatcher.shouldSignalSplash(snapshot, true, 3));
        assertTrue(BootBridgeGameStateWatcher.shouldSignalSplash(snapshot, true, 4));
    }
}
