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
 * <p>When a {@code PlayMonitorAgent} is attached via agentmain, the driver reads its
 * {@code mode} field:
 * <ul>
 *   <li>{@code AUTONOMOUS} — autoplay runs normally; agent commands on the queue are
 *       consumed as a side-effect but don't replace autonomous decisions.</li>
 *   <li>{@code COMMAND_DRIVEN} — autoplay only consumes the command queue and never
 *       takes autonomous actions.</li>
 * </ul>
 * <p>If {@code -Damethyst.autoplay.wait_for_agent=true} is set, the driver makes
 * NO autonomous decisions until the play monitor connects (mode != null).
 */
public final class AutoplayDriver {
    private static volatile boolean bannerLogged;
    private static long lastTickMillis;

    private AutoplayDriver() {}

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
            Object playMode = getAgentPlayMode();

            // wait_for_agent: hold still until agent connects
            if (AutoplayConfig.isWaitForAgentEnabled() && playMode == null) {
                return;
            }

            // Agent present → try consuming a command first
            if (playMode != null) {
                tryDispatchAgentCommand();
            }

            // AUTONOMOUS mode (or no agent): also run autonomous logic
            if (playMode == null || "AUTONOMOUS".equals(playMode)) {
                AutoplayMainMenuActions.tick();
                AutoplayEndScreenActions.tick();
                AutoplayDungeonActions.tick();
            }
            // COMMAND_DRIVEN: agent controls everything — nothing else to do
        } catch (Throwable t) {
            AutoplayLog.warn("driver tick threw, will retry next interval", t);
        }
    }

    /**
     * Read the current play mode from PlayMonitorAgent.INSTANCE.
     * Returns the mode name string ("AUTONOMOUS" / "COMMAND_DRIVEN") or null if
     * the monitor is not attached.
     */
    private static String getAgentPlayMode() {
        try {
            Class<?> playCls = Class.forName("io.stamethyst.agent.monitors.impl.PlayMonitorAgent");
            Object inst = playCls.getField("INSTANCE").get(null);
            if (inst == null) return null;
            Object mode = playCls.getMethod("getMode").invoke(inst);
            return mode != null ? mode.toString() : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Poll the command queue and execute one command on this game thread.
     * Returns true if a command was dispatched, false if the queue was empty.
     */
    private static boolean tryDispatchAgentCommand() {
        try {
            Class<?> playCls = Class.forName("io.stamethyst.agent.monitors.impl.PlayMonitorAgent");
            Object inst = playCls.getField("INSTANCE").get(null);
            if (inst == null) return false;
            Object cmd = playCls.getMethod("pollAndExecute").invoke(inst);
            return cmd != null;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void logBannerOnce() {
        if (bannerLogged) {
            return;
        }
        bannerLogged = true;
        AutoplayLog.info(
            "autoplay engaged tickIntervalMs=" + AutoplayConfig.getTickIntervalMs()
                + " debugLog=" + AutoplayConfig.isDebugLogEnabled()
                + " waitForAgent=" + AutoplayConfig.isWaitForAgentEnabled()
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
