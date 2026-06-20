package io.stamethyst.compatmod.autoplay;

/**
 * Top-level autoplay tick dispatcher. Patched in via {@link AutoplayPatches}, this class is the
 * single entry point that the per-frame patch calls. All routing decisions (main menu vs dungeon
 * vs combat vs run-end screens) live here and in the matching
 * {@code AutoplayMainMenuActions} / {@code AutoplayDungeonActions} /
 * {@code AutoplayEndScreenActions} helpers.
 *
 * <p>The driver:
 * <ul>
 *   <li>Reads {@code -Damethyst.debug.autoplay} on init and short-circuits when not enabled.</li>
 *   <li>Throttles ticks so we don't mutate game state every frame.</li>
 *   <li>Logs a one-time startup banner so it's obvious in logs that autoplay engaged.</li>
 *   <li>Catches Throwable from each tick so a runtime mismatch (e.g. a mod renaming a field) never
 *       crashes the engine; we just log and try again next tick.</li>
 * </ul>
 */
public final class AutoplayDriver {
    private static volatile boolean bannerLogged;
    private static long lastTickMillis;

    private AutoplayDriver() {
    }

    /** Called after every {@code CardCrawlGame.update()} tick. */
    public static void onCardCrawlGameUpdate() {
        if (!AutoplayConfig.isEnabled()) {
            return;
        }
        logBannerOnce();
        if (!shouldTickNow()) {
            return;
        }
        try {
            AutoplayMainMenuActions.tick();
            AutoplayEndScreenActions.tick();
            AutoplayDungeonActions.tick();
        } catch (Throwable t) {
            AutoplayLog.warn("driver tick threw, will retry next interval", t);
        }
    }

    private static void logBannerOnce() {
        if (bannerLogged) {
            return;
        }
        bannerLogged = true;
        AutoplayLog.info(
            "autoplay engaged tickIntervalMs=" + AutoplayConfig.getTickIntervalMs()
                + " saveMode=" + AutoplayConfig.getSaveMode()
                + " debugLog=" + AutoplayConfig.isDebugLogEnabled()
        );
    }

    private static boolean shouldTickNow() {
        long now = currentTimeMillis();
        long interval = AutoplayConfig.getTickIntervalMs();
        if (now - lastTickMillis < interval) {
            return false;
        }
        lastTickMillis = now;
        return true;
    }

    private static long currentTimeMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
