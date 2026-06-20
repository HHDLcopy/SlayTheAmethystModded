package io.stamethyst.compatmod.autoplay;

/**
 * Reads launcher-injected configuration for the bundled autoplay driver.
 *
 * <p>The autoplay driver is gated by {@code -Damethyst.debug.autoplay=true}. The
 * launcher only sets this property when the user explicitly opts into the special
 * autoplay launch mode (see the Gradle {@code stsStartAutoplay} task), so on
 * regular launches every driver hook short-circuits immediately.</p>
 */
public final class AutoplayConfig {
    /** Master switch — autoplay only runs when this property is {@code true}. */
    public static final String AUTOPLAY_ENABLED_PROP = "amethyst.debug.autoplay";
    /** Minimum delay between driver ticks. Avoids burning the engine with state changes. */
    public static final String AUTOPLAY_TICK_INTERVAL_MS_PROP =
        "amethyst.debug.autoplay.tick_interval_ms";
    /** Optional verbose logging toggle. */
    public static final String AUTOPLAY_DEBUG_PROP = "amethyst.debug.autoplay.debug";
    /** When true, the driver makes no autonomous decisions until an agent
     *  PlayMonitorAgent connects.  Useful for harness demo runs. */
    public static final String AUTOPLAY_WAIT_FOR_AGENT_PROP =
        "amethyst.autoplay.wait_for_agent";

    private static final long DEFAULT_TICK_INTERVAL_MS = 250L;
    private static final long MIN_TICK_INTERVAL_MS = 50L;
    private static final long MAX_TICK_INTERVAL_MS = 5000L;

    private static final boolean ENABLED =
        readBoolean(AUTOPLAY_ENABLED_PROP, false);
    private static final boolean DEBUG_LOG_ENABLED =
        readBoolean(AUTOPLAY_DEBUG_PROP, false);
    private static final boolean WAIT_FOR_AGENT =
        readBoolean(AUTOPLAY_WAIT_FOR_AGENT_PROP, false);
    private static final long TICK_INTERVAL_MS =
        clampLong(readLong(AUTOPLAY_TICK_INTERVAL_MS_PROP, DEFAULT_TICK_INTERVAL_MS),
            MIN_TICK_INTERVAL_MS, MAX_TICK_INTERVAL_MS);

    private AutoplayConfig() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean isDebugLogEnabled() {
        return DEBUG_LOG_ENABLED;
    }

    public static long getTickIntervalMs() {
        return TICK_INTERVAL_MS;
    }

    public static boolean isWaitForAgentEnabled() {
        return WAIT_FOR_AGENT;
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(configured) || "1".equals(configured)
            || "on".equalsIgnoreCase(configured) || "yes".equalsIgnoreCase(configured)) {
            return true;
        }
        if ("false".equalsIgnoreCase(configured) || "0".equals(configured)
            || "off".equalsIgnoreCase(configured) || "no".equalsIgnoreCase(configured)) {
            return false;
        }
        return defaultValue;
    }

    private static long readLong(String key, long defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        try {
            return Long.parseLong(configured);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long clampLong(long value, long min, long max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
