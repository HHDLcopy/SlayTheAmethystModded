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
 *
 * <h3>Agent play-mode integration</h3>
 * <p>When a {@code PlayMonitor} is attached via agentmain, normal autoplay reads its
 * {@code mode} field:
 * <ul>
 *   <li>{@code AUTONOMOUS} - autoplay runs normally; agent commands on the queue are
 *       consumed as a side effect but do not replace autonomous decisions.</li>
 *   <li>{@code COMMAND_DRIVEN} - autoplay only consumes the command queue and never
 *       takes autonomous actions.</li>
 * </ul>
 * <p>If {@code -Damethyst.autoplay.wait_for_agent=true} is set, normal autoplay makes
 * no autonomous decisions until the play monitor connects.
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
            if (AutoplayConfig.isSingleRoomMode()) {
                AutoplaySingleRoomRunner.tick();
                return;
            }

            String playMode = getAgentPlayMode();
            if (AutoplayConfig.isWaitForAgentEnabled() && playMode == null) {
                return;
            }
            if (playMode != null) {
                tryDispatchAgentCommand();
            }
            if (playMode != null && !"AUTONOMOUS".equals(playMode)) {
                return;
            }
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
                + " mode=" + AutoplayConfig.getMode()
                + " saveMode=" + AutoplayConfig.getSaveMode()
                + " singleRoomSpec=" + AutoplayConfig.getSingleRoomSpecPath()
                + " debugLog=" + AutoplayConfig.isDebugLogEnabled()
                + " waitForAgent=" + AutoplayConfig.isWaitForAgentEnabled()
        );
    }

    private static String getAgentPlayMode() {
        try {
            Class<?> playCls = Class.forName("io.stamethyst.probe.monitors.impl.PlayMonitor");
            Object inst = playCls.getField("INSTANCE").get(null);
            if (inst == null) {
                return null;
            }
            Object mode = playCls.getMethod("getMode").invoke(inst);
            return mode != null ? mode.toString() : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean tryDispatchAgentCommand() {
        try {
            Class<?> playCls = Class.forName("io.stamethyst.probe.monitors.impl.PlayMonitor");
            Object inst = playCls.getField("INSTANCE").get(null);
            if (inst == null) {
                return false;
            }
            Object cmd = playCls.getMethod("pollAndExecute").invoke(inst);
            return cmd != null;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
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
