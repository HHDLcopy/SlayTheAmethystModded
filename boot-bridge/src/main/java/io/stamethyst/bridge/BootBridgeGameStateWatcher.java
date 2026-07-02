package io.stamethyst.bridge;

final class BootBridgeGameStateWatcher {
    private static final long WATCHER_POLL_MS = 120L;
    private static final int READY_CONFIRM_TICKS = 3;
    private static final int CONSOLE_FALLBACK_FAIL_TICKS = 90;
    private static final int CONSOLE_FALLBACK_WITHOUT_SPLASH_TICKS = 200;
    private static final int CACHE_SPLASH_VISIBLE_CONFIRM_TICKS = 4;
    private static final float SPLASH_VISIBLE_ALPHA_THRESHOLD = 0.06f;
    private static final float CACHE_SPLASH_VISIBLE_ALPHA_THRESHOLD = 0.85f;
    private static final String PROPERTY_PATCH_CACHE_CURRENT = "amethyst.mts.patch_cache.current";

    private final BootBridgeReporter reporter;
    private final BootBridgeGameStateProbe probe = new BootBridgeGameStateProbe();

    private BootBridgeGameStateWatcher(BootBridgeReporter reporter) {
        this.reporter = reporter;
    }

    static void start(BootBridgeReporter reporter) {
        new BootBridgeGameStateWatcher(reporter).startThread();
    }

    private void startThread() {
        Thread watcher = new Thread(this::runLoop, "Amethyst-BootBridge-MenuWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void runLoop() {
        int readyTicks = 0;
        int reflectionFailureTicks = 0;
        int splashVisibleTicks = 0;
        boolean splashSignaledInCurrentSplash = false;
        boolean startupVisualSignalSeen = false;
        boolean patchCacheCurrentLaunch = isPatchCacheCurrentLaunch();
        String lastMainMenuScreen = "";

        while (!reporter.isReadySent() && !reporter.isFailSent()) {
            try {
                BootBridgeGameStateProbe.Snapshot snapshot = probe.readSnapshot();
                reflectionFailureTicks = 0;
                if (snapshot == null) {
                    readyTicks = 0;
                    splashVisibleTicks = 0;
                    splashSignaledInCurrentSplash = false;
                    lastMainMenuScreen = "";
                    sleepQuietly(WATCHER_POLL_MS);
                    continue;
                }

                if ("SPLASH".equals(snapshot.modeName)) {
                    if (isSplashLogoVisible(snapshot, patchCacheCurrentLaunch)) {
                        splashVisibleTicks += 1;
                    } else {
                        splashVisibleTicks = 0;
                    }
                    if (!splashSignaledInCurrentSplash &&
                            shouldSignalSplash(snapshot, patchCacheCurrentLaunch, splashVisibleTicks)) {
                        reporter.splash(BootBridgeStartupMessage.key("game_splash"));
                        splashSignaledInCurrentSplash = true;
                        startupVisualSignalSeen = true;
                    }
                } else {
                    splashVisibleTicks = 0;
                    splashSignaledInCurrentSplash = false;
                }

                if (isReadyGameState(snapshot, startupVisualSignalSeen)) {
                    readyTicks += 1;
                    if (readyTicks >= READY_CONFIRM_TICKS) {
                        reporter.ready(BootBridgeStartupMessage.key("game_ready"));
                        return;
                    }
                } else {
                    readyTicks = 0;
                }

                if ("CHAR_SELECT".equals(snapshot.modeName) && snapshot.hasMainMenuScreen) {
                    String screen = snapshot.menuScreenName == null ? "" : snapshot.menuScreenName;
                    if (!screen.equals(lastMainMenuScreen)) {
                        reporter.phase(97, BootBridgeStartupMessage.key("main_menu_ready"));
                        lastMainMenuScreen = screen;
                    }
                } else {
                    lastMainMenuScreen = "";
                }
            } catch (Throwable ignored) {
                readyTicks = 0;
                splashVisibleTicks = 0;
                reflectionFailureTicks += 1;
                if (!reporter.hasConsoleReadyHint()) {
                    sleepQuietly(WATCHER_POLL_MS);
                    continue;
                }
                if (startupVisualSignalSeen && reflectionFailureTicks >= CONSOLE_FALLBACK_FAIL_TICKS) {
                    reporter.ready(BootBridgeStartupMessage.key("game_ready"));
                    return;
                }
                if (reflectionFailureTicks >= CONSOLE_FALLBACK_WITHOUT_SPLASH_TICKS) {
                    reporter.splash(BootBridgeStartupMessage.key("game_splash"));
                    reporter.ready(BootBridgeStartupMessage.key("game_ready"));
                    return;
                }
            }
            sleepQuietly(WATCHER_POLL_MS);
        }
    }

    static boolean shouldSignalSplash(
            BootBridgeGameStateProbe.Snapshot snapshot,
            boolean patchCacheCurrentLaunch,
            int visibleTicks
    ) {
        if (!isSplashLogoVisible(snapshot, patchCacheCurrentLaunch)) {
            return false;
        }
        if (!patchCacheCurrentLaunch) {
            return true;
        }
        return visibleTicks >= CACHE_SPLASH_VISIBLE_CONFIRM_TICKS;
    }

    static boolean isReadyGameState(
            BootBridgeGameStateProbe.Snapshot snapshot,
            boolean startupVisualSignalSeen
    ) {
        if (snapshot == null) {
            return false;
        }
        if ("GAMEPLAY".equals(snapshot.modeName) || "DUNGEON_TRANSITION".equals(snapshot.modeName)) {
            return true;
        }
        if (!startupVisualSignalSeen) {
            return false;
        }
        if (!"CHAR_SELECT".equals(snapshot.modeName)) {
            return false;
        }
        if (!snapshot.hasMainMenuScreen) {
            return false;
        }
        if (snapshot.menuScreenName == null || snapshot.menuScreenName.isEmpty()) {
            return false;
        }
        return !"NONE".equals(snapshot.menuScreenName);
    }

    static boolean isSplashLogoVisible(
            BootBridgeGameStateProbe.Snapshot snapshot,
            boolean patchCacheCurrentLaunch
    ) {
        if (snapshot == null || !"SPLASH".equals(snapshot.modeName)) {
            return false;
        }
        String phase = snapshot.splashPhaseName == null ? "" : snapshot.splashPhaseName;
        if ("INIT".equals(phase)) {
            return false;
        }
        if (Float.isNaN(snapshot.splashLogoAlpha)) {
            // If alpha introspection is unavailable, use phase-only detection.
            return true;
        }
        float threshold = patchCacheCurrentLaunch ?
                CACHE_SPLASH_VISIBLE_ALPHA_THRESHOLD :
                SPLASH_VISIBLE_ALPHA_THRESHOLD;
        return snapshot.splashLogoAlpha >= threshold;
    }

    private static boolean isPatchCacheCurrentLaunch() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY_PATCH_CACHE_CURRENT, "false"));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
